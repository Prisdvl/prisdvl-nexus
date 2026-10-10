package com.lky.nexus.app.chat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.chat.mapper.ChatConversationMapper;
import com.lky.nexus.app.chat.mapper.ChatMessageMapper;
import com.lky.nexus.app.chat.model.entity.ChatConversationPO;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.model.entity.DocumentPO;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JSON 列往返回归防护（覆盖所有已挂 {@code JsonTextTypeHandler} 的列）。
 *
 * <p><b>为什么必须有这个类</b>：H2 的 {@code json} 列把 JSON 文本存成字符串节点，
 * 而 {@code updateById} 走「整行读出 → 改字段 → 全列写回」，
 * 未改动的 JSON 列也会被把已转义的形态再写回一次，<b>每次 update 套一层</b>。
 * 实测（修复前）：len 61 → 85 → 133 → 229，<b>指数增长</b>；
 * 而解析侧只做单层解包，套两层以上就解不出来 → 引用面板与工具痕迹静默消失。
 *
 * <p><b>断言方式</b>：既查应用层解析结果，也用原生 JDBC 查<b>原始库值</b>。
 * 只查解析结果会漏掉"库里其实已损坏、只是还能解出来一层"的情况。
 *
 * <p><b>覆盖的列</b>：
 * <ul>
 *   <li>{@code chat_conversation.kb_ids}（会话绑定知识库）</li>
 *   <li>{@code chat_message.metadata}（工具痕迹 + 引用明细）</li>
 *   <li>{@code kb_document.frontmatter} / {@code heading_tree} / {@code tags}（{@link #documentJsonColumnsDoNotNest}）</li>
 * </ul>
 *
 * @see com.lky.nexus.app.infra.mybatis.JsonTextTypeHandler
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("JSON 列往返：多次 update 不被套层")
class JsonColumnNoNestingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatMessageMapper messageMapper;

    @Autowired
    private ChatConversationMapper conversationMapper;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("chat_message.metadata 连改 5 次长度不增长（修复前是指数增长）")
    void metadataDoesNotNestAcrossUpdates() throws Exception {
        String token = registerAndLogin("jsonnest");
        String convId = createConversation(token);
        Long userId = currentUserId(token);

        String metadata = "{\"toolCalls\":[{\"name\":\"search\",\"status\":\"done\"}],"
                + "\"citations\":[{\"docId\":\"d1\",\"score\":0.93}]}";
        Long msgId = seedMessage(convId, userId, metadata);

        /*
         * 判据说明（这里踩过一次坑，别改回去）：
         * 用原生 JDBC 读到的是 **H2 的转义形态** `"{\"a\":1}"`，
         * 而处理器解包后实体里是**规范 JSON** `{"a":1}`。
         * 两者长度本就该不同（差值 = 转义开销），所以**不能拿原始库值去比期望的规范长度**。
         *
         * 真正的判据是：**原始库值的"套层次数"不增长** ——
         * 每套一层，转义就要多一层，长度必然单调变长。所以以首次读到的原始值为基准，
         * 断言后续每次 update 后长度**不变**即可。
         */
        int baseline = rawMetadata(msgId).length();

        for (int i = 1; i <= 5; i++) {
            ChatMessagePO row = messageMapper.selectById(msgId);
            messageMapper.updateById(row);
            String now = rawMetadata(msgId);
            assertEquals(baseline, now.length(),
                    "第 " + i + " 次 update 后 metadata 长度变了 → 又套了一层。实际值= " + now);
        }

        // 应用层解析：单层解包必须能解出工具痕迹与引用
        ChatMessagePO row = messageMapper.selectById(msgId);
        assertTrue(row.getMetadata().contains("toolCalls"),
                "解析后应仍含 toolCalls，实际= " + row.getMetadata());
        assertTrue(row.getMetadata().contains("citations"),
                "解析后应仍含 citations，实际= " + row.getMetadata());
    }

    @Test
    @DisplayName("chat_conversation.kb_ids 连改 5 次不被套层")
    void kbIdsDoesNotNestAcrossUpdates() throws Exception {
        String token = registerAndLogin("jsonnestkb");
        String kbId = createKnowledgeBase(token, "套层测试库");
        String convId = createConversation(token, "RAG", kbId);

        // 基准取首次读到的原始库值长度，判据是"不增长"（理由见 metadata 用例的注释）
        int baseline = rawKbIds(convId).length();

        // 只改 title，每次 update 都会整行读写
        for (int i = 1; i <= 5; i++) {
            update(token, convId, "{\"title\":\"第 " + i + " 次改\"}");
            String now = rawKbIds(convId);
            assertEquals(baseline, now.length(),
                    "第 " + i + " 次 update 后 kb_ids 长度变了 → 又套了一层。实际值= " + now);
        }
    }

    /**
     * {@code kb_document} 的三个 JSON 列连改 5 次不被套层。
     *
     * <p>用 Vault 导入造数据（{@code frontmatter} / {@code heading_tree} / {@code tags}
     * 都由导入流程写入），再对文档行反复 {@code updateById}。
     *
     * <p>⚠️ 这三列的处理器在 <b>{@code nexus-knowledge} 模块</b>里
     * （{@code com.lky.nexus.knowledge.mapper.JsonTextTypeHandler}），
     * 与应用层那份是<b>两份独立实现</b> —— 因为模块依赖方向是 app → knowledge，
     * 处理器放在 app 侧则 knowledge 无法反向引用它。
     */
    @Test
    @DisplayName("kb_document 的 frontmatter/heading_tree/tags 连改 5 次不被套层")
    void documentJsonColumnsDoNotNest() throws Exception {
        String token = registerAndLogin("jsonnestdoc");
        String kbId = createKnowledgeBase(token, "文档套层测试库");
        importVault(token, kbId);

        Long docId = firstDocumentId(kbId);
        int fmBaseline = rawDocColumn(docId, "frontmatter").length();
        int htBaseline = rawDocColumn(docId, "heading_tree").length();

        for (int i = 1; i <= 5; i++) {
            DocumentPO row = documentMapper.selectById(docId);
            documentMapper.updateById(row);

            assertEquals(fmBaseline, rawDocColumn(docId, "frontmatter").length(),
                    "第 " + i + " 次 update 后 frontmatter 长度变了 → 又套了一层");
            assertEquals(htBaseline, rawDocColumn(docId, "heading_tree").length(),
                    "第 " + i + " 次 update 后 heading_tree 长度变了 → 又套了一层");
        }

        // 应用层读出来应是规范 JSON（不是被转义的形态）
        DocumentPO row = documentMapper.selectById(docId);
        assertNotNull(row.getFrontmatter(), "frontmatter 不该为 null");
        assertTrue(row.getFrontmatter().startsWith("{"),
                "frontmatter 应是规范 JSON 对象，实际= " + row.getFrontmatter());
        assertNotNull(row.getHeadingTree(), "heading_tree 不该为 null");
        assertTrue(row.getHeadingTree().startsWith("["),
                "heading_tree 应是规范 JSON 数组，实际= " + row.getHeadingTree());
    }

    /* ---------------- 断言辅助：绕开 MyBatis 读原始库值 ---------------- */

    /** 读 kb_document 的某个 JSON 列原始值（绕开处理器，看数据库真实形态）。 */
    private String rawDocColumn(Long docId, String column) throws Exception {
        return readJson("SELECT " + column + " FROM kb_document WHERE id = ?", docId);
    }

    private Long firstDocumentId(String kbId) {
        DocumentPO doc = documentMapper.selectOne(
                Wrappers.<DocumentPO>lambdaQuery().eq(DocumentPO::getKbId, kbId));
        assertNotNull(doc, "导入后应有文档");
        return doc.getId();
    }

    /**
     * 造一个最小 Vault 并导入。
     *
     * <p>导入流程会解析 frontmatter（YAML 头部）与标题树并以 JSON 落库，
     * 这正是要验证的两列的来源。
     */
    private void importVault(String token, String kbId) throws Exception {
        String md = """
                ---
                title: Json RoundTrip
                tags:
                  - java
                  - json
                ---

                # Json RoundTrip

                ## 第一节

                正文。

                ## 第二节

                更多正文。
                """;
        byte[] zipBytes;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("MyVault/Java/Json.md"));
            zip.write(md.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            zipBytes = out.toByteArray();
        }
        mockMvc.perform(multipart("/api/knowledge-bases/" + kbId + "/vault")
                        .file(new MockMultipartFile("file", "vault.zip", "application/zip", zipBytes))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().is2xxSuccessful());
    }

    /**
     * 用原生 JDBC 读 JSON 列。
     *
     * <p>⚠️ H2 的 {@code json} 列用 {@code getObject} 会拿到 {@code byte[]}，
     * 所以按字节读再按 UTF-8 解码；MySQL 下则是字符串。两种都兼容。
     */
    private String readJson(String sql, Long id) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "查不到该行：" + id);
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

    private String rawMetadata(Long msgId) throws Exception {
        return readJson("SELECT metadata FROM chat_message WHERE id = ?", msgId);
    }

    private String rawKbIds(String convId) throws Exception {
        return readJson("SELECT kb_ids FROM chat_conversation WHERE id = ?", Long.parseLong(convId));
    }

    /* ---------------- 数据准备 ---------------- */

    private Long seedMessage(String convId, Long userId, String metadata) {
        ChatMessagePO m = new ChatMessagePO();
        m.setConversationId(Long.parseLong(convId));
        m.setUserId(userId);
        m.setRole("bot");
        m.setContent("往返测试消息");
        m.setStatus("DONE");
        messageMapper.insert(m);
        // insert 时 null 字段不进 SQL，所以 metadata 要单独补一次
        messageMapper.update(null, Wrappers.<ChatMessagePO>lambdaUpdate()
                .eq(ChatMessagePO::getId, m.getId())
                .set(ChatMessagePO::getMetadata, metadata));
        return m.getId();
    }

    private void update(String token, String convId, String body) throws Exception {
        mockMvc.perform(put("/api/chat/conversations/" + convId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private Long currentUserId(String token) throws Exception {
        String me = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(me, "$.data.id"));
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"json123456\"}"))
                .andExpect(status().isOk());
        return JsonPath.read(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"json123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.accessToken");
    }

    private String createKnowledgeBase(String token, String name) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"json\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id");
    }

    private String createConversation(String token, String mode, String kbId) throws Exception {
        String body = "{\"title\":\"往返测试\",\"mode\":\"" + mode + "\""
                + (kbId == null ? "" : ",\"kbIds\":[" + kbId + "]")
                + "}";
        return JsonPath.read(mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id");
    }

    private String createConversation(String token) throws Exception {
        return createConversation(token, "PLAIN", null);
    }
}