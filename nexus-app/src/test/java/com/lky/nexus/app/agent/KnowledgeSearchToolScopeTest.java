package com.lky.nexus.app.agent;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.common.observe.ObservabilityRegistry;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code knowledge_search} 的<b>检索范围</b>契约（Phase 4.1）。
 *
 * <p>这是本次改动的核心风险点，也是唯一一处"改了它、用户看不出对错"的地方：
 * 范围算错的表现不是报错，而是<b>搜了不该搜的库</b>（用户以为 Agent 只答某个库的内容，
 * 实际把它全部笔记都喂给了模型），或者反过来，<b>明明绑了库却一个都没搜</b>
 * （用户以为笔记里没有，其实是范围算空了）。两种失败都不会抛异常，只能靠测试钉住。
 *
 * <p><b>为什么把 {@code ChunkSearchService} 换成 mock</b>：本类要断言的是"搜了哪些库"，
 * 不是"检索得准不准"。真检索在 test profile 下必然降级（ES 与 pgvector 都是 Disabled 实现），
 * 那会让"范围对不对"淹没在"基础设施不可用"的噪声里。mock 让断言退回成
 * "服务收到了哪些 kbId"，与"服务拿它们做了什么"彻底分开。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("knowledge_search：Agent 绑定的检索范围")
class KnowledgeSearchToolScopeTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KnowledgeSearchTool knowledgeSearchTool;

    @Autowired
    private ObservabilityRegistry observabilityRegistry;

    @MockitoBean
    private ChunkSearchService chunkSearchService;

    @Test
    @DisplayName("未绑定（kbIds 为空）= 不限制：该用户的全部库都会被搜到")
    void unscopedSearchesEveryKnowledgeBase() throws Exception {
        String token = registerAndLogin("scope-unbound");
        Long userId = currentUserId(token);
        Long first = Long.valueOf(createKnowledgeBase(token, "未绑定-一"));
        Long second = Long.valueOf(createKnowledgeBase(token, "未绑定-二"));
        returnEmptyHits();

        String result = knowledgeSearchTool.search("缓存击穿", 5, AgentContext.toolContext(userId));

        verify(chunkSearchService).search(userId, first, "缓存击穿", null, SearchMode.HYBRID);
        verify(chunkSearchService).search(userId, second, "缓存击穿", null, SearchMode.HYBRID);
        // 没有"检索范围"那一行：未绑定不是"范围为空"，而是"没有范围限制"
        assertThat(result).doesNotContain("检索范围");
    }

    @Test
    @DisplayName("绑定两个库：第三个库一次都不会被搜到")
    void scopeLimitsWhichKnowledgeBasesAreSearched() throws Exception {
        String token = registerAndLogin("scope-bound");
        Long userId = currentUserId(token);
        Long bound = Long.valueOf(createKnowledgeBase(token, "已绑定"));
        Long boundToo = Long.valueOf(createKnowledgeBase(token, "也绑定"));
        Long notBound = Long.valueOf(createKnowledgeBase(token, "未绑定"));
        returnEmptyHits();

        String result = knowledgeSearchTool.search("缓存击穿", 5,
                new ToolContext(AgentContext.of(userId, List.of(bound, boundToo), null)));

        verify(chunkSearchService).search(userId, bound, "缓存击穿", null, SearchMode.HYBRID);
        verify(chunkSearchService).search(userId, boundToo, "缓存击穿", null, SearchMode.HYBRID);
        // 这条断言就是本次改动的全部意义：范围外的库绝不能因为"顺手"被搜到
        verify(chunkSearchService, never()).search(anyLong(), org.mockito.ArgumentMatchers.eq(notBound),
                anyString(), any(), any());
        assertThat(result).contains("检索范围：本 Agent 绑定的 2 个知识库");
    }

    @Test
    @DisplayName("被禁用的库不参与检索，且明确告知跳过了谁（不静默少搜）")
    void disabledKnowledgeBaseIsSkippedAndReported() throws Exception {
        String token = registerAndLogin("scope-disabled");
        Long userId = currentUserId(token);
        Long enabled = Long.valueOf(createKnowledgeBase(token, "启用的库"));
        Long disabled = Long.valueOf(createKnowledgeBase(token, "停用的库"));
        disableKnowledgeBase(token, disabled);
        returnEmptyHits();

        String result = knowledgeSearchTool.search("缓存击穿", 5,
                new ToolContext(AgentContext.of(userId, List.of(enabled, disabled), null)));

        verify(chunkSearchService).search(userId, enabled, "缓存击穿", null, SearchMode.HYBRID);
        verify(chunkSearchService, never()).search(anyLong(), org.mockito.ArgumentMatchers.eq(disabled),
                anyString(), any(), any());
        // 如实说出来：用户手动禁用了库，就不该得到"什么东西都搜不到"这种无法解释的结果
        assertThat(result).contains("停用的库", "已禁用");
    }

    @Test
    @DisplayName("全部绑定的库都不可用时：逐条说清原因（工具只报事实，不替模型决定答不答）")
    void allBoundKnowledgeBasesUnavailableIsSaidOutLoud() throws Exception {
        String token = registerAndLogin("scope-all-gone");
        Long userId = currentUserId(token);
        Long disabled = Long.valueOf(createKnowledgeBase(token, "全禁用的库"));
        disableKnowledgeBase(token, disabled);
        returnEmptyHits();

        String result = knowledgeSearchTool.search("缓存击穿", 5,
                new ToolContext(AgentContext.of(userId, List.of(disabled, 999999999L), null)));

        assertThat(result).contains("本 Agent 绑定的知识库当前都不可用");
        assertThat(result).contains("全禁用的库", "已禁用");
        // 悬空绑定（库被删了）同样要说出来，而不是当成"这个库没内容"
        assertThat(result).contains("999999999", "已不存在");
        // 4.4 起工具不再规定"能不能用自身知识"——那是回答策略（agent.answer_policy）的事。
        // 工具与提示词各说一遍，正是把新用户挡在门外的成因（见 decisions.md B.41）
        assertThat(result).doesNotContain("不要用你自己的知识回答");
    }

    @Test
    @DisplayName("零知识库：说「你还没有任何知识库」，并明说换关键词也没用（不白烧工具调用）")
    void noKnowledgeBaseAtAllIsSaidAsSuch() throws Exception {
        String token = registerAndLogin("scope-none");
        Long userId = currentUserId(token);
        returnEmptyHits();

        String result = knowledgeSearchTool.search("缓存击穿", 5, AgentContext.toolContext(userId));

        assertThat(result).contains("你还没有任何知识库");
        // 实测里模型把 3 次工具调用全烧在空库上换关键词、白花 4 秒——这句话就是那件事的对策
        assertThat(result).contains("换词也不会有结果");
        assertThat(result).doesNotContain("不要用你自己的知识回答");
    }

    @Test
    @DisplayName("未绑定但库被全禁用：说「你的知识库当前都不可用」，并点名跳过了谁")
    void allKnowledgeBasesDisabledWhileUnscopedIsSaidOutLoud() throws Exception {
        String token = registerAndLogin("scope-all-disabled");
        Long userId = currentUserId(token);
        Long disabled = Long.valueOf(createKnowledgeBase(token, "未绑定的停用库"));
        disableKnowledgeBase(token, disabled);
        returnEmptyHits();

        String result = knowledgeSearchTool.search("缓存击穿", 5, AgentContext.toolContext(userId));

        // 未绑定时的措辞与绑定时要分开：说「本 Agent 绑定的…」会让用户去找一个不存在的绑定
        assertThat(result).contains("你的知识库当前都不可用");
        assertThat(result).contains("未绑定的停用库", "已禁用");
        assertThat(result).doesNotContain("不要用你自己的知识回答");
    }

    @Test
    @DisplayName("库可搜但没命中：说「没有匹配的资料」，且不劝退重试（换关键词仍可能有用）")
    void searchedButNoMatchIsDistinguishedFromNothingToSearch() throws Exception {
        String token = registerAndLogin("scope-no-match");
        Long userId = currentUserId(token);
        createKnowledgeBase(token, "有内容的库");
        returnEmptyHits();

        String result = knowledgeSearchTool.search("缓存击穿", 5, AgentContext.toolContext(userId));

        assertThat(result).contains("没有匹配的资料", "可以换一次关键词");
        // "换词也没用"只属于"没东西可搜"那三种情形，不能混进"搜了但没命中"
        assertThat(result).doesNotContain("换词也不会有结果");
        assertThat(result).doesNotContain("不要用你自己的知识回答");
    }

    @Test
    @DisplayName("没有可引用资料必须可计数：agent.search.empty 有增量（静默降级必须可数）")
    void emptySearchIsCounted() throws Exception {
        String token = registerAndLogin("scope-counted");
        Long userId = currentUserId(token);
        returnEmptyHits();

        long before = observabilityRegistry.snapshot()
                .getOrDefault(ObservabilityRegistry.AGENT_SEARCH_EMPTY, 0L);
        knowledgeSearchTool.search("缓存击穿", 5, AgentContext.toolContext(userId));
        long after = observabilityRegistry.snapshot()
                .getOrDefault(ObservabilityRegistry.AGENT_SEARCH_EMPTY, 0L);

        // 断言增量而不是绝对值：计数器是全局的，别的用例也会加
        assertThat(after).isGreaterThan(before);
    }

    @Test
    @DisplayName("绑定空列表等价于不限制：kbIds 的「空」只有一种含义")
    void emptyBindingsBehaveAsUnscoped() throws Exception {
        String token = registerAndLogin("scope-empty");
        Long userId = currentUserId(token);
        Long only = Long.valueOf(createKnowledgeBase(token, "唯一库"));
        returnEmptyHits();

        // 空列表 / null 都收敛成"不限制"（AgentContext.kbIds 的约定）。
        // 若这里是"绑了 0 个库谁都搜不了"，就会与"不限制"撞在一起——
        // 那时用户新建 Agent 还没绑库，工具会突然什么都不搜，且毫无提示。
        String fromEmpty = knowledgeSearchTool.search("缓存击穿", 5,
                new ToolContext(AgentContext.of(userId, List.of(), null)));
        clearInvocations(chunkSearchService);
        String fromNull = knowledgeSearchTool.search("缓存击穿", 5, AgentContext.toolContext(userId));

        verify(chunkSearchService).search(userId, only, "缓存击穿", null, SearchMode.HYBRID);
        assertThat(fromEmpty).doesNotContain("检索范围");
        assertThat(fromNull).doesNotContain("检索范围");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void returnEmptyHits() {
        when(chunkSearchService.search(anyLong(), anyLong(), anyString(), any(), any())).thenReturn(List.of());
    }

    private void disableKnowledgeBase(String token, Long kbId) throws Exception {
        mockMvc.perform(put("/api/knowledge-bases/" + kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED"}
                                """))
                .andExpect(status().isOk());
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
