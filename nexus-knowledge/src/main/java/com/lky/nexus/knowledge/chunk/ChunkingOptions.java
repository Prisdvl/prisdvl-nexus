package com.lky.nexus.knowledge.chunk;

/**
 * 切分参数（建库时冻结，见 ADR-008 同类理由：同一库内切片标准必须一致）。
 *
 * @param maxTokens     单个切片的目标上限（token）；超出则按语义边界继续切
 * @param overlapTokens 段落级切分时的重叠量；标题级切分不使用重叠（标题路径已提供上下文）
 * @param minTokens     最小切片；过小的切片会被并回上一块，避免产生"只有标题没有正文"的碎片
 */
public record ChunkingOptions(int maxTokens, int overlapTokens, int minTokens) {

    public static final int DEFAULT_MAX_TOKENS = 400;
    public static final int DEFAULT_OVERLAP_TOKENS = 50;
    public static final int DEFAULT_MIN_TOKENS = 60;

    /** 返回安全默认切分参数（max=400 / overlap=50 / min=60）。 */
    public static ChunkingOptions defaults() {
        return new ChunkingOptions(DEFAULT_MAX_TOKENS, DEFAULT_OVERLAP_TOKENS, DEFAULT_MIN_TOKENS);
    }

    /**
     * 用知识库配置构造，并做合理性校验。
     *
     * <p>为什么要在这里兜底：切分参数来自用户输入，若 overlap >= max 会让切片无限重叠，
     * 既浪费 Token 又可能造成死循环式的重复内容。
     */
    public static ChunkingOptions of(Integer maxTokens, Integer overlapTokens, Integer minTokens) {
        int max = maxTokens == null || maxTokens <= 0 ? DEFAULT_MAX_TOKENS : maxTokens;
        int overlap = overlapTokens == null || overlapTokens < 0 ? DEFAULT_OVERLAP_TOKENS : overlapTokens;
        int min = minTokens == null || minTokens <= 0 ? DEFAULT_MIN_TOKENS : minTokens;
        if (overlap >= max) {
            overlap = Math.max(0, max / 8);
        }
        return new ChunkingOptions(max, overlap, Math.min(min, max));
    }
}
