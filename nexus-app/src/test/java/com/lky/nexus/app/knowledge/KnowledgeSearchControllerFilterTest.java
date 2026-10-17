package com.lky.nexus.app.knowledge;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.knowledge.search.SearchMode;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 检索接口的 HTTP 边界：过滤条件与参数怎么从查询串变成 {@link RetrievalFilters}。
 *
 * <p><b>为什么各层都测过了还需要这一条</b>：过滤的每一层都已经有测试
 * （解析器打真实 SQL、ES 出查询 DSL、pgvector 拼 SQL、门面派发两路），
 * 唯独"用户实际发来的那个查询串"没人测过。而这一层的失败方式恰恰最难发现：
 * 参数名写错、多值绑定没按预期拆分，结果都<b>不是报错</b>，而是过滤条件静默消失
 * 或变成一个谁也匹配不到的标签——用户看到的是"我按标签筛了，怎么还是空/全库结果"。
 * 本类第一版就靠这条测试抓到了一个真实缺陷：{@code ?tags=a,b&tags=c} 里的
 * {@code "a,b"} 不会按逗号拆，成了字面量标签（详见 {@code splitMultiValue} 的说明）。
 *
 * <p><b>为什么把 {@code ChunkSearchService} 换成 mock</b>：本类要断言的是边界映射，
 * 不是检索本身。真检索在这里必然失败——test profile 下 ES 与 pgvector 都是 Disabled 实现，
 * 会返回 4005/4004，反而让"过滤绑定对不对"淹没在"基础设施不可用"里。
 * mock 让断言退回成"服务收到的是什么"，与"服务拿它做了什么"彻底分开。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("检索接口：过滤与参数的 HTTP 绑定")
class KnowledgeSearchControllerFilterTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChunkSearchService chunkSearchService;

    @Test
    @DisplayName("多值参数：逗号分隔、重复传参、两者混用，都归一成同一个列表")
    void multiValueParametersNormalizeTheSameWay() throws Exception {
        String token = registerAndLogin("search-filter-bind");

        // 三种写法刻意混用（前端与 curl 都会真实发出这些形式），绑定结果必须一致
        assertFilters(token, b -> b.param("tags", "Redis, 算法").param("tags", "Redis")
                        .param("documentId", "7,8").param("documentId", "8"),
                new RetrievalFilters(List.of("Redis", "算法"), null, List.of(7L, 8L)));

        // 纯逗号 / 纯重复，与上面等价（顺序按首次出现）
        assertFilters(token, b -> b.param("tags", "Redis,算法"),
                new RetrievalFilters(List.of("Redis", "算法"), null, List.of()));
        assertFilters(token, b -> b.param("tags", "Redis").param("tags", "算法"),
                new RetrievalFilters(List.of("Redis", "算法"), null, List.of()));

        // 空白项被丢弃：a,,b 与 a&b 等价；空串标签不能变成"匹配不到任何东西的条件"
        assertFilters(token, b -> b.param("tags", "算法, ,Redis,"),
                new RetrievalFilters(List.of("算法", "Redis"), null, List.of()));
    }

    @Test
    @DisplayName("过滤条件里的 folder 原样透传（含子目录语义在解析器侧）")
    void folderIsPassedThrough() throws Exception {
        String token = registerAndLogin("search-filter-folder");

        assertFilters(token, b -> b.param("folder", "笔记/Redis"),
                new RetrievalFilters(List.of(), "笔记/Redis", List.of()));
    }

    @Test
    @DisplayName("没给过滤条件 → 明确的\"不过滤\"（不是 null，也不是空列表）")
    void absentFiltersBecomeNone() throws Exception {
        String token = registerAndLogin("search-filter-none");

        assertFilters(token, b -> {
        }, RetrievalFilters.none());
    }

    @Test
    @DisplayName("topK 不传 = 用知识库配置（传 null），传了就 clamp 到 1..100")
    void topKFollowsRequestThenKnowledgeBaseConfig() throws Exception {
        when(chunkSearchService.search(anyLong(), any(), any(), any(), any(), any())).thenReturn(List.of());
        String token = registerAndLogin("search-topk");

        for (String topK : new String[]{null, "250", "0"}) {
            var request = get("/api/knowledge-bases/1/search").param("q", "JVM");
            if (topK != null) {
                request = request.param("topK", topK);
            }
            mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());
        }

        ArgumentCaptor<Integer> captured = ArgumentCaptor.forClass(Integer.class);
        verify(chunkSearchService, times(3))
                .search(anyLong(), any(), any(), captured.capture(), any(), any());
        // 不传 → null 而不是某个写死的数：调用方不指定时"用哪条数"由知识库配置决定（Phase 3.2），
        // 在边界处补一个默认值等于让按库调好的参数永远不生效。越界则夹到 1..100——
        // 照做 250 条会把一次查询变成一次全库扫描。
        assertThat(captured.getAllValues()).containsExactly(null, 100, 1);
    }

    @Test
    @DisplayName("mode 缺省是 hybrid；不认识的 mode → 1001 + 400，不静默退回 hybrid")
    void modeIsParsedStrictly() throws Exception {
        String token = registerAndLogin("search-mode");

        ArgumentCaptor<SearchMode> captured = ArgumentCaptor.forClass(SearchMode.class);
        assertFilters(token, b -> {
        }, RetrievalFilters.none());
        verify(chunkSearchService, times(1))
                .search(anyLong(), any(), any(), any(), captured.capture(), any());
        assertThat(captured.getValue()).isEqualTo(SearchMode.HYBRID);

        mockMvc.perform(get("/api/knowledge-bases/1/search")
                        .param("q", "JVM")
                        .param("mode", "graph")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                // 静默退回 hybrid 会让调参的人以为自己看的是另一种模式的结果
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":1001"));
    }

    @Test
    @DisplayName("非法 documentId → 1001；缺 q → 400（都必须是客户端错误，不是 500）")
    void invalidParametersAreClientErrors() throws Exception {
        String token = registerAndLogin("search-bad-param");

        mockMvc.perform(get("/api/knowledge-bases/1/search")
                        .param("q", "JVM")
                        .param("documentId", "7,abc")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("documentId 必须是数字"));

        mockMvc.perform(get("/api/knowledge-bases/1/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());

        // 类型不对同样是客户端错误：若落进兜底处理，写错参数的调用方会收到 500，
        // 服务端还会留下一条带堆栈的 ERROR 日志——把"客户端写错"记成"服务端 BUG"
        mockMvc.perform(get("/api/knowledge-bases/1/search")
                        .param("q", "JVM")
                        .param("topK", "abc")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    /** 发一次检索请求，并断言服务收到的过滤条件。 */
    private void assertFilters(String token,
                              java.util.function.Consumer<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> customizer,
                              RetrievalFilters expected) throws Exception {
        when(chunkSearchService.search(anyLong(), any(), any(), any(), any(), any())).thenReturn(List.of());
        // 同一个测试方法里会连着发多次请求，而 mock 的调用记录在方法内是累积的：
        // 不清掉的话"这一次发了什么"会被前面的调用混淆（times(1) 直接失败）
        clearInvocations(chunkSearchService);
        var request = get("/api/knowledge-bases/1/search").param("q", "缓存击穿");
        customizer.accept(request);

        mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        ArgumentCaptor<RetrievalFilters> filters = ArgumentCaptor.forClass(RetrievalFilters.class);
        verify(chunkSearchService, times(1))
                .search(anyLong(), any(), any(), any(), any(), filters.capture());
        // RetrievalFilters 是 record：归一化写在紧凑构造器里，所以这里断言的是"归一化之后"的形态
        assertThat(filters.getValue()).isEqualTo(expected);
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","email":"%s@example.com"}
                                """.formatted(username, PASSWORD, username)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 409));

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
