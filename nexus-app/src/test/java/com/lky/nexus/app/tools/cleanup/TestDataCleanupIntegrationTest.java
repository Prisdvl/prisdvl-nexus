package com.lky.nexus.app.tools.cleanup;

import com.lky.nexus.app.agent.AgentService;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.mapper.SysUserRoleMapper;
import com.lky.nexus.app.chat.service.ChatMemoryKeys;
import com.lky.nexus.app.chat.service.ConversationService;
import com.lky.nexus.app.workspace.WorkspaceMapper;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * 测试数据清理工具的<b>行为级</b>集成测试（H2 + 真实 Service 层，不需要 Docker）。
 *
 * <p><b>为什么单靠 {@link CleanupPropertiesTest} 不够</b>：那组用例钉住的是"哪些账号会被选中"
 * 这条纯逻辑。而清理工具真正的风险在另一半——<b>选中之后有没有删干净、删错了没、失败了有没有报告</b>。
 * 这三件事只有让真实的 {@code KnowledgeBaseService} / {@code ConversationService} / {@code AgentService}
 * 真的跑一遍才验得了。
 *
 * <p>运行在 {@code test} profile 上（H2 内存库 + MySQL 方言 Flyway 迁移，表结构与生产一致；
 * 向量库与 ES 在该 profile 下是 {@code DisabledVectorStore} / {@code DisabledChunkIndex} 的 no-op）。
 * 这样这套用例可以跟着 {@code mvn test} 常驻回归，而不是依赖"Docker 起着"才能跑。
 *
 * <p>逐条对应任务书第十节要求的八类：
 * <ol>
 *   <li><b>dry-run</b>：{@link #planIsStructurallyReadOnly()}——{@code plan()} 跑完数据一行未动；</li>
 *   <li><b>正常 cleanup</b>：{@link #purgeCascadesEverythingBelowTheAccount()}；</li>
 *   <li><b>demo 不删除</b> / 4. <b>Prisdvl 不删除</b> / 5. <b>非测试账号不删除</b>：
 *       {@link #protectedAndUnrelatedAccountsSurvive()}；</li>
 *   <li><b>完整级联</b>：{@link #purgeCascadesEverythingBelowTheAccount()} 逐层核查到切片 / 关联边 /
 *       标签 / 会话消息 / 会话记忆 / 内置 Agent / 磁盘原文；</li>
 *   <li><b>重复执行幂等</b>：{@link #purgeIsIdempotent()}；</li>
 *   <li><b>部分失败有明确报告</b>：{@link #partialFailureIsReportedAndOthersStillProceed()}；</li>
 *   <li><b>最终一致性回收</b>（额外一类，来自真机运行暴露的缺陷）：
 *       {@link #reclaimsOrphanImportTaskItems()}。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class TestDataCleanupIntegrationTest {

    /** 测试自造 id：与 snowflake 不同段，避免和 ProductX 路径写入的行撞主键。 */
    private static final AtomicLong ID = new AtomicLong(9_000_000_000_000_000_001L);

    /** 与 test profile 的 {@code nexus.storage.root} 保持一致，磁盘级联才在同一根目录下兑现。 */
    private static final String STORAGE_ROOT = "./target/test-storage";

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private WorkspaceMapper workspaceMapper;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
    }

    // ------------------------------------------------------------------
    // ① dry-run 的结构性只读
    // ------------------------------------------------------------------

    @Test
    @DisplayName("① dry-run：plan() 跑完，候选账号与它名下的资源一行未动")
    void planIsStructurallyReadOnly() {
        String prefix = "cln-dry-";
        Long userId = seedAccount(prefix + "1");
        seedKnowledgeBaseWithCascade(userId, "干跑库");

        TestDataCleanupService service = cleanup(prefix);
        TestDataCleanupService.CleanupPlan plan = service.plan();

        assertThat(plan.targets()).extracting(TestDataCleanupService.AccountSnapshot::username)
                .containsExactly(prefix + "1");
        assertThat(plan.totalKnowledgeBases()).isEqualTo(1);

        // plan() 里没有任何写操作，因此跑完之后一切照旧——这是"结构上不可能删数据"，
        // 而不是"靠一个 if 判断保证不删"
        assertThat(isUserActive(prefix + "1")).isTrue();
        assertThat(activeKnowledgeBases(userId)).isEqualTo(1);
        assertThat(chunkCount(userId)).isEqualTo(1);
        assertThat(docCount(userId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // ①b dry-run 的账目完整性
    // ------------------------------------------------------------------

    @Test
    @DisplayName("①b dry-run 账目完整：会被删的内置 Agent 必须出现在计划里，不能只报自建项")
    void planReportsBuiltinAgentsToo() {
        String prefix = "cln-bagt-";
        Long userId = seedAccount(prefix + "1");
        long workspaceId = seedWorkspace(userId);
        seedAgent(userId, workspaceId, "自建助手", 0);
        seedAgent(userId, workspaceId, "内置助手", 1);

        TestDataCleanupService.CleanupPlan plan = cleanup(prefix).plan();

        assertThat(plan.targets()).hasSize(1);
        TestDataCleanupService.AccountSnapshot snapshot = plan.targets().get(0);
        assertThat(snapshot.agents()).as("自建项").isEqualTo(1);
        // purgeBuiltinAgents 默认 true —— 内置 Agent 也会被真删。计划里若不报它，
        // 就会出现"dry-run 说删 1 个 Agent、实际删掉 2 个"的账目对不上
        //（真机实测：dry-run 报"自建Agent 1"，实际删了 4 个 = 1 自建 + 3 内置）。
        assertThat(snapshot.builtinAgents()).as("内置项也会被删，必须进计划").isEqualTo(1);
        assertThat(plan.summary().get("内置Agent")).as("合计口径也要带上").isEqualTo(1);
        assertThat(plan.totalBuiltinAgents()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // ②⑥ 正常清理 + 完整级联
    // ------------------------------------------------------------------

    @Test
    @DisplayName("②⑥ 正常清理：账号名下十类资源全部归零（含切片/关联边/标签/消息/会话记忆/内置Agent/磁盘原文）")
    void purgeCascadesEverythingBelowTheAccount() throws IOException {
        String prefix = "cln-full-";
        Long userId = seedAccount(prefix + "1");
        long kbId = seedKnowledgeBaseWithCascade(userId, "整库");
        long convId = seedConversationWithMessages(userId);
        long workspaceId = seedWorkspace(userId);
        seedAgent(userId, workspaceId, "自建助手", 0);
        seedAgent(userId, workspaceId, "内置助手", 1);
        seedImportTask(userId, kbId);
        seedQaRecord(userId, kbId);
        seedPluginMcpSkillRows(userId);

        Path original = writeOriginalFile(kbId);

        TestDataCleanupService service = cleanup(prefix);
        List<TestDataCleanupService.PurgeResult> results = service.purge(service.plan());

        assertThat(results).hasSize(1);
        TestDataCleanupService.PurgeResult result = results.get(0);
        assertThat(result.username()).isEqualTo(prefix + "1");
        assertThat(result.knowledgeBases()).isEqualTo(1);
        assertThat(result.conversations()).isEqualTo(1);
        assertThat(result.agents()).as("自建 Agent").isEqualTo(1);
        assertThat(result.builtinAgents()).as("内置 Agent 走管理员级旁路").isEqualTo(1);
        assertThat(result.workspaces()).isEqualTo(1);
        assertThat(result.importTasks()).isEqualTo(1);
        assertThat(result.qaRecords()).isEqualTo(1);
        assertThat(result.storedFiles()).isEqualTo(1);
        assertThat(result.storedFilesFound())
                .as("存储根目录必须与写入方一致，否则磁盘原文是静默失败")
                .isEqualTo(1);

        // 逐层核查：逻辑删除的查不到，物理删除的连行都没有
        assertThat(isUserActive(prefix + "1")).as("账号软删").isFalse();
        assertThat(activeKnowledgeBases(userId)).as("知识库软删").isZero();
        assertThat(docCount(userId)).as("文档软删").isZero();
        assertThat(chunkCount(userId)).as("切片物理清空").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM kb_document_link WHERE kb_id = ?", kbId)).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM kb_document_tag WHERE kb_id = ?", kbId)).isZero();
        assertThat(activeConversations(userId)).as("会话软删").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM chat_message WHERE user_id = ?", userId))
                .as("消息随会话物理清空").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?",
                ChatMemoryKeys.of(convId)))
                .as("会话记忆不随会话删除清空，必须单独补这一刀").isZero();
        assertThat(activeAgents(userId)).isZero();
        // ⚠️ 断言的是"没有活跃工作区"而不是"物理行数为 0"：测试 profile 配了
        // mybatis-plus.global-config.db-config.logic-delete-field=deleted，而生产没有这条全局配置
        // —— WorkspacePO 又没标 @TableLogic，于是同一个 deleteById 在测试里是写墓碑、在生产里是真删
        // （真机实测 workspace 73 → 2）。这个口径差异本身已作为发现记入报告，
        // 此处只钉住对用户可见的不变量：清理之后不再有活跃工作区。
        assertThat(scalar("SELECT COUNT(*) FROM workspace WHERE user_id = ? AND deleted = 0", userId))
                .as("工作区不再活跃").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM kb_import_task WHERE user_id = ?", userId)).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM kb_import_task_item WHERE kb_id = ?", kbId)).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM kb_qa_record WHERE user_id = ?", userId)).isZero();
        // V32/V34 的三张用户级表：不清就会留无主行，唯一键还会挡住同名账号的重新安装
        assertThat(scalar("SELECT COUNT(*) FROM user_plugin WHERE user_id = ?", userId))
                .as("插件行物理清空").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM mcp_server WHERE user_id = ?", userId))
                .as("MCP server 物理清空").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM user_skill WHERE user_id = ?", userId))
                .as("技能行物理清空").isZero();
        assertThat(scalar("SELECT COUNT(*) FROM sys_user_role WHERE user_id = ?", userId)).isZero();
        assertThat(Files.exists(original)).as("磁盘原文随库一起消失").isFalse();
    }

    // ------------------------------------------------------------------
    // ⑦ 幂等
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑦ 幂等：连跑两次不报错，第二次计划为空、不重复删任何东西")
    void purgeIsIdempotent() {
        String prefix = "cln-idem-";
        Long userId = seedAccount(prefix + "1");
        seedKnowledgeBaseWithCascade(userId, "幂等库");

        TestDataCleanupService service = cleanup(prefix);
        assertThat(service.purge(service.plan())).hasSize(1);

        TestDataCleanupService.CleanupPlan second = service.plan();
        assertThat(second.targets()).as("账号已软删，不再进入候选集").isEmpty();
        assertThat(second.summary().get("账号")).isZero();
        assertThat(service.purge(second)).as("空计划是 no-op，而不是异常").isEmpty();
        assertThat(chunkCount(userId)).isZero();
    }

    // ------------------------------------------------------------------
    // ③④⑤ 保护与白名单
    // ------------------------------------------------------------------

    @Test
    @DisplayName("③④⑤ 只有前缀白名单命中的账号被清；demo / Prisdvl / 非测试账号一律完整保留")
    void protectedAndUnrelatedAccountsSurvive() {
        String prefix = "cln-prot-";
        Long doomed = seedAccount(prefix + "1");
        seedKnowledgeBaseWithCascade(doomed, "待清理");

        Long unrelated = seedAccount("zoe-keep");
        seedKnowledgeBaseWithCascade(unrelated, "第三方真实用户的库");

        Long demo = seedAccount("demo");
        seedKnowledgeBaseWithCascade(demo, "演示库");

        Long real = seedAccount("Prisdvl");
        seedKnowledgeBaseWithCascade(real, "真实用户的库");

        TestDataCleanupService service = cleanup(prefix);
        service.purge(service.plan());

        assertThat(isUserActive(prefix + "1")).as("白名单命中 → 清理").isFalse();
        assertThat(isUserActive("zoe-keep")).as("不在白名单 → 一律不动").isTrue();
        assertThat(isUserActive("demo")).as("demo 必须完整保留").isTrue();
        assertThat(isUserActive("Prisdvl")).as("Prisdvl 必须完整保留").isTrue();

        assertThat(activeKnowledgeBases(unrelated)).as("非测试账号的库一条都不能少").isEqualTo(1);
        assertThat(activeKnowledgeBases(demo)).as("demo 的库一条都不能少").isEqualTo(1);
        assertThat(activeKnowledgeBases(real)).as("Prisdvl 的库一条都不能少").isEqualTo(1);
        assertThat(chunkCount(unrelated)).isEqualTo(1);
        assertThat(chunkCount(demo)).isEqualTo(1);
        assertThat(chunkCount(real)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // ⑧ 部分失败必须被报告，且不拖垮其它账号
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑧ 单个账号失败：异常携带『账号 + 原因』，其余账号照常清理，不整体回滚")
    void partialFailureIsReportedAndOthersStillProceed() {
        String prefix = "cln-fail-";
        Long broken = seedAccount(prefix + "1");
        seedKnowledgeBaseWithCascade(broken, "会失败的库");
        Long healthy = seedAccount(prefix + "2");
        seedKnowledgeBaseWithCascade(healthy, "会成功的库");

        // 只让"坏账号"的删库这一步炸掉（模拟外部存储不可用），其它调用仍走真实实现
        KnowledgeBaseService failing = mock(KnowledgeBaseService.class,
                withSettings().defaultAnswer(delegatesTo(knowledgeBaseService)));
        doThrow(new IllegalStateException("模拟向量库不可用")).when(failing).delete(eq(broken), anyLong());

        TestDataCleanupService service = cleanup(prefix, failing);

        assertThatThrownBy(() -> service.purge(service.plan()))
                .isInstanceOf(TestDataCleanupService.CleanupPartialFailureException.class)
                .satisfies(thrown -> {
                    Map<String, String> failures =
                            ((TestDataCleanupService.CleanupPartialFailureException) thrown).failures();
                    assertThat(failures).containsOnlyKeys(prefix + "1");
                    assertThat(failures.get(prefix + "1")).contains("模拟向量库不可用");
                });

        assertThat(isUserActive(prefix + "2")).as("其余账号照常清理").isFalse();
        assertThat(isUserActive(prefix + "1")).as("失败账号保留，便于人工复查").isTrue();
    }

    // ------------------------------------------------------------------
    // ⑨ 最终一致性回收
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑨ 最终一致性：父任务已不存在的无主导入明细可被回收，dry-run 下只统计不删")
    void reclaimsOrphanImportTaskItems() {
        String prefix = "cln-orphan-";
        Long userId = seedAccount(prefix + "1");
        long kbId = seedKnowledgeBaseWithCascade(userId, "孤儿明细库");
        seedImportTask(userId, kbId);

        // 制造孤儿：任务行先没了、明细留下 —— 这正是历史缺陷留下的形态
        // （旧 SQL 拿任务 id 去比 kb_id，两个 id 空间不同、永远不相等）
        jdbc.update("DELETE FROM kb_import_task WHERE user_id = ?", userId);
        assertThat(orphanItemsFor(kbId)).isEqualTo(1);

        TestDataCleanupService service = cleanup(prefix);

        // dry-run 路径只统计
        assertThat(service.countOrphanImportTaskItems()).isGreaterThanOrEqualTo(1);
        assertThat(orphanItemsFor(kbId)).as("dry-run 绝不删数据").isEqualTo(1);

        // 实际执行时回收；靠逐账号枚举永远找不到这些行（父行已经查不到了）
        assertThat(service.reclaimOrphanImportTaskItems()).isGreaterThanOrEqualTo(1);
        assertThat(orphanItemsFor(kbId)).isZero();

        service.purge(service.plan());
    }

    // ------------------------------------------------------------------
    // 造数据 & 查数据
    // ------------------------------------------------------------------

    private TestDataCleanupService cleanup(String prefix) {
        return cleanup(prefix, knowledgeBaseService);
    }

    private TestDataCleanupService cleanup(String prefix, KnowledgeBaseService kbService) {
        CleanupProperties properties = new CleanupProperties();
        // 每个用例用自己的前缀：同一个 Spring 上下文（同一份 H2 库）里互不干扰
        properties.setUsernamePrefixes(List.of(prefix));
        properties.setProtectedUsernames(List.of("Prisdvl", "demo"));
        return new TestDataCleanupService(properties, sysUserMapper, sysUserRoleMapper,
                workspaceMapper, kbService, conversationService, agentService, dataSource, STORAGE_ROOT);
    }

    private Long seedAccount(String username) {
        long userId = ID.incrementAndGet();
        jdbc.update("INSERT INTO sys_user (id, username, password_hash) VALUES (?,?,?)",
                userId, username, "$2a$10$not-a-real-hash-seeded-by-test");
        jdbc.update("INSERT INTO sys_user_role (id, user_id, role_id) VALUES (?,?,?)",
                ID.incrementAndGet(), userId, 1L);
        return userId;
    }

    /** 造"知识库 + 文档 + 切片 + 关联边 + 标签"这一整条子树，返回 kbId。 */
    private long seedKnowledgeBaseWithCascade(Long userId, String name) {
        long kbId = ID.incrementAndGet();
        jdbc.update("""
                INSERT INTO kb_knowledge_base (id, user_id, name, embedding_provider, embedding_model, embedding_dim)
                VALUES (?,?,?,?,?,?)
                """, kbId, userId, name, "ollama", "bge-m3", 1024);

        long docId = ID.incrementAndGet();
        jdbc.update("""
                INSERT INTO kb_document (id, kb_id, user_id, file_name, file_ext, source_type, source_path,
                                         file_hash, stored_path)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, docId, kbId, userId, "note.md", "md", "UPLOAD", "note.md",
                "0".repeat(64), "kb/" + kbId + "/note.md");

        jdbc.update("""
                INSERT INTO kb_chunk (id, kb_id, document_id, user_id, chunk_index, chunk_type, content, content_hash)
                VALUES (?,?,?,?,?,?,?,?)
                """, ID.incrementAndGet(), kbId, docId, userId, 0, "TEXT", "正文", "1".repeat(64));

        jdbc.update("INSERT INTO kb_document_link (id, kb_id, from_document_id, link_type, target) VALUES (?,?,?,?,?)",
                ID.incrementAndGet(), kbId, docId, "WIKILINK", "另一篇");

        jdbc.update("""
                INSERT INTO kb_document_tag (id, kb_id, document_id, tag, tag_source)
                VALUES (?,?,?,?,?)
                """, ID.incrementAndGet(), kbId, docId, "测试", "MANUAL");

        return kbId;
    }

    /** 造"会话 + 消息 + 会话记忆"，返回 convId。 */
    private long seedConversationWithMessages(Long userId) {
        long convId = ID.incrementAndGet();
        jdbc.update("INSERT INTO chat_conversation (id, user_id, title) VALUES (?,?,?)",
                convId, userId, "测试会话");
        jdbc.update("INSERT INTO chat_message (id, conversation_id, user_id, role, content) VALUES (?,?,?,?,?)",
                ID.incrementAndGet(), convId, userId, "USER", "问题");
        // 会话记忆的 key 由 ChatMemoryKeys 统一生成，测试里不复制它的格式
        jdbc.update("""
                INSERT INTO SPRING_AI_CHAT_MEMORY (conversation_id, content, type, timestamp, sequence_id)
                VALUES (?,?,?,CURRENT_TIMESTAMP,?)
                """, ChatMemoryKeys.of(convId), "记忆内容", "USER", 1L);
        return convId;
    }

    private void seedAgent(Long userId, long workspaceId, String name, int builtin) {
        long agentId = ID.incrementAndGet();
        jdbc.update("""
                INSERT INTO agent (id, workspace_id, owner_id, code, name, builtin)
                VALUES (?,?,?,?,?,?)
                """, agentId, workspaceId, userId, "agent-" + agentId, name, builtin);
    }

    private long seedWorkspace(Long userId) {
        long workspaceId = ID.incrementAndGet();
        jdbc.update("INSERT INTO workspace (id, user_id, name) VALUES (?,?,?)",
                workspaceId, userId, "测试工作区");
        return workspaceId;
    }

    private void seedImportTask(Long userId, long kbId) {
        long taskId = ID.incrementAndGet();
        jdbc.update("""
                INSERT INTO kb_import_task (id, kb_id, user_id, task_type, status)
                VALUES (?,?,?,?,?)
                """, taskId, kbId, userId, "UPLOAD", "DONE");
        jdbc.update("""
                INSERT INTO kb_import_task_item (id, task_id, kb_id, source_path, action)
                VALUES (?,?,?,?,?)
                """, ID.incrementAndGet(), taskId, kbId, "note.md", "CREATED");
    }

    /**
     * V32 / V34 之后新增的三张用户级表：早期清理清单里没有它们，
     * 删账号会留下无主行（而且 user_plugin / user_skill 的唯一键还会挡住同名账号重装）。
     */
    private void seedPluginMcpSkillRows(Long userId) {
        jdbc.update("""
                INSERT INTO user_plugin (id, user_id, plugin_code, enabled)
                VALUES (?,?,?,?)
                """, ID.incrementAndGet(), userId, "web-fetch", 1);
        jdbc.update("""
                INSERT INTO mcp_server (id, user_id, name, slug, transport, enabled)
                VALUES (?,?,?,?,?,?)
                """, ID.incrementAndGet(), userId, "测试 MCP", "test-mcp", "http", 1);
        jdbc.update("""
                INSERT INTO user_skill (id, user_id, code, name, content_md, enabled)
                VALUES (?,?,?,?,?,?)
                """, ID.incrementAndGet(), userId, "skill-test", "测试技能", "---\nname: skill-test\n---\n", 1);
    }

    private void seedQaRecord(Long userId, long kbId) {
        jdbc.update("""
                INSERT INTO kb_qa_record (id, kb_id, user_id, question, answer)
                VALUES (?,?,?,?,?)
                """, ID.incrementAndGet(), kbId, userId, "问", "答");
    }

    private Path writeOriginalFile(long kbId) throws IOException {
        Path file = Paths.get(STORAGE_ROOT, "kb", String.valueOf(kbId), "note.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "# 正文\n", StandardCharsets.UTF_8);
        return file;
    }

    private int scalar(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private boolean isUserActive(String username) {
        return scalar("SELECT COUNT(*) FROM sys_user WHERE username = ? AND deleted = 0", username) == 1;
    }

    private int activeKnowledgeBases(Long userId) {
        return scalar("SELECT COUNT(*) FROM kb_knowledge_base WHERE user_id = ? AND deleted = 0", userId);
    }

    private int docCount(Long userId) {
        return scalar("SELECT COUNT(*) FROM kb_document WHERE user_id = ? AND deleted = 0", userId);
    }

    private int chunkCount(Long userId) {
        return scalar("SELECT COUNT(*) FROM kb_chunk WHERE user_id = ?", userId);
    }

    /** 指定知识库下"父任务已不存在"的导入明细行数。 */
    private int orphanItemsFor(long kbId) {
        return scalar("SELECT COUNT(*) FROM kb_import_task_item WHERE kb_id = ? "
                + "AND task_id NOT IN (SELECT id FROM kb_import_task)", kbId);
    }

    private int activeConversations(Long userId) {
        return scalar("SELECT COUNT(*) FROM chat_conversation WHERE user_id = ? AND deleted = 0", userId);
    }

    private int activeAgents(Long userId) {
        return scalar("SELECT COUNT(*) FROM agent WHERE owner_id = ? AND deleted = 0", userId);
    }
}
