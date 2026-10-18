package com.lky.nexus.app.workflow.model.dto;

import jakarta.validation.constraints.Size;

/**
 * 修改工作流元信息请求（局部更新：字段为 null 表示不修改，与 {@code UpdateKnowledgeBaseRequest} 同一约定）。
 *
 * <p>只改元信息（名称 / 描述），<b>不动图</b>——图走 {@code PUT /api/workflows/{id}/draft}，
 * 那里每保存一次就生成一个不可变版本快照。把两件事分开，是为了让「改个名字」不产生新版本。
 *
 * <p>description 传空串表示清空（与知识库一致）；name 为空白则视为未提供，不允许把名字改成空。
 */
public record UpdateWorkflowRequest(
        @Size(max = 128, message = "名称长度不能超过 128") String name,
        @Size(max = 512, message = "描述长度不能超过 512") String description
) {
}
