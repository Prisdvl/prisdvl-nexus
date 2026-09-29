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
 * <p>当前的 Vault 导入是<b>同步</b>执行的：请求会一直等到整批处理完才返回。
 * 对几十篇笔记够用，但对上千篇的 Vault 不合适（会占用 Tomcat 线程直到超时），
 * 因此 Step 2.7 会改成"立即返回 QUEUED 任务 + MQ 异步处理"，接口契约不变。
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

    /** 导入 Obsidian Vault（ZIP）。 */
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
        return Result.ok(knowledgeImportService.importVault(CurrentUser.requireId(), knowledgeBaseId, bytes));
    }

    /** 查询导入任务状态与结果。 */
    @GetMapping("/import-tasks/{taskId}")
    public Result<ImportTaskResponse> getTask(@PathVariable Long taskId) {
        return Result.ok(knowledgeImportService.getTask(CurrentUser.requireId(), taskId));
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
