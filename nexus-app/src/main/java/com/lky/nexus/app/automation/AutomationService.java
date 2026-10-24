package com.lky.nexus.app.automation;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lky.nexus.app.agent.AgentService;
import com.lky.nexus.app.automation.mapper.AutomationMapper;
import com.lky.nexus.app.automation.mapper.AutomationRunMapper;
import com.lky.nexus.app.automation.model.AutomationSchedule;
import com.lky.nexus.app.automation.model.dto.AutomationResponse;
import com.lky.nexus.app.automation.model.dto.AutomationRunResponse;
import com.lky.nexus.app.automation.model.dto.CreateAutomationRequest;
import com.lky.nexus.app.automation.model.dto.UpdateAutomationRequest;
import com.lky.nexus.app.automation.model.entity.AutomationPO;
import com.lky.nexus.app.automation.model.entity.AutomationRunPO;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.PageQuery;
import com.lky.nexus.common.api.PageResult;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 自动化任务的资源服务：增删改查 + 排期计算 + 运行记录读取。
 *
 * <p>边界与 {@code AgentService} 一致：Controller 只取 userId 与参数，
 * 业务规则（归属、排期合法性、知识库归属、下次触发时刻）全部在这里。
 *
 * <p>排期写入的两个时刻要分清：
 * <ul>
 *   <li><b>保存时</b>按 {@code now} 算下一次——不回填停机期间欠下的触发
 *       （不然重启后会把积压的几天任务连着跑一遍，把配额和模型都打满）；</li>
 *   <li><b>到点执行后</b>由调度器按"被抢占的那个到期时刻"算下一跳，保证不漂移。</li>
 * </ul>
 */
@Service
public class AutomationService {

    /** 单次查询运行记录的上限：再大就该做分页接口了，而不是让一次请求把表捞空。 */
    private static final int MAX_RUN_HISTORY = 100;

    private final AutomationMapper automationMapper;
    private final AutomationRunMapper runMapper;
    private final AgentService agentService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final AutomationRunner runner;

    public AutomationService(AutomationMapper automationMapper,
                             AutomationRunMapper runMapper,
                             AgentService agentService,
                             KnowledgeBaseMapper knowledgeBaseMapper,
                             AutomationRunner runner) {
        this.automationMapper = automationMapper;
        this.runMapper = runMapper;
        this.agentService = agentService;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.runner = runner;
    }

    /**
     * 分页列出当前用户的自动化任务（按更新时间倒序）。
     *
     * @param userId   当前用户
     * @param pageNum  页码（会被归一化到 ≥1）
     * @param pageSize 每页条数（会被归一化到合法范围）
     * @return 分页结果
     */
    public PageResult<AutomationResponse> list(Long userId, int pageNum, int pageSize) {
        int safePageNum = PageQuery.normalizePageNum(pageNum);
        int safePageSize = PageQuery.normalizePageSize(pageSize);
        Page<AutomationPO> page = new Page<>(safePageNum, safePageSize);
        IPage<AutomationPO> result = automationMapper.selectPage(page, Wrappers.<AutomationPO>lambdaQuery()
                .eq(AutomationPO::getUserId, userId)
                .orderByDesc(AutomationPO::getUpdatedAt));
        return PageResult.of(toResponses(userId, result.getRecords()), result.getTotal(), safePageNum, safePageSize);
    }

    /**
     * 新建任务：校验 Agent 与知识库归属、解析排期、算出首次触发时刻。
     *
     * @param userId  当前用户
     * @param request 创建参数
     * @return 新任务的视图
     */
    public AutomationResponse create(Long userId, CreateAutomationRequest request) {
        AutomationSchedule schedule = AutomationSchedule.parse(request.schedule());
        String cron = resolveCron(schedule, request.cron());
        List<Long> kbIds = requireOwnedKbs(userId, request.kbIds());
        Long agentId = request.agentId();
        if (agentId != null) {
            // 建的时候就按"能被会话使用"的标准校验：DRAFT / DISABLED 的 Agent 到点跑必然失败，
            // 让用户在保存时就撞上 5008，比三天后收到一条失败记录好。
            agentService.requireDefinition(userId, agentId);
        }
        boolean enabled = request.enabled() == null || request.enabled();
        LocalDateTime now = LocalDateTime.now();

        AutomationPO po = new AutomationPO();
        po.setUserId(userId);
        po.setName(request.name().trim());
        po.setPrompt(request.prompt());
        po.setAgentId(agentId);
        po.setKbIds(AutomationKbIds.join(kbIds));
        po.setScheduleType(schedule.name());
        po.setCron(cron);
        po.setEnabled(enabled ? 1 : 0);
        po.setRetryCount(0);
        po.setNextRunAt(enabled ? schedule.nextRunAfter(cron, now) : null);
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        automationMapper.insert(po);
        return toResponses(userId, List.of(po)).get(0);
    }

