package com.lky.nexus.knowledge.parser;

/**
 * 文档解析器 SPI。
 *
 * <p>每种格式一个实现，各自只关心"如何把这种格式拆成结构"。
 * 新增格式（如 HTML、EPUB）只需加一个实现并注册到 {@link ParserRegistry}，
 * 导入流水线完全不用改——这是 Phase 2 后续加入 PDF / DOCX 的接入点。
 */
public interface DocumentParser {

    /**
     * 是否能处理该文件。
     *
     * @param fileName 文件名（用于取扩展名）
     */
    boolean supports(String fileName);

    /**
     * 解析。
     *
     * @param fileName    文件名（用于兜底生成标题）
     * @param rawContent  原始文本内容
     * @return 解析出的结构
     * @throws com.lky.nexus.common.exception.BusinessException 解析失败（错误码 3004）
     */
    ParsedDocument parse(String fileName, String rawContent);
}
