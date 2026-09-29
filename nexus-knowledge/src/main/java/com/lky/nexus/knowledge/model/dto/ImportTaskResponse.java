package com.lky.nexus.knowledge.model.dto;

import java.time.LocalDateTime;

/**
 * 导入任务视图。
 *
 * <p>它是"导入进度"的对外契约：异步导入时前端靠它轮询；
 * 同步导入时它就是本次导入的结果摘要。
 */
public record ImportTaskResponse(
        Long id,
        Long knowledgeBaseId,
        String taskType,
        String status,
        long totalFiles,
        int createdCount,
        int updatedCount,
        int skippedCount,
        int deletedCount,
        int failedCount,
        int totalChunks,
        long totalTokens,
        int progress,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String errorMsg) {
}
