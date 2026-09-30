package com.lky.nexus.app.agent;

import com.lky.nexus.app.agent.model.dto.AgentResponse;
import com.lky.nexus.app.agent.model.dto.CreateAgentRequest;
import com.lky.nexus.app.agent.model.dto.TestAgentRequest;
import com.lky.nexus.app.agent.model.dto.UpdateAgentRequest;
import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 资源接口（平台化 Phase 4.1，§十五）：用户对自己的 Agent 做增删改查、复制、启停、绑定知识库。
 *
 * <p>与 {@code /api/agent}（单数，Phase 5 就有的"用默认 Agent 问一句"）的分工：
 * <ul>
 *   <li>{@code /api/agents}（复数）= <b>管理</b>资源，走 {@link AgentService}（碰库、查归属与状态）；</li>
 *   <li>{@code /api/agent/ask} = <b>执行</b>，走 {@link AgentRuntime}（只认 Definition，不碰库）。</li>
 * </ul>
 *
 * <p>Controller 与知识库那边同一个约定：只取 userId、接参数、包 {@code Result}，
 * 业务规则一律在服务层——这也是 §六十七 "不要在 Controller 堆 AI 逻辑" 的落点。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentResourceController {

    private final AgentService agentService;
    private final AgentRuntime agentRuntime;

    public AgentResourceController(AgentService agentService, AgentRuntime agentRuntime) {
        this.agentService = agentService;
        this.agentRuntime = agentRuntime;
    }

    @GetMapping
    public Result<PageResult<AgentResponse>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(agentService.list(CurrentUser.requireId(), pageNum, pageSize));
    }

    @PostMapping
    public Result<AgentResponse> create(@Valid @RequestBody CreateAgentRequest request) {
        return Result.ok(agentService.create(CurrentUser.requireId(), request));
    }

    @GetMapping("/{agentId}")
    public Result<AgentResponse> detail(@PathVariable Long agentId) {
        return Result.ok(agentService.detail(CurrentUser.requireId(), agentId));
    }

    /**
     * 局部更新：请求体里为 null 的字段一律"不改"。
     *
     * <p>启停也走这里（{@code {"status":"DISABLED"}}），不再单独开一个 {@code /status} 接口——
     * 同一个资源的同一类字段有两种写法只会让前端纠结用哪个，而"null = 不改"的语义已经能表达启停。
     */
    @PutMapping("/{agentId}")
    public Result<AgentResponse> update(@PathVariable Long agentId,
                                        @Valid @RequestBody UpdateAgentRequest request) {
        return Result.ok(agentService.update(CurrentUser.requireId(), agentId, request));
    }

    /** 复制（§十五）：副本回到 DRAFT，供用户改完再发布。 */
    @PostMapping("/{agentId}/duplicate")
    public Result<AgentResponse> duplicate(@PathVariable Long agentId) {
        return Result.ok(agentService.duplicate(CurrentUser.requireId(), agentId));
    }

    @DeleteMapping("/{agentId}")
    public Result<Void> delete(@PathVariable Long agentId) {
        agentService.delete(CurrentUser.requireId(), agentId);
        return Result.ok();
    }

    /**
     * 试跑（§十八 的 Test Chat）：<b>不受发布状态限制</b>。
     *
     * <p>为什么必须允许 DRAFT：§十八 的流程是"用户可以先测试 Agent，再发布"，
     * 若试跑也要求 PUBLISHED，草稿就永远没法被验证，只能靠"发布后才发现提示词写错了"。
     * 归属仍然校验（只能试跑自己的），也就是说这里的宽松只针对状态，不针对隔离。
     *
     * <p>返回值与 {@code /api/agent/ask} 一致（答案 + 工具痕迹），前端据此展示"刚才检索了哪些库"。
     */
    @PostMapping("/{agentId}/test-chat")
    public Result<AgentAnswer> testChat(@PathVariable Long agentId,
                                        @Valid @RequestBody TestAgentRequest request) {
        Long userId = CurrentUser.requireId();
        // 试跑带知识库范围：否则"绑了库的 Agent"在试跑时搜的是全部库，测试结论与线上不符
        return Result.ok(agentRuntime.ask(userId, agentService.testDefinition(userId, agentId),
                request.question()));
    }
}
