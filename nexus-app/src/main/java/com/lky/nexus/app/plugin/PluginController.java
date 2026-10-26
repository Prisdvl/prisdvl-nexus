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
 * 插件目录与用户安装状态（V34 起目录在服务端：{@code plugin_catalog}）。
 *
 * <p>没有 {@code POST}：目录由 {@code PluginCatalogService} 在启动时 seed，
 * 用户这一侧只存在"装/卸/改配置"，所以第一次 {@code PUT} 就是安装。
 *
 * <p>{@code GET} 返回的是目录 + 当前用户的合并视图（含 name/icon/category 等展示字段），
 * 前端不再自带清单。
 *
 * <p>Controller 只取 userId、接参数、包 {@link Result}；合并规则与 upsert 全在
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
