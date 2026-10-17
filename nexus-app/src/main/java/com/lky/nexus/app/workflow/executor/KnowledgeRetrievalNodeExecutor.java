package com.lky.nexus.app.workflow.executor;

import com.lky.nexus.app.workflow.WorkflowExecutionContext;
import com.lky.nexus.app.workflow.WorkflowNodeExecutor;
import com.lky.nexus.app.workflow.model.WorkflowNode;
import com.lky.nexus.knowledge.search.SearchMode;
import com.lky.nexus.knowledge.service.ChunkSearchService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** KNOWLEDGE_RETRIEVAL 节点：复用现有多知识库混合检索服务。 */
@Component
public class KnowledgeRetrievalNodeExecutor implements WorkflowNodeExecutor {

    private final ChunkSearchService chunkSearchService;

    public KnowledgeRetrievalNodeExecutor(ChunkSearchService chunkSearchService) {
        this.chunkSearchService = chunkSearchService;
    }

    @Override
    public String type() {
        return "KNOWLEDGE_RETRIEVAL";
    }

    @Override
    public Object execute(WorkflowNode node, WorkflowExecutionContext context) {
        Object queryValue = node.config().getOrDefault("query", "{{ input.question }}");
        String query = queryValue instanceof String text && text.contains("{{")
                ? context.render(text) : String.valueOf(queryValue);
        List<Long> kbIds = ((List<?>) node.config().get("kbIds")).stream()
                .map(value -> Long.valueOf(String.valueOf(value)))
                .toList();
        Integer topK = node.config().get("topK") == null
                ? null : Integer.valueOf(String.valueOf(node.config().get("topK")));
        SearchMode mode = SearchMode.parse(String.valueOf(node.config().getOrDefault("mode", "hybrid")));
        List<ChunkSearchService.SearchHit> hits =
                chunkSearchService.searchMany(context.userId(), kbIds, query, topK, mode);

        List<Map<String, Object>> documents = hits.stream().map(hit -> {
            Map<String, Object> document = new LinkedHashMap<String, Object>();
            document.put("content", hit.content());
            document.put("score", hit.score());
            document.put("knowledgeBaseId", hit.knowledgeBaseId());
            document.put("chunkId", hit.chunkId());
            document.put("documentId", hit.documentId());
            document.put("documentTitle", hit.documentTitle());
            document.put("headingPath", hit.headingPath());
            return document;
        }).toList();
        return Map.of("query", query, "documents", documents);
    }
}
