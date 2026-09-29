package com.lky.nexus.knowledge.model.dto;

/**
 * 切片视图。
 *
 * <p>同时给出 {@code vectorStatus} 与 {@code esStatus}：这两个状态决定了
 * "这条切片能不能被检索到"，是排查"为什么搜不到"的第一手信息，
 * 因此不要藏起来，直接展示在切片列表里。
 */
public record ChunkView(
        Long id,
        Integer chunkIndex,
        String chunkType,
        String headingPath,
        String content,
        Integer tokenCount,
        Integer charCount,
        String vectorStatus,
        String esStatus) {
}
