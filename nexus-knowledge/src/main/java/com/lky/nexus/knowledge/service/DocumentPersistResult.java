package com.lky.nexus.knowledge.service;

import com.lky.nexus.knowledge.model.entity.DocumentPO;

/**
 * 落库结果。
 *
 * <p>为什么要返回 {@code created}：导入任务需要分别统计"新增"与"更新"，
 * 而"是否新建"只有落库那一刻才知道（要按 (kb_id, source_path) 查过）。
 * 让调用方事后靠 {@code version == 1} 之类去猜，是在制造隐患。
 */
public record DocumentPersistResult(DocumentPO document, boolean created) {
}
