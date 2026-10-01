package com.lky.nexus.app.agent;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.ai.agent.AgentDefinition;
import com.lky.nexus.app.agent.mapper.AgentMapper;
import com.lky.nexus.app.agent.model.entity.AgentPO;
import com.lky.nexus.app.workspace.WorkspaceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent 资源的 HTTP 契约（Phase 4.1，离线部分）。
 *
 * <p>为什么这几条必须在 HTTP 层钉住（服务层测过还不够）：
 * <ol>
 *   <li><b>状态机有没有后果</b>。DRAFT 不能被会话选用（5008）、DISABLED 不是"悄悄换回默认"
 *       而是明确报错（5009）——这类规则一旦退化成"静默降级"，用户看到的是"我选的 Agent 没生效"，
 *       而接口一切正常；</li>
 *   <li><b>越权是 404 而不是 403</b>。404 不泄露资源存在性，是本项目所有资源的一致约定，
 *       换个人写新接口时最容易破坏它；</li>
 *   <li><b>内置默认 Agent 不可删/不可停用</b>。它是 AGENT 模式的兜底：能停用之后，
 *       AGENT 模式会变成"有时候能用、有时候报错"，且用户不知道为什么。</li>
 * </ol>
 *
 * <p><b>本类不覆盖</b>：真实跑一次 Agent（需要模型，见 {@code AgentLoopTest} 的
 * {@code AI_CHAT_API_KEY} 门控）与前端界面（见 web 侧脚本）。这里只谈"配置的写入与准入"。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Agent 资源：CRUD 与准入规则")
class AgentResourceTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentService agentService;

    /** 存量升级那条要用它直接造"老行"——只有绕开服务层才能造出 4.4 之前的播种文本。 */
    @Autowired
    private AgentMapper agentMapper;

    @Autowired
    private WorkspaceService workspaceService;

    @Test
    @DisplayName("创建：落 DRAFT、非内置，知识库绑定随详情返回")
    void createLandsAsDraftWithBindings() throws Exception {
        String token = registerAndLogin("agent-create");
        String kbId = createKnowledgeBase(token, "Agent 绑库");

        String created = mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"note-helper","name":"笔记助手","systemPrompt":"只依据资料回答",
                                 "kbIds":[%s]}
                                """.formatted(kbId)))
                .andExpect(status().isOk())
                // 不自动发布是刻意的：§十八 要求"先测试再发布"，DRAFT 就是那个"先"
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.builtin").value(false))
                .andExpect(jsonPath("$.data.kbIds[0]").value(kbId))
                .andReturn().getResponse().getContentAsString();
        String agentId = JsonPath.read(created, "$.data.id");

        mockMvc.perform(get("/api/agents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                // 内置项置顶：它是 AGENT 模式的兜底，不该被埋在列表第二页
                .andExpect(jsonPath("$.data.list[0].code").value(AgentPrompts.BUILTIN_CODE))
                .andExpect(jsonPath("$.data.list[0].builtin").value(true))
                .andExpect(jsonPath("$.data.list[1].code").value("note-helper"));

        mockMvc.perform(get("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.systemPrompt").value("只依据资料回答"));
    }

    @Test
    @DisplayName("创建：标识重复 5007，标识非法 1001，模型角色未配置 4006")
    void createRejectsBadInput() throws Exception {
        String token = registerAndLogin("agent-create-bad");
        createAgent(token, "dup-code", "第一次");

        mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"dup-code","name":"第二次"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5007));

        // 大写、空格都不允许：code 是被 Tool/Workflow 引用的稳定标识，不是显示名
        mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"Bad Code","name":"非法标识"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"bad-role","name":"角色不存在","modelRole":"chat.nonexistent"}
                                """))
                // 在写入口就拦住：存下一个不存在的角色，用户要到提问时才知道自己填错了
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(4006));
    }

    @Test
    @DisplayName("创建：绑定别人的知识库被拒（3001），不留「绑定成功但检索为空」的状态")
    void createRejectsForeignKnowledgeBase() throws Exception {
        String owner = registerAndLogin("agent-kb-owner");
        String intruder = registerAndLogin("agent-kb-intruder");
        String kbId = createKnowledgeBase(owner, "别人的库");

        mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"steal-kb","name":"越权绑库","kbIds":[%s]}
                                """.formatted(kbId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3001));
    }

    @Test
    @DisplayName("更新：置为 PUBLISHED 后会话才能选用；未发布时 requireDefinition 报 5008")
    void updatePublishesTheAgent() throws Exception {
        String token = registerAndLogin("agent-publish");
        Long userId = currentUserId(token);
        String agentId = createAgent(token, "publish-me", "待发布");

        // 草稿态：不可用于对话（这是"先测试再发布"的另一半——没发布就真的用不了）
        assertThat(throwableCode(() -> agentService.requireDefinition(userId, Long.valueOf(agentId))))
                .isEqualTo(5008);

        mockMvc.perform(put("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"PUBLISHED","name":"已发布"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.name").value("已发布"));

        assertThat(agentService.requireDefinition(userId, Long.valueOf(agentId)).name()).isEqualTo("已发布");

        // 停用之后必须明确报错，而不是"悄悄换回默认"——否则用户不知道自己的 Agent 为什么没生效
        mockMvc.perform(put("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
        assertThat(throwableCode(() -> agentService.requireDefinition(userId, Long.valueOf(agentId))))
                .isEqualTo(5009);
    }

    @Test
    @DisplayName("更新：状态值非法 1001；解绑知识库用空列表")
    void updateValidatesStatusAndUnbinds() throws Exception {
        String token = registerAndLogin("agent-update");
        String kbId = createKnowledgeBase(token, "解绑前的库");
        String agentId = createAgentWithKb(token, "unbind-me", "解绑测试", kbId);

        mockMvc.perform(put("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ARCHIVED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        mockMvc.perform(put("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kbIds":[]}
                                """))
                .andExpect(status().isOk())
                // 空列表 = 解绑（回到"不限制"）。若实现里漏写这句，
                // updateById 会跳过 null 字段，解绑静默失效——所以这条断言是必要的
                .andExpect(jsonPath("$.data.kbIds").isEmpty());
    }

    @Test
    @DisplayName("复制：新 code 带 -copy、副本回到 DRAFT、绑定一并复制")
    void duplicateCreatesDraftCopyWithBindings() throws Exception {
        String token = registerAndLogin("agent-duplicate");
        String kbId = createKnowledgeBase(token, "复制携带的库");
        String agentId = createAgentWithKb(token, "source-agent", "源 Agent", kbId);
        mockMvc.perform(put("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"PUBLISHED"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/agents/" + agentId + "/duplicate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("source-agent-copy"))
                .andExpect(jsonPath("$.data.name").value("源 Agent 副本"))
                // 复制品未经测试，不该直接生效
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.builtin").value(false))
                .andExpect(jsonPath("$.data.kbIds[0]").value(kbId));
    }

    @Test
    @DisplayName("删除：自己的可删（之后 404），内置的报 5010")
    void deleteOwnButNotBuiltin() throws Exception {
        String token = registerAndLogin("agent-delete");
        Long userId = currentUserId(token);
        String agentId = createAgent(token, "delete-me", "待删除");

        mockMvc.perform(delete("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(5006));

        AgentDefinition builtin = agentService.resolveDefault(userId);
        mockMvc.perform(delete("/api/agents/" + builtin.agentId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5010));
        // 停用同样被拒：让内置项能停用，AGENT 模式就会变成"有时候报错、有时候不报"
        mockMvc.perform(put("/api/agents/" + builtin.agentId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5010));
        // 但改名/改提示词是允许的：默认助手要能被用户调教（这正是"不硬编码 Agent"的落点）
        mockMvc.perform(put("/api/agents/" + builtin.agentId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"我的助手","systemPrompt":"只回答笔记里的内容"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.systemPrompt").value("只回答笔记里的内容"));
    }

    @Test
    @DisplayName("隔离：访问别人的 Agent 一律 404（不泄露存在性），同名 code 互不冲突")
    void ownershipIsolation() throws Exception {
        String owner = registerAndLogin("agent-owner");
        String other = registerAndLogin("agent-other");
        String agentId = createAgent(owner, "shared-code", "主人的 Agent");
        // 另一个用户可以取相同的 code：唯一性按 owner 划分，不是全局唯一
        createAgent(other, "shared-code", "别人的同名 Agent");

        mockMvc.perform(get("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(5006));

        mockMvc.perform(put("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"偷改"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(5006));

        mockMvc.perform(delete("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(5006));
    }

    @Test
    @DisplayName("列表：从没跑过 Agent 的新用户也能看见内置默认 Agent（它按需创建，不是注册时预置）")
    void freshUserSeesBuiltinInList() throws Exception {
        String token = registerAndLogin("agent-fresh");

        // 真机发现的坑：内置项只在"执行路径"里按需补齐，于是新用户打开列表是空的，
        // 界面上看不出 AGENT 模式有兜底。列表本身必须补齐它。
        mockMvc.perform(get("/api/agents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].code").value(AgentPrompts.BUILTIN_CODE))
                .andExpect(jsonPath("$.data.list[0].builtin").value(true))
                .andExpect(jsonPath("$.data.list[0].status").value("PUBLISHED"));
    }

    @Test
    @DisplayName("内置默认 Agent 是幂等的真实一行（同名重复解析不会造出第二条）")
    void resolveDefaultIsIdempotent() throws Exception {
        String token = registerAndLogin("agent-default");
        Long userId = currentUserId(token);

        AgentDefinition first = agentService.resolveDefault(userId);
        AgentDefinition second = agentService.resolveDefault(userId);

        assertThat(second.agentId()).isEqualTo(first.agentId());
        assertThat(first.builtin()).isTrue();
        assertThat(first.code()).isEqualTo(AgentPrompts.BUILTIN_CODE);
        assertThat(first.systemPrompt()).isEqualTo(AgentPrompts.AGENT_SYSTEM);
        // 内置项默认不限制检索范围（AgentDefinition 的 kbIds 为空即"全部知识库"）
        assertThat(first.scoped()).isFalse();
    }

    @Test
    @DisplayName("创建：不传回答策略时落默认 KB_PREFERRED（新用户第一次提问不该被拒）")
    void createDefaultsToKbPreferred() throws Exception {
        String token = registerAndLogin("agent-policy-default");

        mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"policy-default","name":"没写策略"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answerPolicy").value("KB_PREFERRED"));

        // 内置默认 Agent 也一样：AGENT 模式的兜底不该是"检索不到就拒答"
        mockMvc.perform(get("/api/agents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].answerPolicy").value("KB_PREFERRED"));
    }

    @Test
    @DisplayName("改回答策略：KB_ONLY 能存能回读，非法值在写入口报 1001")
    void answerPolicyIsUpdatableAndValidated() throws Exception {
        String token = registerAndLogin("agent-policy-update");
        String agentId = createAgent(token, "policy-update", "可改策略");

        mockMvc.perform(put("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answerPolicy":"KB_ONLY"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answerPolicy").value("KB_ONLY"));

        // 回读一次：确认是落库了，而不是只在这一次响应里漂亮
        mockMvc.perform(get("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answerPolicy").value("KB_ONLY"));

        // 非法值不落到库里：写入口就报，而不是存下一个没人认识的值
        mockMvc.perform(put("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answerPolicy":"FREE_STYLE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        mockMvc.perform(get("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.answerPolicy").value("KB_ONLY"));
    }

    @Test
    @DisplayName("复制：回答策略跟着复制（少复制一样会得到一个行为不同的副本，还不报错）")
    void duplicateCopiesAnswerPolicy() throws Exception {
        String token = registerAndLogin("agent-policy-copy");
        String agentId = createAgent(token, "policy-copy", "原样");

        mockMvc.perform(put("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answerPolicy":"KB_ONLY"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/agents/" + agentId + "/duplicate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answerPolicy").value("KB_ONLY"));
    }

    @Test
    @DisplayName("存量升级：只升「还是机器播种的那份」提示词，用户改过的一个字都不动")
    void resolveDefaultUpgradesOnlyUntouchedLegacyPrompt() throws Exception {
        // 两个用户各造一行内置：一个留着 4.4 之前的播种文本，一个被用户改过。
        // 必须分两个用户——resolveDefault 按 code 取一行，同一个用户造两行会取到哪一个不确定
        Long legacyUser = currentUserId(registerAndLogin("agent-legacy-untouched"));
        Long editedUser = currentUserId(registerAndLogin("agent-legacy-edited"));
        AgentPO legacy = insertBuiltin(legacyUser, AgentPrompts.AGENT_SYSTEM_LEGACY);
        AgentPO edited = insertBuiltin(editedUser, "我自己的严格提示词：只依据资料回答");

        AgentDefinition upgraded = agentService.resolveDefault(legacyUser);
        agentService.resolveDefault(legacyUser);   // 幂等：再来一次不该有变化
        agentService.resolveDefault(editedUser);

        // 旧播种文本里写着"不要用自身知识回答"，与默认策略 KB_PREFERRED 矛盾，就地升级
        assertThat(upgraded.systemPrompt()).isEqualTo(AgentPrompts.AGENT_SYSTEM);
        assertThat(agentMapper.selectById(legacy.getId()).getSystemPrompt())
                .isEqualTo(AgentPrompts.AGENT_SYSTEM);
        // 但用户自己写的提示词绝不被机器覆盖——策略块里有让位条款顶着（见 decisions.md B.41）
        assertThat(agentMapper.selectById(edited.getId()).getSystemPrompt())
                .isEqualTo("我自己的严格提示词：只依据资料回答");
    }

    /** 直接造一行内置 Agent（绕过服务层）：只有这样才能造出"4.4 之前播种的那份文本"。 */
    private AgentPO insertBuiltin(Long userId, String systemPrompt) {
        AgentPO agent = new AgentPO();
        agent.setWorkspaceId(workspaceService.requirePersonalWorkspace(userId));
        agent.setOwnerId(userId);
        agent.setCode(AgentPrompts.BUILTIN_CODE);
        agent.setName(AgentPrompts.BUILTIN_NAME);
        agent.setSystemPrompt(systemPrompt);
        agent.setModelRole("chat.agent");
        agent.setAnswerPolicy("KB_PREFERRED");
        agent.setStatus("PUBLISHED");
        agent.setBuiltin(true);
        LocalDateTime now = LocalDateTime.now();
        agent.setCreatedAt(now);
        agent.setUpdatedAt(now);
        agentMapper.insert(agent);
        return agent;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private String createAgent(String token, String code, String name) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"%s"}
                                """.formatted(code, name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id");
    }

    private String createAgentWithKb(String token, String code, String name, String kbId) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"%s","kbIds":[%s]}
                                """.formatted(code, name, kbId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id");
    }

    private String createKnowledgeBase(String token, String name) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id");
    }

    private Long currentUserId(String token) throws Exception {
        return Long.valueOf(JsonPath.read(mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id"));
    }

    /** 取出业务异常的错误码；没有抛异常则返回 0（让断言以"0 != 期望码"失败，而不是空指针）。 */
    private int throwableCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        try {
            action.call();
            return 0;
        } catch (Throwable ex) {
            return ex instanceof com.lky.nexus.common.exception.BusinessException business
                    ? business.getCode()
                    : -1;
        }
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, PASSWORD)));

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
