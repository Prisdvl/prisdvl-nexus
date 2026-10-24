package com.lky.nexus.knowledge.model.dto;

/**
 * 重排后的一条。
 *
 * <p>只带重排分数与名次，不带标题正文：重排的候选来自 {@code fused}，
 * 内容是一致的，再复制一遍只会让响应体翻倍。
 *
 * @param rank        重排名次，从 1 开始
 * @param rerankScore 重排模型给出的分数（越高越相关）
 */
public record ExplainRerankHit(
        Long kbId,
        Long chunkId,
        int rank,
        double rerankScore) {
}
