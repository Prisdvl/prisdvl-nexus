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
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JSON 列持久化往返测试。
 *
 * <p><b>为什么需要它</b>：H2 的 {@code json} 列把写入的 JSON 文本存成字符串节点
 * （{@code "[\"1\"]"}），而 {@code updateById} 走「整行读出 → 改字段 → 全列写回」，
 * 未改动的 {@code kb_ids} 也会被把已转义的形态再写回一次，逐次嵌套。
 * 表现为「改一下标题，知识库绑定就没了」。
 *
 * <p><b>这个测试类的断言方式</b>：每个关键步骤都同时查两样东西 ——
 * ① <b>原始库值</b>（绕开 MyBatis，用原生 JDBC 读），
 * ② {@link ConversationKbIds#parse} 的解析结果。
 * 只断言 ② 是不够的：万一某天出现"应用层读得对、库里还在继续嵌套"，
 * 只有 ① 能发现（这正是本类存在的意义）。
 *
 * <p>⚠️ 覆盖范围仅 H2。MySQL 的行为<b>尚未实测</b>（Docker 不可用）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("standalone")
@DisplayName("JSON 列持久化往返（H2）")
class JsonColumnRoundTripTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatConversationMapper conversationMapper;

    @Autowired
    private DataSource dataSource;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("只改 title / 只改 pinned / 连续多次 update，kbIds 都不能被写坏")
    void repeatedUpdatesKeepKbIdsIntact() throws Exception {
        String token = registerAndLogin("rt-roundtrip");
        String kbId = createKnowledgeBase(token, "往返测试库");
        String convId = createConversation(token, "RAG", List.of(kbId));

        // 建会话后就该正确
        assertParsedKbIds(convId, List.of(kbId), "刚创建");

        // 只改 title
        update(token, convId, "{\"title\":\"改了标题\"}");
        assertParsedKbIds(convId, List.of(kbId), "只改 title 后");

        // 只改 pinned
        update(token, convId, "{\"pinned\":true}");
        assertParsedKbIds(convId, List.of(kbId), "只改 pinned 后");

        // 连续多次
        for (int i = 1; i <= 4; i++) {
            update(token, convId, "{\"title\":\"第 " + i + " 次改标题\"}");
            assertParsedKbIds(convId, List.of(kbId), "第 " + i + " 次连续 update 后");
        }

        // 原始库里不能出现多层转义（这才是真正的判据）
        String raw = rawKbIdsFromDb(convId);
        assertNoNestedEscaping(raw, "连续 update 后的原始库值");
    }

    @Test
    @DisplayName("显式清空 kbIds 仍然有效，且清空结果能落库")
    void explicitClearStillWorks() throws Exception {
        String token = registerAndLogin("rt-clear");
        String kbId = createKnowledgeBase(token, "待解绑库");
        String convId = createConversation(token, "RAG", List.of(kbId));
        assertParsedKbIds(convId, List.of(kbId), "刚创建");

        update(token, convId, "{\"kbIds\":[]}");

        assertEquals(List.of(), parsedKbIds(convId), "显式清空后应为空");
        String raw = rawKbIdsFromDb(convId);
        /*
         * 断言**语义**而不是字面形态：H2 存的是 `"[]"`（字符串节点），
         * MySQL 存的是 `[]`（数组）—— 两者语义相同，都是"空数组 = 没绑定"。
         * 写死成 `assertEquals("[]", raw)` 会把正确的 H2 行为判成失败
         *（曾经就这么错判过一次）。
         */
        assertNoNestedEscaping(raw, "显式清空后的原始库值");
        assertEquals("[]", semanticJson(raw),
                "清空后库里应是空数组语义（[] 或 \"[]\"），不能是 null 或残留值");
    }

    @Test
    @DisplayName("读侧对两种形态的处理：字符串节点解包，数组节点原样保留")
    void handlerHandlesBothNodeShapes() throws Exception {
        String token = registerAndLogin("rt-shapes");
        String kbId = createKnowledgeBase(token, "形态测试库");
        String convId = createConversation(token, "RAG", List.of(kbId));

        // 形态①：H2 写出来的字符串节点 → 处理器应解成 ["id"]
        assertParsedKbIds(convId, List.of(kbId), "字符串节点形态");

        /*
         * 形态②：绕过处理器，用原生 JDBC 把这一列直接写成"已是数组"的形态，
         * 模拟 MySQL 读回来的样子 —— 处理器必须原样保留，不能误解包。
         */
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE chat_conversation SET kb_ids = ? WHERE id = ?")) {
            ps.setString(1, "[\"" + kbId + "\"]");
            ps.setLong(2, Long.parseLong(convId));
            ps.executeUpdate();
        }
        ChatConversationPO row = conversationMapper.selectOne(
                Wrappers.<ChatConversationPO>lambdaQuery().eq(ChatConversationPO::getId, convId));
        assertEquals("[\"" + kbId + "\"]", row.getKbIds(),
                "已是数组的内容必须原样保留，不能被误解包");
        assertEquals(List.of(kbId), ConversationKbIds.parse(jsonMapper, row.getKbIds()).stream()
                .map(String::valueOf).toList(), "解析结果应仍然正确");
    }

    /* ---------------- 断言辅助 ---------------- */

    /** 断言"应用层解析"与"原始库值"两件事都正确。 */
    private void assertParsedKbIds(String convId, List<String> expected, String stage) throws Exception {
        String raw = rawKbIdsFromDb(convId);
        assertNoNestedEscaping(raw, stage + "（原始库值）");

        List<String> parsed = parsedKbIds(convId);
        assertEquals(expected, parsed, stage + "（parse 结果）");
    }

    /** 解析结果（走应用层：Mapper → ConversationKbIds.parse）。 */
    private List<String> parsedKbIds(String convId) {
        ChatConversationPO row = conversationMapper.selectOne(
                Wrappers.<ChatConversationPO>lambdaQuery().eq(ChatConversationPO::getId, convId));
        assertNotNull(row, "会话不存在：" + convId);
        return ConversationKbIds.parse(jsonMapper, row.getKbIds()).stream()
                .map(String::valueOf).toList();
    }

    /**
     * 绕过 MyBatis 用原生 JDBC 读原始列值。
     *
     * <p>H2 的 json 列用 {@code getObject} 会拿到 {@code byte[]}，
     * 所以按字节读再按 UTF-8 解码。
     */
    private String rawKbIdsFromDb(String convId) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT kb_ids FROM chat_conversation WHERE id = ?")) {
            ps.setLong(1, Long.parseLong(convId));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "库里没有这一行：" + convId);
                Object v = rs.getObject(1);
                if (v == null) {
                    return null;
                }
                if (v instanceof byte[] b) {
                    return new String(b, StandardCharsets.UTF_8);
                }
                return String.valueOf(v);
            }
        }
    }

    /**
     * 原始库值不应出现"两层以上"的转义。
     *
     * <p>健康值有三种：{@code ["1"]}（数组，MySQL 形态）、
     * {@code "[\"1\"]"}（一层，H2 存储形态）。
     * 损坏值会是 {@code "\"[\\\"1\\\"]\""} 这样 ——
     * 也就是"外层是字符串节点，解一次之后还是字符串节点"。
     */
    private void assertNoNestedEscaping(String raw, String stage) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String t = raw.strip();
        // 循环解包，看几层之后才变成数组；超过 2 层就是写坏了
        int depth = 0;
        String cur = t;
        while (cur.length() >= 2 && cur.charAt(0) == '"' && cur.charAt(cur.length() - 1) == '"') {
            depth++;
            if (depth > 2) {
                break;
            }
            cur = unquote(cur);
        }
        assertTrue(depth <= 1, stage + "：库值出现多层嵌套转义，说明每次 update 都在加一层 → " + t);
    }

    /**
     * 把库值归一到"语义形态"：解掉 H2 的字符串节点外壳，返回里面真正的 JSON 文本。
 *
 * <p>用于「不该关心存储方言」的断言 —— 比如"清空后应是空数组"，
 * 写成 {@code assertEquals("[]", semanticJson(raw))} 时
 * MySQL 的 {@code []} 与 H2 的 {@code "[]"} 都能通过。
 */
