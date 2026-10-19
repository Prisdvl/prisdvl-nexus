package com.lky.nexus.app.agent.approval;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lky.nexus.ai.agent.ToolPermission;
import com.lky.nexus.app.agent.mapper.ToolInvocationMapper;
import com.lky.nexus.app.agent.model.ToolInvocationStatus;
import com.lky.nexus.app.agent.model.dto.ToolInvocationResponse;
import com.lky.nexus.app.agent.model.entity.ToolInvocationPO;
import com.lky.nexus.common.api.PageQuery;
import com.lky.nexus.common.api.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具调用审计（{@code ai_tool_invocation}，P0 §九）。
 *
 * <p>每一次进入执行壳的工具调用都会先落一行 {@code PENDING_APPROVAL}（不需要审批的也从这一状态起步），
 * 结束时再改写成真实结果。这样即使进程在工具执行中被杀掉，库里留下的也是
 * "有一次调用开始了但没有结束"——比"什么都没记"能解释得多。
 *
 * <p>{@code permission} 存的是执行当时**数据库里生效**的权限，而不是代码声明：
 * "这次调用为什么被拦"事后要能只靠审计表复盘。
 *
 * <p>本类同时提供<b>只读查询</b>（roadmap 待办 #7 的第 2 条：原先只写不查，
 * 审计落了库却没有任何接口能取回来）。查询一律按 {@code userId} 收口——
 * 审计表里带着别人的工具名、参数摘要与运行 id，跨用户可读是不能接受的。
 */
@Service
public class ToolInvocationAuditService {

    private final ToolInvocationMapper mapper;

    public ToolInvocationAuditService(ToolInvocationMapper mapper) {
        this.mapper = mapper;
    }

    /** 开始一次调用审计；返回 invocation id（后续用它更新结果）。 */
    @Transactional
    public Long begin(Long userId, Long runId, String toolCode,
                      ToolPermission permission, String argumentsDigest) {
        ToolInvocationPO row = new ToolInvocationPO();
        row.setUserId(userId);
        row.setRunId(runId);
        row.setToolCode(toolCode);
        row.setPermission(permission == null ? null : permission.name());
        row.setArgumentsDigest(argumentsDigest);
        row.setStatus(ToolInvocationStatus.PENDING_APPROVAL.name());
        row.setStartedAt(LocalDateTime.now());
        mapper.insert(row);
        return row.getId();
    }

    /** 把审计行关联到审批记录（审批链路专用）。 */
    @Transactional
    public void attachApproval(Long invocationId, Long approvalId) {
        if (invocationId == null) {
            return;
        }
        ToolInvocationPO row = mapper.selectById(invocationId);
        if (row == null) {
            return;
        }
        row.setApprovalId(approvalId);
        mapper.updateById(row);
    }

    /** 落定结果；{@code stepId} 用于把审计行挂到具体步骤上（可空）。 */
    @Transactional
    public void finish(Long invocationId, ToolInvocationStatus status,
                       String resultDigest, Integer errorCode, Long stepId) {
        if (invocationId == null) {
            return;
        }
        ToolInvocationPO row = mapper.selectById(invocationId);
        if (row == null) {
            return;
        }
        row.setStatus(status.name());
        row.setResultDigest(resultDigest);
        row.setErrorCode(errorCode);
        if (stepId != null) {
            row.setStepId(stepId);
        }
        row.setFinishedAt(LocalDateTime.now());
        mapper.updateById(row);
    }

    /**
     * 查询当前用户的工具调用审计（新到旧）。
     *
     * <p>筛选条件都是可选的：{@code toolCode} / {@code status} / {@code runId}，
     * 传空即不参与过滤。{@code status} 不做枚举校验——库里存的就是字符串，
     * 传了不存在的值只会得到空页，不值得为一个只读筛选多抛一次 400。
     */
    public PageResult<ToolInvocationResponse> list(Long userId, String toolCode, String status,
                                                   Long runId, int pageNum, int pageSize) {
        int safePageNum = PageQuery.normalizePageNum(pageNum);
        int safePageSize = PageQuery.normalizePageSize(pageSize);

        Page<ToolInvocationPO> page = new Page<>(safePageNum, safePageSize);
        Page<ToolInvocationPO> result = mapper.selectPage(page,
                Wrappers.<ToolInvocationPO>lambdaQuery()
                        .eq(ToolInvocationPO::getUserId, userId)
                        .eq(StringUtils.hasText(toolCode), ToolInvocationPO::getToolCode, toolCode)
                        .eq(StringUtils.hasText(status), ToolInvocationPO::getStatus, status)
                        .eq(runId != null, ToolInvocationPO::getRunId, runId)
                        // 雪花 id 递增：按 id 倒序即"最近发生的在前"，不额外依赖 started_at
                        .orderByDesc(ToolInvocationPO::getId));

        List<ToolInvocationResponse> list = result.getRecords().stream()
                .map(ToolInvocationAuditService::toResponse)
                .toList();
        return PageResult.of(list, result.getTotal(), safePageNum, safePageSize);
    }

    private static ToolInvocationResponse toResponse(ToolInvocationPO row) {
        return new ToolInvocationResponse(
                row.getId(),
                row.getRunId(),
                row.getStepId(),
                row.getToolCode(),
                row.getPermission(),
                row.getStatus(),
                row.getApprovalId(),
                row.getErrorCode(),
                row.getArgumentsDigest(),
                row.getResultDigest(),
                row.getStartedAt(),
                row.getFinishedAt());
    }
}
