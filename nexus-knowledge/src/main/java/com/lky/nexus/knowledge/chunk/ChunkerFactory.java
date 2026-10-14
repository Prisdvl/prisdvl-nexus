package com.lky.nexus.knowledge.chunk;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.model.ChunkStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 切分器工厂：按知识库冻结的策略选择实现。
 *
 * <p>与解析器注册表同样的思路——策略是可扩展的，导入流水线只依赖
 * {@link DocumentChunker} 接口，新增策略不需要改流水线。
 */
@Component
public class ChunkerFactory {

    private final List<DocumentChunker> chunkers;

    public ChunkerFactory(List<DocumentChunker> chunkers) {
        this.chunkers = chunkers;
    }

    /**
     * 按策略选择切分器实现。
     *
     * <p>{@code null} 视为默认策略。没有匹配实现时抛异常——
     * 这通常意味着知识库配置了一个不存在的切分策略。
     *
     * @param strategy 知识库冻结的切分策略
     * @return 对应的切分器
     * @throws BusinessException 没有可用的实现时（策略配置错误）
     */
    public DocumentChunker of(ChunkStrategy strategy) {
        ChunkStrategy target = strategy == null ? ChunkStrategy.DEFAULT : strategy;
        return chunkers.stream()
                .filter(chunker -> chunker.strategy() == target)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "没有可用的切分器实现：" + target));
    }

    /**
     * 按策略名（字符串）选择切分器实现，是 {@link #of(ChunkStrategy)} 的便捷入口。
     *
     * @param strategyName 策略名，如 {@code MARKDOWN_HEADING}、{@code RECURSIVE}
     * @return 对应的切分器
     * @throws BusinessException 名称无法解析，或没有可用的实现时
     */
    public DocumentChunker of(String strategyName) {
        return of(ChunkStrategy.parse(strategyName));
    }
}
