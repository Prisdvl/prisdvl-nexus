package com.lky.nexus.knowledge.rag.retriever;

import com.lky.nexus.ai.rag.retriever.RetrievalQuery;
import com.lky.nexus.ai.rag.retriever.RetrievalStrategy;
import com.lky.nexus.ai.rag.retriever.RetrievedDocument;
import com.lky.nexus.ai.rag.retriever.Retriever;
import com.lky.nexus.knowledge.rag.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 重排检索：在 {@link HybridRetriever} 的结果之上，让模型再判一次"这段资料能不能回答问题"。
 *
 * <p><b>为什么召回与重排是两件事</b>：召回要的是"别漏"，所以宽（多取候选、阈值宽松）；
 * 重排要的是"排序准"，在少量候选上做精细判断。向量相似度衡量的是"两段文本像不像"，
 * 而我们真正需要的是"这段资料能不能回答这个问题"——后者只有读一遍才能判断。
 *
 * <p><b>装饰器形态的意义</b>：重排不是一种新的召回方式，而是在已有结果上加一道工序，
 * 因此它继承被装饰者的全部行为（包括权限校验、跨库归并、回表补齐），只改顺序与分数。
 * 这样"要不要重排"就退化成一个策略选择，而不是散落在 RAG 服务里的一段 if。
 *
 * <p><b>三个必须守住的语义</b>：
 * <ol>
 *   <li><b>只重排前 N 条，其余原样跟在后面</b>：重排是最贵的一步（一次模型调用），
 *       而靠后的候选本来也很难进最终资料块。收窄范围能显著缩短端到端延迟，
 *       同时不因重排丢掉任何召回结果；</li>
 *   <li><b>失败即降级</b>：重排是增强项，不是单点。模型超时 / 返回条数对不上时
 *       保持召回顺序继续，而不是让整个问答失败；</li>
 *   <li><b>降级不静默</b>：降级时打日志。否则"重排其实一直没生效"这种问题
 *       会以"回答质量莫名其妙变差"的形式出现，极难定位。</li>
 * </ol>
 */
@Component
public class RerankedRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(RerankedRetriever.class);

    /**
     * 重排的候选条数上限。
     *
     * <p>8 这个值来自延迟实测：重排的模型调用占端到端延迟的大头，
     * 而重排 8 条与 20 条的排序质量差异远小于它们的延迟差异。
     */
    private static final int RERANK_CANDIDATES = 8;

    private final HybridRetriever delegate;
    private final Reranker reranker;

    public RerankedRetriever(HybridRetriever delegate, Reranker reranker) {
        this.delegate = delegate;
        this.reranker = reranker;
    }

    @Override
    public String name() {
        return "hybrid-rerank";
    }

    @Override
    public Set<RetrievalStrategy> strategies() {
        return Set.of(RetrievalStrategy.HYBRID_RERANK);
    }

    @Override
    public List<RetrievedDocument> retrieve(RetrievalQuery query) {
        List<RetrievedDocument> recalled = delegate.retrieve(query);
        if (recalled.size() < 2) {
            // 0 或 1 条没有"排序"可言，白花一次模型调用
            return recalled;
        }
        int headSize = Math.min(recalled.size(), RERANK_CANDIDATES);
        List<RetrievedDocument> head = recalled.subList(0, headSize);

        List<Double> scores = reranker.score(query.effectiveRerankQuery(), candidates(head));
        if (scores.size() != headSize) {
            log.warn("重排降级：候选 {} 条但只拿到 {} 个分数，保持召回顺序", headSize, scores.size());
            return recalled;
        }

        List<Scored> scored = new ArrayList<>(headSize);
        for (int i = 0; i < headSize; i++) {
            scored.add(new Scored(head.get(i), scores.get(i)));
        }
        // 稳定排序：分数相同时保持召回顺序（Comparator 只比分数，sort 本身是稳定的）
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        List<RetrievedDocument> ordered = new ArrayList<>(recalled.size());
        for (int i = 0; i < scored.size(); i++) {
            RetrievedDocument document = scored.get(i).document();
            // 分数如实替换成重排分：沿用召回分会让界面上的"顺序"与"分数"对不上，
            // 而重排分与 RRF 分本来就是两个量纲，混着显示更糟
            ordered.add(document.withScore(scored.get(i).score())
                    .withMatchedBy(List.of("重排 #" + (i + 1))));
        }
        // 未参与重排的尾部保持原相对顺序
        ordered.addAll(recalled.subList(headSize, recalled.size()));
        log.debug("重排完成：{} 条候选 → 前 {} 条重新排序", recalled.size(), headSize);
        return ordered;
    }

    private List<Reranker.Candidate> candidates(List<RetrievedDocument> documents) {
        return documents.stream()
                .map(document -> new Reranker.Candidate(document.chunkId(), document.headingPath(),
                        document.content()))
                .toList();
    }

    private record Scored(RetrievedDocument document, double score) {
    }
}
