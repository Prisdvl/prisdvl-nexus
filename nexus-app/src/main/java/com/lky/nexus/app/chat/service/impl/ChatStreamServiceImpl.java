package com.lky.nexus.app.chat.service.impl;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.app.agent.AgentContext;
import com.lky.nexus.app.agent.AgentPrompts;
import com.lky.nexus.app.agent.KnowledgeSearchTool;
import com.lky.nexus.app.chat.model.ChatMode;
import com.lky.nexus.app.chat.model.MessageStatus;
import com.lky.nexus.app.chat.model.dto.AssistantReplyDraft;
import com.lky.nexus.app.chat.model.dto.MessageToolCall;
import com.lky.nexus.app.chat.model.entity.ChatConversationPO;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
import com.lky.nexus.app.chat.service.ChatMemoryKeys;
import com.lky.nexus.app.chat.service.ChatMemoryTurns;
import com.lky.nexus.app.chat.service.ChatMessageService;
import com.lky.nexus.app.chat.service.ChatStreamService;
import com.lky.nexus.app.chat.service.ConversationKbIds;
import com.lky.nexus.app.chat.service.ConversationService;
import com.lky.nexus.app.chat.stream.RetrievalEvent;
import com.lky.nexus.app.chat.stream.SseEventNames;
import com.lky.nexus.app.chat.stream.StreamCancellationRegistry;
import com.lky.nexus.app.infra.web.RequestIdHolder;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.rag.PreparedAnswer;
import com.lky.nexus.knowledge.rag.RagAnswerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式对话实现（Spring MVC {@link SseEmitter} + Reactor Flux 桥接）。
 *
 * <p>关键设计点：
 * <ol>
 *   <li><b>先同步落库、再异步推送</b>：会话校验与"用户消息 + STREAMING 助手消息"落库都在
 *       返回 emitter 之前完成，因此 {@code meta} 事件里的消息 id 一定可用；</li>
 *   <li><b>Flux 在 boundedElastic 上订阅</b>：底层 HTTP 客户端的分片读取可能阻塞，
 *       不能在 MVC 请求线程上订阅，否则会把连接占住；</li>
 *   <li><b>停止要真的断开上游</b>：只关 SSE 连接不停订阅的话，Token 仍在消耗；
 *       因此取消时 {@code dispose()} 掉订阅；</li>
 *   <li><b>终止动作只执行一次</b>：用 {@link AtomicBoolean} 保证
 *       "正常结束 / 用户停止 / 异常 / 客户端断开"四条路径里只有一条会落库并关闭 emitter。</li>
 * </ol>
 *
 * <p>三种模式共用这一条流式链路，差别只在"提示词怎么来"：
 * <ul>
 *   <li>{@code PLAIN} —— 用户原话 + 会话记忆 advisor；</li>
 *   <li>{@code RAG} —— 先按会话绑定的知识库检索（{@link RagAnswerService#prepare}），
 *       发 {@code retrieval} 事件，再走与 {@code /ask/stream} <b>同一份</b>提示词；
 *       历史从 ChatMemory 取（见 {@link ChatMemoryTurns}，那里解释了为什么不挂 advisor）；</li>
 *   <li>{@code AGENT} —— 系统提示 + 知识库检索工具，由模型决定检索什么。</li>
 * </ul>
 */
@Service
public class ChatStreamServiceImpl implements ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamServiceImpl.class);

    /** SSE 超时：与 Nginx 的 proxy_read_timeout 需要匹配（Phase 11 会统一配置）。 */
    private static final long SSE_TIMEOUT_MILLIS = 5 * 60 * 1000L;

    private final ConversationService conversationService;
    private final ChatMessageService chatMessageService;
    private final ChatClientFactory chatClientFactory;
    private final ChatMemory chatMemory;
    private final StreamCancellationRegistry cancellationRegistry;
    private final KnowledgeSearchTool knowledgeSearchTool;
    private final RagAnswerService ragAnswerService;
    private final JsonMapper jsonMapper;

    public ChatStreamServiceImpl(ConversationService conversationService,
                                 ChatMessageService chatMessageService,
                                 ChatClientFactory chatClientFactory,
                                 ChatMemory chatMemory,
                                 StreamCancellationRegistry cancellationRegistry,
                                 KnowledgeSearchTool knowledgeSearchTool,
                                 RagAnswerService ragAnswerService,
                                 JsonMapper jsonMapper) {
        this.conversationService = conversationService;
        this.chatMessageService = chatMessageService;
        this.chatClientFactory = chatClientFactory;
        this.chatMemory = chatMemory;
        this.cancellationRegistry = cancellationRegistry;
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.ragAnswerService = ragAnswerService;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public SseEmitter stream(Long userId, Long conversationId, String content) {
        // 归属校验放在最前面：异常会走全局异常处理器返回统一 JSON，而不是开一个 SSE 再报错
        ChatConversationPO conversation = conversationService.requireOwned(userId, conversationId);
        // 模式决定这条链路走法：PLAIN 纯生成、RAG 先检索再回答、AGENT 由模型自己决定检索什么
        ChatMode mode = ChatMode.parse(conversation.getMode());
        String modelRole = StringUtils.hasText(conversation.getModelRole())
                ? conversation.getModelRole()
                : defaultRole(mode);
        // 取模型放在落库之前：模型未注册（4006）属于配置问题，必须在<b>产生副作用之前</b>失败。
        // 否则会留下一条用户消息 + 一条永远停在 STREAMING 的空助手消息，用户看到的是"消息凭空多了一条"。
        ChatClient chatClient = chatClientFactory.get(modelRole);

        // RAG 模式的检索同样放在落库之前（与 /ask/stream 一致，见 RagStreamServiceImpl）：
        // ① 知识库未绑定/不属于自己、索引不可用，都会以统一 JSON 报错，而不是"先开一条 SSE 再在半路报错"；
        // ② 检索发生在生成之前，用户等待的就是"检索 + 生成"两段，事件顺序也才好保证。
        boolean ragMode = mode == ChatMode.RAG;
        Long knowledgeBaseId = ragMode ? requireKnowledgeBaseId(conversation) : null;
        // 历史从 ChatMemory 取（不是 chat_message 全量）：它同时服务两个地方——
        // 消解指代的检索查询串、以及给模型的"之前的问答"。详见 ChatMemoryTurns 的类注释。
        List<RagAnswerService.RagTurn> history = ragMode
                ? ChatMemoryTurns.read(chatMemory, ChatMemoryKeys.of(conversationId))
                : List.of();
        PreparedAnswer prepared = ragMode
                ? ragAnswerService.prepare(userId, knowledgeBaseId, content, null, history)
                : null;

        String requestId = resolveRequestId();

        ChatMessagePO userMessage = chatMessageService.appendUserMessage(userId, conversationId, content, requestId);
        // 先落一条 STREAMING：即使进程中途崩溃，用户也能看到"有一条没生成完"，
        // 而不是消息凭空消失（等生成完再落库的话，崩溃就全丢了）
        ChatMessagePO assistantMessage = chatMessageService.appendAssistantMessage(userId, conversationId,
                AssistantReplyDraft.builder()
                        .requestId(requestId)
                        .status(MessageStatus.STREAMING.name())
                        .build());

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        cancellationRegistry.register(requestId, userId);
        // 客户端主动断开 / 超时 / 出错时都要清理登记，否则内存里会留下悬挂条目
        emitter.onCompletion(() -> cancellationRegistry.clear(requestId));
        emitter.onTimeout(() -> cancellationRegistry.clear(requestId));
        emitter.onError(throwable -> cancellationRegistry.clear(requestId));

        StreamSession session = new StreamSession(userId, conversationId, assistantMessage.getId(),
                requestId, modelRole, mode, ragMode, content, emitter);
        session.sendMeta(userMessage.getId(), assistantMessage.getId());
        if (ragMode) {
            // 检索事件必须在第一个 delta 之前：前端据此显示"检索到 N 个片段"。
            // 这里是同步发送（flux 还没订阅），顺序由代码结构保证，不靠时序运气。
            session.sendRetrieval(knowledgeBaseId, prepared);
        }

        // 没有资料就不调用模型（与 /ask/stream 完全一致：省钱，也从根上避免"无资料硬答"）。
        // 仍走完整的流式事件序列，前端只有一套处理逻辑。
        if (ragMode && prepared.empty()) {
            session.finishWithoutModel(RagAnswerService.EMPTY_ANSWER);
            return emitter;
        }

        Flux<ChatResponse> modelFlux = buildFlux(userId, chatClient, session, conversationId, content,
                ragMode, prepared, history);
        // subscribeOn：分片读取可能阻塞，必须离开 MVC 请求线程
        Disposable subscription = modelFlux.subscribeOn(Schedulers.boundedElastic())
                .subscribe(session::onChunk, session::onError, session::onComplete);
        session.attach(subscription);

        log.info("开始流式生成 requestId={}, conversationId={}, mode={}, modelRole={}{}",
                requestId, conversationId, mode, modelRole,
                ragMode ? ", kbId=" + knowledgeBaseId + ", 检索到 " + prepared.usedHits().size() + " 个片段" : "");
        return emitter;
    }

    /**
     * 构建待订阅的 Flux。
     *
     * <p>用 {@code Flux.defer} 把整条 prompt 的构建推迟到"订阅那一刻"，
     * 这样工具循环（可能包含多次模型往返）落在 boundedElastic 的工作线程上，而不是 MVC 请求线程。
     */
    private Flux<ChatResponse> buildFlux(Long userId, ChatClient chatClient, StreamSession session,
                                         Long conversationId, String content, boolean ragMode,
                                         PreparedAnswer prepared, List<RagAnswerService.RagTurn> history) {
        boolean agentMode = session.mode == ChatMode.AGENT;
        // RAG 模式不挂记忆 advisor（它会把组装后的【资料】块原样存进记忆，见 ChatMemoryTurns）；
        // PLAIN / AGENT 保持原样——它们的用户消息就是用户原话，交给 advisor 既省事又正确。
        MessageChatMemoryAdvisor memoryAdvisor = ragMode ? null : MessageChatMemoryAdvisor.builder(chatMemory).build();
        return Flux.defer(() -> {
            ChatClient.ChatClientRequestSpec request = chatClient.prompt();
            if (ragMode) {
                // 与 /ask/stream 共用同一份系统提示与用户消息构造：两条链路绝不能各写一份，
                // 否则同一个人问同一个问题会得到两种答案（资料编号规则、无依据时的措辞都会漂移）
                request = request.system(ragAnswerService.systemPrompt())
                        .user(ragAnswerService.userMessage(content, prepared, history));
            } else {
                request = request.user(content)
                        .advisors(a -> a.advisors(memoryAdvisor)
                                .param(ChatMemory.CONVERSATION_ID, ChatMemoryKeys.of(conversationId)));
            }
            if (agentMode) {
                // toolContext 是工具拿"当前用户"的唯一通道（详见 AgentContext 的类注释：
                // 流式链路上工具的执行线程与订阅线程不是同一个，ThreadLocal 会静默丢用户）。
                // 把 session 一起塞进去，工具执行时就能实时把"检索中 / 检索完成"推给前端。
                request = request.system(AgentPrompts.AGENT_SYSTEM)
                        .toolContext(AgentContext.of(userId, session))
                        .tools(ToolCallbacks.from(knowledgeSearchTool));
            }
            return request.stream().chatResponse();
        });
    }

    /**
     * 取会话绑定的知识库（RAG 模式）。
     *
     * <p>这条路径上<b>没有</b>"悄悄退化成不检索"这个选项：RAG 会话拿不到知识库时，
     * 用户真正想要的是"告诉我哪里没配好"，而不是一个看起来正常、其实没查过笔记的回答。
     */
    private Long requireKnowledgeBaseId(ChatConversationPO conversation) {
        List<Long> kbIds = ConversationKbIds.parse(jsonMapper, conversation.getKbIds());
        if (kbIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "该会话（RAG 模式）尚未绑定知识库，请先绑定后再提问");
        }
        return kbIds.get(0);
    }

    @Override
    public boolean stop(String requestId, Long userId) {
        return cancellationRegistry.cancel(requestId, userId);
    }

    private String resolveRequestId() {
        String requestId = RequestIdHolder.current();
        return StringUtils.hasText(requestId)
                ? requestId
                : UUID.randomUUID().toString().replace("-", "");
    }

    /** 会话没记模型角色时的兜底：Agent 模式用 chat.agent，其余用 chat.default。 */
    private String defaultRole(ChatMode mode) {
        return mode == ChatMode.AGENT
                ? AiModelProperties.Role.CHAT_AGENT
                : AiModelProperties.Role.CHAT_DEFAULT;
    }

    /**
     * 一次流式会话的可变状态与终止逻辑。
     *
     * <p>做成内部类而不是把十几个局部变量塞进 lambda：终止路径有四条
     * （正常结束 / 用户停止 / 异常 / 客户端断开），状态放在对象里才能让"只终止一次"这件事清晰可读。
     *
     * <p>同时实现 {@link AgentContext.ToolCallListener}：工具执行在别的线程上，
     * 由它把"开始检索 / 检索完成"实时转成 SSE 事件，并留一份痕迹用于落库。
     */
    private final class StreamSession implements AgentContext.ToolCallListener {

        private final Long userId;
        private final Long conversationId;
        private final Long assistantMessageId;
        private final String requestId;
        private final String modelRole;
        private final ChatMode mode;
        /** 是否 RAG 模式：决定结束时要做的额外动作（把这一轮写回会话记忆，见 persist） */
        private final boolean ragMode;
        /** 本轮用户提问原文：写回记忆时必须用它，而不是组装后的提示词 */
        private final String question;
        private final SseEmitter emitter;

        private final StringBuilder assembled = new StringBuilder();
        private final long startNanos = System.nanoTime();
        private final AtomicLong firstTokenNanos = new AtomicLong();
        private final AtomicBoolean finalized = new AtomicBoolean(false);
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();
        /** 工具调用痕迹：工具在订阅线程上执行、结束时在订阅线程上落库，但仍然按并发安全的容器声明 */
        private final List<MessageToolCall> toolCalls = Collections.synchronizedList(new ArrayList<>());

        private volatile Usage lastUsage;
        private volatile String model;
        private volatile String finishReason;

        private StreamSession(Long userId, Long conversationId, Long assistantMessageId,
                              String requestId, String modelRole, ChatMode mode, boolean ragMode,
                              String question, SseEmitter emitter) {
            this.userId = userId;
            this.conversationId = conversationId;
            this.assistantMessageId = assistantMessageId;
            this.requestId = requestId;
            this.modelRole = modelRole;
            this.mode = mode;
            this.ragMode = ragMode;
            this.question = question;
            this.emitter = emitter;
        }

        private void attach(Disposable disposable) {
            subscription.set(disposable);
        }

        private void sendMeta(Long userMessageId, Long assistantMessageId) {
            // 雪花 ID 交给全局 Jackson 配置序列化成字符串（ADR-017），这里直接放 Long
            Map<String, Object> payload = new HashMap<>();
            payload.put("requestId", requestId);
            payload.put("conversationId", conversationId);
            payload.put("userMessageId", userMessageId);
            payload.put("assistantMessageId", assistantMessageId);
            payload.put("modelRole", modelRole);
            payload.put("mode", mode.name());
            send(SseEventNames.META, payload);
        }

        /** 检索事件（RAG 模式）：命中片段、来源、是否重排/截断。必须在第一个 delta 之前发出。 */
        private void sendRetrieval(Long knowledgeBaseId, PreparedAnswer prepared) {
            send(SseEventNames.RETRIEVAL, RetrievalEvent.of(knowledgeBaseId, prepared));
        }

        /**
         * 没有资料时的收尾：不调用模型，仍然按完整的事件序列把固定回答推给前端。
         *
         * <p>与 {@code /ask/stream} 的空结果分支行为一致（同一句 {@link RagAnswerService#EMPTY_ANSWER}），
         * 并且<b>照样落库</b>：这条回答对用户是有效的（它明确说了"没找到依据"），
         * 若只在内存里推一把不落库，刷新后这一轮对话就凭空消失了。
         */
        private void finishWithoutModel(String text) {
            if (!finalized.compareAndSet(false, true)) {
                return;
            }
            assembled.append(text);
            send(SseEventNames.DELTA, Map.of("text", text));
            finishReason = "empty";
            persist(MessageStatus.DONE, null);
            send(SseEventNames.DONE, Map.of("finishReason", "empty"));
            finish();
        }

        // ---------- Agent 工具事件 ----------

        @Override
        public void onCall(String tool, String input) {
            send(SseEventNames.TOOL_CALL, Map.of("tool", tool, "input", input));
        }

        @Override
        public void onResult(AgentContext.ToolCallRecord record) {
            MessageToolCall call = new MessageToolCall(record.tool(), record.input(), record.resultSummary());
            toolCalls.add(call);
            Map<String, Object> payload = new HashMap<>();
            payload.put("tool", call.tool());
            payload.put("input", call.input());
            payload.put("resultSummary", call.resultSummary());
            send(SseEventNames.TOOL_RESULT, payload);
        }

        private void onChunk(ChatResponse chunk) {
            if (finalized.get()) {
                return;
            }
            if (cancellationRegistry.isCancelled(requestId)) {
                stopAndFinalize();
                return;
            }
            String text = textOf(chunk);
            if (StringUtils.hasText(text)) {
                assembled.append(text);
                firstTokenNanos.compareAndSet(0L, System.nanoTime());
                send(SseEventNames.DELTA, Map.of("text", text));
            }
            cacheMetadata(chunk);
        }

        private void onComplete() {
            if (!finalized.compareAndSet(false, true)) {
                return;
            }
            persist(MessageStatus.DONE, null);
            sendUsage();
            send(SseEventNames.DONE, Map.of("finishReason", finishReason == null ? "stop" : finishReason));
            finish();
        }

        private void onError(Throwable error) {
            if (!finalized.compareAndSet(false, true)) {
                return;
            }
            log.error("流式生成失败 requestId={}, conversationId={}", requestId, conversationId, error);
            persist(MessageStatus.FAILED, truncate(error.getMessage()));
            send(SseEventNames.ERROR, Map.of(
                    "code", 4001,
                    "message", "模型调用失败，请稍后重试",
                    "retryable", true));
            finish();
        }

        /** 用户点了"停止生成"：真的 dispose 掉上游订阅，否则 Token 还在烧。 */
        private void stopAndFinalize() {
            if (!finalized.compareAndSet(false, true)) {
                return;
            }
            dispose();
            persist(MessageStatus.STOPPED, null);
            sendUsage();
            send(SseEventNames.DONE, Map.of("finishReason", "stopped", "stopped", true));
            finish();
            log.info("用户停止生成 requestId={}, 已保留部分内容 {} 字", requestId, assembled.length());
        }

        /** 客户端断开：无法再推送事件，但要把已生成的部分落库，并停掉上游。 */
        private void abandon() {
            if (!finalized.compareAndSet(false, true)) {
                return;
            }
            dispose();
            persist(MessageStatus.STOPPED, null);
            cancellationRegistry.clear(requestId);
            log.info("客户端断开，已停止生成 requestId={}, 保留 {} 字", requestId, assembled.length());
        }

        private void persist(MessageStatus status, String errorMsg) {
            Integer firstTokenMs = firstTokenNanos.get() == 0
                    ? null
                    : (int) ((firstTokenNanos.get() - startNanos) / 1_000_000);
            Usage usage = lastUsage;
            List<MessageToolCall> trace;
            synchronized (toolCalls) {
                trace = List.copyOf(toolCalls);
            }
            chatMessageService.updateAssistantMessage(userId, assistantMessageId, AssistantReplyDraft.builder()
                    .content(assembled.toString())
                    .model(model)
                    .provider(modelRole)
                    .tokens(usage == null ? null : usage.getPromptTokens(),
                            usage == null ? null : usage.getCompletionTokens(),
                            usage == null ? null : usage.getTotalTokens())
                    .latency(elapsedMillis(), firstTokenMs)
                    .finishReason(finishReason)
                    .requestId(requestId)
                    .status(status.name())
                    .errorMsg(errorMsg)
                    // 工具痕迹随消息落库：刷新页面后"当时检索了什么"仍然可查
                    .toolCalls(trace.isEmpty() ? null : trace)
                    .build());
            rememberTurn();
        }

        /**
         * RAG 模式：把这一轮写回会话记忆（下一轮据此消解指代 + 给模型看历史）。
         *
         * <p>写记忆<b>不能</b>让落库失败：记忆只影响下一轮的输入质量，
         * 而 chat_message 是这一轮的既成事实，两者重要性不对等。
         */
        private void rememberTurn() {
            if (!ragMode) {
                return;
            }
            try {
                ChatMemoryTurns.remember(chatMemory, ChatMemoryKeys.of(conversationId), question,
                        assembled.toString());
            } catch (Exception ex) {
                log.warn("会话记忆写入失败（不影响本轮落库）conversationId={}: {}", conversationId, ex.getMessage());
            }
        }

        private void sendUsage() {
            Usage usage = lastUsage;
            Map<String, Object> payload = new HashMap<>();
            payload.put("promptTokens", usage == null ? null : usage.getPromptTokens());
            payload.put("completionTokens", usage == null ? null : usage.getCompletionTokens());
            payload.put("totalTokens", usage == null ? null : usage.getTotalTokens());
            payload.put("latencyMs", elapsedMillis());
            payload.put("firstTokenMs", firstTokenNanos.get() == 0
                    ? null
                    : (int) ((firstTokenNanos.get() - startNanos) / 1_000_000));
            send(SseEventNames.USAGE, payload);
        }

        private void send(String eventName, Object payload) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload, MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                // 客户端断开（浏览器关标签页 / 网络断）会让 send 抛异常。
                // 这时必须停掉上游，否则模型会继续生成、继续计费。
                log.debug("SSE 发送失败，判定客户端已断开：{}", ex.getMessage());
                abandon();
            }
        }

        private void finish() {
            cancellationRegistry.clear(requestId);
            try {
                emitter.complete();
            } catch (Exception ex) {
                log.debug("关闭 SSE 时出现异常（可忽略）：{}", ex.getMessage());
            }
        }

        private void dispose() {
            Disposable disposable = subscription.get();
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
            }
        }

        private int elapsedMillis() {
            return (int) ((System.nanoTime() - startNanos) / 1_000_000);
        }

        private String textOf(ChatResponse chunk) {
            if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                return null;
            }
            return chunk.getResult().getOutput().getText();
        }

        private void cacheMetadata(ChatResponse chunk) {
            if (chunk == null || chunk.getMetadata() == null) {
                return;
            }
            Usage usage = chunk.getMetadata().getUsage();
            if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
                lastUsage = usage;
            }
            if (StringUtils.hasText(chunk.getMetadata().getModel())) {
                model = chunk.getMetadata().getModel();
            }
            if (chunk.getResult() != null && chunk.getResult().getMetadata() != null
                    && StringUtils.hasText(chunk.getResult().getMetadata().getFinishReason())) {
                finishReason = chunk.getResult().getMetadata().getFinishReason();
            }
        }

        private String truncate(String message) {
            if (message == null) {
                return null;
            }
            return message.length() <= 1000 ? message : message.substring(0, 1000);
        }
    }
}
