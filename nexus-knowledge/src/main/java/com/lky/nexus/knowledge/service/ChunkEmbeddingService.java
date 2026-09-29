package com.lky.nexus.knowledge.service;

import java.util.Collection;

/**
 * 切片向量化服务：把 {@code kb_chunk} 中待处理的切片转成向量并写入向量库。
 *
 * <p>它与导入流水线是<b>两个阶段</b>（而不是把嵌入塞进导入里）：
 * <ol>
 *   <li>导入阶段只做"解析 + 切分 + 落库"，快且不依赖外部模型；</li>
 *   <li>向量化阶段可以独立重跑（换模型、补失败、重建索引），
 *       而重跑整条导入链路代价要高得多。</li>
 * </ol>
 */
public interface ChunkEmbeddingService {

    /**
     * 为知识库中所有"待处理"的切片生成向量。
     *
     * @param userId          归属用户（与其它服务一致：显式传入，便于 MCP/异步场景复用）
     * @param knowledgeBaseId 知识库 id
     * @param batchLimit      单次处理的切片上限（防止一次请求做太多事）
     * @return 本次处理的统计
     */
    EmbeddingResult embedPendingChunks(Long userId, Long knowledgeBaseId, int batchLimit);

    /** 删除若干文档的向量（文档被删除或重新切分时调用，避免留下孤儿向量）。 */
    void deleteVectors(Collection<Long> documentIds);

    /**
     * 向量化结果统计。
     *
     * @param total      本次取出的待处理切片数
     * @param embedded   真正调用了嵌入模型的条数
     * @param cacheHits  命中嵌入缓存、无需调用模型的条数（省时省钱的部分）
     * @param failed     失败条数（保持 PENDING，下次可重试）
     * @param costMillis 总耗时
     */
    record EmbeddingResult(int total, int embedded, int cacheHits, int failed, long costMillis) {

        public static EmbeddingResult empty() {
            return new EmbeddingResult(0, 0, 0, 0, 0);
        }
    }
}
