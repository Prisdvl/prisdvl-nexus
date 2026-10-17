package com.lky.nexus.app.knowledge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.knowledge.imports.ImportTaskExecutor;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.ImportTaskItemMapper;
import com.lky.nexus.knowledge.mapper.ImportTaskMapper;
import com.lky.nexus.knowledge.model.ImportTaskStatus;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.model.entity.ImportTaskItemPO;
import com.lky.nexus.knowledge.model.entity.ImportTaskPO;
import com.lky.nexus.knowledge.service.ImportTaskPublisher;
import com.lky.nexus.knowledge.storage.ImportStagingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step 2.7 验收测试：异步导入契约、幂等性与重试。
 *
 * <p>测试环境没有 broker，因此这里<b>用 Mockito 替换掉投递器</b>来模拟"真正的异步"：
 * 入队请求返回 QUEUED，然后由测试自己扮演消费者调用执行器。
 * 这样既验证了异步契约（HTTP 线程不干活），又保持了测试的确定性（不依赖真实 MQ 时序）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportTaskAsyncTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ImportTaskExecutor importTaskExecutor;

    @Autowired
    private ImportTaskMapper importTaskMapper;

    @Autowired
    private ImportTaskItemMapper importTaskItemMapper;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private ImportStagingStore importStagingStore;

    /** 替换投递器：拦住"同步执行"，让任务停在 QUEUED，从而观察异步语义。 */
    @MockitoBean
    private ImportTaskPublisher importTaskPublisher;

    private final AtomicLong publishedTaskId = new AtomicLong();

    @BeforeEach
    void stubPublisher() {
        lenient().doAnswer(invocation -> {
            publishedTaskId.set(invocation.getArgument(0));
            return null;
        }).when(importTaskPublisher).publish(anyLong());
    }

    @Test
    @DisplayName("异步契约：入队立即返回 QUEUED，消费端执行后才变 DONE")
    void enqueueReturnsQueuedThenConsumerCompletes() throws Exception {
        String token = registerAndLogin("async-contract");
        Long kbId = createKnowledgeBase(token, "异步契约");

        String body = mockMvc.perform(upload(token, kbId, sampleVault()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.progress").value(0))
                .andReturn().getResponse().getContentAsString();
        Long taskId = Long.valueOf(JsonPath.read(body, "$.data.id").toString());

        // 投递器确实被调用（消息已发出），且任务此时仍未处理
        assertThat(publishedTaskId.get()).isEqualTo(taskId);
        assertThat(importTaskMapper.selectById(taskId).getStatus()).isEqualTo("QUEUED");
        assertThat(documentMapper.selectCount(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, kbId))).isZero();

        // 测试扮演消费者
        importTaskExecutor.execute(taskId);

        ImportTaskPO finished = importTaskMapper.selectById(taskId);
        assertThat(finished.getStatus()).isEqualTo("DONE");
        assertThat(finished.getProgress()).isEqualTo(100);
        assertThat(finished.getFinishedAt()).isNotNull();
        assertThat(documentMapper.selectCount(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, kbId))).isEqualTo(2);

        // 前端轮询接口能看到进度
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/import-tasks/" + taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.createdCount").value(2));
    }

    @Test
    @DisplayName("幂等：同一任务重复投递不会重复处理（消息队列只保证至少一次）")
    void duplicateDeliveryIsIgnored() throws Exception {
        String token = registerAndLogin("async-idempotent");
        Long kbId = createKnowledgeBase(token, "幂等");
        Long taskId = enqueue(token, kbId);

        importTaskExecutor.execute(taskId);
        long documentsAfterFirst = documentMapper.selectCount(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, kbId));
        long itemsAfterFirst = itemCount(taskId);
        Integer versionAfterFirst = documentVersion(kbId);

        // 重复投递两次
        importTaskExecutor.execute(taskId);
        importTaskExecutor.execute(taskId);

        assertThat(documentMapper.selectCount(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, kbId))).isEqualTo(documentsAfterFirst);
        assertThat(itemCount(taskId)).as("重复执行不应重复写明细").isEqualTo(itemsAfterFirst);
        assertThat(documentVersion(kbId)).as("重复执行不应改动文档").isEqualTo(versionAfterFirst);
    }

    @Test
    @DisplayName("暂存文件丢失：任务置为 FAILED 并给出可操作提示，而不是抛异常")
    void missingStagingFailsTaskGracefully() {
        ImportTaskPO orphan = new ImportTaskPO();
        orphan.setKbId(999_999L);
        orphan.setUserId(999_999L);
        orphan.setTaskType("VAULT_ZIP");
        orphan.setStatus(ImportTaskStatus.QUEUED.name());
        orphan.setTotalFiles(0);
        orphan.setCreatedCount(0);
        orphan.setUpdatedCount(0);
        orphan.setSkippedCount(0);
        orphan.setDeletedCount(0);
        orphan.setFailedCount(0);
        orphan.setTotalChunks(0);
        orphan.setTotalTokens(0L);
        orphan.setProgress(0);
        orphan.setCreatedAt(LocalDateTime.now());
        importTaskMapper.insert(orphan);

        importTaskExecutor.execute(orphan.getId());

        ImportTaskPO failed = importTaskMapper.selectById(orphan.getId());
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getErrorMsg()).contains("暂存文件丢失");
    }

    @Test
    @DisplayName("重试接口：暂存仍在时可重试；已完成或暂存已清理时返回 400")
    void retryRules() throws Exception {
        String token = registerAndLogin("async-retry");
        Long kbId = createKnowledgeBase(token, "重试");
        byte[] zip = sampleVault();
        Long taskId = enqueue(token, kbId);

        // ① 还没失败的任务不允许重试
        mockMvc.perform(post("/api/import-tasks/" + taskId + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        // ② 直接把任务置为 PARTIAL（模拟部分失败），暂存文件此时仍在
        ImportTaskPO partial = importTaskMapper.selectById(taskId);
        partial.setStatus(ImportTaskStatus.PARTIAL.name());
        importTaskMapper.updateById(partial);

        publishedTaskId.set(0);
        mockMvc.perform(post("/api/import-tasks/" + taskId + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PARTIAL"));
        assertThat(publishedTaskId.get()).as("重试应重新投递").isEqualTo(taskId);

        // ③ 暂存被清理（例如任务成功过）→ 提示重新上传，而不是内部错误
        importStagingStore.delete(taskId);
        mockMvc.perform(post("/api/import-tasks/" + taskId + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("重新上传")));

        // ④ 别人的任务一律 404
        String otherToken = registerAndLogin("async-other");
        mockMvc.perform(post("/api/import-tasks/" + taskId + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3008));
    }

    // ------------------------------------------------------------------

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder
    upload(String token, Long kbId, byte[] zip) {
        return multipart("/api/knowledge-bases/" + kbId + "/vault")
                .file(new MockMultipartFile("file", "MyVault.zip", "application/zip", zip))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private Long enqueue(String token, Long kbId) throws Exception {
        String body = mockMvc.perform(upload(token, kbId, sampleVault()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.valueOf(JsonPath.read(body, "$.data.id").toString());
    }

    private long itemCount(Long taskId) {
        return importTaskItemMapper.selectCount(Wrappers.<ImportTaskItemPO>lambdaQuery()
                .eq(ImportTaskItemPO::getTaskId, taskId));
    }

    private Integer documentVersion(Long kbId) {
        return documentMapper.selectList(Wrappers.<DocumentPO>lambdaQuery()
                        .eq(DocumentPO::getKbId, kbId).orderByAsc(DocumentPO::getSourcePath))
                .get(0).getVersion();
    }

    private byte[] sampleVault() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("Vault/Java/SpringBoot.md", """
                ---
                title: Spring Boot
                tags: [Java]
                ---

                # Spring Boot

                ## 自动配置

                条件装配决定加载哪些 Bean。
                """);
        entries.put("Vault/Redis/缓存.md", """
                # 缓存

                ## 击穿

                用互斥锁保护数据库。
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
