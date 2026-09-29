package com.lky.nexus.knowledge.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建知识库请求。
 *
 * <p>注意这里<b>不接受</b>嵌入模型参数：嵌入模型由配置里的 {@code embedding.default} 角色决定，
 * 并在建库时冻结（ADR-008）。让客户端指定模型，等于允许把两种维度的向量混进同一个库。
 *
 * <p>切分参数可选，缺省用系统默认值（400 / 50）。
 */
public record CreateKnowledgeBaseRequest(

        @NotBlank(message = "知识库名称不能为空")
        @Size(max = 128, message = "名称长度不能超过 128")
        String name,

        @Size(max = 512, message = "描述长度不能超过 512")
        String description,

        /** MARKDOWN_HEADING（默认，按标题结构切） / RECURSIVE（按段落递归切）。 */
        String chunkStrategy,

        @Min(value = 100, message = "chunkSize 不能小于 100")
        @Max(value = 2000, message = "chunkSize 不能大于 2000")
        Integer chunkSize,

        @Min(value = 0, message = "chunkOverlap 不能为负")
        @Max(value = 500, message = "chunkOverlap 不能大于 500")
        Integer chunkOverlap) {
}
