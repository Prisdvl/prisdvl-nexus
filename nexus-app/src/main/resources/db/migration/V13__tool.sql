-- ============================================================
-- V13 Tool 资源（平台化 4.3：Tool Definition，§十九）
--
-- 目标：把"工具"从代码里的两处硬编码变成平台资源——
--   AgentRuntime.configure 里写死的 ToolCallbacks.from(knowledgeSearchTool)（两条链路共用它）
--   McpEndpoint.toolsList() 里手写的 knowledge_search 名字/描述/schema（+ 直接调工具方法）
-- 变成：内置实现清单（代码）→ tool(表，用户态) → ToolRegistry → ToolFactory → 执行壳。
-- （核对过：ChatStreamServiceImpl 自 4.1 起已委托 AgentRuntime.configure，
--   "两份 Callbacks.from"的说法不成立，那里只是残留了一个没人用的 import，4.3 一并删掉。）
--
-- 两张表的分工（刻意模仿 agent / agent_knowledge_base 那对）：
--   tool       —— "这个用户关掉了哪些工具"（**覆盖行**：没有行 = 用代码侧的默认，即启用）
--   agent_tool —— "哪个 Agent 能用哪些工具"（无行 = 不限制，与该 Agent 的 kbIds 空 = 不限制一致）
--
-- 只增不改：两张新表，不动任何现有表/列。
--
-- 【一张只有 4 个业务列的表，是有意的】
-- §5.3 的设计表里还有 name / description / category / json_schema / source 五个"描述性"列，
-- 本增量**不建**，理由不是省事：
--   1. 这些字段的唯一真实来源是代码——就是那几个 @Tool 方法。框架的 ToolDefinition
--      （name/description/inputSchema）本来就是从方法签名与注解反射出来的，
--      模型看到的描述必须与实现同步；
--   2. 读路径也只能用代码侧那一份（否则改了 @Tool 描述却不生效，或者库里那份描述
--      与模型实际看到的不一致——两种都是最难查的问题）；
--   3. 于是库里那份描述就是**永远不会被读的死数据**，只会在评审时误导人。
--      与 V12 不建 max_iterations / timeout_ms / memory_config 是同一条理由（B.35 §7）。
-- 它们该在什么时候加：**外部工具**（MCP server / 自定义 HTTP）落地时——那时 endpoint、
-- schema、timeout 这些"只有数据库才知道"的配置才第一次真的存在，加列与接上读路径同一次做。
--
-- 【刻意不建的配置列】permission[READ/WRITE/DANGEROUS] / approval_required / timeout_ms /
-- rate_limit：今天没有任何一处会读它们（审批要等 §5.4 的挂起-恢复机制，超时/限流要等执行壳
-- 真的会拦截）。它们与 P1 的 Tool Permission / Approval 一起落地。
-- §5.2 设计里的 agent_tool.enabled 也**没有建**：白名单的"在不在里面"已经表达了
-- 用不用这个工具，多一个恒为 1、没有任何写入点的标志位只会让后来的人以为它能关。
--
-- 为什么 agent_tool 用 tool_code 而不是 tool_id 作引用键（沿用 §5.2 的设计）：
--   内置工具的实现活在代码里，它的"身份"本来就是 code；而 tool 表里的行是**覆盖行**
--   （可以不存在）。用 id 引用会立刻悬空；code 稳定，行被删/重建都不影响绑定。
-- ============================================================

CREATE TABLE tool (
    id           BIGINT      NOT NULL,
    workspace_id BIGINT      NOT NULL COMMENT '所属工作区（建表即带归属维度；查询仍走 owner_id，与 agent 一致）',
    owner_id     BIGINT      NOT NULL COMMENT '所有者：所有查询必须带此条件',
    code         VARCHAR(64) NOT NULL COMMENT '工具标识，来自实现的 @Tool(name=...)（如 knowledge_search）：模型看到的就是它',
    enabled      TINYINT     NOT NULL DEFAULT 1 COMMENT '0 = 该用户停用此工具；这是今天就真的会生效的拦截点（停用的工具不会装给任何 Agent）',
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted      TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 与 agent 同一条理由不建 (owner_id, code, deleted) 唯一键：逻辑删除后 deleted=1 的行之间会互相冲突。
    -- 唯一性在服务层按 deleted=0 校验（toggle 用"先查后写"，且这张表每个用户最多几行，不构成热点）。
    KEY idx_tool_owner (owner_id, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Tool 的用户态（覆盖行：无行 = 代码侧默认，即启用）';

CREATE TABLE agent_tool (
    id         BIGINT      NOT NULL,
    agent_id   BIGINT      NOT NULL,
    tool_code  VARCHAR(64) NOT NULL COMMENT '引用 @Tool(name=...) 的 code，不是 tool.id（理由见文件头）',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 关联表是物理行（无逻辑删除），这里的唯一键是安全的：同一个 Agent 不会重复绑同一个工具
    UNIQUE KEY uk_agent_tool (agent_id, tool_code),
    KEY idx_agent_tool_code (tool_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Agent 可用的工具（无行 = 不限制）';
