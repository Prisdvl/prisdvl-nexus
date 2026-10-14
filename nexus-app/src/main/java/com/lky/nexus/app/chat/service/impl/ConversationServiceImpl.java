package com.lky.nexus.app.chat.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.app.agent.AgentService;
import com.lky.nexus.app.chat.mapper.ChatConversationMapper;
import com.lky.nexus.app.chat.mapper.ChatMessageMapper;
import com.lky.nexus.app.chat.model.ChatMode;
import com.lky.nexus.app.chat.model.MessageRole;
import com.lky.nexus.app.chat.model.dto.ConversationResponse;
import com.lky.nexus.app.chat.model.dto.CreateConversationRequest;
import com.lky.nexus.app.chat.model.dto.ForkConversationRequest;
import com.lky.nexus.app.chat.model.dto.UpdateConversationRequest;
import com.lky.nexus.app.chat.model.entity.ChatConversationPO;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
import com.lky.nexus.app.chat.service.ChatMemoryKeys;
import com.lky.nexus.app.chat.service.ConversationKbIds;
import com.lky.nexus.app.chat.service.ConversationService;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.PageQuery;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话服务实现。
 *
 * <p>三个值得注意的设计：
 * <ol>
 *   <li><b>多租户隔离写在 SQL 条件里</b>（{@code user_id = ?}），而不是先查出来再在 Java 里比对。
 *       后者容易在新增查询时漏掉，前者泄漏可能性更低；</li>
 *   <li><b>访问别人的会话返回 404 而不是 403</b>：403 会告诉攻击者"这个 id 确实存在"；</li>
 *   <li><b>删除 = 软删会话 + 物理删消息 + 清空会话记忆</b>：会话要保留（用户可能只是清理列表），
 *       消息没有独立价值、且量大，物理删除避免无主数据膨胀；
 *       而 {@code SPRING_AI_CHAT_MEMORY} 是"发给模型的有界窗口"，与会话同生命周期——
 *       会话没了它就必须一起走，否则它作为无主数据堆积，还可能被后续同 id 的会话读到。</li>
 * </ol>
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);

    /** 自动生成标题时截取的字符数。 */
    private static final int AUTO_TITLE_LENGTH = 30;

    /**
     * 一个 RAG 会话最多绑定几个知识库（Phase 5.5）。
     *
     * <p><b>为什么写死在代码里而不是做成配置项</b>：这个数字表达的是"这条链路的设计承受能力"，
     * 不是运维旋钮。做成配置项会让人以为调大就能多绑，而它约束的其实不是用户偏好，
     * 是<b>下游</b>（ES / pgvector / 嵌入服务 / 连接池）吃得下多少并发——
     * 这件事不会因为谁改了配置文件而改变。
     *
     * <p>10 的依据：单次 hybrid 检索实测在百毫秒量级，10 个库仍落在"可接受的首字延迟"内。
     *
     * <p><b>Phase 5.5.1 起这条上限守的东西变了，数字没变</b>：并行之前它守的是"逐库串行
     * （每库各一次 ES + 一次 pgvector + 一次查询嵌入 + 回表）叠加出来的首字延迟"，
     * 当时留了一句"将来要可调，前提是先把逐库检索并行化"。并行已经做了
     * （扇出到虚拟线程，整体 10s 截止），延迟不再是限制，限制换成<b>并发放大</b>：
     * 一次问答会把下游的并发度乘到最多库数倍，也就是要求连接池上限 ≥ 绑库数
     * （理由写在 {@code MultiKnowledgeRetriever} 的类注释与 ADR-022 里）。
     * 所以前置条件虽然已经满足，这里也不该顺手把它改成配置项——换的是一个理由，不是一个旋钮。
     */
    private static final int MAX_BOUND_KNOWLEDGE_BASES = 10;

    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AgentService agentService;
    private final JsonMapper jsonMapper;
    private final ChatMemory chatMemory;

    public ConversationServiceImpl(ChatConversationMapper conversationMapper,
                                   ChatMessageMapper messageMapper,
                                   KnowledgeBaseService knowledgeBaseService,
                                   AgentService agentService,
                                   JsonMapper jsonMapper,
                                   ChatMemory chatMemory) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.agentService = agentService;
        this.jsonMapper = jsonMapper;
        this.chatMemory = chatMemory;
    }

    @Override
    @Transactional
    public ConversationResponse create(Long userId, CreateConversationRequest request) {
        ChatMode mode = ChatMode.parse(request.mode());
        String title = normalizeTitle(request.title());

        ChatConversationPO conversation = new ChatConversationPO();
        conversation.setUserId(userId);
        conversation.setTitle(title);
        // title 为空说明要靠首轮消息自动生成
        conversation.setTitleAuto(!StringUtils.hasText(title));
        conversation.setMode(mode.name());
        // 绑定在落库前校验：越权/不存在的知识库必须在这里就被拒绝，
        // 否则会话会带着一个"别人的知识库 id"存下来，等到提问时才炸（甚至悄悄检索不到）
        conversation.setKbIds(resolveKbIds(userId, mode, request.kbIds()));
        conversation.setAgentId(resolveAgentId(userId, mode, request.agentId()));
        // 记下本会话使用的模型角色：换模型时历史会话仍能追溯到当初用的是哪个角色。
        // Agent 模式用专门的 chat.agent 角色——工具调用对模型能力的要求与普通对话不同，
        // 两条链路分开配置才能在"日常对话用便宜模型、Agent 用强模型"之间取舍。
        // 注意这里记的是"会话默认值"：AGENT 模式下真正执行的模型取自 Agent 定义
        // （见 ChatStreamServiceImpl），本字段退化成一个可追溯的历史记录。
        conversation.setModelRole(mode == ChatMode.AGENT
                ? AiModelProperties.Role.CHAT_AGENT
                : AiModelProperties.Role.CHAT_DEFAULT);
        conversation.setMessageCount(0);
        conversation.setPinned(false);
        LocalDateTime now = LocalDateTime.now();
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);

        conversationMapper.insert(conversation);
        log.info("创建会话 id={}, userId={}, mode={}", conversation.getId(), userId, mode);
        return toResponse(conversation);
    }

    @Override
    public PageResult<ConversationResponse> list(Long userId, int pageNum, int pageSize) {
        int safePageNum = PageQuery.normalizePageNum(pageNum);
        int safePageSize = PageQuery.normalizePageSize(pageSize);

        Page<ChatConversationPO> page = new Page<>(safePageNum, safePageSize);
        Page<ChatConversationPO> result = conversationMapper.selectPage(page,
                Wrappers.<ChatConversationPO>lambdaQuery()
                        .eq(ChatConversationPO::getUserId, userId)
                        // 置顶优先，其次按最后消息时间；都没有消息时按创建顺序兜底
                        .orderByDesc(ChatConversationPO::getPinned)
                        .orderByDesc(ChatConversationPO::getLastMessageAt)
                        .orderByDesc(ChatConversationPO::getId));

        List<ConversationResponse> list = result.getRecords().stream().map(this::toResponse).toList();
        return PageResult.of(list, result.getTotal(), safePageNum, safePageSize);
    }

    @Override
    public ConversationResponse detail(Long userId, Long conversationId) {
        return toResponse(requireOwned(userId, conversationId));
    }

