package com.lky.nexus.knowledge.quota;

/**
 * 用户的存储用量快照。
 *
 * @param usedBytes                已入库文档的原始字节合计（{@code kb_document.file_size} 之和）
 * @param documentCount            文档条数
 * @param knowledgeBaseCount       知识库个数
 * @param totalLimitBytes          账号级总量上限；{@code <= 0} 表示不限
 * @param perKnowledgeBaseLimitBytes 单库上限；{@code <= 0} 表示不限
 */
public record StorageUsage(
        long usedBytes,
        long documentCount,
        long knowledgeBaseCount,
        long totalLimitBytes,
        long perKnowledgeBaseLimitBytes) {
}
