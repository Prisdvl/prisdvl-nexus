package com.lky.nexus.knowledge.parser;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DOCX 解析测试。
 *
 * <p>DOCX 与 PDF 的关键差别在这里体现得最清楚：<b>DOCX 的标题是有样式的</b>，
 * 因此能抽出真正的标题层级，切片质量与 Markdown 笔记同级。
 */
class DocxDocumentParserTest {

    private final DocxDocumentParser parser = new DocxDocumentParser();

    @Test
    @DisplayName("按样式抽出标题层级，并写回 Markdown 结构（下游据此做标题级切分）")
    void extractsHeadingHierarchy() throws Exception {
        byte[] docx = buildDocument();

        ParsedDocument document = parser.parse("spring-boot.docx", docx);

        assertThat(document.headings()).extracting(ParsedDocument.Heading::text)
                .containsExactly("Spring Boot", "自动配置", "起步依赖");
        assertThat(document.headings()).extracting(ParsedDocument.Heading::level)
                .containsExactly(1, 2, 2);
        // 标题在正文中以 Markdown 形式保留，偏移量指向标题行首（与 Markdown 解析器同一套约定）
        ParsedDocument.Heading second = document.headings().get(1);
        assertThat(document.content().substring(second.startOffset())).startsWith("## 自动配置");
        // 文档属性里的 title 优先于首个一级标题（与 Markdown 解析器"frontmatter.title 优先"一致）
        assertThat(document.title()).isEqualTo("Spring Boot 学习笔记");
        assertThat(document.hasHeadings()).isTrue();
    }

    @Test
    @DisplayName("表格转成 Markdown 表格文本（下游识别为 TABLE 并整体保留，不被腰斩）")
    void convertsTablesToMarkdown() throws Exception {
        ParsedDocument document = parser.parse("table.docx", buildDocument());

        assertThat(document.content()).contains("| 组件 | 作用 |");
        assertThat(document.content()).contains("| Nacos | 注册中心 |");
        assertThat(document.frontmatter()).containsEntry("tables", 1);
    }

    @Test
    @DisplayName("文档属性作为元数据；无属性标题时用首个一级标题兜底")
    void readsCoreProperties() throws Exception {
        ParsedDocument document = parser.parse("notes.docx", buildDocument());

        assertThat(document.frontmatter()).containsEntry("sourceFormat", "DOCX")
                .containsEntry("author", "LKY");
        assertThat(document.frontmatter().get("title")).isEqualTo("Spring Boot 学习笔记");
        // 属性里有 title 时优先用它，而不是首个 H1
        assertThat(document.title()).isEqualTo("Spring Boot 学习笔记");
    }

    @Test
    @DisplayName("损坏文件 / 旧版 .doc 改名：给出可操作的提示，而不是内部异常")
    void reportsInvalidInput() {
        assertThatThrownBy(() -> parser.parse("empty.docx", new byte[0]))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("为空");

        assertThatThrownBy(() -> parser.parse("old.doc.docx", "not a real docx".getBytes()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(".doc")
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.DOCUMENT_PARSE_FAILED.getCode());
    }

    @Test
    @DisplayName("扩展名声明：只处理 docx（旧版 .doc 需先转换）")
    void declaresExtensions() {
        assertThat(parser.extensions()).containsExactly("docx");
        assertThat(parser.supports("a.docx")).isTrue();
        assertThat(parser.supports("a.doc")).isFalse();
    }

    // ------------------------------------------------------------------

    private byte[] buildDocument() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            document.getProperties().getCoreProperties().setTitle("Spring Boot 学习笔记");
            document.getProperties().getCoreProperties().setCreator("LKY");

            heading(document, "Heading1", "Spring Boot");
            body(document, "Spring Boot 通过条件装配决定加载哪些 Bean。");

            heading(document, "Heading2", "自动配置");
            body(document, "条件装配是自动配置的核心机制。");

            heading(document, "Heading2", "起步依赖");
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("组件");
            table.getRow(0).getCell(1).setText("作用");
            table.getRow(1).getCell(0).setText("Nacos");
            table.getRow(1).getCell(1).setText("注册中心");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }

    private void heading(XWPFDocument document, String styleId, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle(styleId);
        paragraph.createRun().setText(text);
    }

    private void body(XWPFDocument document, String text) {
        document.createParagraph().createRun().setText(text);
    }
}
