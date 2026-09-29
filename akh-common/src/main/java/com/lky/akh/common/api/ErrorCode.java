package com.lky.akh.common.api;

/**
 * 全局错误码。
 *
 * <p>分段规则（本项目约定，见 docs/api.md 1.3）：
 * <pre>
 * 0      成功
 * 1xxx   通用 / 参数
 * 2xxx   认证授权
 * 3xxx   知识库 / 文档
 * 4xxx   AI 模型 / 嵌入 / 向量
 * 5xxx   工具 / Agent / MCP
 * 6xxx   工作流 / 评测
 * 7xxx   限流 / 配额
 * 9999   系统内部错误
 * </pre>
 *
 * <p>为什么把 AI 类错误单独放 4xxx（而不是像示例那样用 5001）：错误码分段要能一眼看出
 * "哪个域炸了"，评测与观测才好归类；5xxx 留给工具与 Agent，语义不混。
 *
 * <p>本类刻意不依赖任何框架：HTTP 状态码的映射放在 Web 层（见
 * {@code com.lky.akh.app.infra.web.ErrorCodeHttpStatusMapper}），保证 akh-common
 * 可以被 akh-mcp-server 等非 Web 进程复用。
 */
public enum ErrorCode {

    // ---------- 成功 ----------
    SUCCESS(0, "success"),

    // ---------- 1xxx 通用 / 参数 ----------
    PARAM_INVALID(1001, "参数校验失败"),
    REQUEST_BODY_INVALID(1002, "请求体格式错误"),
    METHOD_NOT_SUPPORTED(1003, "不支持的请求方法"),
    MISSING_PARAMETER(1004, "缺少必要的请求参数"),

    // ---------- 2xxx 认证授权 ----------
    UNAUTHENTICATED(2001, "未认证或凭证缺失"),
    TOKEN_EXPIRED(2002, "登录状态已过期，请重新登录"),
    FORBIDDEN(2003, "无权限访问该资源"),
    ACCOUNT_DISABLED(2004, "账号已被禁用"),

    // ---------- 3xxx 知识库 / 文档 ----------
    KNOWLEDGE_BASE_NOT_FOUND(3001, "知识库不存在"),
    KNOWLEDGE_BASE_NAME_DUPLICATED(3002, "知识库名称已存在"),
    DOCUMENT_NOT_FOUND(3003, "文档不存在"),
    DOCUMENT_PARSE_FAILED(3004, "文档解析失败"),
    FILE_TYPE_UNSUPPORTED(3005, "文件类型不支持"),
    FILE_SIZE_EXCEEDED(3006, "文件超出大小限制"),
    INVALID_ZIP_STRUCTURE(3007, "压缩包结构非法"),
    IMPORT_TASK_NOT_FOUND(3008, "导入任务不存在"),

    // ---------- 4xxx AI 模型 / 嵌入 / 向量 ----------
    MODEL_UNAVAILABLE(4001, "AI 模型不可用"),
    MODEL_TIMEOUT(4002, "AI 模型响应超时"),
    TOKEN_LIMIT_EXCEEDED(4003, "超出模型上下文长度限制"),
    EMBEDDING_FAILED(4004, "文本嵌入失败"),
    VECTOR_STORE_UNAVAILABLE(4005, "向量库不可用"),
    MODEL_ROLE_NOT_CONFIGURED(4006, "未配置对应的模型角色"),

    // ---------- 5xxx 工具 / Agent / MCP ----------
    TOOL_EXECUTION_FAILED(5001, "工具执行失败"),
    AGENT_MAX_ITERATIONS_EXCEEDED(5002, "Agent 超出最大执行步数"),
    APPROVAL_REJECTED(5003, "人工确认被拒绝"),
    TOOL_PERMISSION_DENIED(5004, "工具调用无权限"),
    MCP_SERVER_UNAVAILABLE(5005, "MCP 服务不可用"),

    // ---------- 6xxx 工作流 / 评测 ----------
    WORKFLOW_DEFINITION_INVALID(6001, "工作流定义非法"),
    WORKFLOW_NODE_FAILED(6002, "工作流节点执行失败"),
    EVAL_DATASET_EMPTY(6003, "评测集为空"),
    EVAL_JUDGE_MODEL_UNAVAILABLE(6004, "评测裁判模型不可用"),

    // ---------- 7xxx 限流 / 配额 ----------
    RATE_LIMITED(7001, "请求过于频繁，请稍后再试"),
    QUOTA_EXHAUSTED(7002, "配额已用尽"),

    // ---------- 兜底 ----------
    INTERNAL_ERROR(9999, "系统内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    /** 按数值码反查枚举；未登记时返回 {@link #INTERNAL_ERROR}（保证不会因未知码抛异常）。 */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return INTERNAL_ERROR;
    }
}
