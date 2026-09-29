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
     * @param outlinks  出链（含外链，外链的 resolvedDocumentId 为 null）
     * @param backlinks 反向链接
     * @param dangling  未被解析的出链数（指向了尚不存在的文档）——"待创建笔记"的清单
     */
    record DocumentLinks(List<DocumentLinkView> outlinks, List<DocumentLinkView> backlinks, int dangling) {
    }
}
