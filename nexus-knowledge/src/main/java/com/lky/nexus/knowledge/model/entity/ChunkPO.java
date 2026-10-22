package com.lky.nexus.knowledge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 切片实体（kb_chunk）。
 *
 * <p>注意 {@code content} 与 {@code rawContent} 的分工（见 TextChunk 的说明）：
 * 前者参与嵌入（带标题路径，让短小节可被检索），后者是原文（展示与引用，必须忠实）。
 */
@Data
@TableName("kb_chunk")
public class ChunkPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long kbId;

    private Long documentId;

    private Long userId;

    /** 文档内序号，从 0 开始。 */
    private Integer chunkIndex;

    /** PARAGRAPH / CODE / TABLE / LIST / HEADING */
    private String chunkType;

    /** 参与嵌入与 BM25 的文本（标题路径 + 正文）。 */
    private String content;

    /** 原文片段。 */
    private String rawContent;

    /** SHA-256(content)：Embedding 缓存键。 */
    private String contentHash;

    private String heading;

    private String headingPath;

    private Integer headingLevel;

    private Integer startOffset;

    private Integer endOffset;

    private Integer tokenCount;

    private Integer charCount;

    /** Phase 3 回填：pgvector 行 id。 */
    private String vectorId;

    /** PENDING / DONE / FAILED / STALE */
    private String vectorStatus;

    private String esStatus;

    /**
     * 最近一次成功写入检索索引的时间（V29）；{@code null} = 从未成功写入。
     *
     * <p>为什么不复用 {@code updated_at}：后者被任何字段更新（重新分块、改标签）带跑，
     * 拿它当"建索引时间"会把「刚改过但没建索引」的库显示成已索引。
     */
    private LocalDateTime indexedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
