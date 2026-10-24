package com.lky.nexus.knowledge.rag.retriever;

import com.lky.nexus.ai.rag.retriever.RetrievalFilters;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.search.ChunkSearchHit;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link ExplainRecaller} 的默认实现：原样转给两条既有召回链路。
 *
 * <p>这里<b>不加任何逻辑</b>（不排序、不截断、不去重）。explain 的全部价值就在于
 * 让人看到"链路本来给出的是什么"，任何一次善意的加工都会让排查结论失真。
 */
@Component
public class RetrievalExplainRecaller implements ExplainRecaller {

    private final KeywordRetriever keywordRetriever;
    private final VectorRetriever vectorRetriever;

    public RetrievalExplainRecaller(KeywordRetriever keywordRetriever, VectorRetriever vectorRetriever) {
        this.keywordRetriever = keywordRetriever;
        this.vectorRetriever = vectorRetriever;
    }

    @Override
    public List<ChunkSearchHit> keywordHits(Long knowledgeBaseId, String query, int depth, RetrievalFilters filters) {
        try {
            return keywordRetriever.recall(knowledgeBaseId, query, depth, filters);
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.VECTOR_STORE_UNAVAILABLE,
                    "关键词检索失败：%s".formatted(rootMessage(ex)), ex);
        }
    }

    @Override
    public List<ChunkSearchHit> vectorHits(KnowledgeBasePO knowledgeBase, String query, int depth,
                                           double minScore, List<Long> documentIds) {
        try {
            return vectorRetriever.recall(knowledgeBase, query, depth, minScore, documentIds);
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.VECTOR_STORE_UNAVAILABLE,
                    "向量检索失败：%s".formatted(rootMessage(ex)), ex);
        }
    }

    /**
     * 只取最内层消息：把 {@code "java.net.ConnectException: Connection refused"} 这类噪音，
     * 收敛成能直接展示给用户的短句（它会被写进 {@code degradedKbs[].reason}）。
     */
    private static String rootMessage(RuntimeException ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
