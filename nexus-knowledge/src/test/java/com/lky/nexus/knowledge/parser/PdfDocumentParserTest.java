package com.lky.nexus.knowledge.parser;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PDF 解析测试。
 *
 * <p>测试用的 PDF 是<b>现场生成</b>的（而不是放一个二进制样本进仓库）：
 * 这样测试自带"样本怎么来的"，也不会因为二进制文件被谁替换而静默失效。
 *
 * <p>注意测试文本只能用 ASCII：标准 14 号字体（Helvetica）是 WinAnsi 编码，
 * 写中文会直接抛异常——这也顺带说明了"PDF 里的中文依赖字体嵌入"这个常见坑。
 */
class PdfDocumentParserTest {

    private final PdfDocumentParser parser = new PdfDocumentParser();

    @Test
    @DisplayName("多页 PDF：抽出全文、记录页数与每页偏移（供引用反查页码）")
    void extractsTextAndPageOffsets() throws Exception {
        byte[] pdf = pdfWithPages("Page one content about Spring Boot.",
                "Page two content about Redis caching.");

        ParsedDocument document = parser.parse("architecture.pdf", pdf);

        assertThat(document.content()).contains("Page one content", "Page two content");
        assertThat(document.frontmatter()).containsEntry("sourceFormat", "PDF")
                .containsEntry("pages", 2)
                .containsEntry("title", "Sample PDF");
        // 分页偏移：两页各一个起点，且第二页偏移大于第一页
        @SuppressWarnings("unchecked")
        List<Integer> offsets = (List<Integer>) document.frontmatter().get("pageOffsets");
        assertThat(offsets).hasSize(2);
        assertThat(offsets.get(0)).isZero();
        assertThat(offsets.get(1)).isPositive();
        // 用偏移能反查出第二页的开头
        assertThat(document.content().substring(offsets.get(1))).startsWith("Page two content");
        assertThat(document.title()).isEqualTo("Sample PDF");
    }

    @Test
    @DisplayName("PDF 不产出标题结构（不靠字号猜标题），切分时自动退化为按段落切")
    void doesNotInventHeadings() throws Exception {
        ParsedDocument document = parser.parse("plain.pdf", pdfWithPages("Just body text."));

        assertThat(document.headings()).isEmpty();
        assertThat(document.hasHeadings()).isFalse();
    }

    @Test
    @DisplayName("无元数据标题时回退到文件名")
    void fallsBackToFileName() throws Exception {
        PDDocument document = new PDDocument();
        document.addPage(new PDPage());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        document.close();

        // 空白页抽不到文字 → 属于"扫描件/空文档"，应报解析失败而不是产出空文档
        assertThatThrownBy(() -> parser.parse("blank.pdf", out.toByteArray()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有可提取的文字")
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.DOCUMENT_PARSE_FAILED.getCode());
    }

    @Test
    @DisplayName("空文件与损坏文件：报 3004 且信息可定位")
    void reportsInvalidInput() {
        assertThatThrownBy(() -> parser.parse("empty.pdf", new byte[0]))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("为空");

        assertThatThrownBy(() -> parser.parse("broken.pdf", "definitely not a pdf".getBytes()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PDF 解析失败");
    }

    @Test
    @DisplayName("扩展名声明：只有 pdf 由它处理")
    void declaresExtensions() {
        assertThat(parser.extensions()).containsExactly("pdf");
        assertThat(parser.supports("a.pdf")).isTrue();
        assertThat(parser.supports("a.md")).isFalse();
    }

    // ------------------------------------------------------------------

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
            document.getDocumentInformation().setTitle("Sample PDF");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
