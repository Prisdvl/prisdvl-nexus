package com.lky.nexus.app.agent;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.agent.mapper.ToolInvocationMapper;
import com.lky.nexus.app.agent.model.ToolInvocationStatus;
import com.lky.nexus.app.agent.model.entity.ToolInvocationPO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工具调用审计查询（roadmap 待办 #7 第 2 条：审计只写不查）。
 *
 * <p>审计行直接插库并把 HTTP 查询接口当作被测对象：这一条待办要补的就是"库里的行能不能
 * 被本人取回来"，插库比驱动一整条审批链路更贴近它，也不会把审批状态机绑进本用例。
 *
 * <p>三条边界：<b>跨用户不可见</b>（审计里带工具名/参数摘要/运行 id）、筛选条件生效、
 * 分页与"新到旧"的顺序正确。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("工具调用审计查询：GET /api/tool-invocations（按用户收口 / 筛选 / 分页）")
class ToolInvocationAuditQueryTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ToolInvocationMapper invocationMapper;

    @Test
    @DisplayName("只返回本人的审计行：另一个用户的调用一条都不出现")
    void scopedToCurrentUser() throws Exception {
        Session a = registerAndLogin("audit-scope-a");
        Session b = registerAndLogin("audit-scope-b");

        Long calculator = insert(a.userId(), 7101L, "calculator", ToolInvocationStatus.SUCCEEDED);
        insert(a.userId(), 7102L, "test_write_tool", ToolInvocationStatus.DENIED);
        Long foreign = insert(b.userId(), 7103L, "knowledge_search", ToolInvocationStatus.SUCCEEDED);

        String bodyA = list(a.token(), "");
        assertThat(total(bodyA)).as("A 只应看到自己的两行").isEqualTo(2);
        assertThat(bodyA).doesNotContain(String.valueOf(foreign));
        assertThat(bodyA).contains(String.valueOf(calculator), "calculator", "test_write_tool");

        String bodyB = list(b.token(), "");
        assertThat(total(bodyB)).as("B 只应看到自己的一行").isEqualTo(1);
        assertThat(bodyB).doesNotContain(String.valueOf(calculator));
    }

    @Test
    @DisplayName("筛选条件各自生效，且可以叠加（toolCode / status / runId）")
    void filtersApply() throws Exception {
        Session s = registerAndLogin("audit-filter");
        insert(s.userId(), 7201L, "calculator", ToolInvocationStatus.SUCCEEDED);
        insert(s.userId(), 7202L, "test_write_tool", ToolInvocationStatus.DENIED);
        insert(s.userId(), 7203L, "calculator", ToolInvocationStatus.DENIED);

        assertThat(total(list(s.token(), "?toolCode=calculator"))).isEqualTo(2);
        assertThat(total(list(s.token(), "?status=DENIED"))).isEqualTo(2);
        assertThat(total(list(s.token(), "?runId=7202"))).isEqualTo(1);
        assertThat(total(list(s.token(), "?toolCode=calculator&status=DENIED"))).isEqualTo(1);
        assertThat(total(list(s.token(), "?toolCode=calculator&status=DENIED&runId=7202")))
                .as("条件叠加后应当没有交集").isZero();
        assertThat(total(list(s.token(), "?toolCode=不存在的工具")))
                .as("不存在的筛选值只是空页，不是错误").isZero();
    }

    @Test
    @DisplayName("分页正确且新到旧：pageSize=2 时第一页是最近插入的两条")
    void pagedAndNewestFirst() throws Exception {
        Session s = registerAndLogin("audit-page");
        Long first = insert(s.userId(), 7301L, "tool-one", ToolInvocationStatus.SUCCEEDED);
        Long second = insert(s.userId(), 7302L, "tool-two", ToolInvocationStatus.SUCCEEDED);
        Long third = insert(s.userId(), 7303L, "tool-three", ToolInvocationStatus.SUCCEEDED);

        String page1 = list(s.token(), "?pageNum=1&pageSize=2");
        assertThat(total(page1)).isEqualTo(3);
        assertThat((int) JsonPath.read(page1, "$.data.pages")).isEqualTo(2);
        assertThat((java.util.List<?>) JsonPath.read(page1, "$.data.list")).hasSize(2);
        assertThat(JsonPath.read(page1, "$.data.list[0].id").toString()).isEqualTo(String.valueOf(third));
        assertThat(JsonPath.read(page1, "$.data.list[1].id").toString()).isEqualTo(String.valueOf(second));

        String page2 = list(s.token(), "?pageNum=2&pageSize=2");
        assertThat((java.util.List<?>) JsonPath.read(page2, "$.data.list")).hasSize(1);
        assertThat(JsonPath.read(page2, "$.data.list[0].id").toString()).isEqualTo(String.valueOf(first));

        // 字段确实映射出来了：摘要按原样透出，不还原成明文
        assertThat(JsonPath.read(page2, "$.data.list[0].argumentsDigest").toString())
                .isEqualTo("digest-tool-one");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String list(String token, String query) throws Exception {
        return mockMvc.perform(get("/api/tool-invocations" + query)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private int total(String body) {
        return ((Number) JsonPath.read(body, "$.data.total")).intValue();
    }

    private Long insert(Long userId, Long runId, String toolCode, ToolInvocationStatus status) {
        ToolInvocationPO row = new ToolInvocationPO();
        row.setUserId(userId);
        row.setRunId(runId);
        row.setToolCode(toolCode);
        row.setPermission("READ");
        row.setStatus(status.name());
        row.setArgumentsDigest("digest-" + toolCode);
        row.setStartedAt(LocalDateTime.now());
        invocationMapper.insert(row);
        return row.getId();
    }

    private Session registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s","email":"%s@example.com"}
                        """.formatted(username, PASSWORD, username)));
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Session(JsonPath.read(body, "$.data.accessToken"),
                Long.valueOf(JsonPath.read(body, "$.data.user.id").toString()));
    }

    private record Session(String token, Long userId) {
    }
}
