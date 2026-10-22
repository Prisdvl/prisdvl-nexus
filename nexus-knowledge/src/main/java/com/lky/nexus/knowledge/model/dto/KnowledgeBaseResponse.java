package com.lky.nexus.knowledge.model.dto;

import java.time.LocalDateTime;

/**
 * 知识库视图。
 *
 * <p>{@code id} 是 Long → 全局序列化为字符串（ADR-017）；
 * 计数字段用 Integer/Long 基本类型包装 → 保持数字形态。
 */
public record KnowledgeBaseResponse(
        Long id,
        Long workspaceId,
        String name,
        String description,
        String icon,
        String status,
        String sourceType,
        String embeddingProvider,
        String embeddingModel,
        Integer embeddingDim,
        String chunkStrategy,
        Integer chunkSize,
        Integer chunkOverlap,
        Integer retrievalTopK,
        Double similarityThreshold,
        Integer docCount,
        Integer chunkCount,
        /**
         * 已写进检索索引的切片数。只在详情接口计算（列表场景按库各查一次会变成 N+1），
         * 列表里为 {@code null}。
         *
         * <p>{@code chunkCount > 0 && indexedChunks == 0} 就是「导入完成但没建索引」——
         * 这种库检索永远 0 命中，界面必须显式提示，而不是让用户以为检索坏了。
         */
        Integer indexedChunks,
        /** 最近一次建索引的时间；从未建过为 {@code null}。同样只在详情接口计算 */
        LocalDateTime indexedAt,
        Long totalTokens,
        /** 已入库文档的原始文件字节合计（单库配额口径，见 StorageQuotaService）。0 表示该库还没有文档 */
        Long usedBytes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /**
     * 补上「索引覆盖」两项，其余字段原样带过。
     *
     * <p>为什么不直接在服务里构造：{@code KnowledgeBaseServiceImpl} 刻意<b>不</b>注入
     * {@code ChunkIndexService}（后者依赖 {@code KnowledgeBaseService}，构成构造器循环依赖，
     * 见该类字段注释），所以索引覆盖只能由上层（控制器）补，而 record 不可变 →
     * 这里给一个显式的「同一份视图 + 两个补充字段」，比在控制器里手抄 21 个字段安全。
     */
    public KnowledgeBaseResponse withIndex(Integer indexedChunks, LocalDateTime indexedAt) {
        return new KnowledgeBaseResponse(id, workspaceId, name, description, icon, status, sourceType,
                embeddingProvider, embeddingModel, embeddingDim, chunkStrategy, chunkSize, chunkOverlap,
                retrievalTopK, similarityThreshold, docCount, chunkCount, indexedChunks, indexedAt,
                totalTokens, usedBytes, createdAt, updatedAt);
    }
}
