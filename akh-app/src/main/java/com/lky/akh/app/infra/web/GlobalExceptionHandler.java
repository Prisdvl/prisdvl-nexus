package com.lky.akh.app.infra.web;

import com.lky.akh.common.api.ErrorCode;
import com.lky.akh.common.api.Result;
import com.lky.akh.common.exception.BusinessException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器：把任何异常统一转换成 {@code {code, message, data}} 返回体。
 *
 * <p>分层原则：业务代码只抛 {@link BusinessException}，不需要 try-catch；
 * "什么错 → 什么 HTTP 状态码 + 什么业务码"的决策全部集中在这里，
 * 这样接口契约不会因不同开发者写法不同而漂移。
 *
 * <p>日志级别也是刻意区分的：
 * <ul>
 *   <li>业务异常 = 预期内的分支（用户点了个不存在的知识库）→ <b>WARN</b>，不打堆栈；</li>
 *   <li>未预期异常 = 真 BUG → <b>ERROR</b>，打完整堆栈，并带上 requestId 便于定位。</li>
 * </ul>
 * 若把业务异常也打成 ERROR，线上告警会被噪声淹没。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：业务码与提示信息都由抛出方决定。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ErrorCode.fromCode(ex.getCode());
        HttpStatus status = ErrorCodeHttpStatusMapper.toHttpStatus(errorCode);
        log.warn("业务异常 code={}, message={}, requestId={}", ex.getCode(), ex.getMessage(),
                RequestIdHolder.current());
        return ResponseEntity.status(status).body(Result.fail(ex.getCode(), ex.getMessage()));
    }

    /** @Valid 校验 @RequestBody 失败：把每个字段的错误拼成可读提示。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return badRequest(ErrorCode.PARAM_INVALID, detail);
    }

    /** @Validated 校验 @RequestParam / @PathVariable 失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        return badRequest(ErrorCode.PARAM_INVALID, detail);
    }

    /** 请求体不是合法 JSON / 类型不匹配。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败 requestId={}", RequestIdHolder.current());
        return badRequest(ErrorCode.REQUEST_BODY_INVALID, ErrorCode.REQUEST_BODY_INVALID.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParameter(MissingServletRequestParameterException ex) {
        return badRequest(ErrorCode.MISSING_PARAMETER, "缺少必要参数: " + ex.getParameterName());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return badRequest(ErrorCode.METHOD_NOT_SUPPORTED, ex.getMessage());
    }

    /** 兜底：未预期异常。绝不把堆栈或 SQL 细节返回给客户端，只给 requestId 供排查。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpectedException(Exception ex) {
        String requestId = RequestIdHolder.current();
        log.error("未预期异常 requestId={}", requestId, ex);
        String message = ErrorCode.INTERNAL_ERROR.getMessage()
                + (requestId == null ? "" : "（requestId=" + requestId + "）");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ErrorCode.INTERNAL_ERROR, message));
    }

    private ResponseEntity<Result<Void>> badRequest(ErrorCode errorCode, String message) {
        return ResponseEntity.status(ErrorCodeHttpStatusMapper.toHttpStatus(errorCode))
                .body(Result.fail(errorCode, message));
    }
}
