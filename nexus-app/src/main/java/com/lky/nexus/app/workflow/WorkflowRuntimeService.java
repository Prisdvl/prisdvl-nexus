package com.lky.nexus.app.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lky.nexus.app.workflow.mapper.WorkflowNodeRunMapper;
import com.lky.nexus.app.workflow.mapper.WorkflowRunMapper;
import com.lky.nexus.app.workflow.model.WorkflowEdge;
import com.lky.nexus.app.workflow.model.WorkflowGraph;
import com.lky.nexus.app.workflow.model.WorkflowNode;
import com.lky.nexus.app.workflow.model.dto.RunWorkflowRequest;
import com.lky.nexus.app.workflow.model.dto.WorkflowApprovalRequest;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** 工作流运行服务：加载不可变版本、调度 DAG 节点并持久化轨迹。 */
@Service
public class WorkflowRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRuntimeService.class);

    /** 人工审批闸门节点类型（与 {@code WorkflowGraphValidator.TYPES} 里的同名字符串一致）。 */
    private static final String HUMAN_APPROVAL = "HUMAN_APPROVAL";
    private static final String STATUS_WAITING_APPROVAL = "WAITING_APPROVAL";

    /**
     * 一次执行的可恢复状态。
     *
     * <p>为什么需要它：重试要「从失败节点续跑」、审批要在挂起后「接着跑」，
     * 两者都要求把已成功节点（及其输出、被激活的边）回灌成初始状态。
     * 否则每次续跑都从 START 重来，既浪费也可能重复副作用。
     */
    private record ExecutionState(Map<String, Object> outputs,
                                  Map<String, String> selectedHandles,
                                  Set<String> activeEdges,
                                  Set<String> doneNodes) {

        static ExecutionState fresh() {
            return new ExecutionState(new HashMap<>(), new HashMap<>(), new HashSet<>(), new HashSet<>());
        }
    }

    /** executeGraph 的结局：正常产出，或停在人工审批节点上挂起。 */
    private record GraphOutcome(Object output, String waitingNodeId) {
        boolean suspended() {
            return waitingNodeId != null;
        }
    }

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

        WorkflowRunPO run = newRun(workflowId, versionId, userId,
                writeJson(request.inputs()), triggerType == null ? "MANUAL" : triggerType);

        Map<String, WorkflowNodeRunPO> nodeRuns = insertPendingNodeRuns(run.getId(), graph);
        try {
            GraphOutcome outcome = executeGraph(userId, graph, request.inputs(), nodeRuns, ExecutionState.fresh());
            if (outcome.suspended()) {
                // 停在人工审批节点：运行置为 WAITING_APPROVAL 并正常提交（挂起态必须落库，
                // 与失败不同——这里不是异常路径，不能靠 noRollbackFor 兜底）
                markWaiting(run);
                return toRunResponse(runMapper.selectById(run.getId()));
            }
            finishRun(run, "SUCCEEDED", writeJson(outcome.output()), null, null);
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
        boolean cancellable = "RUNNING".equals(run.getStatus())
                || "QUEUED".equals(run.getStatus())
                || STATUS_WAITING_APPROVAL.equals(run.getStatus());
        if (cancellable) {
            // 挂起在审批上的运行被取消时，把闸门节点一起收尾，
            // 否则轨迹里会永远挂着一条「等待审批」的节点
            if (STATUS_WAITING_APPROVAL.equals(run.getStatus())) {
                nodeRunMapper.selectList(Wrappers.<WorkflowNodeRunPO>lambdaQuery()
                                .eq(WorkflowNodeRunPO::getRunId, runId))
                        .stream()
                        .filter(nr -> STATUS_WAITING_APPROVAL.equals(nr.getStatus()))
                        .forEach(nr -> {
                            nr.setStatus("SKIPPED");
                            nr.setErrorMessage("运行已取消");
                            nr.setFinishedAt(LocalDateTime.now());
                            nodeRunMapper.updateById(nr);
                        });
            }
            run.setStatus("CANCELLED");
            run.setFinishedAt(LocalDateTime.now());
            run.setUpdatedAt(LocalDateTime.now());
            runMapper.updateById(run);
        }
        return toRunResponse(run);
    }

    /**
     * 从失败节点续跑。
     *
     * <p>语义：以<b>源运行所用的同一版本</b>新建一次运行（triggerType=RETRY，图不可变），
     * 把源运行里已成功节点的输出回灌为初始状态，只重跑未成功的节点；
     * 已成功节点连同输出一起复制进新运行的轨迹，所以新运行单独看仍是一条完整轨迹。
     *
     * <p>只允许 FAILED / CANCELLED：成功的没必要重试（直接再跑一次即可），
     * RUNNING / WAITING_APPROVAL 是「正在进行中」，重试等于同一张图并发双跑。
     *
     * <p>已知缺口：表里没有「重试自哪次运行」的列，所以只靠 {@code triggerType=RETRY} 区分，
     * 无法回溯源运行（要补需要一次迁移）。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public WorkflowRunResponse retry(Long userId, Long runId) {
        WorkflowRunPO source = requireOwnedRun(userId, runId);
        if (!"FAILED".equals(source.getStatus()) && !"CANCELLED".equals(source.getStatus())) {
            throw new BusinessException(ErrorCode.WORKFLOW_RETRY_NOT_ALLOWED,
                    "只有失败或已取消的运行可以重试，当前状态：" + source.getStatus());
        }
        WorkflowGraph graph = definitionService.loadGraph(source.getVersionId());
        List<WorkflowNodeRunPO> sourceNodeRuns = nodeRunMapper.selectList(
                Wrappers.<WorkflowNodeRunPO>lambdaQuery()
                        .eq(WorkflowNodeRunPO::getRunId, runId)
                        .orderByAsc(WorkflowNodeRunPO::getId));

        WorkflowRunPO run = newRun(source.getWorkflowId(), source.getVersionId(), userId,
                source.getInputJson(), "RETRY");
        Map<String, WorkflowNodeRunPO> nodeRuns = insertPendingNodeRuns(run.getId(), graph);
        ExecutionState state = stateFromNodeRuns(graph, sourceNodeRuns);
        copySucceededInto(sourceNodeRuns, nodeRuns);

        try {
            GraphOutcome outcome = executeGraph(userId, graph, readInputs(run.getInputJson()), nodeRuns, state);
            if (outcome.suspended()) {
                markWaiting(run);
                return toRunResponse(runMapper.selectById(run.getId()));
            }
            finishRun(run, "SUCCEEDED", writeJson(outcome.output()), null, null);
            return toRunResponse(runMapper.selectById(run.getId()));
        } catch (BusinessException ex) {
            finishRun(run, "FAILED", null, ex.getCode(), ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.warn("工作流重试失败 runId={}", run.getId(), ex);
            finishRun(run, "FAILED", null, ErrorCode.WORKFLOW_NODE_FAILED.getCode(), "工作流节点执行失败");
            throw new BusinessException(ErrorCode.WORKFLOW_NODE_FAILED, "工作流节点执行失败");
        }
    }

    /**
     * 推进停在人工审批上的运行。
     *
     * <p>用「带状态条件的 UPDATE」抢占 WAITING_APPROVAL → RUNNING：
     * 影响行数为 0 就说明别人已经推进过（或已被取消），返回 6007/409 而不是重复执行。
     *
     * <p>同意 → 闸门节点 SUCCEEDED，从闸门之后继续跑；
     * 拒绝 → 闸门节点 FAILED（原因落库）、运行 FAILED。两种情况都返回 200 + 运行详情：
     * 「审批请求成功」与「运行最终状态」是两件事，拒绝不是 HTTP 错误。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public WorkflowRunResponse approve(Long userId, Long runId, WorkflowApprovalRequest request) {
        requireOwnedRun(userId, runId);
        String decision = normalizeDecision(request.decision());
        int claimed = runMapper.update(null, Wrappers.<WorkflowRunPO>lambdaUpdate()
                .eq(WorkflowRunPO::getId, runId)
                .eq(WorkflowRunPO::getStatus, STATUS_WAITING_APPROVAL)
                .set(WorkflowRunPO::getStatus, "RUNNING")
                .set(WorkflowRunPO::getUpdatedAt, LocalDateTime.now()));
        if (claimed == 0) {
            throw new BusinessException(ErrorCode.WORKFLOW_APPROVAL_NOT_PENDING);
        }
        WorkflowRunPO run = runMapper.selectById(runId);
        List<WorkflowNodeRunPO> existing = nodeRunMapper.selectList(Wrappers.<WorkflowNodeRunPO>lambdaQuery()
                .eq(WorkflowNodeRunPO::getRunId, runId)
                .orderByAsc(WorkflowNodeRunPO::getId));
        WorkflowNodeRunPO gate = existing.stream()
                .filter(nr -> STATUS_WAITING_APPROVAL.equals(nr.getStatus()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.WORKFLOW_APPROVAL_NOT_PENDING));

        String comment = request.comment() == null ? "" : request.comment().trim();
        if ("REJECT".equals(decision)) {
            String reason = comment.isBlank() ? "人工审批被拒绝" : "人工审批被拒绝：" + comment;
            gate.setStatus("FAILED");
            gate.setErrorCode(ErrorCode.WORKFLOW_NODE_FAILED.getCode());
            gate.setErrorMessage(reason);
            gate.setFinishedAt(LocalDateTime.now());
            nodeRunMapper.updateById(gate);
            finishRun(run, "FAILED", null, ErrorCode.WORKFLOW_NODE_FAILED.getCode(), reason);
            return toRunResponse(runMapper.selectById(runId));
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("approved", true);
        if (!comment.isBlank()) {
            output.put("comment", comment);
        }
        LocalDateTime now = LocalDateTime.now();
        gate.setStatus("SUCCEEDED");
        gate.setOutputJson(writeJson(output));
        gate.setStartedAt(now);
        gate.setFinishedAt(now);
        gate.setLatencyMs(0L);
        nodeRunMapper.updateById(gate);

        WorkflowGraph graph = definitionService.loadGraph(run.getVersionId());
        // gate 与 existing 里的元素是同一对象，上面已把它改成 SUCCEEDED，所以重建状态时它算「已完成」
        ExecutionState state = stateFromNodeRuns(graph, existing);
        Map<String, WorkflowNodeRunPO> nodeRuns = indexByNode(existing);
        try {
            GraphOutcome outcome = executeGraph(userId, graph, readInputs(run.getInputJson()), nodeRuns, state);
            if (outcome.suspended()) {
                // 后面还有闸门：继续挂起（支持多次审批）
                markWaiting(run);
                return toRunResponse(runMapper.selectById(runId));
            }
            finishRun(run, "SUCCEEDED", writeJson(outcome.output()), null, null);
            return toRunResponse(runMapper.selectById(runId));
        } catch (BusinessException ex) {
            finishRun(run, "FAILED", null, ex.getCode(), ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.warn("工作流审批后继续执行失败 runId={}", runId, ex);
            finishRun(run, "FAILED", null, ErrorCode.WORKFLOW_NODE_FAILED.getCode(), "工作流节点执行失败");
            throw new BusinessException(ErrorCode.WORKFLOW_NODE_FAILED, "工作流节点执行失败");
        }
    }

    /** 新建一条运行记录（run / retry 共用）；调用方负责后续执行与收尾。 */
    private WorkflowRunPO newRun(Long workflowId, Long versionId, Long userId,
                                 String inputJson, String triggerType) {
        WorkflowRunPO run = new WorkflowRunPO();
        run.setWorkflowId(workflowId);
        run.setVersionId(versionId);
        run.setUserId(userId);
        run.setStatus("RUNNING");
        run.setTriggerType(triggerType);
        run.setInputJson(inputJson);
        LocalDateTime now = LocalDateTime.now();
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        run.setStartedAt(now);
        runMapper.insert(run);
        return run;
    }

    /** 把运行置为「等待人工审批」并落库（正常返回而非抛异常，所以事务会提交）。 */
    private void markWaiting(WorkflowRunPO run) {
        WorkflowRunPO current = runMapper.selectById(run.getId());
        if (current == null) {
            return;
        }
        current.setStatus(STATUS_WAITING_APPROVAL);
        current.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(current);
        run.setStatus(STATUS_WAITING_APPROVAL);
    }

    private String normalizeDecision(String decision) {
        String normalized = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        if (!"APPROVE".equals(normalized) && !"REJECT".equals(normalized)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "decision 只接受 APPROVE 或 REJECT");
        }
        return normalized;
    }

    private Map<String, WorkflowNodeRunPO> indexByNode(List<WorkflowNodeRunPO> runs) {
        Map<String, WorkflowNodeRunPO> byNode = new LinkedHashMap<>();
        runs.forEach(nr -> byNode.put(nr.getNodeId(), nr));
        return byNode;
    }

    /**
     * 用「已成功节点」重建可恢复执行状态：输出回灌、出边按当时的分支选择激活。
     * IF_ELSE 的分支选择从它落库的 output（{@code branch} 字段）还原，
     * 因此不需要额外存「执行到哪」的游标。
     */
    private ExecutionState stateFromNodeRuns(WorkflowGraph graph, List<WorkflowNodeRunPO> runs) {
        Map<String, WorkflowNodeRunPO> byNode = indexByNode(runs);
        Map<String, Object> outputs = new HashMap<>();
        Map<String, String> selectedHandles = new HashMap<>();
        Set<String> activeEdges = new HashSet<>();
        Set<String> doneNodes = new HashSet<>();
        for (WorkflowNode node : graph.nodes()) {
            WorkflowNodeRunPO nodeRun = byNode.get(node.id());
            if (nodeRun == null || !"SUCCEEDED".equals(nodeRun.getStatus())) {
                continue;
            }
            Object output = parseJson(nodeRun.getOutputJson());
            doneNodes.add(node.id());
            outputs.put(node.id(), output);
            activateOutgoing(graph, node, output, selectedHandles, activeEdges);
        }
        return new ExecutionState(outputs, selectedHandles, activeEdges, doneNodes);
    }

    /** 重试时把源运行里已成功的节点（含输出与耗时）复制进新运行轨迹，保证 trace 完整。 */
    private void copySucceededInto(List<WorkflowNodeRunPO> sourceRuns,
                                   Map<String, WorkflowNodeRunPO> targetRuns) {
        for (WorkflowNodeRunPO source : sourceRuns) {
            if (!"SUCCEEDED".equals(source.getStatus())) {
                continue;
            }
            WorkflowNodeRunPO target = targetRuns.get(source.getNodeId());
            if (target == null) {
                continue;
            }
            target.setStatus("SUCCEEDED");
            target.setInputJson(source.getInputJson());
            target.setOutputJson(source.getOutputJson());
            target.setStartedAt(source.getStartedAt());
            target.setFinishedAt(source.getFinishedAt());
            target.setLatencyMs(source.getLatencyMs());
            nodeRunMapper.updateById(target);
        }
    }

    /** 源运行的输入 JSON 还原成 map：重试沿用原入参，不该改变它。 */
    private Map<String, Object> readInputs(String inputJson) {
        Object parsed = parseJson(inputJson);
        if (parsed instanceof Map<?, ?> map) {
            Map<String, Object> inputs = new LinkedHashMap<>();
            map.forEach((key, value) -> inputs.put(String.valueOf(key), value));
            return inputs;
        }
        return Map.of();
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

    private GraphOutcome executeGraph(Long userId,
                                      WorkflowGraph graph,
                                      Map<String, Object> inputs,
                                      Map<String, WorkflowNodeRunPO> nodeRuns,
                                      ExecutionState state) throws Exception {
        Map<String, Object> outputs = state.outputs();
        Map<String, String> selectedHandles = state.selectedHandles();
        Set<String> activeEdges = state.activeEdges();
        WorkflowExecutionContext context = new WorkflowExecutionContext(userId, inputs, outputs, variableResolver);

        for (WorkflowNode node : topologicalOrder(graph)) {
            // 恢复执行（重试 / 审批续跑）：已成功的节点保留其输出与出边，不重复执行
            if (state.doneNodes().contains(node.id())) {
                continue;
            }
            WorkflowNodeRunPO nodeRun = nodeRuns.get(node.id());
            List<WorkflowEdge> incoming = graph.edges().stream()
                    .filter(edge -> edge.target().equals(node.id())).toList();
            boolean hasActiveIncoming = node.type().equals("START")
                    || incoming.stream().anyMatch(edge -> activeEdges.contains(edge.id()));
            if (!hasActiveIncoming) {
                markNode(nodeRun, "SKIPPED", null, "上游分支未选中或上游失败", null, null);
                continue;
            }

            // 人工审批闸门：不调用执行器，先把运行挂起等 /approval 推进
            if (HUMAN_APPROVAL.equals(node.type())) {
                markNode(nodeRun, "WAITING_APPROVAL", writeJson(inputOf(node, inputs, outputs)), null, null, null);
                log.info("工作流停在人工审批 runId={} nodeId={}", nodeRun.getRunId(), node.id());
                return new GraphOutcome(null, node.id());
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

        return new GraphOutcome(endOutput(graph, outputs), null);
    }

    /** 结束节点的输出；没有 END 时返回空对象（与改造前同一语义：reduce 取最后一个 END）。 */
    private Object endOutput(WorkflowGraph graph, Map<String, Object> outputs) {
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
