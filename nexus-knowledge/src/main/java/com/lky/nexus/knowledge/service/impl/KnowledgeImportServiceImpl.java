package com.lky.nexus.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.chunk.ChunkingOptions;
import com.lky.nexus.knowledge.chunk.ChunkerFactory;
import com.lky.nexus.knowledge.chunk.DocumentChunker;
import com.lky.nexus.knowledge.chunk.TextChunk;
import com.lky.nexus.knowledge.imports.VaultFile;
import com.lky.nexus.knowledge.imports.VaultPackageExtractor;
import com.lky.nexus.knowledge.imports.VaultScanOptions;
import com.lky.nexus.knowledge.imports.VaultScanResult;
import com.lky.nexus.knowledge.mapper.ImportTaskItemMapper;
import com.lky.nexus.knowledge.mapper.ImportTaskMapper;
import com.lky.nexus.knowledge.model.ImportAction;
import com.lky.nexus.knowledge.model.ImportTaskStatus;
import com.lky.nexus.knowledge.model.ImportTaskType;
import com.lky.nexus.knowledge.model.dto.ImportTaskResponse;
import com.lky.nexus.knowledge.model.entity.ImportTaskItemPO;
import com.lky.nexus.knowledge.model.entity.ImportTaskPO;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.parser.DocumentParser;
import com.lky.nexus.knowledge.parser.ParsedDocument;
import com.lky.nexus.knowledge.parser.ParserRegistry;
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
import java.util.List;

/**
 * Vault 导入编排。
 *
 * <p>流程：<b>安全解压 → 逐文件（解析 → 切分 → 存原始文件 → 落库）→ 汇总</b>。
 *
 * <p>三个刻意的设计：
 * <ol>
 *   <li><b>单文件失败不终止整批</b>：一篇笔记的 frontmatter 写坏不该让另外 999 篇也进不来。
 *       失败会记录到任务明细（含原因），任务最终状态为 {@code PARTIAL}；</li>
 *   <li><b>进度按批更新</b>：每 10 个文件写一次任务行，避免"每个文件一次 UPDATE"的写放大；</li>
 *   <li><b>统计最后算一次</b>：知识库的 docCount/chunkCount/totalTokens 在导入结束后
 *       用一条聚合查询重算，而不是在循环里累加（累加值容易与实际行数漂移）。</li>
 * </ol>
 *
 * <p>注意：本步<b>还没做增量同步</b>（按 file_hash 跳过未变化文件），那是 Step 2.6 的内容，
 * 因此现在重复导入同一个 Vault 会重新解析全部文件。
 */
