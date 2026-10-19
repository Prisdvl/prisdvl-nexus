package com.lky.nexus.app.agent;

import com.lky.nexus.app.agent.approval.ToolInvocationAuditService;
import com.lky.nexus.app.agent.model.dto.ToolInvocationResponse;
import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.api.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具调用审计查询（roadmap 待办 #7 的第 2 条）。
 *
 * <p>审计从 P0 起就一直在写（{@code ai_tool_invocation}），但此前没有任何接口能把它取回来，
 * "某个工具为什么被拦"只能翻库。这里补上只读查询：<b>没有 userId 参数</b>，
 * 一律用 {@link CurrentUser#requireId()} 收口，避免审计数据跨用户可读。
 */
@RestController
@RequestMapping("/api/tool-invocations")
public class ToolInvocationController {

    private final ToolInvocationAuditService auditService;

    public ToolInvocationController(ToolInvocationAuditService auditService) {
        this.auditService = auditService;
    }

    /** 分页查询当前用户的工具调用审计（新到旧）；筛选条件均可省略。 */
    @GetMapping
    public Result<PageResult<ToolInvocationResponse>> list(
            @RequestParam(required = false) String toolCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long runId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(auditService.list(
                CurrentUser.requireId(), toolCode, status, runId, pageNum, pageSize));
    }
}
