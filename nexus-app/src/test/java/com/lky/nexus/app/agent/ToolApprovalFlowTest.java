package com.lky.nexus.app.agent;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.ai.agent.AnswerPolicy;
import com.lky.nexus.ai.agent.ToolCategory;
import com.lky.nexus.ai.agent.ToolPermission;
import com.lky.nexus.app.agent.approval.AgentRunTraceRecorder;
import com.lky.nexus.app.agent.mapper.AgentRunMapper;
import com.lky.nexus.app.agent.mapper.AgentRunStepMapper;
import com.lky.nexus.app.agent.mapper.ToolApprovalMapper;
import com.lky.nexus.app.agent.mapper.ToolInvocationMapper;
import com.lky.nexus.app.agent.mapper.ToolRegistryMapper;
import com.lky.nexus.app.agent.model.AgentStepType;
import com.lky.nexus.app.agent.model.ApprovalStatus;
import com.lky.nexus.app.agent.model.RunStepStatus;
import com.lky.nexus.app.agent.model.ToolInvocationStatus;
import com.lky.nexus.app.agent.model.entity.AgentRunPO;
import com.lky.nexus.app.agent.model.entity.AgentRunStepPO;
import com.lky.nexus.app.agent.model.entity.ToolApprovalPO;
import com.lky.nexus.app.agent.model.entity.ToolInvocationPO;
import com.lky.nexus.app.agent.model.entity.ToolRegistryPO;
import com.lky.nexus.app.agent.tool.AgentToolFactory;
import com.lky.nexus.app.agent.tool.ToolAuthorizationService;
import com.lky.nexus.app.agent.tool.ToolDeclaration;
import com.lky.nexus.app.agent.tool.ToolGrantService;
import com.lky.nexus.app.agent.tool.ToolProvider;
import com.lky.nexus.app.agent.tool.ToolRegistrationService;
import com.lky.nexus.app.chat.stream.SseEventNames;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0 工具授权与人工审批闭环（§五 / §六 / §七 / §八）。
 *
 * <p><b>为什么不用模型</b>：模型调用是不确定、要花钱、且默认 profile 下根本没有可用密钥的。
 * 审批链路上真正需要验证的边界是"工具执行壳被调用时会发生什么"，而那正好是
 * {@link ExecutingToolCallback} 的入口——所以这里直接调用<b>真实装配出来的回调</b>
 * （{@code AgentToolFactory.assemble} 产出的那个，带授权闸门、审批等待、审计写入），
 * 用真实的 H2、真实的 Mapper、真实的 HTTP 接口驱动整个状态机。
 * 唯一被替换掉的是"模型决定要调用哪个工具"这一步——那一步不是本特性的语义。
 *
 * <p>因此每条断言都落在<b>真实状态变化</b>上：库里的 run/step/approval/invocation 行、
 * 假工具的执行计数、以及 HTTP 响应体。没有一条是"验证某方法被调用过"。
 *
 * <p>超时压到 20 秒（默认 5 分钟）：测试里每条等待都必须在秒级得出结果，
 * 同时留出足够余量，免得慢机器上"审批还没点、审批已超时"。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "nexus.agent.approval-timeout=PT20S")
@DisplayName("P0：工具授权 + 人工审批（真实拦截点、真实落库、无模型）")
class ToolApprovalFlowTest {

    private static final String PASSWORD = "Passw0rd!23";
    private static final String WRITE_TOOL = "test_write_tool";
    private static final String DANGEROUS_TOOL = "test_dangerous_tool";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentToolFactory toolFactory;

    @Autowired
    private ToolAuthorizationService authorization;

    @Autowired
    private ToolRegistrationService registrationService;

    @Autowired
    private ToolGrantService grantService;

    @Autowired
    private AgentRunTraceRecorder traceRecorder;

    @Autowired
    private AgentRunMapper runMapper;

    @Autowired
    private ToolApprovalMapper approvalMapper;

    @Autowired
    private ToolInvocationMapper invocationMapper;

    @Autowired
    private ToolRegistryMapper registryMapper;

    @Autowired
    private AgentRunStepMapper stepMapper;

    @Autowired
    private FakeTools fakeTools;

    @BeforeEach
    void resetFakeTools() {
        fakeTools.reset();
    }

    // ------------------------------------------------------------------
    // READ：不需要审批
    // ------------------------------------------------------------------

    @Test
    @DisplayName("READ 工具：直接执行成功，不产生任何审批记录（检索/计算不该打断用户）")
    void readToolExecutesWithoutApproval() throws Exception {
        String token = registerAndLogin("p0-read");
        Long userId = currentUserId(token);
        Long runId = newRunningRun(userId);
        ListenerHarness listener = harness(runId);

        ToolCallback calculator = callbackFor(userId, "calculator");
        String output = calculator.call("{\"expression\":\"1+2\"}", context(userId, runId, listener));

        assertThat(output).isEqualTo(jsonString("1+2 = 3"));
        assertThat(approvalRows(runId)).as("READ 工具不该产生待审批").isEmpty();
        assertThat(listener.events.approvals).isEmpty();

        List<ToolInvocationPO> invocations = invocationRows(runId);
        assertThat(invocations).hasSize(1);
        assertThat(invocations.get(0).getPermission()).isEqualTo("READ");
        assertThat(invocations.get(0).getStatus()).isEqualTo(ToolInvocationStatus.SUCCEEDED.name());
    }

