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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    /**
     * 大纲：库里是解析阶段写下的 JSON 数组（level/text/anchor/startOffset）。
     *
     * <p>用 {@code readTree} 手工取字段，而不是 {@code readValue(json, TypeReference)}：
     * 实测后者在这条路径上返回了空结果（异常被静默 catch 成空列表），
     * 表现为"文档明明有标题树，接口却返回空大纲"。
     * 手工解析虽啰嗦，但每一步都可断言、失败也不会静默。
     */
    private List<HeadingView> parseHeadings(String headingTreeJson) {
        if (headingTreeJson == null || headingTreeJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = jsonMapper.readTree(headingTreeJson);
            if (!array.isArray()) {
                return List.of();
            }
            List<HeadingView> headings = new ArrayList<>(array.size());
            for (JsonNode node : array) {
                headings.add(new HeadingView(
                        node.path("level").asInt(1),
                        node.path("text").asString(""),
                        node.path("anchor").asString(""),
                        node.path("startOffset").asInt(0)));
            }
            return headings;
        } catch (Exception ex) {
            log.warn("大纲解析失败（返回空大纲）：{}", ex.getMessage());
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
            JsonNode array = jsonMapper.readTree(tagsJson);
            if (!array.isArray()) {
                return List.of();
            }
            List<String> tags = new ArrayList<>(array.size());
            for (JsonNode node : array) {
                tags.add(node.asString());
            }
            return tags;
        } catch (Exception ex) {
            log.warn("标签解析失败（返回空标签）：{}", ex.getMessage());
            return List.of();
        }
    }
}
