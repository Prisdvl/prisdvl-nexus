package com.lky.nexus.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.common.util.HashUtils;
import com.lky.nexus.knowledge.chunk.TextChunk;
import com.lky.nexus.knowledge.imports.VaultFile;
import com.lky.nexus.knowledge.imports.VaultPackageExtractor;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.DocumentTagMapper;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.DocumentStatus;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.model.entity.DocumentTagPO;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.parser.ParsedDocument;
import com.lky.nexus.knowledge.service.DocumentPersistResult;
import com.lky.nexus.knowledge.service.DocumentPersistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档落库实现。
 *
 * <p>两个值得注意的点：
 * <ol>
 *   <li><b>批量插入切片</b>：一个 Vault 上千篇笔记会产生几万行切片，
 *       逐条 insert 是几万次数据库往返。这里按 200 行一批插入；</li>
 *   <li><b>覆盖时先清理旧数据</b>：切片的唯一键是 (document_id, chunk_index)，
 *       如果不清旧行，重新切分后行数变少就会留下"幽灵切片"——检索能命中但早已不存在于正文里。</li>
 * </ol>
 */
@Service
public class DocumentPersistServiceImpl implements DocumentPersistService {

    private static final Logger log = LoggerFactory.getLogger(DocumentPersistServiceImpl.class);

    /** 单批插入的行数：太大可能超过 MySQL 的 max_allowed_packet，太小则往返次数多。 */
    private static final int BATCH_SIZE = 200;

    /** md_chunk.content 是 MEDIUMTEXT，但单片过大本身就说明切分有问题，这里做个记录性截断保护。 */
    private static final int MAX_CHUNK_CHARS = 20000;

    private final DocumentMapper documentMapper;
    private final DocumentTagMapper documentTagMapper;
    private final ChunkMapper chunkMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final JsonMapper jsonMapper;

    public DocumentPersistServiceImpl(DocumentMapper documentMapper,
                                      DocumentTagMapper documentTagMapper,
                                      ChunkMapper chunkMapper,
                                      KnowledgeBaseMapper knowledgeBaseMapper,
                                      JsonMapper jsonMapper) {
        this.documentMapper = documentMapper;
        this.documentTagMapper = documentTagMapper;
        this.chunkMapper = chunkMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
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
        DocumentPO existing = documentMapper.selectOne(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, knowledgeBase.getId())
                .eq(DocumentPO::getSourcePath, file.sourcePath()));

        LocalDateTime now = LocalDateTime.now();
        DocumentPO document = existing == null ? new DocumentPO() : existing;
        boolean created = existing == null;

        document.setKbId(knowledgeBase.getId());
        document.setUserId(userId);
        document.setFileName(file.fileName());
        document.setFileExt(file.extension());
        document.setSourceType(com.lky.nexus.knowledge.model.KnowledgeSourceType.OBSIDIAN.name());
        document.setSourcePath(file.sourcePath());
        document.setFolder(file.folder());
        document.setTitle(parsed.title());
        document.setFileSize(file.size());
        document.setFileHash(HashUtils.sha256Hex(file.content()));
        document.setContentHash(HashUtils.sha256Hex(parsed.content()));
        document.setFrontmatter(toJson(parsed.frontmatter()));
        document.setTags(toJson(parsed.tags()));
        document.setHeadingTree(toJson(parsed.headings()));
        document.setDocUpdatedAt(VaultPackageExtractor.toLocalDateTime(file.lastModified()));
        // 说明：本阶段只做"解析 + 切分"，因此直接置为 READY。
        // Phase 3 接入嵌入与索引后，这里会经历 EMBEDDING → INDEXING → READY。
        document.setStatus(DocumentStatus.READY.name());
        document.setErrorMsg(null);
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
            documentTagMapper.delete(Wrappers.<DocumentTagPO>lambdaQuery()
                    .eq(DocumentTagPO::getDocumentId, document.getId()));
        }

        insertTags(knowledgeBase.getId(), document.getId(), parsed.tags());
        insertChunks(userId, knowledgeBase.getId(), document.getId(), chunks);

        log.debug("文档落库完成 path={}, created={}, chunks={}", file.sourcePath(), created, chunks.size());
        return new DocumentPersistResult(document, created);
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
    }

    @Override
    @Transactional
    public void refreshKnowledgeBaseStats(Long knowledgeBaseId) {
        Long docCount = documentMapper.selectCount(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, knowledgeBaseId));

        // 切片数与 token 总数用两条聚合查询算出来，而不是在 Java 里累加：
        // 导入失败重试、并发导入等情况下，Java 侧计数容易与实际行数不一致。
        List<ChunkPO> chunks = chunkMapper.selectList(Wrappers.<ChunkPO>lambdaQuery()
                .select(ChunkPO::getTokenCount)
                .eq(ChunkPO::getKbId, knowledgeBaseId));
        long totalTokens = chunks.stream().mapToLong(chunk -> chunk.getTokenCount() == null ? 0 : chunk.getTokenCount()).sum();

        KnowledgeBasePO update = new KnowledgeBasePO();
        update.setId(knowledgeBaseId);
        update.setDocCount(docCount.intValue());
        update.setChunkCount(chunks.size());
        update.setTotalTokens(totalTokens);
        update.setUpdatedAt(LocalDateTime.now());
        knowledgeBaseMapper.updateById(update);
    }

    private void insertTags(Long knowledgeBaseId, Long documentId, List<String> tags) {
        if (tags == null || tags.isEmpty()) {
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
