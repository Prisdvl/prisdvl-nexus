package com.lky.nexus.knowledge.model;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;

import java.util.Locale;

/**
 * 切分策略。
 *
 * <p>它在**建库时冻结**：同一个知识库里的切片标准必须一致，
 * 否则"按相似度排序"就失去意义（不同策略切出的片段粒度不同，分数不可比）。
 */
public enum ChunkStrategy {

    /** 按 Markdown 标题层级切分（默认，适合 Obsidian 笔记）。 */
    MARKDOWN_HEADING,

    /** 按段落递归切分（适合无结构的纯文本 / PDF 提取结果）。 */
    RECURSIVE;

    public static final ChunkStrategy DEFAULT = MARKDOWN_HEADING;

    public static ChunkStrategy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "不支持的切分策略: %s（可选值：MARKDOWN_HEADING / RECURSIVE）".formatted(raw));
        }
    }
}
