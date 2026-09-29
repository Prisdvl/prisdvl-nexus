package com.lky.nexus.knowledge.chunk;

import com.lky.nexus.knowledge.model.ChunkType;

/**
 * 文本块（切分的最小单位）。
 *
 * @param type  块类型（代码/表格/列表/段落）
 * @param text  块文本
 * @param start 在正文中的起始字符偏移
 * @param end   结束偏移（不含）
 */
public record Segment(ChunkType type, String text, int start, int end) {
}
