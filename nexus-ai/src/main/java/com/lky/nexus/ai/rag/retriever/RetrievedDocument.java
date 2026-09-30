package com.lky.nexus.ai.rag.retriever;

import java.util.List;

/**
 * 检索结果：一条"能被直接用来回答问题的资料"。
 *
 * <p>与"召回命中"（只有 chunkId + 分数）的区别是这里已经<b>补齐了可展示、可引用、
 * 可追溯的信息</b>：文档标题、文件名、标题路径、页码、正文。RAG 的引用要能点回原文，
 * 工具调用要把"资料出自哪一节"告诉模型，评测要能核对引用是否命中——
 * 这些都需要在检索出口处就把信息带全，而不是让每个调用方各自回表拼装。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code knowledgeBaseId}：多库检索时用于区分结果来自哪个库，
 *       也是 Workspace 隔离的出口断言依据（结果不得来自查询范围之外的库）；</li>
 *   <li>{@code page}：PDF 等版式文档的页码。当前切片还没有落页码，
 *       因此这个字段暂时为 null——它是有意预留的（引用要能定位到第几页），
 *       会在补齐切片元数据时填上，而不是等要用了再加字段；</li>
 *   <li>{@code score}：相关性得分。单路时是该路原始分数（BM25 分 / 余弦相似度），
 *       融合时是 RRF 分数，重排后是重排分数——<b>不同策略之间的分数不可比</b>，
 *       这一点必须由 {@code source} 一起看；</li>
 *   <li>{@code matchedBy}：命中来源明细（如 {@code ["BM25 #2", "向量 #1"]}）。
 *       它是"为什么这条排前面"的答案，也是调检索参数时唯一的抓手。</li>
 * </ul>
 */
public record RetrievedDocument(
        Long chunkId,
        Long documentId,
        Long knowledgeBaseId,
        String documentTitle,
        String fileName,
        String headingPath,
        Integer page,
        String content,
        double score,
        /** 产出它的策略标识：{@code bm25} / {@code vector} / {@code hybrid}。 */
        String source,
        List<String> matchedBy) {

    /** 归一化：命中说明一律给空列表而不是 null，调用方不必判空。 */
    public RetrievedDocument {
        matchedBy = matchedBy == null ? List.of() : List.copyOf(matchedBy);
    }

    /** 追加一条命中说明（重排等于在原有说明后再标一道"重排 #n"）。 */
    public RetrievedDocument withMatchedBy(List<String> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        java.util.ArrayList<String> merged = new java.util.ArrayList<>(matchedBy);
        merged.addAll(extra);
        return new RetrievedDocument(chunkId, documentId, knowledgeBaseId, documentTitle, fileName,
                headingPath, page, content, score, source, merged);
    }

    /** 换一个分数（重排后分数语义变了，需要如实替换而不是保留旧值）。 */
    public RetrievedDocument withScore(double newScore) {
        return new RetrievedDocument(chunkId, documentId, knowledgeBaseId, documentTitle, fileName,
                headingPath, page, content, newScore, source, matchedBy);
    }
}
