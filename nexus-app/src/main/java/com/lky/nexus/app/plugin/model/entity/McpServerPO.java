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

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
