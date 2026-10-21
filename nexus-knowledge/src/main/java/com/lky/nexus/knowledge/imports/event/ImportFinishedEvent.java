package com.lky.nexus.knowledge.imports.event;

/**
 * 导入任务结束（DONE / PARTIAL / FAILED）后发布的领域事件。
 *
 * <p>为什么用 Spring 事件而不是在 {@code ImportTaskExecutor} 里直接调通知服务：
 * knowledge 模块不该反向依赖 app 模块的通知实现（依赖方向是 app → knowledge）。
 * 事件只带"发生了什么"的客观事实，怎么通知由订阅方决定。
 *
 * @param userId       任务发起人
 * @param taskId       导入任务 id
 * @param knowledgeBaseId 目标知识库 id
 * @param knowledgeBaseName 目标知识库名称（拼不出来时为 null，订阅方自己兜底）
 * @param taskType     UPLOAD / OBSIDIAN 等
 * @param status       最终状态
 * @param created      新增文档数
 * @param updated      更新文档数
 * @param skipped      跳过数
 * @param failed       失败数
 * @param totalChunks  切片总数
 */
public record ImportFinishedEvent(
        Long userId,
        Long taskId,
        Long knowledgeBaseId,
        String knowledgeBaseName,
        String taskType,
        String status,
        int created,
        int updated,
        int skipped,
        int failed,
        int totalChunks
) {

    /** 是否"完全成功"：PARTIAL 也有人想收到通知，但文案不同，所以订阅方自己判断。 */
    public boolean succeeded() {
        return "DONE".equals(status);
    }
}
