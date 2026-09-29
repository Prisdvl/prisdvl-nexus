package com.lky.nexus.app.knowledge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
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
 * 文档 / 切片查询接口测试（前端浏览页依赖它们）。
 *
 * <p>这组接口是"为了界面才补的"，很容易只做手工验证就上线；
 * 因此这里把三件事钉住：列表内容正确、切片按序号返回、以及<b>多租户隔离</b>。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentQueryTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentMapper documentMapper;

    @Test
    @DisplayName("文档列表：返回路径/标题/标签/切片数，且只包含自己的知识库")
    void listsDocumentsWithTags() throws Exception {
        String token = registerAndLogin("docquery-owner");
        Long kbId = createKnowledgeBase(token, "查询测试");
        uploadVault(token, kbId);

        String body = mockMvc.perform(get("/api/knowledge-bases/" + kbId + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andReturn().getResponse().getContentAsString();

        // 把"写入侧存了什么"钉住：这一条让前三轮的排查结论变成永久回归防线。
        // 注意：某些环境（H2 路径）里值会被二次编码成 "[...]"，读取侧已兼容并告警，
        // 因此这里只断言内容包含标签，不断言编码形态。
        // 教训：诊断断言要断言**内容**而不是 isNotNull——空字符串同样能通过非空断言。
        DocumentPO stored = documentMapper.selectList(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, kbId)).get(0);
        assertThat(stored.getTags()).as("库里应存有标签").contains("java");
        assertThat(stored.getHeadingTree()).as("库里应存有标题树").contains("level");

        // 标签以数组返回（库里存的是 JSON 字符串，解析是服务端的事）
        String listJson = JsonPath.read(body, "$.data.list").toString();
        assertThat(listJson).contains("java", "springboot", "jvm");

        Long documentId = Long.valueOf(JsonPath.read(body, "$.data.list[0].id").toString());

        mockMvc.perform(get("/api/knowledge-bases/" + kbId + "/documents/" + documentId + "/chunks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].chunkIndex").value(0))
                .andExpect(jsonPath("$.data[0].vectorStatus").value("PENDING"))
                .andExpect(jsonPath("$.data[0].esStatus").value("PENDING"));
    }

    @Test
    @DisplayName("文档详情：返回原始正文（供 Markdown 渲染）与大纲（供目录跳转）")
    void returnsDocumentContentAndOutline() throws Exception {
        String token = registerAndLogin("docquery-detail");
        Long kbId = createKnowledgeBase(token, "详情测试");
        uploadVault(token, kbId);

        String listBody = mockMvc.perform(get("/api/knowledge-bases/" + kbId + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        Long documentId = Long.valueOf(JsonPath.read(listBody, "$.data.list[0].id").toString());

        String body = mockMvc.perform(get("/api/knowledge-bases/" + kbId + "/documents/" + documentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                // 正文是"原文"（含 frontmatter 与 Markdown 标记），不是切片拼接的结果
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString("# ")))
                .andExpect(jsonPath("$.data.headings").isArray())
                .andExpect(jsonPath("$.data.headings[0].level").value(1))
                // 大纲至少要有两级（一级标题 + 二级标题），否则目录跳转没有意义
                .andExpect(jsonPath("$.data.headings[1].level").value(2))
                .andExpect(jsonPath("$.data.headings[1].text").value("G1"))
                .andReturn().getResponse().getContentAsString();

        // 大纲的锚点由解析阶段生成（与切片里的 headingPath 同源）
        assertThat(body).contains("\"anchor\":\"g1\"");
    }

    @Test
    @DisplayName("多租户隔离：别人的知识库一律 404，列表接口也不例外")
    void isolatesBetweenUsers() throws Exception {
        String ownerToken = registerAndLogin("docquery-a");
        String otherToken = registerAndLogin("docquery-b");
        Long kbId = createKnowledgeBase(ownerToken, "A 的库");
        uploadVault(ownerToken, kbId);

        Long documentId = documentMapper.selectList(Wrappers.<DocumentPO>lambdaQuery()
                        .eq(DocumentPO::getKbId, kbId)).get(0).getId();

        mockMvc.perform(get("/api/knowledge-bases/" + kbId + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3001));

        mockMvc.perform(get("/api/knowledge-bases/" + kbId + "/documents/" + documentId + "/chunks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3001));
    }

    // ------------------------------------------------------------------

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

                # Spring Boot

                ## 自动配置

                条件装配决定加载哪些 Bean。
                """);
        entries.put("Vault/Java/JVM.md", """
                ---
                tags: [java, jvm]
                ---

                # JVM

                ## G1

                Region 划分堆内存。
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
