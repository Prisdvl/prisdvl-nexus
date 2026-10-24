package com.lky.nexus.knowledge.service;

import com.lky.nexus.knowledge.model.dto.SearchExplainResponse;
import com.lky.nexus.knowledge.model.dto.SearchRequest;
import com.lky.nexus.knowledge.model.dto.UnifiedSearchResponse;

/**
 * 跨库统一检索（{@code /api/search} 与 {@code /api/search/explain}）。
 *
 * <p>它<b>不重新实现检索</b>：单库召回、扇出、归并、融合都在既有链路上，
 * 这一层只做三件既有链路不做的事：
 * <ol>
 *   <li>把"哪些库"从 {@code kbIds}（可为空 = 全部）解析成真实的库列表，并顺手挡住越权 id；</li>
 *   <li>把单库失败翻译成 {@code degradedKbs}，让"一个库挂了"不等于"整次检索失败"；</li>
 *   <li>把 {@code emptyReason} 从"零命中"里区分出"没有库 / 没建索引 / 真的没有"。</li>
 * </ol>
 */
public interface UnifiedSearchService {

    /**
     * 检索。
     *
     * @param userId  归属用户
     * @param request 检索条件（{@code kbIds} 空 = 全部可检索库）
     * @return 命中 + 空原因 + 耗时 + 降级库；<b>单个库失败不会抛异常</b>
     */
    UnifiedSearchResponse search(Long userId, SearchRequest request);

    /**
     * 检索过程剖析：两路原始名次 + 融合分 + 可选重排分。只读，不落库。
     *
     * @param userId  归属用户
     * @param request 与 {@link #search} 同一套入参
     */
    SearchExplainResponse explain(Long userId, SearchRequest request);
}
