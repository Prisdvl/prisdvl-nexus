package com.lky.nexus.knowledge.model;

/**
 * 文档来源类型。
 *
 * <p>区分它是为了后续做两件事：① 按来源展示/筛选；② 增量同步时判断"这次导入的清单"
 * 是否覆盖了库里的全部来源（例如 ZIP 导入不应该误删手工上传的文档）。
 */
public enum KnowledgeSourceType {
    /** 手工上传的单个/多个文件。 */
    UPLOAD,
    /** Obsidian Vault（ZIP 导入）。 */
    OBSIDIAN,
    /** 普通 ZIP 压缩包。 */
    ZIP,
    /** 建库时创建的空库。 */
    MANUAL
}
