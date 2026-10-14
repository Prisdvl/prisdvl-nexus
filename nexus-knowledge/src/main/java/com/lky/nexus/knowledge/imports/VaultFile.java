package com.lky.nexus.knowledge.imports;

import java.time.Instant;

/**
 * Vault 里的一个文件（已读入内存）。
 *
 * @param sourcePath   相对 Vault 根的路径，如 {@code Spring/SpringBoot.md} —— 增量同步的身份键
 * @param fileName     文件名
 * @param folder       所在目录（便于按目录过滤）
 * @param content      文件字节
 * @param lastModified 压缩包记录的修改时间（可作为 doc_updated_at；压缩包不保存创建时间）
 */
public record VaultFile(
        String sourcePath,
        String fileName,
        String folder,
        byte[] content,
        Instant lastModified) {

    /** 扩展名（小写，无扩展名返回空串）。 */
    public String extension() {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** 文件字节数；内容为 {@code null} 时返回 0。 */
    public long size() {
        return content == null ? 0 : content.length;
    }
}
