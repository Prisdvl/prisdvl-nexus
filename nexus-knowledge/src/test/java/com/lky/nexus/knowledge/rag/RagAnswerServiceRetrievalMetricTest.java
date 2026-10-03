package com.lky.nexus.knowledge.rag;

import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code retrieval.requests} 必须覆盖<b>所有真的发起检索的入口</b>（Phase 5.9 六）。
 *
 * <p>这个指标的定义是"真正发起检索的次数"，而检索有两条入口：
 * {@link RagAnswerServiceImpl#ask}（知识库问答，同步）与
 * {@link RagAnswerServiceImpl#prepareRetrieved}（会话流式 / 多库，经 {@code prepare}）。
 * 早先只在后者计数，于是<b>知识库问答页发起的检索一次都不算</b>——
 * 而这个指标的失效形态恰恰是"少了一部分"，看起来像"流量低"，不会以任何方式报错。
 *
 * <p>真机上是这么暴露的：问两次（`nexus.llm.calls` 记到 4），
 * 再去 {@code /actuator/metrics} 找 {@code nexus.retrieval.requests}——**404，指标根本不存在**。
 * 用例这么写也是冲着这一点：断言的是"键被自增过"，而不是"某条查询发出了"，
 * 因为"检索确实发生了、只是没计数"正是需要被钉住的那种状态。
 *
 * <p>这里用 {@link ObservabilityRegistry#snapshot()} 读，而不是 {@code SimpleMeterRegistry}：
 * micrometer-core 在 nexus-common 里是 {@code optional} 依赖，本模块看不到它。
 * "键 → {@code nexus.<键>} 指标"这一段由 nexus-common 的
 * {@code ObservabilityRegistryTest} 钉着，两段合起来才是完整的一条链。
 */
class RagAnswerServiceRetrievalMetricTest {

    private static final Long USER_ID = 7L;
    private static final Long KB_ID = 1001L;
    private static final String QUESTION = "缓存击穿怎么解决？";

    private ChunkSearchService chunkSearchService;
    private ObservabilityRegistry observability;
    private RagAnswerServiceImpl ragAnswerService;
    private KnowledgeBasePO knowledgeBase;

    @BeforeEach
    void setUp() {
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        chunkSearchService = mock(ChunkSearchService.class);
        Reranker reranker = mock(Reranker.class);

        knowledgeBase = new KnowledgeBasePO();
        knowledgeBase.setId(KB_ID);
        knowledgeBase.setUserId(USER_ID);
        knowledgeBase.setRetrievalTopK(5);
        knowledgeBase.setSimilarityThreshold(0.25);
        when(knowledgeBaseService.requireOwned(USER_ID, KB_ID)).thenReturn(knowledgeBase);
        when(reranker.score(any(), anyList())).thenReturn(List.of(1.0));

        observability = new ObservabilityRegistry();
        ragAnswerService = new RagAnswerServiceImpl(
                knowledgeBaseService,
                chunkSearchService,
                reranker,
                new ContextAssembler(),
                answeringFactory(),
                mock(QaRecordMapper.class),
                mock(JsonMapper.class),
                observability,
                2000);
    }

    @Test
    @DisplayName("知识库问答（同步 ask）也要计一次检索：这条路径不经过 prepare/prepareMany")
    void syncAskCountsOneRetrieval() {
        givenRetrievalReturnsOneHit();

        ragAnswerService.ask(USER_ID, KB_ID, QUESTION, null, List.of());

        assertThat(retrievals())
                .as("同步问答是一等入口，它的检索不能被指标漏掉")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("搜了但没搜到同样算检索：'有没有命中'与'有没有搜'是两件事")
    void syncAskCountsEvenWhenNothingIsRetrieved() {
        when(chunkSearchService.search(any(), any(), anyString(), any(), any())).thenReturn(List.of());

        ragAnswerService.ask(USER_ID, KB_ID, QUESTION, null, List.of());

        assertThat(retrievals()).as("空结果是检索的结果，不是没检索").isEqualTo(1L);
    }

    @Test
    @DisplayName("对照：会话那条路径（prepare）照旧计一次，说明是'补上'而不是'搬走'")
    void prepareStillCountsOneRetrieval() {
        givenRetrievalReturnsOneHit();

        ragAnswerService.prepare(USER_ID, KB_ID, QUESTION, null, List.of());

        assertThat(retrievals()).isEqualTo(1L);
    }

    @Test
    @DisplayName("空问题不计数：那是请求参数问题，指标的定义把'根本没搜'排除在外")
    void blankQuestionIsNotCounted() {
        ragAnswerService.ask(USER_ID, KB_ID, "   ", null, List.of());

        assertThat(observability.snapshot())
                .as("没走到检索就不该有一条检索计数")
                .doesNotContainKey(ObservabilityRegistry.RETRIEVAL_REQUESTS);
    }

    // ------------------------------------------------------------------

    private long retrievals() {
        return observability.snapshot()
                .getOrDefault(ObservabilityRegistry.RETRIEVAL_REQUESTS, 0L);
    }

    private void givenRetrievalReturnsOneHit() {
        when(chunkSearchService.search(any(), any(), anyString(), any(), any())).thenReturn(List.of(
                new ChunkSearchService.SearchHit(101L, 200L, "缓存笔记", "缓存 › 第 1 节",
                        "这是一段独一无二的资料内容，编号 1。", 9.0, "hybrid", List.of("BM25 #1"))));
    }

    /** 一个总能答出带编号引用的模型（同步问答会走到生成那一步）。 */
    private ChatClientFactory answeringFactory() {
        ChatModel ok = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return ChatResponse.builder()
                        .generations(List.of(new Generation(
                                new AssistantMessage("热点 key 失效可以用互斥锁解决 [1]。"))))
                        .build();
            }
        };
        ChatClientFactory factory = mock(ChatClientFactory.class);
        when(factory.get(anyString())).thenReturn(ChatClient.builder(ok).build());
        return factory;
    }
}
