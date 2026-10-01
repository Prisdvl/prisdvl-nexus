package com.lky.nexus.knowledge.service;

import com.lky.nexus.knowledge.model.dto.ImportTaskItemView;
import com.lky.nexus.knowledge.model.dto.ImportTaskResponse;

import java.util.List;

/**
 * 知识导入服务。
 *
 * <p>接口刻意分成"入队"与"执行"两步，对应异步化的两个半场：
 * <ul>
 *   <li>enqueue 系方法：HTTP 线程执行——校验归属与文件类型、建 QUEUED 任务、
 *       原始字节落暂存、投递消息，然后<b>立刻返回</b>（用户拿到 taskId 去轮询进度）；</li>
 *   <li>执行器（{@code ImportTaskExecutor}）：消费者线程执行——真正做解压/解析/切分/落库。
 *       它是<b>幂等</b>的（CAS 抢占任务状态），因为消息中间件保证的是"至少一次"投递。</li>
 * </ul>
 *
 * <p><b>两个入队入口共用一条执行链路</b>：{@link #enqueueVaultImport} 与
 * {@link #enqueueFileImport} 只在"文件清单从哪来"上不同（压缩包条目 vs. 单个文件），
 * 之后的增量比对、切分、落库、统计全部复用。新增导入形态时应当只加一个入队方法，
 * 而不是再写一条流水线。
 */
public interface KnowledgeImportService {

    /**
     * 入队：创建任务 + 压缩包落暂存 + 投递消息。
     *
     * @return 处于 QUEUED（异步）或已完成（同步实现）的任务
     */
    ImportTaskResponse enqueueVaultImport(Long userId, Long knowledgeBaseId, byte[] zipBytes);

    /**
     * 入队：上传单个文件（产品化 Phase 5.3 · P1-1）。
     *
     * <p>与 {@link #enqueueVaultImport} 同形，差别只有两处：任务类型是
     * {@code UPLOAD}（执行器据此知道文件清单不是压缩包），以及文件名要落库
     * （执行器在消费者线程上跑，那时已经问不到 HTTP 请求了）。
     *
     * <p><b>扩展名校验在这里做，而不是在 Controller</b>：否则下一个调用方
     * （MCP、批量导入）绕过它之后拿到的是"导入失败"，而不是一条
     * "3005 + 当前支持哪些格式"的可操作答复。Controller 里那一层只做
     * "请求长得对不对"，不做业务判定。
     *
     * @param fileName 原始文件名（同时用作 {@code source_path}：同名再传即更新，
     *                 这也是"上传"与"同步"两种心智模型之间唯一自然的对齐方式）
     * @throws com.lky.nexus.common.exception.BusinessException 3005 不支持的扩展名；
     *         400 参数非法（文件名为空 / 含非法字符 / 超过单文件上限）
     */
    ImportTaskResponse enqueueFileImport(Long userId, Long knowledgeBaseId, String fileName, byte[] content);

    /** 查询导入任务（校验归属）。 */
    ImportTaskResponse getTask(Long userId, Long taskId);

    /**
     * 逐文件明细：哪个文件被做了什么、为什么（产品化 Phase 5.3 · P1-3/P1-4）。
     *
     * <p>{@code kb_import_task_item} 是"失败原因"唯一的存放处——文档表上的
     * {@code status/error_msg} 从来不会写成失败（落库成功的文档就是 READY，
     * 失败的文件根本不会有文档行）。因此在明细接口出现之前，
     * {@code ImportTaskResponse.failedCount} 是一个<b>没有下文</b>的数字。
     *
     * <p>必须有 {@code limit}：一个真实 Vault 会产生上千条明细，全量返回对前端毫无用处
     * （用户不会滑完 1000 行），却会让一次"看一眼为什么失败"变成几 MB 的响应。
     *
     * @param action 可选的动作过滤（{@code FAILED} / {@code SKIPPED} / …，大小写不敏感）；
     *               为空表示不过滤。之所以做成过滤而不是让前端自己筛：界面上最常见的诉求
     *               恰恰是"只看失败的"，而失败条目在 1000 条里可能只有 3 条——
     *               靠 limit 截断是取不到它们的
     * @param limit  返回条数上限（会被收敛到 1..{@link #MAX_ITEM_LIMIT}）
     * @throws com.lky.nexus.common.exception.BusinessException 3008 任务不存在或不属于该用户
     */
    List<ImportTaskItemView> listItems(Long userId, Long taskId, String action, int limit);

    /** 明细接口单次返回的条数上限。 */
    int MAX_ITEM_LIMIT = 500;

    /** 重试失败任务（复用暂存区的压缩包）。 */
    ImportTaskResponse retryTask(Long userId, Long taskId);
}
