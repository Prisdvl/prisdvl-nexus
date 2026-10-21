package com.lky.nexus.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.util.HashUtils;
import com.lky.nexus.knowledge.chunk.TextChunk;
import com.lky.nexus.knowledge.imports.VaultFile;
import com.lky.nexus.knowledge.imports.VaultPackageExtractor;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentLinkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.DocumentTagMapper;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.DocumentStatus;
import com.lky.nexus.knowledge.model.KnowledgeSourceType;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentLinkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.model.entity.DocumentTagPO;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.parser.ParsedDocument;
import com.lky.nexus.knowledge.quota.StorageQuotaService;
import com.lky.nexus.knowledge.service.ChunkEmbeddingService;
import com.lky.nexus.knowledge.service.ChunkIndexService;
import com.lky.nexus.knowledge.service.DocumentPersistResult;
import com.lky.nexus.knowledge.service.DocumentPersistService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import com.lky.nexus.knowledge.storage.DocumentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文档落库实现。
 *
 * <p>几个值得注意的点：
 * <ol>
 *   <li><b>批量插入切片</b>：一个 Vault 上千篇笔记会产生几万行切片，
 *       逐条 insert 是几万次数据库往返。这里按 200 行一批插入；</li>
 *   <li><b>覆盖时先清理旧数据</b>：切片的唯一键是 (document_id, chunk_index)，
 *       如果不清旧行，重新切分后行数变少就会留下"幽灵切片"——检索能命中但早已不存在于正文里；</li>
 *   <li><b>三条写入路径</b>（save / updateMetadataOnly / relocate）刻意分开：
 *       只有正文真的变了才重建切片（否则会白白丢掉向量、触发重新嵌入）；</li>
 *   <li><b>version 只随正文变化递增</b>：它表达的是"内容版本"，
 *       改个 tag 或改个文件名不该让它跳动（否则用它做增量判断的逻辑会全部失效）。</li>
 * </ol>
 */
@Service
public class DocumentPersistServiceImpl implements DocumentPersistService {

    private static final Logger log = LoggerFactory.getLogger(DocumentPersistServiceImpl.class);

    /** 单批插入的行数：太大可能超过 MySQL 的 max_allowed_packet，太小则往返次数多。 */
    private static final int BATCH_SIZE = 200;

    /** 单片文本上限：过大本身就说明切分有问题，这里做记录性截断保护。 */
    private static final int MAX_CHUNK_CHARS = 20000;

    private final DocumentMapper documentMapper;
    private final DocumentTagMapper documentTagMapper;
    private final DocumentLinkMapper documentLinkMapper;
    private final ChunkMapper chunkMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentStorage documentStorage;
    private final JsonMapper jsonMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ChunkEmbeddingService chunkEmbeddingService;
    private final ChunkIndexService chunkIndexService;
    private final StorageQuotaService storageQuotaService;

    public DocumentPersistServiceImpl(DocumentMapper documentMapper,
                                      DocumentTagMapper documentTagMapper,
                                      DocumentLinkMapper documentLinkMapper,
                                      ChunkMapper chunkMapper,
                                      KnowledgeBaseMapper knowledgeBaseMapper,
                                      DocumentStorage documentStorage,
                                      JsonMapper jsonMapper,
                                      KnowledgeBaseService knowledgeBaseService,
                                      ChunkEmbeddingService chunkEmbeddingService,
                                      ChunkIndexService chunkIndexService,
                                      StorageQuotaService storageQuotaService) {
        this.documentMapper = documentMapper;
        this.documentTagMapper = documentTagMapper;
        this.documentLinkMapper = documentLinkMapper;
        this.chunkMapper = chunkMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentStorage = documentStorage;
        this.jsonMapper = jsonMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.chunkEmbeddingService = chunkEmbeddingService;
        this.chunkIndexService = chunkIndexService;
        this.storageQuotaService = storageQuotaService;
    }

