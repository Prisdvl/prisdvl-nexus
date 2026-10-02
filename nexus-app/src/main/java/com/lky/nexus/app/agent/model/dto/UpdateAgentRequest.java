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

        /**
         * 回答策略（4.4）：{@code KB_PREFERRED} / {@code KB_ONLY}；null = 不改。
         *
         * <p>判空即不改，与 {@code modelRole} 同一套约定；非法值在服务层报 1001。
         */
        String answerPolicy,

        /**
         * 采样温度（5.4，0–2）；<b>null = 不改</b>，与 {@code answerPolicy} 同一套约定。
         *
         * <p>注意这里<b>没有</b>"改回跟随全局"的写法：本字段与 {@code kbIds}/{@code toolCodes}
         * 不同——那两个把"空"复用成了"不限制"，因为那是它们的正常状态；温度的正常状态是
         * "跟随全局"，而一个 Double 字段的 null 已经被"不改"占用了。真要清空，只能新建 Agent。
         * 这在界面上要写清（温度输入框旁标注），否则用户会以为清空输入框就是清掉配置。
         */
        Double temperature,

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
