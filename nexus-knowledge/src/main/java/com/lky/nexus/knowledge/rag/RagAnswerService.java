package com.lky.nexus.knowledge.rag;

import java.util.List;

/**
 * RAG 问答服务：检索 → 重排 → 组装 → 生成 → 引用。
 */
public interface RagAnswerService {

    /**
     * 一条资料都没检索到时的固定回答。
     *
     * <p>定义在接口上（而不是各实现/调用方各写一遍），是因为"库是空的"这个状态下
     * 用户可能从三个入口进来：知识库问答同步/流式、会话流式（RAG 模式）。
     * 三处文案不一致时，用户会以为遇到的是不同的问题（"是不是这个入口坏了？"），
     * 而实际原因相同：没有资料，因此模型根本没被调用。
     */
    String EMPTY_ANSWER = "资料中没有相关内容（知识库尚未导入或未建立索引）。";

    /**
     * 有知识库没检索成功、且其余库也没给出任何资料时的回答（Phase 5.5.1）。
     *
     * <p><b>为什么不复用 {@link #EMPTY_ANSWER}</b>：那句话是一个断言——"资料中没有"。
     * 而当某个库本次没搜成时，我们<b>并不知道</b>它里面有没有。用前者会把一次基础设施故障
     * 说成一次查证结果，用户会据此得出"我的笔记里确实没写"这个错误结论，
     * 并且不会想到重试。两者必须分开说。
     *
     * <p>只有多库检索才可能走到这里（单库失败会直接抛异常）。部分库成功、部分失败时
     * 不适用本句：那时有资料，模型照常回答，只是提示词里会被明确告知"检索范围不完整"。
     */
    String RETRIEVAL_FAILED_ANSWER = "有知识库暂时无法检索（可能正在重建索引或依赖服务不可用），"
            + "本次没有拿到可用资料。请稍后重试，或先只绑定单个知识库确认。";

    /**
     * RAG 会话一个知识库都没绑定时的回答。
     *
     * <p><b>为什么不复用 {@link #EMPTY_ANSWER}</b>：那句话在说"你的资料里没有"，
     * 而这次根本<b>没有检索范围</b>可查——把配置问题说成查证结果，用户会去改关键词，
     * 而不是去绑库。空结果是可行动的（换词），没绑库是配置未完成（去绑库）。
     *
     * <p>与 {@link #RETRIEVAL_FAILED_ANSWER} 同理：它也不是结论，只是一条"缺前提"的说明。
     */
    String NO_KB_ANSWER = "这个会话还没有绑定知识库，所以没有可检索的资料。"
            + "请在右上角「会话配置」里选中至少一个知识库，然后再提问。";

    /**
     * 基于知识库回答问题（并留档）。
     *
     * @param topK    召回条数（重排与上下文预算会在其基础上收窄）；
     *                <b>null 表示没指定</b>，这时用知识库自身的 retrieval_top_k
     *                （相似度阈值同理取知识库的 similarity_threshold）
     * @param history 之前几轮问答（可为空）。用于"追问"：让模型知道上一轮问了什么，
     *                否则"那它有什么缺点？"这类省略主语的追问会被当成独立问题。
     */
    RagAnswer ask(Long userId, Long knowledgeBaseId, String question, Integer topK, List<RagTurn> history);

    /** 最近的问答记录（倒序）。 */
    List<RagAnswer> listHistory(Long userId, Long knowledgeBaseId, int limit);

    /**
     * 准备阶段：检索 + 重排 + 上下文组装（同步与流式两条路径共用）。
     *
     * <p>流式回答必须先拿到资料块才能开始推流，因此这一半要能单独调用。
     *
     * @param topK 召回条数；null = 用知识库配置（同 {@link #ask}）
     */
    PreparedAnswer prepare(Long userId, Long knowledgeBaseId, String question, Integer topK,
                           List<RagTurn> history);

    /**
     * 准备阶段（多知识库版，Phase 5.5）。
     *
     * <p>与 {@link #prepare} 的唯一区别是知识库可以有多个：逐库检索 → 跨库归并 → 重排 → 组装。
     * 重排与上下文预算的策略完全一致（都作用在归并后的结果上），因此"多库"只改变资料的<b>来源</b>，
     * 不改变资料的<b>条数预算</b>。
     *
     * <p>{@code topK} 的语义因此与单库不同，详见
     * {@link com.lky.nexus.knowledge.service.ChunkSearchService#searchMany}：
     * 每个库按自己的 {@code retrieval_top_k} 召回，{@code topK} 只决定归并后保留多少条
     * （null = 用全局默认）。多绑几个库不会让上下文变长。
     *
     * <p>任意一个库不存在或不属于该用户，整批失败（3001）——不做部分成功。
     */
    PreparedAnswer prepareMany(Long userId, List<Long> knowledgeBaseIds, String question, Integer topK,
                               List<RagTurn> history);

    /**
     * 把一次问答留档（流式回答在结束后调用）。
     *
     * @param usedHits 资料块里的命中项（用于生成引用编号）
     */
    RagAnswer recordAnswer(Long userId, Long knowledgeBaseId, String question, String answer,
                           List<com.lky.nexus.knowledge.service.ChunkSearchService.SearchHit> usedHits,
                           int contextTokens, int truncated, boolean reranked, long costMillis);

    /** 系统提示（同步与流式共用，保证两条路径的约束完全一致）。 */
    String systemPrompt();

    /** 用户消息（历史 + 资料 + 问题）。 */
    String userMessage(String question, PreparedAnswer prepared, List<RagTurn> history);

    /**
     * 一轮历史问答。
     *
     * @param answerSummary 上一轮回答的摘要（只取开头一段，避免把上下文撑爆）
     */
    record RagTurn(String question, String answerSummary) {

        /** 历史回答保留的字数：历史只用来"理解指代"，不该挤掉本轮的资料。 */
        public static final int SUMMARY_LENGTH = 300;

        /** 最多带几轮历史：再多也帮不上指代，只会把上下文和成本推高。 */
        public static final int MAX_TURNS = 3;

        /**
         * 由"问题 + 完整回答"构造一轮历史；问题为空时返回 {@code null}（调用方据此过滤）。
         *
         * <p>把"摘要截断"收在这里，是因为它现在有<b>两个</b>调用方
         * （知识库问答接口把前端回传的历史转成 RagTurn、会话流式从 ChatMemory 里取历史）。
         * 两处各写一份的话，迟早出现"同一个会话在不同入口拿到了不同长度的历史"。
         */
        public static RagTurn of(String question, String answer) {
            if (question == null || question.isBlank()) {
                return null;
            }
            return new RagTurn(question.trim(), summarize(answer));
        }

        /** 取最后 {@link #MAX_TURNS} 轮（入参按时间正序，{@code null} 元素被丢弃）。 */
        public static List<RagTurn> lastTurns(List<RagTurn> turns) {
            if (turns == null || turns.isEmpty()) {
                return List.of();
            }
            List<RagTurn> valid = turns.stream().filter(java.util.Objects::nonNull).toList();
            return valid.size() <= MAX_TURNS
                    ? valid
                    : List.copyOf(valid.subList(valid.size() - MAX_TURNS, valid.size()));
        }

        private static String summarize(String answer) {
            if (answer == null || answer.isBlank()) {
                return null;
            }
            String flat = answer.replaceAll("\\s+", " ").trim();
            return flat.length() <= SUMMARY_LENGTH ? flat : flat.substring(0, SUMMARY_LENGTH) + "…";
        }
    }
}
