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
        mockMvc.perform(put("/api/workflows/" + workflowId + "/draft")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nodes":[
                                  {"id":"start","type":"START","version":1,"config":{},
                                   "position":{"x":0,"y":0}},
                                  {"id":"end","type":"END","version":1,"config":{},
                                   "position":{"x":200,"y":0}}
                                ],
                                "edges":[
                                  {"id":"edge-1","source":"start","target":"end","sourceHandle":null}
                                ]}
                                """))
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
