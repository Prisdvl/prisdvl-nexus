package com.lky.nexus.knowledge.model;

/**
 * 导入动作（增量同步时对每个文件的判定结果）。
 */
public enum ImportAction {
    /** 新文件。 */
    CREATED,
    /** 内容变化，需重建切片与向量。 */
    UPDATED,
    /** 未变化，跳过（增量同步省时省钱的关键）。 */
    SKIPPED,
    /** 库里存在但本次导入清单里没有 → 删除。 */
    DELETED,
    /** 处理失败（message 里是原因）。 */
    FAILED
}
