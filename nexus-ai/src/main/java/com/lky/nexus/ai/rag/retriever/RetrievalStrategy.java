package com.lky.nexus.ai.rag.retriever;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;

import java.util.Locale;

/**
 * 检索策略：让"怎么检索"成为一个可替换、可扩展的维度。
 *
 * <p>与 {@code SearchMode}（knowledge 模块，BM25/VECTOR/HYBRID）的关系：
 * {@code SearchMode} 是<b>面向用户的检索模式</b>（前端下拉框里的三个选项），
 * 本枚举是<b>内部的策略标识</b>，两者不是一回事——策略多了
 * {@code HYBRID_RERANK}，而它不该出现在用户的下拉框里（用户不需要知道重排的存在），
 * 未来还会加 GRAPH / SQL / KNOWLEDGE_GRAPH。因此保留两个枚举、
 * 在边界处做一次显式映射，而不是硬把用户选项当内部策略用。
 *
 * <p>新增一个策略要动的地方只有两处：这里的枚举 + 一个 {@link Retriever} 实现，
 * 业务代码（RAG / Agent / Controller）一行都不用改——这正是"抽象"要买到的东西。
 */
public enum RetrievalStrategy {

    /** 只用向量召回。同义改写、口语化提问上有优势。 */
    VECTOR,
    /** 只用关键词（BM25）召回。精确术语、代码标识符、人名地名上有优势。 */
    KEYWORD,
    /** 两路召回 + RRF 融合。默认策略。 */
    HYBRID,
    /** 在 {@link #HYBRID} 之上再做一次重排（更准，但多一次模型调用）。 */
    HYBRID_RERANK;

    /** 未显式指定时使用的策略。 */
    public static final RetrievalStrategy DEFAULT = HYBRID;

    /**
     * 解析策略名（大小写不敏感）。
     *
     * @throws BusinessException 1001：取值不在枚举内
     */
    public static RetrievalStrategy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "不支持的检索策略: %s（可选 vector / keyword / hybrid / hybrid_rerank）".formatted(raw));
        }
    }

    /** 是否最终需要向量召回（重排只是加工，不改变召回来源）。 */
    public boolean needsVector() {
        return this != KEYWORD;
    }

    /** 是否最终需要关键词召回。 */
    public boolean needsKeyword() {
        return this != VECTOR;
    }

    /** 是否需要在召回之后做重排。 */
    public boolean needsRerank() {
        return this == HYBRID_RERANK;
    }

    /**
     * 去掉重排装饰后的召回策略。
     *
     * <p>{@code HYBRID_RERANK} 的召回底座就是 {@link #HYBRID}——
     * 重排是"在结果上加一道工序"，不是另一种召回方式。
     */
    public RetrievalStrategy recallBase() {
        return this == HYBRID_RERANK ? HYBRID : this;
    }
}
