package com.lky.nexus.app.chat.stream;

import com.lky.nexus.knowledge.rag.PreparedAnswer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code retrieval} 事件的负载（Phase 3.4）。
 *
 * <p><b>为什么要有这个事件</b>：检索发生在生成之前，但生成要几十秒。
 * 没有它，用户在第一个字出现之前看到的是一片空白，无从判断"到底在检索、还是在卡住"。
 * 有了它，前端可以在第一个 {@code delta} 之前就显示"检索到 6 个片段"，
 * 并且能列出这 6 段出自哪篇文档的哪一节。
 *
 * <p><b>为什么不复用 {@code meta} 里的 citations 预览</b>：两者信息量不同。
 * {@code meta} 是"流已建立"的最小凭据（请求 id、消息 id、模型角色），
 * 引用预览当初放在那里只是因为还没有别的通道；而这里要回答的是"检索发生了什么"
 * ——命中几条、命中在哪、用的是 chunk 还是文档、融合/重排是否生效。
 * 于是 {@code meta} 保持精简（且不再往里加字段），检索信息统一走这个事件。
 *
 * <p><b>为什么命中项是 chunk 而不是 document</b>：设计稿里写的是 {@code documents}（文档级），
 * 但真正被引用、被编号、被点开的是<b>切片</b>：同一篇文档可能命中 3 个切片，
 * 按文档去重会让"检索到 6 个片段"变成"检索到 2 篇文档"，反而对不上引用编号。
 * 因此事件里的 {@code hits} 是切片级，{@code count == hits.size()}。
 *
 * <p><b>两条链路共用这一个构造器</b>（知识库问答 {@code /ask/stream} 与会话流式 {@code /chat/stream}）：
 * 事件的形状一旦分叉，前端的解析逻辑就得写两份，而"两份里只改了一份"是这类问题的经典来源。
 */
public final class RetrievalEvent {

    private RetrievalEvent() {
    }

    /**
     * 组装 {@code retrieval} 事件负载。
     *
     * @param knowledgeBaseIds 本次检索覆盖的知识库（会话流式下由会话绑定决定；Phase 5.5 起可能是多个）。
     *                         <b>是列表而不是单个 id</b>：多库检索时"检索了哪些库"本身就是要告诉用户的信息，
     *                         而每命中一条片段出自哪个库，由 {@code hits[].knowledgeBaseId} 逐条给出
     * @param prepared         检索/重排/组装后的结果；<b>empty 也要发</b>——
     *                         "检索到 0 个片段"本身就是要告诉用户的信息，
     *                         省略事件会让前端把它当成"还没检索完"一直等下去
     */
    public static Map<String, Object> of(List<Long> knowledgeBaseIds, PreparedAnswer prepared) {
        List<Map<String, Object>> hits = hits(prepared);
        Map<String, Object> payload = new HashMap<>();
        payload.put("knowledgeBaseIds",
                knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds));
        payload.put("count", hits.size());
        payload.put("hits", hits);
        payload.put("contextTokens", prepared.contextTokens());
        payload.put("truncated", prepared.truncated());
        payload.put("reranked", prepared.reranked());
        payload.put("empty", prepared.empty());
        return payload;
    }

    /**
     * 命中切片明细（顺序即引用编号 {@code [1..n]}）。
     *
     * <p>每条带上 {@code knowledgeBaseId}：多库时"这一段出自哪个库"必须逐条能看出来，
     * 否则界面只能显示"检索到 5 个片段"，而用户绑了两个库，无从判断资料到底来自哪一个。
     */
    public static List<Map<String, Object>> hits(PreparedAnswer prepared) {
        return prepared.usedHits().stream().map(hit -> {
            Map<String, Object> item = new HashMap<>();
            item.put("chunkId", hit.chunkId());
            item.put("documentId", hit.documentId());
            item.put("knowledgeBaseId", hit.knowledgeBaseId());
            item.put("documentTitle", hit.documentTitle());
            item.put("headingPath", hit.headingPath());
            item.put("score", hit.score());
            item.put("source", hit.source());
            return item;
        }).toList();
    }

    /** {@code meta} 事件里的引用预览：只有位置信息，不带分数与来源（meta 保持精简）。 */
    public static List<Map<String, Object>> citationPreview(PreparedAnswer prepared) {
        return prepared.usedHits().stream().map(hit -> {
            Map<String, Object> item = new HashMap<>();
            item.put("chunkId", hit.chunkId());
            item.put("documentId", hit.documentId());
            item.put("documentTitle", hit.documentTitle());
            item.put("headingPath", hit.headingPath());
            return item;
        }).toList();
    }
}
