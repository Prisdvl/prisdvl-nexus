package com.lky.nexus.app.plugin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.app.plugin.mapper.PluginCatalogMapper;
import com.lky.nexus.app.plugin.model.entity.PluginCatalogPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 插件目录（{@code plugin_catalog}）：seed 与读取。
 *
 * <p><b>为什么目录搬到服务端</b>：目录此前活在前端 {@code plugins.ts}。那份清单会随发行版本漂移，
 * 而"市场里有什么"是产品数据、不是界面偏好——它应该能被服务端回答，也应该能一改全改。
 * 前端从此只负责画，不再自带一份"看起来是数据、其实是常量"的东西。
 *
 * <p><b>为什么是 seed 而不是管理后台</b>：现在还没有运营侧写入口，目录先按启动时 seed 落地。
 * 但"读"这一侧已经是服务端的了（{@link PluginService#list} 走这张表），
 * 以后把 seed 换成后台写入，接口和前端一行都不用改。
 *
 * <p><b>seed 只在版本变了才写</b>：启动流程里塞一堆无意义的 UPDATE 是自找麻烦
 * （每台机器起来都改一次 {@code updated_at}，还会把运营侧的改动冲掉）。
 * {@link #SEED_VERSION} 变了才重写展示字段。
 */
@Service
public class PluginCatalogService {

    private static final Logger log = LoggerFactory.getLogger(PluginCatalogService.class);

    /** 目录版本：改了它才会重写库里的展示字段。 */
    public static final String SEED_VERSION = "2026.10.1";

    /**
     * 目录内容（唯一的真相来源）。
     *
     * <p>图标一律留空：前端用首字母占位。这不是偷懒——现在没有真实的图标资源，
     * 塞一个假 URL 只会得到一片裂开的图。
     */
    private static final List<Seed> SEEDS = List.of(
            new Seed("nexus-mcp-bridge", "Nexus MCP Bridge", "Prisdvl", "1.4.2", "automation",
                    "把 Nexus 知识库与工具能力暴露为标准 MCP 服务，供外部 Agent 与 IDE 复用。",
                    new BigDecimal("4.9"), "18.2k", List.of("mcp.tools.list", "knowledge.search", "qa.ask"),
                    List.of("MCP", "Protocol", "Integration"), true, true, "2026-10-12", 10),
            new Seed("web-fetch", "Web Fetch", "Prisdvl", "2.0.1", "search",
                    "抓取网页正文与元信息，支持 Markdown 化、链接提取与引用来源。",
                    new BigDecimal("4.8"), "12.7k", List.of("web.fetch", "web.extract_links"),
                    List.of("Web", "Search"), true, true, "2026-10-08", 20),
            new Seed("code-review", "Code Review Copilot", "devtools-org", "1.8.0", "productivity",
                    "为代码变更生成结构化审查意见，输出风险分级与修改建议。",
                    new BigDecimal("4.7"), "9.4k", List.of("code.diff", "code.review"),
                    List.of("Code", "Review"), false, false, "2026-09-30", 30),
            new Seed("rag-tuner", "RAG Tuner", "agent-lab", "0.9.3", "knowledge",
                    "分析检索命中质量，给出 chunk 切分、嵌入与混合检索参数建议。",
                    new BigDecimal("4.5"), "6.1k", List.of("rag.analyze", "rag.suggest"),
                    List.of("RAG", "Knowledge"), false, false, "2026-09-26", 40),
            new Seed("schedule-watch", "Schedule Watch", "automation-hub", "1.2.4", "automation",
                    "定时检查任务、会话与知识库状态，变化时向工作台推送提醒。",
                    new BigDecimal("4.4"), "4.8k", List.of("schedule.monitor", "schedule.notify"),
                    List.of("Automation", "Reminder"), false, false, "2026-09-18", 50),
            new Seed("spreadsheet-kit", "Spreadsheet Kit", "data-works", "2.3.1", "data",
                    "读取与生成 xlsx / csv，支持公式、透视表与图表数据导出。",
                    new BigDecimal("4.3"), "3.2k", List.of("sheet.read", "sheet.write", "sheet.chart"),
                    List.of("Spreadsheet", "Data"), false, false, "2026-09-10", 60),
            new Seed("agent-tester-pro", "Agent Tester Pro", "agent-lab", "1.1.0", "agent",
                    "为 Agent 提供回归用例、上下文隔离与失败重放测试台。",
                    new BigDecimal("4.6"), "2.9k", List.of("agent.test", "agent.replay"),
                    List.of("Agent", "Testing"), false, false, "2026-09-02", 70));

    private final PluginCatalogMapper catalogMapper;
    private final JsonMapper json;

    public PluginCatalogService(PluginCatalogMapper catalogMapper, JsonMapper json) {
        this.catalogMapper = catalogMapper;
        this.json = json;
    }

    /** 目录里的插件（只含在架的），按 sort_order 排序。 */
    public List<PluginCatalogPO> all() {
        return catalogMapper.selectList(Wrappers.<PluginCatalogPO>lambdaQuery()
                .eq(PluginCatalogPO::getEnabled, 1)
                .orderByAsc(PluginCatalogPO::getSortOrder)
                .orderByAsc(PluginCatalogPO::getCode));
    }

    /** 按 code 找在架插件；找不到返回 {@code null}，由调用方决定报什么错。 */
    public PluginCatalogPO find(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        PluginCatalogPO row = catalogMapper.selectById(code);
        return row == null || row.getEnabled() == null || row.getEnabled() == 0 ? null : row;
    }

    /** 把前端要的展示字段从 JSON 列里读出来（坏数据按空数组处理，不让一行脏数据毁掉整个目录）。 */
    public List<String> readList(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            List<?> parsed = json.readValue(rawJson, List.class);
            return parsed.stream().map(String::valueOf).toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /**
     * 落库当前 {@link #SEED_VERSION} 的目录。
     *
     * <p>三条规则：
     * <ul>
     *   <li>库里没有 → 插入；</li>
     *   <li>库里 seed_version 落后 → 重写展示字段（用户在 {@code user_plugin} 里的开关不受影响）；</li>
     *   <li>seed 里已经不再提供的 code → 置 {@code enabled=0} 下架，而不是 DELETE
     *       （用户那行的配置还挂着，删了目录会让它变成"点不动的幽灵"）。</li>
     * </ul>
     *
     * @return 实际写入的行数（0 表示目录没变，这是绝大多数启动的情况）
     */
    @Transactional
    public int seed() {
        LocalDateTime now = LocalDateTime.now();
        Set<String> codes = SEEDS.stream().map(Seed::code).collect(Collectors.toSet());
        int written = 0;
        for (Seed seed : SEEDS) {
            PluginCatalogPO existing = catalogMapper.selectById(seed.code());
            if (existing != null && SEED_VERSION.equals(existing.getSeedVersion())) {
                continue;
            }
            PluginCatalogPO row = existing != null ? existing : new PluginCatalogPO();
            row.setCode(seed.code());
            row.setName(seed.name());
            row.setAuthor(seed.author());
            row.setVersion(seed.version());
            row.setCategory(seed.category());
            row.setDescription(seed.description());
            row.setIcon(null);
            row.setRating(seed.rating());
            row.setDownloads(seed.downloads());
            row.setToolsJson(json.writeValueAsString(seed.tools()));
            row.setTagsJson(json.writeValueAsString(seed.tags()));
            row.setVerified(seed.verified() ? 1 : 0);
            row.setOfficial(seed.official() ? 1 : 0);
            row.setUpdatedLabel(seed.updated());
            row.setSortOrder(seed.sortOrder());
            row.setEnabled(1);
            row.setSeedVersion(SEED_VERSION);
            row.setUpdatedAt(now);
            if (existing == null) {
                row.setCreatedAt(now);
                catalogMapper.insert(row);
            } else {
                catalogMapper.updateById(row);
            }
            written++;
        }
        // 下架：目录里已经不再提供的 code。
        List<PluginCatalogPO> stale = catalogMapper.selectList(Wrappers.<PluginCatalogPO>lambdaQuery()
                        .eq(PluginCatalogPO::getEnabled, 1))
                .stream()
                .filter(row -> !codes.contains(row.getCode()))
                .toList();
        for (PluginCatalogPO row : stale) {
            row.setEnabled(0);
            row.setUpdatedAt(now);
            catalogMapper.updateById(row);
            written++;
        }
        if (written > 0) {
            log.info("插件目录已更新到 {}：写入 {} 行（下架 {}）", SEED_VERSION, written, stale.size());
        }
        return written;
    }

    /** 目录里的一条（seed 用的内部形状，不对外暴露）。 */
    private record Seed(String code, String name, String author, String version, String category,
                        String description, BigDecimal rating, String downloads, List<String> tools,
                        List<String> tags, boolean verified, boolean official, String updated, int sortOrder) {
    }
}
