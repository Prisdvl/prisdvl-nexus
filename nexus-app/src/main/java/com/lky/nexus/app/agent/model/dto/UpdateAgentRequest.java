package com.lky.nexus.app.agent.model.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 修改 Agent 请求（局部更新：为 null 的字段不改）。
 *
 * <p>刻意<b>不允许</b>改 {@code code}：它是被引用的稳定标识，改它等于悄悄改掉别人的引用。
 * 要换标识请复制一个新 Agent（复制的 code 由服务端生成，见 {@code AgentService.duplicate}）。
 *
 * <p>{@code status} 的取值在服务层校验（DRAFT / PUBLISHED / DISABLED），
 * 内置默认 Agent 不允许改成 DISABLED——那会让 AGENT 模式失去兜底（见 {@code AgentService}）。
 */
public record UpdateAgentRequest(

        @Size(max = 128, message = "Agent 名称长度不能超过 128")
        String name,

        @Size(max = 512, message = "描述长度不能超过 512")
        String description,

        String systemPrompt,

        String modelRole,

        /** DRAFT / PUBLISHED / DISABLED。 */
        String status,

        /** 绑定的知识库 id；空列表 = 解绑（变成"不限制"），null = 不改。 */
        List<Long> kbIds,

        /**
         * 工具白名单（4.3）；空列表 = 回到"不限制"（全部启用中的内置工具），null = 不改。
         *
         * <p>注意"空列表 = 不限制"而不是"一个工具都不给"：后者会让一次手滑把 Agent 变成哑巴，
         * 而且症状是"回答突然不用资料了"（静默降级）。真要停用某个工具，去工具自己那里停。
         */
        List<String> toolCodes) {
}
