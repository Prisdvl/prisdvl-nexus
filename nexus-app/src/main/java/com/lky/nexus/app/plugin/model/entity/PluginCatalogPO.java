package com.lky.nexus.app.plugin.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 插件目录（{@code plugin_catalog}）。
 *
 * <p>这张表是"服务端说了算的目录"，由 {@code PluginCatalogService} 在启动时 seed。
 * 前端不再自带一份清单：两份清单只会漂移，而漂移的那一天，页面上会出现一个点不动的插件。
 *
 * <p>主键是业务 {@code code}（{@link IdType#INPUT}），不是自增 id ——
 * 目录行天然以 code 唯一，多一列自增 id 只会让"按 code 查"多一次无意义的翻译。
 */
@Data
@TableName("plugin_catalog")
public class PluginCatalogPO {

    @TableId(type = IdType.INPUT)
    private String code;
    private String name;
    private String author;
    private String version;
    private String category;
    private String description;
    /** 图标 URL；为空时前端用首字母占位。 */
    private String icon;
    private BigDecimal rating;
    private String downloads;
    private String toolsJson;
    private String tagsJson;
    private Integer verified;
    private Integer official;
    private String updatedLabel;
    private Integer sortOrder;
    /** 0 = 已从目录下架（seed 发现这个 code 不再提供）。下架而不是删行：用户配置还挂在那边。 */
    private Integer enabled;
    private String seedVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
