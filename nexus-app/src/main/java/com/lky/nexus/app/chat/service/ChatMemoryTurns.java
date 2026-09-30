package com.lky.nexus.app.chat.service;

import com.lky.nexus.knowledge.rag.RagAnswerService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆 ↔ RAG 历史轮次（Phase 3.4）。
 *
 * <p><b>为什么 RAG 模式不挂 {@code MessageChatMemoryAdvisor}</b>（这是本类存在的理由）：
 * 那个 advisor 会把"这一次请求发给模型的用户消息"原样存进记忆，
 * 而 RAG 的用户消息是组装后的提示词（{@code 【资料】…【问题】…}，实测可达 2000 token）。
 * 后果有两个，都很实在：
 * <ol>
 *   <li><b>历史被资料污染</b>：下一轮请求会带上上一轮的整块资料（窗口 20 条消息 ≈ 上万 token），
 *       模型看到的是"过期且可能与本轮冲突"的引用；</li>
 *   <li><b>检索侧拿不到历史</b>：消解指代（"那它有什么缺点？"）发生在<b>检索之前</b>，
 *       advisor 注入的历史对 {@code buildRetrievalQuery} 不可见——用错的查询串去检索，
 *       拿回来的资料自然也是错的。</li>
 * </ol>
 * 因此 RAG 模式改成显式两步：检索前用本类 {@link #read} 取历史，生成结束后用 {@link #remember}
 * 写回"干净的问题 + 回答"。记忆里存的东西与用户实际看到的一致，
 * 这也符合 ADR-011（ChatMemory 决定"这次发给模型多少历史"，chat_message 是完整留档）。
 */
public final class ChatMemoryTurns {

    private ChatMemoryTurns() {
    }

    /**
     * 取出最近的问答对（按时间正序 → {@code RagTurn}，最多 {@code RagTurn.MAX_TURNS} 轮）。
     *
     * <p>按 USER/ASSISTANT 配对而不是"取最后 N 条消息"：只有成对的历史才能回答
     * "这个代指的是哪一轮问的东西"。落单的 USER（上一轮失败了）不构成一轮历史，丢弃。
     */
    public static List<RagAnswerService.RagTurn> read(ChatMemory memory, String conversationId) {
        List<Message> messages = memory.get(conversationId);
        List<RagAnswerService.RagTurn> turns = new ArrayList<>();
        String pendingQuestion = null;
        for (Message message : messages) {
            if (message.getMessageType() == MessageType.USER) {
                pendingQuestion = message.getText();
            } else if (message.getMessageType() == MessageType.ASSISTANT && pendingQuestion != null) {
                turns.add(RagAnswerService.RagTurn.of(pendingQuestion, message.getText()));
                pendingQuestion = null;
            }
        }
        return RagAnswerService.RagTurn.lastTurns(turns);
    }

    /**
     * 把这一轮写回会话记忆。
     *
     * <p>存"原始问题 + 生成的回答"，**不存组装后的提示词**——记忆是给下一轮模型看的上下文，
     * 资料块属于"这一轮的临时输入"，重放它只会让后续每一轮都拖着过期的引用。
     *
     * @param answer 已生成的内容；为空（模型没吐任何字）时不写，
     *               否则会在记忆里留下一个"答：空"的痕迹，反而干扰下一轮的指代理解
     */
    public static void remember(ChatMemory memory, String conversationId, String question, String answer) {
        if (!StringUtils.hasText(question) || !StringUtils.hasText(answer)) {
            return;
        }
        memory.add(conversationId, List.of(new UserMessage(question), new AssistantMessage(answer)));
    }
}
