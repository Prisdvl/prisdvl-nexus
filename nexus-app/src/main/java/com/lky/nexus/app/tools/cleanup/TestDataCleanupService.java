package com.lky.nexus.app.tools.cleanup;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.app.agent.AgentService;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.mapper.SysUserRoleMapper;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.app.auth.model.entity.SysUserRolePO;
import com.lky.nexus.app.chat.service.ChatMemoryKeys;
import com.lky.nexus.app.chat.service.ConversationService;
import com.lky.nexus.app.workspace.WorkspaceMapper;
import com.lky.nexus.app.workspace.WorkspacePO;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试数据清理的核心编排（仅 {@code cleanup} profile 生效）。
 *
 * <p><b>设计原则：一律复用既有级联，绝不裸改 deleted 标记。</b>
 * 知识库删除牵动四个存储（MySQL / pgvector / Elasticsearch / 磁盘原文），
 * 这些级联已经在 {@code KnowledgeBaseService#delete} 里实现并被测试覆盖；
 * 自己写一遍 SQL 只会得到一个"看起来删干净了、检索却还能命中"的孤儿库。
 * 因此本类在能调服务的地方一律调服务，只有服务层确实没有的能力
 * （删账号 / 删工作区 / 清 {@code SPRING_AI_CHAT_MEMORY} / 清导入任务）才直连数据库，
 * 且每一处都写明为什么。
 *
 * <p><b>两阶段：先 plan（只读）后 purge（写入）。</b>
 * {@link #plan()} 不做任何写操作，因此 dry-run 是"结构上不可能删数据"，
 * 而不是"靠一个 if 判断保证不删"。
 */
@Service
@Profile("cleanup")
public class TestDataCleanupService {

    private static final Logger log = LoggerFactory.getLogger(TestDataCleanupService.class);

    private final CleanupProperties properties;
    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final WorkspaceMapper workspaceMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ConversationService conversationService;
    private final AgentService agentService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 原始文件存储根目录（与 {@code LocalDocumentStorage} 用的是同一个属性）。
     *
     * <p>本工具只用它做<b>一致性核对</b>：库里的 {@code stored_path} 是相对路径
     * （形如 {@code kb/<kbId>/<file>}），由写入方按<b>它自己的</b> root 解析。
     * 若清理进程的 root 与写入方不是同一个目录（典型场景：服务跑在容器里、
     * 文件在 {@code /app/data} 卷，而清理在宿主用默认的 {@code ./data} 跑），
     * 那么 {@code LocalDocumentStorage.delete} 找的是另一条路径 —— 而它会吞掉 IO 异常，
     * 于是"磁盘原文已清理"变成一次<b>静默失败</b>。这里把它变成显式告警。
     */
    private final Path storageRoot;

    public TestDataCleanupService(CleanupProperties properties,
                                  SysUserMapper sysUserMapper,
                                  SysUserRoleMapper sysUserRoleMapper,
                                  WorkspaceMapper workspaceMapper,
                                  KnowledgeBaseService knowledgeBaseService,
                                  ConversationService conversationService,
                                  AgentService agentService,
                                  DataSource dataSource,
                                  @Value("${nexus.storage.root:./data}") String storageRoot) {
        this.storageRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
        this.properties = properties;
        this.sysUserMapper = sysUserMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.workspaceMapper = workspaceMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.conversationService = conversationService;
        this.agentService = agentService;
        // 刻意手工构造而不是注入 JdbcTemplate：向量库那边也声明了一个 JdbcTemplate bean，
        // 注入时存在歧义（很可能拿到 pgvector 那个连接，对着 MySQL 的表名执行必然失败）。
        // 用主数据源（PrimaryDataSourceConfig 里 @Primary 的 dataSource）显式构造最稳。
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    // ------------------------------------------------------------------
    // 计划（只读）
    // ------------------------------------------------------------------

    /**
     * 生成清理计划：只读，不产生任何写操作。
     *
     * @throws IllegalStateException 保护账号被前缀命中时（fail-closed）
     */
    public CleanupPlan plan() {
        properties.assertProtectedNotMatched();

        List<SysUserPO> all = sysUserMapper.selectList(null);
        List<AccountSnapshot> targets = new ArrayList<>();
        for (SysUserPO user : all) {
            String username = user.getUsername();
            if (properties.getProtectedUsernames().contains(username)) {
                continue;
            }
            if (!properties.matches(username)) {
                continue;
            }
            targets.add(snapshot(user.getId(), username));
        }
        return new CleanupPlan(List.copyOf(targets),
                List.copyOf(properties.getProtectedUsernames()),
                List.copyOf(properties.getUsernamePrefixes()));
    }

    private AccountSnapshot snapshot(Long userId, String username) {
        return new AccountSnapshot(
                userId,
                username,
                count("SELECT COUNT(*) FROM kb_knowledge_base WHERE user_id = ? AND deleted = 0", userId),
                count("SELECT COUNT(*) FROM chat_conversation WHERE user_id = ? AND deleted = 0", userId),
                count("SELECT COUNT(*) FROM agent WHERE owner_id = ? AND deleted = 0 AND builtin = 0", userId),
                // 内置 Agent 也必须进计划：每个账号注册时都会自动建一个 builtin.default，
                // 而 purgeBuiltinAgents 默认 true —— 也就是说它**会被真删**。
                // 早先计划里只统计 builtin = 0 的自建项，于是 dry-run 打印"自建Agent 1"，
                // 实际却删掉 4 个 Agent（1 自建 + 3 内置），"报什么删什么"当场不成立。
                count("SELECT COUNT(*) FROM agent WHERE owner_id = ? AND deleted = 0 AND builtin = 1", userId),
                count("SELECT COUNT(*) FROM workspace WHERE user_id = ? AND deleted = 0", userId),
                count("SELECT COUNT(*) FROM kb_import_task WHERE user_id = ?", userId),
                count("SELECT COUNT(*) FROM kb_qa_record WHERE user_id = ?", userId));
    }

    // ------------------------------------------------------------------
    // 执行（写入）
    // ------------------------------------------------------------------

    /**
     * 按计划执行清理。逐账号进行：单个账号失败只记录并继续，最后统一以失败数决定退出码
     * （清理一个残留集合时，"因为其中一个失败就整体回滚"没有意义——它们本就没有事务关联）。
     */
    public List<PurgeResult> purge(CleanupPlan plan) {
        properties.assertProtectedNotMatched();

        List<PurgeResult> results = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        int index = 0;
        for (AccountSnapshot target : plan.targets()) {
            index++;
            log.info("[{}/{}] 清理账号 {}", index, plan.targets().size(), target.username());
            try {
                results.add(purgeAccount(target));
            } catch (Exception e) {
                log.error("账号 {} 清理失败：{}", target.username(), e.getMessage(), e);
                failures.put(target.username(), e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new CleanupPartialFailureException(failures);
        }
        return results;
    }

    // ------------------------------------------------------------------
    // 最终一致性检查：回收"父行已不存在"的无主行
    // ------------------------------------------------------------------

    /**
     * 只读地统计无主导入任务明细的行数（给 dry-run 用，不做任何写操作）。
     */
    public int countOrphanImportTaskItems() {
        return count("SELECT COUNT(*) FROM kb_import_task_item WHERE task_id NOT IN "
                + "(SELECT id FROM kb_import_task)");
    }

    /**
     * 回收"父任务已不存在"的导入任务明细，返回回收行数。
     *
     * <p><b>为什么必须有这一步，而不是只修好逐账号删除</b>：逐账号清理只能按
     * "这个账号名下有什么"来枚举。一旦父行先没了（历史版本的关联键写错、或某次清理中途失败），
     * 剩下的子行就再也不会被任何一次按账号的枚举命中——残留就此固化。
     * 真机实测就是这么发现的：清完 78 个账号后，{@code kb_import_task_item} 里躺着
     * 69 行父任务已被删除的明细，而这 69 行的父任务早就查不到了。
     *
     * <p>本方法只覆盖这一张表，理由不是"省事"而是有证据：全库孤儿扫描（2026-10-03）
     * 显示同一时刻其它 8 类父子关系（切片 / 关联边 / 标签 / 会话消息 / 会话记忆 /
     * Agent 绑定 ×2 / 文档）的无主行数<b>全部为 0</b>——因为它们的删除与父行在同一路径内完成，
     * 不存在"父没了、子还在"的窗口。导入明细是唯一例外（它由另一条 SQL 单独删）。
     */
    public int reclaimOrphanImportTaskItems() {
        int reclaimed = update("DELETE FROM kb_import_task_item WHERE task_id NOT IN "
                + "(SELECT id FROM kb_import_task)");
        if (reclaimed > 0) {
            log.warn("回收无主导入任务明细 {} 行（父任务已不存在）", reclaimed);
        }
        return reclaimed;
    }

    /**
     * 存储根目录自检（前置校验）。
     *
     * <p>为什么必须在<b>开始清理之前</b>做：磁盘原文的删除依赖存储根与写入方一致。
     * 曾经的做法是"跑完再看命中数"，结果在容器化部署下（后端 NEXUS_STORAGE_ROOT=/app/data
     * 而清理工具在宿主机用 ./data）会出现"数据库清干净了、磁盘原文一个没删"，
     * 而且那一步的失败是<b>静默的</b>——只在最后打一条警告，容易被忽略。
     * 现在改成开工前就判定：根目录不存在、或库里有原文记录但根目录下没有任何 kb/ 子目录，
     * 都直接判定为"存储根不可用"，让调用方决定是中止还是明确接受"只清数据库"。
     *
     * @return 自检结果（是否可用 + 人类可读的原因）
     */
    public StorageRootCheck checkStorageRoot() {
        List<String> reasons = new ArrayList<>();
        boolean rootExists = Files.isDirectory(storageRoot);
        if (!rootExists) {
            reasons.add("存储根目录不存在：" + storageRoot);
        }

        // 抽样实测：拿库里真实的 stored_path 去根目录下找。
        // 为什么不能只看"有没有 kb/ 子目录"：宿主机上可能残留一个同名的旧 data 目录，
        // 于是"目录存在"为真、但**没有一个文件能对上**——间接判据会被骗过（已踩）。
        // 直接证据是"抽样的 stored_path 里有多少能在根下找到文件"。
        List<String> samples = jdbcTemplate.queryForList(
                "SELECT stored_path FROM kb_document WHERE deleted = 0 AND stored_path IS NOT NULL "
                        + "ORDER BY id DESC LIMIT 20", String.class);
        int sampled = 0;
        int found = 0;
        if (rootExists) {
            for (String storedPath : samples) {
                if (storedPath == null || storedPath.isBlank()) {
                    continue;
                }
                sampled++;
                try {
                    if (Files.exists(storageRoot.resolve(storedPath))) {
                        found++;
                    }
                } catch (RuntimeException e) {
                    // 含 Windows 非法字符的历史脏数据，跳过
                    log.debug("stored_path 无法解析为本地路径，跳过抽样：{}", storedPath);
                }
            }
        }
        boolean usable = rootExists && (sampled == 0 || found > 0);
        if (rootExists && sampled > 0 && found == 0) {
            reasons.add("抽样核对 " + sampled + " 条 stored_path，在存储根下一个都没找到"
                    + "（本进程的存储根与写入方不是同一个目录）");
        }
        return new StorageRootCheck(usable, storageRoot, sampled, found, List.copyOf(reasons));
    }

    /**
     * 存储根自检结果。
     *
     * @param sampledPaths  抽样的 stored_path 条数
     * @param foundPaths    其中在存储根下真实存在的条数
     */
    public record StorageRootCheck(boolean usable, Path storageRoot, int sampledPaths, int foundPaths,
                                   List<String> reasons) {
    }

    private PurgeResult purgeAccount(AccountSnapshot target) {
        Long userId = target.userId();
        String username = target.username();
        PurgeResult result = new PurgeResult(username);

        // ① 知识库（级联：切片 / 关联边 / 标签 / 向量 / ES 索引 / 磁盘原文）
        List<Long> kbIds = ids("SELECT id FROM kb_knowledge_base WHERE user_id = ? AND deleted = 0", userId);

        // 删之前先核对"磁盘原文在这台机器上是否真的找得到"——见 storageRoot 字段的说明。
        // 这一步必须在 delete 之前做：删完文档行就查不到 stored_path 了。
        for (Long kbId : kbIds) {
            for (String storedPath : strings(
                    "SELECT stored_path FROM kb_document WHERE kb_id = ? AND deleted = 0", kbId)) {
                if (storedPath == null || storedPath.isBlank()) {
                    continue;
                }
                result.storedFiles++;
                // 防御：stored_path 是历史数据，可能含 Windows 非法字符（如 <>:"|?*）。
                // 这类脏数据只应让"这一条"核对失败，不应拖垮整个账号的清理，
                // 更不能让后面的级联（向量 / ES / 文档行）不执行。
                try {
                    if (Files.exists(storageRoot.resolve(storedPath))) {
                        result.storedFilesFound++;
                    }
                } catch (RuntimeException e) {
                    result.storedFilesUnreadable++;
                    log.debug("stored_path 无法解析为本地路径，跳过核对：{}（{}）", storedPath, e.toString());
                }
            }
        }

        for (Long kbId : kbIds) {
            knowledgeBaseService.delete(userId, kbId);
            result.knowledgeBases++;
        }
        if (result.storedFiles > 0 && result.storedFilesFound == 0) {
            // 注意：这里只拼接说明文本，不要对 "kb/<kbId>/<file>" 这种占位串调用 Path.resolve
            // ——尖括号在 Windows 上是非法路径字符，会抛 InvalidPathException（踩过）。
            log.warn("磁盘原文一个都没找到（本进程 storage root={}，期望位置形如 <root>/kb/<kbId>/<file>）——"
                            + "说明本进程的存储根目录与写入方不是同一个目录，磁盘清理本次实际未生效。"
                            + "若服务跑在容器里，请让本工具也在容器内运行，"
                            + "或用 NEXUS_STORAGE_ROOT 指向写入方的存储根目录。",
                    storageRoot);
        }

        // ② 知识库附属数据：删除知识库不管导入任务与问答记录，这里补上，
        //    否则每次清理都会在 kb_import_task / kb_qa_record 里留下一批无主行
        //    （实测存量：186 / 35 行）。
        //
        //    关联键必须是 task_id：早先这里写的是
        //    `kb_id IN (SELECT id FROM kb_import_task WHERE user_id = ?)` ——
        //    子查询取的是任务主键，却拿去比 kb_id 列。两个 id 空间不同、永远不会相等，
        //    于是"清理明细"这一步从未生效过。真机实测：清完 78 个账号后，
        //    kb_import_task_item 里仍留下 69 行父任务已被删掉的无主明细。
        int items = update("DELETE FROM kb_import_task_item WHERE task_id IN "
                + "(SELECT id FROM kb_import_task WHERE user_id = ?)", userId);
        int tasks = update("DELETE FROM kb_import_task WHERE user_id = ?", userId);
        int qa = update("DELETE FROM kb_qa_record WHERE user_id = ?", userId);
        result.importTasks = tasks;
        result.qaRecords = qa;
        log.info("    ├─ 知识库 {} 个；导入任务 {} 条（明细 {} 条）；问答记录 {} 条",
                result.knowledgeBases, tasks, items, qa);

        // ③ 会话 + 会话记忆
        List<Long> convIds = ids("SELECT id FROM chat_conversation WHERE user_id = ? AND deleted = 0", userId);
        for (Long convId : convIds) {
            conversationService.delete(userId, convId);
            // 会话记忆表不随会话删除清空（实测 394 行存量），key 形如 "conv:{id}"
            result.chatMemoryRows += update("DELETE FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?",
                    ChatMemoryKeys.of(convId));
            result.conversations++;
        }
        log.info("    ├─ 会话 {} 个；会话记忆 {} 行", result.conversations, result.chatMemoryRows);

        // ④ Agent（自建走服务级联；内置默认 Agent 服务层拒绝删除，按配置旁路）
        List<Map<String, Object>> agents = jdbcTemplate.queryForList(
                "SELECT id, builtin FROM agent WHERE owner_id = ? AND deleted = 0", userId);
        for (Map<String, Object> row : agents) {
            Long agentId = ((Number) row.get("id")).longValue();
            boolean builtin = isTruthy(row.get("builtin"));
            if (!builtin) {
                agentService.delete(userId, agentId);
                result.agents++;
            } else if (properties.isPurgeBuiltinAgents()) {
                // 管理员级旁路：绕过 AgentService 的"内置不可删"约束。
                // 账号整体都要清理，保留它只会制造无主行；日志显著标注以便审计。
                update("UPDATE agent SET deleted = 1 WHERE id = ?", agentId);
                update("DELETE FROM agent_knowledge_base WHERE agent_id = ?", agentId);
                update("DELETE FROM agent_tool WHERE agent_id = ?", agentId);
                result.builtinAgents++;
                log.info("    ├─ 内置 Agent {} 已由管理员级清理软删（绕过内置保护）", agentId);
            }
        }
        // 绑定关系里指向"已被删掉的知识库"的残留（Agent 可能绑过别人的库或已被删的库）
        if (!kbIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(kbIds.size(), "?"));
            update("DELETE FROM agent_knowledge_base WHERE kb_id IN (" + placeholders + ")", kbIds.toArray());
        }
        log.info("    ├─ 自建 Agent {} 个；内置 Agent {} 个", result.agents, result.builtinAgents);

        // ⑤ 工作区（服务层没有删除方法；workspace 实体未标 @TableLogic，deleteById 为物理删除，
        //    这在"清理一次性账号"的语境下正是想要的——它是纯附属数据，无审计价值）
        List<WorkspacePO> workspaces = workspaceMapper.selectList(
                Wrappers.<WorkspacePO>lambdaQuery().eq(WorkspacePO::getUserId, userId));
        for (WorkspacePO workspace : workspaces) {
            workspaceMapper.deleteById(workspace.getId());
            result.workspaces++;
        }

        // ⑥ 角色关联 + 账号本身（账号走逻辑删除：SysUserPO 标了 @TableLogic，
        //    deleteById 会自动改写为 UPDATE deleted=1，与产品软删约定一致、可回溯）
        int roles = sysUserRoleMapper.delete(
                Wrappers.<SysUserRolePO>lambdaQuery().eq(SysUserRolePO::getUserId, userId));
        sysUserMapper.deleteById(userId);
        log.info("    └─ 工作区 {} 个；角色关联 {} 条；账号已软删", result.workspaces, roles);

        return result;
    }

    // ------------------------------------------------------------------
    // 裸 SQL 的小工具（读 id / 计数 / 更新）
    // ------------------------------------------------------------------

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private int update(String sql, Object... args) {
        return jdbcTemplate.update(sql, args);
    }

    private List<Long> ids(String sql, Object... args) {
        List<Long> result = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, args)) {
            Object id = row.values().iterator().next();
            if (id instanceof Number number) {
                result.add(number.longValue());
            }
        }
        return result;
    }

    private List<String> strings(String sql, Object... args) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, args)) {
            Object value = row.values().iterator().next();
            if (value != null) {
                result.add(value.toString());
            }
        }
        return result;
    }

    /** 原始文件存储根目录（绝对路径），供 CLI 打印与核对。 */
    public Path storageRoot() {
        return storageRoot;
    }

    private static boolean isTruthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    // ------------------------------------------------------------------
    // 值对象
    // ------------------------------------------------------------------

    /** 单个候选账号及其名下资源快照。 */
    public record AccountSnapshot(Long userId,
                                  String username,
                                  int knowledgeBases,
                                  int conversations,
                                  int agents,
                                  int builtinAgents,
                                  int workspaces,
                                  int importTasks,
                                  int qaRecords) {
    }

    /** 清理计划（${@link #plan()} 的产物，只读）。 */
    public record CleanupPlan(List<AccountSnapshot> targets,
                              List<String> protectedUsernames,
                              List<String> prefixes) {

        public int totalKnowledgeBases() {
            return targets.stream().mapToInt(AccountSnapshot::knowledgeBases).sum();
        }

        public int totalConversations() {
            return targets.stream().mapToInt(AccountSnapshot::conversations).sum();
        }

        public int totalAgents() {
            return targets.stream().mapToInt(AccountSnapshot::agents).sum();
        }

        public int totalBuiltinAgents() {
            return targets.stream().mapToInt(AccountSnapshot::builtinAgents).sum();
        }

        public int totalWorkspaces() {
            return targets.stream().mapToInt(AccountSnapshot::workspaces).sum();
        }

        public int totalImportTasks() {
            return targets.stream().mapToInt(AccountSnapshot::importTasks).sum();
        }

        public int totalQaRecords() {
            return targets.stream().mapToInt(AccountSnapshot::qaRecords).sum();
        }

        public Map<String, Integer> summary() {
            Map<String, Integer> summary = new HashMap<>();
            summary.put("账号", targets.size());
            summary.put("知识库", totalKnowledgeBases());
            summary.put("会话", totalConversations());
            summary.put("自建Agent", totalAgents());
            // 内置 Agent 默认也会被删（purgeBuiltinAgents 默认 true），计划里必须报出来，
            // 否则"dry-run 说删 1 个、实际删 4 个"，报告与执行对不上。
            summary.put("内置Agent", totalBuiltinAgents());
            summary.put("工作区", totalWorkspaces());
            summary.put("导入任务", totalImportTasks());
            summary.put("问答记录", totalQaRecords());
            return summary;
        }
    }

    /** 单个账号的清理结果。 */
    public static final class PurgeResult {
        private final String username;
        private int knowledgeBases;
        private int conversations;
        private int agents;
        private int builtinAgents;
        private int workspaces;
        private int importTasks;
        private int qaRecords;
        private int chatMemoryRows;
        /** 库里记录了 stored_path 的原始文件数（期望值）。 */
        private int storedFiles;
        /** 其中在"本进程的存储根目录"下真实存在的数量（用于识别 root 不一致）。 */
        private int storedFilesFound;
        /** stored_path 含非法字符、连解析都失败的条数（脏数据，不影响级联执行）。 */
        private int storedFilesUnreadable;

        PurgeResult(String username) {
            this.username = username;
        }

        public int storedFiles() {
            return storedFiles;
        }

        public int storedFilesFound() {
            return storedFilesFound;
        }

        public int storedFilesUnreadable() {
            return storedFilesUnreadable;
        }

        public String username() {
            return username;
        }

        public int knowledgeBases() {
            return knowledgeBases;
        }

        public int conversations() {
            return conversations;
        }

        public int agents() {
            return agents;
        }

        public int builtinAgents() {
            return builtinAgents;
        }

        public int workspaces() {
            return workspaces;
        }

        public int importTasks() {
            return importTasks;
        }

        public int qaRecords() {
            return qaRecords;
        }

        public int chatMemoryRows() {
            return chatMemoryRows;
        }
    }

    /** 部分账号清理失败时抛出，携带账号 → 原因，供 CLI 打印与非零退出码使用。 */
    public static final class CleanupPartialFailureException extends RuntimeException {
        private final transient Map<String, String> failures;

        CleanupPartialFailureException(Map<String, String> failures) {
            super("部分账号清理失败：" + failures.keySet());
            this.failures = failures;
        }

        public Map<String, String> failures() {
            return failures;
        }
    }
}