    @Override
    @Transactional
    public DocumentPersistResult save(Long userId,
                                      KnowledgeBasePO knowledgeBase,
                                      VaultFile file,
                                      ParsedDocument parsed,
                                      List<TextChunk> chunks,
                                      String storedPath,
                                      KnowledgeSourceType sourceType) {
        DocumentPO existing = findByPath(knowledgeBase.getId(), file.sourcePath());
        boolean created = existing == null;

        LocalDateTime now = LocalDateTime.now();
        DocumentPO document = created ? new DocumentPO() : existing;

        applyMetadata(document, knowledgeBase.getId(), userId, file, parsed, storedPath, true, sourceType);

        // 存储配额：拦在落库这一处，两个导入入口（单文件、Vault 批量）都经过这里。
        // 增量只算"这次比原来多出来的字节"——同一路径覆盖成更小的文件不该被判超限。
        long previousBytes = existing == null || existing.getFileSize() == null ? 0L : existing.getFileSize();
        long delta = file.size() - previousBytes;
        if (delta > 0) {
            storageQuotaService.ensureRoom(userId, knowledgeBase.getId(), delta, file.fileName());
        }

        document.setChunkCount(chunks.size());
        document.setTokenCount(chunks.stream().mapToInt(TextChunk::tokenCount).sum());
        document.setIndexedAt(now);
        document.setUpdatedAt(now);
        if (created) {
            document.setVersion(1);
            document.setCreatedAt(now);
            documentMapper.insert(document);
        } else {
            document.setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
            documentMapper.updateById(document);
            // 覆盖前先清掉外部存储，再清旧切片与标签。
            //
            // 为什么必须在这里做：切片重切之后拿到的是**新的雪花 id**，而向量是按 chunk_id
            // 存的（只有嵌入缓存按 content_hash）。不先清一遍，旧向量就永远留在向量库里
            // 指向一批已经不存在的 chunk_id——检索命中、正文取不出来，正是本阶段要消灭的孤儿。
            // 这条路径（改一篇笔记 → 重新导入）比删文档常见得多，之前一直是漏的。
            // {@code ChunkEmbeddingService.deleteVectors} 的注释本来就写着"文档被删除或
            // **重新切分**时调用"，只是从来没有人从这个方向调过它。
            //
            // 代价如实说：整包同步时，每个"正文变了"的文件会多出一次向量清理 + 一次索引清理，
            // 而此前是 0 次。这是把"看不见的垃圾"换成"看得见的调用"——清理按 documentId 走索引，
            // 与重新嵌入那批切片相比不在一个量级。
            purgeExternalArtifacts(document.getId());
            chunkMapper.delete(Wrappers.<ChunkPO>lambdaQuery().eq(ChunkPO::getDocumentId, document.getId()));
        }
        // 标签在两条路径下都要重建（元数据可能变了）
        documentTagMapper.delete(Wrappers.<DocumentTagPO>lambdaQuery()
                .eq(DocumentTagPO::getDocumentId, document.getId()));
        insertTags(knowledgeBase.getId(), document.getId(), parsed.tags());
        // 关联边只在"正文可能变化"的路径上重建：save 是唯一会改正文的入口
        // （updateMetadataOnly / relocate 保持正文不变，因此链接也不变）
        replaceLinks(knowledgeBase.getId(), document.getId(), parsed);
        insertChunks(userId, knowledgeBase.getId(), document.getId(), chunks);

        log.debug("文档落库 path={}, created={}, chunks={}", file.sourcePath(), created, chunks.size());
        return new DocumentPersistResult(document, created);
    }

    @Override
    @Transactional
    public void updateMetadataOnly(Long userId,
                                   KnowledgeBasePO knowledgeBase,
                                   DocumentPO existing,
                                   VaultFile file,
                                   ParsedDocument parsed,
                                   KnowledgeSourceType sourceType) {
        LocalDateTime now = LocalDateTime.now();
        String oldStoredPath = existing.getStoredPath();
        applyMetadata(existing, knowledgeBase.getId(), userId, file, parsed, oldStoredPath, false, sourceType);
        // 原始字节也变了（否则 file_hash 不会变），因此覆盖保存，保证磁盘上的副本是最新的
        String storedPath = documentStorage.store(knowledgeBase.getId(), file.sourcePath(), file.content());
        existing.setStoredPath(storedPath);
        existing.setUpdatedAt(now);
        documentMapper.updateById(existing);

        documentTagMapper.delete(Wrappers.<DocumentTagPO>lambdaQuery()
                .eq(DocumentTagPO::getDocumentId, existing.getId()));
        insertTags(knowledgeBase.getId(), existing.getId(), parsed.tags());

        // 刻意不动：kb_chunk、version、chunk_count、token_count
        log.debug("仅元数据更新 path={}（保留 {} 个切片与向量）", file.sourcePath(), existing.getChunkCount());
    }

