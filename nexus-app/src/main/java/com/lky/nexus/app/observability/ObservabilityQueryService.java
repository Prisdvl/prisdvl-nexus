package com.lky.nexus.app.observability;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.app.agent.mapper.ToolInvocationMapper;
import com.lky.nexus.app.agent.model.entity.ToolInvocationPO;
import com.lky.nexus.app.chat.mapper.ChatMessageMapper;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
import com.lky.nexus.app.observability.mapper.ObservabilityCallMapper;
import com.lky.nexus.app.observability.model.dto.CallItemResponse;
import com.lky.nexus.app.observability.model.dto.ModelUsageResponse;
import com.lky.nexus.app.observability.model.dto.OverviewResponse;
import com.lky.nexus.app.observability.model.dto.RequestDetailResponse;
import com.lky.nexus.app.observability.model.dto.RequestSummaryResponse;
import com.lky.nexus.app.observability.model.dto.RetrievalHitResponse;
import com.lky.nexus.app.observability.model.dto.RetrievalSummaryResponse;
import com.lky.nexus.app.observability.model.dto.ToolCallSummaryResponse;
import com.lky.nexus.app.observability.model.dto.TrendPointResponse;
import com.lky.nexus.app.observability.model.dto.TrendResponse;
import com.lky.nexus.app.observability.model.entity.ObservabilityCallPO;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.PageQuery;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.observe.ObserveScenes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 观测读模型（§10.1 的五个只读端点）。
 *
 * <p><b>聚合为什么在 Java 里做</b>：按小时分桶与按 {@code request_id} 聚合是两个
 * "MySQL 与 H2 说法不一样"的操作（{@code DATE_FORMAT} / {@code TIMESTAMPADD} / {@code DATEDIFF}
 * 各认一个，测试库是 {@code MODE=MySQL} 的 H2）。把方言写进 SQL，结果就是
 * "生产能跑、测试红"或反过来——而观测页最不该出现的就是"数字对不对取决于数据库"。
 * 一次窗口扫描换两个库上语义一致，这笔交易划算。
 *
 * <p><b>扫描有上限</b>（{@link #MAX_SCAN_ROWS}）：窗口最大 30 天，单用户 30 天内
 * 超过 2 万次模型调用已经属于异常量级。超限时只统计<b>时间上最新的</b>那部分，
 * 并打一条 WARN——宁可少算一段并说出来，也不要为了"完整"把内存打爆。
 *
 * <p><b>越权边界</b>：所有查询都带 {@code user_id = 当前用户}，取自 {@code CurrentUser}，
 * 绝不接受前端传入。{@code requestId} 只是请求内部的关联键，不是访问凭证。
 */
@Service
public class ObservabilityQueryService {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityQueryService.class);

    /** 默认窗口：24 小时（页面上"今天"的语义）。 */
    private static final int DEFAULT_WINDOW_HOURS = 24;

    /** 窗口上限：30 天。 */
    private static final int MAX_WINDOW_HOURS = 24 * 30;

    /** 单次查询最多扫多少行明细（见类注释）。 */
    private static final int MAX_SCAN_ROWS = 20_000;

    /** 详情里最多展示多少条检索命中。 */
    private static final int MAX_HITS = 10;

    private static final String BY_HOUR = "hour";
    private static final String BY_DAY = "day";

    private final ObservabilityCallMapper callMapper;
    private final ToolInvocationMapper toolInvocationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ObservabilityPricing pricing;
    private final JsonMapper jsonMapper;

    public ObservabilityQueryService(ObservabilityCallMapper callMapper,
                                     ToolInvocationMapper toolInvocationMapper,
                                     ChatMessageMapper chatMessageMapper,
                                     ObservabilityPricing pricing,
                                     JsonMapper jsonMapper) {
        this.callMapper = callMapper;
        this.toolInvocationMapper = toolInvocationMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.pricing = pricing;
        this.jsonMapper = jsonMapper;
    }

    // ------------------------------------------------------------------
    // 1. 总览
    // ------------------------------------------------------------------

    /**
     * 总览数字。
     *
     * @param userId 当前用户（由 Controller 从 {@code CurrentUser} 取）
     * @param hours  窗口小时数；{@code null} 取默认 24，越界夹到 1..720
     */
    public OverviewResponse overview(Long userId, Integer hours) {
        int windowHours = normalizeHours(hours);
        LocalDateTime to = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime from = to.minusHours(windowHours);

        List<ObservabilityCallPO> rows = loadWindow(userId, from, to, null, null);

        long calls = rows.size();
        long tokensIn = 0;
        long tokensOut = 0;
        long latencySum = 0;
        long firstTokenSum = 0;
        long firstTokenCount = 0;
        long failures = 0;
        long ragCalls = 0;
        for (ObservabilityCallPO row : rows) {
            tokensIn += nullToZero(row.getTokensIn());
            tokensOut += nullToZero(row.getTokensOut());
            latencySum += nullToZero(row.getLatencyMs());
            if (row.getFirstTokenMs() != null) {
                firstTokenSum += row.getFirstTokenMs();
                firstTokenCount++;
            }
            if (isFailure(row)) {
                failures++;
            }
            if (row.getScene() != null && ObserveScenes.RAG_SCENES.contains(row.getScene())) {
                ragCalls++;
            }
        }

        Map<String, long[]> tokensByModel = tokensByModel(rows);
        List<String> unpricedModels = new ArrayList<>();
        BigDecimal cost = BigDecimal.ZERO;
        boolean costComplete = true;
        for (Map.Entry<String, long[]> entry : tokensByModel.entrySet()) {
            BigDecimal modelCost = pricing.cost(entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
            if (modelCost == null) {
                costComplete = false;
                unpricedModels.add(entry.getKey());
            } else {
                cost = cost.add(modelCost);
            }
        }

        return new OverviewResponse(
                windowHours,
                from,
                to,
                calls,
                tokensIn,
                tokensOut,
                tokensIn + tokensOut,
                calls == 0 ? 0 : latencySum / calls,
                firstTokenCount == 0 ? null : firstTokenSum / firstTokenCount,
                ragCalls,
                countToolCalls(userId, from, to),
                failures,
                calls == 0 ? 0d : round4((double) failures / calls),
                // 缺价格的模型只要有一个，总数就必然偏小：给 null 而不是一个偏小的数
                costComplete ? cost : null,
                costComplete,
                unpricedModels);
    }

    // ------------------------------------------------------------------
    // 2. 趋势
    // ------------------------------------------------------------------

    /**
     * 按小时/天分桶的用量趋势，空桶补零。
     *
     * @param by     {@code hour} 或 {@code day}，其它值按 {@code hour}
     * @param scene  可选场景过滤
     * @param model  可选模型过滤
     * @param hours  窗口小时数
     */
    public TrendResponse trend(Long userId, String by, String scene, String model, Integer hours) {
        boolean hourly = !BY_DAY.equalsIgnoreCase(by == null ? "" : by.trim());
        int windowHours = normalizeHours(hours);
        LocalDateTime to = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime from = to.minusHours(windowHours);

        List<ObservabilityCallPO> rows = loadWindow(userId, from, to, scene, model);

        // 先对齐桶边界：按小时取整点，按天取零点
        LocalDateTime bucketFrom = hourly
                ? from.truncatedTo(ChronoUnit.HOURS)
                : from.truncatedTo(ChronoUnit.DAYS);

        Map<LocalDateTime, long[]> buckets = new LinkedHashMap<>();
        for (LocalDateTime cursor = bucketFrom; !cursor.isAfter(to);
             cursor = hourly ? cursor.plusHours(1) : cursor.plusDays(1)) {
            buckets.put(cursor, new long[]{0, 0, 0, 0}); // calls / tokens / failures / latencySum
        }
        for (ObservabilityCallPO row : rows) {
            if (row.getCreatedAt() == null) {
                continue;
            }
            LocalDateTime key = hourly
                    ? row.getCreatedAt().truncatedTo(ChronoUnit.HOURS)
                    : row.getCreatedAt().truncatedTo(ChronoUnit.DAYS);
            long[] bucket = buckets.get(key);
            if (bucket == null) {
                // 落在桶外的行（窗口边界与桶边界不齐，最多一条）直接丢弃：
                // 补进相邻桶会让"整点"这个说法失真
                continue;
            }
            bucket[0]++;
            bucket[1] += nullToZero(row.getTokensIn()) + nullToZero(row.getTokensOut());
            bucket[2] += isFailure(row) ? 1 : 0;
            bucket[3] += nullToZero(row.getLatencyMs());
        }

        List<TrendPointResponse> points = new ArrayList<>(buckets.size());
        for (Map.Entry<LocalDateTime, long[]> entry : buckets.entrySet()) {
            long[] value = entry.getValue();
            points.add(new TrendPointResponse(
                    entry.getKey(),
                    value[0],
                    value[1],
                    value[2],
                    value[0] == 0 ? 0 : value[3] / value[0]));
        }
        return new TrendResponse(hourly ? BY_HOUR : BY_DAY, from, to, points);
    }

    // ------------------------------------------------------------------
    // 3. 按模型
    // ------------------------------------------------------------------

    /**
     * 按模型聚合的用量与成本（调用数降序）。
     *
     * @param scene 可选场景过滤
     * @param hours 窗口小时数
     */
    public List<ModelUsageResponse> models(Long userId, String scene, Integer hours) {
        int windowHours = normalizeHours(hours);
        LocalDateTime to = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime from = to.minusHours(windowHours);

        List<ObservabilityCallPO> rows = loadWindow(userId, from, to, scene, null);

        // model 可能是 null（供应商没回模型名）：单独归到一组，不丢行
        Map<String, ModelAggregate> aggregates = new LinkedHashMap<>();
        for (ObservabilityCallPO row : rows) {
            ModelAggregate agg = aggregates.computeIfAbsent(row.getModel(), key -> new ModelAggregate());
            agg.calls++;
            agg.tokensIn += nullToZero(row.getTokensIn());
            agg.tokensOut += nullToZero(row.getTokensOut());
            agg.latencySum += nullToZero(row.getLatencyMs());
            if (isFailure(row)) {
                agg.failures++;
            }
            if (row.getScene() != null) {
                agg.scenes.add(row.getScene());
            }
        }

        List<ModelUsageResponse> result = new ArrayList<>(aggregates.size());
        for (Map.Entry<String, ModelAggregate> entry : aggregates.entrySet()) {
            String model = entry.getKey();
            ModelAggregate agg = entry.getValue();
            BigDecimal cost = pricing.cost(model, agg.tokensIn, agg.tokensOut);
            result.add(new ModelUsageResponse(
                    model,
                    agg.calls,
                    agg.tokensIn,
                    agg.tokensOut,
                    agg.tokensIn + agg.tokensOut,
                    agg.failures,
                    agg.calls == 0 ? 0 : agg.latencySum / agg.calls,
                    cost,
                    cost != null,
                    new ArrayList<>(new LinkedHashSet<>(agg.scenes))));
        }
        result.sort(Comparator.comparingLong(ModelUsageResponse::calls).reversed()
                .thenComparing(item -> item.model() == null ? "" : item.model()));
        return result;
    }

    // ------------------------------------------------------------------
    // 4. 请求列表（按 request_id 聚合）
    // ------------------------------------------------------------------

    /**
     * 请求列表：<b>一行一个 {@code request_id}</b>。
     *
     * @param scene   可选场景过滤
     * @param success 可选成败过滤；{@code true/false} 的含义是"该请求里存在/不存在失败调用"
     * @param hours   窗口小时数
     */
    public PageResult<RequestSummaryResponse> requests(Long userId, int pageNum, int pageSize,
                                                       String scene, Boolean success, Integer hours) {
        int safePageNum = PageQuery.normalizePageNum(pageNum);
        int safePageSize = PageQuery.normalizePageSize(pageSize);
        int windowHours = normalizeHours(hours);
        LocalDateTime to = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime from = to.minusHours(windowHours);

        List<ObservabilityCallPO> rows = loadWindow(userId, from, to, scene, null);
        if (success != null) {
            // 先按"这次调用是否失败"过滤再聚合：于是 success=false 得到的是
            // "包含失败调用的请求"，success=true 得到的是"全部调用都成功的请求"
            List<ObservabilityCallPO> filtered = new ArrayList<>();
            for (ObservabilityCallPO row : rows) {
                if (isFailure(row) != success) {
                    filtered.add(row);
                }
            }
            rows = filtered;
        }

        Map<String, List<ObservabilityCallPO>> grouped = groupByRequestId(rows);
        List<RequestSummaryResponse> all = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<ObservabilityCallPO>> entry : grouped.entrySet()) {
            all.add(toSummary(entry.getKey(), entry.getValue()));
        }
        // 最新的请求在前
        all.sort(Comparator.comparing(RequestSummaryResponse::startedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())));

        int fromIndex = Math.min((safePageNum - 1) * safePageSize, all.size());
        int toIndex = Math.min(fromIndex + safePageSize, all.size());
        return PageResult.of(new ArrayList<>(all.subList(fromIndex, toIndex)),
                all.size(), safePageNum, safePageSize);
    }

    // ------------------------------------------------------------------
    // 5. 请求详情
    // ------------------------------------------------------------------

    /**
     * 单次请求全链路：调用明细 + 检索摘要 + 工具摘要。
     *
     * @throws BusinessException 9101：该 {@code requestId} 不属于当前用户或不存在
     */
    public RequestDetailResponse request(Long userId, String requestId) {
        if (!StringUtils.hasText(requestId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID);
        }
        List<ObservabilityCallPO> rows = callMapper.selectList(Wrappers.<ObservabilityCallPO>lambdaQuery()
                .eq(ObservabilityCallPO::getRequestId, requestId)
                .eq(ObservabilityCallPO::getUserId, userId)
                .orderByAsc(ObservabilityCallPO::getCreatedAt));
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.OBSERVABILITY_REQUEST_NOT_FOUND);
        }

        List<CallItemResponse> calls = new ArrayList<>(rows.size());
        long tokensIn = 0;
        long tokensOut = 0;
        long latencySum = 0;
        Long firstTokenMs = null;
        boolean success = true;
        Integer errorCode = null;
        String scene = null;
        String model = null;
        for (ObservabilityCallPO row : rows) {
            tokensIn += nullToZero(row.getTokensIn());
            tokensOut += nullToZero(row.getTokensOut());
            latencySum += nullToZero(row.getLatencyMs());
            if (firstTokenMs == null && row.getFirstTokenMs() != null) {
                firstTokenMs = row.getFirstTokenMs();
            }
            if (isFailure(row)) {
                success = false;
                if (errorCode == null) {
                    errorCode = row.getErrorCode();
                }
            }
            if (scene == null) {
                scene = row.getScene();
            }
            if (model == null) {
                model = row.getModel();
            }
            calls.add(new CallItemResponse(row.getId(), row.getScene(), row.getModel(),
                    row.getTokensIn(), row.getTokensOut(), row.getLatencyMs(), row.getFirstTokenMs(),
                    !isFailure(row), row.getErrorCode(), row.getCreatedAt()));
        }

        MetadataSummary metadata = readMetadata(userId, requestId);
        return new RequestDetailResponse(
                requestId,
                scene,
                model,
                rows.getFirst().getCreatedAt(),
                rows.getLast().getCreatedAt(),
                rows.size(),
                tokensIn,
                tokensOut,
                tokensIn + tokensOut,
                latencySum,
                firstTokenMs,
                success,
                errorCode,
                calls,
                metadata.retrieval(),
                metadata.toolCalls());
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 按窗口读明细（时间升序，最多 {@link #MAX_SCAN_ROWS} 行）。
     *
     * <p>取的是<b>最新的</b>若干行：一旦超限，丢掉的是窗口最老的那一段，
     * 而不是最新的一段——排查问题永远先看刚发生的事。
     */
    private List<ObservabilityCallPO> loadWindow(Long userId, LocalDateTime from, LocalDateTime to,
                                                 String scene, String model) {
        List<ObservabilityCallPO> rows = callMapper.selectList(Wrappers.<ObservabilityCallPO>lambdaQuery()
                .eq(ObservabilityCallPO::getUserId, userId)
                .ge(ObservabilityCallPO::getCreatedAt, from)
                .le(ObservabilityCallPO::getCreatedAt, to)
                .eq(StringUtils.hasText(scene), ObservabilityCallPO::getScene, scene)
                .eq(StringUtils.hasText(model), ObservabilityCallPO::getModel, model)
                .orderByDesc(ObservabilityCallPO::getCreatedAt)
                .last("LIMIT " + MAX_SCAN_ROWS));
        if (rows.size() >= MAX_SCAN_ROWS) {
            log.warn("观测窗口扫描达到上限 {} 行，更早的调用未计入统计 userId={} from={} to={}",
                    MAX_SCAN_ROWS, userId, from, to);
        }
        // 统一升序，下游分组/分桶不依赖数据库返回顺序
        rows.sort(Comparator.comparing(ObservabilityCallPO::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    private long countToolCalls(Long userId, LocalDateTime from, LocalDateTime to) {
        Long count = toolInvocationMapper.selectCount(Wrappers.<ToolInvocationPO>lambdaQuery()
                .eq(ToolInvocationPO::getUserId, userId)
                .ge(ToolInvocationPO::getStartedAt, from)
                .le(ToolInvocationPO::getStartedAt, to));
        return count == null ? 0 : count;
    }

    private Map<String, long[]> tokensByModel(List<ObservabilityCallPO> rows) {
        Map<String, long[]> result = new LinkedHashMap<>();
        for (ObservabilityCallPO row : rows) {
            long[] tokens = result.computeIfAbsent(row.getModel(), key -> new long[]{0, 0});
            tokens[0] += nullToZero(row.getTokensIn());
            tokens[1] += nullToZero(row.getTokensOut());
        }
        return result;
    }

    private static Map<String, List<ObservabilityCallPO>> groupByRequestId(List<ObservabilityCallPO> rows) {
        Map<String, List<ObservabilityCallPO>> grouped = new LinkedHashMap<>();
        for (ObservabilityCallPO row : rows) {
            // 没有 requestId 的行（理论上不该有：过滤器总会给一个）单独成组，
            // 用 id 兜底，避免把互不相关的调用合并成"一次请求"
            String key = StringUtils.hasText(row.getRequestId())
                    ? row.getRequestId()
                    : "call-" + row.getId();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private static RequestSummaryResponse toSummary(String requestId, List<ObservabilityCallPO> calls) {
        List<ObservabilityCallPO> ordered = new ArrayList<>(calls);
        ordered.sort(Comparator.comparing(ObservabilityCallPO::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        long tokens = 0;
        long latency = 0;
        boolean success = true;
        Integer errorCode = null;
        String scene = null;
        String model = null;
        Long firstTokenMs = null;
        Set<String> scenes = new LinkedHashSet<>();
        for (ObservabilityCallPO row : ordered) {
            tokens += nullToZero(row.getTokensIn()) + nullToZero(row.getTokensOut());
            latency += nullToZero(row.getLatencyMs());
            if (isFailure(row)) {
                success = false;
                if (errorCode == null) {
                    errorCode = row.getErrorCode();
                }
            }
            if (scene == null) {
                scene = row.getScene();
            }
            if (model == null) {
                model = row.getModel();
            }
            if (firstTokenMs == null) {
                firstTokenMs = row.getFirstTokenMs();
            }
            if (row.getScene() != null) {
                scenes.add(row.getScene());
            }
        }
        return new RequestSummaryResponse(
                requestId,
                scene,
                model,
                ordered.getFirst().getCreatedAt(),
                ordered.getLast().getCreatedAt(),
                ordered.size(),
                tokens,
                latency,
                firstTokenMs,
                success,
                errorCode,
                new ArrayList<>(scenes));
    }

    /**
     * 从 {@code chat_message.metadata} 里读检索与工具摘要。
     *
     * <p>一个请求可能有多条 assistant 消息（工具循环、重试），所以合并取：
     * 工具调用<u>累加</u>，检索摘要取<b>第一条找到的</b>（它们描述的是同一次检索）。
     */
    private MetadataSummary readMetadata(Long userId, String requestId) {
        List<ChatMessagePO> messages = chatMessageMapper.selectList(Wrappers.<ChatMessagePO>lambdaQuery()
                .eq(ChatMessagePO::getUserId, userId)
                .eq(ChatMessagePO::getRequestId, requestId)
                .isNotNull(ChatMessagePO::getMetadata)
                .orderByAsc(ChatMessagePO::getCreatedAt));

        RetrievalSummaryResponse retrieval = new RetrievalSummaryResponse(
                false, List.of(), null, null, null, null, null, List.of());
        List<ToolCallSummaryResponse> toolCalls = new ArrayList<>();

        for (ChatMessagePO message : messages) {
            JsonNode metadata = parseJson(message.getMetadata());
            if (metadata == null) {
                continue;
            }
            if (!retrieval.found()) {
                RetrievalSummaryResponse parsed = parseRetrieval(metadata.get("retrieval"));
                if (parsed != null) {
                    retrieval = parsed;
                }
            }
            JsonNode toolCallsNode = metadata.get("toolCalls");
            if (toolCallsNode != null && toolCallsNode.isArray()) {
                for (JsonNode item : toolCallsNode) {
                    toolCalls.add(new ToolCallSummaryResponse(
                            text(item, "tool"),
                            text(item, "input"),
                            text(item, "resultSummary")));
                }
            }
        }
        return new MetadataSummary(retrieval, toolCalls);
    }

    private RetrievalSummaryResponse parseRetrieval(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        List<Long> kbIds = new ArrayList<>();
        JsonNode ids = node.get("knowledgeBaseIds");
        if (ids != null && ids.isArray()) {
            for (JsonNode id : ids) {
                if (id != null && !id.isNull()) {
                    kbIds.add(id.asLong());
                }
            }
        }
        List<RetrievalHitResponse> hits = new ArrayList<>();
        JsonNode hitsNode = node.get("hits");
        if (hitsNode != null && hitsNode.isArray()) {
            for (JsonNode hit : hitsNode) {
                if (hits.size() >= MAX_HITS) {
                    break;
                }
                hits.add(new RetrievalHitResponse(
                        longOrNull(hit, "knowledgeBaseId"),
                        text(hit, "documentName"),
                        text(hit, "headingPath"),
                        doubleOrNull(hit, "score"),
                        text(hit, "snippet")));
            }
        }
        return new RetrievalSummaryResponse(
                true,
                kbIds,
                intOrNull(node, "count"),
                intOrNull(node, "contextTokens"),
                boolOrNull(node, "truncated"),
                boolOrNull(node, "reranked"),
                boolOrNull(node, "empty"),
                hits);
    }

    private JsonNode parseJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return jsonMapper.readTree(raw);
        } catch (RuntimeException ex) {
            // metadata 是历史 JSON：解析不了就当没有，不让一次坏数据把整页搞成 500
            log.debug("消息 metadata 解析失败，已跳过：{}", ex.getMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asDouble();
    }

    private static Boolean boolOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    private static boolean isFailure(ObservabilityCallPO row) {
        return row.getSuccess() != null && row.getSuccess() == 0;
    }

    private static long nullToZero(Long value) {
        return value == null ? 0 : value;
    }

    private static long nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private static int normalizeHours(Integer hours) {
        if (hours == null) {
            return DEFAULT_WINDOW_HOURS;
        }
        int value = hours;
        if (value < 1) {
            value = 1;
        }
        return Math.min(value, MAX_WINDOW_HOURS);
    }

    /** 累加器：只在类内部用，避免为一次聚合建一个对外 DTO。 */
    private static final class ModelAggregate {
        private long calls;
        private long tokensIn;
        private long tokensOut;
        private long latencySum;
        private long failures;
        private final Set<String> scenes = new LinkedHashSet<>();
    }

    private record MetadataSummary(RetrievalSummaryResponse retrieval,
                                   List<ToolCallSummaryResponse> toolCalls) {
    }
}
