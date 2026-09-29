package com.lky.nexus.app.ai;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.ChatClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 1.3：<b>真实</b>模型调用（会消耗 Token，因此默认跳过）。
 *
 * <p>启用方式：设置环境变量 {@code AI_CHAT_API_KEY}（可选 {@code AI_CHAT_BASE_URL} / {@code AI_CHAT_MODEL}），
 * 例如在 PowerShell 里：
 * <pre>{@code
 * $env:AI_CHAT_API_KEY='sk-xxxx'
 * mvn -B -pl nexus-app test -Dtest=LiveModelCallTest
 * }</pre>
 * 未设置时该测试整体跳过（{@code @EnabledIfEnvironmentVariable}），CI 与无网环境下不会失败，也不需要 Mock。
 *
 * <p>为什么要有这条测试：前面所有关于 Spring AI API 的结论都来自<b>读源码</b>。
 * 只有真正发一次请求，才能证明这些签名在运行时确实可用——尤其是 Token 用量访问器
 * （{@code getPromptTokens()} / {@code getCompletionTokens()}），它是 Phase 10 观测埋点的数据来源。
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "AI_CHAT_API_KEY", matches = ".+",
        disabledReason = "未配置 AI_CHAT_API_KEY，跳过真实模型调用（避免无谓的 Token 消耗）")
class LiveModelCallTest {

    private static final Logger log = LoggerFactory.getLogger(LiveModelCallTest.class);

    @Autowired
    private ChatClientFactory chatClientFactory;

    @Test
    @DisplayName("非流式调用：返回内容非空，且能取到真实的 Token 用量")
    void nonStreamingCallReturnsContentAndUsage() {
        ChatClient chatClient = chatClientFactory.get(AiModelProperties.Role.CHAT_DEFAULT);

        long startNanos = System.nanoTime();
        ChatResponse response = chatClient.prompt()
                .user("请只回复四个字：收到指令")
                .call()
                .chatResponse();
        long latencyMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(response).isNotNull();
        String content = response.getResult().getOutput().getText();
        assertThat(content).as("模型回答不应为空").isNotBlank();

        // 这些访问器是从 v2.0.1 源码 Usage.java 读出来的（扁平 getter，不是 getInputTokens）
        Usage usage = response.getMetadata().getUsage();
        assertThat(usage).as("响应应携带用量信息").isNotNull();
        assertThat(usage.getPromptTokens()).as("输入 token 数应大于 0").isPositive();
        assertThat(usage.getCompletionTokens()).as("输出 token 数应大于 0").isPositive();
        assertThat(usage.getTotalTokens()).isEqualTo(usage.getPromptTokens() + usage.getCompletionTokens());

        log.info("真实调用成功: model={}, content={}, prompt={}, completion={}, total={}, latency={}ms",
                response.getMetadata().getModel(), content, usage.getPromptTokens(),
                usage.getCompletionTokens(), usage.getTotalTokens(), latencyMillis);
    }

    @Test
    @DisplayName("流式调用：分片到达，首 Token 延迟可测量（Phase 10 的核心指标）")
    void streamingCallEmitsChunksAndMeasuresFirstTokenLatency() {
        ChatClient chatClient = chatClientFactory.get(AiModelProperties.Role.CHAT_DEFAULT);

        AtomicLong firstChunkAt = new AtomicLong();
        long startNanos = System.nanoTime();

        List<String> chunks = chatClient.prompt()
                .user("请用一句话介绍 Spring Boot 的自动配置")
                .stream()
                .content()
                .doOnNext(chunk -> firstChunkAt.compareAndSet(0, System.nanoTime()))
                .collectList()
                .block();

        assertThat(chunks).as("流式结果不应为空").isNotNull().isNotEmpty();
        String fullText = String.join("", chunks);
        assertThat(fullText).as("拼接后的回答不应为空").isNotBlank();

        long firstTokenMillis = firstChunkAt.get() == 0 ? -1 : (firstChunkAt.get() - startNanos) / 1_000_000;
        long totalMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(firstTokenMillis).as("应能测到首 Token 延迟").isNotNegative();
        assertThat(firstTokenMillis).as("首 Token 不应晚于整体结束").isLessThanOrEqualTo(totalMillis);

        log.info("流式调用成功: 分片数={}, 首Token={}ms, 总耗时={}ms, 内容长度={}",
                chunks.size(), firstTokenMillis, totalMillis, fullText.length());
    }
}
