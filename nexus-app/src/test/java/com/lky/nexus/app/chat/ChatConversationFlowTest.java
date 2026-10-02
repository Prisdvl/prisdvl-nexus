package com.lky.nexus.app.chat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.chat.mapper.ChatMessageMapper;
import com.lky.nexus.app.chat.model.MessageRole;
import com.lky.nexus.app.chat.model.MessageStatus;
import com.lky.nexus.app.chat.model.dto.AssistantReplyDraft;
import com.lky.nexus.app.chat.model.entity.ChatMessagePO;
import com.lky.nexus.app.chat.service.ChatMessageService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step 1.4 验收测试：会话与消息持久化。
 *
 * <p>覆盖点：
 * <ol>
 *   <li>会话 CRUD 闭环（建/列/详情/改名/删除）；</li>
 *   <li>mode 宽松解析（小写归一）+ 非法值 400/1001；</li>
 *   <li><b>多租户隔离</b>：B 用户访问 A 的会话得到 404 + 8001（而不是 403，避免泄露存在性）；</li>
 *   <li>消息按时间正序返回，会话冗余字段（messageCount / lastMessageAt / 自动标题）被正确维护；</li>
 *   <li><b>分页真的生效</b>——这条专门用来钉住 MyBatis-Plus 分页拦截器：
 *       没有注册拦截器时 selectPage 会返回全部数据且不报错，是最隐蔽的坑之一；</li>
 *   <li>删除会话时其消息被物理清理。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatConversationFlowTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Test
    @DisplayName("会话 CRUD 闭环")
    void conversationLifecycle() throws Exception {
        String token = registerAndLogin("conv-lifecycle");
        Long userId = currentUserId(token);

        String created = mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"我的第一个会话","mode":"PLAIN"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value("我的第一个会话"))
                .andExpect(jsonPath("$.data.mode").value("PLAIN"))
                // 雪花 ID 必须是字符串（ADR-017）
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.messageCount").value(0))
                .andReturn().getResponse().getContentAsString();
        Long conversationId = Long.valueOf(JsonPath.read(created, "$.data.id").toString());

        // 列表
        mockMvc.perform(get("/api/chat/conversations").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(String.valueOf(conversationId)));

        // 详情
        mockMvc.perform(get("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("我的第一个会话"));

        // 改名 + 置顶
        mockMvc.perform(put("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"改名后的会话","pinned":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("改名后的会话"))
                .andExpect(jsonPath("$.data.pinned").value(true));

        // 删除后不可见
        mockMvc.perform(delete("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(8001));
    }

    @Test
    @DisplayName("mode：小写归一化为大写，非法值返回 400 + 1001")
    void modeParsingIsLenientButValidated() throws Exception {
        String token = registerAndLogin("conv-mode");

        mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"rag"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("RAG"));

        // 不传 mode 用默认 PLAIN
        mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("PLAIN"));

        mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"NOT_A_MODE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("多租户隔离：别人的会话一律 404（不泄露存在性）")
    void conversationIsIsolatedBetweenUsers() throws Exception {
        String tokenA = registerAndLogin("conv-owner-a");
        String tokenB = registerAndLogin("conv-intruder-b");

        String created = mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"A 的私密会话"}
                                """))
                .andReturn().getResponse().getContentAsString();
        Long conversationId = Long.valueOf(JsonPath.read(created, "$.data.id").toString());

        mockMvc.perform(get("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(8001));

        mockMvc.perform(delete("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // B 的列表里看不到 A 的会话
        mockMvc.perform(get("/api/chat/conversations").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(jsonPath("$.data.total").value(0));

        // 消息同样不可见
        mockMvc.perform(get("/api/chat/messages/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("消息落库：时间正序、角色正确、会话计数与自动标题被维护")
    void messagesArePersistedAndConversationCountersUpdated() throws Exception {
        String token = registerAndLogin("conv-messages");
        Long userId = currentUserId(token);
        Long conversationId = createConversation(token, null);

        chatMessageService.appendUserMessage(userId, conversationId, "帮我解释一下 Redis 缓存击穿", "req-1");
        chatMessageService.appendAssistantMessage(userId, conversationId, null, AssistantReplyDraft.builder()
                .content("缓存击穿是指某个热点 key 过期瞬间大量请求打到数据库……")
                .model("deepseek-v4-flash")
                .provider("opencode")
                .tokens(120, 80, 200)
                .latency(1500, 300)
                .finishReason("stop")
                .requestId("req-1")
                .status(MessageStatus.DONE.name())
                .build());
        chatMessageService.appendUserMessage(userId, conversationId, "那互斥锁方案呢？", "req-2");

        String messages = mockMvc.perform(get("/api/chat/messages/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.list[0].role").value(MessageRole.USER.name()))
                .andExpect(jsonPath("$.data.list[1].role").value(MessageRole.ASSISTANT.name()))
                .andExpect(jsonPath("$.data.list[2].role").value(MessageRole.USER.name()))
                // Token 与延迟在 Phase 1 就落库，Phase 10 可直接复用
                .andExpect(jsonPath("$.data.list[1].totalTokens").value(200))
                .andExpect(jsonPath("$.data.list[1].firstTokenMs").value(300))
                .andReturn().getResponse().getContentAsString();

        // 时间正序：第一条是用户消息
        assertThat(JsonPath.read(messages, "$.data.list[0].content").toString())
                .contains("Redis 缓存击穿");

        // 会话冗余字段：messageCount=3，lastMessageAt 已写入，标题由首条用户消息自动生成
        mockMvc.perform(get("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.messageCount").value(3))
                .andExpect(jsonPath("$.data.lastMessageAt").isNotEmpty())
                .andExpect(jsonPath("$.data.title").value("帮我解释一下 Redis 缓存击穿"));
    }

    @Test
    @DisplayName("分页生效：25 条消息按每页 10 条分 3 页（没有分页拦截器时该断言会失败）")
    void messagePaginationIsEffective() throws Exception {
        String token = registerAndLogin("conv-paging");
        Long userId = currentUserId(token);
        Long conversationId = createConversation(token, "分页测试");

        for (int i = 1; i <= 25; i++) {
            chatMessageService.appendUserMessage(userId, conversationId, "第 " + i + " 条消息", "req-" + i);
        }

        mockMvc.perform(get("/api/chat/messages/" + conversationId + "?pageNum=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.total").value(25))
                .andExpect(jsonPath("$.data.pages").value(3))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.list.length()").value(10))
                .andExpect(jsonPath("$.data.list[0].content").value("第 1 条消息"));

        // 最后一页只剩 5 条 —— 若分页插件没生效，这里会返回 25 条
        mockMvc.perform(get("/api/chat/messages/" + conversationId + "?pageNum=3&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.list.length()").value(5))
                .andExpect(jsonPath("$.data.list[0].content").value("第 21 条消息"));

        // pageSize 超限被截断为 100（查询与响应必须用同一个规范化值）
        mockMvc.perform(get("/api/chat/messages/" + conversationId + "?pageNum=1&pageSize=99999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.pageSize").value(100))
                .andExpect(jsonPath("$.data.list.length()").value(25));
    }

    @Test
    @DisplayName("删除会话时其消息被物理清理")
    void deletingConversationRemovesItsMessages() throws Exception {
        String token = registerAndLogin("conv-cleanup");
        Long userId = currentUserId(token);
        Long conversationId = createConversation(token, "待删除");

        chatMessageService.appendUserMessage(userId, conversationId, "会被清理的消息", "req-x");
        assertThat(countMessages(conversationId)).isEqualTo(1);

        mockMvc.perform(delete("/api/chat/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(countMessages(conversationId)).as("消息应被物理删除").isZero();
    }

    // ------------------------------------------------------------------

    private String registerAndLogin(String username) throws Exception {
        // 显式断言注册结果：否则注册一旦失败（例如被参数校验拦下），
        // 失败会延迟表现为"登录 401"，排查成本高（这正是本次踩坑的教训）。
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("注册应成功(200)或用户名已存在(409)，实际 %d：%s",
                                result.getResponse().getStatus(),
                                result.getResponse().getContentAsString())
                        .isIn(200, 409));

        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(loginBody, "$.data.accessToken");
    }

    private Long currentUserId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return Long.valueOf(JsonPath.read(body, "$.data.id").toString());
    }

    private Long createConversation(String token, String title) throws Exception {
        String payload = title == null ? "{}" : """
                {"title":"%s"}
                """.formatted(title);
        String body = mockMvc.perform(post("/api/chat/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.valueOf(JsonPath.read(body, "$.data.id").toString());
    }

    private long countMessages(Long conversationId) {
        List<ChatMessagePO> messages = chatMessageMapper.selectList(
                Wrappers.<ChatMessagePO>lambdaQuery().eq(ChatMessagePO::getConversationId, conversationId));
        return messages.size();
    }
}
