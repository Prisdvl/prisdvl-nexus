package com.lky.nexus.app.plugin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.ai.agent.ToolCategory;
import com.lky.nexus.ai.agent.ToolPermission;
import com.lky.nexus.app.agent.mapper.ToolRegistryMapper;
import com.lky.nexus.app.agent.model.entity.ToolRegistryPO;
import com.lky.nexus.app.agent.tool.ToolRegistrationService;
import com.lky.nexus.app.plugin.mapper.McpServerMapper;
import com.lky.nexus.app.plugin.mcp.McpClient;
import com.lky.nexus.app.plugin.mcp.McpClientFactory;
import com.lky.nexus.app.plugin.mcp.McpException;
import com.lky.nexus.app.plugin.model.dto.McpProbeResponse;
import com.lky.nexus.app.plugin.model.dto.McpServerRequest;
import com.lky.nexus.app.plugin.model.dto.McpServerResponse;
import com.lky.nexus.app.plugin.model.dto.McpToolInfo;
import com.lky.nexus.app.plugin.model.dto.UpdateMcpServerRequest;
import com.lky.nexus.app.plugin.model.entity.McpServerPO;
import com.lky.nexus.app.security.CredentialCipher;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 外部 MCP server 的登记与探测（{@code mcp_server}）。
 *
 * <p>这个类只做四件事：记住配置、把凭据加密、连一次 {@code tools/list}、把结果落成
 * {@code tools_json} 与工具注册行。它<b>不</b>负责执行工具调用（那是
 * {@link com.lky.nexus.app.plugin.mcp.McpToolCallback}）。
 *
 * <p>三条容易做错的地方，写在这里：
 * <ul>
 *   <li><b>probe 失败不是 4xx/5xx</b>：那是上游连不上，而不是你的请求写错了，
 *       所以照样 200，把原因放进 {@code error}。相反，传输方式不认识才是 400——
 *       两者混成一个码，用户就分不清该改配置还是该查网络；</li>
 *   <li><b>probe 成功必须写工具注册表</b>：执行前的授权闸门会 {@code requireRegistered(code)}，
 *       不登记的表现是"模型看得见、一调就被拒"；</li>
 *   <li><b>工具 code 全局唯一</b>：{@code ai_tool_registry} 没有 user_id 维度，
 *       所以 slug 在生成时按<b>全库</b>唯一处理。两个用户都叫 "github" 时，第二个会拿到
 *       "github-2"——这比让两个用户的工具互相覆盖要好得多。</li>
 * </ul>
 */
@Service
public class McpServerService {

    private static final Logger log = LoggerFactory.getLogger(McpServerService.class);

    /** 工具名对模型可见，必须是可以当一个标识符念出来的东西。 */
    private static final String CODE_PREFIX = "mcp__";
    private static final String SEPARATOR = "__";

    private final McpServerMapper serverMapper;
    private final McpClientFactory clientFactory;
    private final ToolRegistrationService registrationService;
    private final ToolRegistryMapper registryMapper;
    private final CredentialCipher cipher;
    private final JsonMapper json;

    public McpServerService(McpServerMapper serverMapper,
                            McpClientFactory clientFactory,
                            ToolRegistrationService registrationService,
                            ToolRegistryMapper registryMapper,
                            CredentialCipher cipher,
                            JsonMapper json) {
        this.serverMapper = serverMapper;
        this.clientFactory = clientFactory;
        this.registrationService = registrationService;
        this.registryMapper = registryMapper;
        this.cipher = cipher;
        this.json = json;
    }

