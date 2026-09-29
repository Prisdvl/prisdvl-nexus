package com.lky.nexus.app.chat.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息表实体（chat_message）。
 *
 * <p>这是<b>完整聊天历史</b>（Chat History），与 Spring AI 的 ChatMemory（发给模型的有界窗口）
 * 是两回事，详见 ADR-011。Token / 延迟 / requestId 这些字段现在就预留好，
 * Phase 10 的观测看板直接从这里取数，不必再改表。
 */
@Data
@TableName("chat_message")
public class ChatMessagePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long conversationId;

    /** 冗余 userId：既要支持"按用户审计"，也避免每次查询都回连会话表 */
    private Long userId;

    /** USER / ASSISTANT / SYSTEM / TOOL */
    private String role;

    private String content;

    /** 推理型模型的思考内容（不展示给用户，但保留供排查） */
    private String reasoningContent;

    private String model;

    private String provider;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    /** 端到端耗时 */
    private Integer latencyMs;

    /** 首 Token 延迟：流式体验的核心指标 */
    private Integer firstTokenMs;

    private String finishReason;

    /** 与响应头 X-Request-Id 一致，用于把一条消息串到全部日志 */
    private String requestId;

    /** Phase 9：本次调用使用的提示词版本 */
    private Long promptVersionId;

    /** 重新生成时指向被重生成的消息 */
    private Long parentMessageId;

    /** STREAMING / DONE / STOPPED / FAILED */
    private String status;

    private String errorMsg;

    /** 预留扩展信息（JSON） */
    private String metadata;

    private LocalDateTime createdAt;
}
