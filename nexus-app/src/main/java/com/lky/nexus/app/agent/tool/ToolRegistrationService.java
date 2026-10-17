package com.lky.nexus.app.agent.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.ai.agent.ToolDefinition;
import com.lky.nexus.ai.agent.ToolPermission;
import com.lky.nexus.app.agent.mapper.ToolRegistryMapper;
import com.lky.nexus.app.agent.model.entity.ToolRegistryPO;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 工具注册元数据（{@code ai_tool_registry}，P0）。
 *
 * <p>它把"有哪些工具、各自什么风险等级"从"只在 JVM 内存里"变成"数据库里可查、
 * 可被管理员调整"。同步规则是这条链路最容易做错的地方，所以写死在这里：
 * <ul>
 *   <li>行不存在 → 用代码声明的默认权限插入；</li>
 *   <li>行已存在 → 只更新描述性字段（name / description / source / category），
 *       <b>绝不覆盖 permission 与 enabled</b>。</li>
 * </ul>
 * 否则"管理员把某个工具降级为 DANGEROUS"会在下一次重启时被悄悄改回 READ——
 * 那是一个没有任何日志、没有任何报错的安全回退。
 */
@Service
public class ToolRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistrationService.class);

    private final ToolRegistryMapper mapper;
    private final ToolCatalog catalog;

    public ToolRegistrationService(ToolRegistryMapper mapper, ToolCatalog catalog) {
        this.mapper = mapper;
        this.catalog = catalog;
    }

    /**
     * 启动时幂等同步。
     *
     * <p>用 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}：后者可能在
     * Flyway 建表之前执行（Bean 初始化顺序不保证），表现是"启动即报表不存在"。
     * 同步失败只记 ERROR 不阻止启动——工具注册表暂时缺几行时，执行侧会默认拒绝，
     * 这是安全方向上的降级，不该把整个应用拖垮。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void syncFromCatalog() {
        int created = 0;
        int refreshed = 0;
        for (ToolCatalog.Registered tool : catalog.all()) {
            ToolRegistryPO existing = findByCode(tool.code()).orElse(null);
            if (existing == null) {
                mapper.insert(newRow(tool));
                created++;
                continue;
            }
            if (refreshDescriptiveFields(existing, tool)) {
                mapper.updateById(existing);
                refreshed++;
            }
        }
        log.info("工具注册表同步完成：新增 {} 条，刷新描述 {} 条（permission 与 enabled 保留库中原值）",
                created, refreshed);
    }

    public Optional<ToolRegistryPO> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(Wrappers.<ToolRegistryPO>lambdaQuery()
                .eq(ToolRegistryPO::getCode, code.trim())
                .last("LIMIT 1")));
    }

    /** 全部注册项（工具目录接口用它补 permission 展示）。 */
    public List<ToolRegistryPO> all() {
        return mapper.selectList(Wrappers.<ToolRegistryPO>lambdaQuery()
                .orderByAsc(ToolRegistryPO::getCode));
    }

    /**
     * 运行时读取权威权限：<b>未注册、已禁用、权限值不认识，一律拒绝</b>。
     *
     * <p>这就是"未知权限默认拒绝"的落点——不要在这里给任何默认值兜底，
     * 一个不认识的 permission 字符串代表的正是我们不知道这个工具能干什么。
     */
    public ToolPermission requirePermission(String code) {
        ToolRegistryPO row = requireRegistered(code);
        try {
            return ToolPermission.parse(row.getPermission());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.TOOL_NOT_FOUND,
                    "工具 %s 的权限配置无法识别（%s），已按默认拒绝处理".formatted(code, row.getPermission()));
        }
    }

    public ToolRegistryPO requireRegistered(String code) {
        ToolRegistryPO row = findByCode(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND,
                        "工具 %s 未注册，拒绝执行".formatted(code)));
        if (!Boolean.TRUE.equals(row.getEnabled())) {
            throw new BusinessException(ErrorCode.TOOL_NOT_FOUND,
                    "工具 %s 已被禁用，拒绝执行".formatted(code));
        }
        return row;
    }

    private ToolRegistryPO newRow(ToolCatalog.Registered tool) {
        ToolRegistryPO row = new ToolRegistryPO();
        row.setCode(tool.code());
        row.setName(tool.displayName());
        row.setDescription(tool.description());
        row.setSource(sourceOf(tool));
        row.setCategory(tool.category().name());
        row.setPermission(tool.permission().name());
        row.setEnabled(true);
        LocalDateTime now = LocalDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private String sourceOf(ToolCatalog.Registered tool) {
        // 内置 provider 今天只产出 BUILTIN；MCP/插件 provider 落地时这里跟着改
        return "BUILTIN";
    }

    /** 只同步描述性字段；返回是否真的有变化（没变化就不写库，避免刷 updated_at）。 */
    private boolean refreshDescriptiveFields(ToolRegistryPO row, ToolCatalog.Registered tool) {
        boolean changed = false;
        if (!Objects.equals(row.getName(), tool.displayName())) {
            row.setName(tool.displayName());
            changed = true;
        }
        if (!Objects.equals(row.getDescription(), tool.description())) {
            row.setDescription(tool.description());
            changed = true;
        }
        if (!Objects.equals(row.getCategory(), tool.category().name())) {
            row.setCategory(tool.category().name());
            changed = true;
        }
        if (changed) {
            row.setUpdatedAt(LocalDateTime.now());
        }
        return changed;
    }
}
