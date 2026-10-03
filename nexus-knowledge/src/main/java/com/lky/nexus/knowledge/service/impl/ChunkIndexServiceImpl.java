package com.lky.nexus.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.search.ChunkIndex;
import com.lky.nexus.knowledge.search.IndexedChunk;
import com.lky.nexus.knowledge.service.ChunkIndexService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 切片索引实现。
 *
 * <p>流程：<b>取待索引切片 → 补齐文档级字段（标题/标签/目录）→ 批量写入 ES → 回写状态</b>。
 *
 * <p>为什么要"补齐文档级字段"：BM25 的打分依赖字段（标题命中的价值远高于正文命中），
 * 而这些字段在 MySQL 里属于 {@code kb_document}。索引是一次性写、反复读的，
 * 因此把需要的上下文冗余进索引（典型的读模型取舍），避免每次检索都跨表查询。
 */
@Service
public class ChunkIndexServiceImpl implements ChunkIndexService {

    private static final Logger log = LoggerFactory.getLogger(ChunkIndexServiceImpl.class);

    private static final int FETCH_BATCH = 200;

    private final KnowledgeBaseService knowledgeBaseService;
    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;
    private final ChunkIndex chunkIndex;
    private final JsonMapper jsonMapper;

    public ChunkIndexServiceImpl(KnowledgeBaseService knowledgeBaseService,
                                 ChunkMapper chunkMapper,
                                 DocumentMapper documentMapper,
                                 ChunkIndex chunkIndex,
                                 JsonMapper jsonMapper) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
        this.chunkIndex = chunkIndex;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public IndexResult indexPendingChunks(Long userId, Long knowledgeBaseId, int batchLimit) {
        KnowledgeBasePO knowledgeBase = knowledgeBaseService.requireOwned(userId, knowledgeBaseId);
        long start = System.currentTimeMillis();
        chunkIndex.ensureIndex(knowledgeBase.getId());

        int total = 0;
        int indexed = 0;

        while (total < batchLimit) {
            int fetch = Math.min(FETCH_BATCH, batchLimit - total);
            List<ChunkPO> chunks = chunkMapper.selectList(Wrappers.<ChunkPO>lambdaQuery()
                    .eq(ChunkPO::getKbId, knowledgeBase.getId())
                    .eq(ChunkPO::getEsStatus, "PENDING")
                    .orderByAsc(ChunkPO::getId)
                    .last("LIMIT " + fetch));
            if (chunks.isEmpty()) {
                break;
            }
            total += chunks.size();

            Map<Long, DocumentPO> documents = loadDocuments(chunks);
            List<IndexedChunk> indexed_chunks = new ArrayList<>(chunks.size());
            for (ChunkPO chunk : chunks) {
                DocumentPO document = documents.get(chunk.getDocumentId());
                indexed_chunks.add(new IndexedChunk(
                        chunk.getId(),
                        chunk.getKbId(),
                        chunk.getDocumentId(),
                        chunk.getUserId(),
                        document == null ? null : document.getTitle(),
                        chunk.getHeadingPath(),
                        chunk.getContent(),
                        document == null ? List.of() : parseTags(document.getTags()),
                        document == null ? null : document.getFolder(),
                        chunk.getChunkIndex()));
            }
            chunkIndex.indexChunks(indexed_chunks);
            indexed += chunks.size();

            List<Long> doneIds = chunks.stream().map(ChunkPO::getId).toList();
            chunkMapper.update(null, Wrappers.<ChunkPO>lambdaUpdate()
                    .set(ChunkPO::getEsStatus, "DONE")
                    .in(ChunkPO::getId, doneIds));

            if (chunks.size() < fetch) {
                break;
            }
        }

        long cost = System.currentTimeMillis() - start;
        log.info("知识库 {} 建索引：待索引 {} 条，写入 {} 条，耗时 {} ms",
                knowledgeBase.getId(), total, indexed, cost);
        return new IndexResult(total, indexed, cost);
    }

    /**
     * 重建<b>本知识库</b>的索引。
     *
     * <p>两个顺序上的讲究：
     * <ol>
     *   <li><b>只重置本库切片</b>：别的知识库有自己的物理索引，重建本库碰不到它们。
     *       早先这里配的是"全局唯一索引"，重建本库会把别人的 ES 文档一起清掉，
     *       而别人的切片状态仍是 {@code DONE}、不会重新索引——BM25 就此静默失效；</li>
     *   <li><b>先重置状态、再重建索引</b>：反过来的话，一旦重建过程中途失败，
     *       切片已经被标成"重建过了"，而重建其实没走完，索引是空的——
     *       又是一个"错了但看起来正常"。反过来，最坏情况只是切片多被标一次 {@code PENDING}：
     *       建索引是按 chunkId 幂等 upsert 的，重跑一遍没有副作用。</li>
     * </ol>
     */
    @Override
    public void recreateIndex(Long userId, Long knowledgeBaseId) {
        knowledgeBaseService.requireOwned(userId, knowledgeBaseId);
        // 重建后本库所有切片都需要重新写入
        chunkMapper.update(null, Wrappers.<ChunkPO>lambdaUpdate()
                .set(ChunkPO::getEsStatus, "PENDING")
                .eq(ChunkPO::getKbId, knowledgeBaseId));
        chunkIndex.recreateIndex(knowledgeBaseId);
        log.info("知识库 {} 索引已重建，切片状态重置为 PENDING", knowledgeBaseId);
    }

    @Override
    public void deleteByDocumentIds(Collection<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        chunkIndex.deleteByDocumentIds(List.copyOf(documentIds));
    }

    private Map<Long, DocumentPO> loadDocuments(List<ChunkPO> chunks) {
        List<Long> documentIds = chunks.stream().map(ChunkPO::getDocumentId).distinct().toList();
        Map<Long, DocumentPO> documents = new HashMap<>();
        for (DocumentPO document : documentMapper.selectByIds(documentIds)) {
            documents.put(document.getId(), document);
        }
        return documents;
    }

    /** 标签在文档表里是 JSON 数组字符串；解析失败不该让整批索引失败。 */
    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(tagsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            log.debug("标签解析失败，按空处理：{}", tagsJson);
            return List.of();
        }
    }
}
