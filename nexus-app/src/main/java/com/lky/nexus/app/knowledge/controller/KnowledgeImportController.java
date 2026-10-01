package com.lky.nexus.app.knowledge.controller;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.Result;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.model.dto.ImportTaskItemView;
import com.lky.nexus.knowledge.model.dto.ImportTaskResponse;
import com.lky.nexus.knowledge.service.KnowledgeImportService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * 知识导入接口。
 *
 * <p>导入是<b>异步</b>的：两个上传入口（整个 Vault 的 ZIP、单个文件）都只做
 * "建任务 + 原始字节落暂存 + 投递消息"，立刻返回一个 {@code QUEUED} 状态的任务；
 * 真正处理由消费者线程完成，前端拿 taskId 轮询 {@code GET /import-tasks/{id}} 看进度。
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

    /**
     * 上传单个文件（P1-1）：TXT / MD / PDF / DOCX，一个文件一个任务。
     *
     * <p>它和 {@link #importVault} 是同一件事的两种入口，所以刻意放在同一个 Controller、
     * 返回同一个 {@code ImportTaskResponse}：用户拿到 taskId 之后的动作完全一样
     * （轮询 {@code /api/import-tasks/{id}}、失败后 retry），有第二条流水线就会有第二套语义。
     *
     * <p>这里只判"请求长得对不对"（有没有文件、是不是空的）；<b>扩展名与大小上限在服务层</b>，
     * 因为那两件事是业务判定——换一个调用方（MCP 工具、批量导入）也必须成立，
     * 放在 Controller 里就成了"换个入口就绕过"。
     */
    @PostMapping(value = "/knowledge-bases/{knowledgeBaseId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ImportTaskResponse> uploadDocument(@PathVariable Long knowledgeBaseId,
                                                    @RequestPart("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "上传文件为空");
        }
        return Result.ok(knowledgeImportService.enqueueFileImport(
                CurrentUser.requireId(), knowledgeBaseId, file.getOriginalFilename(), file.getBytes()));
    }

    /** 查询导入任务状态与结果（异步模式下的进度轮询入口）。 */
    @GetMapping("/import-tasks/{taskId}")
    public Result<ImportTaskResponse> getTask(@PathVariable Long taskId) {
        return Result.ok(knowledgeImportService.getTask(CurrentUser.requireId(), taskId));
    }

    /**
     * 逐文件明细（P1-3 / P1-4）：为什么这几个文件没进来 / 失败了。
     *
     * <p>与 {@link #getTask} 是一对：任务视图给出"失败 3 个"，明细给出"哪 3 个、为什么"。
     * 界面上的默认诉求是看失败项，所以 {@code action} 做成服务端过滤而不是返回全量让前端筛
     * ——一个 Vault 上千条明细，失败的可能只有 3 条，靠 limit 截断根本取不到它们。
     */
    @GetMapping("/import-tasks/{taskId}/items")
    public Result<List<ImportTaskItemView>> listItems(@PathVariable Long taskId,
                                                      @RequestParam(required = false) String action,
                                                      @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(knowledgeImportService.listItems(
                CurrentUser.requireId(), taskId, action, limit));
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
