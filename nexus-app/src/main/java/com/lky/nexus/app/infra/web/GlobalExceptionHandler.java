package com.lky.nexus.app.infra.web;

import com.lky.nexus.ai.error.FailureClassifier;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.Result;
import com.lky.nexus.common.exception.BusinessException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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

    /** 上传上限的配置原值（形如 {@code 200MB}），用于把"超限"说成一句带具体数字的话。 */
    private final String maxFileSize;
    private final String maxRequestSize;

    public GlobalExceptionHandler(
            @Value("${spring.servlet.multipart.max-file-size:200MB}") String maxFileSize,
            @Value("${spring.servlet.multipart.max-request-size:220MB}") String maxRequestSize) {
        this.maxFileSize = maxFileSize;
        this.maxRequestSize = maxRequestSize;
    }

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

    /**
     * {@code @RequestParam} / {@code @PathVariable} 类型不匹配（如 {@code ?topK=abc}）。
     *
     * <p>必须单独接住：它是 {@code TypeMismatchException} 而不是
     * {@code MethodArgumentNotValidException}，不加这条就会被兜底处理器接走，
     * 结果是<b>客户端写错参数、服务端返回 500 并记一条带堆栈的 ERROR 日志</b>——
     * 把"调用方笔误"记成"服务端 BUG"，真正的问题反而被淹没，告警也会失准。
     * 提示里带上参数名与实际值，省掉"到底是哪个参数写错了"的来回猜测。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String expected = ex.getRequiredType() == null ? "?" : ex.getRequiredType().getSimpleName();
        return badRequest(ErrorCode.PARAM_INVALID,
                "参数类型不正确: %s=%s（需要 %s）".formatted(ex.getName(), ex.getValue(), expected));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return badRequest(ErrorCode.METHOD_NOT_SUPPORTED, ex.getMessage());
    }

    /**
     * 上传体积超过 Spring 的 multipart 上限（{@code spring.servlet.multipart.max-file-size}）。
     *
     * <p>这个异常是<b>过期后补上</b>的：此前没有它，超限请求落到兜底 {@code Exception}，
     * 用户看到的是 500「系统内部错误（requestId=…）」——一句话既没说清原因，也没给可行的动作，
     * 而真相是"你文件太大了，上限 200MB"。客户端错误被打成服务端 BUG，日志告警也会失准
     * （同 {@link #handleTypeMismatch} 记录的那类教训）。
     *
     * <p>文案里写上限的<b>实际配置值</b>而不是写死 200MB：上限是可配的，
     * 写死的数字会在改配置后变成谎言。两个值都给出——文件超的是哪一个上限，
     * 用户未必分得清，但"单文件"与"单次请求"的差别正是常见困惑点。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("上传超过体积上限 requestId={}", RequestIdHolder.current());
        return badRequest(ErrorCode.FILE_SIZE_EXCEEDED,
                "文件超出大小限制（单文件上限 %s，单次请求上限 %s）".formatted(maxFileSize, maxRequestSize));
    }

    /**
     * Agent 工具循环撞到轮数上限（框架 {@code ToolCallLimitExceededException}）。
     *
     * <p>非流式入口（{@code /api/agent/ask}、MCP 的 {@code qa_ask}、Agent 试跑、评测）
     * 的异常会一路冒到这里；流式入口的 SSO 已经打开，走
     * {@code FailureClassifier} + error 帧。两边共用同一个判定，所以报的是同一个码同一句话。
     *
     * <p>没有这条 handler 时它落到兜底：500「系统内部错误」——把"这个 Agent 想调用太多步"
     * 说成"服务端崩了"，而 {@code ErrorCode 5002} 也就永远是个死码（见 decisions.md B.43）。
     */
    @ExceptionHandler(ToolCallLimitExceededException.class)
    public ResponseEntity<Result<Void>> handleToolCallLimit(ToolCallLimitExceededException ex) {
        FailureClassifier.Failure failure = FailureClassifier.classify(ex);
        log.warn("工具调用超过轮数上限 requestId={}, tool={}, limit={}",
                RequestIdHolder.current(), ex.getToolName(), ex.getLimit());
        return badRequest(failure.code(), failure.message());
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
