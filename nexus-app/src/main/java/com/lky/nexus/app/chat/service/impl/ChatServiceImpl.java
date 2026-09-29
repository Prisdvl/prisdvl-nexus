package com.lky.nexus.app.chat.service.impl;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.app.chat.model.dto.AssistantReplyDraft;
import com.lky.nexus.app.chat.model.dto.ChatAnswerResponse;
import com.lky.nexus.app.chat.model.entity.ChatConversationPO;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
import com.lky.nexus.app.chat.model.MessageStatus;
import com.lky.nexus.app.chat.service.ChatMemoryKeys;
import com.lky.nexus.app.chat.service.ChatMessageService;
import com.lky.nexus.app.chat.service.ChatService;
import com.lky.nexus.app.chat.service.ConversationService;
import com.lky.nexus.app.infra.web.RequestIdHolder;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 对话服务实现（非流式）。
 *
 * <p>一轮问答的完整动作序列：
 * <pre>
 * 1. 校验会话归属（别人的会话 → 8001）
 * 2. 用户消息落库（完整历史）
 * 3. 调用模型：本次调用挂上 MessageChatMemoryAdvisor，
 *    它会 ① 按 conversationId 取窗口历史拼进请求 ② 把本轮问答写回记忆
 * 4. 助手回复落库（含 Token / 延迟）
 * </pre>
 *
 * <p>记忆顾问是<b>按调用挂载</b>而不是作为 ChatClient 默认顾问：
 * 因为 {@code MessageChatMemoryAdvisor} 要求每次调用都提供 {@code ChatMemory.CONVERSATION_ID}，
 * 否则抛 IllegalArgumentException。评测裁判、字段抽取这类"无会话"的调用不该被迫造一个会话 id。
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final ConversationService conversationService;
    private final ChatMessageService chatMessageService;
    private final ChatClientFactory chatClientFactory;
    private final ChatMemory chatMemory;

    public ChatServiceImpl(ConversationService conversationService,
                           ChatMessageService chatMessageService,
                           ChatClientFactory chatClientFactory,
                           ChatMemory chatMemory) {
        this.conversationService = conversationService;
        this.chatMessageService = chatMessageService;
        this.chatClientFactory = chatClientFactory;
        this.chatMemory = chatMemory;
    }

    @Override
    public ChatAnswerResponse ask(Long userId, Long conversationId, String content) {
        ChatConversationPO conversation = conversationService.requireOwned(userId, conversationId);
        String requestId = RequestIdHolder.current();

        ChatMessagePO userMessage = chatMessageService.appendUserMessage(
                userId, conversationId, content, requestId);

        String role = StringUtils.hasText(conversation.getModelRole())
                ? conversation.getModelRole()
                : AiModelProperties.Role.CHAT_DEFAULT;
        ChatClient chatClient = chatClientFactory.get(role);
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
        String memoryKey = ChatMemoryKeys.of(conversationId);

        long startNanos = System.nanoTime();
        ChatResponse response;
        try {
            response = chatClient.prompt()
                    .user(content)
                    // 一次 advisors(...) 同时完成两件事：挂载记忆顾问 + 传入会话 key
                    .advisors(spec -> spec.advisors(memoryAdvisor)
                            .param(ChatMemory.CONVERSATION_ID, memoryKey))
                    .call()
                    .chatResponse();
        } catch (BusinessException ex) {
            // 配置类错误（如 4006 未配置模型）原样抛出，不做包装
            throw ex;
        } catch (Exception ex) {
            int latencyMs = elapsedMillis(startNanos);
            log.error("模型调用失败 conversationId={}, requestId={}", conversationId, requestId, ex);
            chatMessageService.appendAssistantMessage(userId, conversationId, AssistantReplyDraft.builder()
                    .errorMsg(truncate(ex.getMessage()))
                    .model(role)
                    .requestId(requestId)
                    .latency(latencyMs, null)
                    .status(MessageStatus.FAILED.name())
                    .build());
            throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE, "模型调用失败：" + ex.getMessage());
        }
        int latencyMs = elapsedMillis(startNanos);

        String answer = response.getResult().getOutput().getText();
        Usage usage = response.getMetadata().getUsage();
        String model = response.getMetadata().getModel();

        ChatMessagePO assistantMessage = chatMessageService.appendAssistantMessage(
                userId, conversationId, AssistantReplyDraft.builder()
                        .content(answer)
                        .model(model)
                        .provider(role)
                        .tokens(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens())
                        .latency(latencyMs, null)
                        .finishReason(finishReason(response))
                        .requestId(requestId)
                        .status(MessageStatus.DONE.name())
                        .build());

        log.info("问答完成 conversationId={}, model={}, prompt={}, completion={}, latency={}ms",
                conversationId, model, usage.getPromptTokens(), usage.getCompletionTokens(), latencyMs);

        return new ChatAnswerResponse(
                conversationId,
                userMessage.getId(),
                assistantMessage.getId(),
                answer,
                model,
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens(),
                latencyMs);
    }

    private int elapsedMillis(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000);
    }

    /**
     * 结束原因（stop / length / tool_calls…）。
     *
     * <p>注意它挂在 {@code response.getResult().getMetadata()} 上，
     * <b>不是</b> {@code ChatResponseMetadata}（后者只有 usage / model / id / rateLimit）。
     * 记录它对排查很有用：{@code length} 说明被 maxTokens 截断，
     * {@code tool_calls} 说明 Agent 还要继续跑。
     */
    private String finishReason(ChatResponse response) {
        var generationMetadata = response.getResult().getMetadata();
        return generationMetadata == null ? null : generationMetadata.getFinishReason();
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
