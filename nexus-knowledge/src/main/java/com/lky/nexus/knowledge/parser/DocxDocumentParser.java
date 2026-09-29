package com.lky.nexus.knowledge.parser;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOCX 解析器（Apache POI）。
 *
 * <p><b>DOCX 比 PDF 幸运的地方</b>：标题带有样式（Heading 1/2/3…），
 * 因此能抽出**真正的标题层级**，而不是靠字号猜。这让 DOCX 能直接吃到
 * {@code MarkdownHeadingChunker} 的标题级切分，切片质量与 Markdown 笔记同级。
 *
 * <p>三处实现细节：
 * <ol>
 *   <li><b>按文档顺序遍历 {@code getBodyElements()}</b>：如果分别遍历"所有段落"和"所有表格"，
 *       表格就会全部跑到文末，正文的图文顺序被破坏；</li>
 *   <li><b>标题识别有三条路径</b>：先读大纲级别（{@code outlineLvl}，最可靠），
 *       再匹配样式名/ID（{@code Heading1}、{@code heading 2}，以及中文模板里常见的数字样式 ID {@code 1}）；
 *       都识别不出就当作正文段落——宁可少认标题，也不要错认；</li>
 *   <li><b>表格转成 Markdown 表格文本</b>：这样下游分段器能识别为 TABLE 类型并整体保留
 *       （表格被腰斩会让表头与数据行分离，模型会误读）。</li>
 * </ol>
 *
 * <p>已知限制：列表项暂按普通段落处理（不还原 {@code - } 前缀），
 * 排版信息（字号、颜色、批注、修订）不保留——RAG 需要的是内容与结构，不是排版。
 */