    @Test
    @DisplayName("内置工具的权限是持久化元数据：calculator/knowledge_search 在库里都是 READ")
    void builtinPermissionsArePersisted() {
        ToolRegistryPO calculator = registrationService.findByCode("calculator").orElseThrow();
        ToolRegistryPO search = registrationService.findByCode("knowledge_search").orElseThrow();

        assertThat(calculator.getPermission()).isEqualTo(ToolPermission.READ.name());
        assertThat(calculator.getEnabled()).isTrue();
        assertThat(search.getPermission()).isEqualTo(ToolPermission.READ.name());
    }

    // ------------------------------------------------------------------
    // WRITE / DANGEROUS：审批前不执行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("WRITE 工具：无授权时挂起在审批上，审批前一次都没执行；批准后执行恰好一次")
    void writeToolWaitsForApprovalAndDoesNotExecute() throws Exception {
        String token = registerAndLogin("p0-write");
        Long userId = currentUserId(token);
        Long runId = newRunningRun(userId);
        ListenerHarness listener = harness(runId);

        CompletableFuture<String> pending = callAsync(callbackFor(userId, WRITE_TOOL),
                "{\"payload\":\"hello\"}", userId, runId, listener);

        waitForStatus(runId, "WAITING_APPROVAL");
        assertThat(fakeTools.writeCalls).as("审批通过前绝不能执行工具").hasValue(0);

        // 事件来自真实拦截点（执行壳），且带够了提交审批所需的字段
        assertThat(listener.events.approvals).hasSize(1);
        AgentContext.ApprovalRequired event = listener.events.approvals.get(0);
        assertThat(event.runId()).isEqualTo(runId);
        assertThat(event.toolCode()).isEqualTo(WRITE_TOOL);
        assertThat(event.approvalId()).isNotNull();

        mockMvc.perform(get("/api/agent/runs/" + runId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.data.pendingApproval.toolCode").value(WRITE_TOOL))
                .andExpect(jsonPath("$.data.pendingApproval.approvalId").isString());

        // 轨迹：此刻只有"停下来等审批"这一条阶段标记；TOOL_CALL / OBSERVATION 要等批准后才有
        mockMvc.perform(get("/api/agent/runs/" + runId + "/steps")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].type").value("THINK"))
                .andExpect(jsonPath("$.data[0].toolCode").value(WRITE_TOOL))
                .andExpect(jsonPath("$.data[0].output").value(org.hamcrest.Matchers.containsString("需要人工确认")));

        approve(token, runId, true, "once", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        assertThat(pending.get(15, TimeUnit.SECONDS)).isEqualTo(jsonString("written:hello"));
        assertThat(fakeTools.writeCalls).as("批准后必须真的执行了，且只执行一次").hasValue(1);
        assertThat(stepRows(runId)).extracting(AgentRunStepPO::getStepType)
                .contains(AgentStepType.TOOL_CALL.name(), AgentStepType.OBSERVATION.name());

        ToolInvocationPO invocation = invocationRows(runId).get(0);
        assertThat(invocation.getPermission()).isEqualTo("WRITE");
        assertThat(invocation.getStatus()).isEqualTo(ToolInvocationStatus.SUCCEEDED.name());
        assertThat(invocation.getApprovalId()).isNotNull();
        assertThat(approvalRows(runId).get(0).getStatus()).isEqualTo(ApprovalStatus.APPROVED.name());
        assertThat(approvalRows(runId).get(0).getDecisionScope()).isEqualTo("ONCE");
    }

    @Test
    @DisplayName("DANGEROUS 工具：同样必须审批（不能因为只特判 WRITE 而漏掉它）")
    void dangerousToolAlsoRequiresApproval() throws Exception {
        String token = registerAndLogin("p0-dangerous");
        Long userId = currentUserId(token);
        Long runId = newRunningRun(userId);
        ListenerHarness listener = harness(runId);

        CompletableFuture<String> pending = callAsync(callbackFor(userId, DANGEROUS_TOOL),
                "{\"payload\":\"drop\"}", userId, runId, listener);

        waitForStatus(runId, "WAITING_APPROVAL");
        assertThat(fakeTools.dangerousCalls).hasValue(0);
        assertThat(approvalRows(runId).get(0).getToolCode()).isEqualTo(DANGEROUS_TOOL);

        approve(token, runId, true, "once", "只此一次").andExpect(status().isOk());

        assertThat(pending.get(15, TimeUnit.SECONDS)).isEqualTo(jsonString("dangerous:drop"));
        assertThat(fakeTools.dangerousCalls).hasValue(1);
        assertThat(invocationRows(runId).get(0).getPermission()).isEqualTo("DANGEROUS");
    }

    // ------------------------------------------------------------------
    // once / always / deny
    // ------------------------------------------------------------------

    @Test
    @DisplayName("scope=once：只放行本次调用，不写长期授权；下一次同工具仍然要审批")
    void onceApprovalDoesNotPersistGrant() throws Exception {
        String token = registerAndLogin("p0-once");
        Long userId = currentUserId(token);
        Long firstRun = newRunningRun(userId);

        CompletableFuture<String> first = callAsync(callbackFor(userId, WRITE_TOOL),
                "{\"payload\":\"first\"}", userId, firstRun, harness(firstRun));
        waitForStatus(firstRun, "WAITING_APPROVAL");
        approve(token, firstRun, true, "once", null).andExpect(status().isOk());
        assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(jsonString("written:first"));

        assertThat(grantService.list(userId)).as("once 不是长期授权，不该落 ai_tool_grant").isEmpty();

        // 第二次调用：仍然必须停下来问一次
        Long secondRun = newRunningRun(userId);
        CompletableFuture<String> second = callAsync(callbackFor(userId, WRITE_TOOL),
                "{\"payload\":\"second\"}", userId, secondRun, harness(secondRun));
        waitForStatus(secondRun, "WAITING_APPROVAL");
        assertThat(fakeTools.writeCalls).as("第二次同样要在审批前保持未执行").hasValue(1);
        cancel(token, secondRun).andExpect(status().isOk());
        assertThat(second.get(15, TimeUnit.SECONDS)).contains("取消");
    }

    @Test
    @DisplayName("scope=always：写长期授权（绑定当前用户+工具），后续调用不再询问")
    void alwaysApprovalPersistsGrantAndSkipsNextApproval() throws Exception {
        String token = registerAndLogin("p0-always");
        Long userId = currentUserId(token);
        Long firstRun = newRunningRun(userId);

        CompletableFuture<String> first = callAsync(callbackFor(userId, WRITE_TOOL),
                "{\"payload\":\"first\"}", userId, firstRun, harness(firstRun));
        waitForStatus(firstRun, "WAITING_APPROVAL");

        approve(token, firstRun, true, "always", "信任这个工具").andExpect(status().isOk());
        assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(jsonString("written:first"));

        // 授权真的落库了，并且能从接口读回来
        assertThat(grantService.list(userId)).extracting("toolCode").containsExactly(WRITE_TOOL);
        mockMvc.perform(get("/api/users/me/tool-grants").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].toolCode").value(WRITE_TOOL))
                .andExpect(jsonPath("$.data[0].scope").value("ALWAYS"))
                .andExpect(jsonPath("$.data[0].id").isString());

        // 第二次调用：直接执行，不产生新的审批行
        Long secondRun = newRunningRun(userId);
        String output = callbackFor(userId, WRITE_TOOL)
                .call("{\"payload\":\"second\"}", context(userId, secondRun, harness(secondRun)));

        assertThat(output).isEqualTo(jsonString("written:second"));
        assertThat(fakeTools.writeCalls).hasValue(2);
        assertThat(approvalRows(secondRun)).as("always 之后不该再问一次").isEmpty();
    }

    @Test
    @DisplayName("scope=deny：工具不执行，但「用户拒绝」作为 observation 回灌，run 不是 FAILED")
    void deniedApprovalBecomesObservationAndRunStaysRunning() throws Exception {
        String token = registerAndLogin("p0-deny");
        Long userId = currentUserId(token);
        Long runId = newRunningRun(userId);

        CompletableFuture<String> pending = callAsync(callbackFor(userId, WRITE_TOOL),
                "{\"payload\":\"nope\"}", userId, runId, harness(runId));
        waitForStatus(runId, "WAITING_APPROVAL");

        approve(token, runId, false, "deny", "这次不想改数据").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        String observation = pending.get(15, TimeUnit.SECONDS);
        assertThat(observation)
                .contains("用户拒绝执行工具 " + WRITE_TOOL)
                .contains("这次不想改数据")
                .contains("选择其他可行方案");

        assertThat(fakeTools.writeCalls).hasValue(0);
        assertThat(grantService.list(userId)).isEmpty();

        // 拒绝不是失败：run 回到 RUNNING（模型可以换方案继续），审计里如实记 DENIED
        assertThat(runMapper.selectById(runId).getStatus()).isEqualTo("RUNNING");
        assertThat(invocationRows(runId).get(0).getStatus()).isEqualTo(ToolInvocationStatus.DENIED.name());
        assertThat(approvalRows(runId).get(0).getStatus()).isEqualTo(ApprovalStatus.DENIED.name());
        List<AgentRunStepPO> steps = stepRows(runId);
        assertThat(steps).anySatisfy(step -> {
            assertThat(step.getStepType()).isEqualTo(AgentStepType.OBSERVATION.name());
            assertThat(step.getStatus()).isEqualTo(RunStepStatus.DENIED.name());
            assertThat(step.getOutputDigest()).contains("用户拒绝执行工具");
        });
    }

    @Test
    @DisplayName("重复审批：第二次提交被拒（409），工具仍然只执行了一次")
    void duplicateApprovalIsRejectedAndToolRunsOnce() throws Exception {
        String token = registerAndLogin("p0-duplicate");
        Long userId = currentUserId(token);
        Long runId = newRunningRun(userId);

        CompletableFuture<String> pending = callAsync(callbackFor(userId, WRITE_TOOL),
                "{\"payload\":\"dup\"}", userId, runId, harness(runId));
        waitForStatus(runId, "WAITING_APPROVAL");
        approve(token, runId, true, "once", null).andExpect(status().isOk());
        assertThat(pending.get(15, TimeUnit.SECONDS)).isEqualTo(jsonString("written:dup"));

        // 第二次决策：审批已经不是 PENDING / 运行也不在等待状态 → 409，且不能触发第二次执行
        approve(token, runId, true, "once", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(not(0)));

        assertThat(fakeTools.writeCalls).hasValue(1);
        assertThat(invocationRows(runId)).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 取消
    // ------------------------------------------------------------------

    @Test
    @DisplayName("取消：等待审批时可以取消；工具不执行，审批作废，且取消后不允许再发起新工具调用")
    void cancelWhileWaitingForApproval() throws Exception {
        String token = registerAndLogin("p0-cancel");
        Long userId = currentUserId(token);
        Long runId = newRunningRun(userId);
        // 同一个 run 上的两条调用共用一个 harness：序号是运行内递增的，
        // 每次新建一个 trace 会让 (run_id, seq) 撞唯一键
        ListenerHarness listener = harness(runId);

        CompletableFuture<String> pending = callAsync(callbackFor(userId, WRITE_TOOL),
                "{\"payload\":\"cancelled\"}", userId, runId, listener);
        waitForStatus(runId, "WAITING_APPROVAL");

        cancel(token, runId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancelled").value(true));

        assertThat(pending.get(15, TimeUnit.SECONDS)).contains("取消");
        assertThat(fakeTools.writeCalls).hasValue(0);
        assertThat(runMapper.selectById(runId).getStatus()).isEqualTo("CANCELLED");
        assertThat(approvalRows(runId).get(0).getStatus()).isEqualTo(ApprovalStatus.CANCELLED.name());

        // 重复取消是幂等的
        cancel(token, runId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancelled").value(true));

        // 取消与审批竞争：取消先落地之后，审批必须失败——只允许一个状态转换生效
        approve(token, runId, true, "once", null).andExpect(status().isConflict());
        assertThat(fakeTools.writeCalls).as("取消之后即使有人点了批准，工具也不能被执行").hasValue(0);

        // 取消之后不再启动新的工具调用：READ 工具也会被短路
        String blocked = callbackFor(userId, "calculator")
                .call("{\"expression\":\"1+1\"}", context(userId, runId, listener));
        assertThat(blocked).contains("运行已取消");
        assertThat(invocationRows(runId)).as("被取消后不该再新增工具调用审计").hasSize(1);
    }

    @Test
    @DisplayName("启动同步：管理员改过的 permission 不被代码默认值覆盖，且界面与执行都按库里的值走")
    void registrySyncDoesNotOverwriteAdminPermission() throws Exception {
        String token = registerAndLogin("p0-sync");
        Long userId = currentUserId(token);
        ToolRegistryPO row = registrationService.findByCode("calculator").orElseThrow();
        String original = row.getPermission();
        ListenerHarness listener = null;
        Long runId = null;
        try {
            // 管理员把只读的计算器临时提级为 DANGEROUS
            row.setPermission(ToolPermission.DANGEROUS.name());
            registryMapper.updateById(row);

            // 重启时的幂等同步：描述性字段会刷新，权限不做覆盖
            registrationService.syncFromCatalog();

            ToolRegistryPO afterSync = registrationService.findByCode("calculator").orElseThrow();
            assertThat(afterSync.getPermission())
                    .as("同步只在首次创建时写默认权限；覆盖它等于把管理员的安全调级悄悄回退")
                    .isEqualTo(ToolPermission.DANGEROUS.name());

            // 目录接口下发的是库里的值（否则界面写着 READ、执行按 DANGEROUS 拦）
            mockMvc.perform(get("/api/tools").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[1].code").value("calculator"))
                    .andExpect(jsonPath("$.data[1].permission").value("DANGEROUS"));

            // 执行拦截同样按库里的值：一个只读工具被提级后立刻就要求审批
            runId = newRunningRun(userId);
            listener = harness(runId);
            CompletableFuture<String> pending = callAsync(callbackFor(userId, "calculator"),
                    "{\"expression\":\"1+1\"}", userId, runId, listener);
            waitForStatus(runId, "WAITING_APPROVAL");
            assertThat(listener.events.approvals).hasSize(1);

            cancel(token, runId).andExpect(status().isOk());
            assertThat(pending.get(15, TimeUnit.SECONDS)).contains("取消");
        } finally {
            row.setPermission(original);
            registryMapper.updateById(row);
        }
    }

    // ------------------------------------------------------------------
    // 越权
    // ------------------------------------------------------------------

    @Test
    @DisplayName("用户隔离：A 的 run 与授权，B 既读不到也改不了（统一 404 / 5012，不泄露归属）")
    void crossUserCannotAccessRunOrGrants() throws Exception {
        String alice = registerAndLogin("p0-alice");
        String bob = registerAndLogin("p0-bob");
        Long aliceId = currentUserId(alice);
        Long runId = newRunningRun(aliceId);

        CompletableFuture<String> pending = callAsync(callbackFor(aliceId, WRITE_TOOL),
                "{\"payload\":\"alice\"}", aliceId, runId, harness(runId));
        waitForStatus(runId, "WAITING_APPROVAL");

        mockMvc.perform(get("/api/agent/runs/" + runId).header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.AGENT_RUN_NOT_FOUND.getCode()));
        mockMvc.perform(get("/api/agent/runs/" + runId + "/steps")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
                .andExpect(status().isNotFound());
        approve(bob, runId, true, "once", null).andExpect(status().isNotFound());
        cancel(bob, runId).andExpect(status().isNotFound());

        // B 的越权尝试没有改变 A 的运行状态
        assertThat(runMapper.selectById(runId).getStatus()).isEqualTo("WAITING_APPROVAL");
        assertThat(approvalRows(runId).get(0).getStatus()).isEqualTo(ApprovalStatus.PENDING.name());
        assertThat(fakeTools.writeCalls).hasValue(0);

        // A 自己批准，收尾（避免留下挂起的等待线程）
        approve(alice, runId, true, "always", null).andExpect(status().isOk());
        assertThat(pending.get(15, TimeUnit.SECONDS)).isEqualTo(jsonString("written:alice"));

        // 授权也按用户隔离：B 看不到 A 的授权，B 的撤销不该影响 A
        mockMvc.perform(get("/api/users/me/tool-grants").header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(delete("/api/users/me/tool-grants/" + WRITE_TOOL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
                .andExpect(status().isOk());

        assertThat(grantService.list(aliceId)).extracting("toolCode").containsExactly(WRITE_TOOL);
    }

    @Test
    @DisplayName("未认证：运行与授权接口都返回 2001，不接受请求体里的用户身份")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/users/me/tool-grants"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
        mockMvc.perform(get("/api/agent/runs/1"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // 默认拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未注册工具：5011 直接拒绝（既不入审批等待，也不执行）")
    void unregisteredToolIsRejected() throws Exception {
        String token = registerAndLogin("p0-unknown");
        Long userId = currentUserId(token);

        assertThatThrownBy(() -> authorization.authorize(userId, "ghost_tool"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.TOOL_NOT_FOUND.getCode()));
        assertThatThrownBy(() -> grantService.grantAlways(userId, "ghost_tool"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.TOOL_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("已禁用工具：即使已有 always 授权也拒绝执行（禁用优先于授权）")
    void disabledToolIsRejectedEvenWithGrant() throws Exception {
        String token = registerAndLogin("p0-disabled");
        Long userId = currentUserId(token);
        grantService.grantAlways(userId, DANGEROUS_TOOL);

        ToolRegistryPO row = registrationService.findByCode(DANGEROUS_TOOL).orElseThrow();
        row.setEnabled(false);
        registryMapper.updateById(row);
        try {
            Long runId = newRunningRun(userId);
            ListenerHarness listener = harness(runId);

            String output = callbackFor(userId, DANGEROUS_TOOL)
                    .call("{\"payload\":\"blocked\"}", context(userId, runId, listener));

            assertThat(output).contains("未执行");
            assertThat(fakeTools.dangerousCalls).hasValue(0);
            assertThat(approvalRows(runId)).isEmpty();
            assertThat(listener.events.approvals).as("被禁用的工具根本不该进入审批流程").isEmpty();
        } finally {
            row.setEnabled(true);
            registryMapper.updateById(row);
        }
    }

    @Test
    @DisplayName("缺少用户上下文：默认拒绝（绝不退化成「公开工具」）")
    void missingUserContextIsDenied() throws Exception {
        String token = registerAndLogin("p0-no-context");
        Long userId = currentUserId(token);

        // 没有任何 ToolContext：拿不到用户 → 拒
        String output = callbackFor(userId, WRITE_TOOL).call("{\"payload\":\"anonymous\"}", null);

        assertThat(output).contains("未执行");
        assertThat(fakeTools.writeCalls).hasValue(0);
    }

    // ------------------------------------------------------------------
    // 审计与脱敏
    // ------------------------------------------------------------------

    @Test
    @DisplayName("审计脱敏：入参里的密钥不会出现在步骤摘要或调用审计里")
    void sensitiveArgumentsAreMaskedInAudit() throws Exception {
        String token = registerAndLogin("p0-mask");
        Long userId = currentUserId(token);
        Long runId = newRunningRun(userId);
        String secret = "sk-abcdefghijklmnop123456";

        CompletableFuture<String> pending = callAsync(callbackFor(userId, WRITE_TOOL),
                "{\"payload\":\"token=" + secret + "\"}", userId, runId, harness(runId));
        waitForStatus(runId, "WAITING_APPROVAL");

        // 待审批记录里的 arguments 就已经是脱敏后的（事件与查询接口用的是同一份）
        ToolApprovalPO approval = approvalRows(runId).get(0);
        assertThat(approval.getArgumentsDigest()).doesNotContain(secret).contains("sk-***");

        approve(token, runId, true, "once", null).andExpect(status().isOk());
        assertThat(pending.get(15, TimeUnit.SECONDS)).contains(secret).as("工具本身仍然拿到原值");

        ToolInvocationPO invocation = invocationRows(runId).get(0);
        assertThat(invocation.getArgumentsDigest()).doesNotContain(secret).contains("sk-***");

        List<AgentRunStepPO> steps = stepRows(runId);
        assertThat(steps).isNotEmpty();
        for (AgentRunStepPO step : steps) {
            if (step.getInputDigest() != null) {
                assertThat(step.getInputDigest()).doesNotContain(secret);
            }
            if (step.getOutputDigest() != null) {
                assertThat(step.getOutputDigest()).doesNotContain(secret);
            }
        }
    }

    @Test
    @DisplayName("轨迹只记阶段标记：THINK 里没有模型推理原文，也不回灌用户问题")
    void thinkStepsOnlyCarryStageMarkers() throws Exception {
        String token = registerAndLogin("p0-think");
        Long userId = currentUserId(token);
        Long runId = newRunningRun(userId);
        // 生产用的轨迹记录器同时是审批事件的监听器：harness 用同一个对象写步骤，
        // 因此这里落下的就是真实运行会写的那几条
        ListenerHarness harness = harness(runId);
        harness.trace.append(AgentStepType.THINK, null, null, "开始运行：阶段标记", RunStepStatus.OK);
        CompletableFuture<String> pending = callAsync(callbackFor(userId, WRITE_TOOL),
                "{\"payload\":\"think\"}", userId, runId, harness);
        waitForStatus(runId, "WAITING_APPROVAL");

        List<String> thinkOutputs = stepRows(runId).stream()
                .filter(step -> AgentStepType.THINK.name().equals(step.getStepType()))
                .map(AgentRunStepPO::getOutputDigest)
                .toList();

        assertThat(thinkOutputs).isNotEmpty();
        assertThat(thinkOutputs).allSatisfy(text -> assertThat(text)
                .as("THINK 只能是阶段标记，不能是推理原文")
                .doesNotContain("chain")
                .doesNotContain("理由"));
        assertThat(thinkOutputs).anySatisfy(text -> assertThat(text).contains("需要人工确认"));

        cancel(token, runId).andExpect(status().isOk());
        assertThat(pending.get(15, TimeUnit.SECONDS)).contains("取消");
    }

    // ------------------------------------------------------------------
    // SSE 常量
    // ------------------------------------------------------------------

    @Test
    @DisplayName("SSE：审批事件复用既有 SseEventNames.APPROVAL_REQUIRED，且没有第二个同义常量")
    void approvalEventUsesExistingSseConstant() {
        assertThat(SseEventNames.APPROVAL_REQUIRED).isEqualTo("approval_required");

        List<String> values = new ArrayList<>();
        for (Field field : SseEventNames.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    values.add((String) field.get(null));
                } catch (IllegalAccessException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }
        assertThat(Collections.frequency(values, "approval_required"))
                .as("审批事件名只允许有一个常量：再造一个同义常量会让前后端契约分叉")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 授权接口
    // ------------------------------------------------------------------

    @Test
    @DisplayName("授权接口：PUT 写入 / GET 读回 / DELETE 单个与全部（幂等），非法 scope 走 1001")
    void grantApiRoundTrip() throws Exception {
        String token = registerAndLogin("p0-grant-api");
        Long userId = currentUserId(token);

        mockMvc.perform(put("/api/users/me/tool-grants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolCode\":\"" + WRITE_TOOL + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCode").value(WRITE_TOOL))
                .andExpect(jsonPath("$.data.scope").value("ALWAYS"));

        // 重复 PUT 幂等：仍然只有一行
        mockMvc.perform(put("/api/users/me/tool-grants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolCode\":\"" + WRITE_TOOL + "\",\"scope\":\"always\"}"))
                .andExpect(status().isOk());
        assertThat(grantService.list(userId)).hasSize(1);

        // once / deny 不是长期授权，不能通过这个接口写进来
        mockMvc.perform(put("/api/users/me/tool-grants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolCode\":\"" + WRITE_TOOL + "\",\"scope\":\"once\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID.getCode()));

        // 未注册工具不能授权
        mockMvc.perform(put("/api/users/me/tool-grants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolCode\":\"ghost_tool\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOOL_NOT_FOUND.getCode()));

        mockMvc.perform(delete("/api/users/me/tool-grants/" + WRITE_TOOL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(grantService.list(userId)).isEmpty();

        // 撤销全部：本来就没有也是成功（幂等）
        mockMvc.perform(delete("/api/users/me/tool-grants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("运行接口：未知 runId 与非法格式都返回 5012/404（不泄露 id 是否存在）")
    void unknownRunIsNotFound() throws Exception {
        String token = registerAndLogin("p0-unknown-run");

        mockMvc.perform(get("/api/agent/runs/999999999999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.AGENT_RUN_NOT_FOUND.getCode()));

        mockMvc.perform(get("/api/agent/runs/not-a-number")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.AGENT_RUN_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("审批请求体：非法 scope 与非法组合都返回统一 Result（1001）")
    void invalidApprovalPayloadIsRejected() throws Exception {
        String token = registerAndLogin("p0-bad-payload");
        Long userId = currentUserId(token);
        Long runId = newRunningRun(userId);

        CompletableFuture<String> pending = callAsync(callbackFor(userId, WRITE_TOOL),
                "{\"payload\":\"bad\"}", userId, runId, harness(runId));
        waitForStatus(runId, "WAITING_APPROVAL");

        // scope 不认识 → 1001，且审批仍然待决策
        approve(token, runId, true, "sometimes", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID.getCode()));
        assertThat(approvalRows(runId).get(0).getStatus()).isEqualTo(ApprovalStatus.PENDING.name());

        // approved=true 与 scope=deny 是矛盾组合 → 1001
        approve(token, runId, true, "deny", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID.getCode()));

        cancel(token, runId).andExpect(status().isOk());
        assertThat(pending.get(15, TimeUnit.SECONDS)).contains("取消");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions approve(String token, Long runId,
                                                                         boolean approved,
                                                                         String scope, String reason)
            throws Exception {
        String body = """
                {"approved":%s,"scope":"%s"%s}
                """.formatted(approved, scope,
                reason == null ? "" : ",\"reason\":\"" + reason + "\"");
        return mockMvc.perform(post("/api/agent/runs/" + runId + "/approval")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions cancel(String token, Long runId) throws Exception {
        return mockMvc.perform(post("/api/agent/runs/" + runId + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    /** 装配出来、带授权闸门的真实回调（与模型那条链路用的是同一个装配入口）。 */
    private ToolCallback callbackFor(Long userId, String toolCode) {
        AgentDefinition definition = new AgentDefinition(999999L, "p0.test", "P0 测试 Agent",
                "你是测试助手", "chat.agent", null, List.of(), List.of(toolCode),
                AnswerPolicy.DEFAULT, false);
        return Arrays.stream(toolFactory.assemble(userId, definition))
                .filter(callback -> callback.getToolDefinition().name().equals(toolCode))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("装配结果里没有工具：" + toolCode));
    }

    /**
     * 一条既有轨迹写入、又能被测试观察的监听器。
     *
     * <p>两者都要：{@link AgentRunTraceRecorder.RunTrace} 是生产里真正写 step 的对象
     * （不接上它，{@code /steps} 就永远是空的，测试也就测不到轨迹），
     * {@link RecordingListener} 让测试能直接看到审批事件。
     */
    private ListenerHarness harness(Long runId) {
        return new ListenerHarness(traceRecorder, runId);
    }

    /**
     * 框架的 {@code MethodToolCallback} 会把工具返回值序列化成 JSON——字符串也带引号
     * （与 ToolResourceTest 里"MCP 要把引号拆回原文"那条注释同源）。
     * 这里显式写出这层引号：否则框架的序列化行为一变，测试失败的理由会被误读成"工具没执行"。
     */
    private static String jsonString(String value) {
        return "\"" + value + "\"";
    }

    private ToolContext context(Long userId, Long runId, AgentContext.ToolCallListener listener) {
        return new ToolContext(AgentContext.of(userId, null, listener, runId));
    }

    private CompletableFuture<String> callAsync(ToolCallback callback, String input,
                                                Long userId, Long runId,
                                                AgentContext.ToolCallListener listener) {
        return CompletableFuture.supplyAsync(() ->
                callback.call(input, context(userId, runId, listener)));
    }

    private Long newRunningRun(Long userId) {
        AgentRunPO run = new AgentRunPO();
        run.setUserId(userId);
        run.setQuestion("P0 测试运行");
        run.setStatus("RUNNING");
        run.setMaxIterations(8);
        run.setIterationCount(0);
        run.setModelRole("chat.agent");
        LocalDateTime now = LocalDateTime.now();
        run.setCreatedAt(now);
        run.setStartedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);
        return run.getId();
    }

    private void waitForStatus(Long runId, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            AgentRunPO run = runMapper.selectById(runId);
            if (run != null && expected.equals(run.getStatus())) {
                return;
            }
            Thread.sleep(50);
        }
        AgentRunPO run = runMapper.selectById(runId);
        throw new AssertionError("等待运行进入 %s 超时，当前状态：%s"
                .formatted(expected, run == null ? "null" : run.getStatus()));
    }

    private List<ToolApprovalPO> approvalRows(Long runId) {
        return approvalMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ToolApprovalPO>lambdaQuery()
                        .eq(ToolApprovalPO::getRunId, runId)
                        .orderByAsc(ToolApprovalPO::getCreatedAt));
    }

    private List<ToolInvocationPO> invocationRows(Long runId) {
        return invocationMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ToolInvocationPO>lambdaQuery()
                        .eq(ToolInvocationPO::getRunId, runId)
                        .orderByAsc(ToolInvocationPO::getStartedAt));
    }

    private List<AgentRunStepPO> stepRows(Long runId) {
        return stepMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<AgentRunStepPO>lambdaQuery()
                .eq(AgentRunStepPO::getRunId, runId)
                .orderByAsc(AgentRunStepPO::getSeq));
    }

    private Long currentUserId(String token) throws Exception {
        return Long.valueOf(JsonPath.read(mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id"));
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s","email":"%s@example.com"}
                        """.formatted(username, PASSWORD, username)));
        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(loginBody, "$.data.accessToken");
    }

    /** 记录审批事件（它就是 SSE 那条链路在服务端的对应物）。 */
    static final class ListenerHarness implements AgentContext.ToolCallListener {

        final AgentRunTraceRecorder.RunTrace trace;
        final RecordingListener events;

        ListenerHarness(AgentRunTraceRecorder recorder, Long runId) {
            this.trace = recorder.begin(runId);
            this.events = new RecordingListener(runId);
        }

        @Override
        public Long runId() {
            return trace.runId();
        }

        @Override
        public void onCall(String tool, String input) {
            trace.onCall(tool, input);
        }

        @Override
        public void onResult(AgentContext.ToolCallRecord record) {
            trace.onResult(record);
        }

        @Override
        public void onApprovalRequired(AgentContext.ApprovalRequired event) {
            trace.onApprovalRequired(event);
            events.onApprovalRequired(event);
        }
    }

    static final class RecordingListener implements AgentContext.ToolCallListener {

        private final Long runId;
        final List<AgentContext.ApprovalRequired> approvals =
                Collections.synchronizedList(new ArrayList<>());

        RecordingListener(Long runId) {
            this.runId = runId;
        }

        @Override
        public Long runId() {
            return runId;
        }

        @Override
        public void onCall(String tool, String input) {
        }

        @Override
        public void onResult(AgentContext.ToolCallRecord record) {
        }

        @Override
        public void onApprovalRequired(AgentContext.ApprovalRequired event) {
            approvals.add(event);
        }
    }

    /** 假工具的执行计数与最近入参：断言"审批前没执行"靠的就是它。 */
    static final class FakeTools {

        final AtomicInteger writeCalls = new AtomicInteger();
        final AtomicInteger dangerousCalls = new AtomicInteger();
        volatile String lastWritePayload;

        void reset() {
            writeCalls.set(0);
            dangerousCalls.set(0);
            lastWritePayload = null;
        }
    }

    /** 写入型假工具：真实的 @Tool 方法，经过真实的执行壳与授权闸门。 */
    static final class FakeWriteTool {

        private final FakeTools counters;

        FakeWriteTool(FakeTools counters) {
            this.counters = counters;
        }

        @Tool(name = WRITE_TOOL, description = "写入型测试工具：只在 P0 审批用例里使用")
        public String write(@ToolParam(description = "写入内容") String payload) {
            counters.writeCalls.incrementAndGet();
            counters.lastWritePayload = payload;
            return "written:" + payload;
        }
    }

    /** 危险型假工具：与 WRITE 共用审批路径，用来证明拦截不是按 ==WRITE 特判的。 */
    static final class FakeDangerousTool {

        private final FakeTools counters;

        FakeDangerousTool(FakeTools counters) {
            this.counters = counters;
        }

        @Tool(name = DANGEROUS_TOOL, description = "危险型测试工具：只在 P0 审批用例里使用")
        public String run(@ToolParam(description = "危险操作参数") String payload) {
            counters.dangerousCalls.incrementAndGet();
            return "dangerous:" + payload;
        }
    }

    /** 假工具来源：注册在内置工具之后，权限写 WRITE / DANGEROUS。 */
    static final class FakeToolProvider implements ToolProvider, Ordered {

        private final FakeWriteTool write;
        private final FakeDangerousTool dangerous;

        FakeToolProvider(FakeWriteTool write, FakeDangerousTool dangerous) {
            this.write = write;
            this.dangerous = dangerous;
        }

        @Override
        public List<ToolDeclaration> declare() {
            return List.of(
                    new ToolDeclaration(write, "写入型测试工具", ToolCategory.OTHER, ToolPermission.WRITE, false),
                    new ToolDeclaration(dangerous, "危险型测试工具", ToolCategory.OTHER,
                            ToolPermission.DANGEROUS, false));
        }

        @Override
        public int getOrder() {
            return 100;
        }
    }

    @TestConfiguration
    static class FakeToolsConfig {

        @Bean
        FakeTools fakeTools() {
            return new FakeTools();
        }

        @Bean
        FakeWriteTool fakeWriteTool(FakeTools counters) {
            return new FakeWriteTool(counters);
        }

        @Bean
        FakeDangerousTool fakeDangerousTool(FakeTools counters) {
            return new FakeDangerousTool(counters);
        }

        @Bean
        ToolProvider fakeToolProvider(FakeWriteTool write, FakeDangerousTool dangerous) {
            return new FakeToolProvider(write, dangerous);
        }
    }
}
