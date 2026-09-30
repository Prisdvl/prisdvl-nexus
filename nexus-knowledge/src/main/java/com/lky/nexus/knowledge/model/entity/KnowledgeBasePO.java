package com.lky.nexus.knowledge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库实体（kb_knowledge_base）。
 *
 * <p>{@code embeddingProvider / embeddingModel / embeddingDim} 在**创建时冻结**（ADR-008），
 * 之后不允许修改——维度一变，库里已有向量就失去可比性，而且不会报错。
 */
@Data
@TableName("kb_knowledge_base")
public class KnowledgeBasePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所有者：所有查询必须带此条件。 */
    private Long userId;

    /** 所属工作区（平台化 Phase 2）：资源归属从 user 上移到 workspace。 */
    private Long workspaceId;

    private String name;

    private String description;

    /** 图标（URL 或 emoji）。 */
    private String icon;

    /** ENABLED / DISABLED：禁用后不参与检索。 */
    private String status;

    /** RAG 检索 TopK（按库持久化，Agent/Application 可覆盖）。 */
    private Integer retrievalTopK;

    /** 相似度阈值。 */
    private Double similarityThreshold;

    /** MANUAL / OBSIDIAN / MIXED */
    private String sourceType;

    /** 嵌入 provider 名（如 ollama / opencode）。 */
    private String embeddingProvider;

    /** 嵌入模型名（如 bge-m3）。 */
    private String embeddingModel;

    /** 嵌入维度：必须与向量库列宽一致。 */
    private Integer embeddingDim;

    /** MARKDOWN_HEADING / RECURSIVE */
    private String chunkStrategy;

    private Integer chunkSize;

    private Integer chunkOverlap;

    private Integer docCount;

    private Integer chunkCount;

    private Long totalTokens;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
