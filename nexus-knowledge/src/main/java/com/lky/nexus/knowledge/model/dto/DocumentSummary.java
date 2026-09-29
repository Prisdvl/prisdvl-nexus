package com.lky.nexus.knowledge.model.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档列表视图。
 *
 * <p>{@code tags} 直接给成数组：它在库里存的是 JSON 字符串，
 * 让前端去解析 JSON 是把存储细节泄露给了界面层。
 */
public record DocumentSummary(
        Long id,
        String sourcePath,
        String fileName,
        String fileExt,
        String folder,
        String title,
        /** 文件大小（KB，保留一位小数）—— 用 Double 而不是 Long，因为它带小数。 */
        Double fileSizeKb,
        String status,
        Integer chunkCount,
        Integer tokenCount,
        Integer version,
        List<String> tags,
        LocalDateTime updatedAt) {
}
