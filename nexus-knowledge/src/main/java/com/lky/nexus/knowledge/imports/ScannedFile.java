package com.lky.nexus.knowledge.imports;

/**
 * 扫描到的文件 + 它的 {@code file_hash}。
 *
 * <p>为什么把哈希与文件分开：哈希是"廉价判定"的依据（SHA-256 一次几微秒），
 * 而"正文是否变化（content_hash）"必须<b>先解析</b>才知道。
 * 增量同步的省时省钱就来自这个顺序：先用 file_hash 挡掉绝大多数文件，
 * 只对真正变了的文件做解析与后续处理。
 *
 * @param file     文件
 * @param fileHash SHA-256(原始字节)
 */
public record ScannedFile(VaultFile file, String fileHash) {
}
