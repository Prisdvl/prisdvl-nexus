package com.lky.nexus.app.infra.messaging;

import com.lky.nexus.knowledge.service.ImportTaskPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 异步投递器：把 taskId 发到 RabbitMQ。
 *
 * <p>刻意不发送压缩包本体：消息中间件不是文件通道，大消息会撑爆 broker 内存、
 * 触发 max_message_size，而且消费者失败重试时会被反复投递。
 * 这里只传一个 id，消费方从暂存区与数据库取真实数据。
 */
@Component
@ConditionalOnProperty(name = "nexus.import.mode", havingValue = "async")
public class RabbitImportTaskPublisher implements ImportTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitImportTaskPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public RabbitImportTaskPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(Long taskId) {
        rabbitTemplate.convertAndSend(RabbitImportConfig.IMPORT_EXCHANGE,
                RabbitImportConfig.IMPORT_ROUTING_KEY, String.valueOf(taskId));
        log.info("导入任务已投递 taskId={}", taskId);
    }
}
