package com.lky.nexus.knowledge.rag;

import java.util.List;

/**
 * 重排器。
 *
 * <p>召回与重排是两件事：召回（BM25 + 向量）要的是"别漏"，所以宽；
 * 重排要的是"排序准"，在少量候选上做精细判断。
 * 向量检索的相似度是"两个文本像不像"，而重排判断的是"这段资料能不能回答问题"——
 * 后者才是我们真正需要的。
 *
 * <p>接口刻意收窄成 {@code (query, candidates) -> scores}：
 * 不传整个大对象、也不返回新的数据结构，实现可以是 LLM，也可以是外部重排 API
 * （bge-reranker 之类），将来换实现不会牵动上游。
 */
public interface Reranker {

    /**
     * 对候选打分（分数越大越相关）。
     *
     * <p><b>实现必须容错</b>：重排是"锦上添花"，不应该成为整条链路的单点故障。
     * 模型不可用 / 返回格式不对时，应返回空列表让调用方保持召回顺序，而不是抛异常。
     *
     * @param query      用户问题
     * @param candidates 候选（顺序即召回排名）
     * @return 与入参同序的分数；无法重排时返回空列表
     */
    List<Double> score(String query, List<Candidate> candidates);

    /**
     * 候选片段。
     *
     * @param chunkId     切片 id
     * @param headingPath 标题路径（给重排模型一点上下文，"这一节讲什么"很有用）
     * @param content     正文
     */
    record Candidate(Long chunkId, String headingPath, String content) {
    }
}
