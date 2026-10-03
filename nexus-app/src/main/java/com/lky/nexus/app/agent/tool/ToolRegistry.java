package com.lky.nexus.app.agent.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.ai.agent.ToolDefinition;
import com.lky.nexus.app.agent.mapper.ToolMapper;
import com.lky.nexus.app.agent.model.entity.ToolPO;
import com.lky.nexus.app.workspace.WorkspaceService;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具注册表（4.3，§十九）：<b>"有哪些工具、这个用户能不能用、这个 Agent 装哪些"的唯一答案</b>。
 *
 * <p>它替掉的是两处硬编码：{@code AgentRuntime.configure} 里那句
 * {@code ToolCallbacks.from(knowledgeSearchTool)}（一次性调用与流式对话共用），
 * 以及 {@code McpEndpoint} 手写的 {@code knowledge_search} 名字/描述/schema。
 * 现在谁想知道"工具是什么"，答案只有一个来源：{@link ToolCatalog}（代码侧）+ 本类（用户态）。
 *
 * <p><b>覆盖行语义（本增量最容易误读的一条）</b>：{@code tool} 表的行是<b>用户覆盖</b>，
 * 不是定义，也<b>不是</b>默认值的来源——没有行 = 用代码侧的默认（启用）。
 * 所以"新用户"、"清库之后"、"迁移到新环境"都不会让工具凭空消失；
 * 反过来说，想找"这个用户停用了什么"，看的是<b>存在且 enabled=0</b> 的行。见 V13 的文件头。
 *
 * <p>三条"不许静默"的边界都在 {@link #resolveFor} 里显式处理：白名单里有不存在的工具、
 * 装配结果是 0 个工具（模型会退化成"只会凭自身知识回答"）。按 §1.11 第 10 条，
 * 它们既是日志也是计数。
 */
@Service
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ToolCatalog catalog;
    private final ToolMapper toolMapper;
    private final WorkspaceService workspaceService;
    private final ObservabilityRegistry observability;

    public ToolRegistry(ToolCatalog catalog,
                        ToolMapper toolMapper,
                        WorkspaceService workspaceService,
                        ObservabilityRegistry observability) {
        this.catalog = catalog;
        this.toolMapper = toolMapper;
        this.workspaceService = workspaceService;
        this.observability = observability;
    }

    /**
     * 该用户的工具目录（界面与 {@code GET /api/tools} 用）：每个内置工具一条，
     * {@code enabled} 是<b>覆盖后的真实状态</b>。
     */
    public List<ToolDefinition> catalogOf(Long userId) {
        Map<String, Boolean> overrides = overridesOf(userId);
        return catalog.all().stream()
                .map(registered -> registered.toDefinition(overrides.getOrDefault(registered.code(), true)))
                .toList();
    }

    /**
     * 装给某个 Agent 的工具集：<b>该用户启用中的 ∩ Agent 白名单</b>（白名单为空 = 不限制）。
     *
     * <p>两个"减项"的优先级是有意的：用户停用优先于白名单——一个被用户停用的工具，
     * 不该因为某个 Agent 的白名单里写了它而复活。
     */
    public List<ToolCatalog.Registered> resolveFor(Long userId, AgentDefinition definition) {
        Map<String, Boolean> overrides = overridesOf(userId);
        Set<String> whitelist = definition.toolScoped() ? Set.copyOf(definition.toolCodes()) : Set.of();
        if (definition.toolScoped()) {
            Set<String> known = catalog.all().stream()
                    .map(ToolCatalog.Registered::code).collect(Collectors.toSet());
            List<String> unknown = definition.toolCodes().stream().filter(code -> !known.contains(code)).toList();
            if (!unknown.isEmpty()) {
                // 写绑定时会校验（AgentService.requireKnownTools），走到这里说明是既有数据或工具被下架：
                // 静默忽略会让用户以为"绑了就会用"，所以必须留痕
                log.warn("Agent {} 的工具白名单里有不存在的工具：{}（已忽略）", definition.describe(), unknown);
            }
        }

        List<ToolCatalog.Registered> available = new ArrayList<>();
        List<String> disabledByUser = new ArrayList<>();
        for (ToolCatalog.Registered registered : catalog.all()) {
            if (definition.toolScoped() && !whitelist.contains(registered.code())) {
                continue;
            }
            if (!overrides.getOrDefault(registered.code(), true)) {
                disabledByUser.add(registered.code());
                continue;
            }
            available.add(registered);
        }

        if (available.isEmpty()) {
            // 0 个工具是"静默降级"的经典形态：对话照常回答，只是再也不检索了。
            // 按 §1.11 第 10 条，降级必须可计数，否则等于没问题
            observability.increment(ObservabilityRegistry.AGENT_TOOLS_EMPTY);
            log.warn("Agent {} 装配到 0 个工具（用户停用了 {}）：模型只能凭自身知识回答",
                    definition.describe(),
                    disabledByUser.isEmpty() ? "无" : String.join("、", disabledByUser));
        }
        warnIfPromptNamesMissingTools(definition, available);
        return available;
    }

    /**
     * 该用户<b>启用中</b>的工具（只过用户那一层，不含 Agent 白名单）。
     *
     * <p>给"直调工具"的路径用：MCP 的 {@code tools/list} 与 {@code tools/call}（5.9 八）。
     * 那里没有 Agent，因此只有"用户启用/停用"这一维——但也正因为没有 Agent，
     * 此前这条路径<b>完全没有权限判定</b>：用户在界面上停用了 {@code knowledge_search}，
     * {@code tools/list} 照旧把它列出来、{@code tools/call} 照旧执行。
     * 那是"开关看起来生效、实际只生效了一半"的典型。
     *
     * <p>与 {@link #resolveFor} 的关系：同一个 {@code overridesOf}，不复制一份判定逻辑，
     * 所以"停用优先于白名单"这类规则将来只会有一个版本。
     */
    public List<ToolCatalog.Registered> enabledFor(Long userId) {
        Map<String, Boolean> overrides = overridesOf(userId);
        return catalog.all().stream()
                .filter(registered -> overrides.getOrDefault(registered.code(), true))
                .toList();
    }

    /**
     * 提示词里点名了某个工具、而这次装配又没给它——把这件事留痕。
     *
     * <p><b>为什么这是 4.3 才有的问题</b>：在此之前，"提示词里写的那个工具"永远会被装上
     * （它就写死在装配处），两者不可能不一致。现在工具可以被用户停用、被 Agent 白名单收窄，
     * 于是出现了新的组合：内置默认 Agent 的提示词仍是
     * "{@code 必须先调用 knowledge_search 检索}"（那是数据库里的一行文本，用户改过的就更是），
     * 而这次请求一个检索工具都没装。模型会照提示词去用一把不存在的工具，
     * 而表现与"检索了没命中"一模一样——正是本项目反复吃亏的那类静默不一致。
     *
     * <p>这里只报警不拦截（不改提示词、不补工具）：提示词是用户的数据，我们无权代改；
     * 而"自动补上工具"会让停用这个开关重新变成一个假旋钮。
     * 真正的解法是把可用工具<b>生成进</b>提示词（而不是靠一句写死的名字），
     * 那会改到每一个既有 Agent 的模型输入，需要单独一轮验证——先如实留痕，不夹带。
     */
    private void warnIfPromptNamesMissingTools(AgentDefinition definition, List<ToolCatalog.Registered> assembled) {
        Set<String> assembledCodes = assembled.stream()
                .map(ToolCatalog.Registered::code).collect(Collectors.toSet());
        List<String> named = catalog.all().stream()
                .map(ToolCatalog.Registered::code)
                .filter(code -> !assembledCodes.contains(code))
                .filter(code -> definition.systemPrompt().contains(code))
                .toList();
        if (!named.isEmpty()) {
            observability.increment(ObservabilityRegistry.TOOL_PROMPT_MISMATCH);
            log.warn("Agent {} 的系统提示里点名了 {}，但这次没有装配它：模型可能去调用一个看不见的工具。"
                            + "（停用/白名单是用户的决定，这里只如实留痕）",
                    definition.describe(), named);
        }
    }

    /**
     * 启停一个工具（§十八 的"启停"落在工具上的那一半）。
     *
     * <p>没有覆盖行时插入一行（写的是 enabled 的当前值）；与现有值相同时不写库——
     * 免得每次点一下都刷 {@code updated_at}，让"最近改过"这件事失去意义。
     */
    @Transactional
    public ToolDefinition setEnabled(Long userId, String code, boolean enabled) {
        ToolCatalog.Registered registered = catalog.find(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND,
                        "没有这个工具：%s（可用：%s）".formatted(code, availableCodes())));
        ToolPO row = toolMapper.selectOne(Wrappers.<ToolPO>lambdaQuery()
                .eq(ToolPO::getOwnerId, userId)
                .eq(ToolPO::getCode, registered.code())
                .last("LIMIT 1"));
        if (row == null) {
            row = new ToolPO();
            row.setWorkspaceId(workspaceService.requirePersonalWorkspace(userId));
            row.setOwnerId(userId);
            row.setCode(registered.code());
            row.setEnabled(enabled);
            LocalDateTime now = LocalDateTime.now();
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            toolMapper.insert(row);
            log.info("写入工具状态（该用户的首个覆盖行）：userId={}，工具 {}，enabled={}", userId, code, enabled);
        } else if (!Boolean.valueOf(enabled).equals(row.getEnabled())) {
            row.setEnabled(enabled);
            row.setUpdatedAt(LocalDateTime.now());
            toolMapper.updateById(row);
            log.info("工具状态变更：userId={}，工具 {}，enabled={}", userId, code, enabled);
        } else {
            log.debug("工具状态未变化，跳过写入：userId={}，工具 {}，enabled={}", userId, code, enabled);
        }
        return registered.toDefinition(enabled);
    }

    /**
     * 校验一批工具 code 都是已登记的（Agent 绑定工具时用）：去重保序，不认识的直接拒。
     *
     * <p>为什么在写入侧就拒：把"绑了但用不了"的 code 存进库，症状要到运行时才出现
     * （模型少一个工具），而且那时已经没人知道当初是想绑什么了。
     */
    public List<String> requireKnownTools(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        Set<String> known = catalog.all().stream()
                .map(ToolCatalog.Registered::code).collect(Collectors.toSet());
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : codes) {
            String code = raw == null ? "" : raw.trim();
            if (!known.contains(code)) {
                throw new BusinessException(ErrorCode.TOOL_NOT_FOUND,
                        "工具 %s 不存在（可用：%s）".formatted(raw, availableCodes()));
            }
            result.add(code);
        }
        return List.copyOf(result);
    }

    /** 全部已登记工具的 code（报错信息与界面用）。 */
    public List<String> availableCodes() {
        return catalog.all().stream().map(ToolCatalog.Registered::code).toList();
    }

    /** 该用户的覆盖状态：只含<b>存在覆盖行</b>的工具（没有的走默认 = 启用）。 */
    private Map<String, Boolean> overridesOf(Long userId) {
        return toolMapper.selectList(Wrappers.<ToolPO>lambdaQuery()
                        .eq(ToolPO::getOwnerId, userId))
                .stream()
                .collect(Collectors.toMap(ToolPO::getCode, row -> Boolean.TRUE.equals(row.getEnabled())));
    }
}
