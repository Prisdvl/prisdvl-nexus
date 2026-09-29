package com.lky.nexus.knowledge.model.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档详情（含正文与大纲）。
 *
 * <p>{@code content} 是<b>原始 Markdown 正文</b>（从落盘的原始文件读回），
 * 而不是切片拼接的结果：切片是"检索单位"，会带标题路径前缀、也做过边界切分，
 * 用它渲染正文会出现重复标题与断裂的段落。展示必须回到原文。
 *
 * @param content  原始正文；原始文件丢失时为 null（此时前端应给出提示而不是空白页）
 * @param headings 大纲（按出现顺序），供前端目录跳转
 */
public record DocumentDetail(
        Long id,
        String sourcePath,
        String fileName,
        String fileExt,
        String folder,
        String title,
        String status,
        String content,
        List<HeadingView> headings,
        List<String> tags,
        Double fileSizeKb,
        Integer chunkCount,
        Integer tokenCount,
        Integer version,
        LocalDateTime updatedAt) {
}
