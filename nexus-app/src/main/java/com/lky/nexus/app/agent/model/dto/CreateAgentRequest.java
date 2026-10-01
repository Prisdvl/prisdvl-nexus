package com.lky.nexus.app.agent.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新建 Agent 请求（§十五/§十八）。
 *
 * <p>{@code code} 是<b>稳定标识</b>而不是显示名：显示名可以随时改（中文名很适合改），
 * 但 code 将来会被 Tool / Workflow / Application 引用，改名不该影响引用。
 * 因此这里就要求它是受限字符集，而不是让中文名兼作标识。
 *
 * <p>{@code kbIds} 可空：<b>空 = 不限制</b>（检索该用户全部知识库，即今天的行为）。
 * 注意这与 RAG 会话的 kbIds 语义相反——那边为空是"必须绑定才能提问"的硬错误，
 * 语义差异写在 {@code AgentDefinition} 的类注释里。
 */
public record CreateAgentRequest(

        @NotBlank(message = "Agent 标识不能为空")
        @Pattern(regexp = "[a-z0-9][a-z0-9._-]{1,63}",
                message = "Agent 标识只能用小写字母、数字、点、下划线、连字符（2-64 位），且首字符为字母或数字")
        String code,

        @NotBlank(message = "Agent 名称不能为空")
        @Size(max = 128, message = "Agent 名称长度不能超过 128")
        String name,

        @Size(max = 512, message = "描述长度不能超过 512")
        String description,

        /** 系统提示；为空则用内置默认提示（不是"没有提示"）。 */
        String systemPrompt,

        /** 模型逻辑角色（ai.roles 的 key）；为空则用 chat.agent。 */
        String modelRole,

        /**
         * 回答策略（4.4）：{@code KB_PREFERRED}（默认）/ {@code KB_ONLY}；为空则用默认。
         *
         * <p>它决定"没有可引用的资料时能不能用模型自身的知识回答"，与 {@code kbIds}
         * （检索范围）、{@code toolCodes}（工具白名单）同层——都是这个 Agent 被允许做什么。
         */
        String answerPolicy,

        /** 绑定的知识库 id；空表示不限制。 */
        List<Long> kbIds,

        /**
         * 可用的工具 code（4.3）；<b>空 = 不限制</b>（装该用户全部启用中的内置工具）。
         *
         * <p>与 {@code kbIds} 同一套语义，别与 RAG 会话相反的那条搞混（见 {@code AgentDefinition}）。
         */
        List<String> toolCodes) {
}
