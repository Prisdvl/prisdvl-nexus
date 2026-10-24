package com.lky.nexus.knowledge.model.dto;

import java.util.List;

/**
 * RRF 融合后的一条，带两路名次与融合分。
 *
 * <p>{@code fusedScore} 就是 {@code 1/(60+bm25Rank) + 1/(60+vectorRank)}（缺失的一路按 0 计），
 * 与 {@code RrfFusion.DEFAULT_K = 60} 一致。把三个数一起给出来，
 * 才能验证"融合分不是拍脑袋排的"。
 *
 * @param rank       融合后名次，从 1 开始
 * @param fusedScore 融合分
 * @param bm25Rank   关键词路名次；不在该路时 {@code null}
 * @param vectorRank 向量路名次；不在该路时 {@code null}
 * @param matchedBy  命中说明（与 {@code /api/search} 同一格式）
 */
public record ExplainFusedHit(
        Long kbId,
        Long chunkId,
        Long documentId,
        String documentTitle,
        String headingPath,
        String snippet,
        int rank,
        double fusedScore,
        Integer bm25Rank,
        Integer vectorRank,
        List<String> matchedBy) {
}
