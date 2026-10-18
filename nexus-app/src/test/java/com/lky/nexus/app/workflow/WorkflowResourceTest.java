package com.lky.nexus.app.workflow;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Workflow 资源")
class WorkflowResourceTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("创建草稿、发布版本并运行最小图")
    void createsDraftPublishesAndRunsMinimalGraph() throws Exception {
        String token = registerAndLogin("workflow-minimal");
        String workflowId = createWorkflow(token, "最小工作流");
        saveDraft(token, workflowId);

        mockMvc.perform(post("/api/workflows/" + workflowId + "/validate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true));

        String published = mockMvc.perform(post("/api/workflows/" + workflowId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.publishedVersionNo").value(1))
                .andReturn().getResponse().getContentAsString();
        String versionId = JsonPath.read(published, "$.data.publishedVersionId");

        String run = mockMvc.perform(post("/api/workflows/" + workflowId + "/debug")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputs\":{\"question\":\"hello\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.versionId").value(versionId))
                .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(run, "$.data.id");

        mockMvc.perform(get("/api/workflow-runs/" + runId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        mockMvc.perform(get("/api/workflow-runs/" + runId + "/nodes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").value("SUCCEEDED"));
    }

    @Test
    @DisplayName("运行记录按归属隔离：其他用户访问返回 404")
    void isolatesRunsByOwner() throws Exception {
        String owner = registerAndLogin("workflow-owner");
        String intruder = registerAndLogin("workflow-intruder");
        String workflowId = createWorkflow(owner, "隔离工作流");
        saveDraft(owner, workflowId);

        String run = mockMvc.perform(post("/api/workflows/" + workflowId + "/debug")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputs\":{}}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(run, "$.data.id");

        mockMvc.perform(get("/api/workflow-runs/" + runId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(6006));
    }

    @Test
    @DisplayName("非法图无法发布")
    void rejectsInvalidGraphOnPublish() throws Exception {
        String token = registerAndLogin("workflow-invalid");
        String workflowId = createWorkflow(token, "非法工作流");

        mockMvc.perform(put("/api/workflows/" + workflowId + "/draft")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nodes":[{"id":"a","type":"LLM","version":1,"config":{}}],
                                 "edges":[]}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/workflows/" + workflowId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(6001));
    }

    @Test
    @DisplayName("工作流删除按归属隔离")
    void deletesOnlyOwnedWorkflow() throws Exception {
        String owner = registerAndLogin("workflow-delete-owner");
        String intruder = registerAndLogin("workflow-delete-intruder");
        String workflowId = createWorkflow(owner, "可删除工作流");

        mockMvc.perform(delete("/api/workflows/" + workflowId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(6005));

        mockMvc.perform(delete("/api/workflows/" + workflowId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("运行历史按工作流倒序分页返回")
    void listsRunsNewestFirst() throws Exception {
        String token = registerAndLogin("workflow-run-list");
        String workflowId = createWorkflow(token, "运行历史工作流");
        saveDraft(token, workflowId);

        String firstRun = debugRun(token, workflowId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstId = JsonPath.read(firstRun, "$.data.id");

        String secondRun = debugRun(token, workflowId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondId = JsonPath.read(secondRun, "$.data.id");

        mockMvc.perform(get("/api/workflow-runs")
                        .param("workflowId", workflowId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list.length()").value(2))
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(secondId))
                .andExpect(jsonPath("$.data.list[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.list[1].id").value(firstId));

        // 分页：每页 1 条时只返回最新一条
        mockMvc.perform(get("/api/workflow-runs")
                        .param("workflowId", workflowId)
                        .param("pageNum", "1")
                        .param("pageSize", "1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.pages").value(2));
    }

    @Test
    @DisplayName("失败的运行也能从列表找回，并读到失败节点轨迹")
    void listsFailedRunSoTraceIsReadable() throws Exception {
        String token = registerAndLogin("workflow-run-failed");
        String workflowId = createWorkflow(token, "会失败的工作流");
        // AGENT 节点指向不存在的 agentId：校验只查非空，执行时才抛错——确定性失败，不依赖外部服务
        saveGraph(token, workflowId, """
                {"nodes":[
                  {"id":"start","type":"START","version":1,"config":{},"position":{"x":0,"y":0}},
                  {"id":"agent","type":"AGENT","version":1,"config":{"agentId":"999999999999"},
                   "position":{"x":200,"y":0}},
                  {"id":"end","type":"END","version":1,"config":{},"position":{"x":400,"y":0}}
                ],
                "edges":[
                  {"id":"edge-1","source":"start","target":"agent","sourceHandle":null},
                  {"id":"edge-2","source":"agent","target":"end","sourceHandle":null}
                ]}
                """);
        mockMvc.perform(post("/api/workflows/" + workflowId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // 同步执行：失败时直接抛错，HTTP 报错体里只有错误码、没有 runId
        //（这正是列表接口存在的理由：调用方拿不到 runId，就无法回读失败运行的轨迹）
        debugRun(token, workflowId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(5006));

        String listed = mockMvc.perform(get("/api/workflow-runs")
                        .param("workflowId", workflowId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].status").value("FAILED"))
                .andReturn().getResponse().getContentAsString();
        String failedRunId = JsonPath.read(listed, "$.data.list[0].id");

        mockMvc.perform(get("/api/workflow-runs/" + failedRunId + "/nodes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                // 节点轨迹按 id 升序：start / agent / end
                .andExpect(jsonPath("$.data[1].nodeId").value("agent"))
                .andExpect(jsonPath("$.data[1].status").value("FAILED"));
    }

    @Test
    @DisplayName("运行历史按归属隔离：他人的工作流返回 404")
    void isolatesRunListByOwner() throws Exception {
        String owner = registerAndLogin("workflow-list-owner");
        String intruder = registerAndLogin("workflow-list-intruder");
        String workflowId = createWorkflow(owner, "他人工作流");
        saveDraft(owner, workflowId);
        debugRun(owner, workflowId).andExpect(status().isOk());

        mockMvc.perform(get("/api/workflow-runs")
                        .param("workflowId", workflowId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(6005));

        mockMvc.perform(get("/api/workflow-runs")
                        .param("workflowId", workflowId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("改元信息是局部更新，且不产生新版本")
    void updatesMetadataWithoutNewVersion() throws Exception {
        String token = registerAndLogin("workflow-update");
        String workflowId = createWorkflow(token, "改名前");
        saveDraft(token, workflowId);

        String before = mockMvc.perform(get("/api/workflows/" + workflowId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int draftVersionNo = JsonPath.read(before, "$.data.draftVersionNo");

        // 只传 name：description 应保持 createWorkflow 建时的 "MVP"（局部更新）
        mockMvc.perform(put("/api/workflows/" + workflowId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"改名后\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("改名后"))
                .andExpect(jsonPath("$.data.description").value("MVP"))
                // 改元信息不该动图版本：草稿版本号保持不变
                .andExpect(jsonPath("$.data.draftVersionNo").value(draftVersionNo));

        // description 传空串 = 清空；name 未传则不动
        mockMvc.perform(put("/api/workflows/" + workflowId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("改名后"))
                .andExpect(jsonPath("$.data.description").value(nullValue()));
    }

    @Test
    @DisplayName("改元信息按归属隔离：他人的工作流返回 404 且名字不变")
    void updatesOnlyOwnedWorkflow() throws Exception {
        String owner = registerAndLogin("workflow-update-owner");
        String intruder = registerAndLogin("workflow-update-intruder");
        String workflowId = createWorkflow(owner, "归属工作流");

        mockMvc.perform(put("/api/workflows/" + workflowId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权改名\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(6005));

        mockMvc.perform(get("/api/workflows/" + workflowId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("归属工作流"));
    }

    // ---------------- 重试（从失败节点续跑） ----------------

    @Test
    @DisplayName("重试从失败节点续跑：已成功节点被复制而非重跑")
    void retryResumesFromFailedNode() throws Exception {
        String token = registerAndLogin("workflow-retry");
        String workflowId = createWorkflow(token, "重试工作流");
        // AGENT 指向不存在的 agentId：校验只查非空，执行时抛错——确定性失败，不依赖外部服务
        saveGraph(token, workflowId, """
                {"nodes":[
                  {"id":"start","type":"START","version":1,"config":{},"position":{"x":0,"y":0}},
                  {"id":"tpl","type":"TEMPLATE","version":1,"config":{"template":"tpl"},
                   "position":{"x":150,"y":0}},
                  {"id":"agent","type":"AGENT","version":1,"config":{"agentId":"999999999999"},
                   "position":{"x":300,"y":0}},
                  {"id":"end","type":"END","version":1,"config":{},"position":{"x":450,"y":0}}
                ],
                "edges":[
                  {"id":"e1","source":"start","target":"tpl","sourceHandle":null},
                  {"id":"e2","source":"tpl","target":"agent","sourceHandle":null},
                  {"id":"e3","source":"agent","target":"end","sourceHandle":null}
                ]}
                """);
        publishWorkflow(token, workflowId);

        // 首次运行在 agent 节点失败（HTTP 404/5006），运行本身仍落库
        mockMvc.perform(post("/api/workflows/" + workflowId + "/runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputs\":{\"question\":\"q\"}}"))
                .andExpect(status().isNotFound());

        List<String> runs = runIds(token, workflowId);
        check(runs.size() == 1, "首次失败运行应已落库");
        String sourceRunId = runs.get(0);
        // 记下源运行里 tpl 的 startedAt，用它证明重试没有重跑这个节点
        String sourceNodes = mockMvc.perform(get("/api/workflow-runs/" + sourceRunId + "/nodes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String tplStartedAt = JsonPath.read(sourceNodes, "$.data[1].startedAt");

        // 重试：仍会在同一个 agent 节点失败（本机没有真实 Agent）
        mockMvc.perform(post("/api/workflow-runs/" + sourceRunId + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());

        List<String> afterRetry = runIds(token, workflowId);
        check(afterRetry.size() == 2, "重试应新建一次运行");
        String retryRunId = afterRetry.get(0);

        mockMvc.perform(get("/api/workflow-runs/" + retryRunId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.triggerType").value("RETRY"))
                // 溯源列（V24）：重试指向源运行……
                .andExpect(jsonPath("$.data.retryOfRunId").value(sourceRunId))
                .andExpect(jsonPath("$.data.status").value("FAILED"));

        // ……而非重试运行该列为空（不是 0、不是自身 id）
        mockMvc.perform(get("/api/workflow-runs/" + sourceRunId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retryOfRunId").isEmpty());

        mockMvc.perform(get("/api/workflow-runs/" + retryRunId + "/nodes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[1].status").value("SUCCEEDED"))
                // 已成功的 tpl 时间戳与源运行一致 ⇒ 是复制过来的，没有重跑
                .andExpect(jsonPath("$.data[1].startedAt").value(tplStartedAt))
                .andExpect(jsonPath("$.data[2].status").value("FAILED"))
                .andExpect(jsonPath("$.data[3].status").value("PENDING"));
    }

    @Test
    @DisplayName("成功的运行不可重试（409 / 6008）")
    void retryRejectsSucceededRun() throws Exception {
        String token = registerAndLogin("workflow-retry-guard");
        String workflowId = createWorkflow(token, "成功工作流");
        saveDraft(token, workflowId);
        publishWorkflow(token, workflowId);

        String run = mockMvc.perform(post("/api/workflows/" + workflowId + "/runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(run, "$.data.id");

        mockMvc.perform(post("/api/workflow-runs/" + runId + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(6008));
    }

    // ---------------- 人工审批（挂起 / 恢复） ----------------

    @Test
    @DisplayName("人工审批：挂起 → 同意 → 继续执行至成功，重复审批 409")
    void approvalSuspendsThenResumesToSuccess() throws Exception {
        String token = registerAndLogin("workflow-approval");
        String workflowId = createApprovalWorkflow(token, "审批通过工作流");
        String runId = startWaitingRun(token, workflowId);

        mockMvc.perform(get("/api/workflow-runs/" + runId + "/nodes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[1].nodeType").value("HUMAN_APPROVAL"))
                .andExpect(jsonPath("$.data[1].status").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.data[2].status").value("PENDING"));

        mockMvc.perform(post("/api/workflow-runs/" + runId + "/approval")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"comment\":\"可以\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        mockMvc.perform(get("/api/workflow-runs/" + runId + "/nodes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data[1].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[1].output.approved").value(true))
                .andExpect(jsonPath("$.data[1].output.comment").value("可以"))
                .andExpect(jsonPath("$.data[2].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[3].status").value("SUCCEEDED"));

        // 重复审批：运行已不在 WAITING_APPROVAL → 409 / 6007
        mockMvc.perform(post("/api/workflow-runs/" + runId + "/approval")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(6007));
    }

    @Test
    @DisplayName("人工审批：拒绝 → 运行失败且原因落库")
    void approvalRejectFailsRun() throws Exception {
        String token = registerAndLogin("workflow-approval-reject");
        String workflowId = createApprovalWorkflow(token, "审批拒绝工作流");
        String runId = startWaitingRun(token, workflowId);

        mockMvc.perform(post("/api/workflow-runs/" + runId + "/approval")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"comment\":\"数据不对\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.errorMessage").value(org.hamcrest.Matchers.containsString("数据不对")));

        mockMvc.perform(get("/api/workflow-runs/" + runId + "/nodes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data[1].status").value("FAILED"))
                .andExpect(jsonPath("$.data[1].errorMessage").value(org.hamcrest.Matchers.containsString("数据不对")))
                .andExpect(jsonPath("$.data[2].status").value("PENDING"));
    }

    @Test
    @DisplayName("审批与重试按归属隔离：他人的运行返回 404")
    void isolatesApprovalAndRetry() throws Exception {
        String owner = registerAndLogin("workflow-approval-owner");
        String intruder = registerAndLogin("workflow-approval-intruder");
        String workflowId = createApprovalWorkflow(owner, "归属审批工作流");
        String runId = startWaitingRun(owner, workflowId);

        mockMvc.perform(post("/api/workflow-runs/" + runId + "/approval")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(6006));

        mockMvc.perform(post("/api/workflow-runs/" + runId + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(6006));
    }

    @Test
    @DisplayName("预置模板：清单可查、可实例化为草稿，配置知识库前校验不通过")
    void listsAndInstantiatesPresets() throws Exception {
        String token = registerAndLogin("workflow-presets");

        mockMvc.perform(get("/api/workflows/presets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].code").value("knowledge-gap-analysis"))
                .andExpect(jsonPath("$.data[1].code").value("study-plan-7d"))
                .andExpect(jsonPath("$.data[2].code").value("knowledge-report"));

        String created = mockMvc.perform(post("/api/workflows/presets/study-plan-7d")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("7 天学习计划"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                // 实例化即落草稿：版本号 1，图已写进去（6 个节点，含人工审批闸门）
                .andExpect(jsonPath("$.data.draftVersionNo").value(1))
                .andExpect(jsonPath("$.data.nodes.length()").value(6))
                .andReturn().getResponse().getContentAsString();
        String workflowId = JsonPath.read(created, "$.data.id");

        // 模板是脚手架：kbIds 刻意留空，所以结构完整但校验会明确报缺什么
        mockMvc.perform(post("/api/workflows/" + workflowId + "/validate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.errors[0]").value(
                        org.hamcrest.Matchers.containsString("kbIds")));

        mockMvc.perform(post("/api/workflows/presets/not-a-preset")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    private void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void publishWorkflow(String token, String workflowId) throws Exception {
        mockMvc.perform(post("/api/workflows/" + workflowId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    /** 运行历史按 id 倒序，返回的 id 列表第一项是最新一次运行。 */
    private List<String> runIds(String token, String workflowId) throws Exception {
        String list = mockMvc.perform(get("/api/workflow-runs")
                        .param("workflowId", workflowId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(list, "$.data.list[*].id");
        return ids;
    }

    /** START → HUMAN_APPROVAL → TEMPLATE → END：闸门之后有可跑通的节点，便于验证恢复。 */
    private String createApprovalWorkflow(String token, String name) throws Exception {
        String workflowId = createWorkflow(token, name);
        saveGraph(token, workflowId, """
                {"nodes":[
                  {"id":"start","type":"START","version":1,"config":{},"position":{"x":0,"y":0}},
                  {"id":"gate","type":"HUMAN_APPROVAL","version":1,"config":{},"position":{"x":150,"y":0}},
                  {"id":"tpl","type":"TEMPLATE","version":1,"config":{"template":"approved"},
                   "position":{"x":300,"y":0}},
                  {"id":"end","type":"END","version":1,"config":{},"position":{"x":450,"y":0}}
                ],
                "edges":[
                  {"id":"e1","source":"start","target":"gate","sourceHandle":null},
                  {"id":"e2","source":"gate","target":"tpl","sourceHandle":null},
                  {"id":"e3","source":"tpl","target":"end","sourceHandle":null}
                ]}
                """);
        mockMvc.perform(post("/api/workflows/" + workflowId + "/validate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true));
        publishWorkflow(token, workflowId);
        return workflowId;
    }

    /** 跑一次，断言停在人工审批上，返回 runId。 */
    private String startWaitingRun(String token, String workflowId) throws Exception {
        String run = mockMvc.perform(post("/api/workflows/" + workflowId + "/runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_APPROVAL"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(run, "$.data.id");
    }

    private org.springframework.test.web.servlet.ResultActions debugRun(String token, String workflowId) throws Exception {
        return mockMvc.perform(post("/api/workflows/" + workflowId + "/debug")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inputs\":{\"question\":\"hello\"}}"));
    }

    private String createWorkflow(String token, String name) throws Exception {
        String created = mockMvc.perform(post("/api/workflows")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"description\":\"MVP\"}".formatted(name)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.data.id");
    }

    private void saveDraft(String token, String workflowId) throws Exception {
        saveGraph(token, workflowId, """
                {"nodes":[
                  {"id":"start","type":"START","version":1,"config":{},
                   "position":{"x":0,"y":0}},
                  {"id":"end","type":"END","version":1,"config":{},
                   "position":{"x":200,"y":0}}
                ],
                "edges":[
                  {"id":"edge-1","source":"start","target":"end","sourceHandle":null}
                ]}
                """);
    }

    private void saveGraph(String token, String workflowId, String graphJson) throws Exception {
        mockMvc.perform(put("/api/workflows/" + workflowId + "/draft")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphJson))
                .andExpect(status().isOk());
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
}
