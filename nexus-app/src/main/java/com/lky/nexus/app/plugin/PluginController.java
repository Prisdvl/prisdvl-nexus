package com.lky.nexus.app.plugin;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.app.plugin.model.dto.PluginResponse;
import com.lky.nexus.app.plugin.model.dto.UpdatePluginRequest;
import com.lky.nexus.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户级插件开关与配置。
 *
 * <p>没有 {@code POST}：插件目录活在前端（{@code plugins.ts}），服务端只存"这个用户对它做了什么"。
 * 所以"创建"这件事不存在——第一次 {@code PUT} 就是创建，{@code GET} 一个没动过的 code
 * 返回默认值（启用、空配置）。
 *
 * <p>Controller 只取 userId、接参数、包 {@link Result}；默认值与 upsert 规则全在
 * {@link PluginService} 里，这样 {@code PluginToggleTest} 与页面走的是同一套语义。
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private final PluginService pluginService;

    public PluginController(PluginService pluginService) {
        this.pluginService = pluginService;
    }

    @GetMapping
    public Result<List<PluginResponse>> list() {
        return Result.ok(pluginService.list(CurrentUser.requireId()));
    }

    @GetMapping("/{code}")
    public Result<PluginResponse> get(@PathVariable String code) {
        return Result.ok(pluginService.get(CurrentUser.requireId(), code));
    }

    @PutMapping("/{code}")
    public Result<PluginResponse> update(@PathVariable String code,
                                         @Valid @RequestBody UpdatePluginRequest request) {
        return Result.ok(pluginService.update(CurrentUser.requireId(), code, request));
    }
}
