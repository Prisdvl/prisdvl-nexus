package com.lky.nexus.app.infra.web;

import com.lky.nexus.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 业务错误码 → HTTP 状态码映射。
 *
 * <p>设计取舍（docs/api.md 1.2）：业务码表达"哪个域、什么问题"，HTTP 状态码表达"传输/协议语义"，
 * 两者都要正确，才能让前端拦截器、Nginx、监控系统各自按自己的维度处理。
 *
 * <p>这里用 <b>switch 表达式 + 枚举穷举</b>：Java 编译器会检查枚举是否被完整覆盖，
 * 以后给 {@link ErrorCode} 新增一个常量却忘了映射状态码时，<b>编译就会失败</b>，
 * 而不是悄悄返回 500。这是一种"用类型系统替代代码评审"的做法。
 */
public final class ErrorCodeHttpStatusMapper {

    private ErrorCodeHttpStatusMapper() {
    }

    public static HttpStatus toHttpStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case SUCCESS -> HttpStatus.OK;

            // 1xxx 参数类问题：客户端的错 → 4xx
            case PARAM_INVALID, REQUEST_BODY_INVALID, MISSING_PARAMETER, INVALID_ZIP_STRUCTURE ->
                    HttpStatus.BAD_REQUEST;
            case METHOD_NOT_SUPPORTED -> HttpStatus.METHOD_NOT_ALLOWED;

            // 2xxx 认证授权
            case UNAUTHENTICATED, TOKEN_EXPIRED, INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, ACCOUNT_DISABLED, TOOL_PERMISSION_DENIED, APPROVAL_REJECTED, QUOTA_EXHAUSTED ->
                    HttpStatus.FORBIDDEN;
            case USERNAME_ALREADY_EXISTS -> HttpStatus.CONFLICT;

            // 3xxx 知识库 / 文档
            case KNOWLEDGE_BASE_NOT_FOUND, DOCUMENT_NOT_FOUND, IMPORT_TASK_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case KNOWLEDGE_BASE_NAME_DUPLICATED -> HttpStatus.CONFLICT;
            case FILE_TYPE_UNSUPPORTED -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case FILE_SIZE_EXCEEDED -> HttpStatus.CONTENT_TOO_LARGE;
            case DOCUMENT_PARSE_FAILED -> HttpStatus.UNPROCESSABLE_CONTENT;
            // 消息中间件属于外部依赖：投递失败返回 503 而不是 500，
            // 这样监控能区分"自己写错了"与"依赖挂了"，客户端也知道可以稍后重试
            case IMPORT_TASK_PUBLISH_FAILED -> HttpStatus.SERVICE_UNAVAILABLE;

            // 4xxx AI 依赖：上游不可用/超时 → 502/503/504，便于监控区分"自己挂了"还是"模型挂了"
            case MODEL_UNAVAILABLE, VECTOR_STORE_UNAVAILABLE, MCP_SERVER_UNAVAILABLE, EVAL_JUDGE_MODEL_UNAVAILABLE ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case MODEL_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            // 4007 用 502 而不是 401：401 是"你（调用方）没通过认证"，
            // 而这里是我们作为网关去调上游时被拒——错在我们的配置，不在客户端，
            // 返回 401 会诱导前端去清 Token、跳登录页，那是完全错误的自救动作
            case TOKEN_LIMIT_EXCEEDED, EMBEDDING_FAILED, MODEL_ROLE_NOT_CONFIGURED,
                 MODEL_AUTH_FAILED -> HttpStatus.BAD_GATEWAY;

            // 5xxx / 6xxx / 9999：服务端自身问题
            case TOOL_EXECUTION_FAILED, AGENT_MAX_ITERATIONS_EXCEEDED, WORKFLOW_DEFINITION_INVALID,
                 WORKFLOW_NODE_FAILED, EVAL_DATASET_EMPTY, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;

            // 5xxx Agent 资源的"用户操作错"：id 不存在 → 404（不泄露存在性，与其他资源一致）；
            // 状态/归属类拒绝 → 409，语义是"当前资源状态不允许这个操作"，
            // 与 1001 参数错误区分开——用户的请求格式是对的，是资源状态不对
            case AGENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case AGENT_CODE_DUPLICATED, AGENT_NOT_PUBLISHED, AGENT_DISABLED, AGENT_BUILTIN_READONLY ->
                    HttpStatus.CONFLICT;

            // 4.3 Tool：不认识这个工具 code → 404（与上面同一个理由：不泄露"存在过什么"）。
            // 注意它同时用在写入侧（Agent 绑了不存在的工具）——那是"你填的值不对"，
            // 但对外仍然是"这个 code 没有对应的资源"，前端按 404 展示即可，不必分成两个码
            case TOOL_NOT_FOUND -> HttpStatus.NOT_FOUND;

            // 7xxx 限流
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;

            // 8xxx 会话 / 消息
            // 注意：访问别人的会话返回 404 而不是 403 ——
            // 403 等于告诉攻击者"这个 id 确实存在"，而 404 不泄露资源存在性（防资源枚举）。
            case CONVERSATION_NOT_FOUND, MESSAGE_NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
    }
}
