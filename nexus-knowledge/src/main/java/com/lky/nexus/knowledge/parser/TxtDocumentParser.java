package com.lky.nexus.knowledge.parser;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 纯文本解析器：不做结构提取，整篇作为正文。
 *
 * <p>它存在的意义是"让流水线统一"：导入层不必区分"有结构的格式"和"没结构的格式"，
 * 下游切分器可以按 {@link ParsedDocument#hasHeadings()} 决定走结构化切分还是递归切分。
 */
@Component
public class TxtDocumentParser implements DocumentParser {

    private static final List<String> EXTENSIONS = List.of("txt", "text", "log");

    @Override
    public List<String> extensions() {
        return EXTENSIONS;
    }

    @Override
    public ParsedDocument parse(String fileName, byte[] content) {
        String text = content == null ? "" : new String(content, StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n');
        String title = fileName == null ? "untitled"
                : (fileName.lastIndexOf('.') < 0 ? fileName : fileName.substring(0, fileName.lastIndexOf('.')));
        return new ParsedDocument(title, text, Map.of(), List.of(), List.of(), List.of(), List.of());
    }
}
