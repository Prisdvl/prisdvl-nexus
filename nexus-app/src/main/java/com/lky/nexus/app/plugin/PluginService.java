package com.lky.nexus.app.plugin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.app.plugin.mapper.UserPluginMapper;
import com.lky.nexus.app.plugin.model.dto.PluginResponse;
import com.lky.nexus.app.plugin.model.dto.UpdatePluginRequest;
import com.lky.nexus.app.plugin.model.entity.PluginCatalogPO;
import com.lky.nexus.app.plugin.model.entity.UserPluginPO;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 用户级插件开关与配置（{@code user_plugin}）+ 目录（{@code plugin_catalog}）的合并视图。
 *
 * <p><b>V34 起目录在服务端</b>：{@link PluginCatalogService#all()} 提供"市场里有什么"，
 * 这里只负责把当前用户的覆盖行贴上去。前端不再自带清单，所以
 * {@code GET /api/plugins} 必须返回画页面所需的全部字段（见 {@link PluginResponse}）。
 *
 * <p><b>没有行 = 未安装</b>：这条语义在 V34 改了。此前目录在前端，服务端只能"默认启用"，
 * 否则新用户的列表是空的、前端无法区分"没装"和"没查到"。目录搬到服务端后，
 * 默认全开会让新用户一进市场就满屏已安装——那不是真相，也会掩盖"这台机器到底装了什么"。
 *
 * <p>{@code PUT} 仍是一次 upsert：第一次写就是安装，{@code {"enabled":false}} 就是卸载，
 * 而且配置不丢（卸载只是把 enabled 写 0，行还在）。
 */
@Service
public class PluginService {

    /** 与前端 {@code plugin_code} 同一形状：小写、可带点/横线/下划线：够长也够严。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

    /**
     * 插件配置的字符上限（16 KB）。
     *
     * <p>这一列是给"端点、超时、开关"这类键值用的。没有上限时，客户端能把整份文件塞进来：
     * 没人读它，却会随每次 {@code GET /api/plugins} 一起被序列化出去。
     */
    private static final int CONFIG_LIMIT_CHARS = 16 * 1024;

    private final UserPluginMapper pluginMapper;
    private final PluginCatalogService catalogService;
    private final JsonMapper json;

    public PluginService(UserPluginMapper pluginMapper, PluginCatalogService catalogService, JsonMapper json) {
        this.pluginMapper = pluginMapper;
        this.catalogService = catalogService;
        this.json = json;
    }

    /** 市场 = 目录里的全部在架插件 + 当前用户的安装状态。 */
    public List<PluginResponse> list(Long userId) {
        Map<String, UserPluginPO> rows = pluginMapper.selectList(Wrappers.<UserPluginPO>lambdaQuery()
                        .eq(UserPluginPO::getUserId, userId))
                .stream()
                .collect(Collectors.toMap(UserPluginPO::getPluginCode, Function.identity(), (a, b) -> a));
        return catalogService.all().stream()
                .map(catalog -> toResponse(catalog, rows.get(catalog.getCode())))
                .toList();
    }

    /** 单个插件：不在目录里 → 9301（404），而不是拿默认值糊弄过去。 */
    public PluginResponse get(Long userId, String code) {
        String normalized = requireCode(code);
        PluginCatalogPO catalog = requireCatalog(normalized);
        return toResponse(catalog, find(userId, normalized));
    }

    /**
     * 装 / 卸 / 改配置。
     *
     * <p>两种"没给"必须区分开，这是这个接口唯一的坑：
     * {@code config} 为 {@code null} 表示<b>别动配置</b>，传空对象表示<b>清空配置</b>。
     * 如果两者都按"不改"处理，用户就永远没法把配置清回默认。
     *
     * <p>不在目录里的 code 直接 404：目录是权威，不能往库里塞一个市场里不存在的插件。
     *
     * <p>配置有 16 KB 上限：这一列是给"端点、超时、开关"这类键值用的，不是让客户端
     * 把整个文件塞进来的地方——塞进来的东西没人读，却会随每次列表接口一起被序列化。
     */
    @Transactional
    public PluginResponse update(Long userId, String code, UpdatePluginRequest request) {
        String normalized = requireCode(code);
        PluginCatalogPO catalog = requireCatalog(normalized);
        String configJson = writeConfig(request.config());
        if (configJson != null && configJson.length() > CONFIG_LIMIT_CHARS) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "插件配置不能超过 16 KB（当前 %d 字符）".formatted(configJson.length()));
        }
        UserPluginPO row = find(userId, normalized);
        LocalDateTime now = LocalDateTime.now();
        if (row == null) {
            row = new UserPluginPO();
            row.setUserId(userId);
            row.setPluginCode(normalized);
            row.setEnabled(request.enabled() == null || request.enabled() ? 1 : 0);
            row.setConfigJson(configJson);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            pluginMapper.insert(row);
            return toResponse(catalog, row);
        }
        if (request.enabled() != null) {
            row.setEnabled(request.enabled() ? 1 : 0);
        }
        if (request.config() != null) {
            row.setConfigJson(configJson);
        }
        row.setUpdatedAt(now);
        pluginMapper.updateById(row);
        return toResponse(catalog, row);
    }

    private PluginCatalogPO requireCatalog(String code) {
        PluginCatalogPO catalog = catalogService.find(code);
        if (catalog == null) {
            throw new BusinessException(ErrorCode.PLUGIN_NOT_FOUND,
                    "插件不在目录里：%s（目录由服务端维护，不再接收前端自造的 code）".formatted(code));
        }
        return catalog;
    }

    private UserPluginPO find(Long userId, String code) {
        return pluginMapper.selectOne(Wrappers.<UserPluginPO>lambdaQuery()
                .eq(UserPluginPO::getUserId, userId)
                .eq(UserPluginPO::getPluginCode, code)
                .last("LIMIT 1"));
    }

    private String requireCode(String code) {
        String normalized = code == null ? "" : code.trim().toLowerCase();
        if (!CODE_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "插件 code 只能是小写字母/数字/点/横线/下划线，且以字母或数字开头：%s".formatted(code));
        }
        return normalized;
    }

    private String writeConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return null;
        }
        return json.writeValueAsString(config);
    }

    private Map<String, Object> readConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = json.readValue(raw, Object.class);
            if (!(parsed instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, Object> config = new LinkedHashMap<>();
            map.forEach((key, value) -> config.put(String.valueOf(key), value));
            // 用 unmodifiable 而不是 Map.copyOf：配置里允许出现 null 值（"这个键显式没填"）
            return Collections.unmodifiableMap(config);
        } catch (RuntimeException ex) {
            // 配置是本接口唯一的自由字段，坏数据不该让整个列表 500：当作"没配置"更接近真相
            return Map.of();
        }
    }

    /**
     * 目录行 + 用户覆盖行 → 一个响应。
     *
     * <p>{@code row == null} 就是"没装过"：{@code enabled=false}、空配置。
     * 这里刻意不看目录里的 {@code enabled}（那是"在不在架"，不是"装没装"）。
     */
    private PluginResponse toResponse(PluginCatalogPO catalog, UserPluginPO row) {
        boolean installed = row != null && row.getEnabled() != null && row.getEnabled() != 0;
        return new PluginResponse(catalog.getCode(),
                catalog.getName(),
                catalog.getAuthor(),
                catalog.getVersion(),
                catalog.getCategory(),
                catalog.getDescription(),
                catalog.getIcon(),
                catalog.getRating(),
                catalog.getDownloads(),
                catalogService.readList(catalog.getToolsJson()),
                catalogService.readList(catalog.getTagsJson()),
                catalog.getVerified() != null && catalog.getVerified() != 0,
                catalog.getOfficial() != null && catalog.getOfficial() != 0,
                catalog.getUpdatedLabel(),
                installed,
                row == null ? Map.of() : readConfig(row.getConfigJson()));
    }
}
