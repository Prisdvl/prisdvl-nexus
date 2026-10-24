package com.lky.nexus.app.automation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.agent.AgentAnswer;
import com.lky.nexus.app.agent.AgentRuntime;
import com.lky.nexus.app.automation.mapper.AutomationMapper;
import com.lky.nexus.app.automation.mapper.AutomationRunMapper;
import com.lky.nexus.app.automation.model.entity.AutomationPO;
import com.lky.nexus.app.automation.model.entity.AutomationRunPO;
import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 自动化任务的验收测试（迁移 V30）：CRUD、立即跑、到点触发、失败重试。
 *
 * <p>模型链路用 {@link MockitoBean} 顶掉 {@link AgentRuntime}：
 * 被测的是"任务怎么排期、怎么落运行记录"，不是模型回什么。
 * 这样整个测试不需要网络，也不需要任何真实 Key。
 *
 * <p>调度线程在 test profile 里关着（{@code nexus.automation.scheduler-enabled: false}），
 * 到点触发由用例显式调 {@link AutomationScheduler#sweepOnce()}——
 * 后台线程在断言中途插一脚会造出"偶发多一条运行记录"这种查不出来的失败。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutomationFlowTest {

    private static final String PASSWORD = "Passw0rd!23";
    private static final String AUTH = HttpHeaders.AUTHORIZATION;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AutomationMapper automationMapper;

    @Autowired
    private AutomationRunMapper runMapper;

    @Autowired
    private AutomationScheduler scheduler;

    @Autowired
    private AutomationService automationService;

    @MockitoBean
    private AgentRuntime agentRuntime;

    // ------------------------------------------------------------------ CRUD

    @Test
    @DisplayName("CRUD：建（含知识库绑定）→ 列表 → 改名 → 停用/启用 → 删除；越权 404、非法排期 400")
    void crud() throws Exception {
        String token = registerAndLogin("auto-crud");
        String otherToken = registerAndLogin("auto-crud-other");
        Long kbId = createKb(token, "自动化知识库");
        Long otherKbId = createKb(otherToken, "别人的知识库");

        // 建：daily 落成真实生效的 cron，并算好 nextRunAt
        String createBody = mockMvc.perform(post("/api/automations")
                        .header(AUTH, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"每日知识库简报","prompt":"把今天新增的笔记总结成三条","schedule":"daily","kbIds":[%d],"enabled":true}
                                """.formatted(kbId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.name").value("每日知识库简报"))
                .andExpect(jsonPath("$.data.schedule").value("daily"))
                .andExpect(jsonPath("$.data.cron").value("0 9 * * *"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.nextRunAt").isNotEmpty())
                .andExpect(jsonPath("$.data.kbIds[0]").value(String.valueOf(kbId)))
                .andExpect(jsonPath("$.data.kbNames[0]").value("自动化知识库"))
                .andReturn().getResponse().getContentAsString();
        Long id = Long.valueOf(JsonPath.read(createBody, "$.data.id").toString());

        // 列表：分页信封齐全（Long 一律字符串，见 docs/api.md §1.6）
        mockMvc.perform(get("/api/automations").header(AUTH, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(String.valueOf(id)));

        // 改名：只传 name，排期不动
        String cronBefore = automationMapper.selectById(id).getCron();
        mockMvc.perform(put("/api/automations/" + id)
                        .header(AUTH, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"每日简报（改名）"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("每日简报（改名）"))
                .andExpect(jsonPath("$.data.cron").value(cronBefore));

        // 停用：不再排期（next_run_at 清空，调度器扫不到）
        mockMvc.perform(put("/api/automations/" + id)
                        .header(AUTH, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
        assertThat(automationMapper.selectById(id).getNextRunAt()).isNull();

        // 重新启用：排期自动补回来，不用用户再填一次时间
        mockMvc.perform(put("/api/automations/" + id)
                        .header(AUTH, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.nextRunAt").isNotEmpty());

        // 越权：别人的任务一律 404（不暴露存在性），与知识库/工作流同一套做法
        mockMvc.perform(put("/api/automations/" + id)
                        .header(AUTH, "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"偷改"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTOMATION_NOT_FOUND.getCode()));

        // 非法排期：不认识的 schedule、custom 的 cron 解析不出来 → 400 / 9003
        mockMvc.perform(post("/api/automations")
                        .header(AUTH, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"错误的频率","prompt":"x","schedule":"yearly"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTOMATION_SCHEDULE_INVALID.getCode()));

        mockMvc.perform(post("/api/automations")
                        .header(AUTH, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"错误的 cron","prompt":"x","schedule":"custom","cron":"不是 cron"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTOMATION_SCHEDULE_INVALID.getCode()));

        // 绑了别人的知识库：写入侧也按"这个资源不存在"处理（3001）
        mockMvc.perform(post("/api/automations")
                        .header(AUTH, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"越权绑定","prompt":"x","schedule":"hourly","kbIds":[%d]}
                                """.formatted(otherKbId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode()));

        // 删：任务消失，但运行历史是审计事实，不该被连带删掉
        mockMvc.perform(delete("/api/automations/" + id).header(AUTH, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/automations").header(AUTH, "Bearer " + token))
                .andExpect(jsonPath("$.data.total").value(0));
        assertThat(automationMapper.selectById(id)).isNull();
    }

    // --------------------------------------------------------------- 立即跑

    @Test
    @DisplayName("立即跑：同步返回这次结果，运行记录标 MANUAL，任务的 lastStatus 跟着变")
    void runNowSucceeds() throws Exception {
        when(agentRuntime.ask(anyLong(), any(AgentDefinition.class), anyString()))
                .thenReturn(new AgentAnswer("已完成：今天有 3 条新笔记", List.of(), false));

        String token = registerAndLogin("auto-run-now");
        Long id = createAutomation(token, "立即跑成功", "hourly");

        String runBody = mockMvc.perform(post("/api/automations/" + id + "/run")
                        .header(AUTH, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                // 跑成功也是 200，返回体里才是业务结论
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.triggerType").value("MANUAL"))
                .andExpect(jsonPath("$.data.answer").value("已完成：今天有 3 条新笔记"))
                // Long 一律字符串（docs/api.md §1.6）：耗时也是 Long
                .andExpect(jsonPath("$.data.durationMs").isString())
                .andExpect(jsonPath("$.data.durationMs").isNotEmpty())
                .andExpect(jsonPath("$.data.errorMessage").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(runBody, "$.data.id")).isNotBlank();

        // 运行记录可查（最近 N 条）
        mockMvc.perform(get("/api/automations/" + id + "/runs").header(AUTH, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].triggerType").value("MANUAL"));

        // 任务行上的"最近一次"跟着更新（列表页直接展示，不用再查 runs）
        AutomationPO po = automationMapper.selectById(id);
        assertThat(po.getLastStatus()).isEqualTo("SUCCEEDED");
        assertThat(po.getLastRunAt()).isNotNull();
        // 手动跑不改排期
        assertThat(po.getNextRunAt()).isNotNull();
    }

    @Test
    @DisplayName("立即跑失败：error 落库（4002），手动触发不排重试")
    void runNowFailureLandsError() throws Exception {
        when(agentRuntime.ask(anyLong(), any(AgentDefinition.class), anyString()))
                .thenThrow(new BusinessException(ErrorCode.MODEL_TIMEOUT));

        String token = registerAndLogin("auto-run-fail");
        Long id = createAutomation(token, "立即跑失败", "hourly");
        LocalDateTime nextBefore = automationMapper.selectById(id).getNextRunAt();

        mockMvc.perform(post("/api/automations/" + id + "/run")
                        .header(AUTH, "Bearer " + token))
                // 任务执行失败不是 HTTP 错误：请求本身没问题，失败是这次运行的结果
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.errorCode").value(ErrorCode.MODEL_TIMEOUT.getCode()))
                .andExpect(jsonPath("$.data.errorMessage").isNotEmpty());

        AutomationPO po = automationMapper.selectById(id);
        assertThat(po.getLastStatus()).isEqualTo("FAILED");
        // 手动触发不重试：用户就在屏幕前，重试只会让"立即运行"变得不可预期
        assertThat(po.getRetryCount()).isZero();
        assertThat(po.getNextRunAt()).isEqualTo(nextBefore);
    }

    // ------------------------------------------------------------- 到点触发

    @Test
    @DisplayName("到点触发：CAS 抢占后跑一次，next_run_at 推下一跳，同一轮不会重复跑")
    void scheduledTriggerFiresOnce() throws Exception {
        when(agentRuntime.ask(anyLong(), any(AgentDefinition.class), anyString()))
                .thenReturn(new AgentAnswer("定时任务完成", List.of(), false));

        String token = registerAndLogin("auto-sched");
        Long id = createAutomation(token, "到点任务", "hourly");

        // 把 next_run_at 拨到过去，模拟"到点了"（真实系统里这是时间自己走过去的）
        LocalDateTime due = LocalDateTime.now().minusMinutes(1).truncatedTo(ChronoUnit.MILLIS);
        automationMapper.updateScheduling(id, due, 0, LocalDateTime.now());

        assertThat(scheduler.sweepOnce()).isEqualTo(1);

        List<AutomationRunPO> runs = runsOf(id);
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(runs.get(0).getTriggerType()).isEqualTo("SCHEDULED");

        // 下一跳已经推到未来，且按"被抢占的那个到期时刻"算（不按 now 漂移）
        AutomationPO po = automationMapper.selectById(id);
        assertThat(po.getNextRunAt()).isAfter(LocalDateTime.now());
        // 再扫一轮：没有任何到期任务，不会重复执行（多实例场景下这等价于别的实例已抢走）
        assertThat(scheduler.sweepOnce()).isZero();
        assertThat(runsOf(id)).hasSize(1);
    }

    @Test
    @DisplayName("多实例安全：同一到期值只有一次 UPDATE 能抢占成功")
    void claimIsCompareAndSet() throws Exception {
        String token = registerAndLogin("auto-claim");
        Long id = createAutomation(token, "抢占任务", "hourly");
        LocalDateTime due = LocalDateTime.now().minusMinutes(1).truncatedTo(ChronoUnit.MILLIS);
        automationMapper.updateScheduling(id, due, 0, LocalDateTime.now());

        AutomationPO task = automationMapper.selectById(id);
        LocalDateTime next = automationService.nextRunAfter(task, task.getNextRunAt());

        // 第一次抢到、第二次影响 0 行：并发下另一台机器拿到的就是这个 0
        assertThat(automationMapper.claimDue(id, task.getNextRunAt(), next, LocalDateTime.now())).isEqualTo(1);
        assertThat(automationMapper.claimDue(id, task.getNextRunAt(), next, LocalDateTime.now())).isZero();
        // 抢占成功的那次已经自己推进了排期，不需要再写一遍
        assertThat(automationMapper.selectById(id).getNextRunAt()).isEqualTo(next);
    }

    // ------------------------------------------------------------- 失败重试

    @Test
    @DisplayName("失败重试：调度失败落 error → 排下一跳 → 模型恢复后重跑成功并把 retryCount 归零")
    void scheduledFailureRetries() throws Exception {
        when(agentRuntime.ask(anyLong(), any(AgentDefinition.class), anyString()))
                .thenThrow(new BusinessException(ErrorCode.MODEL_TIMEOUT));

        String token = registerAndLogin("auto-retry");
        Long id = createAutomation(token, "会失败的任务", "hourly");
        LocalDateTime due = LocalDateTime.now().minusMinutes(1).truncatedTo(ChronoUnit.MILLIS);
        automationMapper.updateScheduling(id, due, 0, LocalDateTime.now());

        // 第一次：失败，但 error 必须落库（不是只写日志）
        assertThat(scheduler.sweepOnce()).isEqualTo(1);
        List<AutomationRunPO> failed = runsOf(id);
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(failed.get(0).getErrorCode()).isEqualTo(ErrorCode.MODEL_TIMEOUT.getCode());
        assertThat(failed.get(0).getErrorMessage()).isNotBlank();

        // 失败后自动排了重试：retry_count=1，且 next_run_at 已到点（测试里 retry-delay-ms=0）
        AutomationPO afterFailure = automationMapper.selectById(id);
        assertThat(afterFailure.getLastStatus()).isEqualTo("FAILED");
        assertThat(afterFailure.getRetryCount()).isEqualTo(1);
        assertThat(afterFailure.getNextRunAt()).isBeforeOrEqualTo(LocalDateTime.now());

        // 第二次：模型恢复，重跑成功
        when(agentRuntime.ask(anyLong(), any(AgentDefinition.class), anyString()))
                .thenReturn(new AgentAnswer("这次成功了", List.of(), false));
        assertThat(scheduler.sweepOnce()).isEqualTo(1);

        assertThat(runsOf(id)).hasSize(2);
        assertThat(runsWithStatus(id, "SUCCEEDED")).hasSize(1);
        assertThat(runsWithStatus(id, "FAILED")).hasSize(1);
        // 成功一次就把重试计数清掉，否则下次偶发失败会被"历史欠账"提前放弃
        assertThat(automationMapper.selectById(id).getRetryCount()).isZero();
    }

    // ------------------------------------------------------------------

    private List<AutomationRunPO> runsOf(Long automationId) {
        return runMapper.selectList(Wrappers.<AutomationRunPO>lambdaQuery()
                .eq(AutomationRunPO::getAutomationId, automationId)
                .orderByDesc(AutomationRunPO::getCreatedAt));
    }

    private List<AutomationRunPO> runsWithStatus(Long automationId, String status) {
        return runMapper.selectList(Wrappers.<AutomationRunPO>lambdaQuery()
                .eq(AutomationRunPO::getAutomationId, automationId)
                .eq(AutomationRunPO::getStatus, status));
    }

    private Long createAutomation(String token, String name, String schedule) throws Exception {
        String body = mockMvc.perform(post("/api/automations")
                        .header(AUTH, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","prompt":"总结一下今天的工作","schedule":"%s","enabled":true}
                                """.formatted(name, schedule)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.valueOf(JsonPath.read(body, "$.data.id").toString());
    }

    private Long createKb(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/knowledge-bases")
                        .header(AUTH, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.valueOf(JsonPath.read(body, "$.data.id").toString());
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","email":"%s@example.com"}
                                """.formatted(username, PASSWORD, username)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 409));

        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(loginBody, "$.data.accessToken");
    }
}
