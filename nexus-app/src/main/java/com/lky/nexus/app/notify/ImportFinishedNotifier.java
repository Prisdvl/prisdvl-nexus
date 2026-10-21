package com.lky.nexus.app.notify;

import com.lky.nexus.knowledge.imports.event.ImportFinishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 把知识库导入的结束事件翻译成用户通知。
 *
 * <p>为什么放在 app 模块：依赖方向是 app → knowledge，通知实现（Webhook / 邮件）在 app，
 * 所以监听器只能在这一侧。
 *
 * <p>PARTIAL 归到「导入失败」而不是「完成」：用户订阅这两条是为了知道"有没有东西没进来"，
 * 部分失败恰恰是最需要被看见的情况，标成"完成"会让它被忽略。
 *
 * <p>同步执行、异常全吞：通知投递失败不该让导入任务变成失败，也不该让用户看到未处理的异常日志。
 */
@Component
public class ImportFinishedNotifier {

    private static final Logger log = LoggerFactory.getLogger(ImportFinishedNotifier.class);

    private final NotifyService notifyService;

    public ImportFinishedNotifier(NotifyService notifyService) {
        this.notifyService = notifyService;
    }

    @EventListener
    public void onImportFinished(ImportFinishedEvent event) {
        if (event.userId() == null) {
            return;
        }
        boolean done = event.succeeded();
        String kb = event.knowledgeBaseName() == null || event.knowledgeBaseName().isBlank()
                ? "知识库 #" + event.knowledgeBaseId()
                : event.knowledgeBaseName();
        String title = (done ? "导入完成：" : "导入未完全成功：") + kb;
        String body = "新增 %d / 更新 %d / 跳过 %d / 失败 %d，共 %d 个切片。"
                .formatted(event.created(), event.updated(), event.skipped(), event.failed(),
                        event.totalChunks());
        try {
            notifyService.dispatch(event.userId(), done ? NotifyEvent.IMPORT_DONE : NotifyEvent.IMPORT_FAILED,
                    title, body);
        } catch (RuntimeException ex) {
            log.warn("导入通知分发失败 userId={} taskId={}", event.userId(), event.taskId(), ex);
        }
    }
}
