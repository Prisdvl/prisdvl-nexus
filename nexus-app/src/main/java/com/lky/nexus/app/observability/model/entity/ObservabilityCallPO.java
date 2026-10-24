package com.lky.nexus.app.observability.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型调用明细（{@code observability_call}，V31）。
 *
 * <p><b>一次模型调用一行</b>，不是一次请求一行：工具循环会让一次请求穿过模型多次，
 * 只有逐轮落库才算得出真实成本；"一次请求"是查询侧按 {@code request_id} 聚合出来的视图。
 *
 * <p>token / 首 Token / 错误码允许为 NULL，理由见 {@code CallTrace}：
 * 供应商不回传 usage 时写 0 会让报表说谎。
 *
 * <p>没有 {@code deleted} 列，也不做逻辑删除：这是审计性质的事实表，
 * 行一旦写下就不该被应用层改口（与 {@code wf_run} / {@code automation_run} 一致）。
 */
@Data
@TableName("observability_call")
public class ObservabilityCallPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 与响应头 {@code X-Request-Id} 同源；同一请求的多轮调用共用同一值。 */
    private String requestId;

    /** 归属用户；重排等系统调用为 {@code null}（不进任何用户的报表）。 */
    private Long userId;

    /** chat / chat.stream / rag / rag.stream / agent / agent.stream / rerank */
    private String scene;

    private String model;

    private Integer tokensIn;

    private Integer tokensOut;

    private Long latencyMs;

    private Long firstTokenMs;

    /** 1 正常结束 / 0 失败。 */
    private Integer success;

    private Integer errorCode;

    private LocalDateTime createdAt;
}
