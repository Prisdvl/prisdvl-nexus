package com.lky.nexus.app.infra.messaging;

import com.lky.nexus.knowledge.imports.VaultImportExecutor;
import com.lky.nexus.knowledge.service.ImportTaskPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 同步投递器：在当前线程直接执行导入（不经过 broker）。
 *
 * <p>它的存在不是为了"图省事"，而是两个真实需求：
 * <ul>
 *   <li><b>standalone profile</b>（H2 内存库、不依赖 Docker）里没有 RabbitMQ，
 *       但完整链路仍必须能跑通；</li>
 *   <li>集成测试需要确定性：让 HTTP 请求返回时任务已经完成，
 *       否则测试要么随机失败，要么被迫引入等待与超时。</li>
 * </ul>
 *
 * <p>注意它依然走"暂存 → 执行器"的完整路径（因为入队侧统一先落暂存），
 * 因此同步模式与异步模式的数据流完全一致，差别只有"谁在什么时候调用执行器"。
 */
@Component
@ConditionalOnProperty(name = "nexus.import.mode", havingValue = "sync", matchIfMissing = true)
public class SynchronousImportTaskPublisher implements ImportTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(SynchronousImportTaskPublisher.class);

    private final VaultImportExecutor vaultImportExecutor;

    public SynchronousImportTaskPublisher(VaultImportExecutor vaultImportExecutor) {
        this.vaultImportExecutor = vaultImportExecutor;
    }

    @Override
    public void publish(Long taskId) {
        log.info("同步模式：直接执行导入任务 taskId={}", taskId);
        vaultImportExecutor.execute(taskId);
    }
}
