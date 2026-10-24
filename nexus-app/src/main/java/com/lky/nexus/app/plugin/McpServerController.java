package com.lky.nexus.app.plugin;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.app.plugin.model.dto.McpProbeResponse;
import com.lky.nexus.app.plugin.model.dto.McpServerRequest;
import com.lky.nexus.app.plugin.model.dto.McpServerResponse;
import com.lky.nexus.app.plugin.model.dto.UpdateMcpServerRequest;
import com.lky.nexus.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 外部 MCP server 的增删改查与探测。
 *
 * <p><b>probe 永远返回 200</b>：它是在问"这个 server 现在能连通吗"，答案"不能"同样是答案。
 * 只有请求本身写错（传输方式不认识、server 不存在/不是你的）才用 4xx。
 * 把连通性失败做成 5xx，前端就只能显示"服务器错误"，而用户真正需要看到的是
 * 那句 {@code connection refused}。
 */
@RestController
@RequestMapping("/api/mcp-servers")
public class McpServerController {

    private final McpServerService serverService;

    public McpServerController(McpServerService serverService) {
        this.serverService = serverService;
    }

    @GetMapping
    public Result<List<McpServerResponse>> list() {
        return Result.ok(serverService.list(CurrentUser.requireId()));
    }

    @PostMapping
    public Result<McpServerResponse> create(@Valid @RequestBody McpServerRequest request) {
        return Result.ok(serverService.create(CurrentUser.requireId(), request));
    }

    @PutMapping("/{serverId}")
    public Result<McpServerResponse> update(@PathVariable Long serverId,
                                            @Valid @RequestBody UpdateMcpServerRequest request) {
        return Result.ok(serverService.update(CurrentUser.requireId(), serverId, request));
    }

    @DeleteMapping("/{serverId}")
    public Result<Void> delete(@PathVariable Long serverId) {
        serverService.delete(CurrentUser.requireId(), serverId);
        return Result.ok();
    }

    @PostMapping("/{serverId}/probe")
    public Result<McpProbeResponse> probe(@PathVariable Long serverId) {
        return Result.ok(serverService.probe(CurrentUser.requireId(), serverId));
    }
}
