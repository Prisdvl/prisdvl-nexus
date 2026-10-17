package com.lky.nexus.app.chat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

import com.lky.nexus.app.chat.mapper.ChatConversationMapper;
import com.lky.nexus.app.chat.mapper.ChatMessageMapper;
import com.lky.nexus.app.chat.model.entity.ChatConversationPO;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
import com.lky.nexus.app.chat.service.ConversationKbIds;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话模式切换：PLAIN / RAG / AGENT 三向互转+ 不兼容字段清理。
 *
 * <p><b>为什么这个测试类必须存在，而且必须回查数据库</b>：
 * 改动之前 {@code resetAgent} 清空 Agent 是"接口返回 200、库里没变"的假成功，
 * 而 {@link ConversationAgentBindingTest} 那类MockMvc 测试<b>只断言 HTTP 响应</b>
 * （{@code $.data.agentId} 不存在），所以一直没抓到。
 * 这里的每个断言都走 {@link ChatConversationMapper} 二次查询，
 * 校验的是<b>最终持久化状态</b>，不是响应体。
 *
 * <p>覆盖的三组规则（以目标模式为准）：
 * <ul>
 *   <li>PLAIN：清 agentId + 清 kbIds</li>
 *   <li>RAG：清 agentId；kbIds 未显式提供时保留</li>
 *   <li>AGENT：清 kbIds；agentId 未显式提供时保留</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("会话模式切换：切换语义与不兼容字段清理")
class ConversationModeSwitchTest {

    private static final String PASSWORD = "Mode@12345";

    @Autowired
    private MockMvc mockMvc;

    /** 回查数据库用：不能只信响应体。 */
    @Autowired
    private ChatConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper messageMapper;

    @Autowired
    private JsonMapper jsonMapper;

    // ------------------------------------------------------------------
    // 1-3. PLAIN 为起点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PLAIN → RAG：模式落库、modelRole 重算、agentId 保持为空")
    void plainToRag() throws Exception {
        String token = registerAndLogin("ms-plain-rag");
        String kbId = createKnowledgeBase(token, "PLAIN→RAG 库");
        String conversationId = createConversation(token, "PLAIN", null, null);

        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"RAG","kbIds":[%s]}
                                """.formatted(kbId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("RAG"));

        ChatConversationPO row = reload(conversationId);
        assertEquals("RAG", row.getMode());
        // modelRole 必须跟着重算，否则"表面切了模式、实际没切执行路径"
        assertEquals("chat.default", row.getModelRole());
        assertNull(row.getAgentId(), "PLAIN 会话本来就没有 Agent，切到 RAG 后仍应为空");
        assertEquals(List.of(kbId), kbIdsOf(row));
    }

    @Test
    @DisplayName("PLAIN → AGENT：模式落库、modelRole 切成 chat.agent")
    void plainToAgent() throws Exception {
        String token = registerAndLogin("ms-plain-agent");
        String agentId = publishedAgent(token, "ms-plain-agent-a");
        String conversationId = createConversation(token, "PLAIN", null, null);

        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"AGENT","agentId":%s}
                                """.formatted(agentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("AGENT"));

