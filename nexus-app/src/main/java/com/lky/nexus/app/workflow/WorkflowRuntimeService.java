package com.lky.nexus.app.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lky.nexus.app.workflow.mapper.WorkflowNodeRunMapper;
import com.lky.nexus.app.workflow.mapper.WorkflowRunMapper;
import com.lky.nexus.app.workflow.model.WorkflowEdge;
import com.lky.nexus.app.workflow.model.WorkflowGraph;
import com.lky.nexus.app.workflow.model.WorkflowNode;
import com.lky.nexus.app.workflow.model.dto.RunWorkflowRequest;
import com.lky.nexus.app.workflow.model.dto.WorkflowNodeRunResponse;
import com.lky.nexus.app.workflow.model.dto.WorkflowRunResponse;
import com.lky.nexus.app.workflow.model.entity.WorkflowNodeRunPO;
import com.lky.nexus.app.workflow.model.entity.WorkflowPO;
import com.lky.nexus.app.workflow.model.entity.WorkflowRunPO;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.PageQuery;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** 工作流运行服务：加载不可变版本、调度 DAG 节点并持久化轨迹。 */
@Service
public class WorkflowRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRuntimeService.class);

    private final WorkflowDefinitionService definitionService;
    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final WorkflowNodeExecutorRegistry executorRegistry;
    private final WorkflowVariableResolver variableResolver;
    private final JsonMapper jsonMapper;

    public WorkflowRuntimeService(WorkflowDefinitionService definitionService,
                                  WorkflowRunMapper runMapper,
                                  WorkflowNodeRunMapper nodeRunMapper,
                                  WorkflowNodeExecutorRegistry executorRegistry,
                                  WorkflowVariableResolver variableResolver,
                                  JsonMapper jsonMapper) {
        this.definitionService = definitionService;
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.executorRegistry = executorRegistry;
        this.variableResolver = variableResolver;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 运行一次工作流（同步执行，返回即终态）。
     *
     * <p><b>为什么是 {@code noRollbackFor = BusinessException.class}</b>：
     * 节点失败时这里先把运行与节点轨迹标成 FAILED，再把异常抛出去；
     * 而 {@code BusinessException} 是 {@code RuntimeException}，默认事务规则会回滚整个方法，
     * 于是「已落库的失败运行 + 失败节点轨迹」全被丢弃——失败运行根本不留痕
     * （实测：失败后按 workflowId 查运行列表 total=0，拿不到 runId 也就读不回轨迹）。
     * 审计要的恰好相反：成功失败都要留痕，所以显式声明业务异常不回滚。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public WorkflowRunResponse run(Long userId, Long workflowId, RunWorkflowRequest request, String triggerType) {
        WorkflowPO workflow = definitionService.requireOwned(userId, workflowId);
        Long versionId = "DEBUG".equals(triggerType)
                ? workflow.getDraftVersionId() : workflow.getPublishedVersionId();
        if (versionId == null) {
            throw invalid("DEBUG".equals(triggerType) ? "工作流还没有草稿" : "工作流还没有发布版本");
        }
        WorkflowGraph graph = definitionService.loadGraph(versionId);

        WorkflowRunPO run = new WorkflowRunPO();
        run.setWorkflowId(workflowId);
        run.setVersionId(versionId);
        run.setUserId(userId);
        run.setStatus("RUNNING");
        run.setTriggerType(triggerType == null ? "MANUAL" : triggerType);
        run.setInputJson(writeJson(request.inputs()));
        LocalDateTime now = LocalDateTime.now();
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        run.setStartedAt(now);
        runMapper.insert(run);

        Map<String, WorkflowNodeRunPO> nodeRuns = insertPendingNodeRuns(run.getId(), graph);
        try {
            Object output = executeGraph(userId, graph, request.inputs(), nodeRuns);
            finishRun(run, "SUCCEEDED", writeJson(output), null, null);
            return toRunResponse(runMapper.selectById(run.getId()));
        } catch (BusinessException ex) {
            finishRun(run, "FAILED", null, ex.getCode(), ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.warn("工作流运行失败 runId={}", run.getId(), ex);
            finishRun(run, "FAILED", null, ErrorCode.WORKFLOW_NODE_FAILED.getCode(), "工作流节点执行失败");
            throw new BusinessException(ErrorCode.WORKFLOW_NODE_FAILED, "工作流节点执行失败");
        }
    }

    public WorkflowRunResponse get(Long userId, Long runId) {
        return toRunResponse(requireOwnedRun(userId, runId));
    }

    public List<WorkflowNodeRunResponse> nodes(Long userId, Long runId) {
        requireOwnedRun(userId, runId);
        return nodeRunMapper.selectList(Wrappers.<WorkflowNodeRunPO>lambdaQuery()
                        .eq(WorkflowNodeRunPO::getRunId, runId)
                        .orderByAsc(WorkflowNodeRunPO::getId))
                .stream().map(this::toNodeRunResponse).toList();
    }

    /**
     * 某工作流的运行历史（分页，按时间倒序）。
     *
     * <p>存在的理由：运行是同步执行的，失败时 {@code POST /runs} 直接抛异常，
     * 调用方拿不到 runId，于是那次 FAILED 运行的节点轨迹虽然已落库却读不回来。
     * 有了列表接口，失败运行可以按 workflowId 找回，再走 {@code /runs/{runId}/nodes} 读轨迹。
     *
     * <p>归属：先按工作流归属校验（越权返回 6005/404），查询条件同时带上 userId，
     * 与 {@code /runs/{runId}} 的隔离口径一致。
     */
    public PageResult<WorkflowRunResponse> list(Long userId, Long workflowId, int pageNum, int pageSize) {
        definitionService.requireOwned(userId, workflowId);
        int safePageNum = PageQuery.normalizePageNum(pageNum);
        int safePageSize = PageQuery.normalizePageSize(pageSize);
        Page<WorkflowRunPO> page = new Page<>(safePageNum, safePageSize);
        Page<WorkflowRunPO> result = runMapper.selectPage(page, Wrappers.<WorkflowRunPO>lambdaQuery()
                .eq(WorkflowRunPO::getWorkflowId, workflowId)
                .eq(WorkflowRunPO::getUserId, userId)
                .orderByDesc(WorkflowRunPO::getId));
        return PageResult.of(result.getRecords().stream().map(this::toRunResponse).toList(),
                result.getTotal(), safePageNum, safePageSize);
    }

    public WorkflowRunResponse cancel(Long userId, Long runId) {
        WorkflowRunPO run = requireOwnedRun(userId, runId);
        if ("RUNNING".equals(run.getStatus()) || "QUEUED".equals(run.getStatus())) {
            run.setStatus("CANCELLED");
            run.setFinishedAt(LocalDateTime.now());
            run.setUpdatedAt(LocalDateTime.now());
            runMapper.updateById(run);
        }
        return toRunResponse(run);
    }

    private WorkflowRunPO requireOwnedRun(Long userId, Long runId) {
        if (runId == null) {
            throw notFound();
        }
        WorkflowRunPO run = runMapper.selectOne(Wrappers.<WorkflowRunPO>lambdaQuery()
                .eq(WorkflowRunPO::getId, runId)
                .eq(WorkflowRunPO::getUserId, userId));
        if (run == null) {
            throw notFound();
        }
        return run;
    }

    private Map<String, WorkflowNodeRunPO> insertPendingNodeRuns(Long runId, WorkflowGraph graph) {
        Map<String, WorkflowNodeRunPO> nodeRuns = new LinkedHashMap<>();
        for (WorkflowNode node : graph.nodes()) {
            WorkflowNodeRunPO nodeRun = new WorkflowNodeRunPO();
            nodeRun.setRunId(runId);
            nodeRun.setNodeId(node.id());
            nodeRun.setNodeType(node.type());
            nodeRun.setStatus("PENDING");
            nodeRun.setAttempt(1);
            nodeRun.setCreatedAt(LocalDateTime.now());
            nodeRunMapper.insert(nodeRun);
            nodeRuns.put(node.id(), nodeRun);
        }
        return nodeRuns;
    }

    private Object executeGraph(Long userId,
                                WorkflowGraph graph,
                                Map<String, Object> inputs,
                                Map<String, WorkflowNodeRunPO> nodeRuns) throws Exception {
        Map<String, Object> outputs = new HashMap<>();
        Map<String, String> selectedHandles = new HashMap<>();
        Set<String> activeEdges = new HashSet<>();
        WorkflowExecutionContext context = new WorkflowExecutionContext(userId, inputs, outputs, variableResolver);

        for (WorkflowNode node : topologicalOrder(graph)) {
            WorkflowNodeRunPO nodeRun = nodeRuns.get(node.id());
            List<WorkflowEdge> incoming = graph.edges().stream()
                    .filter(edge -> edge.target().equals(node.id())).toList();
            boolean hasActiveIncoming = node.type().equals("START")
                    || incoming.stream().anyMatch(edge -> activeEdges.contains(edge.id()));
            if (!hasActiveIncoming) {
                markNode(nodeRun, "SKIPPED", null, "上游分支未选中或上游失败", null, null);
                continue;
            }

            LocalDateTime startedAt = LocalDateTime.now();
            markNode(nodeRun, "RUNNING", null, null, null, startedAt);
            try {
                Object output = executorRegistry.require(node.type()).execute(node, context);
                outputs.put(node.id(), output);
                markNode(nodeRun, "SUCCEEDED", writeJson(inputOf(node, inputs, outputs)), writeJson(output),
                        null, startedAt);
                activateOutgoing(graph, node, output, selectedHandles, activeEdges);
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? "节点执行失败" : ex.getMessage();
                markNode(nodeRun, "FAILED", writeJson(inputOf(node, inputs, outputs)), null,
                        ErrorCode.WORKFLOW_NODE_FAILED.getCode(), startedAt);
                nodeRun.setErrorMessage(message);
                nodeRunMapper.updateById(nodeRun);
                if (ex instanceof BusinessException business) {
                    throw business;
                }
                throw new BusinessException(ErrorCode.WORKFLOW_NODE_FAILED, message);
            }
        }

        return outputs.entrySet().stream()
                .filter(entry -> graph.nodes().stream()
                        .anyMatch(node -> "END".equals(node.type()) && node.id().equals(entry.getKey())))
                .reduce((first, second) -> second)
                .map(Map.Entry::getValue)
                .orElse(Map.of());
    }

    private void activateOutgoing(WorkflowGraph graph,
                                  WorkflowNode node,
                                  Object output,
                                  Map<String, String> selectedHandles,
                                  Set<String> activeEdges) {
        if ("IF_ELSE".equals(node.type()) && output instanceof Map<?, ?> map) {
            selectedHandles.put(node.id(), Boolean.TRUE.equals(map.get("branch")) ? "true" : "false");
        }
        graph.edges().stream()
                .filter(edge -> edge.source().equals(node.id()))
                .forEach(edge -> {
                    String selected = selectedHandles.get(node.id());
                    if (selected == null || selected.equals(edge.sourceHandle())) {
                        activeEdges.add(edge.id());
                    }
                });
    }

    private List<WorkflowNode> topologicalOrder(WorkflowGraph graph) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (WorkflowNode node : graph.nodes()) {
            inDegree.putIfAbsent(node.id(), 0);
        }
        for (WorkflowEdge edge : graph.edges()) {
            inDegree.merge(edge.target(), 1, Integer::sum);
            adjacency.computeIfAbsent(edge.source(), key -> new ArrayList<>()).add(edge.target());
        }
        List<String> pending = new ArrayList<>(graph.nodes().stream()
                .filter(node -> inDegree.get(node.id()) == 0)
                .map(WorkflowNode::id).toList());
        List<WorkflowNode> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < pending.size(); index++) {
            String nodeId = pending.get(index);
            WorkflowNode node = graph.nodes().stream()
                    .filter(candidate -> candidate.id().equals(nodeId)).findFirst().orElseThrow();
            if (seen.add(nodeId)) {
                ordered.add(node);
            }
            for (String next : adjacency.getOrDefault(nodeId, List.of())) {
                if (inDegree.merge(next, -1, Integer::sum) == 0) {
                    pending.add(next);
                }
            }
        }
        if (ordered.size() != graph.nodes().size()) {
            throw invalid("工作流图不能存在环");
        }
        return ordered;
    }

    private Object inputOf(WorkflowNode node,
                           Map<String, Object> inputs,
                           Map<String, Object> outputs) {
        WorkflowExecutionContext context = new WorkflowExecutionContext(null, inputs, outputs, variableResolver);
        Map<String, Object> input = new LinkedHashMap<>();
        Object template = node.config().get("input");
        if (template instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (value instanceof String text && text.contains("{{")) {
                    input.put(String.valueOf(key), context.variable(text));
                } else {
                    input.put(String.valueOf(key), value);
                }
            });
        }
        return input;
    }

    private void markNode(WorkflowNodeRunPO nodeRun,
                          String status,
                          String inputJson,
                          String outputJson,
                          Integer errorCode,
                          LocalDateTime startedAt) {
        nodeRun.setStatus(status);
        if (inputJson != null) {
            nodeRun.setInputJson(inputJson);
        }
        if (outputJson != null) {
            nodeRun.setOutputJson(outputJson);
        }
        if (errorCode != null) {
            nodeRun.setErrorCode(errorCode);
        }
        if (startedAt != null) {
            nodeRun.setStartedAt(startedAt);
        }
        LocalDateTime now = LocalDateTime.now();
        nodeRun.setFinishedAt(now);
        if (nodeRun.getStartedAt() != null) {
            nodeRun.setLatencyMs(Duration.between(nodeRun.getStartedAt(), now).toMillis());
        }
        nodeRunMapper.updateById(nodeRun);
    }

    private void finishRun(WorkflowRunPO run, String status, String outputJson, Integer errorCode, String errorMessage) {
        WorkflowRunPO current = runMapper.selectById(run.getId());
        if (current == null) {
            return;
        }
        current.setStatus(status);
        if (outputJson != null) {
            current.setOutputJson(outputJson);
        }
        if (errorCode != null) {
            current.setErrorCode(errorCode);
        }
        if (errorMessage != null) {
            current.setErrorMessage(errorMessage);
        }
        LocalDateTime now = LocalDateTime.now();
        current.setFinishedAt(now);
        if (current.getStartedAt() != null) {
            current.setDurationMs(Duration.between(current.getStartedAt(), now).toMillis());
        }
        current.setUpdatedAt(now);
        runMapper.updateById(current);
        run.setStatus(current.getStatus());
    }

    private WorkflowRunResponse toRunResponse(WorkflowRunPO run) {
        return new WorkflowRunResponse(
                run.getId(),
                run.getWorkflowId(),
                run.getVersionId(),
                run.getStatus(),
                run.getTriggerType(),
                parseJson(run.getInputJson()),
                parseJson(run.getOutputJson()),
                run.getErrorCode(),
                run.getErrorMessage(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getDurationMs()
        );
    }

    private WorkflowNodeRunResponse toNodeRunResponse(WorkflowNodeRunPO nodeRun) {
        return new WorkflowNodeRunResponse(
                nodeRun.getId(),
                nodeRun.getNodeId(),
                nodeRun.getNodeType(),
                nodeRun.getStatus(),
                nodeRun.getAttempt(),
                parseJson(nodeRun.getInputJson()),
                parseJson(nodeRun.getOutputJson()),
                nodeRun.getErrorCode(),
                nodeRun.getErrorMessage(),
                nodeRun.getLatencyMs(),
                nodeRun.getStartedAt(),
                nodeRun.getFinishedAt()
        );
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readValue(json, Object.class);
        } catch (Exception ex) {
            return json;
        }
    }

    private String writeJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw invalid("工作流运行数据序列化失败");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.WORKFLOW_DEFINITION_INVALID, message);
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.WORKFLOW_RUN_NOT_FOUND);
    }
}
