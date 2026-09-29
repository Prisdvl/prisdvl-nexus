package com.lky.nexus.knowledge.imports;

import java.util.List;

/**
 * Vault 扫描结果。
 *
 * @param files        受支持且通过安全检查的文件
 * @param skipped      被跳过的条目（附原因，用于向用户解释"为什么这篇没进来"）
 * @param totalEntries 压缩包内的条目总数（含被跳过的）
 */
public record VaultScanResult(List<VaultFile> files, List<SkippedEntry> skipped, int totalEntries) {

    /**
     * 被跳过的条目。
     *
     * @param path   条目路径
     * @param reason 跳过原因（对用户可见）
     */
    public record SkippedEntry(String path, String reason) {
    }

    /** 跳过原因常量，避免各处拼写不一致。 */
    public static final class SkipReason {
        /** Obsidian 配置目录、.git、.trash 等隐藏目录。 */
        public static final String HIDDEN = "隐藏目录或文件（.obsidian/.trash/.git 等）";
        /** 不支持的文件类型（图片、PDF 等，第一版只处理文本类）。 */
        public static final String UNSUPPORTED_TYPE = "暂不支持的文件类型";
        /** 空文件。 */
        public static final String EMPTY = "空文件";
        /** 目录条目。 */
        public static final String DIRECTORY = "目录条目";

        private SkipReason() {
        }
    }
}
