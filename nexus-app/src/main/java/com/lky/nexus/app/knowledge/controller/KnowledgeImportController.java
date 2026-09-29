package com.lky.nexus.app.knowledge.controller;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.Result;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.model.dto.ImportTaskResponse;
import com.lky.nexus.knowledge.service.KnowledgeImportService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;

/**
 * 知识导入接口。
 *
 * <p>导入是<b>异步</b>的：{@code POST /vault} 只做"建任务 + 压缩包落暂存 + 投递消息"，
 * 立刻返回一个 {@code QUEUED} 状态的任务；真正处理由消费者线程完成，
 * 前端拿 taskId 轮询 {@code GET /import-tasks/{id}} 看进度。
 *
 * <p>当 {@code nexus.import.mode=sync}（standalone profile / 集成测试，
 * 环境里没有 RabbitMQ）时，投递器会在当前线程直接执行，
 * 因此返回的可能是已经 {@code DONE} 的任务——<b>接口契约不变</b>，调用方无需区分。
 */
@RestController
@RequestMapping("/api")
public class KnowledgeImportController {

    /** ZIP 魔数：PK\x03\x04。用它做一次廉价校验，先把明显不是压缩包的请求挡在解压之前。 */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    private final KnowledgeImportService knowledgeImportService;

    public KnowledgeImportController(KnowledgeImportService knowledgeImportService) {
        this.knowledgeImportService = knowledgeImportService;
    }

    /** 导入 Obsidian Vault（ZIP）。异步模式下立即返回 QUEUED 任务。 */
    @PostMapping(value = "/knowledge-bases/{knowledgeBaseId}/vault",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ImportTaskResponse> importVault(@PathVariable Long knowledgeBaseId,
                                                 @RequestPart("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "上传文件为空");
        }
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new BusinessException(ErrorCode.INVALID_ZIP_STRUCTURE,
                    "Vault 导入需要 ZIP 文件，收到：" + fileName);
        }
        byte[] bytes = file.getBytes();
        if (!isZip(bytes)) {
            throw new BusinessException(ErrorCode.INVALID_ZIP_STRUCTURE, "文件内容不是合法的 ZIP 压缩包");
        }
        // 归属校验在服务层（requireOwned）：不是自己的知识库一律 404
        return Result.ok(knowledgeImportService.enqueueVaultImport(CurrentUser.requireId(), knowledgeBaseId, bytes));
    }

    /** 查询导入任务状态与结果（异步模式下的进度轮询入口）。 */
    @GetMapping("/import-tasks/{taskId}")
    public Result<ImportTaskResponse> getTask(@PathVariable Long taskId) {
        return Result.ok(knowledgeImportService.getTask(CurrentUser.requireId(), taskId));
    }

    /** 重试部分成功/失败的任务（复用暂存区里的原始压缩包，用户不必重新上传）。 */
    @PostMapping("/import-tasks/{taskId}/retry")
    public Result<ImportTaskResponse> retryTask(@PathVariable Long taskId) {
        return Result.ok(knowledgeImportService.retryTask(CurrentUser.requireId(), taskId));
    }

    private boolean isZip(byte[] bytes) {
        if (bytes.length < ZIP_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < ZIP_MAGIC.length; i++) {
            if (bytes[i] != ZIP_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }
}
