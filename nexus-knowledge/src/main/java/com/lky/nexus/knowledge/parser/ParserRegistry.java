package com.lky.nexus.knowledge.parser;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 解析器注册表：按文件扩展名分发给对应的 {@link DocumentParser}。
 *
 * <p>用"注册表 + 策略"而不是 if-else 链：新增格式时只加实现类，
 * 这里与导入流水线都不用改（开闭原则的实际收益）。
 */
@Component
public class ParserRegistry {

    private final List<DocumentParser> parsers;

    public ParserRegistry(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    /**
     * 找到能处理该文件的解析器。
     *
     * @throws BusinessException 3005（不支持的文件类型）—— 让用户明确知道是格式问题，
     *         而不是收到一句含糊的"导入失败"
     */
    public DocumentParser requireParser(String fileName) {
        return parsers.stream()
                .filter(parser -> parser.supports(fileName))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED,
                        "暂不支持的文件类型：" + fileName + "（当前支持 " + supportedExtensions() + "）"));
    }

    /** 当前支持的扩展名，用于接口文档与错误提示。 */
    public List<String> supportedExtensions() {
        return List.of("md", "markdown", "txt", "pdf", "docx");
    }

    /** 扩展名小写化（无扩展名返回空串）。 */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