private static String semanticJson(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.strip();
        // 最多解两层，够覆盖 H2 的单层包装
        for (int i = 0; i < 2; i++) {
            if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
                t = unquote(t).strip();
            } else {
                break;
            }
        }
        return t;
    }

    private static String unquote(String jsonString) {
        String body = jsonString.substring(1, jsonString.length() - 1);
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch != '\\' || i == body.length() - 1) {
                sb.append(ch);
                continue;
            }
            char next = body.charAt(++i);
            switch (next) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                default -> sb.append('\\').append(next);
            }
        }
        return sb.toString();
    }

    /* ---------------- HTTP 辅助（沿用既有测试风格） ---------------- */

    private void update(String token, String convId, String body) throws Exception {
        mockMvc.perform(put("/api/chat/conversations/" + convId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"rt123456\"}"))
                .andExpect(status().isOk());
        return JsonPath.read(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"rt123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.accessToken");
    }

    private String createKnowledgeBase(String token, String name) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"d\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id");
    }

    private String createConversation(String token, String mode, List<String> kbIds) throws Exception {
        StringBuilder body = new StringBuilder("{\"title\":\"").append(mode)
                .append(" 会话\",\"mode\":\"").append(mode).append("\"");
        if (kbIds != null && !kbIds.isEmpty()) {
            body.append(",\"kbIds\":[").append(String.join(",", kbIds)).append("]");
        }
        body.append("}");
        return JsonPath.read(mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id");
    }
}