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
 * Phase 4 验收测试：文档关联（出链 / 反向链接 / 待创建）。
 *
 * <p>覆盖三种链接与三种解析结果：
 * <ul>
 *   <li>WikiLink 指向已存在的笔记（按标题解析）→ resolved；</li>
 *   <li>WikiLink 指向尚不存在的笔记 → dangling（"待创建笔记"，这是知识库最有用的一份待办清单）；</li>
 *   <li>Markdown 外链 → external（不参与内部关联图）；</li>
 *   <li>反向链接：另一篇文档引用本文档时，本文档能看到"谁引用了我"。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentLinkTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("出链解析：已存在→resolved、不存在→dangling、外链→external")
    void resolvesOutlinks() throws Exception {
        String token = registerAndLogin("link-owner");
        Long kbId = createKnowledgeBase(token, "关联测试");
        uploadVault(token, kbId);

        String documentId = documentIdOf(token, kbId, "Java/SpringBoot.md");

        mockMvc.perform(get("/api/knowledge-bases/" + kbId + "/documents/" + documentId + "/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outlinks.length()").value(3))
                // 指向不存在的 [[SpringMVC]] -> 未解析，计入 dangling
                .andExpect(jsonPath("$.data.dangling").value(1))
                .andExpect(jsonPath("$.data.outlinks[?(@.external == true)]").exists())
                // [[JVM 笔记]] 应按标题解析到那篇文档
                .andExpect(jsonPath("$.data.outlinks[?(@.resolvedDocumentId != null)]").exists());
    }

    @Test
    @DisplayName("反向链接：别人引用了我，我要看得到")
    void listsBacklinks() throws Exception {
        String token = registerAndLogin("link-back");
        Long kbId = createKnowledgeBase(token, "反向链接");
        uploadVault(token, kbId);

        String targetId = documentIdOf(token, kbId, "Java/JVM 笔记.md");

        String body = mockMvc.perform(get("/api/knowledge-bases/" + kbId + "/documents/" + targetId + "/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.backlinks").isArray())
                .andReturn().getResponse().getContentAsString();

        // 有两篇文档引用了它（SpringBoot.md 与 Redis/缓存.md）
        assertThat((java.util.List<?>) JsonPath.read(body, "$.data.backlinks")).hasSize(2);
    }

    // ------------------------------------------------------------------

    private String documentIdOf(String token, Long kbId, String sourcePath) throws Exception {
        String body = mockMvc.perform(get("/api/knowledge-bases/" + kbId + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        java.util.List<Map<String, Object>> list = JsonPath.read(body, "$.data.list");
        return list.stream()
                .filter(item -> sourcePath.equals(item.get("sourcePath")))
                .map(item -> String.valueOf(item.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到文档 " + sourcePath));
    }

    private void uploadVault(String token, Long kbId) throws Exception {
        mockMvc.perform(multipart("/api/knowledge-bases/" + kbId + "/vault")
                        .file(new MockMultipartFile("file", "Vault.zip", "application/zip", vault()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"));
    }

    private byte[] vault() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        // 出链：一个未解析的 WikiLink、一个按标题解析的 WikiLink、一个外链
        entries.put("Vault/Java/SpringBoot.md", """
                # Spring Boot

                参见 [[SpringMVC]] 与 [[JVM 笔记]]，也可以看 [官方文档](https://spring.io)。
                """);
        // 被引用的目标（标题就是 "JVM 笔记"）
        entries.put("Vault/Java/JVM 笔记.md", """
                # JVM 笔记

                G1 按 Region 划分堆内存。
                """);
        // 第二个反向链接来源
        entries.put("Vault/Redis/缓存.md", """
                # 缓存

                相关：[[JVM 笔记]]
                """);

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
