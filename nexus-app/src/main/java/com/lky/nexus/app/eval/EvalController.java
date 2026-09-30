package com.lky.nexus.app.eval;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.Result;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG 评测接口（Phase 6.1）。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalService evalService;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
    }

    @PostMapping("/kb/{knowledgeBaseId}")
    public Result<EvalService.EvalReport> run(
            @PathVariable Long knowledgeBaseId,
            @RequestBody RunRequest request) {
        return Result.ok(evalService.run(CurrentUser.requireId(), knowledgeBaseId, request.cases()));
    }

    public record RunRequest(List<EvalService.EvalCase> cases) {
    }
}