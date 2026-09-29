package com.lky.nexus.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lky.nexus.common.api.PageQuery;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.model.dto.ChunkView;
import com.lky.nexus.knowledge.model.dto.DocumentSummary;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.service.DocumentQueryService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

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
    private final JsonMapper jsonMapper;

    public DocumentQueryServiceImpl(KnowledgeBaseService knowledgeBaseService,
                                    DocumentMapper documentMapper,
                                    ChunkMapper chunkMapper,
                                    JsonMapper jsonMapper) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
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
