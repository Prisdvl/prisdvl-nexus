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
        Long totalTokens,
        /** 已入库文档的原始文件字节合计（单库配额口径，见 StorageQuotaService）。0 表示该库还没有文档 */
        Long usedBytes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
