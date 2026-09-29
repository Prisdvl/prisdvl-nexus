package com.lky.nexus.knowledge.model.dto;

import com.lky.nexus.knowledge.model.entity.ImportTaskPO;

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

    /**
     * 从实体转换。
     *
     * <p>放在这里而不是各处复制：入队服务与执行器都要返回它，
     * 两份几乎相同的映射代码必然会逐渐不一致（例如漏掉新增字段）。
     */
    public static ImportTaskResponse of(ImportTaskPO task) {
        return new ImportTaskResponse(
                task.getId(),
                task.getKbId(),
                task.getTaskType(),
                task.getStatus(),
                task.getTotalFiles() == null ? 0 : task.getTotalFiles(),
                nz(task.getCreatedCount()),
                nz(task.getUpdatedCount()),
                nz(task.getSkippedCount()),
                nz(task.getDeletedCount()),
                nz(task.getFailedCount()),
                nz(task.getTotalChunks()),
                task.getTotalTokens() == null ? 0L : task.getTotalTokens(),
                nz(task.getProgress()),
                task.getStartedAt(),
                task.getFinishedAt(),
                task.getErrorMsg());
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
