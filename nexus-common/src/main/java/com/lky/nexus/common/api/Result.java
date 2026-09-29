package com.lky.nexus.common.api;

/**
 * 统一返回体：{@code { "code": 0, "message": "success", "data": {...} }}。
 *
 * <p>用 record 而不是 Lombok 类的原因：
 * <ol>
 *   <li>不可变，天然线程安全；</li>
 *   <li>只需声明三个组件，getter/equals/toString 全自动生成；</li>
 *   <li>不需要任何 Jackson 注解 —— 保持 nexus-common 零框架依赖，Jackson 3 / Jackson 2 都能序列化。</li>
 * </ol>
 *
 * <p>注意：这里<b>故意不写</b> {@code isSuccess()} 这类方法。record 上形如 {@code getXxx()}
 * 或 {@code isXxx()} 的方法会被 Jackson 当成属性序列化出去，破坏 {@code {code,message,data}}
 * 的接口契约。需要判断成败请用静态方法 {@link #isSuccess(Result)}。
 *
 * <p>RequestId 不放在 body 里，而是放响应头 {@code X-Request-Id}（见 docs/api.md 1.1）。
 */
public record Result<T>(int code, String message, T data) {

    /** 成功，带数据。 */
    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    /** 成功，无数据（例如删除接口）。 */
    public static Result<Void> ok() {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    /** 失败，使用错误码自带的提示信息。 */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 失败，覆盖提示信息（用于补充上下文，例如"文档 xxx.pdf 解析失败"）。 */
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    /** 失败，直接指定码与信息。 */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    /** 是否成功。静态方法，不会被序列化成 JSON 属性。 */
    public static boolean isSuccess(Result<?> result) {
        return result != null && result.code == ErrorCode.SUCCESS.getCode();
    }
}
