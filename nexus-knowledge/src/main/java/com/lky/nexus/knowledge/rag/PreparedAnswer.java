package com.lky.nexus.knowledge.rag;

import com.lky.nexus.knowledge.rag.retriever.RetrievalFailure;
import com.lky.nexus.knowledge.service.ChunkSearchService;

import java.util.List;

/**
 * 回答的"准备阶段"结果：检索 + 重排 + 上下文组装都已完成，只差调用模型生成。
 *
 * <p>把这一半单独暴露出来，是为了让<b>同步与流式两条路径共用同一套检索逻辑</b>——
 * 否则流式版本很容易变成"另一份略有差异的实现"，而这种差异极难发现
 * （检索参数不同会导致同问题两种答案）。
 *
 * @param contextText 资料块文本（已编号，带防注入分隔）
 * @param usedHits    实际进入资料块的命中项（顺序即引用编号）
 * @param empty       是否没有可用资料（此时不该调用模型）
 * @param failures    本次没能参与检索的知识库（Phase 5.5.1）。单库恒为空；
 *                    多库时才可能非空——那时 {@code contextText} 是<b>不完整</b>的资料，
 *                    这一点必须一路传到提示词与事件里，否则模型会把
 *                    "某个库没搜到"当成"用户笔记里没有"，给出一个看起来有理的假否定
 */
public record PreparedAnswer(
        String contextText,
        List<ChunkSearchService.SearchHit> usedHits,
        int contextTokens,
        int truncated,
        boolean reranked,
        boolean empty,
        List<RetrievalFailure> failures) {

    /** 归一化：null 视作"没有失败"，并且复制一份（调用方改自己的列表不该影响这里）。 */
    public PreparedAnswer {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    /**
     * 兼容构造器（{@code failures = 空}）。
     *
     * <p>保留它是因为绝大多数构造点（单库路径、流式回填、一批测试）手上根本没有失败信息，
     * 让它们统统补一个 {@code List.of()}，只会把"确实没有失败"与"忘了填"写得一模一样。
     * 只有真正拿得到失败的调用方（多库检索那条路）才显式用 7 参构造器。
     */
    public PreparedAnswer(String contextText, List<ChunkSearchService.SearchHit> usedHits, int contextTokens,
                          int truncated, boolean reranked, boolean empty) {
        this(contextText, usedHits, contextTokens, truncated, reranked, empty, List.of());
    }

    /** 有没有知识库这次没搜到。 */
    public boolean hasFailures() {
        return !failures.isEmpty();
    }

    public static PreparedAnswer none() {
        return none(List.of());
    }

    /**
     * 没有可用资料，<b>但要说清是不是因为库没搜到</b>。
     *
     * <p>{@code none()} 与 {@code none(failures)} 必须分开：前者是"库里确实没有"，
     * 后者是"库没搜成，所以不知道有没有"。两者的用户可见文案不同
     * （见 {@code RagAnswerService.RETRIEVAL_FAILED_ANSWER}），
     * 这正是失败隔离不能静默的原因在最后一公里的体现。
     */
    public static PreparedAnswer none(List<RetrievalFailure> failures) {
        return new PreparedAnswer("", List.of(), 0, 0, false, true, failures);
    }
}