    @Override
    @Transactional
    public void relocate(Long userId,
                         KnowledgeBasePO knowledgeBase,
                         DocumentPO existing,
                         VaultFile file,
                         ParsedDocument parsed,
                         KnowledgeSourceType sourceType) {
        String oldStoredPath = existing.getStoredPath();
        LocalDateTime now = LocalDateTime.now();
        applyMetadata(existing, knowledgeBase.getId(), userId, file, parsed, oldStoredPath, true, sourceType);
        String storedPath = documentStorage.store(knowledgeBase.getId(), file.sourcePath(), file.content());
        existing.setStoredPath(storedPath);
        existing.setUpdatedAt(now);
        documentMapper.updateById(existing);

        // 旧路径的原始文件已无用，删掉（否则每改一次名就多一份垃圾）
        if (oldStoredPath != null && !Objects.equals(oldStoredPath, storedPath)) {
            documentStorage.delete(oldStoredPath);
        }

        documentTagMapper.delete(Wrappers.<DocumentTagPO>lambdaQuery()
                .eq(DocumentTagPO::getDocumentId, existing.getId()));
        insertTags(knowledgeBase.getId(), existing.getId(), parsed.tags());

        log.debug("文档改名 path={}（保留 {} 个切片与向量）", file.sourcePath(), existing.getChunkCount());
    }

    @Override
    @Transactional
    public void delete(Long userId, DocumentPO document) {
        if (document == null) {
            return;
        }

        // ---------- 先清外部存储，且"清不掉就整个失败" ----------
        // 顺序是刻意的：向量库与 ES 不在这个事务里（没有分布式事务），所以它们一旦成功
        // 就不会随回滚撤销。把外部清理放在最前面，得到的是一个明确的取舍：
        //   * 外部失败 → 在动 MySQL 之前抛出，事务回滚，文档原封不动，用户重试即可；
        //   * 外部成功、MySQL 失败 → 文档还在但检索不到了（可恢复：重导入或重建索引）。
        // 反过来的顺序会造出最糟的那种状态：MySQL 里已经没有了，向量库里还留着孤儿——
        // 检索命中却取不到内容，表现为"结果里冒出空白条目"，且没有任何入口能修好它。
        // 这正是本次改动的首要目标：宁可删不掉，也不要污染检索。
        purgeExternalArtifacts(document.getId());

        // 只删"以本文档为源"的出链；指向本文档的入链<b>保留</b>——
        // resolveTarget 只解析已存在的文档，因此入链会自然退化成"待创建的引用"，
        // 这正是 Obsidian 的语义（删掉一篇笔记，别的笔记里的 [[它]] 变成未创建状态，而不是消失）
        documentLinkMapper.delete(Wrappers.<DocumentLinkPO>lambdaQuery()
                .eq(DocumentLinkPO::getFromDocumentId, document.getId()));
        chunkMapper.delete(Wrappers.<ChunkPO>lambdaQuery().eq(ChunkPO::getDocumentId, document.getId()));
        documentTagMapper.delete(Wrappers.<DocumentTagPO>lambdaQuery()
                .eq(DocumentTagPO::getDocumentId, document.getId()));

        // 逻辑删除之前先清掉同路径的历史墓碑，否则会撞 uk_doc_kb_path (kb_id, source_path, deleted)。
        // 顺序不能反：反过来会把自己刚写出的 deleted=1 那行一起清掉（见 DocumentMapper 的说明）
        documentMapper.purgePathTombstones(document.getKbId(), document.getSourcePath());
        documentMapper.deleteById(document.getId());

        // 原始文件也清掉：文档都删了，留着它只会随每次导入不断累积
        documentStorage.delete(document.getStoredPath());
    }

    @Override
    @Transactional
    public void deleteDocument(Long userId, Long knowledgeBaseId, Long documentId) {
        // 归属校验不能依赖调用方记得加条件（与查询侧同一约定）
        knowledgeBaseService.requireOwned(userId, knowledgeBaseId);
        DocumentPO document = documentMapper.selectOne(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getId, documentId)
                .eq(DocumentPO::getKbId, knowledgeBaseId));
        if (document == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        delete(userId, document);
        // 文档数与切片数都变了，重算一次；库表上的统计是列表页展示的一部分，
        // 不重算就会显示"3 篇"而实际只剩 2 篇——用户会以为删除没生效
        refreshKnowledgeBaseStats(knowledgeBaseId);
        log.info("删除文档 documentId={}, path={}, kbId={}",
                documentId, document.getSourcePath(), knowledgeBaseId);
    }

