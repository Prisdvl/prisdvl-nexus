package com.lky.nexus.app.knowledge.controller;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.Result;
import com.lky.nexus.knowledge.model.dto.SearchExplainResponse;
import com.lky.nexus.knowledge.model.dto.SearchRequest;
import com.lky.nexus.knowledge.model.dto.UnifiedSearchResponse;
import com.lky.nexus.knowledge.service.UnifiedSearchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨库统一检索（§4.2）。
 *
 * <p><b>为什么单开一个 {@code /api/search} 而不是挂在 {@code /api/knowledge-bases/{id}} 下</b>：
 * 单库检索的前提是"库已知"，所以路径里带库 id、参数走 query string；
 * 跨库检索的第一个参数是"哪些库"，用 {@code GET} 表达不了（20 个 id 会把 URL 撑爆），
 * 而且它的语义是"在我的全部库里找"——这本来就该是顶层资源。
 *
 * <p>Controller 只做三件事：取 userId、校验入参、包 {@code Result}。
 * 范围解析、降级、空原因都在 {@link UnifiedSearchService} 里。
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final UnifiedSearchService unifiedSearchService;

    public SearchController(UnifiedSearchService unifiedSearchService) {
        this.unifiedSearchService = unifiedSearchService;
    }

    /** 检索。单个库失败进 {@code degradedKbs}，整体仍返回 200。 */
    @PostMapping
    public Result<UnifiedSearchResponse> search(@Valid @RequestBody SearchRequest request) {
        return Result.ok(unifiedSearchService.search(CurrentUser.requireId(), request));
    }

    /** 检索过程剖析：两路原始名次 + 融合分 + 可选重排分。只读，不落库。 */
    @PostMapping("/explain")
    public Result<SearchExplainResponse> explain(@Valid @RequestBody SearchRequest request) {
        return Result.ok(unifiedSearchService.explain(CurrentUser.requireId(), request));
    }
}
