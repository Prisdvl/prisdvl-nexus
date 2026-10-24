package com.lky.nexus.app.plugin.mcp;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.ai.agent.ToolCategory;
import com.lky.nexus.ai.agent.ToolPermission;
import com.lky.nexus.app.agent.mapper.ToolMapper;
import com.lky.nexus.app.agent.model.entity.ToolPO;
import com.lky.nexus.app.agent.tool.ToolCatalog;
import com.lky.nexus.app.plugin.McpServerService;
import com.lky.nexus.app.plugin.mapper.McpServerMapper;
import com.lky.nexus.app.plugin.model.entity.McpServerPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把"用户启用的外部 MCP server"变成可以装给 Agent 的工具。
 *
 * <p><b>为什么在装配点做、而不是塞进 {@code ToolCatalog}</b>：目录是启动期的静态表
 * （code → 反射出来的实现），而 MCP 工具是<b>按用户、按 server、按探测结果</b>动态变化的。
 * 把它塞进目录，目录就不再是不可变的，还会让"启动时校验 code 唯一"这条保护失效。
 * 所以这里只<b>追加</b>到 {@code registry.resolveFor} 的结果之后，同一个装配点、
 * 同一层授权闸门。
 *
 * <p>过滤顺序与内置工具完全一致，缺一不可：
 * <ol>
 *   <li>server 被停用 → 不装（用户关掉一个 server 的意志优先于一切）；</li>
 *   <li>工具在 {@code tool} 表里被该用户停用 → 不装；</li>
 *   <li>Agent 声明了工具白名单（{@code toolScoped()}）→ 只装白名单里的。</li>
 * </ol>
 */
@Component
public class McpToolInjector {

    private static final Logger log = LoggerFactory.getLogger(McpToolInjector.class);

    private final McpServerMapper serverMapper;
    private final ToolMapper toolMapper;
    private final McpClientFactory clientFactory;
    private final JsonMapper json;

    public McpToolInjector(McpServerMapper serverMapper,
                           ToolMapper toolMapper,
                           McpClientFactory clientFactory,
                           JsonMapper json) {
        this.serverMapper = serverMapper;
        this.toolMapper = toolMapper;
        this.clientFactory = clientFactory;
        this.json = json;
    }

    public List<ToolCatalog.Registered> toolsFor(Long userId, AgentDefinition definition) {
        List<McpServerPO> servers = serverMapper.selectList(Wrappers.<McpServerPO>lambdaQuery()
                .eq(McpServerPO::getUserId, userId)
                .eq(McpServerPO::getEnabled, 1)
                .isNotNull(McpServerPO::getToolsJson)
                .orderByAsc(McpServerPO::getCreatedAt));
        if (servers.isEmpty()) {
            return List.of();
        }
        Set<String> whitelist = definition != null && definition.toolScoped()
                ? Set.copyOf(definition.toolCodes())
                : Set.of();
        Map<String, Boolean> overrides = overridesOf(userId);

        List<ToolCatalog.Registered> tools = new ArrayList<>();
        for (McpServerPO server : servers) {
            for (JsonNode item : parseTools(server.getToolsJson())) {
                String code = McpServerService.toolCode(server.getSlug(), item.get("name").asString());
                if (!whitelist.isEmpty() && !whitelist.contains(code)) {
                    continue;
                }
                if (Boolean.FALSE.equals(overrides.get(code))) {
                    continue;
                }
                tools.add(toRegistered(server, item, code));
            }
        }
        if (!tools.isEmpty()) {
            log.debug("为用户 {} 注入 {} 个 MCP 工具（来自 {} 个 server）", userId, tools.size(), servers.size());
        }
        return List.copyOf(tools);
    }

    /** MCP 工具一律 READ：外部 server 的风险等级我们无从判断，交给用户用启停去管。 */
    private ToolCatalog.Registered toRegistered(McpServerPO server, JsonNode tool, String code) {
        String toolName = tool.get("name").asString();
        JsonNode description = tool.get("description");
        JsonNode schema = tool.get("inputSchema");
        String displayName = "%s · %s".formatted(server.getName(), toolName);
        McpToolCallback callback = new McpToolCallback(clientFactory, server, code, toolName, displayName,
                description == null || description.isNull() ? null : description.asString(),
                schema == null || schema.isNull() ? null : schema.asString());
        return new ToolCatalog.Registered(code, displayName, ToolCategory.OTHER, ToolPermission.READ,
                callback.getToolDefinition().description(), callback.getToolDefinition().inputSchema(),
                callback, false);
    }

    /** 用户在 {@code tool} 表里的停用覆盖；没有行 = 启用（与 ToolRegistry 同一条默认）。 */
    private Map<String, Boolean> overridesOf(Long userId) {
        Map<String, Boolean> overrides = new HashMap<>();
        for (ToolPO row : toolMapper.selectList(Wrappers.<ToolPO>lambdaQuery()
                .eq(ToolPO::getOwnerId, userId))) {
            overrides.put(row.getCode(), row.getEnabled());
        }
        return overrides;
    }

    private List<JsonNode> parseTools(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = json.readTree(raw);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<JsonNode> tools = new ArrayList<>();
            for (JsonNode item : node) {
                JsonNode name = item.get("name");
                if (name != null && !name.isNull() && !name.asString().isBlank()) {
                    tools.add(item);
                }
            }
            return tools;
        } catch (RuntimeException ex) {
            log.warn("MCP 工具清单解析失败，已按空处理：{}", ex.getMessage());
            return List.of();
        }
    }
}
