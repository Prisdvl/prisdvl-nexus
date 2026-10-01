package com.lky.nexus.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.mapper.ImportTaskItemMapper;
import com.lky.nexus.knowledge.mapper.ImportTaskMapper;
import com.lky.nexus.knowledge.model.ImportTaskStatus;
import com.lky.nexus.knowledge.model.ImportTaskType;
import com.lky.nexus.knowledge.model.dto.ImportTaskItemView;
import com.lky.nexus.knowledge.model.dto.ImportTaskResponse;
import com.lky.nexus.knowledge.model.entity.ImportTaskItemPO;
import com.lky.nexus.knowledge.model.entity.ImportTaskPO;
import com.lky.nexus.knowledge.imports.VaultScanOptions;
import com.lky.nexus.knowledge.parser.ParserRegistry;
import com.lky.nexus.knowledge.service.ImportTaskPublisher;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import com.lky.nexus.knowledge.service.KnowledgeImportService;
import com.lky.nexus.knowledge.storage.ImportStagingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 导入任务的"入队侧"服务（HTTP 线程）。
 *
 * <p>它只做三件事：<b>校验归属与文件 → 建 QUEUED 任务 + 原始字节落暂存 → 投递消息</b>，
 * 然后立刻返回。真正的处理在 {@code ImportTaskExecutor}（消费者线程）。
 * 这样拆分的直接收益：上千篇笔记的 Vault 不会把 HTTP 请求挂住几分钟。
 *
 * <p>两个入队入口（Vault / 单文件）共用同一个 {@link #enqueue} 骨架，
 * 前置校验不同、之后的路径完全一样——见 {@link KnowledgeImportService} 的类注释。
 */
@Service
public class KnowledgeImportServiceImpl implements KnowledgeImportService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeImportServiceImpl.class);

    /**
     * 单文件上传的大小上限。
     *
     * <p>刻意复用压缩包那条路的"解压后总字节"预算，而不是 {@code VaultScanOptions}
     * 的 20MB <b>单条目</b>上限：那个上限是给"一个压缩包里的某一篇"用的——
     * 用户没有逐个指定过里面的文件，所以系统必须替它把风险压小；而单文件上传是
     * 用户显式点名的这一个文件，多一道 20MB 的闸只会让合法的 25MB PDF 被拒。
     *
     * <p>但它<b>不能</b>只依赖 multipart 的请求体上限：那个上限属于 HTTP 层，
     * 换一个调用方（MCP 工具、批量导入）就不存在了。校验放在服务里，
     * 才是"无论谁调都成立"的那一条。
     */
    private static final long MAX_UPLOAD_BYTES = VaultScanOptions.DEFAULT_MAX_TOTAL_BYTES;

    private final KnowledgeBaseService knowledgeBaseService;
    private final ImportStagingStore importStagingStore;
    private final ImportTaskPublisher importTaskPublisher;
    private final ImportTaskMapper importTaskMapper;
    private final ImportTaskItemMapper importTaskItemMapper;
    private final ParserRegistry parserRegistry;

    public KnowledgeImportServiceImpl(KnowledgeBaseService knowledgeBaseService,
                                      ImportStagingStore importStagingStore,
                                      ImportTaskPublisher importTaskPublisher,
                                      ImportTaskMapper importTaskMapper,
                                      ImportTaskItemMapper importTaskItemMapper,
                                      ParserRegistry parserRegistry) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.importStagingStore = importStagingStore;
        this.importTaskPublisher = importTaskPublisher;
        this.importTaskMapper = importTaskMapper;
        this.importTaskItemMapper = importTaskItemMapper;
        this.parserRegistry = parserRegistry;
    }

    @Override
    public ImportTaskResponse enqueueVaultImport(Long userId, Long knowledgeBaseId, byte[] zipBytes) {
        // 归属校验放在建任务之前：否则会留下一个"指向别人知识库"的僵尸任务
        knowledgeBaseService.requireOwned(userId, knowledgeBaseId);
        return enqueue(userId, knowledgeBaseId, ImportTaskType.VAULT_ZIP, null, zipBytes);
    }

    @Override
    public ImportTaskResponse enqueueFileImport(Long userId, Long knowledgeBaseId,
                                                String fileName, byte[] content) {
        knowledgeBaseService.requireOwned(userId, knowledgeBaseId);

        String sourceName = sanitizeFileName(fileName);
        // 支持的格式由解析器注册表说了算：入队期就拒掉，用户立刻拿到
        // "3005 + 当前支持哪些格式"，而不是几分钟后一句含糊的"导入失败"
        parserRegistry.requireParser(sourceName);
        requireUploadable(sourceName, content);

        log.info("单文件上传入队 kbId={}, name={}, size={} KB",
                knowledgeBaseId, sourceName, content.length / 1024);
        return enqueue(userId, knowledgeBaseId, ImportTaskType.UPLOAD, sourceName, content);
    }

    /**
     * 两个入口共用的骨架：建 QUEUED 任务 → 落暂存 → 投递 → 回读任务。
     *
     * <p>抽出来的意义不在于省几行，而在于"投递失败必须收尾"这段只有在<b>一处</b>写，
     * 才不会出现"新增了一个导入入口，但它忘了清理暂存 / 忘了标失败"。
     */
    private ImportTaskResponse enqueue(Long userId, Long knowledgeBaseId, ImportTaskType taskType,
                                       String sourceName, byte[] bytes) {
        ImportTaskPO task = createQueuedTask(userId, knowledgeBaseId, taskType, sourceName);
        importStagingStore.store(task.getId(), bytes);

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
    public List<ImportTaskItemView> listItems(Long userId, Long taskId, String action, int limit) {
        // 先按 userId 查任务、再按 taskId 查明细，而不是"查明细时顺带 join 任务"：
        // 归属校验只有一处（与 getTask 完全同一段），不会出现"这个接口忘了加 user_id 条件"
        // ——那会让任意登录用户拿别人的 taskId 读到别人库里的文件清单。
        getTask(userId, taskId);

        String normalized = action == null || action.isBlank()
                ? null : action.trim().toUpperCase(Locale.ROOT);
        int safeLimit = Math.clamp(limit, 1, MAX_ITEM_LIMIT);

        // 按 id 倒序：雪花 id 单调递增，等价于"最新的在前"，
        // 但不像 created_at 那样会受同毫秒插入的影响（一批文件常常落在同一毫秒里）。
        // LIMIT 走 last() 而不是 Page：这里不需要总数，多一次 count 查询只是白花时间
        List<ImportTaskItemPO> items = importTaskItemMapper.selectList(
                Wrappers.<ImportTaskItemPO>lambdaQuery()
                        .eq(ImportTaskItemPO::getTaskId, taskId)
                        .eq(normalized != null, ImportTaskItemPO::getAction, normalized)
                        .orderByDesc(ImportTaskItemPO::getId)
                        .last("LIMIT " + safeLimit));
        return items.stream().map(ImportTaskItemView::of).toList();
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
            // 成功的任务会清理暂存，因此这里必须给出可操作的提示，而不是抛一个内部错误。
            // 措辞不写"压缩包"：5.3 起这条暂存区也服务单文件上传（见 ImportStagingStore）
            throw new BusinessException(ErrorCode.PARAM_INVALID, "原始文件已清理，请重新上传后再导入");
        }
        importTaskPublisher.publish(taskId);
        return getTask(userId, taskId);
    }

    private ImportTaskPO createQueuedTask(Long userId, Long knowledgeBaseId, ImportTaskType taskType,
                                          String sourceName) {
        ImportTaskPO task = new ImportTaskPO();
        task.setKbId(knowledgeBaseId);
        task.setUserId(userId);
        task.setTaskType(taskType.name());
        task.setSourceName(sourceName);
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

    /**
     * 把客户端给的文件名收敛成一个安全的 {@code source_path}。
     *
     * <p>它最终会同时成为三样东西：文档在库里的身份（{@code kb_document.source_path}，
     * 参与"同名即更新"的匹配）、磁盘上原始文件名的来源、以及界面上那一行显示名。
     * 所以这里只取<b>最后一段</b>：浏览器本来只发文件名，但客户端可以伪造任意字符串，
     * 而 {@code a/../../../etc/passwd} 这种名字如果原样落库，后续每个用到
     * {@code source_path} 的地方都得自己再防一次——不如在入口就收敛掉。
     *
     * <p>注意这与 {@link VaultScanOptions} 那条路的取舍不同：压缩包里出现 {@code ..}
     * 是<b>整批拒绝</b>（那不是正常的 Obsidian 导出，继续处理没有意义）；而这里用户
     * 只是想传一个文件，把路径部分丢掉、留下文件名，才是他期待的结果。
     */
    private static String sanitizeFileName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少文件名");
        }
        String name = rawName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "文件名不合法：" + rawName);
        }
        // 控制字符（含 \0）不可能是用户敲出来的：它只说明这个名字是构造的
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "文件名含非法字符：" + rawName);
            }
        }
        return name;
    }

    private static void requireUploadable(String sourceName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "上传文件为空：" + sourceName);
        }
        if (content.length > MAX_UPLOAD_BYTES) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED,
                    "文件 %s 超过单文件上限 %d MB".formatted(sourceName, MAX_UPLOAD_BYTES / 1024 / 1024));
        }
    }
}
