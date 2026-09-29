package com.lky.nexus.knowledge.service.impl;

import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.search.ChunkIndex;
import com.lky.nexus.knowledge.search.ChunkSearchHit;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索服务实现（BM25 一路）。
 *
 * <p>检索本身在索引侧完成，这里负责"把命中项补齐成可用的上下文"——
 * 也就是把切片正文与文档标题从 MySQL 取回来。
 *
 * <p><b>一个容易错的地方</b>：{@code selectByIds} 的返回顺序与传入顺序无关，
 * 而检索结果<b>必须保持相关性排序</b>。因此这里用"评分顺序驱动"的方式重组结果，
 * 而不是直接把数据库返回的列表当作结果。
 */
@Service
public class ChunkSearchServiceImpl implements ChunkSearchService {

    private static final Logger log = LoggerFactory.getLogger(ChunkSearchServiceImpl.class);

    /** 单次检索最多取回的候选数，防止调用方传入过大的 topK。 */
    private static final int MAX_TOP_K = 100;

    private final KnowledgeBaseService knowledgeBaseService;
    private final ChunkIndex chunkIndex;
    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;

    public ChunkSearchServiceImpl(KnowledgeBaseService knowledgeBaseService,
                                  ChunkIndex chunkIndex,
                                  ChunkMapper chunkMapper,
                                  DocumentMapper documentMapper) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.chunkIndex = chunkIndex;
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
    }

    @Override
    public List<SearchHit> searchBm25(Long userId, Long knowledgeBaseId, String query, int topK) {
        knowledgeBaseService.requireOwned(userId, knowledgeBaseId);
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int limit = Math.clamp(topK, 1, MAX_TOP_K);
        long start = System.currentTimeMillis();

        List<ChunkSearchHit> hits = chunkIndex.search(knowledgeBaseId, query, limit);
        if (hits.isEmpty()) {
            return List.of();
        }

        // 按命中顺序取回正文与文档标题
        List<Long> chunkIds = hits.stream().map(ChunkSearchHit::chunkId).toList();
        Map<Long, ChunkPO> chunks = new HashMap<>();
        for (ChunkPO chunk : chunkMapper.selectByIds(chunkIds)) {
            chunks.put(chunk.getId(), chunk);
        }
        Map<Long, DocumentPO> documents = new LinkedHashMap<>();
        List<Long> documentIds = chunks.values().stream().map(ChunkPO::getDocumentId).distinct().toList();
        if (!documentIds.isEmpty()) {
            for (DocumentPO document : documentMapper.selectByIds(documentIds)) {
                documents.put(document.getId(), document);
            }
        }

        List<SearchHit> results = new ArrayList<>(hits.size());
        for (ChunkSearchHit hit : hits) {
            ChunkPO chunk = chunks.get(hit.chunkId());
            if (chunk == null) {
                // 索引与数据库不一致（例如索引未同步清理）：跳过而不是抛错，
                // 但要留下日志——这类"孤儿命中"是数据一致性的信号
                log.warn("检索命中了一个已不存在的切片 chunkId={}（索引与数据库不一致）", hit.chunkId());
                continue;
            }
            DocumentPO document = documents.get(chunk.getDocumentId());
            results.add(new SearchHit(
                    chunk.getId(),
                    chunk.getDocumentId(),
                    document == null ? null : document.getTitle(),
                    chunk.getHeadingPath(),
                    chunk.getRawContent() == null ? chunk.getContent() : chunk.getRawContent(),
                    hit.score(),
                    hit.source()));
        }

        log.debug("BM25 检索「{}」命中 {} 条（kbId={}），耗时 {} ms",
                query, results.size(), knowledgeBaseId, System.currentTimeMillis() - start);
        return results;
    }
}
