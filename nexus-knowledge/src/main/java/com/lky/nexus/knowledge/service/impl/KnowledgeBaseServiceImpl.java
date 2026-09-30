package com.lky.nexus.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.ModelRef;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.PageQuery;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.DocumentTagMapper;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.ChunkStrategy;
import com.lky.nexus.knowledge.model.KnowledgeBaseStatus;
import com.lky.nexus.knowledge.model.KnowledgeSourceType;
import com.lky.nexus.knowledge.model.dto.CreateKnowledgeBaseRequest;
import com.lky.nexus.knowledge.model.dto.KnowledgeBaseResponse;
import com.lky.nexus.knowledge.model.dto.UpdateKnowledgeBaseRequest;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.model.entity.DocumentTagPO;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库服务实现。
 *
 * <p>三个关键设计：
 * <ol>
 *   <li><b>建库时冻结嵌入模型与维度</b>（ADR-008）：从 {@code embedding.default} 角色解析出
 *       具体模型与 dimensions 写进记录。若配置不完整则直接报 4006 ——
 *       宁可在建库时失败，也不要让一批维度不一致的向量悄悄入库（那种错误不会报错，只会让检索结果莫名其妙）。</li>
 *   <li><b>租户隔离写在 SQL 条件里</b>：{@code user_id = ?} 进查询，而不是查出来再在 Java 里比对。</li>
 *   <li><b>访问他人资源返回 404</b>（3001）而不是 403，避免泄露"这个 id 存在"。</li>
 * </ol>
 */
