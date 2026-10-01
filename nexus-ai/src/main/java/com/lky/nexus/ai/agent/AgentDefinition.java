package com.lky.nexus.ai.agent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Agent 的<b>不可变配置</b>（平台化 §十七：Agent Definition 与 Agent Runtime 分离）。
 *
 * <p>定位：数据库里的 {@code agent} 行 → 本记录 → {@code AgentRuntime} → 现有 Agent Loop。
 * 循环里只认这个值对象，<b>不认 MyBatis 实体</b>——后者的字段（deleted/updatedAt/owner_id…）
 * 对"怎么跑一次 Agent"没有意义，混进去只会让循环被存储细节传染。
 *
 * <p>为什么放在 {@code nexus-ai}：它是"Agent 是什么"的抽象（配置形状），
 * 与厂商、与存储都无关；{@code nexus-app} 决定"从哪来"（数据库）。
 * 依赖方向因此是 {@code app → ai}，不需要 {@code ai → app} 这种倒挂。
 *
 * <p>刻意<b>没有</b>的字段：{@code maxIterations} / {@code timeoutMs}。
 * 它们今天没有任何机制会读（轮数上限在框架的 ToolCallingManager 里，超时在 HTTP 层），
 * 提前放进来会让调用方以为"配了就生效"，这是最难查的一类假配置。
 *
 * <p>{@code toolCodes} 是 4.3（Tool Definition）才加上的：字段与读它的机制
 * （AgentToolFactory 装配工具时取交集）**同一次改动里**落地——这正是上面那条规则的用法。
 *
 * <p>{@link #answerPolicy} 是 4.4 加上的，遵守同一条规则：字段与读它的机制
 * （{@code AgentPrompts.systemPromptFor} 据此追加策略块）一起落地。它回答的是
 * "没有可引用资料时能不能用模型自身的知识"，与 {@link #kbIds}（检索范围）、
 * {@link #toolCodes}（工具白名单）同层——都是"这个 Agent 被允许做什么"。
 *
 * <p>字段语义上的一条<b>刻意区分</b>（与 RAG 会话相反，勿混）：
 * {@link #kbIds} <b>为空表示不限制</b>（检索该用户的全部知识库，即今天的行为），
 * 而 RAG 会话的 {@code kbIds} 为空是"必须先绑定才能提问"的硬错误。
 * 原因是两者的控制点不同：RAG 的检索范围由用户显式指定，Agent 的检索范围由模型在
 * 工具调用里自主决定，绑库只是给出一个默认视野。
 */
public record AgentDefinition(

        /** 对应 agent.id；内置默认 Agent 也是真实一行，因此这里恒非空。 */
        Long agentId,

        /** 稳定标识，如 {@code builtin.default}。 */
        String code,

        /** 显示名（会出现在痕迹与界面上的"哪个 Agent 在跑"）。 */
        String name,

        /** 系统提示（指令层）；空值在装配阶段就被补成默认，运行期不做兜底。 */
        String systemPrompt,

        /** 模型逻辑角色（{@code ai.roles} 里的 key），如 {@code chat.agent}。 */
        String modelRole,

        /** 绑定的知识库；<b>空 = 不限制</b>（检索该用户全部知识库）。 */
        List<Long> kbIds,

        /**
         * 可用的工具（code，如 {@code knowledge_search}）；<b>空 = 不限制</b>
         * （装该用户全部启用中的内置工具）。语义与 {@link #kbIds} 刻意保持一致。
         *
         * <p>Phase 4.1 时刻意没有这个字段（当时只有一个内置工具，白名单只有信息量没有机制）；
         * 4.3 的 Tool 体系落地后它才第一次有意义：装配工具时按它取交集。
         */
        List<String> toolCodes,

        /**
         * 没有可引用的资料时怎么答（4.4，{@code agent.answer_policy}）。
         *
         * <p>它不是"提示词的一部分"，而是与 {@link #systemPrompt} 并列的另一层：
         * 提示词是用户写的数据，本字段由平台按 {@code AgentPrompts} 的策略块表达。
         * 两者在请求期由 {@code AgentPrompts.systemPromptFor} 合成一条系统提示。
         */
        AnswerPolicy answerPolicy,

        /** 是否系统内置：内置项不可删除、不可停用（保证 AGENT 模式总有可用的默认 Agent）。 */
        boolean builtin) {

    public AgentDefinition {
        Objects.requireNonNull(agentId, "agentId 不能为空：内置默认 Agent 也是真实一行");
        code = requireText(code, "code");
        name = requireText(name, "name");
        systemPrompt = requireText(systemPrompt, "systemPrompt");
        modelRole = requireText(modelRole, "modelRole");
        // 去重并保序：绑同一个库两次没有意义，留着只会让"绑了 3 个库"变成错觉
        kbIds = List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(kbIds, "kbIds 不能为 null，空列表表示不限制")));
        // 工具白名单同理去重保序（空 = 不限制，不是"没有工具"）
        toolCodes = List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(toolCodes, "toolCodes 不能为 null，空列表表示不限制")));
        // 回答策略必填：装配阶段（AgentService.toDefinition）就该解析好，运行期不再兜底——
        // 否则"没配时用什么"会有第二个决定的地方，那正是本项目一路在防的静默不一致
        Objects.requireNonNull(answerPolicy, "answerPolicy 不能为 null：装配阶段应补成 AnswerPolicy.DEFAULT");
    }

    /** 是否限定了检索范围（为空 = 不限制）。 */
    public boolean scoped() {
        return !kbIds.isEmpty();
    }

    /** 是否限定了工具白名单（为空 = 不限制，装该用户全部启用中的内置工具）。 */
    public boolean toolScoped() {
        return !toolCodes.isEmpty();
    }

    /** 给日志与痕迹用的一句话描述：跑的是哪个 Agent、检索范围多大、工具白名单多宽。 */
    public String describe() {
        return "%s(%s, %s, %s)".formatted(name, code,
                scoped() ? kbIds.size() + " 个知识库" : "全部知识库",
                toolScoped() ? toolCodes.size() + " 个工具" : "全部工具");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AgentDefinition.%s 不能为空".formatted(field));
        }
        return value;
    }
}
