package com.lky.nexus.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.common.util.HashUtils;
import com.lky.nexus.knowledge.chunk.ChunkingOptions;
import com.lky.nexus.knowledge.chunk.ChunkerFactory;
import com.lky.nexus.knowledge.chunk.DocumentChunker;
import com.lky.nexus.knowledge.chunk.TextChunk;
import com.lky.nexus.knowledge.imports.ScannedFile;
import com.lky.nexus.knowledge.imports.VaultFile;
import com.lky.nexus.knowledge.imports.VaultPackageExtractor;
import com.lky.nexus.knowledge.imports.VaultScanOptions;
import com.lky.nexus.knowledge.imports.VaultScanResult;
import com.lky.nexus.knowledge.imports.VaultSyncPlan;
import com.lky.nexus.knowledge.imports.VaultSyncPlanner;
import com.lky.nexus.knowledge.mapper.DocumentMapper;
import com.lky.nexus.knowledge.mapper.ImportTaskItemMapper;
import com.lky.nexus.knowledge.mapper.ImportTaskMapper;
import com.lky.nexus.knowledge.model.ImportAction;
import com.lky.nexus.knowledge.model.ImportTaskStatus;
import com.lky.nexus.knowledge.model.ImportTaskType;
import com.lky.nexus.knowledge.model.dto.ImportTaskResponse;
import com.lky.nexus.knowledge.model.entity.DocumentPO;
import com.lky.nexus.knowledge.model.entity.ImportTaskItemPO;
import com.lky.nexus.knowledge.model.entity.ImportTaskPO;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.parser.ParsedDocument;
import com.lky.nexus.knowledge.service.DocumentPersistResult;
import com.lky.nexus.knowledge.service.DocumentPersistService;
import com.lky.nexus.knowledge.service.KnowledgeBaseService;
import com.lky.nexus.knowledge.service.KnowledgeImportService;
import com.lky.nexus.knowledge.storage.DocumentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Vault 导入编排（含 SHA-256 增量同步）。
 *
 * <p>流程：<b>安全解压 → 增量比对（不解析）→ 按计划处理 → 差集删除 → 汇总</b>。
 *
 * <p>增量同步的省钱逻辑（嵌入选 Phase 3 接入后会更明显）：
 * <pre>
 *   file_hash 相同           → SKIPPED        不解析、不写库（整个 Vault 重导可以近乎零成本）
 *   file_hash 变、content 不变 → METADATA_ONLY  只更新元数据，<b>保留切片与向量</b>
 *   正文也变了                → UPDATED        重建切片（后续会重新嵌入）
 *   新路径 + 旧文档同 hash     → RENAMED        只改路径，<b>保留切片与向量</b>
 *   清单里没有                → DELETED        删文档、切片、标签与原始文件
 * </pre>
 *
 * <p>三个刻意的设计：
 * <ol>
 *   <li><b>单文件失败不终止整批</b>：一篇笔记的 frontmatter 写坏不该让另外 999 篇也进不来；
 *       失败记录到任务明细（含原因），任务最终状态为 {@code PARTIAL}；</li>
 *   <li><b>SKIPPED 不写明细</b>：否则一次"什么都没改"的重导会插入上千行明细，
 *       把增量同步"零写入"的优势直接抹掉（跳过数量在任务行里已有汇总）；</li>
 *   <li><b>删除只在完整 Vault 同步时判定</b>：单文件上传时清单只有一个文件，
 *       若也按"清单里没有就删"处理，会把库里其余文档全部误删——这是增量同步最危险的失误点。</li>
 * </ol>
 */
