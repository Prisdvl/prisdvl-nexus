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
 * <p>刻意<b>没有</b>的字段：{@code maxIterations} / {@code timeoutMs} / {@code toolCodes}。
 * 前两个今天没有任何机制会读（轮数上限在框架的 ToolCallingManager 里，超时在 HTTP 层），
 * 工具白名单要等 Tool 体系（P0 的下一项）落地——字段在那儿才有意义。
 * 提前放进来会让调用方以为"配了就生效"，这是最难查的一类假配置。
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
    }

    /** 是否限定了检索范围（为空 = 不限制）。 */
    public boolean scoped() {
        return !kbIds.isEmpty();
    }

    /** 给日志与痕迹用的一句话描述：跑的是哪个 Agent、检索范围多大。 */
    public String describe() {
        return "%s(%s, %s)".formatted(name, code, scoped() ? kbIds.size() + " 个知识库" : "全部知识库");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AgentDefinition.%s 不能为空".formatted(field));
        }
        return value;
    }
}
