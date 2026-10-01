package com.lky.nexus.knowledge.rag;

import com.lky.nexus.ai.error.FailureClassifier;
import com.lky.nexus.ai.model.ChatClientFactory;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import tools.jackson.databind.json.JsonMapper;

/**
 * 同步 RAG 问答"生成失败"时到底返回什么（Phase 5.2 / P1-7）。
 *
 * <p><b>修之前的行为</b>：{@code catch (Exception ex) → return "生成回答失败：" + ex.getMessage()}。
 * 也就是说，模型网关 401 之后，这个接口会返回 <b>HTTP 200 + 一段"回答"</b>，
 * 而且这段"回答"会被当作真实问答落进 {@code kb_qa_record}。三处伤害都写在那段代码的注释里，
 * 这个测试逐条钉住——因为它们都不是"看起来的错"：
 * <ol>
 *   <li><b>成败无法用状态码判断</b>：调用方只能去匹配中文字符串；</li>
 *   <li><b>上游原文外泄</b>：网关地址、报错体原样进到用户界面；</li>
 *   <li><b>留档被污染</b>：问答记录里多出一条永远答不上来的"回答"。</li>
 * </ol>
 *
 * <p>这里不连数据库也不发网络请求：检索层与模型都换成替身，让失败发生在
 * "模型调用"这一步——那正是要验的位置。
 */
@DisplayName("同步 RAG 生成失败：抛业务异常 + 不落档 + 不漏上游原文")
class RagAnswerServiceFailureTest {

    private static final Long USER_ID = 7L;
    private static final Long KB_ID = 1001L;
    private static final String QUESTION = "缓存击穿是什么？";

    /** 上游网关的原话：修之前它会出现在返回给用户的"回答"里。 */
    private static final String UPSTREAM_TEXT = "401: Invalid API key. (base-url=https://internal-gateway.example)";

    private ChunkSearchService chunkSearchService;
    private Reranker reranker;
    private QaRecordMapper qaRecordMapper;
    private RagAnswerServiceImpl ragAnswerService;

    @BeforeEach
    void setUp() {
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        chunkSearchService = mock(ChunkSearchService.class);
        reranker = mock(Reranker.class);
        qaRecordMapper = mock(QaRecordMapper.class);

        KnowledgeBasePO knowledgeBase = new KnowledgeBasePO();
        knowledgeBase.setId(KB_ID);
        knowledgeBase.setUserId(USER_ID);
        knowledgeBase.setWorkspaceId(9L);
        knowledgeBase.setRetrievalTopK(5);
        knowledgeBase.setSimilarityThreshold(0.25);
        when(knowledgeBaseService.requireOwned(USER_ID, KB_ID)).thenReturn(knowledgeBase);

        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new RuntimeException(UPSTREAM_TEXT);
            }
        };
        ChatClientFactory factory = mock(ChatClientFactory.class);
        when(factory.get(anyString())).thenReturn(ChatClient.builder(failing).build());

        ragAnswerService = new RagAnswerServiceImpl(
                knowledgeBaseService,
                chunkSearchService,
                reranker,
                new ContextAssembler(),
                factory,
                qaRecordMapper,
                mock(JsonMapper.class),
                new ObservabilityRegistry(),
                2000);
    }

    @Test
    @DisplayName("生成失败要抛 4001 业务异常，而不是把失败当成一句回答返回")
    void generationFailureThrowsInsteadOfReturningAnAnswer() {
        givenRetrievalReturnsOneHit();

        assertThatThrownBy(() -> ragAnswerService.ask(USER_ID, KB_ID, QUESTION, null, List.of()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE.getCode());
                    assertThat(ex.getMessage()).isEqualTo(FailureClassifier.MODEL_FAILED_MESSAGE);
                    // ② 上游原文一个字都不能出现在给用户的话里（原始异常留给日志与 cause）
                    assertThat(ex.getMessage()).doesNotContain("Invalid API key")
                            .doesNotContain("internal-gateway");
                    assertThat(ex.getCause()).isNotNull();
                });
    }

    @Test
    @DisplayName("失败不落档：问答留档只记录真实发生过的问答")
    void failureIsNotArchived() {
        givenRetrievalReturnsOneHit();

        assertThatThrownBy(() -> ragAnswerService.ask(USER_ID, KB_ID, QUESTION, null, List.of()))
                .isInstanceOf(BusinessException.class);

        // ③ 修之前这里会 insert 一条"生成回答失败：…"的记录，把留档变成不可信
        verify(qaRecordMapper, never()).insert(any(QaRecordPO.class));
    }

    @Test
    @DisplayName("对照：检索到了资料、模型也正常时必须落档（免得上面的断言靠'根本不落档'蒙对）")
    void successIsStillArchived() {
        ChatModel ok = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return ChatResponse.builder()
                        .generations(List.of(new org.springframework.ai.chat.model.Generation(
                                new org.springframework.ai.chat.messages.AssistantMessage(
                                        "缓存击穿是指热点 key 失效瞬间大量请求打到数据库 [1]。"))))
                        .build();
            }
        };
        ChatClientFactory factory = mock(ChatClientFactory.class);
        when(factory.get(anyString())).thenReturn(ChatClient.builder(ok).build());
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        KnowledgeBasePO knowledgeBase = new KnowledgeBasePO();
        knowledgeBase.setId(KB_ID);
        knowledgeBase.setUserId(USER_ID);
        knowledgeBase.setRetrievalTopK(5);
        knowledgeBase.setSimilarityThreshold(0.25);
        when(knowledgeBaseService.requireOwned(USER_ID, KB_ID)).thenReturn(knowledgeBase);
        RagAnswerServiceImpl service = new RagAnswerServiceImpl(
                knowledgeBaseService, chunkSearchService, reranker, new ContextAssembler(),
                factory, qaRecordMapper, mock(JsonMapper.class), new ObservabilityRegistry(), 2000);
        givenRetrievalReturnsOneHit();

        RagAnswer answer = service.ask(USER_ID, KB_ID, QUESTION, null, List.of());

        assertThat(answer.answer()).contains("缓存击穿");
        verify(qaRecordMapper).insert(any(QaRecordPO.class));
    }

    // ------------------------------------------------------------------

    private void givenRetrievalReturnsOneHit() {
        when(chunkSearchService.search(any(), any(), anyString(), any(), any())).thenReturn(List.of(
                new ChunkSearchService.SearchHit(101L, 200L, "缓存笔记", "缓存 › 第 1 节",
                        "这是一段独一无二的资料内容，编号 1。", 9.0, "hybrid", List.of("BM25 #1"))));
        when(reranker.score(any(), anyList())).thenReturn(List.of(1.0));
    }
}
