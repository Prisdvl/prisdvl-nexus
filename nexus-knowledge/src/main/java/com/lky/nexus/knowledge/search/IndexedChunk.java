package com.lky.nexus.knowledge.search;

import java.util.List;

/**
 * 待写入检索索引的一条切片（已带上下文元数据）。
 *
 * <p>为什么要把文档级的字段（标题、标签、目录）冗余进索引：BM25 检索时
 * "标题命中"与"正文命中"的价值完全不同，而这些字段在 MySQL 里属于另一张表。
 * 检索时再去 join 会引入额外延迟与跨库依赖，因此写入时就一起带上——
 * 这正是"搜索索引是读模型"的典型取舍。
 *
 * @param chunkId     切片 id（ES 文档 _id，与 MySQL/向量库共用同一个 id）
 * @param knowledgeBaseId 知识库 id（检索必须按它过滤）
 * @param documentId  文档 id（删除文档时按它清理）
 * @param userId      归属用户（多租户兜底过滤）
 * @param title       文档标题
 * @param headingPath 标题路径，如 "Spring Boot > 自动配置"
 * @param content     嵌入/检索正文（含标题路径前缀，与切片 content 一致）
 * @param tags        标签
 * @param folder      所在目录
 * @param chunkIndex  文档内序号
 */
public record IndexedChunk(
        Long chunkId,
        Long knowledgeBaseId,
        Long documentId,
        Long userId,
        String title,
        String headingPath,
        String content,
        List<String> tags,
        String folder,
        Integer chunkIndex) {
}