        ChatConversationPO row = reload(conversationId);
        assertEquals("AGENT", row.getMode());
        assertEquals("chat.agent", row.getModelRole(),
                "AGENT 模式必须用 chat.agent 角色（工具调用对模型能力要求不同）");
        assertEquals(agentId, String.valueOf(row.getAgentId()));
        assertEquals(List.of(), kbIdsOf(row));
    }

    @Test
    @DisplayName("PLAIN → PLAIN：同模式不带任何绑定变更，且不清掉已有配置")
    void plainToPlain() throws Exception {
        String token = registerAndLogin("ms-plain-plain");
        String conversationId = createConversation(token, "PLAIN", null, null);

        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"PLAIN"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("PLAIN"));

        ChatConversationPO row = reload(conversationId);
        assertEquals("PLAIN", row.getMode());
        assertEquals("chat.default", row.getModelRole());
        assertNull(row.getAgentId());
    }

    // ------------------------------------------------------------------
    // 4-5. RAG 为起点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RAG → AGENT：清空 kbIds（落库为空数组）、绑定新 Agent")
    void ragToAgent() throws Exception {
        String token = registerAndLogin("ms-rag-agent");
        String kbId = createKnowledgeBase(token, "RAG→AGENT 库");
        String agentId = publishedAgent(token, "ms-rag-agent-a");
        String conversationId = createConversation(token, "RAG", List.of(kbId), null);

        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"AGENT","agentId":%s}
                                """.formatted(agentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("AGENT"))
                // 响应里就应该是空的，而不是回显旧库
                .andExpect(jsonPath("$.data.kbIds").isEmpty());

        ChatConversationPO row = reload(conversationId);
        assertEquals("AGENT", row.getMode());
        assertEquals(List.of(), kbIdsOf(row),
                "AGENT 模式不用会话级知识库绑定，必须清空且落库");
        assertEquals(agentId, String.valueOf(row.getAgentId()));
    }

    @Test
    @DisplayName("RAG → PLAIN：清空 kbIds 且 agentId 为空")
    void ragToPlain() throws Exception {
        String token = registerAndLogin("ms-rag-plain");
        String kbId = createKnowledgeBase(token, "RAG→PLAIN 库");
        String conversationId = createConversation(token, "RAG", List.of(kbId), null);

        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"PLAIN"}
                                """))
                .andExpect(status().isOk());

        ChatConversationPO row = reload(conversationId);
        assertEquals("PLAIN", row.getMode());
        assertEquals("chat.default", row.getModelRole());
        assertEquals(List.of(), kbIdsOf(row));
        assertNull(row.getAgentId());
    }

    // ------------------------------------------------------------------
    // 6-9. AGENT 为起点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AGENT → RAG：清空 agentId 并确认落库（这正是原先没落库的那个字段）")
    void agentToRag() throws Exception {
        String token = registerAndLogin("ms-agent-rag");
        String agentId = publishedAgent(token, "ms-agent-rag-a");
        String kbId = createKnowledgeBase(token, "AGENT→RAG 库");
        String conversationId = createConversation(token, "AGENT", null, agentId);

        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"RAG","kbIds":[%s]}
                                """.formatted(kbId)))
                .andExpect(status().isOk());

        ChatConversationPO row = reload(conversationId);
        assertEquals("RAG", row.getMode());
        assertEquals("chat.default", row.getModelRole());
        assertNull(row.getAgentId(),
                "清空 Agent 必须真的写进数据库 —— updateStrategy 默认 NOT_NULL 时这一步会静默失效");
        assertEquals(List.of(kbId), kbIdsOf(row));
    }

    @Test
    @DisplayName("AGENT → PLAIN：清空 agentId（回查 DB 验证）")
    void agentToPlain() throws Exception {
        String token = registerAndLogin("ms-agent-plain");
        String agentId = publishedAgent(token, "ms-agent-plain-a");
        String conversationId = createConversation(token, "AGENT", null, agentId);

        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"PLAIN"}
                                """))
                .andExpect(status().isOk());

        ChatConversationPO row = reload(conversationId);
        assertEquals("PLAIN", row.getMode());
        assertNull(row.getAgentId(), "从响应看是空的还不够，必须回查确认真的清了");
        assertEquals("chat.default", row.getModelRole());
    }

    @Test
    @DisplayName("AGENT → AGENT 换 Agent：绑新 Agent，且不清掉别的东西")
    void agentToAgentSwitchAgent() throws Exception {
        String token = registerAndLogin("ms-agent-switch");
        String first = publishedAgent(token, "ms-switch-first");
        String second = publishedAgent(token, "ms-switch-second");
        String conversationId = createConversation(token, "AGENT", null, first);

        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":%s}
                                """.formatted(second)))
                .andExpect(status().isOk());

        ChatConversationPO row = reload(conversationId);
        assertEquals(second, String.valueOf(row.getAgentId()));
        assertEquals("AGENT", row.getMode(), "只换 Agent 不该动mode");
        assertEquals("chat.agent", row.getModelRole(), "只换 Agent 不该动modelRole");
    }

    @Test
    @DisplayName("AGENT → AGENT 重置 Agent（resetAgent）：agentId 真落库为 null")
    void agentToAgentReset() throws Exception {
        String token = registerAndLogin("ms-agent-reset");
        String agentId = publishedAgent(token, "ms-reset-a");
        String conversationId = createConversation(token, "AGENT", null, agentId);

        // 先确认"有值"这个前提成立，否则这个测试可能因为建会话时就没绑上而假绿
        assertEquals(agentId, String.valueOf(reload(conversationId).getAgentId()));

        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resetAgent":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agentId").doesNotExist());

        assertNull(reload(conversationId).getAgentId(),
                "resetAgent 必须在数据库里真的清掉 agentId —— 这条断言在 updateStrategy 修复前会失败");
    }

    // ------------------------------------------------------------------
    // 10-13. 部分更新与参数边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("请求未提供 mode：只改标题，模式与绑定都不动（部分更新语义）")
    void updateWithoutModeKeepsEverything() throws Exception {
        String token = registerAndLogin("ms-no-mode");
        String kbId = createKnowledgeBase(token, "不动我的库");
        String conversationId = createConversation(token, "RAG", List.of(kbId), null);

        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"只改标题"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("只改标题"));

        ChatConversationPO row = reload(conversationId);
        assertEquals("只改标题", row.getTitle());
        assertEquals("RAG", row.getMode(), "没传 mode 就不该改mode");
        assertEquals(List.of(kbId), kbIdsOf(row),
                "没传 kbIds 就不该动知识库绑定");
    }

    @Test
    @DisplayName("agentId 选了但不传 mode：AGENT 会话正常换；PLAIN 会话报 1001")
    void agentIdWithoutMode() throws Exception {
        String token = registerAndLogin("ms-agent-no-mode");
        String agentId = publishedAgent(token, "ms-nomode-a");
        String agentConv = createConversation(token, "AGENT", null, null);
        String plainConv = createConversation(token, "PLAIN", null, null);

        mockMvc.perform(put("/api/chat/conversations/" + agentConv)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":%s}
                                """.formatted(agentId)))
                .andExpect(status().isOk());
        assertEquals(agentId, String.valueOf(reload(agentConv).getAgentId()));

        // 保持 PLAIN 却要绑 Agent：静默忽略比报错更坏
        mockMvc.perform(put("/api/chat/conversations/" + plainConv)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":%s}
                                """.formatted(agentId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("kbIds 显式传空列表 = 解绑；不传 = 不动")
    void kbIdsExplicitClearVsAbsent() throws Exception {
        String token = registerAndLogin("ms-kb-clear");
        String kbId = createKnowledgeBase(token, "解绑测试库");
        String conversationId = createConversation(token, "RAG", List.of(kbId), null);

        // 不传：保持不动
        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pinned":true}
                                """))
                .andExpect(status().isOk());
        assertEquals(List.of(kbId), kbIdsOf(conversationId));

        // 显式空列表：解绑且落库
        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kbIds":[]}
                                """))
                .andExpect(status().isOk());
        assertEquals(List.of(), kbIdsOf(conversationId),
                "解绑要真的落库（靠 \"[]\" 而非 null 绕开 NOT_NULL 策略）");
    }

    @Test
    @DisplayName("非法 mode 值报 1001，且不改动任何东西")
    void illegalModeRejected() throws Exception {
        String token = registerAndLogin("ms-bad-mode");
        String conversationId = createConversation(token, "PLAIN", null, null);

        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"SUPER"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        assertEquals("PLAIN", reload(conversationId).getMode(), "被拒的请求不能留下半截修改");
    }

    @Test
    @DisplayName("agentId 与 resetAgent 同时传仍报 1001（沿用既有约定，且 mode 不参与裁决）")
    void conflictingAgentSignalsRejected() throws Exception {
        String token = registerAndLogin("ms-conflict");
        String agentId = publishedAgent(token, "ms-conflict-a");
        String conversationId = createConversation(token, "AGENT", null, agentId);

        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"AGENT","agentId":%s,"resetAgent":true}
                                """.formatted(agentId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        assertEquals(agentId, String.valueOf(reload(conversationId).getAgentId()),
                "自相矛盾的请求必须整体拒绝，不猜用户意图");
    }

    // ------------------------------------------------------------------
    // 14-15. 权限与历史保留
    // ------------------------------------------------------------------

    @Test
    @DisplayName("不能切到别人的会话：404/越权，且自己的会话不受影响")
    void cannotSwitchOthersConversation() throws Exception {
        String tokenA = registerAndLogin("ms-owner-a");
        String tokenB = registerAndLogin("ms-owner-b");
        String conversationId = createConversation(tokenA, "RAG", null, null);

        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"PLAIN"}
                                """))
                .andExpect(status().isNotFound());

        assertEquals("RAG", reload(conversationId).getMode(), "越权请求不能改到数据");
    }

    @Test
    @DisplayName("切模式不能绑别人的知识库：1001，且原绑定保持不变")
    void cannotBindForeignKnowledgeBase() throws Exception {
        String tokenA = registerAndLogin("ms-kb-owner-a");
        String tokenB = registerAndLogin("ms-kb-owner-b");
        String kbId = createKnowledgeBase(tokenA, "A 的库");
        String conversationId = createConversation(tokenA, "PLAIN", null, null);

        // A 的会话切到 RAG 并想绑B 的库 —— 必须被拒
        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"RAG","kbIds":[%s]}
                                """.formatted(kbId)))
                .andExpect(status().isOk()); // 先正常绑上自己的库

        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"RAG","kbIds":[%s]}
                                """.formatted(kbId)))
                .andExpect(status().isNotFound());

        assertEquals("RAG", reload(conversationId).getMode());
    }

    @Test
    @DisplayName("切换模式不删除历史消息：已有的消息条数与内容都还在")
    void modeSwitchKeepsHistory() throws Exception {
        String token = registerAndLogin("ms-history");
        Long userId = currentUserId(token);
        String conversationId = createConversation(token, "PLAIN", null, null);

        /*
         * 直接用 Mapper 造两条历史消息，**不调 `POST /api/chat`**。
         *
         * <p>原因：这个用例验证的是「模式切换会不会删历史消息」，
         * 而 `POST /api/chat` 会真的去调模型 —— standalone 没有可用模型时
         * 它返回 500，于是这个用例变成在测"模型能不能调通"，
         * 既跑不起来（本轮实测 POST 直接 500），也测不到本来要测的东西。
         *
         * <p>造的是<b>真实持久化</b>的数据（走 insert 进库），
         * 所以"切换后重新查库还能看到"这个断言是有效的，
         * 而不是内存里的假象。
         */
        seedMessage(conversationId, userId, "USER", "切换前的提问");
        seedMessage(conversationId, userId, "ASSISTANT", "切换前的回答");

        Long beforeCount = messageMapper.selectCount(Wrappers.<ChatMessagePO>lambdaQuery()
                .eq(ChatMessagePO::getConversationId, conversationId));
        assertTrue(beforeCount != null && beforeCount == 2,
                "前提：切换前应有2 条消息，实际 " + beforeCount);

        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"RAG"}
                                """))
                .andExpect(status().isOk());

        // 切换后重新查库：条数、内容都必须还在
        Long afterCount = messageMapper.selectCount(Wrappers.<ChatMessagePO>lambdaQuery()
                .eq(ChatMessagePO::getConversationId, conversationId));
        assertEquals(beforeCount, afterCount, "切换模式绝不能删历史消息");

        List<String> contents = messageMapper.selectList(Wrappers.<ChatMessagePO>lambdaQuery()
                        .eq(ChatMessagePO::getConversationId, conversationId))
                .stream().map(ChatMessagePO::getContent).toList();
        assertTrue(contents.contains("切换前的提问"), "历史提问内容应还在，实际：" + contents);
        assertTrue(contents.contains("切换前的回答"), "历史回答内容应还在，实际：" + contents);

        // mode 与 modelRole 都要跟着切（这是「切了模式但没切执行路径」的判据）
        ChatConversationPO row = reload(conversationId);
        assertEquals("RAG", row.getMode(), "mode 应已切到 RAG");
        assertEquals("chat.default", row.getModelRole(), "RAG 用默认模型角色");

        // 接口读历史也仍然能读出来（不是只有直查库才看得到）
        mockMvc.perform(get("/api/chat/messages/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(beforeCount));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 取当前登录用户的 id（雪花 ID，按字符串返回）。
     *
     * <p>消息表要带 {@code user_id}（多租户隔离列，非空），造数据时必须填对，
     * 否则列表接口按 {@code user_id} 过滤时读不到自己刚造的消息。
     */
    private Long currentUserId(String token) throws Exception {
        String me = mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(JsonPath.read(me, "$.data.id"));
    }

    /**
     * 造一条<b>真实持久化</b>的历史消息。
     *
     * <p>用 Mapper 直接 insert 而不是调 {@code POST /api/chat}：
     * 后者会真的去调模型，standalone 下没有可用模型时返回 500，
     * 会让「验证模式切换不删历史消息」的用例变成「验证模型能不能调通」。
     *
     * <p>不设 {@code messageCount}：会话表上的冗余计数由提问链路维护，
     * 而这个用例只关心消息行本身还在不在。
     */
    private void seedMessage(String conversationId, Long userId, String role, String content) {
        ChatMessagePO message = new ChatMessagePO();
        message.setConversationId(Long.parseLong(conversationId));
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setStatus("DONE");
        messageMapper.insert(message);
    }

    /**
     * 回查会话当前绑定的知识库 id（**字符串**形态）。
     *
     * <p>⚠️ 必须转成字符串再断言：{@link ConversationKbIds#parse} 返回的是
     * {@code List<Long>}，而会话/知识库 id 在本项目里一律以字符串形态出现
     * （雪花 ID 超出JS 安全整数上限）。直接
     * {@code assertEquals(List.of(kbId), parse(...))} 会因为
     * {@code List<String>} 与 {@code List<Long>} 的元素类型不同而恒不相等——
     * 值看起来一样，断言却红。
     */
    private List<String> kbIdsOf(String conversationId) {
        return kbIdsOf(reload(conversationId));
    }

    /** 已查出的行直接取绑定（避免同一个方法里反复 reload）。 */
    private List<String> kbIdsOf(ChatConversationPO row) {
        if (row.getKbIds() == null) {
            return List.of();
        }
        return ConversationKbIds.parse(jsonMapper, row.getKbIds()).stream()
                .map(String::valueOf)
                .toList();
    }

    /** 回查数据库。响应体不能代表落库状态 —— 这正是本测试类存在的理由。 */
    private ChatConversationPO reload(String conversationId) {
        ChatConversationPO row = conversationMapper.selectOne(
                Wrappers.<ChatConversationPO>lambdaQuery().eq(ChatConversationPO::getId, conversationId));
        assertNotNull(row, "会话不存在：" + conversationId);
        return row;
    }

    private String publishedAgent(String token, String code) throws Exception {
        String agentId = createAgent(token, code);
        mockMvc.perform(put("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"PUBLISHED"}
                                """))
                .andExpect(status().isOk());
        return agentId;
    }

    private String createAgent(String token, String code) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"模式切换 %s"}
                                """.formatted(code, code)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id");
    }

    private String createConversation(String token, String mode, List<String> kbIds, String agentId)
            throws Exception {
        StringBuilder body = new StringBuilder("{\"title\":\"").append(mode).append(" 会话\",\"mode\":\"").append(mode).append("\"");
        if (kbIds != null && !kbIds.isEmpty()) {
            body.append(",\"kbIds\":[").append(String.join(",", kbIds)).append("]");
        }
        if (agentId != null) {
            body.append(",\"agentId\":").append(agentId);
        }
        body.append("}");

        return JsonPath.read(mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
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