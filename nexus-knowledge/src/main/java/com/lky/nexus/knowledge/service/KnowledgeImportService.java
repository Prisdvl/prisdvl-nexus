package com.lky.nexus.knowledge.service;

import com.lky.nexus.knowledge.model.dto.ImportTaskResponse;

/**
 * 知识导入服务。
 *
 * <p>当前是<b>同步执行</b>：调用方（HTTP 请求）会一直等到整批处理完。
 * 这对"几十篇笔记"够用，但对整个 Vault（上千篇）不合适——
 * 因此 Step 2.7 会把它改成"立即返回 taskId + MQ 异步处理"，
 * 届时本接口签名的语义不变（返回任务），只是立刻返回 QUEUED 状态的任务。
 */
public interface KnowledgeImportService {

    /**
     * 导入 Obsidian Vault（ZIP 字节）。
     *
     * @return 导入结果/任务状态
     */
    ImportTaskResponse importVault(Long userId, Long knowledgeBaseId, byte[] zipBytes);

    /** 查询导入任务（校验归属）。 */
    ImportTaskResponse getTask(Long userId, Long taskId);
}
