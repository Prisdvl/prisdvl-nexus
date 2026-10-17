package com.lky.nexus.app.knowledge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.DocumentTagMapper;
import com.lky.nexus.knowledge.mapper.ImportTaskItemMapper;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.model.entity.DocumentTagPO;
import com.lky.nexus.knowledge.model.entity.ImportTaskItemPO;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step 2.6 验收测试：SHA-256 增量同步。
 *
 * <p>每条用例都对应一种真实的"用户改了点什么"，并断言<b>该省的省了、该改的改了</b>：
 * <ul>
 *   <li>什么都没改 → 零写入（不解析、不重建切片、不写明细）</li>
 *   <li>只改 frontmatter → 只更新元数据，<b>切片 id 保持不变</b>（等于保住了向量）</li>
 *   <li>改了正文 → 重建切片、version+1</li>
 *   <li>删了文件 → 文档与切片一并清理</li>
 *   <li>改了名 → 识别为 RENAMED，<b>文档 id 与切片 id 都保持不变</b>（不重新嵌入）</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VaultIncrementalSyncTest {

    private static final String PASSWORD = "Passw0rd!23";

    private static final String SPRING_BOOT_PATH = "Java/SpringBoot.md";
    private static final String GC_PATH = "Java/JVM/GC.md";
    private static final String README_PATH = "Redis/README.txt";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private ChunkMapper chunkMapper;

    @Autowired
    private DocumentTagMapper documentTagMapper;

    @Autowired
    private ImportTaskItemMapper importTaskItemMapper;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Test
    @DisplayName("什么都没改：全部跳过，且零写入（不产生任务明细）")
    void noChangeImportIsZeroWrite() throws Exception {
        String token = registerAndLogin("sync-noop");
        Long kbId = createKnowledgeBase(token, "无变化重导");
        upload(token, kbId, vault(baseline()));

        Map<String, Object> before = snapshot(kbId);
        long itemsBefore = importTaskItemMapper.selectCount(Wrappers.<ImportTaskItemPO>lambdaQuery());

        mockMvc.perform(uploadRequest(token, kbId, vault(baseline())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdCount").value(0))
                .andExpect(jsonPath("$.data.updatedCount").value(0))
                .andExpect(jsonPath("$.data.skippedCount").value(3));

        assertThat(snapshot(kbId)).as("未变化时不应有任何数据变化").isEqualTo(before);
        assertThat(importTaskItemMapper.selectCount(Wrappers.<ImportTaskItemPO>lambdaQuery()))
                .as("跳过的文件不写明细，否则一次重导会插入上千行")
                .isEqualTo(itemsBefore);
    }

    @Test
    @DisplayName("只改 frontmatter（加了个 tag）：只更新元数据，切片 id 不变 = 不必重新嵌入")
    void metadataOnlyChangeKeepsChunks() throws Exception {
        String token = registerAndLogin("sync-metadata");
        Long kbId = createKnowledgeBase(token, "只改元数据");
        upload(token, kbId, vault(baseline()));

        Long documentId = documentIdOf(kbId, GC_PATH);
        List<Long> chunkIdsBefore = chunkIdsOf(documentId);
        Integer versionBefore = documentMapper.selectById(documentId).getVersion();

        // 只给 frontmatter 增加一个标签，正文一字未动
        Map<String, String> changed = baseline();
        changed.put("MyVault/Java/JVM/GC.md", """
                ---
                tags: [Java, JVM, 性能调优]
                ---

                # 垃圾回收

                ## G1

                G1 按 Region 划分堆内存。
                """);

        mockMvc.perform(uploadRequest(token, kbId, vault(changed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(1))
                .andExpect(jsonPath("$.data.skippedCount").value(2));

        assertThat(importTaskItemMapper.selectList(Wrappers.<ImportTaskItemPO>lambdaQuery()
                .eq(ImportTaskItemPO::getKbId, kbId)))
                .extracting(ImportTaskItemPO::getAction)
                .contains("METADATA_ONLY");

        // 切片与版本都不该动 —— 这是"省下一次重新嵌入"的可直接观测证据
        assertThat(chunkIdsOf(documentId)).isEqualTo(chunkIdsBefore);
        assertThat(documentMapper.selectById(documentId).getVersion()).isEqualTo(versionBefore);
        // 但标签确实更新了
        assertThat(documentTagMapper.selectList(Wrappers.<DocumentTagPO>lambdaQuery()
                .eq(DocumentTagPO::getDocumentId, documentId)))
                .extracting(DocumentTagPO::getTag)
                .containsExactlyInAnyOrder("java", "jvm", "性能调优");
    }

    @Test
    @DisplayName("改了正文：重建切片、version+1、切片内容随之更新")
    void bodyChangeRebuildsChunks() throws Exception {
        String token = registerAndLogin("sync-body");
        Long kbId = createKnowledgeBase(token, "改正文");
        upload(token, kbId, vault(baseline()));

        Long documentId = documentIdOf(kbId, SPRING_BOOT_PATH);
        List<Long> chunkIdsBefore = chunkIdsOf(documentId);

        Map<String, String> changed = baseline();
        changed.put("MyVault/" + SPRING_BOOT_PATH, """
                ---
                title: Spring Boot
                tags: [Java, SpringBoot]
                ---

                # Spring Boot

                ## 自动配置

                条件装配是自动配置的核心机制。

                ## 起步依赖

                起步依赖把常用库组合在一起。

                ## 内嵌容器

                默认内嵌 Tomcat。
                """);

        mockMvc.perform(uploadRequest(token, kbId, vault(changed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(1))
                .andExpect(jsonPath("$.data.skippedCount").value(2));

        DocumentPO document = documentMapper.selectById(documentId);
        assertThat(document.getVersion()).isEqualTo(2);
        assertThat(document.getChunkCount()).isEqualTo(3);
        // 切片被重建（id 不同），因为正文变了必须重新嵌入
        assertThat(chunkIdsOf(documentId)).doesNotContainAnyElementsOf(chunkIdsBefore);
        assertThat(chunkMapper.selectList(Wrappers.<ChunkPO>lambdaQuery()
                .eq(ChunkPO::getDocumentId, documentId)))
                .extracting(ChunkPO::getHeadingPath)
                .contains("Spring Boot > 内嵌容器");
    }

    @Test
    @DisplayName("删了文件：文档、切片、标签一并清理，知识库统计同步下降")
    void deletedFileIsRemoved() throws Exception {
        String token = registerAndLogin("sync-delete");
        Long kbId = createKnowledgeBase(token, "删除文件");
        upload(token, kbId, vault(baseline()));

        Long documentId = documentIdOf(kbId, README_PATH);

        Map<String, String> after = baseline();
        after.remove("MyVault/" + README_PATH);

        mockMvc.perform(uploadRequest(token, kbId, vault(after)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedCount").value(1))
                .andExpect(jsonPath("$.data.skippedCount").value(2));

        assertThat(documentMapper.selectById(documentId)).as("文档应被软删").isNull();
        assertThat(chunkMapper.selectCount(Wrappers.<ChunkPO>lambdaQuery()
                .eq(ChunkPO::getDocumentId, documentId))).isZero();

        KnowledgeBasePO kb = knowledgeBaseMapper.selectById(kbId);
        assertThat(kb.getDocCount()).isEqualTo(2);
        assertThat(kb.getChunkCount()).isPositive();
    }

    @Test
    @DisplayName("改了名：识别为 RENAMED，文档 id 与切片 id 都保持不变（不重新嵌入）")
    void renamedFileIsDetectedNotRecreated() throws Exception {
        String token = registerAndLogin("sync-rename");
        Long kbId = createKnowledgeBase(token, "改名");
        upload(token, kbId, vault(baseline()));

        Long documentIdBefore = documentIdOf(kbId, README_PATH);
        List<Long> chunkIdsBefore = chunkIdsOf(documentIdBefore);

        // 内容一字不改，只把 Redis/README.txt 移到 根目录/说明.txt
        Map<String, String> renamed = new LinkedHashMap<>();
        baseline().forEach((path, content) -> {
            if (path.equals("MyVault/" + README_PATH)) {
                renamed.put("MyVault/说明.txt", content);
            } else {
                renamed.put(path, content);
            }
        });

        mockMvc.perform(uploadRequest(token, kbId, vault(renamed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdCount").value(0))
                .andExpect(jsonPath("$.data.deletedCount").value(0))
                .andExpect(jsonPath("$.data.updatedCount").value(1))
                .andExpect(jsonPath("$.data.skippedCount").value(2));

        assertThat(importTaskItemMapper.selectList(Wrappers.<ImportTaskItemPO>lambdaQuery()
                .eq(ImportTaskItemPO::getKbId, kbId)))
                .extracting(ImportTaskItemPO::getAction)
                .contains("RENAMED");

        DocumentPO moved = documentMapper.selectOne(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, kbId)
                .eq(DocumentPO::getSourcePath, "说明.txt"));
        assertThat(moved).isNotNull();
        assertThat(moved.getId()).as("改名应复用原文档，而不是删掉重建").isEqualTo(documentIdBefore);
        assertThat(chunkIdsOf(moved.getId())).as("切片与向量原样保留").isEqualTo(chunkIdsBefore);
        assertThat(documentMapper.selectCount(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, kbId))).isEqualTo(3);
    }

    // ------------------------------------------------------------------

    /** 只对比"文档与切片的关键字段"，避免把 updated_at 之类的正常变化也算进去。 */
    private Map<String, Object> snapshot(Long kbId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        documentMapper.selectList(Wrappers.<DocumentPO>lambdaQuery()
                        .eq(DocumentPO::getKbId, kbId).orderByAsc(DocumentPO::getSourcePath))
                .forEach(document -> snapshot.put(document.getSourcePath(),
                        document.getId() + "|" + document.getVersion() + "|" + document.getFileHash()
                                + "|" + document.getContentHash() + "|" + document.getChunkCount()));
        snapshot.put("chunks", chunkMapper.selectCount(Wrappers.<ChunkPO>lambdaQuery()
                .eq(ChunkPO::getKbId, kbId)));
        snapshot.put("tags", documentTagMapper.selectCount(Wrappers.<DocumentTagPO>lambdaQuery()
                .eq(DocumentTagPO::getKbId, kbId)));
        return snapshot;
    }

    private Long documentIdOf(Long kbId, String sourcePath) {
        DocumentPO document = documentMapper.selectOne(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, kbId)
                .eq(DocumentPO::getSourcePath, sourcePath));
        assertThat(document).as("应存在文档 " + sourcePath).isNotNull();
        return document.getId();
    }

    private List<Long> chunkIdsOf(Long documentId) {
        return chunkMapper.selectList(Wrappers.<ChunkPO>lambdaQuery()
                        .eq(ChunkPO::getDocumentId, documentId).orderByAsc(ChunkPO::getChunkIndex))
                .stream().map(ChunkPO::getId).toList();
    }

    /** 基线 Vault：3 个文件（2 个 md + 1 个 txt）。 */
    private Map<String, String> baseline() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("MyVault/" + SPRING_BOOT_PATH, """
                ---
                title: Spring Boot
                tags: [Java, SpringBoot]
                ---

                # Spring Boot

                ## 自动配置

                Spring Boot 通过条件装配决定加载哪些 Bean。

                ## 起步依赖

                起步依赖把常用库组合在一起。
                """);
        entries.put("MyVault/" + GC_PATH, """
                ---
                tags: [Java, JVM]
                ---

                # 垃圾回收

                ## G1

                G1 按 Region 划分堆内存。
                """);
        entries.put("MyVault/" + README_PATH, "这是 Vault 的说明文件。");
        return entries;
    }

    private byte[] vault(Map<String, String> entries) throws Exception {
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

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder
    uploadRequest(String token, Long kbId, byte[] zip) {
        return multipart("/api/knowledge-bases/" + kbId + "/vault")
                .file(new MockMultipartFile("file", "MyVault.zip", "application/zip", zip))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private void upload(String token, Long kbId, byte[] zip) throws Exception {
        mockMvc.perform(uploadRequest(token, kbId, zip)).andExpect(status().isOk());
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
