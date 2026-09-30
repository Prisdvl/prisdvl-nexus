package com.lky.nexus.knowledge.rag.retriever;

import com.lky.nexus.ai.rag.retriever.RetrievedDocument;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 回表补齐：把"召回命中"（chunkId + 分数）变成"可用的资料"（正文 + 标题 + 文件）。
 *
 * <p>为什么单独抽出来：三种检索策略的召回方式完全不同（ES / pgvector / 两路融合），
 * 但召回之后的做事<b>一模一样</b>——按相关性顺序回表、补齐展示与引用字段、
 * 跳过已被删除的切片。之前这段逻辑写在一个大 Service 里，现在被三个实现共用，
 * 如果各自复制一份，迟早会出现"某一路上带的字段不一样"这种极难发现的问题。
 *
 * <p>两个沿用下来的关键约定：
 * <ol>
 *   <li><b>结果顺序由命中顺序驱动</b>：{@code selectByIds} 的返回顺序与传入顺序无关，
 *       所以必须以命中列表为骨架去查表，而不是反过来；</li>
 *   <li><b>孤儿命中要跳过而不是报错</b>：索引里可能残留已删除的切片
 *       （MySQL 与 ES/pgvector 之间没有分布式事务）。跳过并打日志——
 *       这类不一致是数据质量的信号，不该让整次检索失败，但也不能静默。</li>
 * </ol>
 */
@Component
public class ChunkHydrator {

    private static final Logger log = LoggerFactory.getLogger(ChunkHydrator.class);

    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;

    public ChunkHydrator(ChunkMapper chunkMapper, DocumentMapper documentMapper) {
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
    }

    /**
     * 按命中顺序补齐资料。
     *
     * @param knowledgeBaseId 这条链路服务的知识库（多库检索时每个库各调一次）
     * @param recalled        按相关性降序的召回命中
     */
    public List<RetrievedDocument> hydrate(Long knowledgeBaseId, List<RecalledChunk> recalled) {
        if (recalled == null || recalled.isEmpty()) {
            return List.of();
        }
        List<Long> chunkIds = recalled.stream().map(RecalledChunk::chunkId).distinct().toList();
        Map<Long, ChunkPO> chunks = new HashMap<>();
        for (ChunkPO chunk : chunkMapper.selectByIds(chunkIds)) {
            chunks.put(chunk.getId(), chunk);
        }

        Map<Long, DocumentPO> documents = new HashMap<>();
        List<Long> documentIds = chunks.values().stream().map(ChunkPO::getDocumentId).distinct().toList();
        if (!documentIds.isEmpty()) {
            for (DocumentPO document : documentMapper.selectByIds(documentIds)) {
                documents.put(document.getId(), document);
            }
        }

        List<RetrievedDocument> results = new ArrayList<>(recalled.size());
        for (RecalledChunk hit : recalled) {
            ChunkPO chunk = chunks.get(hit.chunkId());
            if (chunk == null) {
                log.warn("检索命中了一个已不存在的切片 chunkId={}（索引与数据库不一致）", hit.chunkId());
                continue;
            }
            DocumentPO document = documents.get(chunk.getDocumentId());
            results.add(new RetrievedDocument(
                    chunk.getId(),
                    chunk.getDocumentId(),
                    knowledgeBaseId,
                    document == null ? null : document.getTitle(),
                    document == null ? null : document.getFileName(),
                    chunk.getHeadingPath(),
                    // 页码：切片表还没有这一列，等补齐版式文档元数据时回填（引用要能定位到第几页）
                    null,
                    // 展示与引用用原文（rawContent），参与嵌入的那份带标题路径，不适合给人看
                    chunk.getRawContent() == null ? chunk.getContent() : chunk.getRawContent(),
                    hit.score(),
                    hit.source(),
                    hit.matchedBy()));
        }
        return results;
    }

    /**
     * 召回命中（尚未回表）。
     *
     * @param score     相关性得分（单路时是该路原始分，融合时是 RRF 分）
     * @param source    {@code bm25} / {@code vector} / {@code hybrid}
     * @param matchedBy 命中说明（如 {@code ["BM25 #2", "向量 #1"]}）
     */
    public record RecalledChunk(Long chunkId, double score, String source, List<String> matchedBy) {
    }
}
