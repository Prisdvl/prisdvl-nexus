package com.lky.nexus.knowledge.model.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 修改知识库请求（局部更新，字段为空表示不修改）。
 *
 * <p>刻意<b>不允许</b>修改嵌入模型与切分参数：前者会让已有向量失去可比性（ADR-008），
 * 后者会让同一库里的切片标准不一致（检索时排名不可比）。要换就用新的参数新建一个知识库。
 *
 * <p>但检索参数（retrievalTopK / similarityThreshold）与 status（启用/禁用）是可改的：
 * 它们只影响"怎么查"，不影响"库里存了什么"。
 */
public record UpdateKnowledgeBaseRequest(

        @Size(max = 128, message = "名称长度不能超过 128")
        String name,

        @Size(max = 512, message = "描述长度不能超过 512")
        String description,

        @Size(max = 512, message = "图标长度不能超过 512")
        String icon,

        /** ENABLED / DISABLED。 */
        String status,

        @Min(value = 1, message = "retrievalTopK 不能小于 1")
        @Max(value = 50, message = "retrievalTopK 不能大于 50")
        Integer retrievalTopK,

        @DecimalMin(value = "0.0", message = "similarityThreshold 不能小于 0")
        @DecimalMax(value = "1.0", message = "similarityThreshold 不能大于 1")
        Double similarityThreshold) {
}
