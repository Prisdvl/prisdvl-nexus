package com.lky.nexus.app.agent.approval;

import com.lky.nexus.ai.agent.ToolPermission;
import com.lky.nexus.app.agent.mapper.ToolInvocationMapper;
import com.lky.nexus.app.agent.model.ToolInvocationStatus;
import com.lky.nexus.app.agent.model.entity.ToolInvocationPO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 工具调用审计（{@code ai_tool_invocation}，P0 §九）。
 *
 * <p>每一次进入执行壳的工具调用都会先落一行 {@code PENDING_APPROVAL}（不需要审批的也从这一状态起步），
 * 结束时再改写成真实结果。这样即使进程在工具执行中被杀掉，库里留下的也是
 * "有一次调用开始了但没有结束"——比"什么都没记"能解释得多。
 *
 * <p>{@code permission} 存的是执行当时**数据库里生效**的权限，而不是代码声明：
 * "这次调用为什么被拦"事后要能只靠审计表复盘。
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
}
