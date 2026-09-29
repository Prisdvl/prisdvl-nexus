package com.lky.nexus.knowledge.chunk;

import com.lky.nexus.knowledge.model.ChunkType;

/**
 * 一个切片。
 *
 * <p>两个内容字段是刻意的区分（对应 kb_chunk 表的 content / raw_content）：
 * <ul>
 *   <li>{@link #content} —— <b>送去嵌入与 BM25 的文本</b>。它在正文前拼上了标题路径，
 *       让"只有一句话的小节"也能带上上下文（否则"自动配置"这样的短片段几乎无法被检索到）；</li>
 *   <li>{@link #rawContent} —— <b>原文片段</b>。展示与引用定位用它，
 *       用户看到的必须是原文，而不是被我们加工过的版本。</li>
 * </ul>
 *
 * <p>{@code startOffset}/{@code endOffset} 指向解析后正文的字符区间，
 * 前端据此在文档里高亮命中的位置。
 */
public record TextChunk(
        /** 文档内序号，从 0 开始。 */
        int index,
        ChunkType type,
        /** 嵌入用文本（标题路径 + 正文）。 */
        String content,
        /** 原文片段（不含标题路径前缀）。 */
        String rawContent,
        /** 所属最近标题。 */
        String heading,
        /** 完整标题路径，如 "Spring > SpringBoot > 自动配置"。 */
        String headingPath,
        /** 所属标题层级；无标题时为 null。 */
        Integer headingLevel,
        int startOffset,
        int endOffset,
        int tokenCount,
        int charCount,
        /** SHA-256(content)：Embedding 缓存键，也是"内容是否变化"的判断依据。 */
        String contentHash) {
}
