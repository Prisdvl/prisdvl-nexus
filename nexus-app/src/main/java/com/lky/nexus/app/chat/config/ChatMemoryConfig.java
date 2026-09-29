package com.lky.nexus.app.chat.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会话记忆（Chat Memory）装配。
 *
 * <p>这里显式声明 {@link ChatMemory}，而不是完全依赖 Spring AI 的自动配置，
 * 原因是我们需要自己控制<b>窗口大小</b>（可配置），并让"用哪个仓库"这件事在代码里可见：
 * 引入 {@code spring-ai-starter-model-chat-memory-repository-jdbc} 后，
 * 自动配置会提供 {@code JdbcChatMemoryRepository}，本 Bean 把它接进滑动窗口策略。
 *
 * <p>再次强调职责边界（ADR-011）：
 * <ul>
 *   <li><b>ChatMemory</b>（这里）—— 决定"这次请求发给模型多少历史"，有界、可淘汰；</li>
 *   <li><b>chat_message</b>（本项目自建表）—— 完整历史，供用户查看、评测与观测。</li>
 * </ul>
 * 两者并存、各自解决不同问题，不要试图用一个替代另一个。
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository,
                                 @Value("${nexus.chat.memory.max-messages:20}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(maxMessages)
                .build();
    }
}
