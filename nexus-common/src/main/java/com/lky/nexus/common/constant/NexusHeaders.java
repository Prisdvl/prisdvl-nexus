package com.lky.nexus.common.constant;

/**
 * 本项目自定义的 HTTP 头名称。
 *
 * <p>放在 nexus-common 而不是 Web 层：请求 ID 是全链路追踪标识，
 * 未来 nexus-mcp-server 与其他调用方也要用它传递与回显。
 */
public final class NexusHeaders {

    /** 请求唯一标识：入口过滤器生成（或透传上游），写入响应头与日志 MDC，并落库到 obs_ai_request。 */
    public static final String REQUEST_ID = "X-Request-Id";

    /** 幂等键：创建类接口可选携带，同一 key 在窗口期内返回首次结果（docs/api.md 1.5）。 */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /** API 版本协商头（预留）：路径保持 /api/...，版本通过该头演进。 */
    public static final String API_VERSION = "Api-Version";

    private NexusHeaders() {
    }
}
