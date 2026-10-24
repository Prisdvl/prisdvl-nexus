package com.lky.nexus.app.observability.model.dto;

import java.util.List;

/**
 * 请求详情里的检索摘要（从 {@code chat_message.metadata.retrieval} 解析）。
 *
 * <p><b>为什么不新增埋点</b>：检索结果在生成回答时已经落进消息的 metadata
 * （{@code toolCalls} / {@code citations} / {@code retrieval} 三个字段，见
 * {@code ChatMessageServiceImpl}）。观测页只是把已有事实读出来——再撒一次点，
 * 只会多出一份可能对不上的副本。
 *
 * <p>代价是<b>覆盖范围</b>：只有真的产生了 assistant 消息的请求才有摘要；
 * 纯 Agent 运行、纯重排、失败的请求这里会是 {@code found = false}。
 * 这是诚实的边界，页面上会显示"暂无检索记录"，而不是编一个空摘要。
 *
 * @param found           是否在消息 metadata 里找到检索记录
 * @param knowledgeBaseIds 参与检索的知识库
 * @param count           命中条数（检索返回的候选数）
 * @param contextTokens   拼进上下文的 token 数
 * @param truncated       上下文是否被截断
 * @param reranked        是否经过重排
 * @param empty           是否检索为空
 * @param hits            命中片段（最多 10 条，避免响应体过大）
 */
public record RetrievalSummaryResponse(
        boolean found,
        List<Long> knowledgeBaseIds,
        Integer count,
        Integer contextTokens,
        Boolean truncated,
        Boolean reranked,
        Boolean empty,
        List<RetrievalHitResponse> hits) {
}
