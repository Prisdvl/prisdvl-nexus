package com.lky.nexus.app.observability.model.dto;

import java.time.LocalDateTime;

/**
 * 请求详情里的一次模型调用（{@code observability_call} 的一行原样呈现）。
 *
 * @param id           明细行 ID
 * @param scene        场景
 * @param model        模型名
 * @param tokensIn     prompt token；供应商没回传时为 {@code null}
 * @param tokensOut    completion token；同上
 * @param latencyMs    本次耗时
 * @param firstTokenMs 首 Token 延迟；非流式为 {@code null}
 * @param success      是否成功
 * @param errorCode    失败时的业务码
 * @param createdAt    调用开始时间
 */
public record CallItemResponse(
        Long id,
        String scene,
        String model,
        Integer tokensIn,
        Integer tokensOut,
        Long latencyMs,
        Long firstTokenMs,
        boolean success,
        Integer errorCode,
        LocalDateTime createdAt) {
}
