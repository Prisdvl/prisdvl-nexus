package com.lky.nexus.knowledge.model.dto;

/**
 * 文档关联视图（Phase 4）。
 *
 * @param linkId             边 id
 * @param linkType           WIKILINK / MARKDOWN_LINK / EMBED
 * @param target             原始目标（可能是笔记名、相对路径或 URL）
 * @param display            展示文本
 * @param resolvedDocumentId 解析到的文档 id；null 表示"暂未解析"
 * @param resolvedTitle      解析到的文档标题
 * @param external           是否外链（外链不参与内部关联图）
 * @param startOffset        在正文中的位置（供前端定位）
 */
public record DocumentLinkView(
        Long linkId,
        String linkType,
        String target,
        String display,
        Long resolvedDocumentId,
        String resolvedTitle,
        boolean external,
        int startOffset) {
}