@Override
@Transactional
public ConversationResponse update(Long userId, Long conversationId, UpdateConversationRequest request) {
    ChatConversationPO conversation = requireOwned(userId, conversationId);

    /*
     * 顺序说明（改动模式切换后新增，改动顺序是有讲究的）：
     *
     * ① 先查 agentId/resetAgent 自相矛盾 —— 必须最早，否则下面的 mode 迁移
     *    可能已经"顺手清空"了 agentId，再报错时用户会以为是 mode 切换搞坏了它。
     *    这个约定与既有实现一致，不因新增 mode 而改变。
     */
    if (Boolean.TRUE.equals(request.resetAgent()) && request.agentId() != null) {
        // 既指定又清空是自相矛盾的请求：猜哪个都可能在用户没预期的地方生效
        throw new BusinessException(ErrorCode.PARAM_INVALID,
                "agentId 与 resetAgent 不能同时传");
    }

    if (request.title() != null) {
        String title = request.title().trim();
        if (!StringUtils.hasText(title)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "标题不能为空字符串");
        }
        conversation.setTitle(title);
        // 用户手动改过标题后，后续对话不再自动覆盖
        conversation.setTitleAuto(false);
    }
    if (request.pinned() != null) {
        conversation.setPinned(request.pinned());
    }

    /*
     * ② 先把目标模式定下来，后面所有校验都以它为准。
     *
     * ⚠️ 不能继续用 `conversation.getMode()`（旧模式）做校验——
     * "把一个 PLAIN 会话切到 AGENT 并同时指定 agentId" 若按旧模式校验会误报1001，
     * 而用户的意图明确是合法的（切到 AGENT 并选 Agent）。
     */
    ChatMode currentMode = ChatMode.parse(conversation.getMode());
    ChatMode targetMode = request.mode() == null ? currentMode : ChatMode.parse(request.mode());
    boolean modeChanged = targetMode != currentMode;

    /*
     * ③ 按目标模式清理不兼容绑定。
     *
     * 规则（kbIds 仅 RAG 有效、agentId 仅 AGENT 有效，两者互斥）：
     *   - PLAIN：清agentId + 清 kbIds
     *   - RAG：清 agentId；kbIds 默认保留（等下面显式更新那步再定）
     *   - AGENT：清 kbIds；agentId 默认保留（同上）
     *
     * ⚠️ 只在**模式真的变了**时才做清理：
     * "保持 AGENT 模式换 Agent" 不该顺手清 kbIds——虽然清了在语义上也是安全的
     * （AGENT 的检索范围由 Agent 自己的知识库绑定决定，见 resolveKbIds 的提示），
     * 但那会平白让用户丢配置，不该在没要求的情况下发生。
     */
    if (modeChanged) {
        switch (targetMode) {
            case PLAIN -> {
                // 两条绑定都用不上，清干净（"[]" 而非 null：null 会被 updateById 跳过）
                conversation.setKbIds(ConversationKbIds.write(jsonMapper, List.of()));
                conversation.setAgentId(null);
            }
            case RAG -> conversation.setAgentId(null);
            case AGENT -> conversation.setKbIds(ConversationKbIds.write(jsonMapper, List.of()));
        }
        /*
         * ④ 同步重算 modelRole —— 这一步漏掉就是"表面切了模式、实际没切执行路径"：
         * model_role 是 create 时按模式派生并持久化的历史记录，不重算的话
         * 一个切到 AGENT 的会话仍记着 chat.default，而
         * ChatStreamServiceImpl 会按 modelRole 选模型（AGENT 的工具调用需要更强模型）。
         * 与 create 的派生规则保持一致。
         */
        conversation.setMode(targetMode.name());
        conversation.setModelRole(targetMode == ChatMode.AGENT
                ? AiModelProperties.Role.CHAT_AGENT
                : AiModelProperties.Role.CHAT_DEFAULT);
    }

    /*
     * ⑤ kbIds：显式提供才动（null = 不改）。
     * 注意校验用的是 targetMode —— 切到 RAG 后立刻绑库是合法的，
     * 而保持 PLAIN 却想绑库会被resolveKbIds 挡下（1001）。
     */
    if (request.kbIds() != null) {
        conversation.setKbIds(resolveKbIds(userId, targetMode, request.kbIds()));
    }

    /*
     * ⑥ agentId：显式绑定 / 显式重置，两种信号都不会被 mode 迁移覆盖掉——
     * ③④ 只在modeChanged 时清空，而这里是在同一个请求里明确要设的值。
     */
    if (Boolean.TRUE.equals(request.resetAgent())) {
        /*
         * 回到内置默认 Agent。
         *
         * ⚠️ 这里的校验**不能省**（实测回归过一次）：
         * 原实现写成"targetMode 是 AGENT 才校验，否则静默跳过"，
         * 结果「给一个 PLAIN 会话发 resetAgent」返回 200 而不是 1001——
         * 而 `resolveAgentId` 对"选用 Agent"是要求 1001 的，
         * 同一件事两条路两种口径，是明显的契约不一致。
         *
         * 静默跳过比报错更坏：用户以为"已经回到默认助手"了，
         * 实际那个 Agent 还挂在会话上（而且因为 ③ 已经在切模式时清过，
         * 结果是"返回成功但什么都没变"），属于典型的假成功。
         *
         * 校验用**目标模式**（targetMode）而非库里的旧模式：
         * "把 PLAIN 会话切到 AGENT 并同时 resetAgent" 是合法的
         *（先切模式再重置），而"没传 mode 的 PLAIN 会话 resetAgent" 必须拒绝。
         */
        requireAgentMode(targetMode, "清空 Agent");
        conversation.setAgentId(null);
    } else if (request.agentId() != null) {
        conversation.setAgentId(resolveAgentId(userId, targetMode, request.agentId()));
    }

    conversation.setUpdatedAt(LocalDateTime.now());

    conversationMapper.updateById(conversation);
    return toResponse(conversation);
}

    @Override
    @Transactional
    public void delete(Long userId, Long conversationId) {
        requireOwned(userId, conversationId);

        // 会话走逻辑删除（@TableLogic 自动改写为 UPDATE deleted=1）
        conversationMapper.deleteById(conversationId);
        // 消息物理删除：否则消息表会随会话删除不断堆积无主数据
        int removedMessages = messageMapper.delete(Wrappers.<ChatMessagePO>lambdaQuery()
                .eq(ChatMessagePO::getConversationId, conversationId));
        // 会话记忆必须一起清（key = "conv:{id}"，见 ChatMemoryKeys）。
        // 不清它不只是"多留几行无主数据"：记忆是"这次发给模型多少历史"的唯一来源，
        // 一旦该 id 重新被读到（重建会话、同 id 复用），新会话就会凭空记得旧会话说过的话。
        // 用抽象层 clear() 而不是裸 SQL —— key 的构造规则只在 ChatMemoryKeys 一处，
        // 在这里重新拼一遍字符串等于把"key 长什么样"复制成两份，迟早分叉。
        chatMemory.clear(ChatMemoryKeys.of(conversationId));
        log.info("删除会话 id={}, userId={}：清理消息 {} 条、会话记忆已清空",
                conversationId, userId, removedMessages);
    }

    /**
     * 分支（P1-3.7）。
     *
     * <p>复制规则刻意保持"整份复制、互不影响"：
     * <ul>
     *   <li>新会话复制 mode / kbIds / agentId / modelRole，但<b>不复制置顶</b>
     *       （置顶是列表组织，不是内容的一部分）；</li>
     *   <li>消息复制到 beforeMessageId（含）为止，雪花 id 单调，按 id 升序取前缀即可；
     *       复制行的内容 / token / metadata（工具调用、引用）原样保留，
     *       但 requestId 与 parentMessageId 清空——它们指向原会话的日志与消息，
     *       带过去会把新会话的排障串到旧链路上；</li>
     *   <li>消息保留原 createdAt（历史事实），会话的 lastMessageAt 取最后一条复制消息，
     *       于是分支在列表里排在"对话真正停留的时刻"附近；</li>
     *   <li>把最近若干个完整问答对重放进新会话的 ChatMemory——
     *       否则分支里问"那它有什么缺点"时代理消解拿不到被复制的历史（ADR-011：
     *       ChatMemory 是发给模型的有界窗口，不会因为消息落库就自动恢复）。</li>
     * </ul>
     */
    @Override
    @Transactional
    public ConversationResponse fork(Long userId, Long conversationId, ForkConversationRequest request) {
        ChatConversationPO source = requireOwned(userId, conversationId);

        String title = request.title() == null ? null : request.title().trim();
        if (!StringUtils.hasText(title)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "分支标题不能为空");
        }

        Long beforeId = request.beforeMessageId();
        if (beforeId != null) {
            Long belongs = messageMapper.selectCount(Wrappers.<ChatMessagePO>lambdaQuery()
                    .eq(ChatMessagePO::getId, beforeId)
                    .eq(ChatMessagePO::getConversationId, conversationId));
            if (belongs == null || belongs == 0) {
                // 截断点必须属于原会话，否则可能复制了错误的范围
                throw new BusinessException(ErrorCode.PARAM_INVALID, "截断点消息不属于该会话");
            }
        }

        List<ChatMessagePO> messages = messageMapper.selectList(Wrappers.<ChatMessagePO>lambdaQuery()
                .eq(ChatMessagePO::getConversationId, conversationId)
                .le(beforeId != null, ChatMessagePO::getId, beforeId)
                .orderByAsc(ChatMessagePO::getId));

        LocalDateTime now = LocalDateTime.now();
        ChatConversationPO copy = new ChatConversationPO();
        copy.setUserId(userId);
        copy.setTitle(title);
        copy.setTitleAuto(false);
        copy.setMode(source.getMode());
        // 直接透传 JSON 原文：复制的是同一个用户自己的绑定，不需要重新做归属校验
        copy.setKbIds(source.getKbIds());
        copy.setAgentId(source.getAgentId());
        copy.setModelRole(source.getModelRole());
        copy.setPromptTemplateId(source.getPromptTemplateId());
        copy.setMessageCount(messages.size());
        copy.setPinned(false);
        copy.setLastMessageAt(messages.isEmpty() ? null : messages.get(messages.size() - 1).getCreatedAt());
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        conversationMapper.insert(copy);

        List<Message> memoryWindow = new ArrayList<>();
        for (ChatMessagePO original : messages) {
            ChatMessagePO cloned = new ChatMessagePO();
            cloned.setConversationId(copy.getId());
            cloned.setUserId(userId);
            cloned.setAgentId(original.getAgentId());
            cloned.setRole(original.getRole());
            cloned.setContent(original.getContent());
            cloned.setReasoningContent(original.getReasoningContent());
            cloned.setModel(original.getModel());
            cloned.setProvider(original.getProvider());
            cloned.setPromptTokens(original.getPromptTokens());
            cloned.setCompletionTokens(original.getCompletionTokens());
            cloned.setTotalTokens(original.getTotalTokens());
            cloned.setLatencyMs(original.getLatencyMs());
            cloned.setFirstTokenMs(original.getFirstTokenMs());
            cloned.setFinishReason(original.getFinishReason());
            // requestId / parentMessageId 指向原会话的运行与消息，不跨会话复制
            cloned.setRequestId(null);
            cloned.setPromptVersionId(original.getPromptVersionId());
            cloned.setParentMessageId(null);
            cloned.setStatus(original.getStatus());
            cloned.setErrorMsg(original.getErrorMsg());
            cloned.setMetadata(original.getMetadata());
            cloned.setCreatedAt(original.getCreatedAt());
            messageMapper.insert(cloned);

            /*
             * 记忆重放只收成对的 USER/ASSISTANT 纯文本（与 ChatMemoryTurns.remember
             * 的写入口径保持一致），且只重放最近窗口内的部分：
             * ChatMemory 本身是有界窗口，灌太多也会被裁掉，还会平白放大写库量。
             */
            if (StringUtils.hasText(original.getContent())) {
                if (MessageRole.USER.name().equals(original.getRole())) {
                    memoryWindow.add(new UserMessage(original.getContent()));
                } else if (MessageRole.ASSISTANT.name().equals(original.getRole())) {
                    memoryWindow.add(new AssistantMessage(original.getContent()));
                }
            }
        }
        if (!memoryWindow.isEmpty()) {
            int maxWindowMessages = 20;
            List<Message> replay = memoryWindow.size() > maxWindowMessages
                    ? memoryWindow.subList(memoryWindow.size() - maxWindowMessages, memoryWindow.size())
                    : memoryWindow;
            chatMemory.add(ChatMemoryKeys.of(copy.getId()), replay);
        }

        log.info("分支会话 id={} 由 id={} 复制，消息 {} 条，userId={}",
                copy.getId(), conversationId, messages.size(), userId);
        return toResponse(copy);
    }

    @Override
    public ChatConversationPO requireOwned(Long userId, Long conversationId) {
        if (conversationId == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        // 关键：把 user_id 放进查询条件，而不是查出来再比对
        ChatConversationPO conversation = conversationMapper.selectOne(
                Wrappers.<ChatConversationPO>lambdaQuery()
                        .eq(ChatConversationPO::getId, conversationId)
                        .eq(ChatConversationPO::getUserId, userId));
        if (conversation == null) {
            // 别人的会话与不存在的会话返回完全相同的错误：不泄露资源存在性
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    private String normalizeTitle(String rawTitle) {
        return StringUtils.hasText(rawTitle) ? rawTitle.trim() : null;
    }

    /**
     * 校验并序列化绑定的知识库。
     *
     * <p>这几条规则都是"宁可不做、也不装作做了"：
     * <ol>
     *   <li><b>只有 RAG 能用</b>：PLAIN 绑了没用；AGENT 的检索范围由所选 Agent 决定
     *       （Phase 4.1 起 Agent 绑定自己的知识库，见 {@code AgentService}）。
     *       写进会话字段只会让人误以为检索被限定了；</li>
     *   <li><b>最多 {@value #MAX_BOUND_KNOWLEDGE_BASES} 个</b>：多库检索是<b>并行</b>的
     *       （Phase 5.5.1 起扇出到虚拟线程，整体 10s 截止），所以上限防的不再是"线性变慢"，
     *       而是<b>并发放大</b>——一次问答会把 ES / pgvector / 嵌入服务 / 连接池的并发度
     *       乘到最多库数倍（每个库在 Retriever 里还要各做一次归属查询）。
     *       也就是说：上限的存在没变，但它守的东西从"延迟"换成了"下游压力"。
     *       超过上限仍然显式拒绝，而不是"先绑上、压垮了再说"；</li>
     *   <li><b>必须是自己的库</b>：走 {@code requireAllOwned}——<b>一次校验全部 id</b>，
     *       任意一个不存在或不属于该用户就整批 3001，不做部分成功。
     *       "绑定成功、检索却少查了一个库"是这里最坏的结果，因为它不报错；</li>
     * </ol>
     *
     * <p><b>Phase 5.5 之前这里还有一条"最多绑一个"的拒绝</b>，理由是检索侧当时只有单库签名
     * （{@code ChunkSearchService} 吃一个 kbId），绑多个只能 {@code get(0)}。
     * 检索侧支持多库之后那条拒绝就只剩阻塞作用了：它要防的"静默丢条件"已经不存在，
     * 现在是有几个库查几个库。这条注释留在原处，是为了说明<b>为什么规则变了</b>——
     * 而不是把旧规则悄悄删掉。
     *
     * <p>同理，上面第 2 条在 Phase 5.5.1 被改的是<b>理由</b>而不是数字：
     * 并行之后"逐库串行、成本线性增长"这句话不再成立（继续留着它，会让下一个人
     * 按错误的模型去调这个上限）。这类"注释比代码先过期"的地方必须跟着改——
     * 它不报错，只是把人引到错误的方向上。
     */
    private String resolveKbIds(Long userId, ChatMode mode, List<Long> rawKbIds) {
        List<Long> kbIds = ConversationKbIds.normalize(rawKbIds);
        if (kbIds.isEmpty()) {
            // 注意不能返回 null：updateById 会跳过 null 字段，"解绑"就会静默失效
            // （详见 ConversationKbIds.write 的说明）
            return ConversationKbIds.write(jsonMapper, List.of());
        }
        if (mode != ChatMode.RAG) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "只有 RAG 会话可以绑定知识库（当前模式 " + mode + "）；"
                            + "Agent 模式的检索范围由所选 Agent 的知识库绑定决定");
        }
        if (kbIds.size() > MAX_BOUND_KNOWLEDGE_BASES) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "最多绑定 " + MAX_BOUND_KNOWLEDGE_BASES + " 个知识库，收到 " + kbIds.size() + " 个");
        }
        knowledgeBaseService.requireAllOwned(userId, kbIds);
        return ConversationKbIds.write(jsonMapper, kbIds);
    }

    /**
     * 校验会话选用的 Agent（仅 AGENT 模式）。
     *
     * <p>三条规则，与 {@link #resolveKbIds} 是同一种"写入口就把话说清楚"的思路：
     * <ol>
     *   <li>{@code null} 是<b>合法</b>的，表示用内置默认 Agent（运行期解析，见
     *       {@code AgentService.resolveDefault}）；</li>
     *   <li><b>只有 AGENT 模式能选</b>：PLAIN/RAG 挂一个 Agent 进去不会生效，
     *       静默忽略比报错更坏——用户会以为提示词变了而实际没变；</li>
     *   <li><b>必须是自己的、且已发布</b>：走 {@code requireDefinition}（DRAFT 报 5008、
     *       DISABLED 报 5009、别人的报 5006→404）。在这里挡住，是为了让"选了草稿 Agent"
     *       在<b>选的时候</b>就失败，而不是等提问时才炸。</li>
     * </ol>
     *
     * <p>运行期仍会再解析一次（{@code ChatStreamServiceImpl}）：本方法只能保证"选的那刻"是
     * PUBLISHED，之后被停用的 Agent 必须在那时被拦住，否则停用就形同虚设。
     */
    private Long resolveAgentId(Long userId, ChatMode mode, Long rawAgentId) {
        if (rawAgentId == null) {
            return null;
        }
        requireAgentMode(mode, "选用 Agent");
        agentService.requireDefinition(userId, rawAgentId);
        return rawAgentId;
    }

    /** "Agent 只属于 AGENT 会话"这一条，选用与清空都适用，所以单独提出来。 */
    private void requireAgentMode(ChatMode mode, String action) {
        if (mode != ChatMode.AGENT) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "只有 AGENT 会话可以" + action + "（当前模式 " + mode + "）");
        }
    }

    private ConversationResponse toResponse(ChatConversationPO conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getMode(),
                conversation.getModelRole(),
                conversation.getMessageCount(),
                conversation.getLastMessageAt(),
                conversation.getPinned(),
                ConversationKbIds.parse(jsonMapper, conversation.getKbIds()),
                conversation.getAgentId(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }
}
