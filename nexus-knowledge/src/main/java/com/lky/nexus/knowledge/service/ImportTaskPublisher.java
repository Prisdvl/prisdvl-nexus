package com.lky.nexus.knowledge.service;

/**
 * 导入任务投递器（依赖倒置点）。
 *
 * <p>把"消息怎么发"抽象出来，使领域层完全不依赖 RabbitMQ：
 * <ul>
 *   <li>生产环境：RabbitMQ 实现（消息体只有 taskId，压缩包在暂存区）；</li>
 *   <li>无 Docker / 单元测试：同步实现（在当前线程直接执行），
 *       因此 standalone profile 与测试不装 broker 也能跑通完整链路。</li>
 * </ul>
 * 这也让"异步"成为一个可切换的部署特性，而不是写死在业务代码里的假设。
 */
public interface ImportTaskPublisher {

    /**
     * 投递导入任务。
     *
     * @param taskId 任务 id（消费方据此从数据库加载任务、从暂存区读取压缩包）
     */
    void publish(Long taskId);
}
