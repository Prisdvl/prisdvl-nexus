package com.lky.nexus.app.knowledge.service;

import com.lky.nexus.knowledge.rag.RagAnswerService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * RAG 流式问答（SSE）。
 *
 * <p>为什么流式对问答特别重要：实测一次问答端到端 28~172 秒，其中检索只占百毫秒级，
 * 时间全在模型生成上。非流式时用户面对的是三分钟白屏；流式则几百毫秒内就能看到第一个字。
 * 引用的处理有个约束：**回答结束前无法知道引用了哪几段**，
 * 因此事件顺序固定为 {@code meta → delta… → done(含引用)}。
 */
public interface RagStreamService {

    /**
     * 流式回答。
     *
     * @param topK 召回条数；null = 用知识库配置的 retrieval_top_k
     * @return SSE 发射器（由 Spring MVC 负责写出）
     */
    SseEmitter stream(Long userId, Long knowledgeBaseId, String question, Integer topK,
                      List<RagAnswerService.RagTurn> history);
}
