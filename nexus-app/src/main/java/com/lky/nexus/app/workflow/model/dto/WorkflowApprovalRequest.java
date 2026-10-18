package com.lky.nexus.app.workflow.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 人工审批决定。
 *
 * <p>{@code decision} 只接受 {@code APPROVE} / {@code REJECT}（大小写不敏感）；
 * 别的值按参数错误处理，而不是默默当成同意——审批方向搞反的代价太大。
 *
 * <p>{@code comment} 选填：同意时作为闸门节点的输出留痕，拒绝时作为失败原因落库。
 */
public record WorkflowApprovalRequest(
        @NotBlank(message = "decision 不能为空") String decision,
        @Size(max = 512, message = "审批意见长度不能超过 512") String comment
) {
}
