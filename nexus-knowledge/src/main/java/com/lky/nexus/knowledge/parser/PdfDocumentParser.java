package com.lky.nexus.knowledge.parser;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PDF 解析器（PDFBox）。
 *
 * <p><b>为什么 PDF 不产出标题结构</b>：PDF 里没有"标题"这种语义，只有"某个字号加粗的一行字"。
 * 靠字号猜标题会同时产生大量假标题和漏标题，而假标题会污染切片边界与引用路径——
 * 这比"没有标题"更糟。因此这里只做忠实的文本抽取 + 段落结构，
 * 切分交给下游（无标题时自动退化为按段落递归切分）。
 *
 * <p><b>分页信息保留下来</b>：把每页的起始字符偏移写进元数据（{@code pageOffsets}），
 * 引用时就能由切片偏移反查页码——这是 PDF 场景下最有用的定位信息（"第 12 页"），
 * 也让"不猜标题"这个取舍不丢信息。
 *
 * <p>两个真实的文本质量问题：
 * <ul>
 *   <li><b>连字符换行</b>：PDF 常把长单词折成 {@code auto-\nconfiguration}，
 *       这里做去连字符（仅当下一行以小写字母开头，避免误伤 {@code e-mail} 这类词）；</li>
 *   <li><b>扫描件</b>：纯图片 PDF 抽不出任何文字，会得到空结果——识别为解析失败并明确告知
 *       （需要 OCR），而不是悄悄导入一篇空文档。</li>
 * </ul>
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentParser.class);

    private static final List<String> EXTENSIONS = List.of("pdf");

    /** 跨行连字符：字母 + 连字符 + 换行 + 小写字母 → 去掉连字符与换行。 */
    private static final Pattern HYPHEN_LINEBREAK = Pattern.compile("(?<=\\p{L})-\\n(?=\\p{Ll})");

    /** 只在页数不多时才记录分页偏移（避免元数据 JSON 过大）。 */
    private static final int MAX_TRACKED_PAGES = 300;

    @Override
    public List<String> extensions() {
        return EXTENSIONS;
    }

    @Override
    public ParsedDocument parse(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "PDF 文件为空：" + fileName);
        }

        try (PDDocument document = Loader.loadPDF(content)) {
            int pages = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            // 按位置排序：不排序时多栏 PDF 的文字顺序会错乱
            stripper.setSortByPosition(true);

            StringBuilder text = new StringBuilder();
            List<Integer> pageOffsets = new ArrayList<>();
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                pageOffsets.add(text.length());
                text.append(pageText == null ? "" : pageText.stripTrailing());
                if (page < pages) {
                    text.append("\n\n");
                }
            }

            String body = normalizeBody(text.toString());
            if (body.isBlank()) {
                throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED,
                        "PDF 中没有可提取的文字（可能是扫描件，需要 OCR）：" + fileName);
            }

            PDDocumentInformation info = document.getDocumentInformation();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("sourceFormat", "PDF");
            metadata.put("pages", pages);
            putIfPresent(metadata, "title", safe(info::getTitle));
            putIfPresent(metadata, "author", safe(info::getAuthor));
            putIfPresent(metadata, "subject", safe(info::getSubject));
            putIfPresent(metadata, "keywords", safe(info::getKeywords));
            if (pages <= MAX_TRACKED_PAGES) {
                metadata.put("pageOffsets", pageOffsets);
            }

            String title = firstNonBlank(safe(info::getTitle), stripExtension(fileName));
            log.debug("PDF 解析完成 {}：{} 页，{} 字符", fileName, pages, body.length());
            return new ParsedDocument(title, body, metadata, List.of(), List.of(), List.of(), List.of());

        } catch (InvalidPasswordException ex) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED,
                    "PDF 已加密，无法解析（请先去除密码）：" + fileName);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED,
                    "PDF 解析失败：" + fileName + "（" + ex.getMessage() + "）");
        }
    }

    /** 去连字符换行 + 统一换行符 + 压缩过多空行。 */
    private String normalizeBody(String raw) {
        String body = raw.replace("\r\n", "\n").replace('\r', '\n');
        body = HYPHEN_LINEBREAK.matcher(body).replaceAll("");
        // 三个以上连续换行压成两个：PDF 抽取常产生大段空白，留着会切出空切片
        return body.replaceAll("\n{3,}", "\n\n").strip();
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
            // 个别 PDF 的元数据字段损坏不应导致整篇解析失败
            return null;
        }
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "untitled";
    }

    private String stripExtension(String fileName) {
        if (fileName == null) {
            return "untitled";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
