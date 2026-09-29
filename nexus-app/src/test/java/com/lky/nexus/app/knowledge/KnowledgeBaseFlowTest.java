package com.lky.nexus.app.knowledge;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step 2.1 验收测试：知识库 CRUD 与嵌入模型冻结。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgeBaseFlowTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("建库：嵌入模型与维度被冻结、切分参数取默认值")
    void createFreezesEmbeddingModelAndDefaults() throws Exception {
        String token = registerAndLogin("kb-create");

        mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"我的 Java 笔记","description":"Obsidian 导出"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").isString())
                // ADR-008：建库即冻结
                .andExpect(jsonPath("$.data.embeddingProvider").value("ollama"))
                .andExpect(jsonPath("$.data.embeddingModel").value("bge-m3"))
                .andExpect(jsonPath("$.data.embeddingDim").value(1024))
                .andExpect(jsonPath("$.data.chunkStrategy").value("MARKDOWN_HEADING"))
                .andExpect(jsonPath("$.data.chunkSize").value(400))
                .andExpect(jsonPath("$.data.chunkOverlap").value(50))
                .andExpect(jsonPath("$.data.sourceType").value("MANUAL"))
                .andExpect(jsonPath("$.data.docCount").value(0));
    }

    @Test
    @DisplayName("建库：自定义切分参数与策略生效")
    void createWithCustomChunkParams() throws Exception {
        String token = registerAndLogin("kb-chunk");

        mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"自定义切分","chunkStrategy":"recursive","chunkSize":800,"chunkOverlap":100}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chunkStrategy").value("RECURSIVE"))
                .andExpect(jsonPath("$.data.chunkSize").value(800))
                .andExpect(jsonPath("$.data.chunkOverlap").value(100));
    }

    @Test
    @DisplayName("参数校验：重名 409、overlap>=size 400、非法策略 400")
    void validatesInputs() throws Exception {
        String token = registerAndLogin("kb-validate");
        create(token, "唯一名字");

        // 重名
        mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"唯一名字"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3002));

        // overlap >= size
        mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"重叠过大","chunkSize":400,"chunkOverlap":400}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        // 非法策略
        mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"坏策略","chunkStrategy":"BY_RANDOM"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("改名 / 列表 / 删除 闭环")
    void updateListDelete() throws Exception {
        String token = registerAndLogin("kb-lifecycle");
        Long kbId = create(token, "旧名字");

        mockMvc.perform(put("/api/knowledge-bases/" + kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"新名字","description":"改过了"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("新名字"))
                .andExpect(jsonPath("$.data.description").value("改过了"))
                // 改名不应影响已冻结的嵌入模型
                .andExpect(jsonPath("$.data.embeddingModel").value("bge-m3"));

        mockMvc.perform(get("/api/knowledge-bases").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].name").value("新名字"));

        mockMvc.perform(delete("/api/knowledge-bases/" + kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/knowledge-bases/" + kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3001));
    }

    @Test
    @DisplayName("多租户隔离：别人的知识库一律 404，列表里也看不到")
    void isolatesBetweenUsers() throws Exception {
        String tokenA = registerAndLogin("kb-owner");
        String tokenB = registerAndLogin("kb-other");
        Long kbId = create(tokenA, "A 的知识库");

        mockMvc.perform(get("/api/knowledge-bases/" + kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3001));

        mockMvc.perform(delete("/api/knowledge-bases/" + kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/knowledge-bases").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("未认证访问 → 401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/knowledge-bases"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));
    }

    // ------------------------------------------------------------------

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

    private Long create(String token, String name) throws Exception {
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