    /**
     * 清理文档在外部存储（向量库 / 检索索引）里的痕迹。
     *
     * <p>两处都是按 documentId 删除而不是按 chunkId：切片的 id 是随重新切分变化的，
     * 而"这篇文档的向量与索引"是一个稳定的语义单位——按 chunkId 删就必须先把
     * kb_chunk 读出来，那等于把"要删什么"建立在一份可能已经不全的列表上。
     */
    private void purgeExternalArtifacts(Long documentId) {
        List<Long> documentIds = List.of(documentId);
        chunkEmbeddingService.deleteVectors(documentIds);
        chunkIndexService.deleteByDocumentIds(documentIds);
    }

    @Override
    @Transactional
    public void refreshKnowledgeBaseStats(Long knowledgeBaseId) {
        // 三个数字都在数据库里聚合，不把行读回内存。原先的写法是 selectList 拉回整库切片的
        // token_count 再求和——而本方法在<b>每次文档删除</b>后都会被调用，于是"删一篇文档"的
        // 代价随全库切片数线性增长（真实的库是几万到几十万行，跨网络传输本身就是开销）。
        // 聚合查询把这件事变成一次索引扫描，与库的规模无关。
        Integer docCount = Math.toIntExact(documentMapper.selectCount(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, knowledgeBaseId)));
        Integer chunkCount = Math.toIntExact(chunkMapper.selectCount(Wrappers.<ChunkPO>lambdaQuery()
                .eq(ChunkPO::getKbId, knowledgeBaseId)));
        long totalTokens = chunkMapper.sumTokenCount(knowledgeBaseId);

