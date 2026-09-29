package com.lky.nexus.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.mapper.ImportTaskMapper;
import com.lky.nexus.knowledge.model.ImportTaskStatus;
import com.lky.nexus.knowledge.model.ImportTaskType;
import com.lky.nexus.knowledge.model.dto.ImportTaskResponse;
import com.lky.nexus.knowledge.model.entity.ImportTaskPO;
import com.lky.nexus.knowledge.service.ImportTaskPublisher;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import com.lky.nexus.knowledge.service.KnowledgeImportService;
import com.lky.nexus.knowledge.storage.ImportStagingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 导入任务的"入队侧"服务（HTTP 线程）。
 *
 * <p>它只做三件事：<b>校验归属 → 建 QUEUED 任务 + 压缩包落暂存 → 投递消息</b>，
 * 然后立刻返回。真正的处理在 {@code VaultImportExecutor}（消费者线程）。
 * 这样拆分的直接收益：上千篇笔记的 Vault 不会把 HTTP 请求挂住几分钟。
 */
@Service
public class KnowledgeImportServiceImpl implements KnowledgeImportService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeImportServiceImpl.class);

    private final KnowledgeBaseService knowledgeBaseService;
    private final ImportStagingStore importStagingStore;
    private final ImportTaskPublisher importTaskPublisher;
    private final ImportTaskMapper importTaskMapper;

    public KnowledgeImportServiceImpl(KnowledgeBaseService knowledgeBaseService,
                                      ImportStagingStore importStagingStore,
                                      ImportTaskPublisher importTaskPublisher,
                                      ImportTaskMapper importTaskMapper) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.importStagingStore = importStagingStore;
        this.importTaskPublisher = importTaskPublisher;
        this.importTaskMapper = importTaskMapper;
    }

    @Override
    public ImportTaskResponse enqueueVaultImport(Long userId, Long knowledgeBaseId, byte[] zipBytes) {
        // 归属校验放在建任务之前：否则会留下一个"指向别人知识库"的僵尸任务
        knowledgeBaseService.requireOwned(userId, knowledgeBaseId);

        ImportTaskPO task = createQueuedTask(userId, knowledgeBaseId);
        importStagingStore.store(task.getId(), zipBytes);

        try {
            importTaskPublisher.publish(task.getId());
        } catch (Exception ex) {
            // 投递失败必须收尾：否则任务永远停在 QUEUED，用户以为"排队中"其实永远不动
            log.error("导入任务投递失败 taskId={}", task.getId(), ex);
            markFailed(task.getId(), "任务投递失败：" + ex.getMessage());
            importStagingStore.delete(task.getId());
            throw new BusinessException(ErrorCode.IMPORT_TASK_PUBLISH_FAILED, "导入任务投递失败，请稍后重试");
        }
        return getTask(userId, task.getId());
    }

    @Override
    public ImportTaskResponse getTask(Long userId, Long taskId) {
        ImportTaskPO task = importTaskMapper.selectOne(Wrappers.<ImportTaskPO>lambdaQuery()
                .eq(ImportTaskPO::getId, taskId)
                .eq(ImportTaskPO::getUserId, userId));
        if (task == null) {
            throw new BusinessException(ErrorCode.IMPORT_TASK_NOT_FOUND);
        }
        return ImportTaskResponse.of(task);
    }

    @Override
    public ImportTaskResponse retryTask(Long userId, Long taskId) {
        ImportTaskPO task = importTaskMapper.selectOne(Wrappers.<ImportTaskPO>lambdaQuery()
                .eq(ImportTaskPO::getId, taskId)
                .eq(ImportTaskPO::getUserId, userId));
        if (task == null) {
            throw new BusinessException(ErrorCode.IMPORT_TASK_NOT_FOUND);
        }
        if (!ImportTaskStatus.PARTIAL.name().equals(task.getStatus())
                && !ImportTaskStatus.FAILED.name().equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "只有部分成功或失败的任务可以重试，当前状态：" + task.getStatus());
        }
        if (!importStagingStore.exists(taskId)) {
            // 成功的任务会清理暂存，因此这里必须给出可操作的提示，而不是抛一个内部错误
            throw new BusinessException(ErrorCode.PARAM_INVALID, "原始压缩包已清理，请重新上传后再导入");
        }
        importTaskPublisher.publish(taskId);
        return getTask(userId, taskId);
    }

    private ImportTaskPO createQueuedTask(Long userId, Long knowledgeBaseId) {
        ImportTaskPO task = new ImportTaskPO();
        task.setKbId(knowledgeBaseId);
        task.setUserId(userId);
        task.setTaskType(ImportTaskType.VAULT_ZIP.name());
        task.setStatus(ImportTaskStatus.QUEUED.name());
        // 总文件数要等解压后才知道，这里先置 0
        task.setTotalFiles(0);
        task.setCreatedCount(0);
        task.setUpdatedCount(0);
        task.setSkippedCount(0);
        task.setDeletedCount(0);
        task.setFailedCount(0);
        task.setTotalChunks(0);
        task.setTotalTokens(0L);
        task.setProgress(0);
        task.setCreatedAt(LocalDateTime.now());
        importTaskMapper.insert(task);
        return task;
    }

    private void markFailed(Long taskId, String message) {
        importTaskMapper.update(null, Wrappers.<ImportTaskPO>lambdaUpdate()
                .set(ImportTaskPO::getStatus, ImportTaskStatus.FAILED.name())
                .set(ImportTaskPO::getErrorMsg,
                        message == null ? null : message.substring(0, Math.min(1900, message.length())))
                .set(ImportTaskPO::getFinishedAt, LocalDateTime.now())
                .eq(ImportTaskPO::getId, taskId));
    }
}
