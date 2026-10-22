package com.lky.nexus.knowledge.model.dto;

import com.lky.nexus.knowledge.service.ChunkSearchService;

import java.util.List;

/**
 * 检索结果：命中的切片 + 「为什么一条都没命中」。
 *
 * <p><b>为什么不再直接返回数组</b>：{@code []} 把三种互斥的事实压成同一个响应——
 * 库是空的、库里有切片但没建索引、索引正常但没有相关内容。前两种换关键词都不会有结果，
 * 第三种值得再试一次；调用方（界面、Agent 工具）没有理由的信息就只能笼统地说
 * 「没有匹配到任何资料」，而这正是用户误判「检索坏了」的来源。
 *
 * @param hits        命中的切片，按相关度降序
 * @param emptyReason 命中为空时的原因 code：{@code NO_INDEX} / {@code NO_RESULTS}；
 *                    有命中时为 {@code null}（没有问题就没有答案）
 */
public record SearchResponse(List<ChunkSearchService.SearchHit> hits, String emptyReason) {

    public SearchResponse {
        hits = hits == null ? List.of() : List.copyOf(hits);
        // 有命中就不该再带原因：两个字段同时有值，调用方总有一个要猜哪个是权威
        emptyReason = hits.isEmpty() ? emptyReason : null;
    }

    public static SearchResponse of(List<ChunkSearchService.SearchHit> hits, String emptyReason) {
        return new SearchResponse(hits, emptyReason);
    }
}
