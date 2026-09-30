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
        List<Long> kbIds) {
}
