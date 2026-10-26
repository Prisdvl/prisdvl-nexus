package com.lky.nexus.common.api;

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
 * 8xxx   会话 / 消息
 * 9999   系统内部错误
 * </pre>
 *
 * <p>为什么把 AI 类错误单独放 4xxx（而不是像示例那样用 5001）：错误码分段要能一眼看出
 * "哪个域炸了"，评测与观测才好归类；5xxx 留给工具与 Agent，语义不混。
 *
 * <p>本类刻意不依赖任何框架：HTTP 状态码的映射放在 Web 层（见
 * {@code com.lky.nexus.app.infra.web.ErrorCodeHttpStatusMapper}），保证 nexus-common
 * 可以被 nexus-mcp-server 等非 Web 进程复用。
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
    USERNAME_ALREADY_EXISTS(2005, "用户名已被占用"),
    INVALID_CREDENTIALS(2006, "用户名或密码错误"),
    /**
     * 2007（5.6）：与 2005 对称的邮箱占用。
     *
     * <p>加它的原因是一次**没人报的静默不一致**：{@code sys_user} 上有
     * {@code uk_user_email(email, deleted)}，但注册只预检了用户名。用重复邮箱注册
     * 会撞唯一键 → {@code DataIntegrityViolationException} → 9999/500，
     * 用户看到"系统内部错误"却不知道该换邮箱。分开成 2007 是为了让前端能指到那个输入框。
     */
    EMAIL_ALREADY_EXISTS(2007, "邮箱已被占用"),
    /**
     * 2008：邮箱验证码错误或已失效（含未发送、已过期、已消费）。
     *
     * <p>对外统一成一条文案，避免用不同错误码区分"没发过 / 过期 / 错了"——
     * 那会给撞库方提供时序侧信道。
     */
    EMAIL_CODE_INVALID(2008, "验证码错误或已失效"),
    /**
     * 2009：同一验证码连续输错达到上限后作废，需重新获取。
     * 与 2008 分开是为了让前端能提示"请重新发送"而不是"再试一次"。
     */
    EMAIL_CODE_TOO_MANY_ATTEMPTS(2009, "验证码错误次数过多，请重新获取"),
    /**
     * 2010：登录设备会话不存在或不属于当前用户。
     *
     * <p>越权与不存在统一此码 → HTTP 404，避免用 403 泄露"这个 session id 在别人名下"。
     */
    SESSION_NOT_FOUND(2010, "会话不存在"),
    /**
     * 2011：账号已启用两步验证，但本次登录没带验证码。
     *
     * <p><b>为什么单独开一个码而不是 2006</b>：密码这一步其实已经过了，
     * 前端要据此把表单从"账号密码"翻到"输验证码"，而不是提示"用户名或密码错误"
     * 让用户反复重试密码。对外仍然不能说更多——这个响应和 2006 一样不透露密码对不对
     * 之外的任何账号信息，且只有<em>密码正确</em>时才会出现，所以不构成新的枚举面。
     */
    TOTP_REQUIRED(2011, "该账号已启用两步验证，请输入验证器中的 6 位验证码"),
    /**
     * 2012：两步验证码错误或已过期。
     *
     * <p>与 2008（邮箱验证码）分开：前者是登录第二因子，后者是邮箱归属证明，
     * 前端要展示的地方和计数策略都不一样（第二因子错了要计入登录失败次数）。
     */
    TOTP_CODE_INVALID(2012, "两步验证码错误或已失效"),
    /** 2013：想关闭/查询两步验证，但账号根本没启用 → 409，属"资源状态不允许"。 */
    TOTP_NOT_ENABLED(2013, "两步验证尚未启用"),
    /** 2014：重复启用 → 409。要换密钥必须先关闭再重新绑定，避免悄悄覆盖旧密钥把用户锁在门外。 */
    TOTP_ALREADY_ENABLED(2014, "两步验证已启用，如需更换密钥请先关闭"),
    /*
     * 没有"恢复码专用错误码"：登录时验证码与恢复码走同一个入口，两者都错就统一回 2012。
     * 分开报会告诉攻击者"这段字符串长得像恢复码"，而这恰好是他最想知道的信息。
     */

    // ---------- 3xxx 知识库 / 文档 ----------
    KNOWLEDGE_BASE_NOT_FOUND(3001, "知识库不存在"),
    KNOWLEDGE_BASE_NAME_DUPLICATED(3002, "知识库名称已存在"),
    DOCUMENT_NOT_FOUND(3003, "文档不存在"),
    DOCUMENT_PARSE_FAILED(3004, "文档解析失败"),
    FILE_TYPE_UNSUPPORTED(3005, "文件类型不支持"),
    FILE_SIZE_EXCEEDED(3006, "文件超出大小限制"),
    INVALID_ZIP_STRUCTURE(3007, "压缩包结构非法"),
    IMPORT_TASK_NOT_FOUND(3008, "导入任务不存在"),
    IMPORT_TASK_PUBLISH_FAILED(3009, "导入任务投递失败"),

    /** 通知通道不存在（已删或不属于当前用户）。 */
    NOTIFY_CHANNEL_NOT_FOUND(3010, "通知通道不存在"),

    // ---------- 4xxx AI 模型 / 嵌入 / 向量 ----------
    MODEL_UNAVAILABLE(4001, "AI 模型不可用"),
    MODEL_TIMEOUT(4002, "AI 模型响应超时"),
    TOKEN_LIMIT_EXCEEDED(4003, "超出模型上下文长度限制"),
    EMBEDDING_FAILED(4004, "文本嵌入失败"),
    VECTOR_STORE_UNAVAILABLE(4005, "向量库不可用"),
    MODEL_ROLE_NOT_CONFIGURED(4006, "未配置对应的模型角色"),
    // 4007 与 4006 都是"服务端没把模型配好"，但对用户是两件事：
    // 4006 是"这个角色压根没接模型"（选错了/没配），4007 是"接了但密钥被拒"
    // （key 过期、被吊销、额度封禁）。分开的好处是动作不同：前者改配置，后者换密钥。
    MODEL_AUTH_FAILED(4007, "模型服务鉴权失败"),
    // 4008：模型网关返回 400/422（请求参数错、模型名错、上下文超长等客户端错误）。
    // 与 4001 的区别：4001 是"网关暂时不可用，重试可能好"；4008 是"请求本身发错了，重试一万次都一样"。
    MODEL_BAD_REQUEST(4008, "模型请求被拒绝"),
    // 4009：检索期「知识库冻结的嵌入模型 ≠ 当前生效配置」。
    // 刻意从 4006 拆出来，因为对客户端与监控是完全不同的信号：
    //   4006 = 服务端没把模型配好（502，重试无用，要运维去配）；
    //   4009 = 这个库当初按另一个模型建的（409，请求格式没错，是资源状态冲突，
    //          用户重建索引或恢复配置就能修）。
    // 混在 4006 里会让"用户可修复的操作"在监控上被计成上游故障（2026-10-18 Docker 验收时发现）。
    EMBEDDING_FROZEN_MISMATCH(4009, "知识库冻结的嵌入模型与当前生效配置不一致"),

    // ---------- 5xxx 工具 / Agent / MCP ----------
    TOOL_EXECUTION_FAILED(5001, "工具执行失败"),
    AGENT_MAX_ITERATIONS_EXCEEDED(5002, "Agent 超出最大执行步数"),
    APPROVAL_REJECTED(5003, "人工确认被拒绝"),
    TOOL_PERMISSION_DENIED(5004, "工具调用无权限"),
    MCP_SERVER_UNAVAILABLE(5005, "MCP 服务不可用"),
    AGENT_NOT_FOUND(5006, "Agent 不存在"),
    AGENT_CODE_DUPLICATED(5007, "Agent 标识已存在"),
    AGENT_NOT_PUBLISHED(5008, "Agent 尚未发布"),
    AGENT_DISABLED(5009, "Agent 已停用"),
    AGENT_BUILTIN_READONLY(5010, "内置默认 Agent 不允许此操作"),
    TOOL_NOT_FOUND(5011, "工具不存在"),
    /**
     * 5012（P0）：运行不存在，或不属于当前用户。
     *
     * <p>越权与不存在统一此码 → HTTP 404：用 403 区分"存在但不是你的"等于
     * 告诉探测者"这个 runId 是有效的"，与 2010 会话不存在同一条理由。
     */
    AGENT_RUN_NOT_FOUND(5012, "运行不存在"),
    /** 5013：当前状态不允许该操作（例如对 RUNNING 的运行提交审批）。 */
    AGENT_RUN_STATE_INVALID(5013, "运行状态不允许此操作"),
    /** 5014：审批已被处理、已过期，或该运行当前没有待决策审批。 */
    APPROVAL_NOT_PENDING(5014, "审批已处理或已失效"),
    /** 5015：运行已取消（用于取消后仍尝试推进运行的路径）。 */
    AGENT_RUN_CANCELLED(5015, "运行已取消"),

    // ---------- 6xxx 工作流 / 评测 ----------
    WORKFLOW_DEFINITION_INVALID(6001, "工作流定义非法"),
    WORKFLOW_NODE_FAILED(6002, "工作流节点执行失败"),
    EVAL_DATASET_EMPTY(6003, "评测集为空"),
    EVAL_JUDGE_MODEL_UNAVAILABLE(6004, "评测裁判模型不可用"),
    WORKFLOW_NOT_FOUND(6005, "工作流不存在"),
    WORKFLOW_RUN_NOT_FOUND(6006, "工作流运行不存在"),
    /** 6007：/approval 打到的运行已不在等待审批状态（含重复审批、已被取消）。 */
    WORKFLOW_APPROVAL_NOT_PENDING(6007, "工作流运行不处于等待审批状态"),
    /** 6008：/retry 打到的运行不处于可重试状态（成功 / 运行中 / 等待审批都不行）。 */
    WORKFLOW_RETRY_NOT_ALLOWED(6008, "该运行当前不可重试"),

    // ---------- 7xxx 限流 / 配额 ----------
    RATE_LIMITED(7001, "请求过于频繁，请稍后再试"),
    QUOTA_EXHAUSTED(7002, "配额已用尽"),

    // ---------- 8xxx 会话 / 消息 ----------
    CONVERSATION_NOT_FOUND(8001, "会话不存在"),
    MESSAGE_NOT_FOUND(8002, "消息不存在"),
    // 8003（5.4）：非流式接口收到它跑不了的会话模式。与 1001 分开是因为
    // "参数填错了"和"这个模式没走这个入口"对用户是两个动作：前者改参数，
    // 后者换接口。合并成一个码，用户会一直在参数上找问题。
    CONVERSATION_MODE_UNSUPPORTED(8003, "该会话模式不支持非流式问答"),

    // ---------- 9xxx 自动化 ----------
    /** 9001：任务不存在，或不属于当前用户（两者一律 404，不暴露存在性）。 */
    AUTOMATION_NOT_FOUND(9001, "自动化任务不存在"),
    AUTOMATION_RUN_NOT_FOUND(9002, "自动化运行记录不存在"),
    /** 9003：排期非法（schedule 不认识，或 custom 的 cron 解析不出来）。 */
    AUTOMATION_SCHEDULE_INVALID(9003, "排期格式非法"),

    // ---------- 91xx 观测 ----------
    /**
     * 9101：该 requestId 没有属于当前用户的调用明细。
     *
     * <p>与 9001 同一个理由：别人的请求 ID 与不存在的请求 ID 必须<b>看起来一样</b>，
     * 否则观测接口就成了一个"这个 ID 是否存在"的探测器。
     */
    OBSERVABILITY_REQUEST_NOT_FOUND(9101, "观测请求不存在"),

    // ---------- 92xx 插件与 MCP ----------
    /**
     * 9201：MCP server 不存在，或不属于当前用户（一律 404）。
     *
     * <p>和 9001 / 9101 同一条规则：别人的资源与不存在的资源必须看起来一样。
     */
    MCP_SERVER_NOT_FOUND(9201, "MCP server 不存在"),
    /**
     * 9202：传输方式不认识（只有 http / stdio 两种）。
     *
     * <p>刻意与"探测失败"分开：填错传输方式是<b>请求写错了</b>（400，改一下就好），
     * 探测失败是<b>上游连不上</b>（200 + 原因）。合成一个码，用户就分不清该改配置还是该找网络。
     */
    MCP_TRANSPORT_UNSUPPORTED(9203, "不支持的 MCP 传输方式"),
    /** 9204：本机 stdio 传输被服务端关闭，或这个 server 没被显式允许启动子进程。 */
    MCP_STDIO_DISABLED(9204, "本机 stdio 传输已关闭"),
    /**
     * 9205：MCP 工具调用超时——server 在约定时间内没有把结果写回来。
     *
     * <p>9205 / 9206 / 9207 三个码刻意分开，理由是 531ffb6 那次教训：把不同故障
     * 合成一句「模型调用失败」，用户就不知道该改配置、该装依赖，还是该重试。
     * 超时 = server 慢或卡死（调大超时/重试可能有效）；
     * 进程退出 = 命令本身有问题（重试永远不会好）；
     * JSON-RPC error = server 收到并明确拒绝了这次调用（改参数才有用）。
     */
    MCP_CALL_TIMEOUT(9205, "MCP 工具调用超时"),
    /** 9206：stdio 子进程已退出（命令写错、依赖没装、进程自己崩了）——重试没有意义。 */
    MCP_PROCESS_EXITED(9206, "MCP 子进程已退出"),
    /** 9207：server 用 JSON-RPC error 回应了这次调用（协议层拒绝，不是网络问题）。 */
    MCP_JSONRPC_ERROR(9207, "MCP server 返回 JSON-RPC 错误"),
    /**
     * 9301：插件不在目录里。
     *
     * <p>目录由服务端 seed（{@code plugin_catalog}），前端不再自带清单，
     * 所以"这个 code 不存在"从此是服务端能回答的问题——用 404 而不是 400：
     * 与 9201 同一条理由，别人的和不存在的不该有区别。
     */
    PLUGIN_NOT_FOUND(9301, "插件不存在"),
    /** 9302：用户没导入过这个技能（同样的防枚举理由 → 404）。 */
    SKILL_NOT_FOUND(9302, "技能不存在"),

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
