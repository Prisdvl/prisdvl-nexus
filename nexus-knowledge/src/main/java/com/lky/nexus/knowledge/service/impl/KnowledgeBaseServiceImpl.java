package com.lky.nexus.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.ModelRef;
import com.lky.nexus.ai.rag.retriever.RetrievalDefaults;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.PageQuery;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentLinkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.DocumentTagMapper;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.ChunkStrategy;
import com.lky.nexus.knowledge.model.KnowledgeBaseStatus;
import com.lky.nexus.knowledge.model.KnowledgeSourceType;
import com.lky.nexus.knowledge.model.dto.CreateKnowledgeBaseRequest;
import com.lky.nexus.knowledge.model.dto.KnowledgeBaseResponse;
import com.lky.nexus.knowledge.model.dto.UpdateKnowledgeBaseRequest;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentLinkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.model.entity.DocumentTagPO;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.search.ChunkIndex;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import com.lky.nexus.knowledge.storage.DocumentStorage;
import com.lky.nexus.knowledge.vector.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final DocumentTagMapper documentTagMapper;
    private final DocumentLinkMapper documentLinkMapper;
    private final ChunkMapper chunkMapper;
    private final AiModelProperties aiModelProperties;
    private final RetrievalDefaults retrievalDefaults;

    /**
     * 外部存储的清理直接用<b>端口</b>（{@code VectorStore} / {@code ChunkIndex}）而不是
     * {@code ChunkEmbeddingService} / {@code ChunkIndexService}：后两者的实现都注入了
     * {@code KnowledgeBaseService}（用于 {@code requireOwned}），本类再反向注入它们就构成
     * 构造器循环依赖，Spring Boot 3 默认直接启动失败。而端口这一层只依赖中间件，没有回边。
     */
    private final VectorStore vectorStore;
    private final ChunkIndex chunkIndex;
    private final DocumentStorage documentStorage;

    public KnowledgeBaseServiceImpl(KnowledgeBaseMapper knowledgeBaseMapper,
                                    DocumentMapper documentMapper,
                                    DocumentTagMapper documentTagMapper,
                                    DocumentLinkMapper documentLinkMapper,
                                    ChunkMapper chunkMapper,
                                    AiModelProperties aiModelProperties,
                                    RetrievalDefaults retrievalDefaults,
                                    VectorStore vectorStore,
                                    ChunkIndex chunkIndex,
                                    DocumentStorage documentStorage) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.documentTagMapper = documentTagMapper;
        this.documentLinkMapper = documentLinkMapper;
        this.chunkMapper = chunkMapper;
        this.aiModelProperties = aiModelProperties;
        this.retrievalDefaults = retrievalDefaults;
        this.vectorStore = vectorStore;
        this.chunkIndex = chunkIndex;
        this.documentStorage = documentStorage;
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

        // 建库时的检索参数默认值与检索时"没指定就用什么"必须同源（Phase 3.2）：
        // 早先这里是写死的 5 / 0.7，而检索侧实际用的是 0.25——两处默认值打架，
        // 结果是"新库按 0.7 过滤，几乎什么都召不回来"，且没有任何报错。
        int retrievalTopK = request.retrievalTopK() == null
                ? retrievalDefaults.getTopK() : request.retrievalTopK();
        double similarityThreshold = request.similarityThreshold() == null
                ? retrievalDefaults.getSimilarityThreshold() : request.similarityThreshold();

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

        // 删库是"整库消失"，因此外部的向量与索引必须一并清掉——否则留下的是最坏的一类孤儿：
        // 知识库行已经没了，向量库里还留着它的向量，检索命中却翻不出任何文档。
        // 与单篇文档删除同一取舍：外部清理在前、且清不掉就整体失败（详见 DocumentPersistService.delete）。
        vectorStore.deleteByKnowledgeBase(knowledgeBaseId);
        chunkIndex.deleteByKnowledgeBase(knowledgeBaseId);

        // 磁盘上的原始文件先记下来，等数据库这部分做完再删。
        // 顺序与外部存储相反是有理由的：向量清不掉会污染检索，所以宁可删不掉；
        // 而原始文件清不掉只是浪费磁盘，反过来"文件先删了、事务却回滚"才是真的数据丢失。
        // LocalDocumentStorage.delete 自身吞掉 IO 异常，因此放在这里也不会让删除失败。
        List<String> storedPaths = documentMapper.selectList(Wrappers.<DocumentPO>lambdaQuery()
                        .select(DocumentPO::getStoredPath)
                        .eq(DocumentPO::getKbId, knowledgeBaseId)).stream()
                .map(DocumentPO::getStoredPath)
                .filter(java.util.Objects::nonNull)
                .toList();

        // 切片与关联边没有独立价值且量大，物理删除——它们不承载审计意义，
        // 而且留着的每一行都会让"这个库还有多少数据"这类核对失去意义。
        int chunks = chunkMapper.delete(Wrappers.<ChunkPO>lambdaQuery().eq(ChunkPO::getKbId, knowledgeBaseId));
        int links = documentLinkMapper.delete(Wrappers.<DocumentLinkPO>lambdaQuery()
                .eq(DocumentLinkPO::getKbId, knowledgeBaseId));

        // 逻辑删除文档之前，先清掉本库下早先留下的墓碑，否则会撞 uk_doc_kb_path：
        // 库里某条路径上若已有一行 deleted=1，这次逻辑删除会再写一行同键的，唯一键直接拒绝。
        documentMapper.purgeKnowledgeBaseTombstones(knowledgeBaseId);

        // 知识库与文档走逻辑删除（保留记录便于审计）
        int documents = documentMapper.delete(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, knowledgeBaseId));
        int tags = documentTagMapper.delete(Wrappers.<DocumentTagPO>lambdaQuery()
                .eq(DocumentTagPO::getKbId, knowledgeBaseId));
        knowledgeBaseMapper.deleteById(knowledgeBaseId);

        storedPaths.forEach(documentStorage::delete);
        log.info("删除知识库 id={}, userId={}, 清理文档 {} 篇 / 切片 {} 条 / 关联边 {} 条 / 标签 {} 条 / 原始文件 {} 个",
                knowledgeBaseId, userId, documents, chunks, links, tags, storedPaths.size());
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

    @Override
    public List<KnowledgeBasePO> requireAllOwned(Long userId, List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        // 去重但保序：同一个库绑两次只算一次（否则白跑一轮检索、归并后还会出现重复片段）。
        // 收口在这里，比要求每个调用方先自己 normalize 一遍可靠。null 元素不去掉——
        // 它会在下面被判为"查不到"，与 requireOwned(null) 同样是 3001。
        List<Long> requested = new ArrayList<>(new LinkedHashSet<>(knowledgeBaseIds));
        List<Long> queryIds = requested.stream().filter(Objects::nonNull).toList();

        Map<Long, KnowledgeBasePO> found = new HashMap<>();
        // 空集合不能进 IN：MyBatis-Plus 会生成 `IN ()`，那是语法错误而不是空结果
        if (!queryIds.isEmpty()) {
            for (KnowledgeBasePO entity : knowledgeBaseMapper.selectList(
                    Wrappers.<KnowledgeBasePO>lambdaQuery()
                            .eq(KnowledgeBasePO::getUserId, userId)
                            .in(KnowledgeBasePO::getId, queryIds))) {
                found.put(entity.getId(), entity);
            }
        }

        // 按**请求顺序**返回（不是数据库的返回顺序）：调用方据此决定检索顺序与展示顺序。
        // 第一个查不到的 id 直接失败，并把这个 id 带进信息里——多库下一句
        // "知识库不存在"而不说是哪个，用户面对一屏配置无从判断。
        List<KnowledgeBasePO> result = new ArrayList<>(requested.size());
        for (Long id : requested) {
            KnowledgeBasePO entity = found.get(id);
            if (entity == null) {
                // 措辞对"不存在"与"存在但不属于你"必须完全一致：区分开就等于
                // 用错误信息确认了别人知识库的存在（沿用 3001 → 404 的既有口径）
                throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND,
                        "知识库不存在或无权访问：id=" + id);
            }
            result.add(entity);
        }
        return result;
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
