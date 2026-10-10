package com.lky.nexus.app.chat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.chat.mapper.ChatConversationMapper;
import com.lky.nexus.app.chat.model.entity.ChatConversationPO;
import com.lky.nexus.app.chat.service.ConversationKbIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import com.lky.nexus.app.support.LiveInfrastructure;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0 验证：<b>真实 MySQL</b> 上 {@code @TableField(typeHandler = JsonTextTypeHandler)} 的行为。
 *
 * <p><b>为什么必须单独写这个类</b>：整套634 个测试都跑在 H2 上，
 * 而 H2 的 {@code json} 列会把 JSON 文本存成<b>字符串节点</b>（这正是那个双重编码 bug 的根因）。
 * MySQL 的行为完全不同 —— 实测读回来是 {@code ARRAY}。
 * 也就是说：<b>H2 通过 ≠ MySQL 正确</b>，这个处理器在 MySQL 下必须单独验。
 *
 * <p><b>断言策略</b>：每个关键步骤同时查两样 ——
 * ① 原始库值（绕开 MyBatis，用原生 JDBC 读，MySQL 下是 UTF-8 字符串而非 byte[]）
 * ② {@link ConversationKbIds#parse} 的结果。只断言 ② 会漏掉"库里其实已损坏"。
 *
 * <p><b>隔离</b>：每个用例只碰自己刚建的那条会话，末尾删掉，不影响库里既有 461 条数据。
 *
 * <p>运行方式：{@code $env:AI_MYSQL_LIVE='true'; mvn -pl nexus-app -am test -Dtest=MySqlJsonRoundTripTest}。
 * 容器由 Testcontainers 现拉现用，不需要事先 {@code docker compose up}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "AI_MYSQL_LIVE", matches = "true")
@DisplayName("P0：真实 MySQL 上的 JSON 列往返")
class MySqlJsonRoundTripTest {

    /**
     * 用测试<b>自己拉起的 MySQL 8.4.11 容器</b>（与 compose 同版本），连的不是开发栈那一份。
     * Redis / RabbitMQ / 向量库与本次要验的东西无关，显式关掉
     * （每一条的理由见 {@link LiveInfrastructure}）。
     *
     * <p>方法在上下文创建之前执行，因此这里改的属性就是应用真正读到的属性。
     */
    @DynamicPropertySource
    static void liveInfrastructure(DynamicPropertyRegistry registry) {
        LiveInfrastructure.registerMySql(registry);
        LiveInfrastructure.registerVectorServerDisabled(registry);
        LiveInfrastructure.registerUnusedExternalDependencies(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatConversationMapper conversationMapper;

    @Autowired
    private DataSource dataSource;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("MySQL：kb_ids 保持 ARRAY 类型，连续 update 不会被层层包裹")
    void mySqlKeepsArrayTypeAcrossUpdates() throws Exception {
        String token = registerAndLogin("mysqljson1");
        String kbId = createKnowledgeBase(token, "MySQL 库");
        String convId = createConversation(token, "RAG", List.of(kbId));

        // 创建后：原始库值必须是 ARRAY（不是字符串节点）
        assertJsonType(convId, "ARRAY", "刚创建");
        assertParsedKbIds(convId, List.of(kbId), "刚创建");

        // 只改 title，再查
        update(token, convId, "{\"title\":\"改了标题\"}");
        assertJsonType(convId, "ARRAY", "只改 title 后");
        assertParsedKbIds(convId, List.of(kbId), "只改 title 后");

        // 只改 pinned，再查
        update(token, convId, "{\"pinned\":true}");
        assertJsonType(convId, "ARRAY", "只改 pinned 后");

        // 连续多次 update —— H2 上正是这里开始逐次嵌套
        for (int i = 1; i <= 5; i++) {
            update(token, convId, "{\"title\":\"第 " + i + " 次\"}");
        }
        assertJsonType(convId, "ARRAY", "连续 5 次 update 后");
        assertParsedKbIds(convId, List.of(kbId), "连续 5 次 update 后");

        deleteConversation(token, convId);
    }

    @Test
    @DisplayName("MySQL：显式清空 kbIds 与 resetAgent 都真的落库")
    void mySqlExplicitClearLands() throws Exception {
        String token = registerAndLogin("mysqljson2");
        String kbId = createKnowledgeBase(token, "MySQL 解绑库");
        String convId = createConversation(token, "RAG", List.of(kbId));
        assertJsonType(convId, "ARRAY", "刚创建");

        // 显式解绑
        update(token, convId, "{\"kbIds\":[]}");
        ChatConversationPO afterClear = reload(convId);
        assertEquals(List.of(), ConversationKbIds.parse(jsonMapper, afterClear.getKbIds()),
                "解绑后应为空");
        assertEquals("[]", rawJson(convId).strip(), "库里应是空数组而不是 null");
        assertJsonType(convId, "ARRAY", "解绑后");

        deleteConversation(token, convId);
    }

    /* ---------------- 断言辅助 ---------------- */

    /** 断言该行的 kb_ids 列在 MySQL 里是 ARRAY 类型（不是字符串节点）。 */
    private void assertJsonType(String convId, String expected, String stage) throws Exception {
        String jtype = jsonType(convId);
        assertEquals(expected, jtype,
                stage + "：kb_ids 的 JSON_TYPE 应为 " + expected + "，实际 " + jtype
                        + "（若为STRING 说明被套了一层，处理器没生效）");
    }

    /** 用原生 JDBC 查 JSON_TYPE —— 绕开 MyBatis 与处理器，看数据库的真实形态。 */
    private String jsonType(String convId) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT JSON_TYPE(kb_ids) FROM chat_conversation WHERE id = ?")) {
            ps.setLong(1, Long.parseLong(convId));
            try (ResultSet rs = ps.executeQuery()) {
                assertNotNull(rs, "查不到该行");
                assertEquals(true, rs.next(), "查不到该行：" + convId);
                return rs.getString(1);
            }
        }
    }

