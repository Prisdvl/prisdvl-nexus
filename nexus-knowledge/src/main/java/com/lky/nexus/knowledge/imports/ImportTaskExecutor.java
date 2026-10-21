package com.lky.nexus.knowledge.imports;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.util.HashUtils;
import com.lky.nexus.knowledge.chunk.ChunkingOptions;
import com.lky.nexus.knowledge.chunk.ChunkerFactory;
import com.lky.nexus.knowledge.chunk.DocumentChunker;
import com.lky.nexus.knowledge.chunk.TextChunk;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.ImportTaskItemMapper;
import com.lky.nexus.knowledge.mapper.ImportTaskMapper;
import com.lky.nexus.knowledge.model.ImportAction;
import com.lky.nexus.knowledge.model.ImportTaskStatus;
import com.lky.nexus.knowledge.model.ImportTaskType;
import com.lky.nexus.knowledge.model.KnowledgeSourceType;
import com.lky.nexus.knowledge.model.dto.ImportTaskResponse;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.model.entity.ImportTaskItemPO;
import com.lky.nexus.knowledge.model.entity.ImportTaskPO;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.parser.ParsedDocument;
import com.lky.nexus.knowledge.parser.ParserRegistry;
import com.lky.nexus.knowledge.service.DocumentPersistResult;
import com.lky.nexus.knowledge.service.DocumentPersistService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import com.lky.nexus.knowledge.imports.event.ImportFinishedEvent;
import com.lky.nexus.knowledge.storage.DocumentStorage;
import com.lky.nexus.knowledge.storage.ImportStagingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入任务的<b>执行器</b>（消费者侧）。Vault 压缩包与单文件上传共用这一条流水线。
 *
 * <p>它被三种调用方复用，这也是它必须独立成组件的原因：
 * <ul>
 *   <li>RabbitMQ 监听器（异步模式）；</li>
 *   <li>同步投递器（无 broker 的 standalone / 测试模式）；</li>
 *   <li>手工重试。</li>
 * </ul>
 * 如果把它塞进"入队服务"里，同步投递器就会形成
 * 投递器 → 导入服务 → 投递器 的循环依赖（Spring Boot 默认拒绝循环引用，会直接启动失败）。
 * 拆开之后依赖关系变成一棵树：入队服务与各投递器都只依赖执行器。
 *
 * <p><b>幂等性</b>：消息队列只保证"至少一次"投递，因此 {@link #execute(Long)} 用一次
 * CAS（比较并设置）把任务从可执行状态抢占为 RUNNING；抢不到就说明已有人在处理或已处理完，
 * 直接返回当前状态。这不是"大概不会重复"，而是数据库层面的互斥。
 *
 * <p><b>两种任务类型的唯一分歧点在文件清单的来源</b>（5.3 起）：
 * <ul>
 *   <li>{@code VAULT_ZIP}：清单由解压器从压缩包里读出（含被跳过的条目与总数）；</li>
 *   <li>{@code UPLOAD}：清单就是暂存区的那一个文件（见 {@link #singleFileScan}）。</li>
 * </ul>
 * 从"增量比对"开始往下——认领改名、切分、落库、计数、明细、收尾——两条路<b>完全一致</b>。
 * 这是刻意不新建第二条流水线的地方：单文件上传看起来简单，但它同样要处理
 * "同名再传是更新还是新增"、"内容没变就跳过"，这些判断已经在这里了，
 * 复制一份等于把它们的修正永远留在两个地方。
 */
@Component
public class ImportTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(ImportTaskExecutor.class);

    /** 每处理多少个文件更新一次任务进度。 */
    private static final int PROGRESS_BATCH = 10;

    /** 可以被抢占执行的状态：QUEUED（首次）、PARTIAL/FAILED（重试）。 */
    private static final List<String> CLAIMABLE_STATUS = List.of(
            ImportTaskStatus.QUEUED.name(), ImportTaskStatus.PARTIAL.name(), ImportTaskStatus.FAILED.name());

    /** 未变化文件在明细里的说明。措辞要说清"这是正常的"，否则用户会把它当成一种失败。 */
    private static final String UNCHANGED_REASON = "文件未变化，已跳过（未重新解析，也未重新嵌入向量）";

    private final KnowledgeBaseService knowledgeBaseService;
    private final VaultPackageExtractor vaultPackageExtractor;
    private final VaultSyncPlanner vaultSyncPlanner;
    private final ParserRegistry parserRegistry;
    private final ChunkerFactory chunkerFactory;
    private final DocumentPersistService documentPersistService;
    private final DocumentStorage documentStorage;
    private final ImportStagingStore importStagingStore;
    private final DocumentMapper documentMapper;
    private final ImportTaskMapper importTaskMapper;
    private final ImportTaskItemMapper importTaskItemMapper;
    private final ApplicationEventPublisher eventPublisher;

    public ImportTaskExecutor(KnowledgeBaseService knowledgeBaseService,
                              VaultPackageExtractor vaultPackageExtractor,
                              VaultSyncPlanner vaultSyncPlanner,
                              ParserRegistry parserRegistry,
                              ChunkerFactory chunkerFactory,
                              DocumentPersistService documentPersistService,
                              DocumentStorage documentStorage,
                              ImportStagingStore importStagingStore,
                              DocumentMapper documentMapper,
                              ImportTaskMapper importTaskMapper,
                              ImportTaskItemMapper importTaskItemMapper,
                              ApplicationEventPublisher eventPublisher) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.vaultPackageExtractor = vaultPackageExtractor;
        this.vaultSyncPlanner = vaultSyncPlanner;
        this.parserRegistry = parserRegistry;
        this.chunkerFactory = chunkerFactory;
        this.documentPersistService = documentPersistService;
        this.documentStorage = documentStorage;
        this.importStagingStore = importStagingStore;
        this.documentMapper = documentMapper;
        this.importTaskMapper = importTaskMapper;
        this.importTaskItemMapper = importTaskItemMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 执行一个已入队的导入任务。
     *
     * @param taskId 任务 id
     * @return 执行后的任务；任务不存在时返回 null
     */
    public ImportTaskResponse execute(Long taskId) {
        ImportTaskPO task = importTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("导入任务不存在，忽略该消息 taskId={}", taskId);
            return null;
        }

        // 幂等抢占：只有把状态从"可执行"成功改为 RUNNING 的那一次调用才继续
        int claimed = importTaskMapper.update(null, Wrappers.<ImportTaskPO>lambdaUpdate()
                .set(ImportTaskPO::getStatus, ImportTaskStatus.RUNNING.name())
                .set(ImportTaskPO::getStartedAt, LocalDateTime.now())
                .eq(ImportTaskPO::getId, taskId)
                .in(ImportTaskPO::getStatus, CLAIMABLE_STATUS));
        if (claimed == 0) {
            ImportTaskPO current = importTaskMapper.selectById(taskId);
            log.info("导入任务已被处理或正在处理，忽略重复投递 taskId={}, status={}",
                    taskId, current == null ? "null" : current.getStatus());
            return current == null ? null : toResponse(current);
        }

        byte[] stagedBytes = importStagingStore.load(taskId);
        if (stagedBytes == null) {
            log.error("导入暂存文件丢失 taskId={}", taskId);
            markFailed(taskId, "导入暂存文件丢失，请重新上传");
            publishFailedById(taskId, safeKbName(task));
            return toResponse(importTaskMapper.selectById(taskId));
        }

        try {
            return runPipeline(task, stagedBytes);
        } catch (BusinessException ex) {
            // 业务性失败（压缩包损坏、参数非法）：重试不会有不同结果，因此不再抛出
            log.warn("导入任务因业务原因失败 taskId={}, reason={}", taskId, ex.getMessage());
            markFailed(taskId, ex.getMessage());
            publishFailedById(taskId, safeKbName(task));
            return toResponse(importTaskMapper.selectById(taskId));
        } catch (Exception ex) {
            // 非预期失败（数据库抖动、网络）：落失败状态后抛出，交给重试策略与死信队列
            log.error("导入任务执行异常 taskId={}", taskId, ex);
            markFailed(taskId, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            publishFailedById(taskId, safeKbName(task));
            throw ex;
        }
    }

    // ------------------------------------------------------------------

    private ImportTaskResponse runPipeline(ImportTaskPO task, byte[] stagedBytes) {
        KnowledgeBasePO knowledgeBase = knowledgeBaseService.requireOwned(task.getUserId(), task.getKbId());

        // 唯一的类型分歧点：文件清单从哪来。往下全部共用
        boolean fullSync = !ImportTaskType.UPLOAD.name().equals(task.getTaskType());
        VaultScanResult scan = fullSync
                ? vaultPackageExtractor.extract(stagedBytes, VaultScanOptions.defaults())
                : singleFileScan(task, stagedBytes);

        // 来源如实标注：整包同步是 OBSIDIAN，单文件上传是 UPLOAD。
        // 两者在库里的路径可以长得一模一样，因此这件事只能由发起方声明，不能事后推断
        KnowledgeSourceType sourceType = fullSync
                ? KnowledgeSourceType.OBSIDIAN : KnowledgeSourceType.UPLOAD;

        task.setTotalFiles(scan.files().size());
        importTaskMapper.updateById(task);

        ChunkingOptions chunkingOptions = ChunkingOptions.of(
                knowledgeBase.getChunkSize(), knowledgeBase.getChunkOverlap(), null);
        DocumentChunker chunker = chunkerFactory.of(knowledgeBase.getChunkStrategy());

        // ---------- 第一阶段：只用 file_hash 做廉价比对（不解析正文） ----------
        List<DocumentPO> existingDocuments = documentMapper.selectList(Wrappers.<DocumentPO>lambdaQuery()
                .eq(DocumentPO::getKbId, knowledgeBase.getId()));
        Map<String, DocumentPO> existingByPath = new HashMap<>();
        for (DocumentPO document : existingDocuments) {
            existingByPath.put(document.getSourcePath(), document);
        }
        List<ScannedFile> scannedFiles = scan.files().stream()
                .map(file -> new ScannedFile(file, HashUtils.sha256Hex(file.content())))
                .toList();
        // 完整 Vault 同步才允许判定删除：单文件上传的"清单"只有一个文件，
        // 若也按"清单里没有 = 文件被删了"来判定，会把库里其余文档全部误删
        VaultSyncPlan plan = vaultSyncPlanner.plan(existingDocuments, scannedFiles, fullSync);
        Map<String, DocumentPO> missingByHash = vaultSyncPlanner.indexByFileHash(plan.missing());

        Counters counters = new Counters(totalFiles(plan, scan));

        // 未变化的文件：<b>单文件上传才逐条记明细</b>，整包同步只计数。
        //
        // 这个区分不是省事，是两条路的代价差了几个数量级：
        //   * 上传一个文件时"未变化"最多一条，而它恰恰是最需要解释的一种结果——
        //     用户传了、界面说"跳过 1"，没有任何明细的话看起来就像失败；
        //   * 整包同步一次可能带着上千篇未变的笔记（这正是增量同步的常态），
        //     逐条落库意味着每次重导都插入上千行，而它们提供的信息（"这篇没变"）
        //     已经由 skippedCount 汇总表达了。
        // 因此整包同步的 skippedCount 会大于明细条数，界面上要如实说明这一点。
        if (!fullSync) {
            for (ScannedFile unchanged : plan.unchanged()) {
                recordItem(task, unchanged.file().sourcePath(), ImportAction.SKIPPED, null,
                        unchanged.fileHash(), unchanged.fileHash(), 0, 0, UNCHANGED_REASON);
            }
        }
        counters.skipped += plan.unchanged().size();

        // ---------- 第二阶段：字节变了 → 解析后判断"只改元数据"还是"正文也变" ----------
        for (ScannedFile scanned : plan.changed()) {
            counters.accept(processFile(task, scanned, () -> {
                ParsedDocument parsed = parse(scanned.file());
                DocumentPO document = existingByPath.get(scanned.file().sourcePath());
                String contentHash = HashUtils.sha256Hex(parsed.content());
                if (document != null && contentHash.equals(document.getContentHash())) {
                    documentPersistService.updateMetadataOnly(task.getUserId(), knowledgeBase, document,
                            scanned.file(), parsed, sourceType);
                    return new FileOutcome(ImportAction.METADATA_ONLY, document.getId(),
                            nz(document.getChunkCount()));
                }
                List<TextChunk> chunks = chunker.chunk(parsed, chunkingOptions);
                String storedPath = documentStorage.store(knowledgeBase.getId(),
                        scanned.file().sourcePath(), scanned.file().content());
                DocumentPersistResult result = documentPersistService.save(task.getUserId(), knowledgeBase,
                        scanned.file(), parsed, chunks, storedPath, sourceType);
                return new FileOutcome(ImportAction.UPDATED, result.document().getId(), chunks.size());
            }));
        }

        // ---------- 第三阶段：新路径 → 先按 file_hash 认领改名，否则新增 ----------
        for (ScannedFile scanned : plan.added()) {
            counters.accept(processFile(task, scanned, () -> {
                ParsedDocument parsed = parse(scanned.file());
                DocumentPO renamedFrom = vaultSyncPlanner.claimRename(missingByHash, scanned);
                if (renamedFrom != null) {
                    documentPersistService.relocate(task.getUserId(), knowledgeBase, renamedFrom,
                            scanned.file(), parsed, sourceType);
                    log.debug("识别为改名：{} → {}", renamedFrom.getSourcePath(), scanned.file().sourcePath());
                    return new FileOutcome(ImportAction.RENAMED, renamedFrom.getId(),
                            nz(renamedFrom.getChunkCount()));
                }
                List<TextChunk> chunks = chunker.chunk(parsed, chunkingOptions);
                String storedPath = documentStorage.store(knowledgeBase.getId(),
                        scanned.file().sourcePath(), scanned.file().content());
                DocumentPersistResult result = documentPersistService.save(task.getUserId(), knowledgeBase,
                        scanned.file(), parsed, chunks, storedPath, sourceType);
                return new FileOutcome(ImportAction.CREATED, result.document().getId(), chunks.size());
            }));
        }

        // ---------- 第四阶段：没被认领的"消失文档" → 删除 ----------
        // 单文件上传时 missingByHash 必然为空（detectDeletions=false），本段自然跳过：
        // "这次没传它"绝不等于"用户删了它"
        for (DocumentPO document : missingByHash.values()) {
            long startMillis = System.currentTimeMillis();
            try {
                documentPersistService.delete(task.getUserId(), document);
                counters.deleted++;
                recordItem(task, document.getSourcePath(), ImportAction.DELETED, document.getId(),
                        document.getFileHash(), null, 0,
                        (int) (System.currentTimeMillis() - startMillis), "本次 Vault 清单中已不存在该文件");
            } catch (Exception ex) {
                counters.failed++;
                log.error("删除文档失败 path={}", document.getSourcePath(), ex);
                recordItem(task, document.getSourcePath(), ImportAction.FAILED, document.getId(),
                        document.getFileHash(), null, 0,
                        (int) (System.currentTimeMillis() - startMillis),
                        ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
            counters.processed++;
            if (counters.processed % PROGRESS_BATCH == 0) {
                updateProgress(task, counters);
            }
        }

        // 被跳过的压缩包条目（隐藏目录、不支持类型）同样要留明细。
        // 这里是"用户最常问的那一类问题"的落点：我的 .pptx / .png 为什么没进来？
        // 答案在 SkippedEntry.reason 里，而这条路径此前只加了一个计数——
        // 于是 ImportAction.SKIPPED 与 Counters 里的 case SKIPPED 都是死代码。
        for (VaultScanResult.SkippedEntry entry : scan.skipped()) {
            recordItem(task, entry.path(), ImportAction.SKIPPED, null, null, null, 0, 0, entry.reason());
        }
        counters.skipped += scan.skipped().size();

        task.setCreatedCount(counters.created);
        task.setUpdatedCount(counters.updated + counters.metadataOnly + counters.renamed);
        task.setSkippedCount(counters.skipped);
        task.setDeletedCount(counters.deleted);
        task.setFailedCount(counters.failed);
        task.setTotalChunks(counters.totalChunks);
        task.setProgress(100);
        task.setFinishedAt(LocalDateTime.now());
        task.setStatus(counters.failed == 0 ? ImportTaskStatus.DONE.name()
                : (counters.created + counters.updated + counters.metadataOnly + counters.renamed > 0
                        ? ImportTaskStatus.PARTIAL.name() : ImportTaskStatus.FAILED.name()));
        importTaskMapper.updateById(task);

        // 通知订阅方（app 模块的监听器负责翻译成人话并投递）。事件只带客观计数，
        // 文案与通道选择都不在 knowledge 模块里决定。
        publishFinished(task, knowledgeBase.getName());

        documentPersistService.refreshKnowledgeBaseStats(knowledgeBase.getId());

        // 完全成功才清理暂存：部分失败时保留压缩包，重试接口可直接复用，用户不必再传一次
        if (ImportTaskStatus.DONE.name().equals(task.getStatus())) {
            importStagingStore.delete(task.getId());
        }

        log.info("导入完成 type={} kbId={}，新增 {} / 正文更新 {} / 元数据更新 {} / 改名 {} / 跳过 {} / 删除 {} / 失败 {}，切片 {}",
                task.getTaskType(), knowledgeBase.getId(), counters.created, counters.updated,
                counters.metadataOnly, counters.renamed, counters.skipped, counters.deleted,
                counters.failed, counters.totalChunks);
        return toResponse(task);
    }

    // ------------------------------------------------------------------
    // 单文件处理与计数
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface FileTask {
        FileOutcome run() throws Exception;
    }

    private record FileOutcome(ImportAction action, Long documentId, int chunkCount) {
    }

    /** 可变计数器：把"处理一个文件"的统计逻辑收在一处，避免散落在各分支里漏加。 */
    private static final class Counters {
        private final int total;
        private int processed;
        private int created;
        private int updated;
        private int metadataOnly;
        private int renamed;
        private int skipped;
        private int deleted;
        private int failed;
        private int totalChunks;

        private Counters(int total) {
            this.total = total;
        }

        private void accept(FileOutcome outcome) {
            processed++;
            switch (outcome.action()) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case METADATA_ONLY -> metadataOnly++;
                case RENAMED -> renamed++;
                case SKIPPED -> skipped++;
                case DELETED -> deleted++;
                case FAILED -> failed++;
            }
            totalChunks += outcome.chunkCount();
        }
    }

    /** 执行单文件处理：统一异常处理与明细记录（"一个文件失败不影响其他文件"的落点）。 */
    private FileOutcome processFile(ImportTaskPO task, ScannedFile scanned, FileTask body) {
        long startMillis = System.currentTimeMillis();
        try {
            FileOutcome outcome = body.run();
            recordItem(task, scanned.file().sourcePath(), outcome.action(), outcome.documentId(),
                    null, scanned.fileHash(), outcome.chunkCount(),
                    (int) (System.currentTimeMillis() - startMillis), null);
            return outcome;
        } catch (BusinessException ex) {
            log.warn("导入失败 path={}, reason={}", scanned.file().sourcePath(), ex.getMessage());
            recordItem(task, scanned.file().sourcePath(), ImportAction.FAILED, null, null,
                    scanned.fileHash(), 0, (int) (System.currentTimeMillis() - startMillis), ex.getMessage());
            return new FileOutcome(ImportAction.FAILED, null, 0);
        } catch (Exception ex) {
            log.error("导入异常 path={}", scanned.file().sourcePath(), ex);
            recordItem(task, scanned.file().sourcePath(), ImportAction.FAILED, null, null,
                    scanned.fileHash(), 0, (int) (System.currentTimeMillis() - startMillis),
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return new FileOutcome(ImportAction.FAILED, null, 0);
        }
    }

    /**
     * 单文件上传的"扫描结果"：不需要扫描，直接由暂存的字节与任务上的文件名合成一个文件。
     *
     * <p>这样做让上传与 Vault 走同一个 {@link VaultSyncPlan}——于是"同名再传是更新还是新增"、
     * "内容没变就跳过"、"库里已有同内容的别的路径 → 认作改名"这些判断全部自动成立，
     * 而不必为上传再写一套（写两套的代价不是多写代码，是它们的修正永远会分叉）。
     *
     * <p>几个刻意的取值：
     * <ul>
     *   <li>{@code folder = null}、{@code sourcePath = 文件名}：上传的文件没有"库内路径"这回事，
     *       文件名就是它的身份。这也让 {@code [[笔记名]]} 这类链接能直接匹配上；</li>
     *   <li>{@code lastModified = null}：multipart 不携带可靠的修改时间（那是客户端可伪造的
     *       元数据，不是文件内容的一部分），而 {@code doc_updated_at} 宁可空着，
     *       也不要填一个看起来精确其实是假的时刻；</li>
     *   <li>{@code totalEntries = 1}、{@code skipped} 为空：上传没有"条目"概念，
     *       被拒的情况（空文件 / 格式不支持 / 超大）在入队期就已经答复过用户了。</li>
     * </ul>
     *
     * <p>注意这里<b>没有</b> {@link VaultScanOptions} 的单文件 20MB 限制——那是给
     * "压缩包里的某一个条目"设的，而这是用户显式点名的文件，上限在入队侧另行判定。
     */
    private VaultScanResult singleFileScan(ImportTaskPO task, byte[] content) {
        String fileName = task.getSourceName();
        if (fileName == null || fileName.isBlank()) {
            // 任务行上没有文件名 → 无法确定这次要导入什么，属于数据损坏而不是用户输入问题
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "上传任务缺少文件名（taskId=" + task.getId() + "），无法确定导入目标");
        }
        VaultFile file = new VaultFile(fileName, fileName, null, content, null);
        return new VaultScanResult(List.of(file), List.of(), 1);
    }

    private ParsedDocument parse(VaultFile file) {
        // 统一传字节：文本格式由解析器内部按 UTF-8 解码，PDF/DOCX 直接消费二进制，
        // 调用方因此不需要按格式分支（见 DocumentParser 的接口说明）
        return parserRegistry.requireParser(file.fileName()).parse(file.fileName(), file.content());
    }

    private int totalFiles(VaultSyncPlan plan, VaultScanResult scan) {
        return plan.unchanged().size() + plan.changed().size() + plan.added().size()
                + plan.missing().size() + scan.skipped().size();
    }

    private void markFailed(Long taskId, String message) {
        importTaskMapper.update(null, Wrappers.<ImportTaskPO>lambdaUpdate()
                .set(ImportTaskPO::getStatus, ImportTaskStatus.FAILED.name())
                .set(ImportTaskPO::getErrorMsg, truncate(message, 1900))
                .set(ImportTaskPO::getFinishedAt, LocalDateTime.now())
                .eq(ImportTaskPO::getId, taskId));
    }

    /**
     * 发布"导入结束"事件。
     *
     * <p>失败一律吞掉：通知是附加能力，事件发不出去不该让一个已经成功的导入返回 500。
     * 监听方（app 模块）自己也会再兜一层。
     */
    private void publishFinished(ImportTaskPO task, String knowledgeBaseName) {
        try {
            eventPublisher.publishEvent(new ImportFinishedEvent(
                    task.getUserId(),
                    task.getId(),
                    task.getKbId(),
                    knowledgeBaseName,
                    task.getTaskType(),
                    task.getStatus(),
                    nullSafe(task.getCreatedCount()),
                    nullSafe(task.getUpdatedCount()),
                    nullSafe(task.getSkippedCount()),
                    nullSafe(task.getFailedCount()),
                    nullSafe(task.getTotalChunks())));
        } catch (RuntimeException ex) {
            log.warn("导入结束事件发布失败 taskId={}", task.getId(), ex);
        }
    }

    /** 重新读一次任务再发：markFailed 用的是 update，不刷新内存里的对象。 */
    private void publishFailedById(Long taskId, String knowledgeBaseName) {
        ImportTaskPO fresh = importTaskMapper.selectById(taskId);
        if (fresh != null) {
            publishFinished(fresh, knowledgeBaseName);
        }
    }

    /** 知识库可能已被删除，此时通知里就写 id，不要让"取名字"失败再引发一次异常。 */
    private String safeKbName(ImportTaskPO task) {
        try {
            return knowledgeBaseService.requireOwned(task.getUserId(), task.getKbId()).getName();
        } catch (RuntimeException ex) {
            return null;
        }
    }
    private static int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private void updateProgress(ImportTaskPO task, Counters counters) {
        task.setProgress(counters.total == 0 ? 100
                : Math.min(99, (int) (counters.processed * 100L / counters.total)));
        task.setCreatedCount(counters.created);
        task.setUpdatedCount(counters.updated + counters.metadataOnly + counters.renamed);
        task.setDeletedCount(counters.deleted);
        task.setFailedCount(counters.failed);
        task.setTotalChunks(counters.totalChunks);
        importTaskMapper.updateById(task);
    }

    private void recordItem(ImportTaskPO task, String sourcePath, ImportAction action,
                            Long documentId, String oldHash, String newHash,
                            int chunkCount, int durationMs, String message) {
        ImportTaskItemPO item = new ImportTaskItemPO();
        item.setTaskId(task.getId());
        item.setKbId(task.getKbId());
        item.setSourcePath(sourcePath);
        item.setAction(action.name());
        item.setDocumentId(documentId);
        item.setOldHash(oldHash);
        item.setNewHash(newHash);
        item.setChunkCount(chunkCount);
        item.setDurationMs(durationMs);
        item.setMessage(truncate(message, 900));
        item.setCreatedAt(LocalDateTime.now());
        importTaskItemMapper.insert(item);
    }

    private static ImportTaskResponse toResponse(ImportTaskPO task) {
        return ImportTaskResponse.of(task);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