@Service
public class KnowledgeImportServiceImpl implements KnowledgeImportService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeImportServiceImpl.class);

    /** 每处理多少个文件更新一次任务进度。 */
    private static final int PROGRESS_BATCH = 10;

    private final KnowledgeBaseService knowledgeBaseService;
    private final VaultPackageExtractor vaultPackageExtractor;
    private final VaultSyncPlanner vaultSyncPlanner;
    private final com.lky.nexus.knowledge.parser.ParserRegistry parserRegistry;
    private final ChunkerFactory chunkerFactory;
    private final DocumentPersistService documentPersistService;
    private final DocumentStorage documentStorage;
    private final DocumentMapper documentMapper;
    private final ImportTaskMapper importTaskMapper;
    private final ImportTaskItemMapper importTaskItemMapper;

    public KnowledgeImportServiceImpl(KnowledgeBaseService knowledgeBaseService,
                                      VaultPackageExtractor vaultPackageExtractor,
                                      VaultSyncPlanner vaultSyncPlanner,
                                      com.lky.nexus.knowledge.parser.ParserRegistry parserRegistry,
                                      ChunkerFactory chunkerFactory,
                                      DocumentPersistService documentPersistService,
                                      DocumentStorage documentStorage,
                                      DocumentMapper documentMapper,
                                      ImportTaskMapper importTaskMapper,
                                      ImportTaskItemMapper importTaskItemMapper) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.vaultPackageExtractor = vaultPackageExtractor;
        this.vaultSyncPlanner = vaultSyncPlanner;
        this.parserRegistry = parserRegistry;
        this.chunkerFactory = chunkerFactory;
        this.documentPersistService = documentPersistService;
        this.documentStorage = documentStorage;
        this.documentMapper = documentMapper;
        this.importTaskMapper = importTaskMapper;
        this.importTaskItemMapper = importTaskItemMapper;
    }

    @Override
    public ImportTaskResponse importVault(Long userId, Long knowledgeBaseId, byte[] zipBytes) {
        KnowledgeBasePO knowledgeBase = knowledgeBaseService.requireOwned(userId, knowledgeBaseId);
        VaultScanResult scan = vaultPackageExtractor.extract(zipBytes, VaultScanOptions.defaults());

        ImportTaskPO task = createTask(userId, knowledgeBase, scan);
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
        // 完整 Vault 同步才允许判定删除
        VaultSyncPlan plan = vaultSyncPlanner.plan(existingDocuments, scannedFiles, true);

        Map<String, DocumentPO> missingByHash = vaultSyncPlanner.indexByFileHash(plan.missing());

        Counters counters = new Counters(totalFiles(plan, scan));
        counters.skipped += plan.unchanged().size();

        // ---------- 第二阶段：字节变了 → 解析后判断"只改元数据"还是"正文也变" ----------
        for (ScannedFile scanned : plan.changed()) {
            counters.accept(processFile(task, scanned, () -> {
                ParsedDocument parsed = parse(scanned.file());
                DocumentPO document = existingByPath.get(scanned.file().sourcePath());
                String contentHash = HashUtils.sha256Hex(parsed.content());
                if (document != null && contentHash.equals(document.getContentHash())) {
                    documentPersistService.updateMetadataOnly(userId, knowledgeBase, document,
                            scanned.file(), parsed);
                    return new FileOutcome(ImportAction.METADATA_ONLY, document.getId(),
                            nz(document.getChunkCount()));
                }
                List<TextChunk> chunks = chunker.chunk(parsed, chunkingOptions);
                String storedPath = documentStorage.store(knowledgeBase.getId(),
                        scanned.file().sourcePath(), scanned.file().content());
                DocumentPersistResult result = documentPersistService.save(userId, knowledgeBase,
                        scanned.file(), parsed, chunks, storedPath);
                return new FileOutcome(ImportAction.UPDATED, result.document().getId(), chunks.size());
            }));
        }

        // ---------- 第三阶段：新路径 → 先按 file_hash 认领改名，否则新增 ----------
        for (ScannedFile scanned : plan.added()) {
            counters.accept(processFile(task, scanned, () -> {
                ParsedDocument parsed = parse(scanned.file());
                DocumentPO renamedFrom = vaultSyncPlanner.claimRename(missingByHash, scanned);
                if (renamedFrom != null) {
                    documentPersistService.relocate(userId, knowledgeBase, renamedFrom,
                            scanned.file(), parsed);
                    log.debug("识别为改名：{} → {}", renamedFrom.getSourcePath(), scanned.file().sourcePath());
                    return new FileOutcome(ImportAction.RENAMED, renamedFrom.getId(),
                            nz(renamedFrom.getChunkCount()));
                }
                List<TextChunk> chunks = chunker.chunk(parsed, chunkingOptions);
                String storedPath = documentStorage.store(knowledgeBase.getId(),
                        scanned.file().sourcePath(), scanned.file().content());
                DocumentPersistResult result = documentPersistService.save(userId, knowledgeBase,
                        scanned.file(), parsed, chunks, storedPath);
                return new FileOutcome(ImportAction.CREATED, result.document().getId(), chunks.size());
            }));
        }

        // ---------- 第四阶段：没被认领的"消失文档" → 删除 ----------
        for (DocumentPO document : missingByHash.values()) {
            long startMillis = System.currentTimeMillis();
            try {
                documentPersistService.delete(userId, document);
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

        // 被跳过的压缩包条目（隐藏目录、不支持类型）也计入统计，让用户看到完整情况
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

        documentPersistService.refreshKnowledgeBaseStats(knowledgeBase.getId());

        log.info("Vault 同步完成 kbId={}，新增 {} / 正文更新 {} / 元数据更新 {} / 改名 {} / 跳过 {} / 删除 {} / 失败 {}，切片 {}",
                knowledgeBase.getId(), counters.created, counters.updated, counters.metadataOnly,
                counters.renamed, counters.skipped, counters.deleted, counters.failed, counters.totalChunks);
        return toResponse(task);
    }

    @Override
    public ImportTaskResponse getTask(Long userId, Long taskId) {
        ImportTaskPO task = importTaskMapper.selectOne(Wrappers.<ImportTaskPO>lambdaQuery()
                .eq(ImportTaskPO::getId, taskId)
                .eq(ImportTaskPO::getUserId, userId));
        if (task == null) {
            throw new BusinessException(ErrorCode.IMPORT_TASK_NOT_FOUND);
        }
        return toResponse(task);
    }

    // ------------------------------------------------------------------
    // 单文件处理与计数
    // ------------------------------------------------------------------

    /** 单文件处理逻辑（返回动作与文档标识，供记明细）。 */
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

    /** 执行单文件处理：统一异常处理与明细记录（这是"一个文件失败不影响其他文件"的落点）。 */
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

    private ParsedDocument parse(VaultFile file) {
        // 第一版按 UTF-8 解码；非 UTF-8 文件会出现替换字符（已记录为已知限制）
        return parserRegistry.requireParser(file.fileName())
                .parse(file.fileName(), new String(file.content(), StandardCharsets.UTF_8));
    }

    private int totalFiles(VaultSyncPlan plan, VaultScanResult scan) {
        return plan.unchanged().size() + plan.changed().size() + plan.added().size()
                + plan.missing().size() + scan.skipped().size();
    }

    private ImportTaskPO createTask(Long userId, KnowledgeBasePO knowledgeBase, VaultScanResult scan) {
        ImportTaskPO task = new ImportTaskPO();
        task.setKbId(knowledgeBase.getId());
        task.setUserId(userId);
        task.setTaskType(ImportTaskType.VAULT_ZIP.name());
        task.setStatus(ImportTaskStatus.RUNNING.name());
        task.setTotalFiles(scan.files().size());
        task.setCreatedCount(0);
        task.setUpdatedCount(0);
        task.setSkippedCount(0);
        task.setDeletedCount(0);
        task.setFailedCount(0);
        task.setTotalChunks(0);
        task.setTotalTokens(0L);
        task.setProgress(0);
        task.setStartedAt(LocalDateTime.now());
        task.setCreatedAt(LocalDateTime.now());
        importTaskMapper.insert(task);
        return task;
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
        item.setMessage(message);
        item.setCreatedAt(LocalDateTime.now());
        importTaskItemMapper.insert(item);
    }

    private ImportTaskResponse toResponse(ImportTaskPO task) {
        return new ImportTaskResponse(
                task.getId(),
                task.getKbId(),
                task.getTaskType(),
                task.getStatus(),
                task.getTotalFiles() == null ? 0 : task.getTotalFiles(),
                nz(task.getCreatedCount()),
                nz(task.getUpdatedCount()),
                nz(task.getSkippedCount()),
                nz(task.getDeletedCount()),
                nz(task.getFailedCount()),
                nz(task.getTotalChunks()),
                task.getTotalTokens() == null ? 0L : task.getTotalTokens(),
                nz(task.getProgress()),
                task.getStartedAt(),
                task.getFinishedAt(),
                task.getErrorMsg());
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
