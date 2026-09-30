package com.lky.nexus.app.agent;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.Result;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 接口（Phase 5）。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/ask")
    public Result<AgentAnswer> ask(@RequestBody AskRequest request) {
        return Result.ok(agentService.ask(CurrentUser.requireId(), request.question()));
    }

    public record AskRequest(@NotBlank String question) {
    }
}