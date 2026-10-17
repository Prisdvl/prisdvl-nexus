package com.lky.nexus.app.knowledge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentLinkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.DocumentTagMapper;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentLinkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.model.entity.DocumentTagPO;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.service.ChunkEmbeddingService;
import com.lky.nexus.knowledge.service.ChunkIndexService;
import com.lky.nexus.knowledge.storage.DocumentStorage;
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

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 删除单篇文档（Phase 5.3 · P1-2）：{@code DELETE /api/knowledge-bases/{kbId}/documents/{docId}}。
 *
 * <p>这个端点本身只有一行转发，值得测的是它<b>背后那条清理链</b>——
 * 删除一个文档要动六处数据，少动哪一处都不会报错，只会留下看不见的残留：
 * <ol>
 *   <li>向量库（删不掉 → 检索命中却翻不出正文，界面上表现为"结果里冒出一条空白"）；</li>
 *   <li>检索索引（同上，走 BM25 那一路）；</li>
 *   <li>{@code kb_chunk} / {@code kb_document_tag}（幽灵切片：搜得到、正文里没有）；</li>
 *   <li>{@code kb_document_link} 的<b>出链</b>（保留入链是刻意的，见下）；</li>
 *   <li>磁盘上的原始文件（不删就是纯垃圾，每删一篇留一份）；</li>
 *   <li>知识库上的统计数字（不重算，列表页会显示"3 篇"而实际只剩 2 篇）。</li>
 * </ol>
 *
 * <p><b>为什么用 {@code @MockitoBean} 替换两个"外部写入"服务</b>：测试 profile 下
 * {@code DisabledVectorStore} / {@code DisabledChunkIndex} 是<b>静默 no-op</b>——
 * 它们不报错，所以"删完向量库是空的"这种断言在测试里用真实 Bean 根本无法成立
 * （查什么都是空的）。换成 mock 之后才能断言"确实按这个 documentId 调了清理"，
 * 真实存储的 {@code countByKnowledgeBase == 0} 交给 live 门控用例（需要 Docker）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentDeleteTest {

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
    private DocumentLinkMapper documentLinkMapper;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    private DocumentStorage documentStorage;

    @MockitoBean
    private ChunkEmbeddingService chunkEmbeddingService;

    @MockitoBean
    private ChunkIndexService chunkIndexService;

    @Test
    @DisplayName("删除一篇：文档/切片/标签/出链/原始文件全部清掉，外部存储按 documentId 清理，库统计随之重算")
    void deleteRemovesDocumentAndEverythingBelowIt() throws Exception {
        String token = registerAndLogin("doc-delete-ok");
        Long kbId = createKnowledgeBase(token, "删除文档");

        // 两篇互相引用：删掉甲之后，乙里那条 [[甲]] 必须留下（退化成待创建），甲里那条 [[乙]] 必须删掉
        upload(token, kbId, "甲.md", "# 甲\n\n参见 [[乙]]，另有一句正文。".getBytes(StandardCharsets.UTF_8));
        upload(token, kbId, "乙.md", "# 乙\n\n参见 [[甲]]。".getBytes(StandardCharsets.UTF_8));

        DocumentPO victim = byPath(kbId, "甲.md");
        Long victimId = victim.getId();
        String storedPath = victim.getStoredPath();
        assertThat(storedPath).isNotNull();
        assertThat(documentStorage.load(storedPath)).as("删除前磁盘上应当有原始文件").isNotNull();
        assertThat(chunkCount(victimId)).isPositive();
        assertThat(linkCount(kbId)).isPositive();

        mockMvc.perform(delete(docUrl(kbId, victimId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // ① 文档本身（查询侧一律看不到）
        assertThat(documentMapper.selectCount(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getId, victimId))).as("逻辑删除后按 id 查不到").isZero();
        mockMvc.perform(get(docUrl(kbId, victimId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3003));

        // ② 切片与标签：物理删除（它们是量大且无审计价值的附属数据）
        assertThat(chunkCount(victimId)).isZero();
        assertThat(documentTagMapper.selectCount(Wrappers.<DocumentTagPO>lambdaQuery()
                .eq(DocumentTagPO::getDocumentId, victimId))).isZero();

        // ③ 出链删掉、入链保留 —— 这条断言是 Obsidian 语义的核心：
        // 删掉一篇笔记，别的笔记里对它的引用不该消失（用户会因此丢掉"这里本该有篇笔记"的线索）
        assertThat(documentLinkMapper.selectCount(Wrappers.<DocumentLinkPO>lambdaQuery()
                .eq(DocumentLinkPO::getFromDocumentId, victimId))).isZero();
        DocumentPO survivor = byPath(kbId, "乙.md");
        assertThat(documentLinkMapper.selectCount(Wrappers.<DocumentLinkPO>lambdaQuery()
                .eq(DocumentLinkPO::getFromDocumentId, survivor.getId()))).isPositive();

        // ④ 原始文件
        assertThat(documentStorage.load(storedPath)).as("磁盘上的原始文件也要清掉").isNull();

        // ⑤ 外部存储：按 documentId 清理，而不是按 chunkId（切片的 id 会随重新切分变化）
        verify(chunkEmbeddingService).deleteVectors(List.of(victimId));
        verify(chunkIndexService).deleteByDocumentIds(List.of(victimId));

        // ⑥ 知识库统计：不重算的话列表页会一直显示删除前的数字
        KnowledgeBasePO kb = knowledgeBaseMapper.selectById(kbId);
        assertThat(kb.getDocCount()).as("删了一篇，文档数要跟着降").isEqualTo(1);
        assertThat(kb.getChunkCount()).as("切片数取实际行数").isEqualTo(chunkCount(survivor.getId()));
        assertThat(kb.getTotalTokens()).isPositive();
    }

    @Test
    @DisplayName("删完再传同名文件、再删一次：不会撞 uk_doc_kb_path 的唯一键")
    void deletingTheSamePathTwiceDoesNotCollide() throws Exception {
        String token = registerAndLogin("doc-delete-tombstone");
        Long kbId = createKnowledgeBase(token, "墓碑");

        // 第一轮
        upload(token, kbId, "note.md", "# 第一版\n\n内容一。".getBytes(StandardCharsets.UTF_8));
        Long firstId = byPath(kbId, "note.md").getId();
        mockMvc.perform(delete(docUrl(kbId, firstId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // 第二轮：同一个 source_path 再传一次（同名即更新 → 这里是新建，因为旧行已经删了）
        upload(token, kbId, "note.md", "# 第二版\n\n内容二。".getBytes(StandardCharsets.UTF_8));
        Long secondId = byPath(kbId, "note.md").getId();
        assertThat(secondId).as("删掉的文档再传回来应当是一篇新文档").isNotEqualTo(firstId);

        // 关键的一步：第二次逻辑删除会在 (kb_id, source_path, deleted=1) 上再写一行，
        // 而第一轮已经占用了这个键。没有 purgePathTombstones 的话这里会抛
        // "Duplicate entry ... for key 'uk_doc_kb_path'"——一句和用户操作毫无关系的报错
        mockMvc.perform(delete(docUrl(kbId, secondId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(documentMapper.selectCount(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, kbId))).isZero();
    }

    @Test
    @DisplayName("外部存储清理失败 → 整体失败，文档原封不动（宁可删不掉，也不要留下查不到正文的孤儿）")
    void purgeFailureAbortsTheWholeDelete() throws Exception {
        String token = registerAndLogin("doc-delete-failclosed");
        Long kbId = createKnowledgeBase(token, "fail-closed");

        upload(token, kbId, "keep.md", "# 保留\n\n这篇不该被删掉。".getBytes(StandardCharsets.UTF_8));
        DocumentPO document = byPath(kbId, "keep.md");
        Long documentId = document.getId();
        String storedPath = document.getStoredPath();
        int chunksBefore = chunkCount(documentId);
        long linksBefore = documentLinkMapper.selectCount(Wrappers.<DocumentLinkPO>lambdaQuery()
                .eq(DocumentLinkPO::getKbId, kbId));

        // 模拟向量库不可用。真实场景：pgvector 挂了、网络不通、表被锁
        doThrow(new IllegalStateException("vector store unavailable"))
                .when(chunkEmbeddingService).deleteVectors(any());

        mockMvc.perform(delete(docUrl(kbId, documentId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().is5xxServerError());

        // 这是整个改动的核心取舍：外部清理在动 MySQL <b>之前</b>，
        // 它的失败换来的是一次干净的回滚——文档、切片、文件都还在，用户重试即可。
        assertThat(documentMapper.selectById(documentId)).as("删不掉就必须原封不动").isNotNull();
        assertThat(chunkCount(documentId)).isEqualTo(chunksBefore);
        assertThat(documentStorage.load(storedPath)).isNotNull();
        assertThat(documentLinkMapper.selectCount(Wrappers.<DocumentLinkPO>lambdaQuery()
                .eq(DocumentLinkPO::getKbId, kbId))).as("关联边也不该被清掉").isEqualTo(linksBefore);

        // 而检索索引那一步根本没轮到执行（顺序本身就是保证）
        verify(chunkIndexService, never()).deleteByDocumentIds(any());
    }

    @Test
    @DisplayName("别人的知识库 / 别人的文档：一律 404，且不泄露文档是否存在")
    void cannotDeleteOthersDocuments() throws Exception {
        String ownerToken = registerAndLogin("doc-del-owner");
        Long kbId = createKnowledgeBase(ownerToken, "归属校验");
        upload(ownerToken, kbId, "secret.md", "# 机密\n\n不该被别人删掉。".getBytes(StandardCharsets.UTF_8));
        Long documentId = byPath(kbId, "secret.md").getId();

        String intruderToken = registerAndLogin("doc-del-intruder");

        // ① 别人的知识库 → 3001（连库都不认，文档 id 对不对无从得知）
        mockMvc.perform(delete(docUrl(kbId, documentId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + intruderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3001));

        // ② 自己的库 + 别人的文档 id → 3003（同样的 404，不泄露"这个文档存在"）
        Long ownKbId = createKnowledgeBase(intruderToken, "自己的库");
        mockMvc.perform(delete(docUrl(ownKbId, documentId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3003));

        assertThat(documentMapper.selectById(documentId)).as("两次都不该删到东西").isNotNull();
    }

    @Test
    @DisplayName("不存在的文档 id → 3003，而不是 500")
    void unknownDocumentIsNotFound() throws Exception {
        String token = registerAndLogin("doc-delete-missing");
        Long kbId = createKnowledgeBase(token, "不存在");

        mockMvc.perform(delete(docUrl(kbId, 999_999_999_999L))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3003));
    }

    // ------------------------------------------------------------------

    private String docUrl(Long kbId, Long documentId) {
        return "/api/knowledge-bases/" + kbId + "/documents/" + documentId;
    }

    private org.springframework.test.web.servlet.ResultActions upload(
            String token, Long kbId, String fileName, byte[] content) throws Exception {
        return mockMvc.perform(multipart("/api/knowledge-bases/" + kbId + "/documents")
                        .file(new MockMultipartFile("file", fileName, MediaType.APPLICATION_OCTET_STREAM_VALUE, content))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    private DocumentPO byPath(Long kbId, String sourcePath) {
        DocumentPO document = documentMapper.selectOne(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, kbId)
                .eq(DocumentPO::getSourcePath, sourcePath));
        assertThat(document).as("应存在文档 " + sourcePath).isNotNull();
        return document;
    }

    private int chunkCount(Long documentId) {
        return Math.toIntExact(chunkMapper.selectCount(Wrappers.<ChunkPO>lambdaQuery()
                .eq(ChunkPO::getDocumentId, documentId)));
    }

    private long linkCount(Long kbId) {
        return documentLinkMapper.selectCount(Wrappers.<DocumentLinkPO>lambdaQuery()
                .eq(DocumentLinkPO::getKbId, kbId));
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