@Service
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseServiceImpl.class);

    private static final int DEFAULT_CHUNK_SIZE = 400;
    private static final int DEFAULT_CHUNK_OVERLAP = 50;
    private static final int DEFAULT_RETRIEVAL_TOP_K = 5;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.7;

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final DocumentTagMapper documentTagMapper;
    private final AiModelProperties aiModelProperties;

    public KnowledgeBaseServiceImpl(KnowledgeBaseMapper knowledgeBaseMapper,
                                    DocumentMapper documentMapper,
                                    DocumentTagMapper documentTagMapper,
                                    AiModelProperties aiModelProperties) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.documentTagMapper = documentTagMapper;
        this.aiModelProperties = aiModelProperties;
    }

    @Override
    @Transactional
    public KnowledgeBaseResponse create(Long userId, Long workspaceId, CreateKnowledgeBaseRequest request) {
        String name = request.name().trim();
        if (existsName(userId, name)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NAME_DUPLICATED,
                    "已存在同名知识库：" + name);
        }

        ChunkStrategy chunkStrategy = ChunkStrategy.parse(request.chunkStrategy());
        int chunkSize = request.chunkSize() == null ? DEFAULT_CHUNK_SIZE : request.chunkSize();
        int chunkOverlap = request.chunkOverlap() == null ? DEFAULT_CHUNK_OVERLAP : request.chunkOverlap();
        if (chunkOverlap >= chunkSize) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "chunkOverlap(%d) 必须小于 chunkSize(%d)，否则切片会无限重叠".formatted(chunkOverlap, chunkSize));
        }

        int retrievalTopK = request.retrievalTopK() == null ? DEFAULT_RETRIEVAL_TOP_K : request.retrievalTopK();
        double similarityThreshold = request.similarityThreshold() == null
                ? DEFAULT_SIMILARITY_THRESHOLD : request.similarityThreshold();

        FrozenEmbedding embedding = resolveEmbedding();

        KnowledgeBasePO entity = new KnowledgeBasePO();
        entity.setUserId(userId);
        entity.setWorkspaceId(workspaceId);
        entity.setName(name);
        entity.setDescription(StringUtils.hasText(request.description()) ? request.description().trim() : null);
        entity.setIcon(StringUtils.hasText(request.icon()) ? request.icon().trim() : null);
        entity.setStatus(KnowledgeBaseStatus.DEFAULT.name());
        entity.setSourceType(KnowledgeSourceType.MANUAL.name());
        entity.setEmbeddingProvider(embedding.provider());
        entity.setEmbeddingModel(embedding.model());
        entity.setEmbeddingDim(embedding.dimensions());
        entity.setChunkStrategy(chunkStrategy.name());
        entity.setChunkSize(chunkSize);
        entity.setChunkOverlap(chunkOverlap);
        entity.setRetrievalTopK(retrievalTopK);
        entity.setSimilarityThreshold(similarityThreshold);
        entity.setDocCount(0);
        entity.setChunkCount(0);
        entity.setTotalTokens(0L);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        knowledgeBaseMapper.insert(entity);
        log.info("创建知识库 id={}, userId={}, workspaceId={}, name={}, embedding={}:{} ({}维), chunk={}/{}",
                entity.getId(), userId, workspaceId, name, embedding.provider(), embedding.model(),
                embedding.dimensions(), chunkSize, chunkOverlap);
        return toResponse(entity);
    }

    @Override
    public PageResult<KnowledgeBaseResponse> list(Long userId, int pageNum, int pageSize) {
        int safePageNum = PageQuery.normalizePageNum(pageNum);
        int safePageSize = PageQuery.normalizePageSize(pageSize);

        Page<KnowledgeBasePO> page = new Page<>(safePageNum, safePageSize);
        Page<KnowledgeBasePO> result = knowledgeBaseMapper.selectPage(page,
                Wrappers.<KnowledgeBasePO>lambdaQuery()
                        .eq(KnowledgeBasePO::getUserId, userId)
                        .orderByDesc(KnowledgeBasePO::getCreatedAt));

        List<KnowledgeBaseResponse> list = result.getRecords().stream().map(this::toResponse).toList();
        return PageResult.of(list, result.getTotal(), safePageNum, safePageSize);
    }

    @Override
    public KnowledgeBaseResponse detail(Long userId, Long knowledgeBaseId) {
        return toResponse(requireOwned(userId, knowledgeBaseId));
    }

    @Override
    @Transactional
    public KnowledgeBaseResponse update(Long userId, Long knowledgeBaseId, UpdateKnowledgeBaseRequest request) {
        KnowledgeBasePO entity = requireOwned(userId, knowledgeBaseId);

        if (StringUtils.hasText(request.name())) {
            String name = request.name().trim();
            if (!name.equals(entity.getName()) && existsName(userId, name)) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NAME_DUPLICATED, "已存在同名知识库：" + name);
            }
            entity.setName(name);
        }
        if (request.description() != null) {
            entity.setDescription(StringUtils.hasText(request.description()) ? request.description().trim() : null);
        }
        if (request.icon() != null) {
            entity.setIcon(StringUtils.hasText(request.icon()) ? request.icon().trim() : null);
        }
        if (StringUtils.hasText(request.status())) {
            entity.setStatus(KnowledgeBaseStatus.parse(request.status()).name());
        }
        if (request.retrievalTopK() != null) {
            entity.setRetrievalTopK(request.retrievalTopK());
        }
        if (request.similarityThreshold() != null) {
            entity.setSimilarityThreshold(request.similarityThreshold());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        knowledgeBaseMapper.updateById(entity);
        return toResponse(entity);
    }

    @Override
    @Transactional
    public void delete(Long userId, Long knowledgeBaseId) {
        requireOwned(userId, knowledgeBaseId);

        // 知识库走逻辑删除（保留记录便于审计）；
        // 文档与标签没有独立价值且量大，物理删除，避免无主数据堆积。
        // 注意：Phase 3 起这里还要级联清理向量与 ES 索引（按 documentId 过滤）。
        int documents = documentMapper.delete(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, knowledgeBaseId));
        int tags = documentTagMapper.delete(Wrappers.<DocumentTagPO>lambdaQuery()
                .eq(DocumentTagPO::getKbId, knowledgeBaseId));
        knowledgeBaseMapper.deleteById(knowledgeBaseId);
        log.info("删除知识库 id={}, userId={}, 清理文档 {} 篇 / 标签 {} 条", knowledgeBaseId, userId, documents, tags);
    }

    @Override
    public KnowledgeBasePO requireOwned(Long userId, Long knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        KnowledgeBasePO entity = knowledgeBaseMapper.selectOne(
                Wrappers.<KnowledgeBasePO>lambdaQuery()
                        .eq(KnowledgeBasePO::getId, knowledgeBaseId)
                        .eq(KnowledgeBasePO::getUserId, userId));
        if (entity == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        return entity;
    }

    private boolean existsName(Long userId, String name) {
        return knowledgeBaseMapper.selectCount(Wrappers.<KnowledgeBasePO>lambdaQuery()
                .eq(KnowledgeBasePO::getUserId, userId)
                .eq(KnowledgeBasePO::getName, name)) > 0;
    }

    /**
     * 从 {@code embedding.default} 角色解析出要冻结的嵌入模型与维度。
     *
     * @throws BusinessException 4006：角色未配置 / 模型不存在 / 未声明 dimensions
     */
    private FrozenEmbedding resolveEmbedding() {
        String role = AiModelProperties.Role.EMBEDDING_DEFAULT;
        String reference = aiModelProperties.getRoles().get(role);
        if (!StringUtils.hasText(reference)) {
            throw new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                    "未配置逻辑角色 %s，无法创建知识库".formatted(role));
        }
        ModelRef ref = ModelRef.parse(reference);
        AiModelProperties.ModelEntry entry = aiModelProperties.findModel(ref)
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                        "找不到嵌入模型 %s，请检查 ai.providers 配置".formatted(ref.value())));
        if (entry.getDimensions() == null || entry.getDimensions() <= 0) {
            throw new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                    "嵌入模型 %s 未声明 dimensions，无法确定向量维度（向量维度必须建库时冻结）"
                            .formatted(ref.value()));
        }
        return new FrozenEmbedding(ref.provider(), ref.model(), entry.getDimensions());
    }

    private record FrozenEmbedding(String provider, String model, int dimensions) {
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBasePO entity) {
        return new KnowledgeBaseResponse(
                entity.getId(),
                entity.getWorkspaceId(),
                entity.getName(),
                entity.getDescription(),
                entity.getIcon(),
                entity.getStatus(),
                entity.getSourceType(),
                entity.getEmbeddingProvider(),
                entity.getEmbeddingModel(),
                entity.getEmbeddingDim(),
                entity.getChunkStrategy(),
                entity.getChunkSize(),
                entity.getChunkOverlap(),
                entity.getRetrievalTopK(),
                entity.getSimilarityThreshold(),
                entity.getDocCount(),
                entity.getChunkCount(),
                entity.getTotalTokens(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
