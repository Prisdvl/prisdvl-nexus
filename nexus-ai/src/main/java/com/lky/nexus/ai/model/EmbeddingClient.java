package com.lky.nexus.ai.model;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 嵌入客户端：对 Spring AI {@link EmbeddingModel} 的一层薄封装。
 *
 * <p>为什么要再包一层（而不是业务代码直接用 {@code EmbeddingModel}）：
 * <ol>
 *   <li><b>维度自校验</b>。知识库在创建时冻结了向量维度（ADR-008），而"模型实际维度"
 *       只有真正调用一次才知道。如果两者不一致，写进向量库的向量宽度就是错的——
 *       这种错误不会在写入时报错，只会在检索时表现为"结果莫名其妙"。
 *       因此首次调用会探测实际维度并与声明的比对，不一致立即失败。</li>
 *   <li><b>批量切分</b>。一次把上千个切片塞进一个请求会让模型侧内存爆掉，
 *       这里按固定大小分批。</li>
 *   <li><b>统一观测</b>。耗时、条数、维度都集中在这里打点，业务层不必各自埋点。</li>
 * </ol>
 */
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    /** 单次请求的最大文本数：限制请求体大小，也避免模型侧一次性占用过多内存。 */
    private static final int MAX_BATCH_SIZE = 32;

    private final String reference;
    private final EmbeddingModel delegate;
    private final Integer declaredDimensions;

    /** 实际维度：首次调用后缓存（volatile 保证多线程可见）。 */
    private volatile Integer actualDimensions;

    public EmbeddingClient(String reference, EmbeddingModel delegate, Integer declaredDimensions) {
        this.reference = reference;
        this.delegate = delegate;
        this.declaredDimensions = declaredDimensions;
    }

    /** 模型引用（如 {@code ollama:bge-m3}），用于日志与错误信息。 */
    public String reference() {
        return reference;
    }

    /**
     * 向量维度。
     *
     * <p>首次调用会真探测一次并校验；之后直接返回缓存值。
     *
     * @throws BusinessException 4004：实际维度与配置声明不一致
     */
    public int dimensions() {
        Integer cached = actualDimensions;
        if (cached != null) {
            return cached;
        }
        return verifyDimensions(embedRaw(List.of("dimension-probe")));
    }

    /** 单条嵌入。 */
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    /**
     * 批量嵌入（自动按 {@value #MAX_BATCH_SIZE} 分批）。
     *
     * @return 与入参顺序严格对应的向量列表
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += MAX_BATCH_SIZE) {
            int to = Math.min(from + MAX_BATCH_SIZE, texts.size());
            List<String> batch = texts.subList(from, to);
            long start = System.currentTimeMillis();
            List<float[]> batchVectors = embedRaw(batch);
            long cost = System.currentTimeMillis() - start;
            verifyDimensions(batchVectors);
            log.debug("嵌入 {} 条（{}/{}），耗时 {} ms", batchVectors.size(), to, texts.size(), cost);
            vectors.addAll(batchVectors);
        }
        return vectors;
    }

    private List<float[]> embedRaw(List<String> texts) {
        try {
            return delegate.embed(texts);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            // 模型侧的任何问题（服务未启动、模型未拉取、超时）都归为 4004，
            // 并带上模型引用，便于定位"是哪个角色挂了"
            throw new BusinessException(ErrorCode.EMBEDDING_FAILED,
                    "嵌入调用失败（%s）：%s".formatted(reference, ex.getMessage()));
        }
    }

    /** 校验实际维度；首个向量为空也视为异常（模型返回了空结果）。 */
    private int verifyDimensions(List<float[]> vectors) {
        if (vectors.isEmpty() || vectors.get(0) == null || vectors.get(0).length == 0) {
            throw new BusinessException(ErrorCode.EMBEDDING_FAILED,
                    "嵌入模型 %s 返回了空向量".formatted(reference));
        }
        int actual = vectors.get(0).length;
        if (declaredDimensions != null && declaredDimensions > 0 && actual != declaredDimensions) {
            throw new BusinessException(ErrorCode.EMBEDDING_FAILED,
                    "嵌入维度不一致：配置声明 %d 维，模型 %s 实际返回 %d 维。"
                            .formatted(declaredDimensions, reference, actual)
                            + "向量库列宽与索引都按声明的维度建立，继续写入会导致检索结果错乱，因此这里直接失败。");
        }
        actualDimensions = actual;
        return actual;
    }
}
