package com.lky.nexus.app.observability.model.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单个模型的用量（{@code GET /api/observability/models}）。
 *
 * @param model        模型名（供应商返回的那个，不是角色名）
 * @param calls        调用次数
 * @param tokensIn     prompt token 合计
 * @param tokensOut    completion token 合计
 * @param tokens       两者之和
 * @param failures     失败次数
 * @param avgLatencyMs 平均耗时
 * @param estimatedCost 估算成本；<b>该模型没配价格时为 {@code null}</b>
 * @param priced       是否配了完整价格（{@code estimatedCost} 是否可用）
 * @param scenes       该模型被哪些场景用到（升序、去重）
 */
public record ModelUsageResponse(
        String model,
        long calls,
        long tokensIn,
        long tokensOut,
        long tokens,
        long failures,
        long avgLatencyMs,
        BigDecimal estimatedCost,
        boolean priced,
        List<String> scenes) {
}
