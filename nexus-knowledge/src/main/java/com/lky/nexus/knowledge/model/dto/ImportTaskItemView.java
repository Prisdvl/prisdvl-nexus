package com.lky.nexus.knowledge.model.dto;

import com.lky.nexus.knowledge.model.entity.ImportTaskItemPO;

import java.time.LocalDateTime;

/**
 * 导入任务明细视图：一个文件的处理结果（产品化 Phase 5.3 · P1-3/P1-4）。
 *
 * <p>它存在的原因是 {@code kb_import_task_item} 这张表此前<b>只写不读</b>——
 * 导入失败时用户能看到的只有任务行上的一个 {@code failedCount}，
 * 至于"失败的是哪几个文件、为什么"没有任何出口，只能去翻服务端日志。
 *
 * <p>{@code message} 是这里信息量最大的字段，不同 action 下含义不同：
 * <ul>
 *   <li>{@code SKIPPED}：为什么没进来（不支持的格式 / 空文件 / 隐藏目录 / 内容未变）；</li>
 *   <li>{@code FAILED}：异常原因（解析失败、落库失败、向量清理失败……）；</li>
 *   <li>{@code DELETED}：本次清单里已不存在该文件。</li>
 * </ul>
 * 刻意不暴露 {@code oldHash}/{@code newHash}：那是给增量比对用的内部状态，
 * 对用户没有意义（界面上没人能凭一个 sha256 前缀判断"这篇改了什么"）。
 */
public record ImportTaskItemView(
        Long id,
        String sourcePath,
        String action,
        Long documentId,
        int chunkCount,
        int durationMs,
        String message,
        LocalDateTime createdAt) {

    /**
     * 从实体转换。
     *
     * <p>放在 DTO 里而不是服务里，与 {@link ImportTaskResponse#of} 同一约定：
     * 映射只写一遍，新增字段时不会出现"两个入口映射不一致"。
     */
    public static ImportTaskItemView of(ImportTaskItemPO item) {
        return new ImportTaskItemView(
                item.getId(),
                item.getSourcePath(),
                item.getAction(),
                item.getDocumentId(),
                item.getChunkCount() == null ? 0 : item.getChunkCount(),
                item.getDurationMs() == null ? 0 : item.getDurationMs(),
                item.getMessage(),
                item.getCreatedAt());
    }
}
