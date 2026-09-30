package com.lky.nexus.app.agent;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.Result;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 接口（Phase 5）：用<b>内置默认 Agent</b> 跑一次一次性问答。
 *
 * <p>Phase 4.1 起提示词与模型角色不再写死在代码里，而是读该用户的默认 Agent 那一行
 * （见 {@link AgentRuntime#askWithDefault}）——用户在界面上改了默认 Agent，
 * 本接口与 MCP、评测三个入口的行为一起跟着变。管理 API 在
 * {@link AgentResourceController}（{@code /api/agents}）。
 *
 * <p>路径保持单数 {@code /api/agent}：它是已有接口，改名会破坏调用方（§四十九 的精神同样适用于 API）。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentRuntime agentRuntime;

    public AgentController(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    @PostMapping("/ask")
    public Result<AgentAnswer> ask(@RequestBody AskRequest request) {
        return Result.ok(agentRuntime.askWithDefault(CurrentUser.requireId(), request.question()));
    }

    public record AskRequest(@NotBlank String question) {
    }
}