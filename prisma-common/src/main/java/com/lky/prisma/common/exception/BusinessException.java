package com.lky.prisma.common.exception;

import com.lky.prisma.common.api.ErrorCode;

/**
 * 业务异常：业务规则不满足时抛出，由 Web 层的 {@code GlobalExceptionHandler} 统一转换为返回体。
 *
 * <p>使用方式：
 * <pre>{@code
 * throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
 * throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "文档 " + name + " 解析失败");
 * }</pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li>继承 {@link RuntimeException}：业务异常不需要强制捕获，避免污染方法签名；</li>
 *   <li>保留 {@code code}：Web 层据此决定 HTTP 状态码与 body 中的业务码；</li>
 *   <li>不吞堆栈：DEBUG 日志里仍能看到抛出位置，便于排查。</li>
 * </ul>
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