@Service
public class KnowledgeImportServiceImpl implements KnowledgeImportService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeImportServiceImpl.class);

    /** 每处理多少个文件更新一次任务进度。 */
    private static final int PROGRESS_BATCH = 10;

    private final KnowledgeBaseService knowledgeBaseService;
    private final VaultPackageExtractor vaultPackageExtractor;
    private final ParserRegistry parserRegistry;
    private final ChunkerFactory chunkerFactory;
    private final DocumentPersistService documentPersistService;
    private final DocumentStorage documentStorage;
    private final ImportTaskMapper importTaskMapper;
    private final ImportTaskItemMapper importTaskItemMapper;

    public KnowledgeImportServiceImpl(KnowledgeBaseService knowledgeBaseService,
                                      VaultPackageExtractor vaultPackageExtractor,
                                      ParserRegistry parserRegistry,
                                      ChunkerFactory chunkerFactory,
                                      DocumentPersistService documentPersistService,
                                      DocumentStorage documentStorage,
                                      ImportTaskMapper importTaskMapper,
                                      ImportTaskItemMapper importTaskItemMapper) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.vaultPackageExtractor = vaultPackageExtractor;
        this.parserRegistry = parserRegistry;
        this.chunkerFactory = chunkerFactory;
        this.documentPersistService = documentPersistService;
        this.documentStorage = documentStorage;
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

        int created = 0;
        int updated = 0;
        int failed = 0;
        int totalChunks = 0;
        int processed = 0;

        for (VaultFile file : scan.files()) {
            long startMillis = System.currentTimeMillis();
            try {
                DocumentParser parser = parserRegistry.requireParser(file.fileName());
                // 第一版按 UTF-8 解码；非 UTF-8 文件会出现替换字符（已记录为已知限制）
                ParsedDocument parsed = parser.parse(file.fileName(),
                        new String(file.content(), StandardCharsets.UTF_8));
                List<TextChunk> chunks = chunker.chunk(parsed, chunkingOptions);
                String storedPath = documentStorage.store(knowledgeBase.getId(), file.sourcePath(), file.content());

                DocumentPersistResult result = documentPersistService.save(
                        userId, knowledgeBase, file, parsed, chunks, storedPath);

                if (result.created()) {
                    created++;
                } else {
                    updated++;
                }
                totalChunks += chunks.size();
                recordItem(task.getId(), knowledgeBase.getId(), file.sourcePath(),
                        result.created() ? ImportAction.CREATED : ImportAction.UPDATED,
                        result.document().getId(), null, result.document().getFileHash(),
                        chunks.size(), (int) (System.currentTimeMillis() - startMillis), null);
            } catch (BusinessException ex) {
                failed++;
                log.warn("导入失败 path={}, reason={}", file.sourcePath(), ex.getMessage());
                recordItem(task.getId(), knowledgeBase.getId(), file.sourcePath(), ImportAction.FAILED,
                        null, null, null, 0, (int) (System.currentTimeMillis() - startMillis), ex.getMessage());
            } catch (Exception ex) {
                failed++;
                log.error("导入异常 path={}", file.sourcePath(), ex);
                recordItem(task.getId(), knowledgeBase.getId(), file.sourcePath(), ImportAction.FAILED,
                        null, null, null, 0, (int) (System.currentTimeMillis() - startMillis),
                        ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }

            processed++;
            if (processed % PROGRESS_BATCH == 0) {
                updateProgress(task, processed, scan.files().size(), created, updated, failed, totalChunks);
            }
        }

        // 解的"跳过"条目（隐藏目录、不支持类型）也计入任务统计，让用户看到完整情况
        int skipped = scan.skipped().size();

        task.setCreatedCount(created);
        task.setUpdatedCount(updated);
        task.setSkippedCount(skipped);
        task.setFailedCount(failed);
        task.setTotalChunks(totalChunks);
        task.setProgress(100);
        task.setFinishedAt(LocalDateTime.now());
        task.setStatus(failed == 0 ? ImportTaskStatus.DONE.name()
                : (created + updated > 0 ? ImportTaskStatus.PARTIAL.name() : ImportTaskStatus.FAILED.name()));
        importTaskMapper.updateById(task);

        documentPersistService.refreshKnowledgeBaseStats(knowledgeBase.getId());

        log.info("Vault 导入完成 kbId={}, 新增 {} / 更新 {} / 跳过 {} / 失败 {}，切片 {} 个",
                knowledgeBase.getId(), created, updated, skipped, failed, totalChunks);
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

    private void updateProgress(ImportTaskPO task, int processed, int total,
                                int created, int updated, int failed, int totalChunks) {
        task.setProgress(total == 0 ? 100 : (int) (processed * 100L / total));
        task.setCreatedCount(created);
        task.setUpdatedCount(updated);
        task.setFailedCount(failed);
        task.setTotalChunks(totalChunks);
        importTaskMapper.updateById(task);
    }

    private void recordItem(Long taskId, Long kbId, String sourcePath, ImportAction action,
                            Long documentId, String oldHash, String newHash,
                            int chunkCount, int durationMs, String message) {
        ImportTaskItemPO item = new ImportTaskItemPO();
        item.setTaskId(taskId);
        item.setKbId(kbId);
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
