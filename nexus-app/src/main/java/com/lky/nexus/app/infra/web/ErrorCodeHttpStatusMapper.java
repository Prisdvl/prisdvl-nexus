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

            // 4xxx AI 依赖：上游不可用/超时 → 502/503/504，便于监控区分"自己挂了"还是"模型挂了"
            case MODEL_UNAVAILABLE, VECTOR_STORE_UNAVAILABLE, MCP_SERVER_UNAVAILABLE, EVAL_JUDGE_MODEL_UNAVAILABLE ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case MODEL_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case TOKEN_LIMIT_EXCEEDED, EMBEDDING_FAILED, MODEL_ROLE_NOT_CONFIGURED -> HttpStatus.BAD_GATEWAY;

            // 5xxx / 6xxx / 9999：服务端自身问题
            case TOOL_EXECUTION_FAILED, AGENT_MAX_ITERATIONS_EXCEEDED, WORKFLOW_DEFINITION_INVALID,
                 WORKFLOW_NODE_FAILED, EVAL_DATASET_EMPTY, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;

            // 7xxx 限流
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;

            // 8xxx 会话 / 消息
            // 注意：访问别人的会话返回 404 而不是 403 ——
            // 403 等于告诉攻击者"这个 id 确实存在"，而 404 不泄露资源存在性（防资源枚举）。
            case CONVERSATION_NOT_FOUND, MESSAGE_NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
    }
}
