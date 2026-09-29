package com.lky.nexus.knowledge.rag;

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
 */
public record PreparedAnswer(
        String contextText,
        List<ChunkSearchService.SearchHit> usedHits,
        int contextTokens,
        int truncated,
        boolean reranked,
        boolean empty) {

    public static PreparedAnswer none() {
        return new PreparedAnswer("", List.of(), 0, 0, false, true);
    }
}
