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
        String name,
        String description,
        String sourceType,
        String embeddingProvider,
        String embeddingModel,
        Integer embeddingDim,
        String chunkStrategy,
        Integer chunkSize,
        Integer chunkOverlap,
        Integer docCount,
        Integer chunkCount,
        Long totalTokens,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