    public List<McpServerResponse> list(Long userId) {
        return serverMapper.selectList(Wrappers.<McpServerPO>lambdaQuery()
                        .eq(McpServerPO::getUserId, userId)
                        .orderByAsc(McpServerPO::getCreatedAt))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public McpServerResponse create(Long userId, McpServerRequest request) {
        McpServerPO server = new McpServerPO();
        server.setUserId(userId);
        server.setName(request.name().trim());
        server.setSlug(uniqueSlug(slugify(request.name())));
        server.setTransport(normalizeTransport(request.transport()));
        server.setUrl(blankToNull(request.url()));
        server.setCommand(blankToNull(request.command()));
        server.setAuthHeaderCipher(encrypt(request.authHeader()));
        server.setEnabled(request.enabled() == null || request.enabled() ? 1 : 0);
        // 默认是 0：stdio 会在服务器上执行命令，"用户什么都没说"绝不能当成"用户同意了"
        server.setStdioAllowed(Boolean.TRUE.equals(request.allowStdio()) ? 1 : 0);
        LocalDateTime now = LocalDateTime.now();
        server.setCreatedAt(now);
        server.setUpdatedAt(now);
        serverMapper.insert(server);
        return toResponse(server);
    }

    @Transactional
    public McpServerResponse update(Long userId, Long serverId, UpdateMcpServerRequest request) {
        McpServerPO server = requireOwned(userId, serverId);
        if (request.name() != null && !request.name().isBlank()) {
            server.setName(request.name().trim());
            // 刻意不改 slug：它已经刻在工具 code（mcp__slug__tool）与调用记录里，
            // 改个显示名就把历史工具名改断了，而用户只是改了个名字
        }
        if (request.transport() != null && !request.transport().isBlank()) {
            server.setTransport(normalizeTransport(request.transport()));
        }
        if (request.url() != null) {
            server.setUrl(blankToNull(request.url()));
        }
        if (request.command() != null) {
            server.setCommand(blankToNull(request.command()));
        }
        if (request.authHeader() != null) {
            // 空串是"清除凭据"，null 是"别动"——两者必须分开
            server.setAuthHeaderCipher(encrypt(request.authHeader()));
        }
        if (request.enabled() != null) {
            server.setEnabled(request.enabled() ? 1 : 0);
        }
        if (request.allowStdio() != null) {
            // 取消勾选必须显式传 false：和 authHeader 一样，null 是"别动"
            server.setStdioAllowed(request.allowStdio() ? 1 : 0);
        }
        server.setUpdatedAt(LocalDateTime.now());
        serverMapper.updateById(server);
        return toResponse(server);
    }

    @Transactional
    public void delete(Long userId, Long serverId) {
        McpServerPO server = requireOwned(userId, serverId);
        serverMapper.deleteById(serverId);
        disableToolsOf(server.getSlug(), List.of(), LocalDateTime.now());
    }

    /**
     * 连一次 {@code tools/list} 并把结果落库。
     *
     * <p>失败时<b>保留上一次的 {@code tools_json}</b>（不覆盖成空）：页面上"上次探测成功过、
     * 现在连不上"比"从来没有工具"信息量大得多，而把旧结果清空等于把这段历史删了。
     */
    public McpProbeResponse probe(Long userId, Long serverId) {
        McpServerPO server = requireOwned(userId, serverId);
        long startedNanos = System.nanoTime();
        List<McpToolInfo> tools;
        try (McpClient client = clientFactory.create(server)) {
            tools = client.listTools();
        } catch (McpException ex) {
            // McpException 自带错误码：9205 超时 / 9206 子进程退出 / 9207 JSON-RPC 拒绝 /
            // 9204 stdio 未允许 / 5005 连不上。前端按码分支、按文案展示
            return recordProbeFailure(server, ex.getCode().getCode(), ex.getMessage(), startedNanos);
        } catch (RuntimeException ex) {
            // 不是通信失败（例如解密凭据失败）：不谎报成 5005，把原文交给用户
            return recordProbeFailure(server, null, rootMessage(ex), startedNanos);
        }
        LocalDateTime now = LocalDateTime.now();
        String toolsJson = json.writeValueAsString(tools);
        serverMapper.update(null, Wrappers.<McpServerPO>lambdaUpdate()
                .eq(McpServerPO::getId, server.getId())
                .set(McpServerPO::getToolsJson, toolsJson)
                .set(McpServerPO::getLastOkAt, now)
                .set(McpServerPO::getLastError, null)
                .set(McpServerPO::getUpdatedAt, now));
        syncToolRegistry(server, tools, now);
        log.info("MCP server 探测成功 id={} name={}，发现 {} 个工具", server.getId(), server.getName(), tools.size());
        return new McpProbeResponse(true, null, null, tools, elapsedMillis(startedNanos), now);
    }

    /** 探测失败的唯一落点：写 {@code last_error}、保留旧 {@code tools_json}、把原因与码一起回给前端。 */
    private McpProbeResponse recordProbeFailure(McpServerPO server, Integer errorCode, String reason,
                                                long startedNanos) {
        LocalDateTime now = LocalDateTime.now();
        String detail = errorCode == null ? reason : "[%d] %s".formatted(errorCode, reason);
        serverMapper.update(null, Wrappers.<McpServerPO>lambdaUpdate()
                .eq(McpServerPO::getId, server.getId())
                .set(McpServerPO::getLastError, clip(detail))
                .set(McpServerPO::getUpdatedAt, now));
        log.warn("MCP server 探测失败 id={} name={} code={}：{}", server.getId(), server.getName(), errorCode, reason);
        // 刻意不动 tools_json：上一次拉到的工具有效，只是现在连不上
        return new McpProbeResponse(false, reason, errorCode, List.of(), elapsedMillis(startedNanos),
                server.getLastOkAt());
    }

    /** 工具 code 对某个 slug 的前缀，注入与清理两侧共用同一段拼法。 */
    public static String codePrefix(String slug) {
        return CODE_PREFIX + slug + SEPARATOR;
    }

    /** {@code mcp__<slug>__<tool>}；工具名里的怪字符一律折成下划线，保证模型能照抄。 */
    public static String toolCode(String slug, String toolName) {
        return codePrefix(slug) + sanitize(toolName);
    }

    private void syncToolRegistry(McpServerPO server, List<McpToolInfo> tools, LocalDateTime now) {
        List<String> codes = new ArrayList<>();
        for (McpToolInfo tool : tools) {
            String code = toolCode(server.getSlug(), tool.name());
            codes.add(code);
            registrationService.registerExternal(code,
                    "%s · %s".formatted(server.getName(), tool.name()),
                    tool.description(),
                    ToolCategory.OTHER,
                    // 外部工具一律按写操作处理：它的行为不归我们控制（可能删库、可能发邮件），
                    // 所以"默认不经审批就执行外部指令"是这次改动最不能留的口子。
                    // 用户真要免审批，走 ToolGrantController 的 always 授权——那是显式、可撤销的
                    ToolPermission.WRITE,
                    "MCP");
        }
        // 上游删掉的工具要跟着失效，否则模型还能看到并调用一个已经不存在的工具。
        // 注意只做"失效"、不做"复活"：用户手动停用过的工具，下一次 probe 不该被悄悄打开
        disableToolsOf(server.getSlug(), codes, now);
    }

    private void disableToolsOf(String slug, List<String> keepCodes, LocalDateTime now) {
        var update = Wrappers.<ToolRegistryPO>lambdaUpdate()
                .likeRight(ToolRegistryPO::getCode, codePrefix(slug))
                .eq(ToolRegistryPO::getEnabled, true)
                .set(ToolRegistryPO::getEnabled, false)
                .set(ToolRegistryPO::getUpdatedAt, now);
        if (!keepCodes.isEmpty()) {
            update.notIn(ToolRegistryPO::getCode, keepCodes);
        }
        registryMapper.update(null, update);
    }

    private McpServerPO requireOwned(Long userId, Long serverId) {
        McpServerPO server = serverId == null ? null : serverMapper.selectOne(Wrappers.<McpServerPO>lambdaQuery()
                .eq(McpServerPO::getId, serverId)
                .eq(McpServerPO::getUserId, userId)
                .last("LIMIT 1"));
        if (server == null) {
            // 别人的 server 与不存在的 server 必须看起来一样
            throw new BusinessException(ErrorCode.MCP_SERVER_NOT_FOUND);
        }
        return server;
    }

    private String uniqueSlug(String base) {
        String candidate = base;
        int suffix = 2;
        while (serverMapper.selectCount(Wrappers.<McpServerPO>lambdaQuery()
                .eq(McpServerPO::getSlug, candidate)) > 0) {
            candidate = base + "-" + suffix++;
            if (suffix > 100) {
                candidate = base + "-" + Integer.toHexString(Objects.hash(base, System.nanoTime()));
                break;
            }
        }
        return candidate;
    }

    private static String slugify(String raw) {
        String slug = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        return slug.isBlank() ? "mcp" : slug;
    }

    private static String sanitize(String toolName) {
        String name = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "_");
        return name.isBlank() ? "tool" : name;
    }

