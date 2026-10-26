-- ============================================================
-- 目标：让 MCP 从「探测"失败"」变成「能真调到工具」
-- 只增不改（除一条刻意的数据订正）：三件事必须落库，否则页面与执行壳都拿不到事实
--   1. stdio_allowed —— 谁来为"在服务器上执行一条命令"签字；
--   2. last_call_*   —— 最近一次工具调用的结果（页面的"最近一次调用"，与 probe 失败分开）；
--   3. MCP 工具的 permission —— 从 READ 改成 WRITE（数据订正，见文件末尾）。
-- ============================================================

-- stdio 的第二道门（第一道是部署者的 nexus.mcp.stdio-enabled）。
-- 为什么必须 per-server 存：stdio 会把 command 列里那段命令行真的执行起来。
-- 只有全局开关的话，任何能在网页上建 server 的用户都等于拿到了 RCE；
-- 只有这个开关的话，运维没法整体关掉本机执行。两道门都要，缺一不可。
ALTER TABLE mcp_server
    ADD COLUMN stdio_allowed TINYINT NOT NULL DEFAULT 0 COMMENT '1 = 用户显式允许在本机启动这条命令（新建默认 0）';

-- 最近一次"工具调用"的结果。刻意不复用 last_error/last_ok_at：
-- probe 失败 = "配的 server 连不上"，调用失败 = "工具这次没跑成"，
-- 两件事的自救动作不同，混成一个字段页面就只能说一句笼统的话。
ALTER TABLE mcp_server
    ADD COLUMN last_call_at DATETIME(3) DEFAULT NULL COMMENT '最近一次工具调用时间';
ALTER TABLE mcp_server
    ADD COLUMN last_call_tool VARCHAR(64) DEFAULT NULL COMMENT '最近一次调用的工具名';
ALTER TABLE mcp_server
    ADD COLUMN last_call_ok TINYINT DEFAULT NULL COMMENT '1 成功 / 0 失败 / NULL 从未调用';
ALTER TABLE mcp_server
    ADD COLUMN last_call_error VARCHAR(1000) DEFAULT NULL COMMENT '最近一次调用失败原因（含错误码 9205/9206/9207）';

-- 数据订正：外部 MCP 工具此前被同步成 READ，等于"不经审批直接执行外部指令"。
-- 外部工具的行为不归我们控制（它可能删库、可能发邮件），按写操作处理才是诚实的默认；
-- 用户真要免审批，走 ToolGrantController 的 always 授权，那是显式的、可撤销的决定。
-- 注意这里只改 source='MCP' 且仍是 READ 的行：BUILTIN 的风险等级是逐个评审过的，不动。
UPDATE ai_tool_registry
SET permission = 'WRITE'
WHERE source = 'MCP'
  AND permission = 'READ';
