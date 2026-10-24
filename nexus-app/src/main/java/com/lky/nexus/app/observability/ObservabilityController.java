package com.lky.nexus.app.observability;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.app.observability.model.dto.ModelUsageResponse;
import com.lky.nexus.app.observability.model.dto.OverviewResponse;
import com.lky.nexus.app.observability.model.dto.RequestDetailResponse;
import com.lky.nexus.app.observability.model.dto.RequestSummaryResponse;
import com.lky.nexus.app.observability.model.dto.TrendResponse;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.api.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 观测只读端点（{@code docs/api.md} §10.1）。
 *
 * <p>五个接口<b>全部只读</b>，且全部按当前用户过滤——观测数据不是"全站公开",
 * 别人的请求 ID 在这里既查不到详情、也不会出现在列表里。
 *
 * <p>Controller 只做三件事：取 userId、收参数、包 {@link Result}。
 * 窗口夹取、分桶、聚合、成本估算一律在 {@link ObservabilityQueryService}。
 *
 * <p>窗口参数 {@code hours} 三处一致：缺省 24，小于 1 夹到 1，大于 720 夹到 720
 * （30 天）。夹取发生在服务层，所以三个端点的"默认值"不会漂。
 */
@RestController
@RequestMapping("/api/observability")
public class ObservabilityController {

    private final ObservabilityQueryService queryService;

    public ObservabilityController(ObservabilityQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 总览：调用数、token、平均延迟、首 Token、RAG/工具次数、失败率、估算成本。
     *
     * @param hours 窗口小时数（默认 24，上限 720）
     */
    @GetMapping("/overview")
    public Result<OverviewResponse> overview(
            @RequestParam(required = false) Integer hours) {
        return Result.ok(queryService.overview(CurrentUser.requireId(), hours));
    }

    /**
     * 趋势：按小时或按天分桶，空桶补零。
     *
     * @param by     {@code hour}（默认）或 {@code day}
     * @param scene  可选场景过滤
     * @param model  可选模型过滤
     * @param hours  窗口小时数
     */
    @GetMapping("/trend")
    public Result<TrendResponse> trend(
            @RequestParam(required = false, defaultValue = "hour") String by,
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) Integer hours) {
        return Result.ok(queryService.trend(CurrentUser.requireId(), by, scene, model, hours));
    }

    /**
     * 按模型的用量与成本（缺价格时 {@code estimatedCost} 为 null）。
     *
     * @param scene 可选场景过滤
     * @param hours 窗口小时数
     */
    @GetMapping("/models")
    public Result<List<ModelUsageResponse>> models(
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) Integer hours) {
        return Result.ok(queryService.models(CurrentUser.requireId(), scene, hours));
    }

    /**
     * 请求列表：一行一个 {@code request_id}（工具循环的多轮调用已聚合）。
     *
     * @param scene   可选场景过滤
     * @param success 可选成败过滤（true = 全部调用成功；false = 含失败调用）
     * @param hours   窗口小时数
     */
    @GetMapping("/requests")
    public Result<PageResult<RequestSummaryResponse>> requests(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) Integer hours) {
        return Result.ok(queryService.requests(CurrentUser.requireId(), pageNum, pageSize,
                scene, success, hours));
    }

    /**
     * 单次请求全链路：逐轮调用 + 检索摘要 + 工具摘要。
     *
     * @param requestId 与响应头 {@code X-Request-Id} 一致
     */
    @GetMapping("/requests/{requestId}")
    public Result<RequestDetailResponse> request(@PathVariable String requestId) {
        return Result.ok(queryService.request(CurrentUser.requireId(), requestId));
    }
}
