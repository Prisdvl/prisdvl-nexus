package com.lky.nexus.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.EmbeddingClient;
import com.lky.nexus.ai.model.EmbeddingClientFactory;
import com.lky.nexus.ai.model.ModelRef;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.service.ChunkEmbeddingService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import com.lky.nexus.knowledge.vector.VectorRecord;
import com.lky.nexus.knowledge.vector.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 切片向量化实现。
 *
 * <p>流程：<b>取待处理切片 → 查嵌入缓存 → 只对未命中的调模型 → 写向量库 → 回写状态</b>。
 *
 * <p>四个关键设计：
 * <ol>
 *   <li><b>模型一致性校验</b>：知识库创建时冻结了嵌入模型（ADR-008）。如果配置里的
 *       {@code embedding.default} 已经换成了别的模型，就直接拒绝——用新模型给旧库补向量，
 *       会让同一个库里的向量来自两个语义空间，检索结果必然错乱，而且不会报任何错。</li>
 *   <li><b>按 content_hash 去重 + 查缓存</b>：同一段内容（模板、抄录、重复笔记）只嵌入一次；
 *       命中的比例在真实笔记库里相当可观，省下的既是时间也是钱。</li>
 *   <li><b>写入幂等</b>：向量表主键是 chunk_id，写用 {@code ON CONFLICT DO UPDATE}。
 *       两库之间没有分布式事务，"写向量成功、回写状态失败"时重试必须安全。</li>
 *   <li><b>失败保持 PENDING</b>：失败批次不改状态（只记日志），下次调用自然重试；
 *       若改成 FAILED，就需要额外的"重置失败项"入口，反而更麻烦。</li>
 * </ol>
 */