        KnowledgeBasePO update = new KnowledgeBasePO();
        update.setId(knowledgeBaseId);
        update.setDocCount(docCount);
        update.setChunkCount(chunkCount);
        update.setTotalTokens(totalTokens);
        update.setUpdatedAt(LocalDateTime.now());
        knowledgeBaseMapper.updateById(update);
    }

    // ------------------------------------------------------------------

    private DocumentPO findByPath(Long knowledgeBaseId, String sourcePath) {
        return documentMapper.selectOne(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, knowledgeBaseId)
                .eq(DocumentPO::getSourcePath, sourcePath));
    }

    /**
     * 应用元数据字段。
     *
     * @param updatePath 是否更新路径相关字段（改名时为 true，仅元数据变化时为 false）
     * @param sourceType 文档来源。<b>调用方必须给</b>，不能在这里推断——这一层看到的
     *                   只有路径与字节，而"整包同步来的"与"用户单传的"可以长得完全一样
     *                   （详见 {@link DocumentPersistService} 的类注释）
     */
    private void applyMetadata(DocumentPO document, Long knowledgeBaseId, Long userId,
                               VaultFile file, ParsedDocument parsed, String storedPath,
                               boolean updatePath, KnowledgeSourceType sourceType) {
        document.setKbId(knowledgeBaseId);
        document.setUserId(userId);
        document.setFileName(file.fileName());
        document.setFileExt(file.extension());
        document.setSourceType(sourceType.name());
        if (updatePath) {
            document.setSourcePath(file.sourcePath());
            document.setFolder(file.folder());
        }
        document.setTitle(parsed.title());
        document.setFileSize(file.size());
        document.setFileHash(HashUtils.sha256Hex(file.content()));
        document.setContentHash(HashUtils.sha256Hex(parsed.content()));
        document.setFrontmatter(toJson(parsed.frontmatter()));
        document.setTags(toJson(parsed.tags()));
        document.setHeadingTree(toJson(parsed.headings()));
        document.setDocUpdatedAt(VaultPackageExtractor.toLocalDateTime(file.lastModified()));
        if (storedPath != null) {
            document.setStoredPath(storedPath);
        }
        // 本阶段只做"解析 + 切分"，因此直接置为 READY。
        // Phase 3 接入嵌入与索引后，这里会经历 EMBEDDING → INDEXING → READY。
        document.setStatus(DocumentStatus.READY.name());
        document.setErrorMsg(null);
    }

    /**
     * 重建文档的关联边（Phase 4）。
     *
     * <p>外链（http/https/mailto）也记下来但标记 {@code is_external}：
     * 界面上可以展示"引用了哪些外部资料"，而关联图与反向链接只用内部链接——
     * 否则图谱里会塞满互联网。
     */
    private void replaceLinks(Long knowledgeBaseId, Long documentId, ParsedDocument parsed) {
        documentLinkMapper.delete(Wrappers.<DocumentLinkPO>lambdaQuery()
                .eq(DocumentLinkPO::getFromDocumentId, documentId));
        List<ParsedDocument.Link> links = parsed.links();
        if (links == null || links.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int inserted = 0;
        for (ParsedDocument.Link link : links) {
            String target = link.target() == null ? "" : link.target().trim();
            if (target.isEmpty()) {
                continue;
            }
            DocumentLinkPO entity = new DocumentLinkPO();
            entity.setKbId(knowledgeBaseId);
            entity.setFromDocumentId(documentId);
            entity.setLinkType(link.type().name());
            entity.setTarget(clip(target, 500));
            entity.setDisplay(clip(link.display(), 500));
            entity.setStartOffset(link.startOffset());
            entity.setIsExternal(isExternal(target) ? 1 : 0);
            entity.setCreatedAt(now);
            documentLinkMapper.insert(entity);
            inserted++;
        }
        log.debug("文档关联边已重建 documentId={}, links={}", documentId, inserted);
    }

    private boolean isExternal(String target) {
        String lower = target.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:");
    }

    private String clip(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private void insertTags(Long knowledgeBaseId, Long documentId, List<String> tags) {        if (tags == null || tags.isEmpty()) {
            return;
        }
        for (String tag : tags) {
            DocumentTagPO entity = new DocumentTagPO();
            entity.setKbId(knowledgeBaseId);
            entity.setDocumentId(documentId);
            entity.setTag(tag);
            entity.setTagSource("FRONTMATTER");
            documentTagMapper.insert(entity);
        }
    }

    private void insertChunks(Long userId, Long knowledgeBaseId, Long documentId, List<TextChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<ChunkPO> buffer = new ArrayList<>(BATCH_SIZE);
        for (TextChunk chunk : chunks) {
            ChunkPO entity = new ChunkPO();
            entity.setId(IdWorker.getId());           // 批量插入不走自动填充，这里显式生成雪花 id
            entity.setKbId(knowledgeBaseId);
            entity.setDocumentId(documentId);
            entity.setUserId(userId);
            entity.setChunkIndex(chunk.index());
            entity.setChunkType(chunk.type().name());
            entity.setContent(truncate(chunk.content()));
            entity.setRawContent(truncate(chunk.rawContent()));
            entity.setContentHash(chunk.contentHash());
            entity.setHeading(chunk.heading());
            entity.setHeadingPath(chunk.headingPath());
            entity.setHeadingLevel(chunk.headingLevel());
            entity.setStartOffset(chunk.startOffset());
            entity.setEndOffset(chunk.endOffset());
            entity.setTokenCount(chunk.tokenCount());
            entity.setCharCount(chunk.charCount());
            // 向量与 ES 索引在 Phase 3 写入；这里先标记为待处理，
            // 这样"重建索引"任务可以直接按 status 捞出待办，而不必扫描全库。
            entity.setVectorStatus("PENDING");
            entity.setEsStatus("PENDING");
            buffer.add(entity);

            if (buffer.size() >= BATCH_SIZE) {
                chunkMapper.insertBatch(buffer);
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) {
            chunkMapper.insertBatch(buffer);
        }
    }

    private String truncate(String text) {
        if (text == null || text.length() <= MAX_CHUNK_CHARS) {
            return text;
        }
        log.warn("切片文本超过 {} 字符，已截断（通常说明切分参数异常）", MAX_CHUNK_CHARS);
        return text.substring(0, MAX_CHUNK_CHARS);
    }

    /** 转 JSON；失败时返回 null 而不是让整篇文档导入失败（元数据不完整总好过导入不了）。 */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return null;
        }
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("元数据序列化失败，已跳过该字段：{}", ex.getMessage());
            return null;
        }
    }
}
