package com.lky.nexus.app.knowledge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.DocumentTagMapper;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.model.entity.DocumentTagPO;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
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
 * Step 2.5 验收测试：Obsidian Vault 导入闭环。
 *
 * <p>一条用例覆盖整条链路：<b>上传 ZIP → 安全解压 → 解析 → 结构化切分 → 落库（文档/标签/切片）→ 任务统计</b>，
 * 并顺带验证多租户隔离与非 ZIP 输入的拒绝行为。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VaultImportFlowTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private ChunkMapper chunkMapper;

    @Autowired
    private DocumentTagMapper documentTagMapper;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Test
    @DisplayName("导入 Vault：文档/标签/切片落库，任务统计正确，跳过项被记录")
    void importsVaultEndToEnd() throws Exception {
        String token = registerAndLogin("vault-import");
        Long kbId = createKnowledgeBase(token, "我的 Obsidian 笔记");

        byte[] zip = sampleVault();
        String body = mockMvc.perform(multipart("/api/knowledge-bases/" + kbId + "/vault")
                        .file(new MockMultipartFile("file", "MyVault.zip", "application/zip", zip))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.taskType").value("VAULT_ZIP"))
                // 3 个可用文件（2 个 md + 1 个 txt）
                .andExpect(jsonPath("$.data.totalFiles").value(3))
                .andExpect(jsonPath("$.data.createdCount").value(3))
                .andExpect(jsonPath("$.data.failedCount").value(0))
                // .obsidian/ 与 png 被跳过
                .andExpect(jsonPath("$.data.skippedCount").value(3))
                .andExpect(jsonPath("$.data.progress").value(100))
                .andExpect(jsonPath("$.data.totalChunks").value(org.hamcrest.Matchers.greaterThan(0)))
                .andReturn().getResponse().getContentAsString();

        Long taskId = Long.valueOf(JsonPath.read(body, "$.data.id").toString());

        // ---------- 文档落库 ----------
        List<DocumentPO> documents = documentMapper.selectList(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, kbId));
        assertThat(documents).hasSize(3);
        assertThat(documents).extracting(DocumentPO::getSourcePath)
                .containsExactlyInAnyOrder("Java/SpringBoot.md", "Java/JVM/GC.md", "README.txt");

        DocumentPO springBoot = documents.stream()
                .filter(d -> d.getSourcePath().equals("Java/SpringBoot.md")).findFirst().orElseThrow();
        // 顶层目录已剥离；folder 反映真实层级
        assertThat(springBoot.getFolder()).isEqualTo("Java");
        assertThat(springBoot.getTitle()).isEqualTo("Spring Boot");
        assertThat(springBoot.getStatus()).isEqualTo("READY");
        assertThat(springBoot.getFileHash()).hasSize(64);
        assertThat(springBoot.getContentHash()).hasSize(64);
        assertThat(springBoot.getVersion()).isEqualTo(1);
        // frontmatter / 标签 / 标题树都以 JSON 落库
        assertThat(springBoot.getFrontmatter()).contains("Spring Boot");
        assertThat(springBoot.getTags()).contains("java", "springboot");
        assertThat(springBoot.getHeadingTree()).contains("自动配置");
        assertThat(springBoot.getChunkCount()).isPositive();

        // ---------- 标签独立成表（检索要靠它做过滤） ----------
        List<DocumentTagPO> tags = documentTagMapper.selectList(Wrappers.<DocumentTagPO>lambdaQuery()
                .eq(DocumentTagPO::getKbId, kbId));
        assertThat(tags).extracting(DocumentTagPO::getTag).contains("java", "jvm", "springboot");

        // ---------- 切片落库 ----------
        List<ChunkPO> chunks = chunkMapper.selectList(Wrappers.<ChunkPO>lambdaQuery()
                .eq(ChunkPO::getKbId, kbId));
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.getDocumentId()).isNotNull();
            assertThat(chunk.getChunkIndex()).isNotNull();
            assertThat(chunk.getContentHash()).hasSize(64);
            // 向量与 ES 索引留待 Phase 3，先标记为待处理
            assertThat(chunk.getVectorStatus()).isEqualTo("PENDING");
        });
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.getHeadingPath()).contains("自动配置"));

        // ---------- 知识库统计被重算 ----------
        KnowledgeBasePO kb = knowledgeBaseMapper.selectById(kbId);
        assertThat(kb.getDocCount()).isEqualTo(3);
        assertThat(kb.getChunkCount()).isEqualTo(chunks.size());
        assertThat(kb.getTotalTokens()).isPositive();

        // ---------- 任务可查询（异步化后的前端轮询契约） ----------
        mockMvc.perform(get("/api/import-tasks/" + taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.createdCount").value(3));
    }

    @Test
    @DisplayName("重复导入同一 Vault：增量同步命中，全部跳过且不产生重复切片")
    void reimportSkipsUnchangedFiles() throws Exception {
        String token = registerAndLogin("vault-reimport");
        Long kbId = createKnowledgeBase(token, "重复导入");
        byte[] zip = sampleVault();

        uploadVault(token, kbId, zip);
        long firstChunkCount = chunkMapper.selectCount(Wrappers.<ChunkPO>lambdaQuery()
                .eq(ChunkPO::getKbId, kbId));

        // 第二次导入：字节完全一致 → 走 SHA-256 增量同步，全部跳过
        mockMvc.perform(multipart("/api/knowledge-bases/" + kbId + "/vault")
                        .file(new MockMultipartFile("file", "MyVault.zip", "application/zip", zip))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdCount").value(0))
                .andExpect(jsonPath("$.data.updatedCount").value(0))
                // skippedCount 同时包含"内容未变化"与"扫描阶段跳过"：
                // 3 个文件未变化 + 3 个跳过条目（.obsidian/、.trash/、png）
                .andExpect(jsonPath("$.data.skippedCount").value(6));

        // 关键断言：跳过的文件既不重建切片、也不升版本，更不会越导越多
        assertThat(documentMapper.selectCount(Wrappers.<DocumentPO>lambdaQuery().eq(DocumentPO::getKbId, kbId)))
                .isEqualTo(3);
        assertThat(chunkMapper.selectCount(Wrappers.<ChunkPO>lambdaQuery().eq(ChunkPO::getKbId, kbId)))
                .as("重复导入不应产生幽灵切片")
                .isEqualTo(firstChunkCount);
        assertThat(documentMapper.selectList(Wrappers.<DocumentPO>lambdaQuery().eq(DocumentPO::getKbId, kbId)))
                .as("内容未变化时版本不应递增")
                .allSatisfy(document -> assertThat(document.getVersion()).isEqualTo(1));
    }

    @Test
    @DisplayName("多租户隔离：往别人的知识库导入 → 404")
    void cannotImportIntoOthersKnowledgeBase() throws Exception {
        String tokenA = registerAndLogin("vault-owner");
        String tokenB = registerAndLogin("vault-intruder");
        Long kbId = createKnowledgeBase(tokenA, "A 的库");

        mockMvc.perform(multipart("/api/knowledge-bases/" + kbId + "/vault")
                        .file(new MockMultipartFile("file", "MyVault.zip", "application/zip", sampleVault()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3001));
    }

    @Test
    @DisplayName("非 ZIP 文件：直接拒绝（扩展名与魔数双重校验）")
    void rejectsNonZipUpload() throws Exception {
        String token = registerAndLogin("vault-notzip");
        Long kbId = createKnowledgeBase(token, "非 ZIP");

        // 扩展名不对
        mockMvc.perform(multipart("/api/knowledge-bases/" + kbId + "/vault")
                        .file(new MockMultipartFile("file", "notes.md", MediaType.TEXT_PLAIN_VALUE,
                                "# 不是压缩包".getBytes(StandardCharsets.UTF_8)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3007));

        // 扩展名对但内容不是 ZIP（魔数校验）
        mockMvc.perform(multipart("/api/knowledge-bases/" + kbId + "/vault")
                        .file(new MockMultipartFile("file", "fake.zip", "application/zip",
                                "这不是压缩包".getBytes(StandardCharsets.UTF_8)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3007));
    }

    // ------------------------------------------------------------------

    private String uploadVault(String token, Long kbId, byte[] zip) throws Exception {
        return mockMvc.perform(multipart("/api/knowledge-bases/" + kbId + "/vault")
                        .file(new MockMultipartFile("file", "MyVault.zip", "application/zip", zip))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** 造一个小型 Vault：含 frontmatter、嵌套目录、WikiLink、隐藏目录与不支持的附件。 */
    private byte[] sampleVault() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("MyVault/Java/SpringBoot.md", """
                ---
                title: Spring Boot
                tags:
                  - Java
                  - SpringBoot
                ---

                # Spring Boot

                ## 自动配置

                Spring Boot 通过条件装配决定加载哪些 Bean。参见 [[SpringMVC]] 与 [[JVM#垃圾回收]]。

                ```java
                @SpringBootApplication
                public class App { }
                ```

                ## 起步依赖

                起步依赖把常用库组合在一起。
                """);
        entries.put("MyVault/Java/JVM/GC.md", """
                ---
                tags: [Java, JVM]
                ---

                # 垃圾回收

                ## G1

                G1 按 Region 划分堆内存。
                """);
        entries.put("MyVault/README.txt", "这是 Vault 根目录的说明文件。");
        entries.put("MyVault/.obsidian/workspace.json", "{}");
        entries.put("MyVault/.trash/deleted.md", "# 已删除");
        entries.put("MyVault/Attachments/diagram.png", "binary-content");

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
