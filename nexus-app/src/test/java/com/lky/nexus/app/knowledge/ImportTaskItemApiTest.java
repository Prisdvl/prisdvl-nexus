package com.lky.nexus.app.knowledge;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 导入明细接口（Phase 5.3 · P1-3 / P1-4）：{@code GET /api/import-tasks/{id}/items}。
 *
 * <p>它补的是导入功能里最别扭的一个缺口：{@code kb_import_task_item} 这张表从建立起
 * <b>只有写入方、没有任何读接口</b>。于是"失败 3 个"是一个没有下文的数字，
 * 用户只能去翻服务端日志——而这张表存在的全部意义就是回答"为什么这篇没进来"
 * （它自己的类注释就是这么写的）。
 *
 * <p>顺带补上的那个洞更隐蔽：<b>扫描期被跳过的条目从来没被记录过</b>。
 * 执行器只做了 {@code counters.skipped += scan.skipped().size()}，于是
 * {@code ImportAction.SKIPPED} 与 {@code Counters} 里的 {@code case SKIPPED} 一直是死代码——
 * 而"我的 .pptx 为什么没进来"恰恰是用户最常问的那一类问题。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportTaskItemApiTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("单个文件解析失败：明细里有这个文件、动作是 FAILED、并带上失败原因")
    void failedFileIsItemizedWithReason() throws Exception {
        String token = registerAndLogin("items-failed");
        Long kbId = createKnowledgeBase(token, "失败明细");

        // 扩展名合法（入队时能过），但内容不是 PDF —— 失败只能发生在解析阶段，
        // 这正是"入队期校验挡不住、必须靠明细解释"的那一类
        Long taskId = uploadSingle(token, kbId, "broken.pdf",
                "这不是一个 PDF 文件".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get(itemsUrl(taskId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].sourcePath").value("broken.pdf"))
                .andExpect(jsonPath("$.data[0].action").value("FAILED"))
                .andExpect(jsonPath("$.data[0].message").isNotEmpty());

        // 任务本身也如实反映：一个文件都没成功，状态是 FAILED 而不是 DONE
        mockMvc.perform(get("/api/import-tasks/" + taskId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.status").value("FAILED"));

        // 动作过滤要真的在过滤：查 FAILED 有 1 条，查 SKIPPED 是空的
        mockMvc.perform(get(itemsUrl(taskId, "limit=100&action=SKIPPED"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("扫描期跳过的条目现在也有明细（回归：.pptx 为什么没进来）")
    void scanSkippedEntriesAreItemized() throws Exception {
        String token = registerAndLogin("items-skipped");
        Long kbId = createKnowledgeBase(token, "跳过明细");

        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("Vault/note.md", "# 笔记\n\n正常的一篇。\n");
        // 不支持的附件类型：以前只进计数，用户在明细里看不到它
        entries.put("Vault/slides.pptx", "not really a pptx");
        // Obsidian 配置目录：同理
        entries.put("Vault/.obsidian/app.json", "{}");

        String uploadBody = mockMvc.perform(multipart("/api/knowledge-bases/" + kbId + "/vault")
                        .file(new MockMultipartFile("file", "Vault.zip", "application/zip", zip(entries)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.createdCount").value(1))
                .andExpect(jsonPath("$.data.skippedCount").value(2))
                .andReturn().getResponse().getContentAsString();

        Long taskId = Long.valueOf(JsonPath.read(uploadBody, "$.data.id").toString());

        String body = mockMvc.perform(get(itemsUrl(taskId, "limit=100&action=skipped"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> items = JsonPath.read(body, "$.data");
        assertThat(items).extracting(item -> item.get("sourcePath"))
                .containsExactlyInAnyOrder("Vault/slides.pptx", "Vault/.obsidian/app.json");
        // 每条都带着可以读给人听的原因（"暂不支持的文件类型" / "隐藏目录或文件…"）
        assertThat(items).allSatisfy(item ->
                assertThat((String) item.get("message")).isNotBlank());
        // action 过滤大小写不敏感：前端写 'FAILED' 还是 'failed' 都应当能用
        assertThat(items).allSatisfy(item -> assertThat(item.get("action")).isEqualTo("SKIPPED"));
    }

    @Test
    @DisplayName("单文件重复上传（内容未变）：明细里有一条 SKIPPED 说明原因，而不是零条")
    void unchangedSingleFileUploadIsExplained() throws Exception {
        String token = registerAndLogin("items-unchanged");
        Long kbId = createKnowledgeBase(token, "未变化");

        byte[] content = "# 稳定的内容\n\n一个字都没改。".getBytes(StandardCharsets.UTF_8);
        uploadSingle(token, kbId, "stable.md", content);
        Long secondTaskId = uploadSingle(token, kbId, "stable.md", content);

        // 这是"传了却什么都没发生"的唯一解释来源：任务视图只会说"跳过 1"
        mockMvc.perform(get(itemsUrl(secondTaskId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].action").value("SKIPPED"))
                .andExpect(jsonPath("$.data[0].sourcePath").value("stable.md"))
                .andExpect(jsonPath("$.data[0].message")
                        .value(org.hamcrest.Matchers.containsString("未变化")));
    }

    @Test
    @DisplayName("整包同步时未变化的文件只计数、不逐条落明细（一次重导上千行是不能接受的）")
    void unchangedVaultFilesAreCountedButNotItemized() throws Exception {
        String token = registerAndLogin("items-vault-unchanged");
        Long kbId = createKnowledgeBase(token, "整包未变化");

        Map<String, String> entries = Map.of("Vault/a.md", "# 甲\n\n正文。\n", "Vault/b.md", "# 乙\n\n正文。\n");
        uploadVault(token, kbId, entries);
        Long secondTaskId = uploadVault(token, kbId, entries);

        mockMvc.perform(get("/api/import-tasks/" + secondTaskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.skippedCount").value(2))
                .andExpect(jsonPath("$.data.createdCount").value(0));

        // 计数是 2，明细是 0 —— 这个"不相等"是刻意的，因此界面必须解释它，
        // 而不是让用户以为明细丢了。这条断言就是那份解释的守卫
        mockMvc.perform(get(itemsUrl(secondTaskId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("limit 收敛：0 会被抬到 1，超大值会被压到上限，不会让一次查询拖回整个库的明细")
    void limitIsClamped() throws Exception {
        String token = registerAndLogin("items-limit");
        Long kbId = createKnowledgeBase(token, "limit");

        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("Vault/a.md", "# 甲\n\n正文。\n");
        entries.put("Vault/b.md", "# 乙\n\n正文。\n");
        entries.put("Vault/c.md", "# 丙\n\n正文。\n");
        Long taskId = uploadVault(token, kbId, entries);

        mockMvc.perform(get(itemsUrl(taskId, "limit=0"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get(itemsUrl(taskId, "limit=2"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(2));
        // 超过 MAX_ITEM_LIMIT(500) 时按上限截断——这里只有 3 条，断言的是"没有因为越界而报错"
        mockMvc.perform(get(itemsUrl(taskId, "limit=100000"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    @DisplayName("归属校验：别人的任务明细一律 404（3008），不泄露文件清单")
    void itemsAreOwnedByUser() throws Exception {
        String ownerToken = registerAndLogin("items-owner");
        Long kbId = createKnowledgeBase(ownerToken, "别人的明细");
        Long taskId = uploadSingle(ownerToken, kbId, "private.md",
                "# 私有\n\n不该被别人看到路径。".getBytes(StandardCharsets.UTF_8));

        String intruderToken = registerAndLogin("items-intruder");
        mockMvc.perform(get(itemsUrl(taskId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + intruderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3008));

        // 不存在的任务同样是 3008（与"别人的任务"不可区分）
        mockMvc.perform(get(itemsUrl(999_999_999_999L))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3008));

        // 未认证 → 401
        mockMvc.perform(get(itemsUrl(taskId))).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------

    private String itemsUrl(Long taskId) {
        return itemsUrl(taskId, "limit=100");
    }

    /** 带自定义查询串的版本——不能直接在默认 URL 后面拼 `&limit=x`：同名参数出现两次时 Spring 取第一个。 */
    private String itemsUrl(Long taskId, String query) {
        return "/api/import-tasks/" + taskId + "/items?" + query;
    }

    /** 单文件上传并返回任务 id。 */
    private Long uploadSingle(String token, Long kbId, String fileName, byte[] content) throws Exception {
        String body = mockMvc.perform(multipart("/api/knowledge-bases/" + kbId + "/documents")
                        .file(new MockMultipartFile("file", fileName, MediaType.APPLICATION_OCTET_STREAM_VALUE, content))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.valueOf(JsonPath.read(body, "$.data.id").toString());
    }

    private Long uploadVault(String token, Long kbId, Map<String, String> entries) throws Exception {
        String body = mockMvc.perform(multipart("/api/knowledge-bases/" + kbId + "/vault")
                        .file(new MockMultipartFile("file", "Vault.zip", "application/zip", zip(entries)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.valueOf(JsonPath.read(body, "$.data.id").toString());
    }

    private byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
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

    private Long createKnowledgeBase(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.valueOf(JsonPath.read(body, "$.data.id").toString());
    }
}
