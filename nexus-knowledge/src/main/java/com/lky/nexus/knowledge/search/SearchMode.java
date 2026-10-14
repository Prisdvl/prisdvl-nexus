package com.lky.nexus.knowledge.search;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;

import java.util.Locale;

/** 检索模式。让三种模式都能被单独调用，是调参与验证融合效果的前提。 */
public enum SearchMode {
    /** 只用关键词（BM25）。精确术语、代码标识符上有优势。 */
    BM25,
    /** 只用向量。同义改写、口语化提问上有优势。 */
    VECTOR,
    /** 两路 + RRF 融合（默认）。 */
    HYBRID;

    /**
     * 将外部传入的检索模式字符串解析为 {@link SearchMode}。
     *
     * @param raw 原始字符串（可为 null 或空白；此时回退到默认的 HYBRID）
     * @return 对应的检索模式
     * @throws BusinessException 当 raw 非空但不属于 bm25 / vector / hybrid 时
     */
    public static SearchMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return HYBRID;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "不支持的检索模式: %s（可选 bm25 / vector / hybrid）".formatted(raw));
        }
    }

    /** 该模式是否需要走 BM25 一路（BM25 或 HYBRID 时为真）。 */
    public boolean usesBm25() {
        return this == BM25 || this == HYBRID;
    }

    /** 该模式是否需要走向量一路（VECTOR 或 HYBRID 时为真）。 */
    public boolean usesVector() {
        return this == VECTOR || this == HYBRID;
    }
}
