package com.lky.nexus.knowledge.rag;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.ChatClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 LLM 的重排：把候选片段交给模型打相关性分。
 *
 * <p>用 {@code chat.judge} 角色而不是对话模型（ADR-003）：重排是"判断"类任务，
 * 用便宜/快的模型即可，也便于以后换成专用的重排模型或外部 API。
 *
 * <p><b>只发一次请求</b>：把全部候选放进一个 prompt 里要一组分数，
 * 而不是每个候选一次调用。20 个候选意味着 20 倍延迟与成本，而批次打分的效果几乎一样。
 *
 * <p><b>失败即降级</b>：任何异常（模型不可用、JSON 解析失败、条数不匹配）
 * 都返回空列表，让调用方保持召回顺序——重排坏了不该让整个问答不可用。
 */
@Component
public class LlmReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);

    /** 单次重排的候选上限：太多会超出上下文，也太贵。 */
    private static final int MAX_CANDIDATES = 20;

    /** 每个候选送进 prompt 的正文上限（够判断即可，不必全文）。 */
    private static final int MAX_CANDIDATE_CHARS = 600;

    private static final String PROMPT = """
            你是检索结果重排器。请判断下面每个候选片段对回答用户问题的价值，打 0-10 分。
            评分标准：能直接回答问题的给 8-10 分；提供部分相关信息给 4-7 分；仅有主题相关给 1-3 分；无关给 0 分。

            用户问题：
            %s

            候选片段：
            %s

            只输出 JSON，不要解释，格式为：{"scores":[{"id":1,"score":8},{"id":2,"score":3}]}
            """;

    private final ChatClientFactory chatClientFactory;
    private final JsonMapper jsonMapper;
    private final boolean enabled;
    private final java.time.Duration timeout;

    public LlmReranker(ChatClientFactory chatClientFactory,
                       JsonMapper jsonMapper,
                       @Value("${nexus.rag.rerank-enabled:true}") boolean enabled,
                       @Value("${nexus.rag.rerank-timeout-ms:12000}") long timeoutMs) {
        this.chatClientFactory = chatClientFactory;
        this.jsonMapper = jsonMapper;
        this.enabled = enabled;
        this.timeout = java.time.Duration.ofMillis(timeoutMs);
    }

    @Override
    public List<Double> score(String query, List<Candidate> candidates) {
        if (!enabled || candidates.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        List<Candidate> limited = candidates.size() > MAX_CANDIDATES
                ? candidates.subList(0, MAX_CANDIDATES) : candidates;

        // 带超时：网关退化时"重排"是最先该被牺牲的一步（它本来就是增强项），
        // 超时即降级为召回顺序，绝不让用户干等（实测遇到过单次调用上百秒）
        var response = com.lky.nexus.common.util.TimeLimitedCall.run(timeout,
                () -> callModel(query, limited));

        if (response.isEmpty()) {
            log.warn("重排超时（>{} ms）或失败，保持召回顺序", timeout.toMillis());
            return List.of();
        }
        List<Double> scores = parseScores(response.get(), limited.size());
        if (scores.size() != limited.size()) {
            log.warn("重排返回条数与候选不一致（{} vs {}），本次放弃重排",
                    scores.size(), limited.size());
            return List.of();
        }
        return scores;
    }

    /** 实际的模型调用（抽出来是为了让超时逻辑与调用逻辑分开）。 */
    private String callModel(String query, List<Candidate> candidates) {
        ChatClient client = chatClientFactory.get(AiModelProperties.Role.CHAT_JUDGE);
        return client.prompt()
                .user(PROMPT.formatted(query, renderCandidates(candidates)))
                .call()
                .content();
    }

    private String renderCandidates(List<Candidate> candidates) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            String text = candidate.content() == null ? "" : candidate.content();
            if (text.length() > MAX_CANDIDATE_CHARS) {
                text = text.substring(0, MAX_CANDIDATE_CHARS) + "…";
            }
            builder.append('[').append(i + 1).append(']');
            if (candidate.headingPath() != null && !candidate.headingPath().isBlank()) {
                builder.append(" （").append(candidate.headingPath()).append('）');
            }
            builder.append('\n').append(text).append("\n\n");
        }
        return builder.toString();
    }

    /**
     * 解析模型输出。
     *
     * <p>模型经常在 JSON 外面套一层解释或 markdown 代码块，所以先截取第一个 {@code {} 到最后一个 {@code }}，
     * 再按 id 归位——顺序不能靠模型保证。
     */
    private List<Double> parseScores(String response, int expected) {
        if (response == null) {
            return List.of();
        }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return List.of();
        }
        JsonNode root = jsonMapper.readTree(response.substring(start, end + 1));
        JsonNode scoresNode = root.get("scores");
        if (scoresNode == null || !scoresNode.isArray()) {
            return List.of();
        }
        List<Double> scores = new ArrayList<>(java.util.Collections.nCopies(expected, 0.0));
        for (JsonNode item : scoresNode) {
            JsonNode idNode = item.get("id");
            JsonNode scoreNode = item.get("score");
            if (idNode == null || scoreNode == null) {
                continue;
            }
            int index = idNode.asInt() - 1;
            if (index >= 0 && index < expected) {
                scores.set(index, scoreNode.asDouble());
            }
        }
        return scores;
    }
}
