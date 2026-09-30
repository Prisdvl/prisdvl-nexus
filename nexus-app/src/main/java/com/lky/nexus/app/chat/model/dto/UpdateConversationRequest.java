package com.lky.nexus.app.chat.model.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 修改会话请求（局部更新：为 null 的字段不修改）。
 *
 * <p>刻意<b>不允许</b>在这里改 mode：模式切换会影响上下文语义（纯对话的会话突然开检索，
 * 历史回答的引用关系就不一致了）。要换模式请新建会话。
 *
 * <p>{@code kbIds} 可以改（"换一个知识库再问"是常见操作）：空列表 = 解绑。
 * 注意它和 mode 的区别——知识库是<b>数据来源</b>，换掉只影响后续轮次检索到什么；
 * 而 mode 决定"要不要检索、要不要引用"，改它等于让同一段历史里的回答失去可比性。
 */
public record UpdateConversationRequest(

        @Size(max = 200, message = "标题长度不能超过 200")
        String title,

        Boolean pinned,

        /** 绑定的知识库 id（仅 RAG 模式）；空列表表示解绑，null 表示不改。 */
        List<Long> kbIds,

        /**
         * 换一个 Agent（仅 AGENT 模式）。
         *
         * <p>与 {@code kbIds} 同理，"换 Agent 再问"是常见操作：Agent 是<b>执行者</b>，
         * 换掉只影响后续轮次由谁来答，不改 mode，历史回答的可比性不受影响。
         * null = 不改；要回到内置默认 Agent 请用 {@link #resetAgent()}——
         * "null 既是不改又是解绑"分不清，所以清空必须有独立信号。
         */
        Long agentId,

        /**
         * 回到内置默认 Agent（仅 AGENT 模式）：{@code true} = 清空会话选用的 Agent。
         *
         * <p>为什么需要它：选过 Agent 的会话若没有回退路径，用户唯一的办法是新建会话——
         * 而"这个会话我不想用自定义 Agent 了"是完全正常的操作（Phase 4.2 做界面时才发现
         * 这条死路，是 E2E 逼出来的：界面上要么给这个按钮，要么就得写一句"请新建会话"）。
         *
         * <p>与 {@code agentId} 同时传属于自相矛盾的请求，直接报 1001，不猜用户意图。
         */
        Boolean resetAgent) {
}
