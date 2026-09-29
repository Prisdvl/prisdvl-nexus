package com.lky.nexus.knowledge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导入任务明细（kb_import_task_item）：每个文件的处理结果。
 *
 * <p>它解决两个问题：① 用户能知道"为什么这篇没进来"（action=SKIPPED 的原因）；
 * ② 失败可以按文件重试，而不是整批重跑。
 */
@Data
@TableName("kb_import_task_item")
public class ImportTaskItemPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long taskId;

    private Long kbId;

    private String sourcePath;

    /** CREATED / UPDATED / SKIPPED / DELETED / FAILED */
    private String action;

    private Long documentId;

    private String oldHash;

    private String newHash;

    private Integer chunkCount;

    private Integer durationMs;

    private String message;

    private LocalDateTime createdAt;
}
