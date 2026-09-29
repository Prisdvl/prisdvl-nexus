package com.lky.nexus.knowledge.parser;

import java.util.List;
import java.util.Map;

/**
 * 解析结果：一篇文档被拆解出的**结构**，而不是一大段文本。
 *
 * <p>为什么要保留这么多结构而不是只留下纯文本：RAG 的效果与引用体验都依赖它们——
 * <ul>
 *   <li>{@code headings} 决定按标题切分的边界，也决定引用能不能"跳到某个小节"；</li>
 *   <li>{@code tags} 支撑"按标签过滤检索"；</li>
 *   <li>{@code links} 是知识关联（Phase 4）的原料；</li>
 *   <li>{@code content} 上的偏移量让切分结果可回溯到原文位置。</li>
 * </ul>
 */
public record ParsedDocument(
        /** 标题：frontmatter.title → 首个 H1 → 文件名（去扩展名）。 */
        String title,
        /** 规范化后的正文（已剥离 frontmatter），后续切分与嵌入都基于它。 */
        String content,
        /** frontmatter 原始键值（无 frontmatter 时为空 Map）。 */
        Map<String, Object> frontmatter,
        /** 标签：frontmatter 的 tags/tag + 正文内联 #tag；已去重、统一小写。 */
        List<String> tags,
        /** 标题列表，按出现顺序；偏移量指向 {@link #content} 中的字符位置。 */
        List<Heading> headings,
        /** 链接（WikiLink / Markdown 链接 / 非图片嵌入）。 */
        List<Link> links,
        /** 图片等附件引用。 */
        List<Attachment> attachments) {

    /**
     * 标题。
     *
     * @param level       1~6，对应 #~######
     * @param text        标题文本
     * @param startOffset 在 content 中的起始字符偏移
     * @param anchor      锚点（供前端跳转）
     */
    public record Heading(int level, String text, int startOffset, String anchor) {
    }

    /** 链接类型。 */
    public enum LinkType {
        /** Obsidian 双链 {@code [[目标]]}。 */
        WIKILINK,
        /** 标准 Markdown 链接 {@code [文本](地址)}。 */
        MARKDOWN_LINK,
        /** 嵌入 {@code ![[目标]]}（非图片），例如嵌入另一篇笔记。 */
        EMBED
    }

    /**
     * 链接。
     *
     * @param type        链接类型
     * @param target      目标（WikiLink 里是笔记名或路径；Markdown 链接里是 URL/相对路径）
     * @param display     展示文本（可为空）
     * @param startOffset 起始偏移
     */
    public record Link(LinkType type, String target, String display, int startOffset) {
    }

    /**
     * 附件（图片等）。
     *
     * @param path        引用路径（如 Attachments/图1.png）
     * @param alt         替代文本 / WikiLink 展示名
     * @param startOffset 起始偏移
     */
    public record Attachment(String path, String alt, int startOffset) {
    }

    /** 是否解析出了标题（供调用方判断是否需要回退到"整篇一段"的切分）。 */
    public boolean hasHeadings() {
        return headings != null && !headings.isEmpty();
    }
}
