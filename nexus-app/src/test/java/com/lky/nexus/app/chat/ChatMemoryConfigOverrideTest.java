package com.lky.nexus.app.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置绑定回归保护（非默认值路径）：一个**不等于**默认值 20 的配置必须原样作用到
 * {@link ChatMemory} Bean 上。
 *
 * <p><b>为什么需要它</b>：{@code ChatMemoryConfigBindingTest} 验的是"键存在且组件按它截断"，
 * 但那时键的值恰好就是默认值 20——万一 {@code @Value} 因为某种原因压根没注入到 Bean
 * （或者 Bean 写死了窗口），用默认值做断言是分辨不出来的。本类用
 * {@code @SpringBootTest(properties = ...)} 把键压成 7，断言窗口<b>确实缩到 7</b>：
 * 只有"属性 → Bean"这条链路真的通了才会绿。
 *
 * <p>这里用的属性覆盖走的是 Spring 的标准优先级（测试内联属性高于 application*.yml），
 * 与环境变量覆盖（{@code CHAT_MEMORY_MAX_MESSAGES}）是同一套宽松绑定/优先级机制，
 * 因此它同时守住"合理的覆盖行为没有被破坏"。
 *
 * <p>同样不调用模型、不依赖外部基础设施，跑在 {@code test} profile 上。
 */
@SpringBootTest(properties = "nexus.chat.memory.max-messages=7")
@ActiveProfiles("test")
@DisplayName("会话记忆窗口大小：非默认配置值必须作用到 ChatMemory Bean")
class ChatMemoryConfigOverrideTest {

    private static final String KEY = "nexus.chat.memory.max-messages";
    private static final int OVERRIDDEN = 7;

    @Autowired
    private Environment environment;

    @Autowired
    private ChatMemory chatMemory;

    @Test
    @DisplayName("覆盖值 7（非默认）确实生效：窗口缩到 7 且保留最新一条")
    void overrideValueIsAppliedToChatMemoryBean() {
        assertThat(environment.getProperty(KEY))
                .as("覆盖值应优先于 application.yml 的默认 20")
                .isEqualTo(String.valueOf(OVERRIDDEN));

        String conversationId = UUID.randomUUID().toString();
        int total = OVERRIDDEN + 6;
        for (int i = 1; i <= total; i++) {
            chatMemory.add(conversationId, List.of(new UserMessage("m" + i)));
        }

        List<Message> messages = chatMemory.get(conversationId);
        assertThat(messages)
                .as("窗口必须等于覆盖值 %d（写入了 %d 条）——若这里仍是 20，说明配置没绑到 Bean 上", OVERRIDDEN, total)
                .hasSize(OVERRIDDEN);
        assertThat(messages.get(messages.size() - 1).getText())
                .as("最新一条必须保留")
                .isEqualTo("m" + total);

        chatMemory.clear(conversationId);
    }
}