package com.lky.nexus.knowledge.parser;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文档解析器 SPI。
 *
 * <p>每种格式一个实现，各自只关心"如何把这种格式拆成结构"。
 * 新增格式只需加一个实现并注册到 {@link ParserRegistry}，导入流水线完全不用改。
 *
 * <p><b>入口统一为字节数组</b>（真实踩坑式修正）：最初的接口只接受 {@code String}，
 * 对 Markdown/TXT 够用，但 PDF/DOCX 是二进制格式——若为它们另开一条接口，
 * 导入流水线就要按格式分支，SPI 的意义也就没了。统一成 byte[] 后，
 * 文本解析器在内部按 UTF-8 解码，二进制解析器直接消费字节，
 * 调用方永远只有一种写法。
 */
public interface DocumentParser {

    /**
     * 该解析器处理的扩展名（小写、不含点）。
     *
     * <p>它是"支持哪些格式"的<b>唯一事实来源</b>：{@link ParserRegistry#supportedExtensions()}
     * 由各解析器汇总而来。此前这份清单在注册表里硬编码，新增解析器时极易忘记同步——
     * 那种不一致的表现是"解析器写好了，但压缩包导入阶段就把这类文件过滤掉了"。
     */
    List<String> extensions();

    /** 是否能处理该文件。 */
    default boolean supports(String fileName) {
        return extensions().contains(ParserRegistry.extensionOf(fileName));
    }

    /**
     * 解析。
     *
     * @param fileName 文件名（用于兜底生成标题）
     * @param content  原始字节
     * @return 解析出的结构
     * @throws com.lky.nexus.common.exception.BusinessException 解析失败（错误码 3004）
     */
    ParsedDocument parse(String fileName, byte[] content);

    /** 文本便捷重载：便于纯文本场景与测试书写。 */
    default ParsedDocument parse(String fileName, String text) {
        return parse(fileName, text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
    }
}
