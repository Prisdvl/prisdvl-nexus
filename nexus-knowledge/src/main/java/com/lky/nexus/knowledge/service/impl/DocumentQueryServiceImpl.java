package com.lky.nexus.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.PageQuery;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.model.dto.ChunkView;
import com.lky.nexus.knowledge.model.dto.DocumentDetail;
import com.lky.nexus.knowledge.model.dto.DocumentSummary;
import com.lky.nexus.knowledge.model.dto.HeadingView;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.parser.MarkdownDocumentParser;
import com.lky.nexus.knowledge.service.DocumentQueryService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import com.lky.nexus.knowledge.storage.DocumentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文档与切片查询实现。
 *
 * <p>所有查询都先跑一次 {@code requireOwned}：多租户隔离不能依赖"调用方记得加 user_id 条件"，
 * 而是每次查询前显式确认知识库归属。
 */
@Service
public class DocumentQueryServiceImpl implements DocumentQueryService {

    private static final Logger log = LoggerFactory.getLogger(DocumentQueryServiceImpl.class);

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final DocumentStorage documentStorage;
    private final JsonMapper jsonMapper;

    public DocumentQueryServiceImpl(KnowledgeBaseService knowledgeBaseService,
                                    DocumentMapper documentMapper,
                                    ChunkMapper chunkMapper,
                                    DocumentStorage documentStorage,
                                    JsonMapper jsonMapper) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.documentStorage = documentStorage;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public PageResult<DocumentSummary> listDocuments(Long userId, Long knowledgeBaseId,
                                                     int pageNum, int pageSize) {
        knowledgeBaseService.requireOwned(userId, knowledgeBaseId);
        int safePageNum = PageQuery.normalizePageNum(pageNum);
        int safePageSize = PageQuery.normalizePageSize(pageSize);

        Page<DocumentPO> page = documentMapper.selectPage(new Page<>(safePageNum, safePageSize),
                Wrappers.<DocumentPO>lambdaQuery()
                        .eq(DocumentPO::getKbId, knowledgeBaseId)
                        .orderByAsc(DocumentPO::getSourcePath));

        List<DocumentSummary> list = page.getRecords().stream().map(this::toSummary).toList();
        return PageResult.of(list, page.getTotal(), safePageNum, safePageSize);
    }

    @Override
    public List<ChunkView> listChunks(Long userId, Long knowledgeBaseId, Long documentId) {
        knowledgeBaseService.requireOwned(userId, knowledgeBaseId);
        return chunkMapper.selectList(Wrappers.<ChunkPO>lambdaQuery()
                        .eq(ChunkPO::getKbId, knowledgeBaseId)
                        .eq(ChunkPO::getDocumentId, documentId)
                        .orderByAsc(ChunkPO::getChunkIndex))
                .stream()
                .map(chunk -> new ChunkView(
                        chunk.getId(),
                        chunk.getChunkIndex(),
                        chunk.getChunkType(),
                        chunk.getHeadingPath(),
                        chunk.getRawContent() == null ? chunk.getContent() : chunk.getRawContent(),
                        chunk.getTokenCount(),
                        chunk.getCharCount(),
                        chunk.getVectorStatus(),
                        chunk.getEsStatus()))
                .toList();
    }

    @Override
    public DocumentDetail getDocument(Long userId, Long knowledgeBaseId, Long documentId) {
        knowledgeBaseService.requireOwned(userId, knowledgeBaseId);
        DocumentPO document = documentMapper.selectOne(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getId, documentId)
                .eq(DocumentPO::getKbId, knowledgeBaseId));
        if (document == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        // 正文从落盘的原始文件读回。文件可能因为手工清理等原因不存在，
        // 此时返回 null 让前端明确提示，而不是给一个空白页面让人以为"文档是空的"。
        String content = null;
        if (document.getStoredPath() != null) {
            byte[] bytes = documentStorage.load(document.getStoredPath());
            if (bytes == null) {
                log.warn("原始文件缺失，无法展示正文：documentId={}, storedPath={}",
                        documentId, document.getStoredPath());
            } else {
                // 只把正文交给前端：frontmatter 是元数据（已单独以 tags 等字段返回），
                // 混在正文里会被 Markdown 渲染成页面顶部的一坨 YAML
                content = MarkdownDocumentParser.stripFrontmatter(new String(bytes, StandardCharsets.UTF_8));
            }
        }

        Long fileSize = document.getFileSize();
        Double fileSizeKb = fileSize == null ? null : Math.round(fileSize / 1024.0 * 10) / 10.0;
        return new DocumentDetail(
                document.getId(),
                document.getSourcePath(),
                document.getFileName(),
                document.getFileExt(),
                document.getFolder(),
                document.getTitle(),
                document.getStatus(),
                content,
                parseHeadings(document.getHeadingTree()),
                parseTags(document.getTags()),
                fileSizeKb,
                document.getChunkCount(),
                document.getTokenCount(),
                document.getVersion(),
                document.getUpdatedAt());
    }

    /** 大纲：库里是解析阶段写下的 JSON 数组（level/text/anchor/startOffset）。 */
    private List<HeadingView> parseHeadings(String headingTreeJson) {
        if (headingTreeJson == null || headingTreeJson.isBlank()) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(headingTreeJson, new TypeReference<List<HeadingView>>() {
            });
        } catch (Exception ex) {
            log.debug("大纲解析失败，按空处理：{}", ex.getMessage());
            return List.of();
        }
    }

    private DocumentSummary toSummary(DocumentPO document) {
        Long fileSize = document.getFileSize();
        // 字节 → KB 保留一位小数（展示用；精确字节数在库里，展示不必给）
        Double fileSizeKb = fileSize == null ? null : Math.round(fileSize / 1024.0 * 10) / 10.0;
        return new DocumentSummary(
                document.getId(),
                document.getSourcePath(),
                document.getFileName(),
                document.getFileExt(),
                document.getFolder(),
                document.getTitle(),
                fileSizeKb,
                document.getStatus(),
                document.getChunkCount(),
                document.getTokenCount(),
                document.getVersion(),
                parseTags(document.getTags()),
                document.getUpdatedAt());
    }

    /** 标签在库里是 JSON 字符串；解析失败按空处理（展示层不该因脏数据整页失败）。 */
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
