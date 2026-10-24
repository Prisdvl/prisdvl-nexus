package com.lky.nexus.app.plugin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.app.plugin.mapper.UserPluginMapper;
import com.lky.nexus.app.plugin.model.dto.PluginResponse;
import com.lky.nexus.app.plugin.model.dto.UpdatePluginRequest;
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
import java.util.regex.Pattern;

/**
 * 用户级插件开关与配置（{@code user_plugin}）。
 *
 * <p><b>为什么服务端不存插件目录</b>：目录（名字、图标、分类、描述）是纯展示数据，
 * 活在前端 {@code plugins.ts} 里；服务端只需要记住"这个用户对这个 code 做了什么"。
 * 把目录也抄一份到库里，只会得到两份互相漂移的清单——而漂移的那一天，
 * 页面上会出现一个点不动的插件。
 *
 * <p>由此带来一条规则：<b>没有行 = 用默认值</b>（启用、空配置）。所以新用户
 * {@code GET /api/plugins/xxx} 不会 404，而是拿到默认值——这与"默认全开"的产品
 * 语义一致，也避免了"先去创建一遍才能查"这种无意义的一次往返。
 */
@Service
public class PluginService {

    /** 与前端 {@code plugin_code} 同一形状：小写、可带点/横线/下划线：够长也够严。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

    private final UserPluginMapper pluginMapper;
    private final JsonMapper json;

    public PluginService(UserPluginMapper pluginMapper, JsonMapper json) {
        this.pluginMapper = pluginMapper;
        this.json = json;
    }

    public List<PluginResponse> list(Long userId) {
        return pluginMapper.selectList(Wrappers.<UserPluginPO>lambdaQuery()
                        .eq(UserPluginPO::getUserId, userId)
                        .orderByAsc(UserPluginPO::getPluginCode))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public PluginResponse get(Long userId, String code) {
        String normalized = requireCode(code);
        UserPluginPO row = find(userId, normalized);
        return row == null ? new PluginResponse(normalized, true, Map.of()) : toResponse(row);
    }

    /**
     * 启停 / 改配置。
     *
     * <p>两种"没给"必须区分开，这是这个接口唯一的坑：
     * {@code config} 为 {@code null} 表示<b>别动配置</b>，传空对象表示<b>清空配置</b>。
     * 如果两者都按"不改"处理，用户就永远没法把配置清回默认。
     */
    @Transactional
    public PluginResponse update(Long userId, String code, UpdatePluginRequest request) {
        String normalized = requireCode(code);
        UserPluginPO row = find(userId, normalized);
        LocalDateTime now = LocalDateTime.now();
        if (row == null) {
            row = new UserPluginPO();
            row.setUserId(userId);
            row.setPluginCode(normalized);
            row.setEnabled(request.enabled() == null || request.enabled() ? 1 : 0);
            row.setConfigJson(writeConfig(request.config()));
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            pluginMapper.insert(row);
            return toResponse(row);
        }
        if (request.enabled() != null) {
            row.setEnabled(request.enabled() ? 1 : 0);
        }
        if (request.config() != null) {
            row.setConfigJson(writeConfig(request.config()));
        }
        row.setUpdatedAt(now);
        pluginMapper.updateById(row);
        return toResponse(row);
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

    private PluginResponse toResponse(UserPluginPO row) {
        return new PluginResponse(row.getPluginCode(),
                row.getEnabled() == null || row.getEnabled() != 0,
                readConfig(row.getConfigJson()));
    }
}
