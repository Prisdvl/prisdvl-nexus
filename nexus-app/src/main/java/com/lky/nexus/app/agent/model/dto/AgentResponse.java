package com.lky.nexus.app.agent.model.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 视图（列表与详情共用）。
 *
 * <p>{@code kbIds} 与 {@code status} 的语义要在界面上说清楚，否则很容易被误读：
 * <ul>
 *   <li>{@code kbIds} 为空 = <b>不限制</b>（检索全部知识库），不是"检索不到东西"；</li>
 *   <li>只有 {@code PUBLISHED} 能被会话选用；{@code DRAFT} 可以先测试再发布；</li>
 *   <li>{@code builtin=true} 的是内置默认 Agent，不可删除、不可停用。</li>
 * </ul>
 */
public record AgentResponse(
        Long id,
        String code,
        String name,
        String description,
        String systemPrompt,
        String modelRole,
        /** 回答策略（4.4）：KB_PREFERRED / KB_ONLY。决定"没有可引用资料时能不能用自身知识"。 */
        String answerPolicy,
        /**
         * 采样温度（5.4）：{@code null} = <b>跟随服务端全局默认</b>（{@code ai.defaults.temperature}）。
         *
         * <p>界面必须把 null 显示成"服务端默认"而不是留空或显示 0：0 是"显式要求确定性"，
         * 是用户真的做过的选择，两者混起来用户就分不清自己有没有配过。
         */
        Double temperature,
        String status,
        Boolean builtin,
        List<Long> kbIds,
        /** 工具白名单（4.3）；空 = 不限制（全部启用中的内置工具）——与 kbIds 同一套语义。 */
        List<String> toolCodes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
