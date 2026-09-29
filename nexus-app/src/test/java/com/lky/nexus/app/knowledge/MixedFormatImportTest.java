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
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
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
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step 2.3 验收测试：混合格式 Vault 导入（Markdown + PDF + DOCX）。
 *
 * <p>单测只证明"解析器能解析"，这里要证明的是<b>它们能接进同一条导入流水线</b>：
 * 安全解压 → 按扩展名分发解析器 → 结构化切分 → 落库，且三种格式的结构差异如实反映在数据里
 * （Markdown/DOCX 有标题路径，PDF 没有——因为 PDF 里本来就没有标题这种语义）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MixedFormatImportTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private ChunkMapper chunkMapper;

    @Test
    @DisplayName("一个 Vault 里混有 md/pdf/docx：全部导入，且各自的结构差异如实保留")
    void importsMixedFormats() throws Exception {
        String token = registerAndLogin("mixed-format");
        Long kbId = createKnowledgeBase(token, "混合格式");

        mockMvc.perform(multipart("/api/knowledge-bases/" + kbId + "/vault")
                        .file(new MockMultipartFile("file", "MixedVault.zip", "application/zip",
                                mixedVault()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.createdCount").value(3))
                .andExpect(jsonPath("$.data.failedCount").value(0));

        List<DocumentPO> documents = documentMapper.selectList(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, kbId));
        assertThat(documents).extracting(DocumentPO::getFileExt)
                .containsExactlyInAnyOrder("md", "pdf", "docx");
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.getStatus()).isEqualTo("READY");
            assertThat(document.getChunkCount()).isPositive();
        });

        // ---------- Markdown：标题树 + 标题路径 ----------
        DocumentPO markdown = documentOf(documents, "Java/笔记.md");
        assertThat(markdown.getHeadingTree()).contains("自动配置");
        assertThat(headingPathsOf(markdown.getId())).allMatch(path -> path != null && path.contains(">"));

        // ---------- DOCX：有样式即有真标题层级 ----------
        DocumentPO docx = documentOf(documents, "Docs/需求.docx");
        assertThat(docx.getHeadingTree()).contains("功能需求");
        assertThat(docx.getFrontmatter()).contains("DOCX");
        assertThat(headingPathsOf(docx.getId()))
                .as("DOCX 的标题来自样式，因此切片能带上标题路径")
                .allMatch(path -> path != null && path.contains("功能需求"));

        // ---------- PDF：不猜标题，但保留分页信息（引用可反查页码） ----------
        DocumentPO pdf = documentOf(documents, "Papers/架构.pdf");
        assertThat(pdf.getFrontmatter()).contains("PDF").contains("pages");
        assertThat(pdf.getHeadingTree()).as("PDF 不产出标题结构").isNull();
        assertThat(headingPathsOf(pdf.getId())).allMatch(java.util.Objects::isNull);
    }

    // ------------------------------------------------------------------

    private DocumentPO documentOf(List<DocumentPO> documents, String sourcePath) {
        return documents.stream()
                .filter(document -> document.getSourcePath().equals(sourcePath))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到文档 " + sourcePath));
    }

    private List<String> headingPathsOf(Long documentId) {
        return chunkMapper.selectList(Wrappers.<ChunkPO>lambdaQuery()
                        .eq(ChunkPO::getDocumentId, documentId).orderByAsc(ChunkPO::getChunkIndex))
                .stream().map(ChunkPO::getHeadingPath).toList();
    }

    private byte[] mixedVault() throws Exception {
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put("Vault/Java/笔记.md", """
                # Spring Boot

                ## 自动配置

                条件装配决定加载哪些 Bean。
                """.getBytes(StandardCharsets.UTF_8));
        entries.put("Vault/Papers/架构.pdf", samplePdf());
        entries.put("Vault/Docs/需求.docx", sampleDocx());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** 造一份两页 PDF（文本必须用 ASCII：标准 14 号字体不支持中文）。 */
    private byte[] samplePdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (String line : List.of("Architecture overview of the RAG pipeline.",
                    "Retrieval combines vector search and BM25.")) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText(line);
                    stream.endText();
                }
            }
            document.getDocumentInformation().setTitle("Architecture");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /** 造一份带标题样式与表格的 DOCX。 */
    private byte[] sampleDocx() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            document.getProperties().getCoreProperties().setTitle("需求说明");

            XWPFParagraph title = document.createParagraph();
            title.setStyle("Heading1");
            title.createRun().setText("需求说明");

            XWPFParagraph section = document.createParagraph();
            section.setStyle("Heading2");
            section.createRun().setText("功能需求");

            document.createParagraph().createRun().setText("系统需要支持知识库导入与检索。");

            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("编号");
            table.getRow(0).getCell(1).setText("描述");
            table.getRow(1).getCell(0).setText("R1");
            table.getRow(1).getCell(1).setText("支持增量同步");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
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
