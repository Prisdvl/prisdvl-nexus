package com.lky.nexus.knowledge.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 跨库统一检索请求（{@code POST /api/search} 与 {@code POST /api/search/explain} 共用）。
 *
 * <p><b>为什么 {@code kbIds} 允许为空</b>：界面上"在全部知识库里搜一次"是最高频的动作，
 * 让前端先把所有库 id 拉一遍再传回来，等于把"用户有哪些库"这件事复制到客户端。
 * 空表达"我的全部可检索库"，由服务端自己解析——顺带天然挡掉了越权 id。
 *
 * <p><b>为什么 {@code topK} 上限 100</b>：与 {@code RetrievalQuery.MAX_TOP_K} 同一口径。
 * 再往上加不会更相关，只会让响应体和模型上下文一起膨胀。
 */
public record SearchRequest(

        @NotBlank(message = "检索词不能为空")
        @Size(max = 512, message = "检索词长度不能超过 512")
        String q,

        /**
         * 检索范围。空（或 {@code null}）= 当前用户的全部可检索知识库。
         *
         * <p>上限 20 个库：一次请求会按库扇出并行检索，库越多越容易把下游打满；
         * 真要更大范围，正确做法是收敛成"全部库"而不是逐个列举。
         */
        @Size(max = 20, message = "一次最多跨 20 个知识库检索")
        List<Long> kbIds,

        /** {@code VECTOR} / {@code BM25} / {@code HYBRID}，大小写不敏感；缺省 HYBRID。 */
        String mode,

        @Min(value = 1, message = "topK 不能小于 1")
        @Max(value = 100, message = "topK 不能大于 100")
        Integer topK,

        /** 是否对归并后的前几条做重排；缺省 false（重排会更准，但多一次模型调用）。 */
        Boolean rerank,

        /** 元数据过滤条件；整块可省略。 */
        SearchFilters filters) {

    /**
     * 元数据过滤。
     *
     * <p>刻意只支持 <b>单个</b> {@code documentId}：raw 检索的 {@code documentIds} 是列表，
     * 但对外契约里"按文档过滤"就是"只看这一篇"，多个文档的需求用 {@code tags} / {@code folder} 表达。
     */
    public record SearchFilters(
            List<String> tags,
            String folder,
            Long documentId) {
    }
}
