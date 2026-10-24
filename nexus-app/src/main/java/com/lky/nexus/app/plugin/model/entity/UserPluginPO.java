package com.lky.nexus.app.plugin.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 插件启停与配置（{@code user_plugin}，V32）。
 *
 * <p><b>没有行 = 用目录默认值</b>（见 {@code PluginService#get}）：这张表存的是
 * <b>用户覆盖</b>，不是插件定义。和 {@code tool} 表同一套语义，两处不要各搞一套。
 */
@Data
@TableName("user_plugin")
public class UserPluginPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 目录里的插件 id（{@code PLUGIN_CATALOG} 的 id，如 {@code web-fetch}）。 */
    private String pluginCode;

    /** 1 启用 / 0 停用。 */
    private Integer enabled;

    /** 插件配置（JSON 对象），null = 用默认值。 */
    private String configJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
