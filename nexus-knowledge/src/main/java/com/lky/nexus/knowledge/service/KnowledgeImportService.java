package com.lky.nexus.knowledge.service;

import com.lky.nexus.knowledge.model.dto.ImportTaskResponse;

/**
 * 知识导入服务。
 *
 * <p>接口刻意分成"入队"与"执行"两步，对应异步化的两个半场：
 * <ul>
 *   <li>{@link #enqueueVaultImport}：HTTP 线程执行——校验归属、建 QUEUED 任务、
 *       压缩包落暂存、投递消息，然后<b>立刻返回</b>（用户拿到 taskId 去轮询进度）；</li>
 *   <li>{@link #processVaultImport}：消费者线程执行——真正做解压/解析/切分/落库。
 *       它是<b>幂等</b>的（CAS 抢占任务状态），因为消息中间件保证的是"至少一次"投递。</li>
 * </ul>
 */
public interface KnowledgeImportService {

    /**
     * 入队：创建任务 + 压缩包落暂存 + 投递消息。
     *
     * @return 处于 QUEUED（异步）或已完成（同步实现）的任务
     */
    ImportTaskResponse enqueueVaultImport(Long userId, Long knowledgeBaseId, byte[] zipBytes);

    /** 查询导入任务（校验归属）。 */
    ImportTaskResponse getTask(Long userId, Long taskId);

    /** 重试失败任务（复用暂存区的压缩包）。 */
    ImportTaskResponse retryTask(Long userId, Long taskId);
}