    private static String normalizeTransport(String raw) {
        if (raw == null || raw.isBlank()) {
            return "http";
        }
        String transport = raw.trim().toLowerCase(Locale.ROOT);
        return switch (transport) {
            case "http", "streamable-http", "streamable_http" -> "http";
            case "stdio" -> "stdio";
            default -> throw new BusinessException(ErrorCode.MCP_TRANSPORT_UNSUPPORTED,
                    "不支持的传输方式：%s（可选 http / stdio）".formatted(raw));
        };
    }

    private String encrypt(String authHeader) {
        String plain = blankToNull(authHeader);
        return plain == null ? null : cipher.encrypt(plain);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String clip(String message) {
        return message == null || message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max((System.nanoTime() - startedNanos) / 1_000_000, 0);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private McpServerResponse toResponse(McpServerPO server) {
        return new McpServerResponse(
                server.getId(),
                server.getName(),
                server.getSlug(),
                server.getTransport(),
                server.getUrl(),
                server.getCommand(),
                server.getAuthHeaderCipher() != null && !server.getAuthHeaderCipher().isBlank(),
                server.getEnabled() == null || server.getEnabled() != 0,
                server.getLastOkAt(),
                server.getLastError(),
                readTools(server.getToolsJson()),
                server.allowsStdio(),
                server.getLastCallAt(),
                server.getLastCallTool(),
                server.getLastCallOk() == null ? null : server.getLastCallOk() == 1,
                server.getLastCallError());
    }

    private List<McpToolInfo> readTools(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = json.readTree(raw);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<McpToolInfo> tools = new ArrayList<>();
            for (JsonNode item : node) {
                JsonNode name = item.get("name");
                if (name == null || name.isNull() || name.asString().isBlank()) {
                    continue;
                }
                JsonNode description = item.get("description");
                JsonNode schema = item.get("inputSchema");
                tools.add(new McpToolInfo(name.asString(),
                        description == null || description.isNull() ? null : description.asString(),
                        schema == null || schema.isNull() ? null : schema.asString()));
            }
            return List.copyOf(tools);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }
}
