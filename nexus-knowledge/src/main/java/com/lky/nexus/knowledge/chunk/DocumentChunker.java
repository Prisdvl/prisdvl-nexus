package com.lky.nexus.knowledge.chunk;

import com.lky.nexus.knowledge.model.ChunkStrategy;
import com.lky.nexus.knowledge.parser.ParsedDocument;

import java.util.List;

/**
 * 文档切分器 SPI。
 *
 * <p>一种策略一个实现，由 {@link ChunkerFactory} 按知识库冻结的策略选择。
 * 新增策略（例如语义切分、按代码块优先）只需加实现，导入流水线不用改。
 */
public interface DocumentChunker {

    /** 该实现对应的策略。 */
    ChunkStrategy strategy();

    /**
     * 切分。
     *
     * @param document 解析结果（含正文、标题、偏移量）
     * @param options  切分参数
     * @return 切片列表，按顺序排列；空文档返回空列表
     */
    List<TextChunk> chunk(ParsedDocument document, ChunkingOptions options);
}
