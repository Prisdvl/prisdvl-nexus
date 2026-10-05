package com.lky.nexus.app.knowledge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentLinkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.DocumentTagMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentLinkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.model.entity.DocumentTagPO;
import com.lky.nexus.knowledge.search.ChunkIndex;
import com.lky.nexus.knowledge.storage.DocumentStorage;
import com.lky.nexus.knowledge.vector.VectorStore;
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
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 删除知识库时的外部清理（Phase 5.3 · P1-2 的另一半）。
 *
 * <p><b>为什么它单成一类</b>：删库此前比删单篇文档漏得更多——它连 {@code kb_chunk} 都没删
 * （{@code documentMapper.delete} 走的是 {@code @TableLogic} 逻辑删除，而注释里写着"物理删除"），
 * 向量与 ES 更是不碰，代码里还留着一句 TODO 承认了这件事。
 * 本阶段的验收标准是"删除后向量 / ES 无残留"，只修文档级把这句话留在库级不成立。
 *
 * <p>这里直接用 {@code VectorStore} / {@code ChunkIndex} <b>端口</b>做 mock，
 * 而不是 {@code ChunkEmbeddingService} / {@code ChunkIndexService}：库级清理在实现里走的就是端口
 * （那两个服务反向依赖 {@code KnowledgeBaseService}，从它再注回去会构成构造器循环依赖）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgeBaseDeletePurgeTest {

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
    private DocumentStorage documentStorage;

    @MockitoBean
    private VectorStore vectorStore;

    @MockitoBean
    private ChunkIndex chunkIndex;

    @Test
    @DisplayName("删库：切片/关联边物理清空，标签清空，向量与索引按库清理，原始文件从磁盘消失")
    void deletingKnowledgeBasePurgesEverythingBelowIt() throws Exception {
        String token = registerAndLogin("kb-purge");
        Long kbId = createKnowledgeBase(token, "整库清理");

        upload(token, kbId, "甲.md", "# 甲\n\n参见 [[乙]]，正文若干。".getBytes(StandardCharsets.UTF_8));
        upload(token, kbId, "乙.md", "# 乙\n\n参见 [[甲]]。".getBytes(StandardCharsets.UTF_8));

        List<DocumentPO> documents = documentMapper.selectList(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, kbId));
        assertThat(documents).hasSize(2);
        assertThat(documents).allSatisfy(document ->
                assertThat(documentStorage.load(document.getStoredPath())).isNotNull());
        assertThat(chunkCount(kbId)).isPositive();
        assertThat(linkCount(kbId)).isPositive();

        mockMvc.perform(delete("/api/knowledge-bases/" + kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // ① 切片与关联边是物理删除：它们不承载审计意义，留着的每一行都会让"这个库还有多少数据"失去意义。
        //    注意这里查的是原始 SQL 层面的行数（没有 deleted 列可依赖），
        //    所以能被 Wrappers 查出来的就是真的还在
        assertThat(chunkCount(kbId)).as("切片必须物理清空").isZero();
        assertThat(linkCount(kbId)).as("关联边必须物理清空").isZero();
        assertThat(documentTagMapper.selectCount(Wrappers.<DocumentTagPO>lambdaQuery()
                .eq(DocumentTagPO::getKbId, kbId))).isZero();

        // ② 文档走逻辑删除（保留审计），因此查询侧看不到
        assertThat(documentMapper.selectCount(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, kbId))).isZero();

        // ③ 外部存储：按库清理（这正是此前完全缺失的一步——TODO 写在那里很久了）
        verify(vectorStore).deleteByKnowledgeBase(kbId);
        verify(chunkIndex).deleteByKnowledgeBase(kbId);

        // ④ 磁盘上的原始文件
        for (DocumentPO document : documents) {
            assertThat(documentStorage.load(document.getStoredPath()))
                    .as("原始文件应随库一起删除：" + document.getSourcePath()).isNull();
        }
    }

    @Test
    @DisplayName("库里有过删除记录（墓碑）时删库不会撞唯一键")
    void deletingKnowledgeBaseWithTombstonesDoesNotCollide() throws Exception {
        String token = registerAndLogin("kb-purge-tombstone");
        Long kbId = createKnowledgeBase(token, "带墓碑的库");

        // 制造一条墓碑：传 → 删 → 再传同名（此时库里既有 deleted=0 的行，也有 deleted=1 的墓碑）
        upload(token, kbId, "note.md", "# 第一版\n\n内容一。".getBytes(StandardCharsets.UTF_8));
        Long firstId = documentIdOf(kbId, "note.md");
        mockMvc.perform(delete("/api/knowledge-bases/" + kbId + "/documents/" + firstId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        upload(token, kbId, "note.md", "# 第二版\n\n内容二。".getBytes(StandardCharsets.UTF_8));

        // 删库时会把这条存活文档再逻辑删除一次，写出第二个 (kb_id, note.md, deleted=1)。
        // 没有 purgeKnowledgeBaseTombstones 的话，这里会以一句 Duplicate entry 失败，
        // 而且失败在一个和用户操作毫无关系的地方
        mockMvc.perform(delete("/api/knowledge-bases/" + kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(documentMapper.selectCount(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, kbId))).isZero();
    }

    @Test
    @DisplayName("删库：库内存在 stored_path 为 NULL 的文档（vault 导入）时不再 NPE，库照样删干净")
    void deletingKnowledgeBaseWithNullStoredPathSucceeds() throws Exception {
        String token = registerAndLogin("kb-purge-null-path");
        Long kbId = createKnowledgeBase(token, "含无落盘文件的库");

        upload(token, kbId, "note.md", "# 笔记\n\n正文。".getBytes(StandardCharsets.UTF_8));

        // 复现真实生产数据形态：OBSIDIAN vault 导入的文档没有落盘文件，stored_path 就是 NULL。
        // 触发点很隐蔽——收集"待删磁盘文件"时只 select 了 stored_path 一列，而 MyBatis 在
        // "一行里被映射的列全为 NULL"时会把整行映射成 null（returnInstanceForEmptyRow 默认 false），
        // 于是 stream 里混进 null 元素，方法引用 DocumentPO::getStoredPath 的接收者为 null 直接 NPE。
        // 症状是"这个库根本删不掉"：用户点删除拿 500，而且报错点和 vault 导入毫无关系。
        Long documentId = documentIdOf(kbId, "note.md");
        documentMapper.update(null, Wrappers.<DocumentPO>lambdaUpdate()
                .eq(DocumentPO::getId, documentId)
                .set(DocumentPO::getStoredPath, null));

        mockMvc.perform(delete("/api/knowledge-bases/" + kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(documentMapper.selectCount(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, kbId))).isZero();
    }

    @Test
    @DisplayName("删库不影响别的库：另一个库的切片与文档原封不动")
    void deletingOneKnowledgeBaseLeavesOthersIntact() throws Exception {
        String token = registerAndLogin("kb-purge-isolated");
        Long doomed = createKnowledgeBase(token, "待删");
        Long kept = createKnowledgeBase(token, "保留");

        upload(token, doomed, "gone.md", "# 会消失\n\n正文。".getBytes(StandardCharsets.UTF_8));
        upload(token, kept, "kept.md", "# 会留下\n\n正文。".getBytes(StandardCharsets.UTF_8));

        Long keptChunks = chunkCount(kept);
        DocumentPO survivor = documentMapper.selectOne(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, kept));

        mockMvc.perform(delete("/api/knowledge-bases/" + doomed)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(chunkCount(kept)).as("另一个库的切片一条都不能少").isEqualTo(keptChunks);
        assertThat(documentMapper.selectById(survivor.getId())).isNotNull();
        assertThat(documentStorage.load(survivor.getStoredPath())).as("另一个库的原始文件也不该被删").isNotNull();
    }

    // ------------------------------------------------------------------

    private long chunkCount(Long kbId) {
        return chunkMapper.selectCount(Wrappers.<ChunkPO>lambdaQuery().eq(ChunkPO::getKbId, kbId));
    }

    private long linkCount(Long kbId) {
        return documentLinkMapper.selectCount(Wrappers.<DocumentLinkPO>lambdaQuery()
                .eq(DocumentLinkPO::getKbId, kbId));
    }

    private Long documentIdOf(Long kbId, String sourcePath) {
        DocumentPO document = documentMapper.selectOne(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, kbId)
                .eq(DocumentPO::getSourcePath, sourcePath));
        assertThat(document).as("应存在文档 " + sourcePath).isNotNull();
        return document.getId();
    }

    private void upload(String token, Long kbId, String fileName, byte[] content) throws Exception {
        mockMvc.perform(multipart("/api/knowledge-bases/" + kbId + "/documents")
                        .file(new MockMultipartFile("file", fileName, MediaType.APPLICATION_OCTET_STREAM_VALUE, content))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"));
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
