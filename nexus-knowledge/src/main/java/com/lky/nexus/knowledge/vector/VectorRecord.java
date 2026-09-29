package com.lky.nexus.knowledge.vector;

/**
 * 待写入向量库的一条记录。
 *
 * @param chunkId     MySQL kb_chunk.id（作为向量表主键，保证两库可对应）
 * @param kbId        知识库 id（检索时按它过滤，实现租户内检索）
 * @param documentId  文档 id（删除文档时按它清理向量）
 * @param contentHash SHA-256(嵌入文本)，用于嵌入缓存与变更判断
 * @param embedding   向量
 */
public record VectorRecord(
        Long chunkId,
        Long kbId,
        Long documentId,
        String contentHash,
        float[] embedding) {
}
