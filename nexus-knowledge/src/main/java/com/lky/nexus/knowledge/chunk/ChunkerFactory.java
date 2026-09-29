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

    public DocumentChunker of(ChunkStrategy strategy) {
        ChunkStrategy target = strategy == null ? ChunkStrategy.DEFAULT : strategy;
        return chunkers.stream()
                .filter(chunker -> chunker.strategy() == target)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "没有可用的切分器实现：" + target));
    }

    public DocumentChunker of(String strategyName) {
        return of(ChunkStrategy.parse(strategyName));
    }
}
