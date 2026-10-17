package com.lky.nexus.app.agent;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.search.SearchMode;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code knowledge_search} 的<b>逐库并行与失败隔离</b>（Phase 5.5.1）。
 *
 * <p>改造前的串行 for 有两个问题，测试要分别钉住：
 * <ol>
 *   <li><b>一个库卡住 = 整个工具卡住</b>（没有超时，也没人接管）；</li>
 *   <li>并行之后，痕迹可能从工作线程上写出去——而 {@code AgentContext.record} 最终会推到
 *       SSE emitter，那一层<b>不是线程安全的</b>。</li>
 * </ol>
 *
 * <p>第二个问题正是本类最要紧的那条断言：所有痕迹都必须在<b>同一个线程</b>上回放，
 * 而且必须是调用线程。只断言"痕迹内容对"是不够的——并发写在测试里往往<b>照样通过</b>
 * （{@code ArrayList} 的并发 add 多数时候不炸，只是偶尔丢一条或下标错乱），
 * 到了生产才变成"卡片上少了一条"这种没人能复现的现象。所以这里断言的是<b>线程身份</b>。
 *
 * <p>另外两条守住"降级阶梯没有被并行改造改掉"：一个库不可用时其余库照常贡献，
 * 以及既有的一级降级（LIKE 关键词匹配）在任务体里原样保留。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("knowledge_search：多库并行与逐库失败隔离")
class KnowledgeSearchToolFanoutTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KnowledgeSearchTool knowledgeSearchTool;

    @Autowired
    private ObservabilityRegistry observabilityRegistry;

    @Autowired
    private ChunkMapper chunkMapper;

    @MockitoBean
    private ChunkSearchService chunkSearchService;

    @Test
    @DisplayName("一个库不可用：其余库的结果照常返回，且如实记一条『检索不可用』")
    void failingKnowledgeBaseDoesNotTakeTheOthersDown() throws Exception {
        String token = registerAndLogin("fanout-one-bad");
        Long userId = currentUserId(token);
        Long good = Long.valueOf(createKnowledgeBase(token, "正常库"));
        Long bad = Long.valueOf(createKnowledgeBase(token, "故障库"));

        when(chunkSearchService.search(anyLong(), eq(good), anyString(), any(), any()))
                .thenReturn(List.of(hit(good, 101L, "正常库里的资料，讲的是缓存击穿。")));
        when(chunkSearchService.search(anyLong(), eq(bad), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("ES 连接被拒绝"));

        Map<String, Long> before = observabilityRegistry.snapshot();
        List<AgentContext.ToolCallRecord> records = new ArrayList<>();
        ToolContext context = new ToolContext(
                AgentContext.of(userId, List.of(good, bad), AgentContext.collector(records)));

        String result = knowledgeSearchTool.search("缓存击穿", 5, context);

        // 其余库的资料照常进入模型可见的结果：一个库坏了不该让另一个库的资料一起作废
        assertThat(result).contains("正常库里的资料", "检索到 1 条结果");
        assertThat(records).anySatisfy(record ->
                assertThat(record.resultSummary()).contains("故障库", "检索不可用", "ES 连接被拒绝"));
        assertThat(delta(before, ObservabilityRegistry.AGENT_SEARCH_UNAVAILABLE))
                .as("工具侧的降级同样不能静默")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("一个库的精确检索不可用：该库降级为关键词匹配，其余库不受影响")
    void degradedKnowledgeBaseFallsBackToKeywordMatch() throws Exception {
        String token = registerAndLogin("fanout-degraded");
        Long userId = currentUserId(token);
        Long good = Long.valueOf(createKnowledgeBase(token, "精确检索正常"));
        Long bad = Long.valueOf(createKnowledgeBase(token, "只能关键词匹配"));

        // 故障库里放一条能被 LIKE 命中的切片：降级路径是"库里真有资料"时的兜底，不是空手套
        ChunkPO chunk = new ChunkPO();
        chunk.setId(880001L);
        chunk.setKbId(bad);
        chunk.setDocumentId(990001L);
        chunk.setUserId(userId);
        chunk.setChunkIndex(0);
        chunk.setChunkType("PARAGRAPH");
        chunk.setContent("缓存击穿的兜底方案：先加互斥锁，再考虑逻辑过期。");
        chunk.setContentHash("hash-fanout-degraded");
        chunk.setHeadingPath("Redis > 缓存");
        chunk.setCreatedAt(LocalDateTime.now());
        chunkMapper.insert(chunk);

        when(chunkSearchService.search(anyLong(), eq(good), anyString(), any(), any()))
                .thenReturn(List.of(hit(good, 102L, "来自正常库的资料。")));
        when(chunkSearchService.search(anyLong(), eq(bad), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("ES 连接被拒绝"));

        Map<String, Long> before = observabilityRegistry.snapshot();
        List<AgentContext.ToolCallRecord> records = new ArrayList<>();
        String result = knowledgeSearchTool.search("缓存击穿", 5, new ToolContext(
                AgentContext.of(userId, List.of(good, bad), AgentContext.collector(records))));

        assertThat(result).as("降级路径的资料要能被模型看到").contains("互斥锁");
        assertThat(records).anySatisfy(record ->
                assertThat(record.resultSummary()).contains("只能关键词匹配", "降级为关键词匹配"));
        assertThat(delta(before, ObservabilityRegistry.AGENT_SEARCH_DEGRADED)).isEqualTo(1L);
        assertThat(delta(before, ObservabilityRegistry.AGENT_SEARCH_UNAVAILABLE))
                .as("降级成功不算『不可用』——两档必须分开计数，否则巡检看不出恶化")
                .isZero();
    }

    @Test
    @DisplayName("两个库都失败：两条痕迹都在调用线程上回放（不能并发写 SSE 监听器）")
    void allRecordsAreReplayedOnTheCallingThread() throws Exception {
        String token = registerAndLogin("fanout-thread");
        Long userId = currentUserId(token);
        Long first = Long.valueOf(createKnowledgeBase(token, "线程-一"));
        Long second = Long.valueOf(createKnowledgeBase(token, "线程-二"));
        when(chunkSearchService.search(anyLong(), anyLong(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("ES 连接被拒绝"));

        Thread caller = Thread.currentThread();
        List<AgentContext.ToolCallRecord> records = new ArrayList<>();
        Set<Thread> recordThreads = ConcurrentHashMap.newKeySet();
        AgentContext.ToolCallListener listener = new AgentContext.ToolCallListener() {
            @Override
            public void onCall(String tool, String input) {
            }

            @Override
            public void onResult(AgentContext.ToolCallRecord record) {
                recordThreads.add(Thread.currentThread());
                records.add(record);
            }
        };

        knowledgeSearchTool.search("缓存击穿", 5,
                new ToolContext(AgentContext.of(userId, List.of(first, second), listener)));

        assertThat(records)
                .as("两个库各一条失败痕迹，外加末尾一条汇总——三条的顺序在每次运行里都必须一致")
                .hasSize(3);
        assertThat(recordThreads)
                .as("痕迹必须全部由调用线程回放：AgentContext.collector 的 sink 是普通 ArrayList，"
                        + "而流式路径的监听器会直接推 SSE")
                .containsExactly(caller);
        assertThat(caller.isVirtual())
                .as("测试线程是平台线程——若这条断言变了，说明执行模型整体变了")
                .isFalse();
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private long delta(Map<String, Long> before, String key) {
        return observabilityRegistry.snapshot().getOrDefault(key, 0L) - before.getOrDefault(key, 0L);
    }

    private ChunkSearchService.SearchHit hit(Long kbId, Long chunkId, String content) {
        return new ChunkSearchService.SearchHit(chunkId, 900L, "缓存笔记", "Redis > 缓存",
                content, 0.9, "hybrid", List.of("BM25 #1"), kbId);
    }

    private String createKnowledgeBase(String token, String name) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id");
    }

    private Long currentUserId(String token) throws Exception {
        return Long.valueOf(JsonPath.read(mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id"));
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s","email":"%s@example.com"}
                        """.formatted(username, PASSWORD, username)));

        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(loginBody, "$.data.accessToken");
    }
}
