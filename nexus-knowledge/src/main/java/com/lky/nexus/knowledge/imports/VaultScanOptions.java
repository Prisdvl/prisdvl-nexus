package com.lky.nexus.knowledge.imports;

/**
 * Vault 解压/扫描的安全限额。
 *
 * <p>这些限制不是"防御性编程的形式主义"，而是针对真实攻击面的：
 * <ul>
 *   <li><b>ZIP 炸弹</b>：一个几 KB 的压缩包可以解出几十 GB。因此必须限制
 *       <b>解压后</b>的字节数（而不是压缩包大小），并且边读边计数；</li>
 *   <li><b>路径穿越（Zip Slip）</b>：条目名写成 {@code ../../etc/passwd} 或绝对路径，
 *       解压时覆盖系统文件。本项目的做法是<b>先校验再使用</b>，且落盘时用"清洗 + 哈希"后的名字，
 *       双重保险；</li>
 *   <li><b>条目数量炸弹</b>：几十万个小文件把 inode 和数据库打满。</li>
 * </ul>
 */
public record VaultScanOptions(int maxEntries, long maxFileBytes, long maxTotalBytes) {

    public static final int DEFAULT_MAX_ENTRIES = 5000;
    public static final long DEFAULT_MAX_FILE_BYTES = 20L * 1024 * 1024;
    public static final long DEFAULT_MAX_TOTAL_BYTES = 200L * 1024 * 1024;

    /** 返回默认安全限额（5000 条目 / 单文件 20MB / 总 200MB）。 */
    public static VaultScanOptions defaults() {
        return new VaultScanOptions(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_FILE_BYTES, DEFAULT_MAX_TOTAL_BYTES);
    }
}
