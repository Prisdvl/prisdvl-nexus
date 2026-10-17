package com.lky.nexus.app.agent;

import com.lky.nexus.app.agent.model.dto.ToolGrantRequest;
import com.lky.nexus.app.agent.model.dto.ToolGrantResponse;
import com.lky.nexus.app.agent.tool.ToolGrantService;
import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.Result;
import com.lky.nexus.common.exception.BusinessException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户工具授权接口（P0 §八）。
 *
 * <p>四个动作都只作用于 JWT 里的用户：路径里没有 userId，请求体里也不接受它——
 * 授权是"我给这个工具开了长期口子"，越权写入等于替别人开闸。
 */
@RestController
@RequestMapping("/api/users/me/tool-grants")
public class ToolGrantController {

    private final ToolGrantService grantService;

    public ToolGrantController(ToolGrantService grantService) {
        this.grantService = grantService;
    }

    @GetMapping
    public Result<List<ToolGrantResponse>> list() {
        return Result.ok(grantService.list(CurrentUser.requireId()).stream()
                .map(ToolGrantResponse::of)
                .toList());
    }

    /** 授予或更新 always 授权（幂等）；未注册/已禁用的工具不能授权。 */
    @PutMapping
    public Result<ToolGrantResponse> grant(@Valid @RequestBody ToolGrantRequest request) {
        if (!ToolGrantService.SCOPE_ALWAYS.equalsIgnoreCase(request.scopeOrDefault())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "长期授权只支持 scope=always（once / deny 只对单次审批有意义）");
        }
        return Result.ok(ToolGrantResponse.of(
                grantService.grantAlways(CurrentUser.requireId(), request.toolCode())));
    }

    /** 撤销单个授权（幂等：重复撤销不报错）。 */
    @DeleteMapping("/{toolCode}")
    public Result<Void> revoke(@PathVariable String toolCode) {
        grantService.revoke(CurrentUser.requireId(), toolCode);
        return Result.ok(null);
    }

    /** 撤销全部授权（幂等）。 */
    @DeleteMapping
    public Result<Void> revokeAll() {
        grantService.revokeAll(CurrentUser.requireId());
        return Result.ok(null);
    }
}
