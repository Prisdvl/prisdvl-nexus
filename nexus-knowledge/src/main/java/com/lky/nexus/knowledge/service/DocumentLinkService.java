package com.lky.nexus.knowledge.service;

import com.lky.nexus.knowledge.model.dto.DocumentLinkView;

import java.util.List;

/**
 * 文档关联服务（Phase 4）：出链与反向链接。
 *
 * <p><b>目标在查询时解析</b>，而不是导入时：WikiLink 指向的笔记可能还没导入
 * （先写笔记、后补被引用那篇很常见），写入期解析会把"暂未解析"永久固化成脏数据。
 * 查询时解析的代价是一次内存匹配，换来的是"后导入的文档自动补上关联"。
 */
public interface DocumentLinkService {

    /**
     * 查询一篇文档的关联。
     *
     * @return 出链（本文档指向谁）与反向链接（谁引用了本文档）
     */
    DocumentLinks links(Long userId, Long knowledgeBaseId, Long documentId);

    /**
     * 相关推荐（Phase 4.2）：与本篇文档"内容相关"的其他文档。
     *
     * <p>评分 = 互相链接（30 分，最强）+ 共同标签（每个 10 分）。
     * 刻意不依赖向量库：standalone（无 pgvector）环境下也能算，
     * 且标签/链接本身就是很好的相关性信号。
     */
    List<RelatedDocument> related(Long userId, Long knowledgeBaseId, Long documentId, int limit);

    /**
     * 相关推荐项。
     *
     * @param linked   是否与本篇互相链接（出链或反向链接）
     * @param reasons  为什么相关（"互相链接"、"共同标签: Java"）
     * @param score    排序分（只在内部使用，不返回给前端）
     */
    record RelatedDocument(Long documentId, String title, int sharedTags, boolean linked,
                           List<String> reasons, int score) {
    }

    /**
     * @param outlinks  出链（含外链，外链的 resolvedDocumentId 为 null）
     * @param backlinks 反向链接
     * @param dangling  未被解析的出链数（指向了尚不存在的文档）——"待创建笔记"的清单
     */
    record DocumentLinks(List<DocumentLinkView> outlinks, List<DocumentLinkView> backlinks, int dangling) {
    }
}