    /**
     * 局部更新（null = 不改；显式解绑见 {@link UpdateAutomationRequest}）。
     *
     * <p>改了频率 / cron / 启停，或从暂停恢复时重算 next_run_at；只改名字不碰排期。
     *
     * @param userId      当前用户
     * @param automationId 任务 ID
     * @param request     更新参数
     * @return 更新后的视图
     */
    public AutomationResponse update(Long userId, Long automationId, UpdateAutomationRequest request) {
        AutomationPO current = requireOwned(userId, automationId);
        LocalDateTime now = LocalDateTime.now();

        String name = request.name() == null ? current.getName() : requireText(request.name(), "任务名不能为空");
        String prompt = request.prompt() == null ? current.getPrompt() : requireText(request.prompt(), "prompt 不能为空");

        Long agentId = current.getAgentId();
        if (Boolean.TRUE.equals(request.useDefaultAgent())) {
            agentId = null;
        } else if (request.agentId() != null) {
            agentService.requireDefinition(userId, request.agentId());
            agentId = request.agentId();
        }

        String kbIds = request.kbIds() == null
                ? current.getKbIds()
                : AutomationKbIds.join(requireOwnedKbs(userId, request.kbIds()));

        AutomationSchedule schedule = request.schedule() == null
                ? AutomationSchedule.parse(current.getScheduleType())
                : AutomationSchedule.parse(request.schedule());
        String rawCron = request.cron() != null ? request.cron() : current.getCron();
        String cron = resolveCron(schedule, rawCron);

        boolean enabled = request.enabled() != null
                ? request.enabled()
                : current.getEnabled() != null && current.getEnabled() == 1;
        boolean reschedule = enabled && (request.schedule() != null
                || request.cron() != null
                || request.enabled() != null
                || current.getNextRunAt() == null);
        LocalDateTime nextRunAt = enabled
                ? (reschedule ? schedule.nextRunAfter(cron, now) : current.getNextRunAt())
                : null;

        automationMapper.updateDefinition(
                current.getId(), name, prompt, agentId, kbIds,
                schedule.name(), cron, enabled ? 1 : 0, nextRunAt, now);

        AutomationPO updated = automationMapper.selectById(current.getId());
        return toResponses(userId, List.of(updated == null ? current : updated)).get(0);
    }

    /**
     * 删除任务（逻辑删除）。
     *
     * <p>运行历史<b>不</b>跟着删：{@code automation_run} 是审计事实，
     * 任务没了也要能回答"上周那次为什么失败"。
     *
     * @param userId       当前用户
     * @param automationId 任务 ID
     */
    public void delete(Long userId, Long automationId) {
        requireOwned(userId, automationId);
        automationMapper.deleteById(automationId);
    }

    /**
     * 立即跑一次（同步执行，返回这次运行的记录）。
     *
     * <p>同步而不是丢后台：接口返回的就是"这次跑成什么样"，前端不用再轮询；
     * 失败也以 200 + {@code status=FAILED} 返回，错误原因在记录里。
     *
     * @param userId       当前用户
     * @param automationId 任务 ID
     * @return 运行记录
     */
    public AutomationRunResponse runNow(Long userId, Long automationId) {
        AutomationPO automation = requireOwned(userId, automationId);
        return runner.run(userId, automation, "MANUAL");
    }

    /**
     * 最近 N 条运行记录（按时间倒序）。
     *
     * @param userId       当前用户
     * @param automationId 任务 ID
     * @param limit        条数（1..100）
     * @return 运行记录列表
     */
    public List<AutomationRunResponse> recentRuns(Long userId, Long automationId, int limit) {
        requireOwned(userId, automationId);
        int safeLimit = Math.min(Math.max(limit, 1), MAX_RUN_HISTORY);
        List<AutomationRunPO> runs = runMapper.selectList(Wrappers.<AutomationRunPO>lambdaQuery()
                .eq(AutomationRunPO::getAutomationId, automationId)
                .eq(AutomationRunPO::getUserId, userId)
                .orderByDesc(AutomationRunPO::getCreatedAt)
                .last("LIMIT " + safeLimit));
        List<AutomationRunResponse> responses = new ArrayList<>(runs.size());
        for (AutomationRunPO run : runs) {
            responses.add(toRunResponse(run));
        }
        return responses;
    }

