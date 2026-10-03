package com.lky.nexus.app.chat.service.impl;

import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.error.FailureClassifier;
import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.app.agent.AgentContext;
import com.lky.nexus.app.agent.AgentRuntime;
import com.lky.nexus.app.agent.AgentService;
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
import com.lky.nexus.app.chat.service.ConversationAgentResolver;
import com.lky.nexus.app.chat.service.ConversationKbIds;
import com.lky.nexus.app.chat.service.ConversationService;
import com.lky.nexus.app.chat.stream.RetrievalEvent;
import com.lky.nexus.app.chat.stream.SseEventNames;
import com.lky.nexus.app.chat.stream.StreamCancellationRegistry;
import com.lky.nexus.app.infra.web.RequestIdHolder;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import com.lky.nexus.common.security.UserContextKeys;
import com.lky.nexus.knowledge.rag.Citations;
import com.lky.nexus.knowledge.rag.PreparedAnswer;
import com.lky.nexus.knowledge.rag.RagAnswer;
import com.lky.nexus.knowledge.rag.RagAnswerService;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
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
 *   <li>{@code AGENT} —— 会话所选 Agent 的提示词/模型/知识库范围 + 知识库检索工具，
 *       由模型决定检索什么（Phase 4.1 起不再是写死的系统提示）。</li>
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
    private final RagAnswerService ragAnswerService;
    private final AgentService agentService;
    private final AgentRuntime agentRuntime;
    private final JsonMapper jsonMapper;
    private final ObservabilityRegistry observabilityRegistry;

    public ChatStreamServiceImpl(ConversationService conversationService,
                                 ChatMessageService chatMessageService,
                                 ChatClientFactory chatClientFactory,
                                 ChatMemory chatMemory,
                                 StreamCancellationRegistry cancellationRegistry,
                                 RagAnswerService ragAnswerService,
                                 AgentService agentService,
                                 AgentRuntime agentRuntime,
                                 JsonMapper jsonMapper,
                                 ObservabilityRegistry observabilityRegistry) {
        this.conversationService = conversationService;
        this.chatMessageService = chatMessageService;
        this.chatClientFactory = chatClientFactory;
        this.chatMemory = chatMemory;
        this.cancellationRegistry = cancellationRegistry;
        this.ragAnswerService = ragAnswerService;
        this.agentService = agentService;
        this.agentRuntime = agentRuntime;
        this.jsonMapper = jsonMapper;
        this.observabilityRegistry = observabilityRegistry;
    }

    @Override
    public SseEmitter stream(Long userId, Long conversationId, String content) {
        // 归属校验放在最前面：异常会走全局异常处理器返回统一 JSON，而不是开一个 SSE 再报错
        ChatConversationPO conversation = conversationService.requireOwned(userId, conversationId);
        // 请求次数在**校验通过之后**才计：越权/不存在的会话是请求参数问题，
        // 计进去会让"对话请求量"把探测性请求也算成正常流量
        observabilityRegistry.increment(ObservabilityRegistry.CHAT_REQUESTS);
        // 模式决定这条链路走法：PLAIN 纯生成、RAG 先检索再回答、AGENT 由模型自己决定检索什么
        ChatMode mode = ChatMode.parse(conversation.getMode());
        // Agent 执行次数（5.9 六）：与 chat.requests 分开计，因为两者回答的问题不同——
        // "用户问了多少次"与"跑 Agent 跑了多少次"在 PLAIN/RAG 会话多起来之后会分叉，
        // 合成一个数就看不出 Agent 这一路实际承担了多少流量
        if (mode == ChatMode.AGENT) {
            observabilityRegistry.increment(ObservabilityRegistry.AGENT_EXECUTIONS);
        }
        // Agent 定义同样在落库之前解析：会话选了 DRAFT/DISABLED 的 Agent 时，
        // 用户应当看到一条明确的报错，而不是"先多出一条消息、再在 SSE 里半路失败"。
        // 未指定 Agent（agentId 为空）走内置默认——AGENT 模式永远有兜底，不存在"没有 Agent"的状态。
        //
        // 解析规则本身在 ConversationAgentResolver：5.4 起非流式入口也要按模式解析，
        // 两处各写一份的后果是"流式报 5008、非流式照跑"这种不报错的行为漂移。
        AgentDefinition agentDefinition = ConversationAgentResolver.resolve(agentService, userId, conversation, mode);
        // AGENT 模式下模型角色取自 Agent 定义（Agent 自己声明它需要什么模型），
        // 会话上的 model_role 只是创建时记下的历史值（见 ConversationServiceImpl）
        String modelRole = agentDefinition != null
                ? agentDefinition.modelRole()
                : StringUtils.hasText(conversation.getModelRole())
                        ? conversation.getModelRole()
                        : defaultRole(mode);
        // 取模型放在落库之前：模型未注册（4006）属于配置问题，必须在<b>产生副作用之前</b>失败。
        // 否则会留下一条用户消息 + 一条永远停在 STREAMING 的空助手消息，用户看到的是"消息凭空多了一条"。
        ChatClient chatClient = chatClientFactory.get(modelRole);

        // RAG 模式的检索同样放在落库之前（与 /ask/stream 一致，见 RagStreamServiceImpl）：
        // ① 知识库未绑定/不属于自己、索引不可用，都会以统一 JSON 报错，而不是"先开一条 SSE 再在半路报错"；
        // ② 检索发生在生成之前，用户等待的就是"检索 + 生成"两段，事件顺序也才好保证。
        boolean ragMode = mode == ChatMode.RAG;
        // 会话可以绑定多个知识库（Phase 5.5）：这里的列表就是"本次检索覆盖哪些库"，
        // 非 RAG 模式下没有库维度，取空表（下游只在 ragMode 分支里用它）。
        List<Long> knowledgeBaseIds = ragMode ? requireKnowledgeBaseIds(conversation) : List.of();
        // 历史从 ChatMemory 取（不是 chat_message 全量）：它同时服务两个地方——
        // 消解指代的检索查询串、以及给模型的"之前的问答"。详见 ChatMemoryTurns 的类注释。
        List<RagAnswerService.RagTurn> history = ragMode
                ? ChatMemoryTurns.read(chatMemory, ChatMemoryKeys.of(conversationId))
                : List.of();
        PreparedAnswer prepared = ragMode
                ? ragAnswerService.prepareMany(userId, knowledgeBaseIds, content, null, history)
                : null;

        String requestId = resolveRequestId();

        ChatMessagePO userMessage = chatMessageService.appendUserMessage(userId, conversationId, content, requestId);
        // 先落一条 STREAMING：即使进程中途崩溃，用户也能看到"有一条没生成完"，
        // 而不是消息凭空消失（等生成完再落库的话，崩溃就全丢了）
        ChatMessagePO assistantMessage = chatMessageService.appendAssistantMessage(userId, conversationId,
                // 出处（5.4）在骨架落库时就写定，不等生成完再补：这条消息从存在的那一刻起
                // 就有主人。传的是**解析后**的 id 而不是 conversation.getAgentId()——
                // 后者为 null 时（会话没选）照抄下去就丢掉了"其实是内置默认在跑"
                agentDefinition == null ? null : agentDefinition.agentId(),
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
            session.sendRetrieval(knowledgeBaseIds, prepared);
            // 交给模型的那几段同时留一份：回答结束后它们要变成随消息落库的引用。
            // 顺序就是提示词里的 [1..n]，与回答正文里的编号同一套
            session.rememberRetrieval(prepared.usedHits());
        }

        // 没有资料就不调用模型（与 /ask/stream 完全一致：省钱，也从根上避免"无资料硬答"）。
        // 仍走完整的流式事件序列，前端只有一套处理逻辑。
        if (ragMode && prepared.empty()) {
            // 同样是"没有资料"，但两种原因的用户可见文案必须不同：一个是查证结果（库里没有），
            // 一个是故障（有库没搜成，我们并不知道里面有没有）。混为一谈就是让用户
            // 把基础设施故障当成自己笔记的结论（Phase 5.5.1）
            session.finishWithoutModel(prepared.hasFailures()
                    ? RagAnswerService.RETRIEVAL_FAILED_ANSWER
                    : RagAnswerService.EMPTY_ANSWER);
            return emitter;
        }

        Flux<ChatResponse> modelFlux = buildFlux(userId, chatClient, session, conversationId, content,
                ragMode, prepared, history, agentDefinition);
        // subscribeOn：分片读取可能阻塞，必须离开 MVC 请求线程
        Disposable subscription = modelFlux.subscribeOn(Schedulers.boundedElastic())
                .subscribe(session::onChunk, session::onError, session::onComplete);
        session.attach(subscription);

        log.info("开始流式生成 requestId={}, conversationId={}, mode={}, modelRole={}{}",
                requestId, conversationId, mode, modelRole,
                ragMode ? ", kbIds=" + knowledgeBaseIds + ", 检索到 " + prepared.usedHits().size() + " 个片段" : "");
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
                                         PreparedAnswer prepared, List<RagAnswerService.RagTurn> history,
                                         AgentDefinition agentDefinition) {
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
            // token 记账归属（5.6）：放在 if/else 之外，是因为两个分支都要记账。
            // RAG 分支不挂记忆 advisor，但它照样要花 token——只在非 RAG 分支里传 userId
            // 会让"用 RAG 对话永远不扣额度"，而且这种漏记没有任何报错
            request = request.advisors(a -> a.param(UserContextKeys.USER_ID, userId));
            if (agentMode) {
                // 提示词 + 工具 + ToolContext 的组装交给 AgentRuntime.configure：一次性调用
                // （AgentRuntime.ask）走的是同一个方法。两条链路各写一份的话，将来给 Agent
                // 加第二个工具时只改一处，症状会是"对话能用、流式不能用"——不报错、只是不生效。
                // 这里传的 listener 是 session：工具执行时就能把"检索中 / 检索完成"实时推给前端。
                request = agentRuntime.configure(request, userId, agentDefinition, session);
            }
            return request.stream().chatResponse();
        });
    }

    /**
     * 取会话绑定的知识库（RAG 模式，Phase 5.5 起可以是一个或多个）。
     *
     * <p>这条路径上<b>没有</b>"悄悄退化成不检索"这个选项：RAG 会话拿不到知识库时，
     * 用户真正想要的是"告诉我哪里没配好"，而不是一个看起来正常、其实没查过笔记的回答。
     *
     * <p><b>为什么这里不再取 {@code get(0)}</b>：绑定是用户在界面上配的，
     * 静默只查第一个库的后果是"答案确实来自知识库、但只是一部分资料"，看不出少查了。
     * 库里到底有几个就查几个——"只绑了一个"是 {@code List.of(one)} 这个特例，不是另一条分支。
     *
     * <p>这里<b>不做</b>归属校验：{@code prepareMany} 会先 {@code requireAllOwned}，
     * 两处都查一遍只是多一次 SQL，还会让"谁负责这件事"变模糊。
     */
    private List<Long> requireKnowledgeBaseIds(ChatConversationPO conversation) {
        List<Long> kbIds = ConversationKbIds.parse(jsonMapper, conversation.getKbIds());
        if (kbIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "该会话（RAG 模式）尚未绑定知识库，请先绑定后再提问");
        }
        return kbIds;
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

        /**
         * 这一轮<b>交给模型的</b>资料片段，按"第几次召回"分组（RAG 模式恰好一组，
         * Agent 模式每次工具调用各一组）。
         *
         * <p>为什么要分组：编号规则不同。RAG 的 {@code [n]} 是这一组内的位置；
         * Agent 的 {@code [n]} 是<b>每次工具调用各自</b>从 1 开始的（工具是无状态的，
         * 它不知道自己是本轮的第二次调用）。而"这段资料被引用了吗"只能拿
         * <b>它自己那一组的编号</b>去回答正文里比对——拉平之后再算，
         * 第二次调用的第 1 条会被拿去比对第一次调用的 {@code [1]}，
         * 结论就反了。因此在 {@link #citations()} 里逐组算、算完再合并编号。
         */
        private final List<List<ChunkSearchService.SearchHit>> retrievalGroups =
                Collections.synchronizedList(new ArrayList<>());

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

        /**
         * 检索事件（RAG 模式）：本次查了哪些库、命中哪些片段、是否重排/截断。
         * 必须在第一个 delta 之前发出。
         */
        private void sendRetrieval(List<Long> knowledgeBaseIds, PreparedAnswer prepared) {
            send(SseEventNames.RETRIEVAL, RetrievalEvent.of(knowledgeBaseIds, prepared));
        }

        /**
         * 留一份本轮交给模型的片段（RAG 模式在订阅之前调用，Agent 模式由工具回调调用）。
         *
         * <p>不推事件：片段已经在 {@code retrieval} 事件里推过了，这里只是"落库要用的那份"。
         */
        private void rememberRetrieval(List<ChunkSearchService.SearchHit> hits) {
            if (hits != null && !hits.isEmpty()) {
                retrievalGroups.add(List.copyOf(hits));
            }
        }

        /**
         * 交给模型的片段 → 随消息落库的引用列表。
         *
         * <p>合并规则（分组算 {@code referenced}、按 chunkId 去重、统一重编号）在
         * {@link Citations#merge} 里，那里是三条链路共用的一处；这里只负责把
         * 收集到的分组交出去。
         */
        private List<RagAnswer.RagCitation> buildCitations() {
            List<List<ChunkSearchService.SearchHit>> groups;
            synchronized (retrievalGroups) {
                groups = List.copyOf(retrievalGroups);
            }
            return Citations.merge(groups, assembled.toString());
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

        /**
         * 工具把资料交给了模型（Agent 模式）。
         *
         * <p>只留档、不推事件：工具痕迹事件（{@code tool_result}）已经告诉前端
         * "这一步命中了几条"，再把每一段正文推一遍只是把同一件事说两遍。
         * 而落库需要的是<b>哪几段</b>——那正是痕迹里没有的东西。
         */
        @Override
        public void onRetrieval(String tool, List<ChunkSearchService.SearchHit> hits) {
            rememberRetrieval(hits);
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
            send(SseEventNames.DONE, donePayload(finishReason == null ? "stop" : finishReason));
            finish();
        }

        /**
         * 流式失败。
         *
         * <p>这里曾经写死 {@code code 4001 / message "模型调用失败" / retryable true}——
         * 于是"Agent 工具循环撞到轮数上限（5002）"、"检索服务不可用（4005）"、
         * "模型响应超时（4002）"统统被报成同一句话，用户按错误的线索去排查。
         * 现在码与文案交给 {@link FailureClassifier}（判定只写在那一个地方，
         * 同步问答与这里因此不会漂移）。
         *
         * <p>落库的 {@code error_msg} 存<b>给用户看的那句</b>而不是原始异常文本：
         * 原始异常连同堆栈已经在下面这行日志里（带 requestId，可回查），
         * 而 {@code error_msg} 会随 {@code GET /api/chat/conversations/{id}/messages}
         * 返回给客户端——把上游响应体、内网地址之类的东西塞进这个字段，
         * 等于开了一条"错误信息外泄"的通道（update.txt §十四）。
         */
        private void onError(Throwable error) {
            if (!finalized.compareAndSet(false, true)) {
                return;
            }
            FailureClassifier.Failure failure = FailureClassifier.classify(error);
            log.error("流式生成失败 requestId={}, conversationId={}, code={}",
                    requestId, conversationId, failure.code().getCode(), error);
            // 流式失败不走全局异常处理器（SSE 已经打开，只能发 error 帧），
            // 因此这里必须自己计一次；否则"对话失败了多少次"在指标里永远是 0
            observabilityRegistry.increment(ObservabilityRegistry.ERRORS);
            persist(MessageStatus.FAILED, truncate(failure.message()));
            send(SseEventNames.ERROR, Map.of(
                    "code", failure.code().getCode(),
                    "message", failure.message(),
                    "retryable", failure.retryable()));
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
            Map<String, Object> payload = donePayload("stopped");
            payload.put("stopped", true);
            send(SseEventNames.DONE, payload);
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
            List<RagAnswer.RagCitation> citations = buildCitations();
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
                    // 引用同理：检索事件只在流里发一次，不落库的话刷新页面就没了——
                    // "这条回答是照着什么说的"在历史回放里无从查证（update.txt §五）
                    .citations(citations.isEmpty() ? null : citations)
                    .build());
            // 端到端耗时（5.9 六）：从请求进来到这一轮落库为止，含检索 + 全部模型往返 + 工具调用。
            // 记在这里而不是 sendUsage 里，是因为四条终止路径（正常/停止/异常/断开）都汇到 persist，
            // 只有它保证"每一轮各记一次、不会漏也不会重复"
            observabilityRegistry.recordDuration(ObservabilityRegistry.CHAT_LATENCY, elapsedMillis());
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

        /**
         * {@code done} 事件的负载：结束原因 + 本轮引用（没有引用就不带这个字段）。
         *
         * <p>引用跟着 {@code done} 走、而不是另开一个事件类型：它本来就要等回答结束才算得出来
         * （{@code referenced} 是在完整正文里比对编号），而前端也需要它在<b>实时</b>与
         * <b>刷新后</b>两个视图里长得一样——只落库不发事件的话，同一轮对话
         * 刷新前看不到引用、刷新后却出现了，看起来像是"刷新把数据变多了"。
         */
        private Map<String, Object> donePayload(String reason) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("finishReason", reason);
            List<RagAnswer.RagCitation> citations = buildCitations();
            if (!citations.isEmpty()) {
                payload.put("citations", citations);
            }
            return payload;
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
