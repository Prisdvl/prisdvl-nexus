package com.lky.nexus.ai.error;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * 把"下游抛出来的异常"翻译成"用户能看懂的失败"：错误码 + 文案 + 能不能重试。
 *
 * <p><b>为什么需要它</b>：一次生成可能失败在很多地方——模型网关超时、模型网关 5xx、
 * 工具循环撞到轮数上限、检索服务不可用、我们自己的 BUG。这些异常从不同的库里抛出来，
 * 类型五花八门。如果每个调用点各自 {@code catch (Exception)} 然后写一句
 * "模型调用失败"，就会出现两类错误：
 * <ol>
 *   <li><b>错报归因</b>：撞到工具轮数上限的用户看到"模型调用失败"，于是去换模型、
 *       去查额度，而真正该做的是把问题拆小（见 {@code docs/decisions.md} B.43）；</li>
 *   <li><b>多处漂移</b>：同步问答、流式对话、Agent 试跑各写一份映射，
 *       同一个故障在不同入口显示不同的话。</li>
 * </ol>
 * 因此映射只写在这一处：{@link #classify(Throwable)} 是<b>唯一</b>的判定入口。
 *
 * <p><b>判定顺序（从具体到笼统）</b>，这个顺序本身是契约的一部分：
 * <ol>
 *   <li>{@link ToolCallLimitExceededException} —— 框架抛的轮数上限，诊断最具体；</li>
 *   <li>{@link BusinessException} —— 已经是业务异常，原样保留它自己的码与文案
 *       （它可能是 RAG 域、权限域、参数域）；</li>
 *   <li>鉴权被拒 —— 走 {@link ErrorCode#MODEL_AUTH_FAILED}（HTTP 502）：模型网关<b>收到了</b>
 *       我们的请求并明确拒绝，属于服务端配置问题，重试不会恢复；</li>
 *   <li>超时 —— 走 {@link ErrorCode#MODEL_TIMEOUT}（HTTP 504，语义是"上游没按时回"）；</li>
 *   <li>其余 —— 走 {@link ErrorCode#MODEL_UNAVAILABLE}（HTTP 503），文案保持
 *       {@link #MODEL_FAILED_MESSAGE} 不变：这是与 {@code ChatServiceImpl} 既有的
 *       "生成期异常都按模型不可用上报"口径一致的选择，也避免把所有未知故障都说成
 *       "系统内部错误"而丢掉"重试可能有用"这个真实信息。原始异常连同堆栈仍然落在日志里
 *       （有 requestId 可关联），排查不靠这句话。</li>
 * </ol>
 *
 * <p>只能沿 cause 链找，不能只看最外层：Spring AI 会把网关异常包一层
 * （{@code RetryExhaustedException} → {@code WebClientRequestException} → {@code ConnectException}），
 * 只看最外层等于永远匹配不上。
 */
public final class FailureClassifier {

    /**
     * 未知生成故障的对外文案。
     *
     * <p>刻意保持不变：前端与 E2E 脚本（{@code docs/archive/web-vue/scripts/verify-agent*.mjs}）用它区分
     * "这台机器没配模型"与"产品坏了"——改这句话会让没配 key 的机器从"跳过"变成"失败"。
     */
    public static final String MODEL_FAILED_MESSAGE = "模型调用失败，请稍后重试";

    /** 超时文案：带上"没等到"这个事实，用户才知道该重试而不是改配置。 */
    public static final String MODEL_TIMEOUT_MESSAGE = "模型响应超时，请稍后重试";

    /**
     * 密钥被拒的文案。
     *
     * <p>刻意不说"请稍后重试"：密钥错了是确定性的，重试一万次都会被拒。
     * 该说清"这不是你操作的问题、重试没用、需要管理员处理"，否则用户会把时间花在
     * 反复点击上（这条措辞由 5.2 的真机验证逼出来：用一个无效 Key 起的服务，
     * 三个入口给出三种说法，其中两种都在劝用户重试）。
     */
    public static final String MODEL_AUTH_FAILED_MESSAGE =
            "模型服务的密钥无效或已过期（服务端配置问题），重试无法恢复，请联系管理员";

    private FailureClassifier() {
    }

    /**
     * 一次失败的完整口径。
     *
     * @param code      业务错误码（决定 HTTP 状态码与前端展示口径）
     * @param message   给用户看的话，不含堆栈、不含内部类名
     * @param retryable 重试同样的请求有没有意义（前端据此决定要不要给"重试"按钮）
     */
    public record Failure(ErrorCode code, String message, boolean retryable) {
    }

    /** 唯一的判定入口。 */
    public static Failure classify(Throwable error) {
        ToolCallLimitExceededException limit = firstOfType(error, ToolCallLimitExceededException.class);
        if (limit != null) {
            return toolCallLimit(limit);
        }
        BusinessException business = firstOfType(error, BusinessException.class);
        if (business != null) {
            ErrorCode code = ErrorCode.fromCode(business.getCode());
            return new Failure(code, business.getMessage(), retryable(code));
        }
        if (isCredentialRejected(error)) {
            return new Failure(ErrorCode.MODEL_AUTH_FAILED, MODEL_AUTH_FAILED_MESSAGE, false);
        }
        if (isTimeout(error)) {
            return new Failure(ErrorCode.MODEL_TIMEOUT, MODEL_TIMEOUT_MESSAGE, true);
        }
        return new Failure(ErrorCode.MODEL_UNAVAILABLE, MODEL_FAILED_MESSAGE, true);
    }

    /**
     * 转成业务异常——给<b>非流式</b>调用点用（流式路径不能再抛异常，SSE 已经打开了，
     * 只能把 {@link Failure} 填进 error 帧）。
     */
    public static BusinessException toBusinessException(Throwable error) {
        Failure failure = classify(error);
        return new BusinessException(failure.code(), failure.message(), error);
    }

    /**
     * 工具轮数上限：{@code getToolName()} 为空表示"本次生成所有工具合计"的上限，
     * 非空表示"单个工具"的上限——这是框架两个不同的限额（默认 40 / 150，见 B.43），
     * 文案必须如实区分，否则用户按错误的方向去调。
     */
    private static Failure toolCallLimit(ToolCallLimitExceededException ex) {
        String message = ex.getToolName() == null
                ? "本次生成的工具调用总次数超过上限（最多 %d 次），已停止生成。请把问题拆小一些再试。"
                        .formatted(ex.getLimit())
                : "工具 %s 的调用次数超过上限（最多 %d 次），已停止生成。请把问题拆小一些再试。"
                        .formatted(ex.getToolName(), ex.getLimit());
        // 不可重试：同样的问法会撞同样的墙（上限是确定性的），重试只会再烧一次额度
        return new Failure(ErrorCode.AGENT_MAX_ITERATIONS_EXCEEDED, message, false);
    }

    /**
     * 哪些错误值得"再试一次"。
     *
     * <p>按错误域白名单，而不是按"是不是 5xx"：只有下游服务<b>当下不可用</b>这一类
     * 才可能自己恢复。参数错、权限错、状态错、轮数超限，重试一万次结果都一样。
     */
    private static boolean retryable(ErrorCode code) {
        return switch (code) {
            case MODEL_TIMEOUT, MODEL_UNAVAILABLE, TOKEN_LIMIT_EXCEEDED, EMBEDDING_FAILED,
                 VECTOR_STORE_UNAVAILABLE, MCP_SERVER_UNAVAILABLE, RATE_LIMITED -> true;
            default -> false;
        };
    }

    /**
     * 凭证被拒判定（上游返回 401/403）。
     *
     * <p>为什么要单独认这一类：它<b>长得像</b>普通的模型不可用（都是"调用没成功"），
     * 但两者的正确动作完全相反——网关 503 该稍后重试，密钥无效只有管理员能修。
     * 归到一个桶里就必然对一半人给出错误的建议。
     *
     * <p>仍然按类名匹配，理由与 {@link #isTimeout} 相同：这些异常来自不同的 SDK
     * （{@code com.openai.errors.UnauthorizedException}、响应式栈的
     * {@code WebClientResponseException$Unauthorized}…），逐一 import 会把本项目
     * 绑死在某个供应商 SDK 上。匹配用的是这些库共有的命名习惯。
     *
     * <p><b>刻意不匹配 {@code AccessDenied}</b>：在 Spring Security 里它是"<b>我们自己的</b>
     * 接口被无权限访问"，与"模型网关拒绝我们的密钥"是两件相反的事，
     * 混进来会把一次越权访问报成"模型密钥过期"。
     *
     * <p>顺序上必须在 {@link BusinessException} 之后：那类异常已经带着明确的域与文案，
     * 不该被类名匹配改写（一个 {@code FORBIDDEN} 业务异常被说成"模型密钥过期"就是错报）。
     */
    private static boolean isCredentialRejected(Throwable error) {
        for (Throwable current = error; current != null; current = next(current)) {
            String name = current.getClass().getSimpleName();
            if (name.contains("Unauthorized") || name.contains("Authentication")
                    || name.contains("InvalidApiKey") || name.contains("PermissionDenied")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 超时判定：不同 HTTP 客户端抛的类不一样（JDK 的 {@link SocketTimeoutException}、
     * 响应式栈的 {@code ReadTimeoutException}、{@code HttpTimeoutException}…）。
     * 逐一枚举必然会漏（换一个客户端就漏一个），因此先认 JDK 的两个标准类型，
     * 再用"类名里带 Timeout"兜住其余实现——这条约定是这些库共同的命名习惯，
     * 比维护一份会过期的清单更可靠。
     */
    private static boolean isTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = next(current)) {
            if (current instanceof SocketTimeoutException || current instanceof TimeoutException) {
                return true;
            }
            if (current.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
        }
        return false;
    }

    private static <T extends Throwable> T firstOfType(Throwable error, Class<T> type) {
        for (Throwable current = error; current != null; current = next(current)) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }

    /** 沿 cause 链上溯；cause 指向自己只可能是构造失误，遇到就停，避免死循环。 */
    private static Throwable next(Throwable current) {
        Throwable cause = current.getCause();
        return cause == current ? null : cause;
    }
}
