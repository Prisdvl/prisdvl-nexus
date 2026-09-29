package com.lky.nexus.knowledge.parser;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 解析器（含 Obsidian 方言）。
 *
 * <p><b>为什么手写而不是用 CommonMark 之类的渲染库</b>：我们要的不是 HTML，
 * 而是"结构 + 偏移量"。渲染库会把语法糖消化掉（例如把 {@code [[WikiLink]]} 当普通文本），
 * 而 WikiLink、frontmatter、代码块边界恰恰是本项目最需要的信息。
 *
 * <p>提取内容：
 * <ol>
 *   <li>YAML frontmatter（title / tags / 自定义字段）；</li>
 *   <li>标题层级（#~######），带字符偏移与锚点；</li>
 *   <li>标签：frontmatter 的 {@code tags}/{@code tag} + 正文内联 {@code #tag}；</li>
 *   <li>链接：{@code [[wiki]]}、{@code [[wiki|别名]]}、{@code [文本](地址)}；</li>
 *   <li>附件：{@code ![alt](src)}、{@code ![[图.png]]}。</li>
 * </ol>
 *
 * <p><b>关键实现细节：扫描时必须跳过代码块</b>。
 * 笔记里经常出现 {@code # 这是注释}、{@code [[不是链接]]}、{@code #tag} 这类
 * 出现在代码围栏内的文本；若不跳过，会产生大量假标题与假标签，
 * 直接污染切片与知识关联——这是 Markdown 解析最典型的坑。
 */
@Component
public class MarkdownDocumentParser implements DocumentParser {

    private static final List<String> EXTENSIONS = List.of("md", "markdown");

    /** 标题：# ~ ######，后跟空白与文本；结尾的 # 会被去掉。 */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    /** 代码围栏：``` 或 ~~~（允许跟随语言标识）。 */
    private static final Pattern CODE_FENCE = Pattern.compile("^(```+|~~~+)\\s*\\S*\\s*$");

    /** 行内代码，扫描标签/链接前先剔除，避免把 `#不是标签` 当标签。 */
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`]*`");

    /** Obsidian 嵌入：![[目标.png]] / ![[目标|别名]] */
    private static final Pattern WIKI_EMBED = Pattern.compile("!\\[\\[([^\\[\\]|]+)(?:\\|([^\\[\\]]+))?]]");

    /** Obsidian 双链：[[目标]] / [[目标|别名]] / [[目标#小节]] */
    private static final Pattern WIKILINK = Pattern.compile("(?<!!)\\[\\[([^\\[\\]|#]+)(?:#([^\\[\\]|]+))?(?:\\|([^\\[\\]]+))?]]");

    /** Markdown 图片：![alt](src) */
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");

    /** Markdown 链接：[文本](地址) —— 前面加 (?<!!) 排除图片。 */
    private static final Pattern MD_LINK = Pattern.compile("(?<!!)\\[([^\\]]+)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");

    /** 内联标签：#后跟中英文/数字/下划线/连字符/斜杠；# 前必须是行首或空白。 */
    private static final Pattern INLINE_TAG = Pattern.compile("(?:^|\\s)#([\\p{L}\\p{N}_][\\p{L}\\p{N}_/-]*)");

    private static final String FRONTMATTER_DELIMITER = "---";
    private static final List<String> FRONTMATTER_END = List.of("---", "...");

    @Override
    public List<String> extensions() {
        return EXTENSIONS;
    }

    @Override
    public ParsedDocument parse(String fileName, byte[] content) {
        String rawContent = content == null ? "" : new String(content, StandardCharsets.UTF_8);
        String normalized = normalize(rawContent);
        FrontmatterExtraction frontmatter = extractFrontmatter(normalized);

        String body = frontmatter.body();
        Map<String, Object> metadata = frontmatter.metadata();

        List<ParsedDocument.Heading> headings = new ArrayList<>();
        List<ParsedDocument.Link> links = new ArrayList<>();
        List<ParsedDocument.Attachment> attachments = new ArrayList<>();
        Set<String> tags = new LinkedHashSet<>(tagsFromFrontmatter(metadata));

        scan(body, headings, links, attachments, tags);

        String title = resolveTitle(metadata, headings, fileName);
        return new ParsedDocument(title, body, metadata, List.copyOf(tags),
                headings, links, attachments);
    }

    // ------------------------------------------------------------------
    // 正文扫描（跳过代码块）
    // ------------------------------------------------------------------

    private void scan(String body,
                      List<ParsedDocument.Heading> headings,
                      List<ParsedDocument.Link> links,
                      List<ParsedDocument.Attachment> attachments,
                      Set<String> tags) {
        boolean insideFence = false;
        int offset = 0;

        for (String line : body.split("\n", -1)) {
            boolean fenceLine = CODE_FENCE.matcher(line).matches();
            if (fenceLine) {
                insideFence = !insideFence;
                offset += line.length() + 1;
                continue;
            }
            if (insideFence) {
                // 代码块内的 # 标题、[[双链]]、#标签 一律不算结构
                offset += line.length() + 1;
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                String text = heading.group(2).trim();
                headings.add(new ParsedDocument.Heading(heading.group(1).length(), text, offset, slug(text)));
            }

            // 行内代码先剔除，避免把 `#注释`、`[[代码里的]]` 当成结构
            String scannable = INLINE_CODE.matcher(line).replaceAll(match -> " ".repeat(match.group().length()));

            collectAttachments(scannable, offset, attachments);
            collectLinks(scannable, offset, links);
            collectInlineTags(scannable, tags);

            offset += line.length() + 1;
        }
    }

    private void collectAttachments(String line, int lineOffset, List<ParsedDocument.Attachment> attachments) {
        // Obsidian 图片嵌入：![[xxx.png]]
        Matcher wikiEmbed = WIKI_EMBED.matcher(line);
        while (wikiEmbed.find()) {
            String target = wikiEmbed.group(1).trim();
            if (isImage(target)) {
                attachments.add(new ParsedDocument.Attachment(target,
                        wikiEmbed.group(2) == null ? "" : wikiEmbed.group(2).trim(),
                        lineOffset + wikiEmbed.start()));
            }
        }
        // Markdown 图片：![alt](src)
        Matcher mdImage = MD_IMAGE.matcher(line);
        while (mdImage.find()) {
            attachments.add(new ParsedDocument.Attachment(mdImage.group(2).trim(),
                    mdImage.group(1).trim(), lineOffset + mdImage.start()));
        }
    }

    private void collectLinks(String line, int lineOffset, List<ParsedDocument.Link> links) {
        // Obsidian 非图片嵌入 ![[另一篇笔记]]
        Matcher wikiEmbed = WIKI_EMBED.matcher(line);
        while (wikiEmbed.find()) {
            String target = wikiEmbed.group(1).trim();
            if (!isImage(target)) {
                links.add(new ParsedDocument.Link(ParsedDocument.LinkType.EMBED, target,
                        wikiEmbed.group(2) == null ? target : wikiEmbed.group(2).trim(),
                        lineOffset + wikiEmbed.start()));
            }
        }
        // Obsidian 双链 [[目标#小节|别名]]
        Matcher wikilink = WIKILINK.matcher(line);
        while (wikilink.find()) {
            String target = wikilink.group(1).trim();
            String anchor = wikilink.group(2);
            String full = anchor == null ? target : target + "#" + anchor.trim();
            links.add(new ParsedDocument.Link(ParsedDocument.LinkType.WIKILINK, full,
                    wikilink.group(3) == null ? target : wikilink.group(3).trim(),
                    lineOffset + wikilink.start()));
        }
        // Markdown 链接
        Matcher mdLink = MD_LINK.matcher(line);
        while (mdLink.find()) {
            links.add(new ParsedDocument.Link(ParsedDocument.LinkType.MARKDOWN_LINK,
                    mdLink.group(2).trim(), mdLink.group(1).trim(), lineOffset + mdLink.start()));
        }
    }

    private void collectInlineTags(String scannable, Set<String> tags) {
        Matcher matcher = INLINE_TAG.matcher(scannable);
        while (matcher.find()) {
            // 无需特判标题行："# 标题" 里的 # 后面是空格，不满足 #后必须紧跟字母/数字的规则
            tags.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
    }

    // ------------------------------------------------------------------
    // frontmatter
    // ------------------------------------------------------------------

    private record FrontmatterExtraction(Map<String, Object> metadata, String body) {
    }

    private FrontmatterExtraction extractFrontmatter(String content) {
        String withoutBom = content.startsWith("\uFEFF") ? content.substring(1) : content;
        if (!withoutBom.startsWith(FRONTMATTER_DELIMITER)) {
            return new FrontmatterExtraction(Map.of(), withoutBom);
        }
        String[] lines = withoutBom.split("\n", -1);
        if (lines.length == 0 || !FRONTMATTER_DELIMITER.equals(lines[0].trim())) {
            return new FrontmatterExtraction(Map.of(), withoutBom);
        }
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if (FRONTMATTER_END.contains(lines[i].trim())) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            // 只有开头没有结尾：当作普通正文（不报错），避免因一个破折号毁掉整篇导入
            return new FrontmatterExtraction(Map.of(), withoutBom);
        }

        String yamlText = String.join("\n", List.of(lines).subList(1, end));
        Map<String, Object> metadata = parseYaml(yamlText);
        String body = String.join("\n", List.of(lines).subList(end + 1, lines.length));
        // 去掉 frontmatter 后常见的开头空行，保持偏移量稳定且直观
        body = stripLeadingBlankLines(body);
        return new FrontmatterExtraction(metadata, body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYaml(String yamlText) {
        if (yamlText.isBlank()) {
            return Map.of();
        }
        try {
            Object loaded = new Yaml().load(yamlText);
            if (loaded instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
            return Map.of();
        } catch (Exception ex) {
            // frontmatter 写坏了是常见情况（缩进/冒号），给出可定位的错误而不是空 Map
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED,
                    "frontmatter YAML 解析失败：" + ex.getMessage());
        }
    }

    private String stripLeadingBlankLines(String body) {
        int index = 0;
        while (index < body.length() && (body.charAt(index) == '\n' || body.charAt(index) == '\r')) {
            index++;
        }
        return body.substring(index);
    }

    private List<String> tagsFromFrontmatter(Map<String, Object> metadata) {
        List<String> result = new ArrayList<>();
        collectFrontmatterTags(metadata.get("tags"), result);
        collectFrontmatterTags(metadata.get("tag"), result);
        return result;
    }

    private void collectFrontmatterTags(Object value, List<String> target) {
        switch (value) {
            case null -> {
            }
            case List<?> list -> list.forEach(item -> collectFrontmatterTags(item, target));
            case String text -> {
                // 支持 "Java, Spring" 与 "Java" 两种写法
                for (String part : text.split(",")) {
                    String tag = normalizeTag(part);
                    if (!tag.isEmpty()) {
                        target.add(tag);
                    }
                }
            }
            default -> {
                String tag = normalizeTag(String.valueOf(value));
                if (!tag.isEmpty()) {
                    target.add(tag);
                }
            }
        }
    }

    private String normalizeTag(String raw) {
        String tag = raw == null ? "" : raw.trim();
        while (tag.startsWith("#")) {
            tag = tag.substring(1);
        }
        return tag.toLowerCase(Locale.ROOT);
    }

    private String resolveTitle(Map<String, Object> metadata, List<ParsedDocument.Heading> headings, String fileName) {
        Object frontmatterTitle = metadata.get("title");
        if (frontmatterTitle != null && !String.valueOf(frontmatterTitle).isBlank()) {
            return String.valueOf(frontmatterTitle).trim();
        }
        // 首个一级标题优先（比任意级别的第一个标题更符合直觉）
        for (ParsedDocument.Heading heading : headings) {
            if (heading.level() == 1) {
                return heading.text();
            }
        }
        if (!headings.isEmpty()) {
            return headings.get(0).text();
        }
        return stripExtension(fileName);
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private boolean isImage(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".svg")
                || lower.endsWith(".bmp") || lower.endsWith(".avif");
    }

    /** 统一换行符，避免 CRLF 让偏移量与正则边界判断出现偏差。 */
    private String normalize(String rawContent) {
        return rawContent == null ? "" : rawContent.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 生成锚点：保留中英文与数字，空白转连字符，其余去掉。
     *
     * <p>中文标题直接保留（浏览器会自动做 URL 编码），这样"跳转到 #自动配置"是可读的。
     */
    static String slug(String text) {
        String slug = text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "-")
                .replaceAll("[^\\p{L}\\p{N}-]", "");
        return slug.isEmpty() ? "section" : slug;
    }
}
