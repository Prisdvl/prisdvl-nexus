package com.lky.nexus.knowledge.model;

/**
 * 文档处理状态（导入流水线的进度载体）。
 *
 * <p>为什么要细分成这么多步而不是只有"成功/失败"：导入是异步的，
 * 用户与运维都需要知道"卡在哪一步"。例如全是 {@code PARSING} 说明解析器有问题，
 * 全是 {@code EMBEDDING} 说明模型侧有问题——这比一个笼统的 FAILED 有用得多。
 */
public enum DocumentStatus {
    /** 已登记，等待处理。 */
    PENDING,
    /** 正在解析（提取正文/frontmatter/标题）。 */
    PARSING,
    /** 正在切分。 */
    CHUNKING,
    /** 正在生成嵌入。 */
    EMBEDDING,
    /** 正在写入向量库与 ES。 */
    INDEXING,
    /** 全部完成。 */
    READY,
    /** 失败（error_msg 里是原因）。 */
    FAILED,
    /** 增量同步判定为未变化，跳过。 */
    SKIPPED
}
