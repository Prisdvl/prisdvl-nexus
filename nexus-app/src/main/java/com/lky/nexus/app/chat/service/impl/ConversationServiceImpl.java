package com.lky.nexus.app.chat.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.app.agent.AgentService;
import com.lky.nexus.app.chat.mapper.ChatConversationMapper;
import com.lky.nexus.app.chat.mapper.ChatMessageMapper;
import com.lky.nexus.app.chat.model.ChatMode;
import com.lky.nexus.app.chat.model.dto.ConversationResponse;
import com.lky.nexus.app.chat.model.dto.CreateConversationRequest;
import com.lky.nexus.app.chat.model.dto.UpdateConversationRequest;
import com.lky.nexus.app.chat.model.entity.ChatConversationPO;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
import com.lky.nexus.app.chat.service.ConversationKbIds;
import com.lky.nexus.app.chat.service.ConversationService;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.PageQuery;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话服务实现。
 *
 * <p>三个值得注意的设计：
 * <ol>
 *   <li><b>多租户隔离写在 SQL 条件里</b>（{@code user_id = ?}），而不是先查出来再在 Java 里比对。
 *       后者容易在新增查询时漏掉，前者泄漏可能性更低；</li>
 *   <li><b>访问别人的会话返回 404 而不是 403</b>：403 会告诉攻击者"这个 id 确实存在"；</li>
 *   <li><b>删除 = 软删会话 + 物理删消息</b>：会话要保留（用户可能只是清理列表），
 *       消息没有独立价值、且量大，物理删除避免无主数据膨胀。</li>
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
     * <p><b>为什么写死在代码里而不是做成配置项</b>：多库检索是逐库<b>串行</b>的
     * （每库各一次 ES + 一次 pgvector + 一次查询嵌入 + 回表），成本随库数线性增长。
     * 这个数字表达的是"这条链路的设计承受能力"，不是运维旋钮——
     * 做成配置项会让人以为调大就能多绑，而真正的瓶颈（串行放大首字延迟）不会因此改变。
     *
     * <p>10 的依据：单次 hybrid 检索实测在百毫秒量级，10 个库仍落在"可接受的首字延迟"内。
     * 将来若真要把它变成可调参数，前提是先把逐库检索并行化。
     */
    private static final int MAX_BOUND_KNOWLEDGE_BASES = 10;

    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AgentService agentService;
    private final JsonMapper jsonMapper;

    public ConversationServiceImpl(ChatConversationMapper conversationMapper,
                                   ChatMessageMapper messageMapper,
                                   KnowledgeBaseService knowledgeBaseService,
                                   AgentService agentService,
                                   JsonMapper jsonMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.agentService = agentService;
        this.jsonMapper = jsonMapper;
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
        ChatMode mode = ChatMode.parse(conversation.getMode());
        if (request.kbIds() != null) {
            // null = 不改（局部更新约定）；空列表 = 解绑
            conversation.setKbIds(resolveKbIds(userId, mode, request.kbIds()));
        }
        if (Boolean.TRUE.equals(request.resetAgent()) && request.agentId() != null) {
            // 既指定又清空是自相矛盾的请求：猜哪个都可能在用户没预期的地方生效
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "agentId 与 resetAgent 不能同时传");
        }
        if (Boolean.TRUE.equals(request.resetAgent())) {
            // 回到内置默认 Agent。只在 AGENT 模式有意义，理由与"选 Agent"同：
            // 给 PLAIN/RAG 会话留一个 agentId 会让"谁在执行"变得含糊
            requireAgentMode(mode, "清空 Agent");
            conversation.setAgentId(null);
        } else if (request.agentId() != null) {
            conversation.setAgentId(resolveAgentId(userId, mode, request.agentId()));
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
        log.info("删除会话 id={}, userId={}, 同时清理消息 {} 条", conversationId, userId, removedMessages);
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
     *   <li><b>最多 {@value #MAX_BOUND_KNOWLEDGE_BASES} 个</b>：多库检索是<b>逐库串行</b>的，
     *       成本随库数线性增长，超过上限显式拒绝，而不是"先绑上、慢起来再说"；</li>
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
