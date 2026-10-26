package com.lky.nexus.app.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时把插件目录 seed 进 {@code plugin_catalog}。
 *
 * <p>用 {@link ApplicationRunner} 而不是 {@code @PostConstruct}：这里要写库，
 * 必须等 Flyway 建好表以后。也不放在迁移脚本里——目录内容会随版本变化，
 * 塞进 SQL 就意味着"改一个名称要新写一个迁移"。
 *
 * <p>失败不阻断启动：目录写不进去时页面会看到一个空市场，但服务其余部分照常可用；
 * 为了一个展示用的目录让整个应用起不来，是更糟的取舍。
 */
@Component
public class PluginCatalogSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PluginCatalogSeeder.class);

    private final PluginCatalogService catalogService;

    public PluginCatalogSeeder(PluginCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            catalogService.seed();
        } catch (RuntimeException ex) {
            log.warn("插件目录 seed 失败，市场将暂时为空：{}", ex.getMessage());
        }
    }
}