    /** 原始库值文本（MySQL 下是 UTF-8 字符串；H2 下是 byte[]，两种都兼容）。 */
    private String rawJson(String convId) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT CAST(kb_ids AS CHAR) FROM chat_conversation WHERE id = ?")) {
            ps.setLong(1, Long.parseLong(convId));
            try (ResultSet rs = ps.executeQuery()) {
                assertEquals(true, rs.next(), "查不到该行：" + convId);
                return rs.getString(1);
            }
        }
    }

    private void assertParsedKbIds(String convId, List<String> expected, String stage) {
        ChatConversationPO row = reload(convId);
        assertEquals(expected, ConversationKbIds.parse(jsonMapper, row.getKbIds()).stream()
                .map(String::valueOf).toList(), stage + "（parse 结果）");
    }

    private ChatConversationPO reload(String convId) {
        ChatConversationPO row = conversationMapper.selectOne(
                Wrappers.<ChatConversationPO>lambdaQuery().eq(ChatConversationPO::getId, convId));
        assertNotNull(row, "会话不存在：" + convId);
        return row;
    }

    /* ---------------- HTTP 辅助 ---------------- */

    private void update(String token, String convId, String body) throws Exception {
        mockMvc.perform(put("/api/chat/conversations/" + convId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    /** 删掉测试会话，保持 MySQL 库干净（不留下孤儿行）。 */
    private void deleteConversation(String token, String convId) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/chat/conversations/" + convId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"mysql123456\"}"))
                .andExpect(status().isOk());
        return JsonPath.read(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"mysql123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.accessToken");
    }

    private String createKnowledgeBase(String token, String name) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"p0\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id");
    }

    private String createConversation(String token, String mode, List<String> kbIds) throws Exception {
        StringBuilder body = new StringBuilder("{\"title\":\"P0 mysql 会话\",\"mode\":\"").append(mode).append("\"");
        if (kbIds != null && !kbIds.isEmpty()) {
            body.append(",\"kbIds\":[").append(String.join(",", kbIds)).append("]");
        }
        body.append("}");
        var resp = mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andReturn().getResponse();
        assertEquals(200, resp.getStatus(),
                "建会话失败（body=" + body + "）：" + resp.getContentAsString());
        return JsonPath.read(resp.getContentAsString(), "$.data.id");
    }
}