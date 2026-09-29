package com.lky.nexus.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.mapper.DocumentLinkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.model.dto.DocumentLinkView;
import com.lky.nexus.knowledge.model.entity.DocumentLinkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.service.DocumentLinkService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文档关联实现。
 *
 * <p>解析规则（按优先级）：相对路径 → 文件名（含/不含扩展名）→ 标题。
 * 这与 Obsidian 的链接语义一致：{@code [[SpringBoot]]} 指的是"名为 SpringBoot 的笔记"，
 * 而 {@code [文本](Java/JVM.md)} 指的是"那个路径的文件"。
 *
 * <p>反向链接用"候选目标字符串"做精确匹配查询（而不是把全库的边都捞回来在内存里过滤）：
 * 一张大库的边可能有几万条，能走索引就不要全表扫。
 */
@Service
public class DocumentLinkServiceImpl implements DocumentLinkService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentMapper documentMapper;
    private final DocumentLinkMapper documentLinkMapper;

    public DocumentLinkServiceImpl(KnowledgeBaseService knowledgeBaseService,
                                   DocumentMapper documentMapper,
                                   DocumentLinkMapper documentLinkMapper) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentMapper = documentMapper;
        this.documentLinkMapper = documentLinkMapper;
    }

    @Override
    public DocumentLinks links(Long userId, Long knowledgeBaseId, Long documentId) {
        knowledgeBaseService.requireOwned(userId, knowledgeBaseId);
        DocumentPO document = documentMapper.selectOne(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getId, documentId)
                .eq(DocumentPO::getKbId, knowledgeBaseId));
        if (document == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        // 一次把本库文档读进内存做匹配：解析一篇文档的链接最多也就几十条，
        // 而为每条链接查一次库才是真的低效（N+1）
        Map<String, DocumentPO> byName = buildLookup(knowledgeBaseId);

        List<DocumentLinkPO> outgoing = documentLinkMapper.selectList(
                Wrappers.<DocumentLinkPO>lambdaQuery()
                        .eq(DocumentLinkPO::getFromDocumentId, documentId)
                        .orderByAsc(DocumentLinkPO::getStartOffset));

        List<DocumentLinkView> outlinks = new ArrayList<>(outgoing.size());
        int dangling = 0;
        for (DocumentLinkPO link : outgoing) {
            boolean external = link.getIsExternal() != null && link.getIsExternal() == 1;
            DocumentPO resolved = external ? null : resolveTarget(byName, link.getTarget());
            if (!external && resolved == null) {
                dangling++;
            }
            outlinks.add(toView(link, resolved, external));
        }

        // 反向链接：用本文档的名字/路径作为候选目标
        Set<String> candidates = namesOf(document);
        List<DocumentLinkPO> incoming = documentLinkMapper.selectList(
                Wrappers.<DocumentLinkPO>lambdaQuery()
                        .eq(DocumentLinkPO::getKbId, knowledgeBaseId)
                        .in(DocumentLinkPO::getTarget, candidates)
                        .ne(DocumentLinkPO::getFromDocumentId, documentId));

        List<DocumentLinkView> backlinks = new ArrayList<>(incoming.size());
        for (DocumentLinkPO link : incoming) {
            DocumentPO source = byName.get("__id__" + link.getFromDocumentId());
            backlinks.add(toView(link, source, false));
        }
        return new DocumentLinks(outlinks, backlinks, dangling);
    }

    // ------------------------------------------------------------------

    private Map<String, DocumentPO> buildLookup(Long knowledgeBaseId) {
        Map<String, DocumentPO> lookup = new HashMap<>();
        for (DocumentPO document : documentMapper.selectList(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, knowledgeBaseId))) {
            // 按 id 也建一份索引：反向链接需要从 fromDocumentId 取回来源文档
            lookup.put("__id__" + document.getId(), document);
            for (String name : namesOf(document)) {
                lookup.putIfAbsent(name, document);
            }
        }
        return lookup;
    }

    /** 一篇文档可能被链接引用到的所有名字。 */
    private Set<String> namesOf(DocumentPO document) {
        Set<String> names = new LinkedHashSet<>();
        addName(names, document.getSourcePath());
        addName(names, document.getFileName());
        addName(names, document.getTitle());
        String fileName = document.getFileName();
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot > 0) {
                addName(names, fileName.substring(0, dot));
            }
        }
        return names;
    }

    private void addName(Set<String> names, String value) {
        if (value != null && !value.isBlank()) {
            names.add(value.trim());
        }
    }

    /** 把链接目标解析成文档：路径 → 文件名 → 标题，并兼容 {@code #小节} 与 {@code ./} 前缀。 */
    private DocumentPO resolveTarget(Map<String, DocumentPO> lookup, String rawTarget) {
        if (rawTarget == null) {
            return null;
        }
        String target = rawTarget.trim();
        int hash = target.indexOf('#');
        if (hash >= 0) {
            target = target.substring(0, hash);
        }
        target = target.replace('\\', '/');
        while (target.startsWith("./")) {
            target = target.substring(2);
        }
        target = target.replaceFirst("^/+", "");
        if (target.isEmpty()) {
            return null;
        }
        DocumentPO hit = lookup.get(target);
        if (hit != null) {
            return hit;
        }
        // 最后一段（文件名）兜底：链接可能写了完整路径，也可能只写笔记名
        int slash = target.lastIndexOf('/');
        String lastSegment = slash < 0 ? target : target.substring(slash + 1);
        hit = lookup.get(lastSegment);
        if (hit != null) {
            return hit;
        }
        return lookup.get(stripExtension(lastSegment));
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private DocumentLinkView toView(DocumentLinkPO link, DocumentPO resolved, boolean external) {
        return new DocumentLinkView(
                link.getId(),
                link.getLinkType(),
                link.getTarget(),
                link.getDisplay(),
                resolved == null ? null : resolved.getId(),
                resolved == null ? null : resolved.getTitle(),
                external,
                link.getStartOffset() == null ? 0 : link.getStartOffset());
    }

    /** 供调试用：把目标规范化成小写（当前未使用，保留给后续模糊匹配）。 */
    static String normalize(String target) {
        return target == null ? "" : target.trim().toLowerCase(Locale.ROOT);
    }
}
