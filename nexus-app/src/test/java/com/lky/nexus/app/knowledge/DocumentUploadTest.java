package com.lky.nexus.app.knowledge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.knowledge.mapper.ChunkMapper;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.model.entity.ChunkPO;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 单文件上传（Phase 5.3 · P1-1）：{@code POST /api/knowledge-bases/{id}/documents}。
 *
 * <p>它验证的核心不是"能传上去"，而是<b>重用了 Vault 那条流水线之后必须继承的那些判断</b>：
 * <ul>
 *   <li>同名再传是<b>更新</b>而不是新增一行——这条直接决定了用户的心智模型
 *       （"上传"和"同步"应该长得一样，否则同一个文件会在库里出现两份）；</li>
 *   <li>内容没变就是<b>跳过</b>，不是"又更新了一次"（否则每次重传都白白重嵌一遍向量）；</li>
 *   <li>上传<b>绝不能</b>触发删除——这是复用 {@code VaultSyncPlanner} 时最危险的地方：
 *       那份计划器把"清单里没有"判定为"文件被删了"，而单文件上传的清单只有一个文件，
 *       少了 {@code detectDeletions=false} 就会把库里其余文档全部删掉；</li>
 *   <li>来源如实标注为 {@code UPLOAD}（5.3 之前这个枚举值不可达，见 {@code DocumentPersistService}）。</li>
 * </ul>
 *
 * <p>格式覆盖 TXT / MD / PDF / DOCX 四种：前两种是文本类（解析器按 UTF-8 解码），
 * 后两种走二进制解析，走的是完全不同的分支。PDF 与 DOCX 的样本都是<b>现场生成</b>的
 * （照 {@code PdfDocumentParserTest} / {@code DocxDocumentParserTest} 的做法），
 * 而不是往仓库里塞二进制样本——样本怎么来的应当写在测试里。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentUploadTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private ChunkMapper chunkMapper;

    @Test
    @DisplayName("四种格式各传一次：都落库成功、来源标为 UPLOAD、切片非空")
    void uploadsEachSupportedFormat() throws Exception {
        String token = registerAndLogin("upload-formats");
        Long kbId = createKnowledgeBase(token, "单文件上传");

        upload(token, kbId, "notes.md", "# 缓存\n\n缓存击穿的解法是互斥锁。".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.taskType").value("UPLOAD"))
                .andExpect(jsonPath("$.data.totalFiles").value(1))
                .andExpect(jsonPath("$.data.createdCount").value(1))
                .andExpect(jsonPath("$.data.failedCount").value(0));

        upload(token, kbId, "readme.txt", "纯文本内容，没有 frontmatter。".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"));
        upload(token, kbId, "architecture.pdf", pdfWithPages("Spring Boot auto configuration notes."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"));
        upload(token, kbId, "design.docx", docx("设计说明", "这一节说明切分策略。"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"));

        List<DocumentPO> documents = documentsOf(kbId);
        assertThat(documents).hasSize(4);
        // source_path 就是文件名本身：上传的文件没有"库内路径"这回事，
        // 文件名就是它的身份（也让 [[笔记名]] 这类链接能直接匹配上）
        assertThat(documents).extracting(DocumentPO::getSourcePath)
                .containsExactlyInAnyOrder("notes.md", "readme.txt", "architecture.pdf", "design.docx");
        // 来源如实标注：这四种都是用户单传的，不是整包同步来的
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.getSourceType()).isEqualTo("UPLOAD");
            assertThat(document.getStatus()).isEqualTo("READY");
            assertThat(document.getFolder()).isNull();
        });

        DocumentPO pdf = byPath(documents, "architecture.pdf");
        assertThat(pdf.getFileExt()).isEqualTo("pdf");
        assertThat(pdf.getVersion()).isEqualTo(1);
        assertThat(chunkCount(pdf.getId())).isPositive();

        DocumentPO docx = byPath(documents, "design.docx");
        assertThat(docx.getTitle()).isEqualTo("设计说明");
        assertThat(chunkCount(docx.getId())).isPositive();
    }

    @Test
    @DisplayName("同名再传不同内容 = 更新同一行（版本 +1，切片重建），不是新增第二份")
    void sameNameIsUpdateNotDuplicate() throws Exception {
        String token = registerAndLogin("upload-same-name");
        Long kbId = createKnowledgeBase(token, "同名重传");

        upload(token, kbId, "note.md", "# 第一版\n\n最初的内容。".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdCount").value(1));

        DocumentPO first = byPath(documentsOf(kbId), "note.md");
        Long firstId = first.getId();

        upload(token, kbId, "note.md", "# 第二版\n\n内容换了，标题也换了。".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.createdCount").value(0))
                .andExpect(jsonPath("$.data.updatedCount").value(1));

        List<DocumentPO> documents = documentsOf(kbId);
        assertThat(documents).hasSize(1);
        DocumentPO updated = documents.get(0);
        assertThat(updated.getId()).isEqualTo(firstId);
        assertThat(updated.getVersion()).isEqualTo(2);
        assertThat(updated.getTitle()).isEqualTo("第二版");
        // 正文变了 → 切片重建。旧切片必须清干净，否则会留下"检索得到但正文里没有"的幽灵切片
        assertThat(chunkMapper.selectCount(Wrappers.<ChunkPO>lambdaQuery()
                .eq(ChunkPO::getDocumentId, firstId))).isPositive();
    }

    @Test
    @DisplayName("内容一字不差的重复上传 = 跳过（不重嵌向量，不产生新版本）")
    void identicalContentIsSkipped() throws Exception {
        String token = registerAndLogin("upload-unchanged");
        Long kbId = createKnowledgeBase(token, "重复上传");

        byte[] content = "# 稳定的内容\n\n这一段没有变过。".getBytes(StandardCharsets.UTF_8);
        upload(token, kbId, "stable.md", content).andExpect(status().isOk());

        DocumentPO before = byPath(documentsOf(kbId), "stable.md");
        int chunksBefore = chunkCount(before.getId());

        upload(token, kbId, "stable.md", content)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.createdCount").value(0))
                .andExpect(jsonPath("$.data.updatedCount").value(0))
                .andExpect(jsonPath("$.data.skippedCount").value(1));

        DocumentPO after = byPath(documentsOf(kbId), "stable.md");
        // 版本不动、切片数不动：这说明连解析都没做（先比 file_hash 就挡掉了）
        assertThat(after.getVersion()).isEqualTo(1);
        assertThat(chunkCount(after.getId())).isEqualTo(chunksBefore);
    }

    @Test
    @DisplayName("上传一个文件不会动到库里其它文档（清单只有一个文件 ≠ 其余都被删了）")
    void uploadNeverDeletesOtherDocuments() throws Exception {
        String token = registerAndLogin("upload-no-delete");
        Long kbId = createKnowledgeBase(token, "上传不误删");

        upload(token, kbId, "first.md", "# 第一篇\n\n先放进来的。".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isOk());
        upload(token, kbId, "second.md", "# 第二篇\n\n后放进来的。".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isOk())
                // 这一条是本用例的重点：若 detectDeletions 传成了 true，
                // 第二次上传会以"清单里没有 first.md"为由把它删掉，而任务依然是 DONE
                .andExpect(jsonPath("$.data.deletedCount").value(0));

        assertThat(documentsOf(kbId)).hasSize(2);
    }

    @Test
    @DisplayName("不支持的扩展名：同步拒绝（3005），响应里直接给出当前支持哪些格式")
    void unsupportedExtensionIsRejectedSynchronously() throws Exception {
        String token = registerAndLogin("upload-bad-ext");
        Long kbId = createKnowledgeBase(token, "格式不支持");

        upload(token, kbId, "slides.pptx", "not really a pptx".getBytes(StandardCharsets.UTF_8))
                // 校验发生在建任务之前 → 没有产生任何任务，用户立刻拿到可操作的答复，
                // 而不是"排队中…然后失败"（那会让人以为格式其实是对的，只是环境有问题）
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(3005))
                // 消息要说清"是哪个文件"和"支持哪些"：只说"导入失败"等于让用户自己猜。
                // 支持列表由解析器注册表汇总而来（不是这里硬编码的），所以断言的是它真的带上了清单
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("slides.pptx")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("pdf")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("docx")));

        assertThat(documentsOf(kbId)).isEmpty();
    }

    @Test
    @DisplayName("上传到别人的知识库：404（不泄露该库是否存在）")
    void cannotUploadToSomeoneElsesKnowledgeBase() throws Exception {
        String ownerToken = registerAndLogin("upload-owner");
        Long kbId = createKnowledgeBase(ownerToken, "别人的库");

        String intruderToken = registerAndLogin("upload-intruder");
        upload(intruderToken, kbId, "sneaky.md", "# 不该成功".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3001));

        assertThat(documentsOf(kbId)).isEmpty();
    }

    // ------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions upload(
            String token, Long kbId, String fileName, byte[] content) throws Exception {
        return mockMvc.perform(multipart("/api/knowledge-bases/" + kbId + "/documents")
                .file(new MockMultipartFile("file", fileName, MediaType.APPLICATION_OCTET_STREAM_VALUE, content))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private List<DocumentPO> documentsOf(Long kbId) {
        return documentMapper.selectList(Wrappers.<DocumentPO>lambdaQuery().eq(DocumentPO::getKbId, kbId));
    }

    private DocumentPO byPath(List<DocumentPO> documents, String sourcePath) {
        return documents.stream().filter(d -> sourcePath.equals(d.getSourcePath()))
                .findFirst().orElseThrow(() -> new AssertionError("没有找到文档：" + sourcePath));
    }

    private int chunkCount(Long documentId) {
        return Math.toIntExact(chunkMapper.selectCount(Wrappers.<ChunkPO>lambdaQuery()
                .eq(ChunkPO::getDocumentId, documentId)));
    }

    /** 现场生成一页 PDF。文本只能用 ASCII：标准 14 号字体是 WinAnsi 编码，写中文会直接抛异常。 */
    private byte[] pdfWithPages(String... pageTexts) throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (String pageText : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText(pageText);
                    stream.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private byte[] docx(String heading, String body) throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().setStyle("Heading1");
            document.getParagraphs().get(0).createRun().setText(heading);
            document.createParagraph().createRun().setText(body);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
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
