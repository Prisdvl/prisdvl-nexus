package com.lky.nexus.app.infra.messaging;

import com.lky.nexus.knowledge.imports.VaultImportExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 导入任务的消费者。
 *
 * <p>它只做一件事：把消息里的 taskId 交给执行器。所有业务逻辑（幂等抢占、解压、解析、
 * 切分、落库、收尾）都在执行器里，因此同步模式与异步模式走的是<b>同一条代码路径</b>——
 * 这样"本地跑通了、上线异步却不一样"的风险被压到最低。
 *
 * <p>异常处理：执行器对"业务性失败"已就地消化（不会抛出）；只有非预期异常才会冒泡到
 * 这里，从而触发 Spring AMQP 的重试，重试耗尽后由 {@link RabbitImportConfig} 的死信配置兜底。
 */
@Component
@ConditionalOnProperty(name = "nexus.import.mode", havingValue = "async")
public class ImportTaskListener {

    private static final Logger log = LoggerFactory.getLogger(ImportTaskListener.class);

    private final VaultImportExecutor vaultImportExecutor;

    public ImportTaskListener(VaultImportExecutor vaultImportExecutor) {
        this.vaultImportExecutor = vaultImportExecutor;
    }

    @RabbitListener(queues = RabbitImportConfig.IMPORT_QUEUE)
    public void onImportTask(String taskId) {
        log.info("收到导入任务消息 taskId={}", taskId);
        try {
            vaultImportExecutor.execute(Long.valueOf(taskId));
        } catch (NumberFormatException ex) {
            // 消息体不是合法 id：重试多少次都一样，直接丢弃并告警（否则会一直进死信队列）
            log.error("导入任务消息格式非法，已丢弃：{}", taskId);
        }
    }
}
