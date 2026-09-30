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
 * Phase 4.2 验收测试：相关推荐。
 *
 * <p>覆盖两种相关性信号与排序确定性：
 * <ul>
 *   <li>互相链接（30 分，最强）——被引用的那篇文档一定靠前；</li>
 *   <li>共同标签（每个 10 分）——没链接但标签相同也会出现（"Redis" 系列）。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RelatedDocumentTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("相关推荐：链接优先于标签，且给出理由")
    void recommendsByLinksThenSharedTags() throws Exception {
        String token = registerAndLogin("related-owner");
        Long kbId = createKnowledgeBase(token, "相关推荐");
        uploadVault(token, kbId);

        // 目标 = JVM 笔记：SpringBoot（链接 30 + 共同标签 java 10 = 40）应排第一，
        // 缓存（仅链接 30）第二；"Redis 架构"（仅标签 redis，与 JVM 无交集）不应出现
        String jvmId = documentIdOf(token, kbId, "Java/JVM 笔记.md");
        String body = mockMvc.perform(get("/api/knowledge-bases/" + kbId + "/documents/" + jvmId + "/related")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].title").value("Spring Boot 自动配置"))
                .andExpect(jsonPath("$.data[0].reasons[0]").value("互相链接"))
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> items = JsonPath.read(body, "$.data");
        assertThat((List<String>) items.get(0).get("reasons"))
                .contains("互相链接", "共同标签: java");
        assertThat(items.get(0).get("title")).isEqualTo("Spring Boot 自动配置");

        // 目标 = 缓存：链接到 JVM 笔记（30）第一，"Redis 架构"仅共同标签（10）第二
        String cacheId = documentIdOf(token, kbId, "Redis/缓存.md");
        String cacheBody = mockMvc.perform(get("/api/knowledge-bases/" + kbId + "/documents/" + cacheId + "/related")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].linked").value(true))
                .andExpect(jsonPath("$.data[1].linked").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<List<Map<String, Object>>>read(cacheBody, "$.data"))
                .extracting(item -> item.get("title"))
                .containsExactly("JVM 笔记", "Redis 架构");
    }

    // ------------------------------------------------------------------

    private String documentIdOf(String token, Long kbId, String sourcePath) throws Exception {
        String body = mockMvc.perform(get("/api/knowledge-bases/" + kbId + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> list = JsonPath.read(body, "$.data.list");
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
        entries.put("Vault/Java/SpringBoot.md", """
                ---
                tags: [java, springboot]
                ---

                # Spring Boot 自动配置

                参见 [[JVM 笔记]]。
                """);
        entries.put("Vault/Java/JVM 笔记.md", """
                ---
                tags: [java, jvm]
                ---

                # JVM 笔记

                G1 按 Region 划分堆内存。
                """);
        entries.put("Vault/Redis/缓存.md", """
                ---
                tags: [redis]
                ---

                # 缓存

                用 SETNX 加锁。相关：[[JVM 笔记]]
                """);
        entries.put("Vault/Redis/Redis 架构.md", """
                ---
                tags: [redis]
                ---

                # Redis 架构

                单线程事件循环。
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
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
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