@Service
public class ChunkEmbeddingServiceImpl implements ChunkEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkEmbeddingServiceImpl.class);

    /** 单次从数据库取出的切片数。 */
    private static final int FETCH_BATCH = 64;

    private final KnowledgeBaseService knowledgeBaseService;
    private final ChunkMapper chunkMapper;
    private final VectorStore vectorStore;
    private final EmbeddingClientFactory embeddingClientFactory;
    private final AiModelProperties aiModelProperties;

    public ChunkEmbeddingServiceImpl(KnowledgeBaseService knowledgeBaseService,
                                     ChunkMapper chunkMapper,
                                     VectorStore vectorStore,
                                     EmbeddingClientFactory embeddingClientFactory,
                                     AiModelProperties aiModelProperties) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.chunkMapper = chunkMapper;
        this.vectorStore = vectorStore;
        this.embeddingClientFactory = embeddingClientFactory;
        this.aiModelProperties = aiModelProperties;
    }

    @Override
    public EmbeddingResult embedPendingChunks(Long userId, Long knowledgeBaseId, int batchLimit) {
        KnowledgeBasePO knowledgeBase = knowledgeBaseService.requireOwned(userId, knowledgeBaseId);
        return embed(knowledgeBase, batchLimit);
    }

    private EmbeddingResult embed(KnowledgeBasePO knowledgeBase, int batchLimit) {
        long start = System.currentTimeMillis();
        EmbeddingClient client = resolveClientFor(knowledgeBase);

        int total = 0;
        int embedded = 0;
        int cacheHits = 0;
        int failed = 0;

        while (total < batchLimit) {
            int fetch = Math.min(FETCH_BATCH, batchLimit - total);
            List<ChunkPO> chunks = chunkMapper.selectList(Wrappers.<ChunkPO>lambdaQuery()
                    .eq(ChunkPO::getKbId, knowledgeBase.getId())
                    .eq(ChunkPO::getVectorStatus, "PENDING")
                    .orderByAsc(ChunkPO::getId)
                    .last("LIMIT " + fetch));
            if (chunks.isEmpty()) {
                break;
            }
            total += chunks.size();

            try {
                BatchOutcome outcome = embedBatch(client, knowledgeBase, chunks);
                embedded += outcome.embedded();
                cacheHits += outcome.cacheHits();
            } catch (BusinessException ex) {
                // 整批失败（模型不可用等）：不改状态，留待下次重试
                failed += chunks.size();
                log.warn("批次嵌入失败，保持 PENDING 以便重试：{}", ex.getMessage());
            }

            if (chunks.size() < fetch) {
                break;
            }
        }

        long cost = System.currentTimeMillis() - start;
        log.info("知识库 {} 向量化：待处理 {} 条，调用模型 {} 条，缓存命中 {} 条，失败 {} 条，耗时 {} ms",
                knowledgeBase.getId(), total, embedded, cacheHits, failed, cost);
        return new EmbeddingResult(total, embedded, cacheHits, failed, cost);
    }

    /** 处理一批切片：查缓存 → 嵌入缺失的 → 写向量库 → 回写状态。 */
    private BatchOutcome embedBatch(EmbeddingClient client, KnowledgeBasePO knowledgeBase,
                                    List<ChunkPO> chunks) {
        // ① 去重：同一批里内容相同的切片只嵌入一次
        Map<String, String> textByHash = new HashMap<>();
        for (ChunkPO chunk : chunks) {
            textByHash.putIfAbsent(chunk.getContentHash(), chunk.getContent());
        }

        // ② 查嵌入缓存
        Map<String, float[]> vectors = new HashMap<>(
                vectorStore.findCachedEmbeddings(textByHash.keySet()));
        int cacheHits = chunks.size() - countMissing(chunks, vectors);

        // ③ 只对未命中的调用模型
        List<String> missingHashes = textByHash.keySet().stream()
                .filter(hash -> !vectors.containsKey(hash))
                .toList();
        int embedded = 0;
        if (!missingHashes.isEmpty()) {
            List<String> texts = missingHashes.stream().map(textByHash::get).toList();
            List<float[]> newVectors = client.embedBatch(texts);
            if (newVectors.size() != texts.size()) {
                throw new BusinessException(ErrorCode.EMBEDDING_FAILED,
                        "嵌入返回数量与请求不一致：请求 %d 条，返回 %d 条"
                                .formatted(texts.size(), newVectors.size()));
            }
            Map<String, float[]> newCacheEntries = new HashMap<>();
            for (int i = 0; i < texts.size(); i++) {
                vectors.put(missingHashes.get(i), newVectors.get(i));
                newCacheEntries.put(missingHashes.get(i), newVectors.get(i));
            }
            embedded = texts.size();
            // 写入缓存：下次相同内容直接用，不再调模型
            vectorStore.cacheEmbeddings(newCacheEntries, client.reference());
        }

        // ④ 写向量库（幂等 upsert）
        List<VectorRecord> records = new ArrayList<>(chunks.size());
        for (ChunkPO chunk : chunks) {
            float[] vector = vectors.get(chunk.getContentHash());
            if (vector == null) {
                throw new BusinessException(ErrorCode.EMBEDDING_FAILED,
                        "切片 %d 没有对应向量".formatted(chunk.getId()));
            }
            records.add(new VectorRecord(chunk.getId(), chunk.getKbId(), chunk.getDocumentId(),
                    chunk.getContentHash(), vector));
        }
        vectorStore.upsert(records);

        // ⑤ 回写状态（一条 UPDATE 覆盖整批，避免逐条往返）
        List<Long> doneIds = chunks.stream().map(ChunkPO::getId).toList();
        chunkMapper.update(null, Wrappers.<ChunkPO>lambdaUpdate()
                .set(ChunkPO::getVectorStatus, "DONE")
                .in(ChunkPO::getId, doneIds));

        return new BatchOutcome(embedded, cacheHits);
    }

    private int countMissing(List<ChunkPO> chunks, Map<String, float[]> vectors) {
        Set<String> counted = new LinkedHashSet<>();
        int missing = 0;
        for (ChunkPO chunk : chunks) {
            if (counted.add(chunk.getContentHash()) && !vectors.containsKey(chunk.getContentHash())) {
                missing++;
            }
        }
        return missing;
    }

    @Override
    public void deleteVectors(Collection<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        vectorStore.deleteByDocumentIds(documentIds);
    }

    /**
     * 解析并校验该知识库应使用的嵌入客户端。
     *
     * @throws BusinessException 4006：配置的嵌入模型与知识库冻结的模型不一致
     */
    private EmbeddingClient resolveClientFor(KnowledgeBasePO knowledgeBase) {
        String role = AiModelProperties.Role.EMBEDDING_DEFAULT;
        String reference = aiModelProperties.getRoles().get(role);
        if (reference == null) {
            throw new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                    "未配置逻辑角色 " + role);
        }
        ModelRef ref = ModelRef.parse(reference);
        if (!ref.provider().equals(knowledgeBase.getEmbeddingProvider())
                || !ref.model().equals(knowledgeBase.getEmbeddingModel())) {
            throw new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                    ("知识库冻结的嵌入模型是 %s:%s，而当前配置的角色 %s 指向 %s。"
                            + "同一个库里混用两种嵌入模型的向量会让检索结果错乱（且不会报错），"
                            + "因此拒绝执行。请恢复配置，或新建知识库。")
                            .formatted(knowledgeBase.getEmbeddingProvider(), knowledgeBase.getEmbeddingModel(),
                                    role, ref.value()));
        }
        return embeddingClientFactory.get(role);
    }

    private record BatchOutcome(int embedded, int cacheHits) {
    }
}
