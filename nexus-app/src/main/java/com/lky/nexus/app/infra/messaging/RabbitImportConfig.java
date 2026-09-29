package com.lky.nexus.app.infra.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 导入任务的 RabbitMQ 拓扑：主队列 + 死信队列。
 *
 * <p>为什么必须显式声明拓扑而不是"发消息时自动创建"：
 * 自动声明只能保证队列存在，<b>死信参数（DLX）无法事后附加</b>——
 * 而"重试耗尽后消息去哪"恰恰是异步任务最重要的一环。拓扑由应用声明，
 * 也让 broker 上的配置成为代码的一部分（可评审、可回滚）。
 *
 * <p>消息体只有 taskId（字符串），压缩包在暂存区；因此不需要自定义消息转换器，
 * 也就避免了"消息格式与消费者不一"这类跨版本问题。
 */
@Configuration
@ConditionalOnProperty(name = "nexus.import.mode", havingValue = "async")
public class RabbitImportConfig {

    public static final String IMPORT_EXCHANGE = "nexus.import.exchange";
    public static final String IMPORT_QUEUE = "nexus.import.vault.queue";
    public static final String IMPORT_ROUTING_KEY = "nexus.import.vault";

    /** 死信交换机与死信队列：重试耗尽的消息最终落在这里，供人工排查/回放。 */
    public static final String IMPORT_DLX = "nexus.import.dlx";
    public static final String IMPORT_DLQ = "nexus.import.vault.dlq";

    @Bean
    public DirectExchange importExchange() {
        return new DirectExchange(IMPORT_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange importDlx() {
        return new DirectExchange(IMPORT_DLX, true, false);
    }

    /**
     * 主队列。
     *
     * <p>{@code deadLetterRoutingKey=IMPORT_DLQ}：配合
     * {@code spring.rabbitmq.listener.simple.default-requeue-rejected=false}，
     * 消费者抛出异常且重试次数耗尽后，消息会被投到死信队列，而不是无限重新入队
     * （无限 requeue 会把 CPU 和日志打满，是消息队列最常见的生产事故之一）。
     */
    @Bean
    public Queue importQueue() {
        return QueueBuilder.durable(IMPORT_QUEUE)
                .deadLetterExchange(IMPORT_DLX)
                .deadLetterRoutingKey(IMPORT_DLQ)
                .build();
    }

    @Bean
    public Queue importDeadLetterQueue() {
        return QueueBuilder.durable(IMPORT_DLQ).build();
    }

    @Bean
    public Binding importBinding(Queue importQueue, DirectExchange importExchange) {
        return BindingBuilder.bind(importQueue).to(importExchange).with(IMPORT_ROUTING_KEY);
    }

    @Bean
    public Binding importDeadLetterBinding(Queue importDeadLetterQueue, DirectExchange importDlx) {
        return BindingBuilder.bind(importDeadLetterQueue).to(importDlx).with(IMPORT_DLQ);
    }
}