@Component
public class DocxDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocxDocumentParser.class);

    private static final List<String> EXTENSIONS = List.of("docx");

    /** "Heading1" / "heading 2" / "Heading 3" 这类样式名或样式 ID。 */
    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)heading\\s*([1-6])");

    /** 中文 Word 模板里标题样式 ID 常常就是数字（"1" 表示一级标题）。 */
    private static final Pattern NUMERIC_STYLE = Pattern.compile("^([1-6])$");

    @Override
    public List<String> extensions() {
        return EXTENSIONS;
    }

    @Override
    public ParsedDocument parse(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "DOCX 文件为空：" + fileName);
        }

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            StringBuilder body = new StringBuilder();
            List<ParsedDocument.Heading> headings = new ArrayList<>();

            for (IBodyElement element : document.getBodyElements()) {
                switch (element) {
                    case XWPFParagraph paragraph -> {
                        String text = clean(paragraph.getText());
                        if (text.isEmpty()) {
                            continue;
                        }
                        int level = headingLevel(paragraph);
                        if (level > 0) {
                            // 标题以 Markdown 形式写入正文：既保持可读/可引用，
                            // 也让偏移量与 Markdown 解析器保持同一套约定（指向标题行首）
                            headings.add(new ParsedDocument.Heading(level, text, body.length(),
                                    MarkdownDocumentParser.slug(text)));
                            body.append("#".repeat(level)).append(' ').append(text).append("\n\n");
                        } else {
                            body.append(text).append("\n\n");
                        }
                    }
                    case XWPFTable table -> appendTable(body, table);
                    default -> {
                        // 其他 body 元素（如内容控件）暂不处理
                    }
                }
            }

            String normalized = body.toString().strip();
            if (normalized.isBlank()) {
                throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED,
                        "DOCX 中没有可提取的文字：" + fileName);
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("sourceFormat", "DOCX");
            var core = document.getProperties().getCoreProperties();
            putIfPresent(metadata, "title", safe(core::getTitle));
            putIfPresent(metadata, "author", safe(core::getCreator));
            putIfPresent(metadata, "subject", safe(core::getSubject));
            putIfPresent(metadata, "description", safe(core::getDescription));
            metadata.put("paragraphs", document.getParagraphs().size());
            metadata.put("tables", document.getTables().size());

            String title = resolveTitle(core::getTitle, headings, fileName);
            log.debug("DOCX 解析完成 {}：{} 个标题，{} 字符", fileName, headings.size(), normalized.length());
            return new ParsedDocument(title, normalized, metadata, List.of(), headings,
                    List.of(), List.of());

        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED,
                    "DOCX 解析失败：" + fileName + "（" + ex.getMessage() + "）");
        } catch (RuntimeException ex) {
            // POI 对"其实是 .doc / 损坏文件"会抛 NotOfficeXmlFileException 等运行时异常。
            // 这类情况很常见（用户把 .doc 改名成 .docx），必须给出可操作的提示。
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED,
                    "无法读取该 .docx（可能已损坏，或其实是旧版 .doc 文件——请先用 Word 另存为 .docx）："
                            + fileName);
        }
    }

    /**
     * 判断段落是否为标题，返回层级（1~6），非标题返回 0。
     *
     * <p>顺序很重要：大纲级别 > 样式名/ID。因为存在"样式名是 Normal 但设置了大纲级别"的文档，
     * 也存在"样式 ID 是中文模板数字"的文档。
     */
    private int headingLevel(XWPFParagraph paragraph) {
        try {
            var paragraphProperties = paragraph.getCTP().getPPr();
            if (paragraphProperties != null && paragraphProperties.getOutlineLvl() != null
                    && paragraphProperties.getOutlineLvl().getVal() != null) {
                int outlineLevel = paragraphProperties.getOutlineLvl().getVal().intValue();
                if (outlineLevel >= 0 && outlineLevel <= 5) {
                    return outlineLevel + 1;
                }
            }
        } catch (RuntimeException ex) {
            // 个别文档的 XML 结构异常，退回到样式判断
            log.trace("读取大纲级别失败，改用样式判断", ex);
        }

        for (String style : List.of(nullSafe(paragraph.getStyleID()), nullSafe(paragraph.getStyle()))) {
            if (style.isEmpty()) {
                continue;
            }
            Matcher numeric = NUMERIC_STYLE.matcher(style);
            if (numeric.matches()) {
                return Integer.parseInt(numeric.group(1));
            }
            Matcher named = HEADING_STYLE.matcher(style);
            if (named.find()) {
                return Integer.parseInt(named.group(1));
            }
        }
        return 0;
    }

    /** 表格转 Markdown 表格文本（下游据此识别为 TABLE 并整体保留）。 */
    private void appendTable(StringBuilder body, XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return;
        }
        int columnCount = rows.get(0).getTableCells().size();
        if (columnCount == 0) {
            return;
        }
        body.append("| ").append(joinCells(rows.get(0))).append(" |\n");
        body.append("|").append(" --- |".repeat(columnCount)).append('\n');
        for (int i = 1; i < rows.size(); i++) {
            body.append("| ").append(joinCells(rows.get(i))).append(" |\n");
        }
        body.append('\n');
    }

    private String joinCells(XWPFTableRow row) {
        List<XWPFTableCell> cells = row.getTableCells();
        // 表格单元格里的换行会破坏 Markdown 表格的"一行一记录"，因此压成单行
        return cells.stream()
                .map(cell -> clean(cell.getText()).replace('\n', ' '))
                .reduce((a, b) -> a + " | " + b)
                .orElse("");
    }

    private String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n')
                .replace('\t', ' ')
                .replaceAll("[ ]{2,}", " ")
                .strip();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value.trim();
    }

    private String resolveTitle(java.util.function.Supplier<String> titleSupplier,
                                List<ParsedDocument.Heading> headings, String fileName) {
        String fromProperties = safe(titleSupplier);
        if (fromProperties != null && !fromProperties.isBlank()) {
            return fromProperties.trim();
        }
        for (ParsedDocument.Heading heading : headings) {
            if (heading.level() == 1) {
                return heading.text();
            }
        }
        if (!headings.isEmpty()) {
            return headings.get(0).text();
        }
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot < 0 ? String.valueOf(fileName) : fileName.substring(0, dot);
    }

    private void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value.trim());
        }
    }

    private String safe(java.util.function.Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (Exception ex) {
            return null;
        }
    }
}
