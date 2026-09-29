package com.lky.nexus.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import com.lky.nexus.knowledge.service.DocumentPersistResult;
import com.lky.nexus.knowledge.service.DocumentPersistService;
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

    public DocumentPersistServiceImpl(DocumentMapper documentMapper,
                                      DocumentTagMapper documentTagMapper,
                                      DocumentLinkMapper documentLinkMapper,
                                      ChunkMapper chunkMapper,
                                      KnowledgeBaseMapper knowledgeBaseMapper,
                                      DocumentStorage documentStorage,
                                      JsonMapper jsonMapper) {
        this.documentMapper = documentMapper;
        this.documentTagMapper = documentTagMapper;
        this.documentLinkMapper = documentLinkMapper;
        this.chunkMapper = chunkMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentStorage = documentStorage;
        this.jsonMapper = jsonMapper;
    }

    @Override
    @Transactional
    public DocumentPersistResult save(Long userId,
                                      KnowledgeBasePO knowledgeBase,
                                      VaultFile file,
                                      ParsedDocument parsed,
                                      List<TextChunk> chunks,
                                      String storedPath) {
        DocumentPO existing = findByPath(knowledgeBase.getId(), file.sourcePath());
        boolean created = existing == null;

        LocalDateTime now = LocalDateTime.now();
        DocumentPO document = created ? new DocumentPO() : existing;

        applyMetadata(document, knowledgeBase.getId(), userId, file, parsed, storedPath, true);
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
            // 覆盖前清掉旧切片与标签，避免"幽灵切片"
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
                                   ParsedDocument parsed) {
        LocalDateTime now = LocalDateTime.now();
        String oldStoredPath = existing.getStoredPath();
        applyMetadata(existing, knowledgeBase.getId(), userId, file, parsed, oldStoredPath, false);
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
                         ParsedDocument parsed) {
        String oldStoredPath = existing.getStoredPath();
        LocalDateTime now = LocalDateTime.now();
        applyMetadata(existing, knowledgeBase.getId(), userId, file, parsed, oldStoredPath, true);
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
        chunkMapper.delete(Wrappers.<ChunkPO>lambdaQuery().eq(ChunkPO::getDocumentId, document.getId()));
        documentTagMapper.delete(Wrappers.<DocumentTagPO>lambdaQuery()
                .eq(DocumentTagPO::getDocumentId, document.getId()));
        documentMapper.deleteById(document.getId());
        // 原始文件也清掉：文档都删了，留着它只会随每次导入不断累积
        documentStorage.delete(document.getStoredPath());
    }

    @Override
    @Transactional
    public void refreshKnowledgeBaseStats(Long knowledgeBaseId) {
        Long docCount = documentMapper.selectCount(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, knowledgeBaseId));

        // 切片数与 token 总数用聚合查询算出来，而不是在 Java 里累加：
        // 导入失败重试、重复导入等情况下，Java 侧计数容易与实际行数不一致。
        List<ChunkPO> chunks = chunkMapper.selectList(Wrappers.<ChunkPO>lambdaQuery()
                .select(ChunkPO::getTokenCount)
                .eq(ChunkPO::getKbId, knowledgeBaseId));
        long totalTokens = chunks.stream()
                .mapToLong(chunk -> chunk.getTokenCount() == null ? 0 : chunk.getTokenCount())
                .sum();

        KnowledgeBasePO update = new KnowledgeBasePO();
        update.setId(knowledgeBaseId);
        update.setDocCount(docCount.intValue());
        update.setChunkCount(chunks.size());
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
     */
    private void applyMetadata(DocumentPO document, Long knowledgeBaseId, Long userId,
                               VaultFile file, ParsedDocument parsed, String storedPath,
                               boolean updatePath) {
        document.setKbId(knowledgeBaseId);
        document.setUserId(userId);
        document.setFileName(file.fileName());
        document.setFileExt(file.extension());
        document.setSourceType(KnowledgeSourceType.OBSIDIAN.name());
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
