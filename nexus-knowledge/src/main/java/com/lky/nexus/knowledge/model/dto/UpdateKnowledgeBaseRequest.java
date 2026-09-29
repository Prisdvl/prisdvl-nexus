package com.lky.nexus.knowledge.model.dto;

import jakarta.validation.constraints.Size;

/**
 * 修改知识库请求（局部更新）。
 *
 * <p>刻意<b>不允许</b>修改嵌入模型与切分参数：前者会让已有向量失去可比性（ADR-008），
 * 后者会让同一库里的切片标准不一致（检索时排名不可比）。
 * 要换就用新的参数新建一个知识库。
 */
public record UpdateKnowledgeBaseRequest(

        @Size(max = 128, message = "名称长度不能超过 128")
        String name,

        @Size(max = 512, message = "描述长度不能超过 512")
        String description) {
}
