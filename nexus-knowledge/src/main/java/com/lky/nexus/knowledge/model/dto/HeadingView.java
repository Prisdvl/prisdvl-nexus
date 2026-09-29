package com.lky.nexus.knowledge.model.dto;

/**
 * 文档大纲中的一项（标题树的一个节点）。
 *
 * <p>{@code anchor} 用于前端跳转：它是按标题文本生成的稳定标识，
 * 与切片里的 {@code headingPath} 同源（都来自解析阶段）。
 */
public record HeadingView(int level, String text, String anchor, int startOffset) {
}