    /**
     * 当前用户拥有的任务（别人的一律 404，不暴露存在性）。
     *
     * @param userId       当前用户
     * @param automationId 任务 ID
     * @return 任务行
     * @throws BusinessException 9001（不存在或不属于当前用户）
     */
    public AutomationPO requireOwned(Long userId, Long automationId) {
        if (automationId == null) {
            throw new BusinessException(ErrorCode.AUTOMATION_NOT_FOUND);
        }
        AutomationPO po = automationMapper.selectOne(Wrappers.<AutomationPO>lambdaQuery()
                .eq(AutomationPO::getId, automationId)
                .eq(AutomationPO::getUserId, userId));
        if (po == null) {
            throw new BusinessException(ErrorCode.AUTOMATION_NOT_FOUND);
        }
        return po;
    }

    /**
     * 按任务的排期算出下一个触发时刻（调度器抢占时用）。
     *
     * @param po   任务行
     * @param from 基准时刻
     * @return 下一次触发时刻
     * @throws BusinessException 9003（库里的 cron 被改坏了）
     */
    public LocalDateTime nextRunAfter(AutomationPO po, LocalDateTime from) {
        return AutomationSchedule.parse(po.getScheduleType()).nextRunAfter(po.getCron(), from);
    }

    // ------------------------------------------------------------------

    /** 固定频率一律用预设 cron 覆盖；CUSTOM 用用户填的，并在这里校验。 */
    private String resolveCron(AutomationSchedule schedule, String rawCron) {
        if (schedule.presetCron() != null) {
            return schedule.presetCron();
        }
        AutomationSchedule.parseOrThrow(rawCron);
        return rawCron.trim();
    }

    /** 校验知识库归属，返回去重后的 ID（空列表 = 不覆盖 Agent 绑定）。 */
    private List<Long> requireOwnedKbs(Long userId, List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Long id : kbIds) {
            if (id != null && id > 0) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        Long owned = knowledgeBaseMapper.selectCount(Wrappers.<KnowledgeBasePO>lambdaQuery()
                .in(KnowledgeBasePO::getId, ids)
                .eq(KnowledgeBasePO::getUserId, userId));
        if (owned == null || owned != ids.size()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        return List.copyOf(ids);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, message);
        }
        return value.trim();
    }

    private List<AutomationResponse> toResponses(Long userId, List<AutomationPO> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Set<Long> agentIds = new LinkedHashSet<>();
        Set<Long> kbIds = new LinkedHashSet<>();
        for (AutomationPO row : rows) {
            if (row.getAgentId() != null) {
                agentIds.add(row.getAgentId());
            }
            kbIds.addAll(AutomationKbIds.parse(row.getKbIds()));
        }
        Map<Long, String> agentNames = new HashMap<>();
        for (Long agentId : agentIds) {
            try {
                agentNames.put(agentId, agentService.requireOwned(userId, agentId).getName());
            } catch (BusinessException ignored) {
                // Agent 已被删除：名称留空，前端显示 ID 由它自己决定
            }
        }
        Map<Long, String> kbNames = new HashMap<>();
        if (!kbIds.isEmpty()) {
            for (KnowledgeBasePO kb : knowledgeBaseMapper.selectList(Wrappers.<KnowledgeBasePO>lambdaQuery()
                    .in(KnowledgeBasePO::getId, kbIds)
                    .eq(KnowledgeBasePO::getUserId, userId))) {
                kbNames.put(kb.getId(), kb.getName());
            }
        }

        List<AutomationResponse> responses = new ArrayList<>(rows.size());
        for (AutomationPO row : rows) {
            List<Long> rowKbIds = AutomationKbIds.parse(row.getKbIds());
            List<String> rowKbNames = new ArrayList<>(rowKbIds.size());
            for (Long kbId : rowKbIds) {
                String kbName = kbNames.get(kbId);
                if (kbName != null) {
                    rowKbNames.add(kbName);
                }
            }
            responses.add(new AutomationResponse(
                    row.getId(),
                    row.getName(),
                    row.getPrompt(),
                    row.getAgentId(),
                    row.getAgentId() == null ? null : agentNames.get(row.getAgentId()),
                    rowKbIds,
                    rowKbNames,
                    row.getScheduleType() == null ? null : row.getScheduleType().toLowerCase(Locale.ROOT),
                    row.getCron(),
                    row.getEnabled() != null && row.getEnabled() == 1,
                    row.getNextRunAt(),
                    row.getLastRunAt(),
                    row.getLastStatus(),
                    row.getCreatedAt(),
                    row.getUpdatedAt()));
        }
        return responses;
    }

    private AutomationRunResponse toRunResponse(AutomationRunPO run) {
        return new AutomationRunResponse(
                run.getId(),
                run.getAutomationId(),
                run.getTriggerType(),
                run.getStatus(),
                run.getAnswer(),
                run.getErrorCode(),
                run.getErrorMessage(),
                run.getPromptTokens(),
                run.getCompletionTokens(),
                run.getTotalTokens(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getDurationMs());
    }
}
