package com.lky.nexus.app.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.ai.agent.AnswerPolicy;
import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.app.agent.mapper.AgentKnowledgeBaseMapper;
import com.lky.nexus.app.agent.mapper.AgentMapper;
import com.lky.nexus.app.agent.mapper.AgentToolMapper;
import com.lky.nexus.app.agent.model.AgentStatus;
import com.lky.nexus.app.agent.model.dto.AgentResponse;
import com.lky.nexus.app.agent.model.dto.CreateAgentRequest;
import com.lky.nexus.app.agent.model.dto.UpdateAgentRequest;
import com.lky.nexus.app.agent.model.entity.AgentKnowledgeBasePO;
import com.lky.nexus.app.agent.model.entity.AgentPO;
import com.lky.nexus.app.agent.model.entity.AgentToolPO;
import com.lky.nexus.app.agent.tool.ToolRegistry;
import com.lky.nexus.app.workspace.WorkspaceService;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.PageQuery;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Agent 资源服务（平台化 Phase 4.1，§十五–§十八）：用户可配置的 Agent 的增删改查与绑定。
 *
 * <p>与 {@link AgentRuntime} 的分工是本增量最重要的一条线：
 * <b>本类只碰数据库与准入规则，一行"怎么跑 Agent"的逻辑都没有</b>；
 * 跑循环的是 {@code AgentRuntime}，它只认 {@link AgentDefinition}。
 * 两者之间由 {@link #requireDefinition} / {@link #resolveDefault} 搭桥——这也是 §十七
 * "不要把数据库 Entity 直接塞给 Agent Loop"的落点。
 *
 * <p>三个刻意的设计：
 * <ol>
 *   <li><b>内置默认 Agent 是真实一行</b>，而不是代码里的 if-else 兜底。这样"AGENT 模式用哪个
 *       Agent"永远只有一条路径（查库），用户在界面上也看得见、改得动它的提示词——
 *       写死在代码里的兜底恰恰是 §六十七 禁止的"硬编码 Agent"；</li>
 *   <li><b>状态机有实际后果</b>：DRAFT 不能被会话选用（先测试再发布），DISABLED 不是"悄悄换回默认"
 *       而是明确报错；</li>
 *   <li><b>访问他人资源返回 404</b>（3001/5006 语义一致），不泄露资源存在性。</li>
 * </ol>
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AgentMapper agentMapper;
    private final AgentKnowledgeBaseMapper agentKbMapper;
    private final AgentToolMapper agentToolMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final WorkspaceService workspaceService;
    private final ChatClientFactory chatClientFactory;
    private final ToolRegistry toolRegistry;

    public AgentService(AgentMapper agentMapper,
                        AgentKnowledgeBaseMapper agentKbMapper,
                        AgentToolMapper agentToolMapper,
                        KnowledgeBaseService knowledgeBaseService,
                        WorkspaceService workspaceService,
                        ChatClientFactory chatClientFactory,
                        ToolRegistry toolRegistry) {
        this.agentMapper = agentMapper;
        this.agentKbMapper = agentKbMapper;
        this.agentToolMapper = agentToolMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.workspaceService = workspaceService;
        this.chatClientFactory = chatClientFactory;
        this.toolRegistry = toolRegistry;
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 分页列出当前用户的 Agent：内置默认 Agent 置顶，其余按更新时间倒序。
     *
     * <p>读路径上会先（幂等地）补齐内置默认 Agent 那一行，保证新用户的列表不为空。
     *
     * @param userId   归属用户
     * @param pageNum  页码（会被归一化到 ≥1）
     * @param pageSize 每页条数（会被归一化到合法范围）
     * @return 当前用户的 Agent 分页列表
     */
    public PageResult<AgentResponse> list(Long userId, int pageNum, int pageSize) {
        int safePageNum = PageQuery.normalizePageNum(pageNum);
        int safePageSize = PageQuery.normalizePageSize(pageSize);

        // 先补齐内置默认 Agent 那一行。它是**按需创建**的（注册流程不预置），
        // 如果只有执行路径会触发补齐，新用户的列表就会是空的——
        // 而界面上的 Agent 选择器、"我的 Agent"列表都读这里，
        // 于是"AGENT 模式永远有兜底"这句在列表里看不出来，用户只能靠猜。
        // 补齐是幂等的（同一用户至多一行），所以放在读路径上不会写出脏数据。
        resolveDefault(userId);

        Page<AgentPO> page = new Page<>(safePageNum, safePageSize);
        Page<AgentPO> result = agentMapper.selectPage(page, Wrappers.<AgentPO>lambdaQuery()
                // 内置默认 Agent 置顶（它是 AGENT 模式的兜底，不该被埋在第二页）
                .eq(AgentPO::getOwnerId, userId)
                .orderByDesc(AgentPO::getBuiltin)
                .orderByDesc(AgentPO::getUpdatedAt));

        List<AgentResponse> list = result.getRecords().stream().map(this::toResponse).toList();
        return PageResult.of(list, result.getTotal(), safePageNum, safePageSize);
    }

    /**
     * 查看单个 Agent 详情（含绑定知识库与工具白名单），仅限本人。
     *
     * @param userId   归属用户
     * @param agentId  目标 Agent 主键
     * @return 该 Agent 的视图
     */
    public AgentResponse detail(Long userId, Long agentId) {
        AgentPO agent = requireOwned(userId, agentId);
        return toResponse(agent);
    }

    /**
     * 取当前用户名下、指定 id 的 Agent；越权或不存在一律抛 {@code AGENT_NOT_FOUND}（404 语义，
     * 不泄露资源存在性）。
     *
     * <p>归属校验放进 SQL 条件（{@code owner_id} 进 WHERE），而不是查出来再比对。
     *
     * @param userId   归属用户
     * @param agentId  目标 Agent 主键（为 null 直接按"找不到"处理）
     * @return 命中的 Agent 实体
     */
    public AgentPO requireOwned(Long userId, Long agentId) {
        if (agentId == null) {
            throw new BusinessException(ErrorCode.AGENT_NOT_FOUND);
        }
        // user_id 进 SQL 条件，而不是查出来再比对（与知识库/会话同一套隔离约定）
        AgentPO agent = agentMapper.selectOne(Wrappers.<AgentPO>lambdaQuery()
                .eq(AgentPO::getId, agentId)
                .eq(AgentPO::getOwnerId, userId));
        if (agent == null) {
            throw new BusinessException(ErrorCode.AGENT_NOT_FOUND);
        }
        return agent;
    }

    /** 该 Agent 绑定的知识库 id（按绑定顺序）。 */
    public List<Long> kbIdsOf(Long agentId) {
        return agentKbMapper.selectList(Wrappers.<AgentKnowledgeBasePO>lambdaQuery()
                        .eq(AgentKnowledgeBasePO::getAgentId, agentId)
                        .orderByAsc(AgentKnowledgeBasePO::getId))
                .stream().map(AgentKnowledgeBasePO::getKbId).toList();
    }

    /**
     * 该 Agent 的工具白名单（4.3，按绑定顺序）；<b>空 = 不限制</b>。
     *
     * <p>返回的是 code 而不是 id：工具的身份在实现里（{@code @Tool(name=...)}），
     * 而 {@code tool} 表的行只是用户态覆盖——用 id 引用随时可能悬空。理由详见 V13 的文件头。
     */
    public List<String> toolCodesOf(Long agentId) {
        return agentToolMapper.selectList(Wrappers.<AgentToolPO>lambdaQuery()
                        .eq(AgentToolPO::getAgentId, agentId)
                        .orderByAsc(AgentToolPO::getId))
                .stream().map(AgentToolPO::getToolCode).toList();
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    @Transactional
    public AgentResponse create(Long userId, CreateAgentRequest request) {
        String code = request.code().trim();
        if (existsCode(userId, code)) {
            throw new BusinessException(ErrorCode.AGENT_CODE_DUPLICATED, "已存在同名标识的 Agent：" + code);
        }
        requireConfiguredModelRole(request.modelRole() == null
                ? AiModelProperties.Role.CHAT_AGENT : request.modelRole().trim());

        AgentPO agent = new AgentPO();
        agent.setWorkspaceId(workspaceService.requirePersonalWorkspace(userId));
        agent.setOwnerId(userId);
        agent.setCode(code);
        applyEditableFields(agent, request.name(), request.description(),
                request.systemPrompt(), request.modelRole(), request.answerPolicy(), request.temperature());
        // 新建行必须有策略：这里显式落默认，而不是依赖 DDL 的 DEFAULT——
        // 库里补了默认值，内存里的对象仍然是 null，紧接着的 toResponse 就会回一个 null 给前端
        if (agent.getAnswerPolicy() == null) {
            agent.setAnswerPolicy(AnswerPolicy.DEFAULT.name());
        }
        // 不自动发布：§十八 要求"用户可以先测试 Agent，再发布"，DRAFT 就是那个"先"
        agent.setStatus(AgentStatus.DEFAULT.name());
        agent.setBuiltin(false);
        LocalDateTime now = LocalDateTime.now();
        agent.setCreatedAt(now);
        agent.setUpdatedAt(now);
        agentMapper.insert(agent);

        // 绑定在落库前校验（越权/不存在的库必须在这里被拒，不然会存下一个查不到数据的 id）
        replaceKnowledgeBases(userId, agent.getId(), request.kbIds());
        replaceTools(agent.getId(), request.toolCodes());
        log.info("创建 Agent id={}, userId={}, code={}, 绑定知识库 {} 个、工具 {}",
                agent.getId(), userId, code, kbIdsOf(agent.getId()).size(),
                toolCodesOf(agent.getId()).isEmpty() ? "全部" : toolCodesOf(agent.getId()));
        return toResponse(agent);
    }

    /**
     * 局部更新 Agent：{@code null} 字段"不改"，空列表表示"解绑/回到不限制"。
     *
     * <p>启停也走这里（{@code status} 字段），复用同一套局部更新语义，不再单独开接口。
     *
     * @param userId   归属用户
     * @param agentId  目标 Agent 主键
     * @param request  更新参数；为 null 的字段不改，{@code status} 变更会做状态机校验
     * @return 更新后的 Agent 视图
     */
    @Transactional
    public AgentResponse update(Long userId, Long agentId, UpdateAgentRequest request) {
        AgentPO agent = requireOwned(userId, agentId);

        // 局部更新：内部逐个字段判 null（null = 不改）
        applyEditableFields(agent, request.name(), request.description(),
                request.systemPrompt(), request.modelRole(), request.answerPolicy(), request.temperature());
        if (StringUtils.hasText(request.status())) {
            AgentStatus target = AgentStatus.parse(request.status());
            requireStatusChangeAllowed(agent, target);
            agent.setStatus(target.name());
        }
        agent.setUpdatedAt(LocalDateTime.now());
        agentMapper.updateById(agent);

        if (request.kbIds() != null) {
            // null = 不改；空列表 = 解绑（回到"不限制"）
            replaceKnowledgeBases(userId, agentId, request.kbIds());
        }
        if (request.toolCodes() != null) {
            // 与 kbIds 同样的约定：null = 不改；空列表 = 回到"不限制"
            replaceTools(agentId, request.toolCodes());
        }
        log.info("更新 Agent id={}, userId={}, status={}, 绑定知识库 {} 个、工具 {}",
                agentId, userId, agent.getStatus(), kbIdsOf(agentId).size(),
                toolCodesOf(agentId).isEmpty() ? "全部" : toolCodesOf(agentId));
        return toResponse(agent);
    }

    /** 复制一个 Agent（§十五）：新 code 由服务端生成，副本回到 DRAFT——复制品未经测试不该直接生效。 */
    @Transactional
    public AgentResponse duplicate(Long userId, Long agentId) {
        AgentPO source = requireOwned(userId, agentId);

        AgentPO copy = new AgentPO();
        copy.setWorkspaceId(source.getWorkspaceId() == null
                ? workspaceService.requirePersonalWorkspace(userId) : source.getWorkspaceId());
        copy.setOwnerId(userId);
        copy.setCode(nextAvailableCode(userId, source.getCode()));
        copy.setName(cut(source.getName() + " 副本", 128));
        copy.setDescription(source.getDescription());
        copy.setSystemPrompt(source.getSystemPrompt());
        copy.setModelRole(source.getModelRole());
        // 策略跟着复制：复制一个 Agent 的意图是"拿它改"，少复制一样会得到一个行为不同的副本
        //（还不报错）——与上面复制 toolCodes 是同一条理由
        copy.setAnswerPolicy(source.getAnswerPolicy());
        // 温度同理：副本少带一样东西，就是"复制出来的 Agent 行为不一样，还不报错"
        copy.setTemperature(source.getTemperature());
        copy.setStatus(AgentStatus.DRAFT.name());
        // 副本不是内置项：用户复制出来就是为了改
        copy.setBuiltin(false);
        LocalDateTime now = LocalDateTime.now();
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        agentMapper.insert(copy);

        replaceKnowledgeBases(userId, copy.getId(), kbIdsOf(agentId));
        // 工具白名单跟着复制：用户复制一个 Agent 的意图是"拿它改"，少复制一样东西
        // 会得到一个行为不同的副本（还不报错）
        replaceTools(copy.getId(), toolCodesOf(agentId));
        log.info("复制 Agent 源 id={} → 新 id={}, code={}", agentId, copy.getId(), copy.getCode());
        return toResponse(copy);
    }

    /**
     * 删除当前用户自己的 Agent，并级联清掉知识库/工具绑定行（避免无主数据）。
     *
     * <p>内置默认 Agent 不可删——AGENT 模式需要它兜底。
     *
     * @param userId   归属用户
     * @param agentId  目标 Agent 主键
     */
    @Transactional
    public void delete(Long userId, Long agentId) {
        AgentPO agent = requireOwned(userId, agentId);
        if (Boolean.TRUE.equals(agent.getBuiltin())) {
            throw new BusinessException(ErrorCode.AGENT_BUILTIN_READONLY,
                    "内置默认 Agent 不可删除（AGENT 模式需要它兜底）；可以改名、改提示词，或新建自己的 Agent");
        }
        agentMapper.deleteById(agentId);
        // 绑定是物理行，随 Agent 一起清掉，避免留下指向已删 Agent 的无主数据
        agentKbMapper.delete(Wrappers.<AgentKnowledgeBasePO>lambdaQuery()
                .eq(AgentKnowledgeBasePO::getAgentId, agentId));
        agentToolMapper.delete(Wrappers.<AgentToolPO>lambdaQuery()
                .eq(AgentToolPO::getAgentId, agentId));
        log.info("删除 Agent id={}, userId={}", agentId, userId);
    }

    // ------------------------------------------------------------------
    // Definition 装配（资源 → 值对象，运行期的唯一入口）
    // ------------------------------------------------------------------

    /**
     * 取<b>可被使用</b>的 Agent 定义：会话选用它时会走这里。
     *
     * <p>三道检查都必须在"提问之前"做，而不是让链路带着一个不可用的 Agent 跑下去：
     * 归属（404）、发布状态（DRAFT 5008 / DISABLED 5009）。
     */
    public AgentDefinition requireDefinition(Long userId, Long agentId) {
        AgentPO agent = requireOwned(userId, agentId);
        AgentStatus status = AgentStatus.parse(agent.getStatus());
        if (status == AgentStatus.DRAFT) {
            throw new BusinessException(ErrorCode.AGENT_NOT_PUBLISHED,
                    "Agent「%s」还是草稿（DRAFT），先测试再发布后才能用于对话".formatted(agent.getName()));
        }
        if (status == AgentStatus.DISABLED) {
            throw new BusinessException(ErrorCode.AGENT_DISABLED,
                    "Agent「%s」已停用，重新启用后才能用于对话".formatted(agent.getName()));
        }
        return toDefinition(agent);
    }

    /**
     * 取<b>用于试跑</b>的 Agent 定义（§十八 Test Chat）：只校验归属，<b>不校验状态</b>。
     *
     * <p>为什么这里和 {@link #requireDefinition} 不一样：§十八 的流程是"先测试再发布"，
     * 草稿必须能被跑起来，否则发布就成了盲盒。反过来，DISABLED 的 Agent 也允许试跑——
     * 用户停用它之后仍需要能确认"是不是它的问题"。
     *
     * <p>宽松的边界很明确：<b>只放宽状态，归属一步不让</b>（越权仍然是 404）。
     */
    public AgentDefinition testDefinition(Long userId, Long agentId) {
        return toDefinition(requireOwned(userId, agentId));
    }

    /**
     * 取该用户的<b>内置默认 Agent</b>（AGENT 会话未指定 Agent 时用）。
     *
     * <p>幂等：缺失（首次使用、或被人为清库）时按 {@code AgentPrompts} 的默认值补一行出来，
     * 与 {@code WorkspaceService.requirePersonalWorkspace} 是同一种"按需补齐"做法。
     * 内置项不可删除/停用，所以这里取到的必然是 PUBLISHED。
     */
    @Transactional
    public AgentDefinition resolveDefault(Long userId) {
        AgentPO builtin = agentMapper.selectOne(Wrappers.<AgentPO>lambdaQuery()
                .eq(AgentPO::getOwnerId, userId)
                .eq(AgentPO::getCode, AgentPrompts.BUILTIN_CODE)
                .last("LIMIT 1"));
        if (builtin == null) {
            builtin = createBuiltin(userId);
        } else {
            boolean changed = false;
            if (!AgentStatus.PUBLISHED.name().equals(builtin.getStatus())) {
                // 历史数据/直接造库可能留下非 PUBLISHED 的内置行：就地纠正，
                // 否则 AGENT 模式会莫名其妙不可用，而用户什么都没改过
                builtin.setStatus(AgentStatus.PUBLISHED.name());
                changed = true;
            }
            if (isUntouchedLegacyPrompt(builtin.getSystemPrompt())) {
                // 4.4：旧默认提示词里写着"检索不到就如实说 / 不要用自身知识回答"，
                // 与默认策略 KB_PREFERRED 直接矛盾。只升"还是机器播种的那份文本"的行——
                // 用户改过的一个字都不动（策略块里有让位条款顶着，见 docs/decisions.md B.41）
                builtin.setSystemPrompt(AgentPrompts.AGENT_SYSTEM);
                changed = true;
                log.info("内置默认 Agent 的提示词仍是 4.4 之前的播种文本，就地升级 id={}, userId={}",
                        builtin.getId(), userId);
            }
            if (changed) {
                builtin.setUpdatedAt(LocalDateTime.now());
                agentMapper.updateById(builtin);
            }
        }
        return toDefinition(builtin);
    }

    /**
     * 这一行的提示词是否还<b>原样</b>是 4.4 之前机器播种的那份？
     *
     * <p>两边都 {@code strip()} 再比：{@code createBuiltin} 存的是带尾换行的文本块，
     * 而 {@code applyEditableFields} 保存时 trim 过——不统一就认不出"没改过"。
     * 空提示词不算（那种行由 {@code toDefinition} 回落到当前默认，本就不必改写）。
     */
    private boolean isUntouchedLegacyPrompt(String stored) {
        return StringUtils.hasText(stored)
                && AgentPrompts.AGENT_SYSTEM_LEGACY.strip().equals(stored.strip());
    }

    /** 内置默认 Agent 的那一行；不暴露为 API，只由 {@link #resolveDefault} 按需补齐。 */
    private AgentPO createBuiltin(Long userId) {
        AgentPO agent = new AgentPO();
        agent.setWorkspaceId(workspaceService.requirePersonalWorkspace(userId));
        agent.setOwnerId(userId);
        agent.setCode(AgentPrompts.BUILTIN_CODE);
        agent.setName(AgentPrompts.BUILTIN_NAME);
        agent.setDescription(AgentPrompts.BUILTIN_DESCRIPTION);
        agent.setSystemPrompt(AgentPrompts.AGENT_SYSTEM);
        agent.setModelRole(AiModelProperties.Role.CHAT_AGENT);
        agent.setAnswerPolicy(AnswerPolicy.DEFAULT.name());
        agent.setStatus(AgentStatus.PUBLISHED.name());
        agent.setBuiltin(true);
        LocalDateTime now = LocalDateTime.now();
        agent.setCreatedAt(now);
        agent.setUpdatedAt(now);
        agentMapper.insert(agent);
        log.info("创建内置默认 Agent id={}, userId={}", agent.getId(), userId);
        return agent;
    }

    /**
     * 实体 → 值对象。空提示/空角色在这里补默认值，<b>运行期不再兜底</b>：
     * 只有一个地方决定"没配时用什么"，就不会出现"两条链路各兜各的、结果行为不一致"。
     */
    private AgentDefinition toDefinition(AgentPO agent) {
        return new AgentDefinition(
                agent.getId(),
                agent.getCode(),
                agent.getName(),
                StringUtils.hasText(agent.getSystemPrompt())
                        ? agent.getSystemPrompt() : AgentPrompts.AGENT_SYSTEM,
                StringUtils.hasText(agent.getModelRole())
                        ? agent.getModelRole() : AiModelProperties.Role.CHAT_AGENT,
                // null 原样传下去 = "跟随全局默认"，运行期由 ChatModel 的默认选项兜住。
                // 这里**不能**补成 ai.defaults.temperature：那会把"跟随全局"变成
                // "装配时刻的全局值快照"，运维改了配置，老 Agent 却还钉在旧值上
                agent.getTemperature(),
                kbIdsOf(agent.getId()),
                toolCodesOf(agent.getId()),
                // 空 = 没配过（DDL 默认值把老行补成了 KB_PREFERRED，这里兜住 null）
                AnswerPolicy.parse(agent.getAnswerPolicy()),
                Boolean.TRUE.equals(agent.getBuiltin()));
    }

    // ------------------------------------------------------------------
    // 内部校验
    // ------------------------------------------------------------------

    private void applyEditableFields(AgentPO agent, String name, String description,
                                     String systemPrompt, String modelRole, String answerPolicy,
                                     Double temperature) {
        if (StringUtils.hasText(name)) {
            agent.setName(name.trim());
        }
        if (description != null) {
            agent.setDescription(StringUtils.hasText(description) ? description.trim() : null);
        }
        if (systemPrompt != null) {
            // 空字符串 = 回到默认提示（与"不改"区分开：null 才是不改）
            agent.setSystemPrompt(StringUtils.hasText(systemPrompt) ? systemPrompt.trim() : null);
        }
        if (StringUtils.hasText(modelRole)) {
            String role = modelRole.trim();
            requireConfiguredModelRole(role);
            agent.setModelRole(role);
        }
        if (StringUtils.hasText(answerPolicy)) {
            // 解析一次就同时做了两件事：非法值在写入口报 1001（不存下没人认识的值），
            // 大小写归一后落库（枚举名是稳定的对外取值）
            agent.setAnswerPolicy(AnswerPolicy.parse(answerPolicy).name());
        }
        if (temperature != null) {
            // 与 answerPolicy 同一位置、同一条理由：校验只在这一处，运行期不再兜底。
            // 界面上的输入框只能拦住手输，拦不住 curl——而越界的温度不会报错，
            // 它只会让模型悄悄按厂商的默认值跑（OpenAI 直接 400，压到 [0,2] 之外的值
            // Anthropic 会拒），症状是"这个 Agent 时不时抽风"，最难查的一类
            agent.setTemperature(requireTemperatureInRange(temperature));
        }
    }

    /**
     * 采样温度必须落在 0–2。
     *
     * <p>上限取 2 而不是 1：Anthropic 系模型允许 0–1，OpenAI 允许 0–2，取两家的并集
     * （0–2）才不会把 OpenAI 上合法的 1.5 挡在门外。超过 2 没有任何厂商接受，
     * 那才是真正无意义的取值。下限 0 是闭区间：0 = 贪心解码，是完全合法的诉求。
     */
    private double requireTemperatureInRange(double temperature) {
        if (temperature < 0 || temperature > 2) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "采样温度必须在 0 到 2 之间，当前是 " + temperature);
        }
        return temperature;
    }

    /**
     * 模型角色必须是 {@code ai.roles} 里真实存在的 key。
     *
     * <p>为什么在写入口就校验：不校验的话，用户能存下一个不存在的角色，
     * 直到提问时才报 4006——那时用户会以为"Agent 坏了"，而不是"我填错了角色"。
     */
    private void requireConfiguredModelRole(String role) {
        if (!chatClientFactory.configuredRoles().contains(role)) {
            throw new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                    "模型角色 %s 未配置。已配置：%s".formatted(role, chatClientFactory.configuredRoles()));
        }
    }

    private void requireStatusChangeAllowed(AgentPO agent, AgentStatus target) {
        if (Boolean.TRUE.equals(agent.getBuiltin()) && target != AgentStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.AGENT_BUILTIN_READONLY,
                    "内置默认 Agent 不能停用或退回草稿：AGENT 模式需要它兜底。"
                            + "若要停用检索类行为，请新建一个自己的 Agent 并选用它");
        }
    }

    private void replaceTools(Long agentId, List<String> rawToolCodes) {
        // null 与空列表都表示"不限制"（与 kbIds 的语义一致：空 = 用全部启用中的内置工具）。
        // 这里不做"空列表 = 不给任何工具"的解读——那样会让"想清空白名单"变成"把 Agent 变哑巴"
        List<String> codes = toolRegistry.requireKnownTools(rawToolCodes);
        agentToolMapper.delete(Wrappers.<AgentToolPO>lambdaQuery()
                .eq(AgentToolPO::getAgentId, agentId));
        for (String code : codes) {
            AgentToolPO link = new AgentToolPO();
            link.setAgentId(agentId);
            link.setToolCode(code);
            link.setCreatedAt(LocalDateTime.now());
            agentToolMapper.insert(link);
        }
    }

    private void replaceKnowledgeBases(Long userId, Long agentId, List<Long> rawKbIds) {
        List<Long> kbIds = rawKbIds == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(rawKbIds));
        // 越权/不存在的库在这里就被拒绝：绑上别人的库不会报错，只会"检索不到东西"，
        // 那种失败模式最难排查（与 RAG 会话绑库的做法一致）
        for (Long kbId : kbIds) {
            knowledgeBaseService.requireOwned(userId, kbId);
        }
        agentKbMapper.delete(Wrappers.<AgentKnowledgeBasePO>lambdaQuery()
                .eq(AgentKnowledgeBasePO::getAgentId, agentId));
        for (Long kbId : kbIds) {
            AgentKnowledgeBasePO link = new AgentKnowledgeBasePO();
            link.setAgentId(agentId);
            link.setKbId(kbId);
            link.setCreatedAt(LocalDateTime.now());
            agentKbMapper.insert(link);
        }
    }

    private boolean existsCode(Long userId, String code) {
        return agentMapper.selectCount(Wrappers.<AgentPO>lambdaQuery()
                .eq(AgentPO::getOwnerId, userId)
                .eq(AgentPO::getCode, code)) > 0;
    }

    /** 副本的 code：{@code source-copy}、{@code source-copy-2}…直到不冲突。 */
    private String nextAvailableCode(Long userId, String sourceCode) {
        String base = cut(sourceCode + "-copy", 64);
        String candidate = base;
        for (int i = 2; existsCode(userId, candidate); i++) {
            String suffix = "-" + i;
            candidate = cut(base, 64 - suffix.length()) + suffix;
        }
        return candidate;
    }

    private String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private AgentResponse toResponse(AgentPO agent) {
        return new AgentResponse(
                agent.getId(),
                agent.getCode(),
                agent.getName(),
                agent.getDescription(),
                agent.getSystemPrompt(),
                agent.getModelRole(),
                // 对外返回的一定是合法值：直接插库（测试/运维）留下的 null 在这里被收敛成默认
                AnswerPolicy.parse(agent.getAnswerPolicy()).name(),
                // 原样返回（含 null = 跟随服务端默认）：界面据此显示"服务端默认"，
                // 不在这里替换成具体数字——数字一旦回填，用户就分不清那是自己配的还是系统的
                agent.getTemperature(),
                agent.getStatus(),
                agent.getBuiltin(),
                kbIdsOf(agent.getId()),
                toolCodesOf(agent.getId()),
                agent.getCreatedAt(),
                agent.getUpdatedAt());
    }
}
