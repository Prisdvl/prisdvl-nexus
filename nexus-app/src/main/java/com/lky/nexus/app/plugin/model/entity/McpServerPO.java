package com.lky.nexus.app.plugin.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 外部 MCP server（{@code mcp_server}，V32）。
 *
 * <p>三个字段值得单独解释：
 * <ul>
 *   <li>{@code slug}：工具名 {@code mcp__<slug>__<tool>} 里的那一段。存在库里而不是每次现算，
 *       因为改名不该让"工具名"跟着变——模型侧的工具名一旦漂移，历史对话里点名的工具就找不到了；</li>
 *   <li>{@code authHeaderCipher}：Authorization 头的密文（{@code v1:} 前缀，见
 *       {@code CredentialCipher}）。列宽 500 是按明文上限 320 倒推的；</li>
 *   <li>{@code lastError}：最近一次 probe 的失败原因。<b>它必须有落点</b>——探测失败还当成
 *       "这个 server 没有工具"，是本次改动最要避免的那种静默。</li>
 * </ul>
 */
@Data
@TableName("mcp_server")
public class McpServerPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 展示名（用户填），也是 slug 的来源。 */
    private String name;

    /** 工具名前缀：{@code mcp__<slug>__<tool>}。 */
    private String slug;

    /** {@code http}（streamable HTTP）/ {@code stdio}（本机子进程）。 */
    private String transport;

    /** transport=http 时的 endpoint。 */
    private String url;

    /** transport=stdio 时的命令行。 */
    private String command;

    /** Authorization 头的密文。 */
    private String authHeaderCipher;

    /** 1 启用 / 0 停用：停用后不再向 Agent 注入它的工具。 */
    private Integer enabled;

    /** 最近一次 probe 成功时间。 */
    private LocalDateTime lastOkAt;

    /** 最近一次 probe 失败原因（页面直接显示）。 */
    private String lastError;

    /** 最近一次 tools/list 的原始 JSON。 */
    private String toolsJson;

    /**
     * 1 = 用户显式允许在本机启动这个子进程（V33）。
     *
     * <p>和全局开关 {@code nexus.mcp.stdio-enabled} 是两道门：全局那个是部署者的决定，
     * 这一个是"用户为这一条命令签字"。没有它，任何能建 server 的人都能让服务器执行任意命令。
     */
    private Integer stdioAllowed;

    /** 最近一次<b>工具调用</b>（不是 probe）的时间。 */
    private LocalDateTime lastCallAt;

    /** 最近一次调用的工具名（页面显示"最近调用了谁"）。 */
    private String lastCallTool;

    /** 最近一次调用是否成功：1 成功 / 0 失败。失败原因在 {@link #lastCallError}。 */
    private Integer lastCallOk;

    /** 最近一次调用失败的原因（含错误码）。与 {@link #lastError}（probe 失败）分开，两者故障不同。 */
    private String lastCallError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    /**
     * 是否为这条命令签过字；历史行（V33 之前建的）为 null，按"没签"算。
     *
     * <p>刻意不叫 {@code isStdioAllowed()}：Lombok 会给 {@code Integer stdioAllowed} 生成
     * {@code getStdioAllowed()}，再加一个同名 {@code isStdioAllowed()} 会让 MyBatis 判成
     * "overloaded getter with ambiguous type" 而整条 insert 失败。
     */
    public boolean allowsStdio() {
        return stdioAllowed != null && stdioAllowed == 1;
    }
}
