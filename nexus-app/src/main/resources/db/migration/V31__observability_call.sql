-- =====================================================================
-- V31：模型调用明细（观测 §10.1）
--
-- 目标：让 /api/observability/* 的五个只读端点有真实数据可读，而不是从
--       chat_message 里反推（那里只有"回答成功"的调用，失败与重排根本不在）。
-- 只增不改：本脚本只新建 observability_call 一张表，不触碰任何既有表。
-- 为什么按"一次模型调用一行"而不是"一次请求一行"：工具循环会让一次请求
--       穿过模型 3~5 次，只有逐轮落库才能算出真实成本；而"一次请求"是查询侧
--       按 request_id 聚合出来的视图，不需要在写时决定粒度。
-- 为什么 user_id 允许 NULL：重排（rerank）是系统调用，没有归属用户，
--       但它确实花钱——记下来才不会让系统开销在报表里凭空消失。
-- =====================================================================

CREATE TABLE observability_call
(
    id             BIGINT       NOT NULL COMMENT '雪花 ID（应用生成）',
    request_id     VARCHAR(64)  DEFAULT NULL COMMENT '与响应头 X-Request-Id 同源；同一请求的多轮调用共用',
    user_id        BIGINT       DEFAULT NULL COMMENT '归属用户：所有用户侧查询必须带此条件；系统调用为 NULL',
    scene          VARCHAR(32)  DEFAULT NULL COMMENT 'chat / chat.stream / rag / rag.stream / agent / agent.stream / rerank',
    model          VARCHAR(96)  DEFAULT NULL COMMENT '供应商返回的模型名（不是角色名：角色会随配置变）',
    tokens_in      INT          DEFAULT NULL COMMENT 'prompt token；供应商不回传时为 NULL，不写 0',
    tokens_out     INT          DEFAULT NULL COMMENT 'completion token；同上',
    latency_ms     BIGINT       NOT NULL DEFAULT 0 COMMENT '本次调用耗时（流式从订阅起算）',
    first_token_ms BIGINT       DEFAULT NULL COMMENT '首 Token 延迟；非流式为 NULL',
    success        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常结束 / 0 失败（含供应商报错）',
    error_code     INT          DEFAULT NULL COMMENT '失败时的业务码（与统一返回体同号段）',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '调用开始时间',
    PRIMARY KEY (id),
    KEY idx_obs_call_user_time (user_id, created_at),
    KEY idx_obs_call_request (request_id),
    KEY idx_obs_call_user_scene (user_id, scene, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='模型调用明细（观测 §10.1）：一次模型调用一行';
