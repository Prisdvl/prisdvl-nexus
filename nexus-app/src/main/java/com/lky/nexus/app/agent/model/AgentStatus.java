package com.lky.nexus.app.agent.model;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;

/**
 * Agent 状态（§十八）。
 *
 * <p>状态机刻意很小，但每一条都有实际后果：
 * <ul>
 *   <li>{@link #DRAFT}：可以改、可以测试，<b>不能</b>被会话选用——"先测试再发布"是 §十八 的要求；</li>
 *   <li>{@link #PUBLISHED}：可被会话选用；</li>
 *   <li>{@link #DISABLED}：保留配置但停止服务；已被它服务过的会话再提问会被明确拒绝，
 *       而不是悄悄换回默认 Agent（那会让用户以为停用没生效）。</li>
 * </ul>
 *
 * <p>与知识库一样在实体层存字符串、在服务层做枚举转换（理由见 {@code ChatConversationPO} 的类注释）。
 */
public enum AgentStatus {

    DRAFT,
    PUBLISHED,
    DISABLED;

    /** 新建时的默认状态：不自动发布，逼一次"测试再发布"。 */
    public static final AgentStatus DEFAULT = DRAFT;

    public static AgentStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "不支持的 Agent 状态: %s（可选值：DRAFT / PUBLISHED / DISABLED）".formatted(raw));
        }
    }
}
