package com.lky.nexus.app.notify;

import com.lky.nexus.knowledge.imports.event.ImportFinishedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 导入完成事件 → 通知事件的映射。
 *
 * <p>关键取舍：PARTIAL（部分失败）必须归到 {@code import.failed}。用户订阅这两条就是为了
 * "有没有东西没进来"，把部分失败标成"完成"会让它被忽略——这正是需要通知的时刻。
 */
@DisplayName("ImportFinishedNotifier")
class ImportFinishedNotifierTest {

    private final NotifyService notifyService = mock(NotifyService.class);
    private final ImportFinishedNotifier notifier = new ImportFinishedNotifier(notifyService);

    private static ImportFinishedEvent event(String status) {
        return new ImportFinishedEvent(7L, 11L, 3L, "我的笔记", "UPLOAD", status, 3, 1, 0, 2, 42);
    }

    @Test
    @DisplayName("DONE → import.done，标题带知识库名，正文带计数")
    void doneMapsToImportDone() {
        notifier.onImportFinished(event("DONE"));

        verify(notifyService).dispatch(eq(7L), eq(NotifyEvent.IMPORT_DONE),
                eq("导入完成：我的笔记"), contains("共 42 个切片"));
    }

    @Test
    @DisplayName("PARTIAL → import.failed（部分失败必须被看见）")
    void partialMapsToImportFailed() {
        notifier.onImportFinished(event("PARTIAL"));

        verify(notifyService).dispatch(eq(7L), eq(NotifyEvent.IMPORT_FAILED),
                contains("导入未完全成功"), contains("失败 2"));
    }

    @Test
    @DisplayName("知识库名缺失时用 id 兜底，不抛异常")
    void missingNameFallsBackToId() {
        notifier.onImportFinished(new ImportFinishedEvent(7L, 11L, 3L, null, "UPLOAD", "FAILED", 0, 0, 0, 1, 0));

        verify(notifyService).dispatch(eq(7L), eq(NotifyEvent.IMPORT_FAILED), contains("知识库 #3"), any());
    }

    @Test
    @DisplayName("没有发起人就不发（事件来自系统任务时）")
    void skipsWithoutUser() {
        notifier.onImportFinished(new ImportFinishedEvent(null, 11L, 3L, "x", "UPLOAD", "DONE", 1, 0, 0, 0, 1));

        verifyNoInteractions(notifyService);
    }

    private static String any() {
        return org.mockito.ArgumentMatchers.anyString();
    }
}
