package com.lky.nexus.app.chat;

import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.chat.model.dto.ChatMessageResponse;
import com.lky.nexus.app.chat.model.MessageStatus;
import com.lky.nexus.app.chat.service.ChatMessageService;
import com.lky.nexus.common.api.PageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 1.6 验收测试（真实 HTTP + 真实模型）：SSE 流式与停止生成。
 *
 * <p>为什么用真实 Tomcat + JDK {@link HttpClient} 而不是 MockMvc：
 * MockMvc 对异步 {@code SseEmitter} 的处理是模拟的，拿到的事件往往是残缺的；
 * 而 {@code BodyHandlers.ofLines()} 让我们能<b>逐行读取正在到达的流</b>，
 * 这正是"中途停止生成"这类测试所必需的。
 *
 * <p>未配置 {@code AI_CHAT_API_KEY} 时整体跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "AI_CHAT_API_KEY", matches = ".+",
        disabledReason = "未配置 AI_CHAT_API_KEY，跳过流式真实调用")
class ChatStreamLiveTest {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamLiveTest.class);
    private static final String PASSWORD = "Passw0rd!23";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ChatMessageService chatMessageService;

    @Test
    @DisplayName("完整流式：meta → delta* → usage → done，且助手消息以 DONE 落库")
    void streamingCompletesAndPersistsDoneMessage() throws Exception {
        Session session = newSession("stream-ok");
        Long conversationId = createConversation(session.token());

        StreamResult result = consumeStream(session, conversationId, "请用一句话说明什么是 Spring Boot 自动配置。", 0);

        assertThat(result.events()).as("事件顺序：meta 必须在 delta 之前")
                .startsWith("meta")
                .contains("delta", "usage", "done");
        assertThat(result.deltaText()).as("流式内容不应为空").isNotBlank();
        assertThat(result.errorData()).as("不应出现 error 事件").isNull();
        assertThat(result.doneData()).contains("\"finishReason\"");

        log.info("流式完成：分片数={}, 内容长度={}, usage={}", result.deltaCount(),
                result.deltaText().length(), result.usageData());

        // 落库校验：DONE、内容非空、且没有因为"先插 STREAMING 再更新"而重复计数
        PageResult<ChatMessageResponse> messages =
                chatMessageService.listMessages(session.userId(), conversationId, 1, 20);
        assertThat(messages.total()).as("一轮问答应为 2 条消息").isEqualTo(2L);
        ChatMessageResponse assistant = messages.list().get(1);
        assertThat(assistant.role()).isEqualTo("ASSISTANT");
        assertThat(assistant.status()).isEqualTo(MessageStatus.DONE.name());
        assertThat(assistant.content()).isNotBlank();
        assertThat(assistant.latencyMs()).isNotNull();
    }

    @Test
    @DisplayName("停止生成：收到若干分片后停止，上游被中断、部分内容以 STOPPED 落库")
    void stopInterruptsGenerationAndPersistsPartialContent() throws Exception {
        Session session = newSession("stream-stop");
        Long conversationId = createConversation(session.token());

        // 停在第 3 个分片：既保证已经有部分内容，又确保生成还没结束
        StreamResult result = consumeStream(session, conversationId,
                "请用大约 300 字比较 Redis 与 Memcached 的差异，尽量详细。", 3);

        assertThat(result.cancelled()).as("停止请求应命中正在进行的流").isTrue();
        assertThat(result.deltaCount()).as("停止前应已收到分片").isGreaterThanOrEqualTo(3);
        assertThat(result.deltaText()).isNotBlank();
        assertThat(result.doneData())
                .as("结束事件应标明是被停止的")
                .contains("\"stopped\":true");

        PageResult<ChatMessageResponse> messages =
                chatMessageService.listMessages(session.userId(), conversationId, 1, 20);
        assertThat(messages.total()).isEqualTo(2L);
        ChatMessageResponse assistant = messages.list().get(1);
        assertThat(assistant.status()).as("被停止的消息状态应为 STOPPED").isEqualTo(MessageStatus.STOPPED.name());
        assertThat(assistant.content()).as("已生成的部分内容必须保留").isNotBlank();
    }

    // ------------------------------------------------------------------
    // SSE 读取与辅助方法
    // ------------------------------------------------------------------

    /**
     * 逐行消费 SSE 流；{@code stopAfterDeltas > 0} 时在收到第 N 个分片后调用停止接口。
     */
    private StreamResult consumeStream(Session session, Long conversationId, String content, int stopAfterDeltas)
            throws Exception {
        HttpResponse<Stream<String>> response = httpClient.send(
                HttpRequest.newBuilder(uri("/api/chat/stream"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + session.token())
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"conversationId":%d,"content":"%s"}
                                """.formatted(conversationId, content), StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofLines());

        assertThat(response.statusCode()).as("SSE 连接应建立成功").isEqualTo(200);

        List<String> events = new ArrayList<>();
        StringBuilder deltaText = new StringBuilder();
        String metaData = null;
        String usageData = null;
        String doneData = null;
        String errorData = null;
        String requestId = null;
        boolean cancelled = false;
        int deltaCount = 0;

        try (Stream<String> lines = response.body()) {
            Iterator<String> iterator = lines.iterator();
            String currentEvent = null;
            while (iterator.hasNext()) {
                String line = iterator.next();
                if (line.startsWith("event:")) {
                    currentEvent = line.substring("event:".length()).trim();
                    events.add(currentEvent);
                } else if (line.startsWith("data:")) {
                    String data = line.substring("data:".length()).trim();
                    switch (currentEvent == null ? "" : currentEvent) {
                        case "meta" -> {
                            metaData = data;
                            requestId = JsonPath.read(data, "$.requestId");
                        }
                        case "delta" -> {
                            deltaCount++;
                            deltaText.append((String) JsonPath.read(data, "$.text"));
                            if (stopAfterDeltas > 0 && deltaCount == stopAfterDeltas) {
                                cancelled = stopStream(requestId, session.token());
                            }
                        }
                        case "usage" -> usageData = data;
                        case "done" -> doneData = data;
                        case "error" -> errorData = data;
                        default -> {
                            // 其它事件（retrieval / citation / tool_call 等）Phase 3+ 才会出现
                        }
                    }
                }
            }
        }

        assertThat(metaData).as("必须收到 meta 事件").isNotNull();
        return new StreamResult(events, deltaText.toString(), metaData, usageData, doneData, errorData,
                deltaCount, cancelled);
    }

    private boolean stopStream(String requestId, String token) throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(uri("/api/chat/stream/" + requestId + "/stop"))
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        return (boolean) JsonPath.read(response.body(), "$.data.cancelled");
    }

    private Session newSession(String username) throws Exception {
        post("/api/auth/register", """
                {"username":"%s","password":"%s"}
                """.formatted(username, PASSWORD), null);

        String loginBody = post("/api/auth/login", """
                {"username":"%s","password":"%s"}
                """.formatted(username, PASSWORD), null);
        String token = JsonPath.read(loginBody, "$.data.accessToken");
        Long userId = Long.valueOf(JsonPath.read(loginBody, "$.data.user.id").toString());
        return new Session(token, userId);
    }

    private Long createConversation(String token) throws Exception {
        String body = post("/api/chat/conversations", """
                {"title":"流式测试"}
                """, token);
        return Long.valueOf(JsonPath.read(body, "$.data.id").toString());
    }

    private String post(String path, String jsonBody, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return response.body();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private record Session(String token, Long userId) {
    }

    private record StreamResult(List<String> events,
                                String deltaText,
                                String metaData,
                                String usageData,
                                String doneData,
                                String errorData,
                                int deltaCount,
                                boolean cancelled) {
    }
}
