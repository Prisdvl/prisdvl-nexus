package com.lky.nexus.knowledge.model.dto;

import java.util.List;

/**
 * 跨库统一检索的响应。
 *
 * @param hits         命中列表，已按相关性降序（重排时为重排分数序）
 * @param emptyReason  {@code NO_KB} / {@code NO_INDEX} / {@code NO_RESULTS}；有命中时为 {@code null}
 * @param tookMs       服务端耗时（毫秒），含扇出与归并，不含网络
 * @param degradedKbs  本次检索里失败的库：它们没有贡献命中，但**不影响**其它库的返回结果
 */
public record UnifiedSearchResponse(
        List<UnifiedSearchHit> hits,
        String emptyReason,
        long tookMs,
        List<DegradedKb> degradedKbs) {
}
