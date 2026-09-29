package com.lky.nexus.knowledge.service;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.EmbeddingClient;
import com.lky.nexus.ai.model.EmbeddingClientFactory;
import com.lky.nexus.ai.model.ModelRef;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import org.springframework.stereotype.Component;

/**
 * 解析"该知识库应该用哪个嵌入客户端"，并校验模型一致性。
 *
 * <p>抽成独立组件是因为它被两条路径共用：<b>写入侧</b>（向量化切片）与
 * <b>读取侧</b>（把查询语句转成向量）。两条路径必须用同一个模型——
 * 查询用模型 A、索引用模型 B，向量空间不一致，检索结果会离谱且不报错。
 * 这种"两边都写一遍校验"的代码最容易一边改一边忘，所以收敛到一处。
 */
@Component
public class KnowledgeEmbeddingResolver {

    private final AiModelProperties aiModelProperties;
    private final EmbeddingClientFactory embeddingClientFactory;

    public KnowledgeEmbeddingResolver(AiModelProperties aiModelProperties,
                                      EmbeddingClientFactory embeddingClientFactory) {
        this.aiModelProperties = aiModelProperties;
        this.embeddingClientFactory = embeddingClientFactory;
    }

    /**
     * @throws BusinessException 4006：配置里的嵌入模型与该知识库冻结的模型不一致
     */
    public EmbeddingClient resolve(KnowledgeBasePO knowledgeBase) {
        String role = AiModelProperties.Role.EMBEDDING_DEFAULT;
        String reference = aiModelProperties.getRoles().get(role);
        if (reference == null) {
            throw new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED, "未配置逻辑角色 " + role);
        }
        ModelRef ref = ModelRef.parse(reference);
        if (!ref.provider().equals(knowledgeBase.getEmbeddingProvider())
                || !ref.model().equals(knowledgeBase.getEmbeddingModel())) {
            throw new BusinessException(ErrorCode.MODEL_ROLE_NOT_CONFIGURED,
                    ("知识库冻结的嵌入模型是 %s:%s，而当前配置的角色 %s 指向 %s。"
                            + "同一个库里混用两种嵌入模型的向量会让检索结果错乱（且不会报错），"
                            + "因此拒绝执行。请恢复配置，或新建知识库。")
                            .formatted(knowledgeBase.getEmbeddingProvider(), knowledgeBase.getEmbeddingModel(),
                                    role, ref.value()));
        }
        return embeddingClientFactory.get(role);
    }
}
