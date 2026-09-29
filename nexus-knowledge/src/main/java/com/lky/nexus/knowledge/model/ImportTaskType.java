package com.lky.nexus.knowledge.model;

/**
 * 导入任务类型。
 */
public enum ImportTaskType {
    /** 单/多文件上传。 */
    UPLOAD,
    /** Obsidian Vault（ZIP）。 */
    VAULT_ZIP,
    /** 用已存原始文件重新同步。 */
    RESYNC
}
