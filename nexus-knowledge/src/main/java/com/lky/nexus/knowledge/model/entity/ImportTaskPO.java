package com.lky.nexus.knowledge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导入任务实体（kb_import_task）。
 *
 * <p>它是"异步导入"的状态载体：HTTP 立即返回 taskId，worker 边处理边更新这里的计数与进度，
 * 前端轮询或订阅 SSE 看进展。没有它，用户面对大批量导入只能干等或以为卡死了。
 */
@Data
@TableName("kb_import_task")
public class ImportTaskPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long kbId;

    private Long userId;

    /** UPLOAD / VAULT_ZIP / RESYNC */
    private String taskType;

    /** QUEUED / RUNNING / PARTIAL / DONE / FAILED / CANCELLED */
    private String status;

    private Integer totalFiles;

    private Integer createdCount;

    private Integer updatedCount;

    private Integer skippedCount;

    private Integer deletedCount;

    private Integer failedCount;

    private Integer totalChunks;

    private Long totalTokens;

    /** 0-100 */
    private Integer progress;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private String errorMsg;

    private LocalDateTime createdAt;
}
