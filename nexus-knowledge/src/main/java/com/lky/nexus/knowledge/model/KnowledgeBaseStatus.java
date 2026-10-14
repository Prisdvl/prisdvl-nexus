package com.lky.nexus.knowledge.model;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;

import java.util.Locale;

/**
 * 知识库状态。
 *
 * <p>禁用（DISABLED）后知识库仍保留数据，但**不参与检索**——
 * 用户想"临时下线某个库"时不必删除再重建（重建会重新冻结嵌入模型、重新嵌入全部文档）。
 */
public enum KnowledgeBaseStatus {

    /** 已启用（默认）：参与检索。 */
    ENABLED,

    /** 已禁用：不参与检索。 */
    DISABLED;

    public static final KnowledgeBaseStatus DEFAULT = ENABLED;

    /**
     * 解析知识库状态字符串。
     *
     * @param raw 客户端传入的原始字符串（忽略大小写与首尾空白）
     * @return 对应的知识库状态；{@code raw} 为空或空白时回退到 {@link #DEFAULT}
     * @throws BusinessException 当 {@code raw} 非空但不匹配任何枚举值时
     */
    public static KnowledgeBaseStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "不支持的知识库状态: %s（可选值：ENABLED / DISABLED）".formatted(raw));
        }
    }
}
