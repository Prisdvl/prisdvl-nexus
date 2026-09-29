package com.lky.nexus.knowledge.model;

/**
 * 导入任务状态。
 *
 * <p>{@link #PARTIAL} 是刻意保留的：批量导入时"部分成功"远比"全失败"常见，
 * 用一个状态把它和"全部成功"区分开，用户才知道是否需要处理失败项。
 */
public enum ImportTaskStatus {
    QUEUED,
    RUNNING,
    /** 部分成功（有失败项）。 */
    PARTIAL,
    DONE,
    FAILED,
    CANCELLED
}
