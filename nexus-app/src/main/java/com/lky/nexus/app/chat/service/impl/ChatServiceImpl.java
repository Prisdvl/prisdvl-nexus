package com.lky.nexus.app.chat.service.impl;

import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.error.FailureClassifier;
import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.app.agent.AgentContext;
import com.lky.nexus.app.agent.AgentRuntime;
import com.lky.nexus.app.agent.AgentService;
import com.lky.nexus.app.chat.model.ChatMode;
import com.lky.nexus.app.chat.model.dto.AssistantReplyDraft;
import com.lky.nexus.app.chat.model.dto.ChatAnswerResponse;
import com.lky.nexus.app.chat.model.dto.MessageToolCall;
import com.lky.nexus.app.chat.model.entity.ChatConversationPO;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
import com.lky.nexus.app.chat.model.MessageStatus;
import com.lky.nexus.app.chat.service.ChatMemoryKeys;
import com.lky.nexus.app.chat.service.ChatMessageService;
import com.lky.nexus.app.chat.service.ChatService;
import com.lky.nexus.app.chat.service.ConversationAgentResolver;
import com.lky.nexus.app.chat.service.ConversationService;
import com.lky.nexus.app.infra.web.RequestIdHolder;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import com.lky.nexus.common.security.UserContextKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话服务实现（非流式）。
 *
 * <p>一轮问答的完整动作序列：
 * <pre>
 * 1. 校验会话归属（别人的会话 → 8001）
 * 2. 按会话模式决定这次怎么答（见下）
 * 3. 用户消息落库（完整历史）
 * 4. 调用模型：本次调用挂上 MessageChatMemoryAdvisor，
 *    它会 ① 按 conversationId 取窗口历史拼进请求 ② 把本轮问答写回记忆
 * 5. 助手回复落库（含 Token / 延迟 / 出处）
 * </pre>
 *
 * <p><b>第 2 步是 5.4 补上的</b>。在此之前本方法完全不看 {@code conversation.mode}，
 * 只读 model_role 就调模型，于是同一个会话走不同入口会得到两种答案，而且<b>都不报错</b>：
 * <ul>
 *   <li>AGENT 模式的会话调这里 —— 回答不用该 Agent 的提示词与工具，
 *       用户选的是"严谨的笔记助手"、得到的是裸模型；</li>
 *   <li>RAG 模式的会话调这里 —— 回答里没有任何笔记内容，看起来却完全正常。
 *       这是最危险的一种：用户以为问的是自己的笔记。</li>
 * </ul>
 * 现在 AGENT 走 {@link AgentRuntime#configure}（与流式链路同一份装配），
 * RAG <b>显式报错</b>并指向 {@code /api/chat/stream}——宁可让用户换入口，
 * 也不给他一个"看起来正常、其实没查过笔记"的答案。
 *
 * <p><b>为什么 RAG 是报错而不是顺手补上检索</b>：那需要把
 * {@code ChatStreamServiceImpl} 里"检索 → 无结果短路 → 提示词组装"那一整套在非流式下
 * 再实现一遍（流式那套是围绕 SSE 事件序列长出来的）。而 RAG 会话绑多个库时"用哪个库"
 * （P1-6）本身还没定，现在接上来等于把两个未决问题焊在一起。等 5.5 的多库 RAG 定了再一起做。
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
    private final AgentService agentService;
    private final AgentRuntime agentRuntime;
    private final ObservabilityRegistry observabilityRegistry;

    public ChatServiceImpl(ConversationService conversationService,
                           ChatMessageService chatMessageService,
                           ChatClientFactory chatClientFactory,
                           ChatMemory chatMemory,
                           AgentService agentService,
                           AgentRuntime agentRuntime,
                           ObservabilityRegistry observabilityRegistry) {
        this.conversationService = conversationService;
        this.chatMessageService = chatMessageService;
        this.chatClientFactory = chatClientFactory;
        this.chatMemory = chatMemory;
        this.agentService = agentService;
        this.agentRuntime = agentRuntime;
        this.observabilityRegistry = observabilityRegistry;
    }

    @Override
    public ChatAnswerResponse ask(Long userId, Long conversationId, String content) {
        ChatConversationPO conversation = conversationService.requireOwned(userId, conversationId);
        ChatMode mode = ChatMode.parse(conversation.getMode());
        // 请求次数与流式链路同一口径：过了归属校验才计（见 ChatStreamServiceImpl.stream）
        observabilityRegistry.increment(ObservabilityRegistry.CHAT_REQUESTS);
        if (mode == ChatMode.AGENT) {
            observabilityRegistry.increment(ObservabilityRegistry.AGENT_EXECUTIONS);
        }
        // RAG 在**任何副作用之前**就报错：不能先落一条用户消息再拒绝，
        // 否则用户看到的是"消息多了一条、却没有回答"，还得自己猜为什么
        requireModeServableHere(mode);

        // Agent 解析与模型解析都挪到落库之前（与流式链路同一种排序）：
        // 会话选了 DRAFT/DISABLED 的 Agent（5008/5009）、或角色没配模型（4006），
        // 都属于"这次调用根本不成立"，必须在产生任何数据之前失败
        AgentDefinition agentDefinition =
                ConversationAgentResolver.resolve(agentService, userId, conversation, mode);

        String role = agentDefinition != null
                ? agentDefinition.modelRole()
                : StringUtils.hasText(conversation.getModelRole())
                        ? conversation.getModelRole()
                        : AiModelProperties.Role.CHAT_DEFAULT;
        ChatClient chatClient = chatClientFactory.get(role);

        String requestId = RequestIdHolder.current();
        ChatMessagePO userMessage = chatMessageService.appendUserMessage(
                userId, conversationId, content, requestId);

        // 出处（5.4）：解析后的具体 id（含内置默认那一行），非 AGENT 模式为 null
        Long agentId = agentDefinition == null ? null : agentDefinition.agentId();
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
        String memoryKey = ChatMemoryKeys.of(conversationId);
        // 工具痕迹由监听器收进本次调用的私有列表（不共享状态，天然并发安全），
        // 与 AgentRuntime.ask 用的是同一个 collector
        List<AgentContext.ToolCallRecord> trace = new ArrayList<>();

        long startNanos = System.nanoTime();
        ChatResponse response;
        try {
            ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                    .user(content)
                    // 一次 advisors(...) 同时完成三件事：挂载记忆顾问 + 会话 key + token 归属
                    .advisors(spec -> spec.advisors(memoryAdvisor)
                            .param(ChatMemory.CONVERSATION_ID, memoryKey)
                            // token 记账归属（5.6）：UsageMeteringAdvisor 靠它把用量记到具体用户头上。
                            // 少这一行不会报错，只会把这次对话的消耗记成 quota.usage.unattributed
                            .param(UserContextKeys.USER_ID, userId));
            if (agentDefinition != null) {
                // 提示词 + 工具 + ToolContext 的组装交给 AgentRuntime.configure：流式链路
                // 走的是同一个方法。两处各写一份的话，将来给 Agent 加第二个工具时只改一处，
                // 症状会是"流式能用、非流式不生效"——不报错、只是不生效
                request = agentRuntime.configure(request, userId, agentDefinition,
                        AgentContext.collector(trace));
            }
            response = request.call().chatResponse();
        } catch (BusinessException ex) {
            // 配置类错误（如 4006 未配置模型、5008/5009 Agent 不可用）原样抛出，不做包装
            throw ex;
        } catch (Exception ex) {
            // 分类只写在一处（FailureClassifier），流式路径用的是同一个判定，
            // 所以"同一次故障"在流式与非流式两个入口给出的是同一个码、同一句话。
            //
            // 改之前这里直接把 ex.getMessage() 拼进响应，也存进 error_msg：上游 SDK 的原话
            // （"401: Invalid API key."、网关地址、内部报错）就这样露给了用户，
            // 而且它还会被 messages 接口读回来再露一次。原始异常仍打在日志里，
            // 靠 requestId 关联，排查不依赖返回给用户的那句话。
            FailureClassifier.Failure failure = FailureClassifier.classify(ex);
            int latencyMs = elapsedMillis(startNanos);
            log.error("模型调用失败 conversationId={}, requestId={}, code={}",
                    conversationId, requestId, failure.code().getCode(), ex);
            // 失败也记出处：用户问"这条回答是哪个 Agent 跑的"时，失败的那条同样需要一个答案
            chatMessageService.appendAssistantMessage(userId, conversationId, agentId,
                    AssistantReplyDraft.builder()
                            .errorMsg(truncate(failure.message()))
                            .model(role)
                            .requestId(requestId)
                            .latency(latencyMs, null)
                            .status(MessageStatus.FAILED.name())
                            .build());
            throw new BusinessException(failure.code(), failure.message());
        }
        int latencyMs = elapsedMillis(startNanos);

        String answer = response.getResult().getOutput().getText();
        Usage usage = response.getMetadata().getUsage();
        String model = response.getMetadata().getModel();

        ChatMessagePO assistantMessage = chatMessageService.appendAssistantMessage(
                userId, conversationId, agentId, AssistantReplyDraft.builder()
                        .content(answer)
                        .model(model)
                        .provider(role)
                        .tokens(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens())
                        .latency(latencyMs, null)
                        .finishReason(finishReason(response))
                        .requestId(requestId)
                        .status(MessageStatus.DONE.name())
                        // 工具痕迹随消息落库（与流式链路一致）：刷新后"当时检索了什么"仍然可查
                        .toolCalls(trace.isEmpty() ? null : trace.stream()
                                .map(record -> new MessageToolCall(
                                        record.tool(), record.input(), record.resultSummary()))
                                .toList())
                        .build());

        // 端到端耗时（5.9 六）：与流式链路同一个指标名，两个入口的可比性才有意义。
        // 这里只记成功这一条路（失败会在上面 catch 里抛出），而流式那边四条终止路径都记——
        // 差别是刻意的：非流式的失败没有"半截回答"这回事，它压根没产生对话
        observabilityRegistry.recordDuration(ObservabilityRegistry.CHAT_LATENCY, latencyMs);
        log.info("问答完成 conversationId={}, model={}, prompt={}, completion={}, latency={}ms{}",
                conversationId, model, usage.getPromptTokens(), usage.getCompletionTokens(), latencyMs,
                agentDefinition == null ? "" : ", agent=" + agentDefinition.describe());

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

    /**
     * 这个入口能不能服务这种会话模式。
     *
     * <p>只有 RAG 会走到抛异常那一支：AGENT 与 PLAIN 都能在这里答（分别见
     * {@link #ask} 的类注释与实现）。写法上刻意用"列出能服务的模式"而不是"列出不能的"——
     * 将来加了新模式（比如工作流模式），默认落到报错上，而不是默默按 PLAIN 跑出个不对的答案。
     */
    private void requireModeServableHere(ChatMode mode) {
        if (mode == ChatMode.AGENT || mode == ChatMode.PLAIN) {
            return;
        }
        throw new BusinessException(ErrorCode.CONVERSATION_MODE_UNSUPPORTED,
                "该会话是 %s 模式，非流式接口无法完成检索与引用。请改用流式接口 POST /api/chat/stream"
                        .formatted(mode.name()));
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
