package com.lky.nexus.app.chat.service;

import com.lky.nexus.app.chat.model.dto.ChatAnswerResponse;

/**
 * 对话服务（非流式）。
 *
 * <p>流式版本（SSE）在 Step 1.6 实现。本步先把"记忆 + 落库 + 模型调用"这条链路走通，
 * 因为流式只是把"等完整结果"换成"边生成边推"，核心逻辑完全复用。
 */
public interface ChatService {

    /**
     * 发起一轮提问并返回完整回答。
     *
     * <p>注意：本方法<b>不加 {@code @Transactional}</b>。模型调用是秒级的外部 IO，
     * 若把数据库事务包在它外面，会长时间占用连接与行锁——这是很常见的性能事故来源。
     * 落库由 {@code ChatMessageService} 内部各自的小事务完成。
     */
    ChatAnswerResponse ask(Long userId, Long conversationId, String content);
}
