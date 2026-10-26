package com.lky.nexus.app.plugin.mcp;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.app.plugin.mapper.McpServerMapper;
import com.lky.nexus.app.plugin.model.entity.McpServerPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 把"最近一次工具调用"写回 {@code mcp_server}（{@code last_call_*} 五列）。
 *
 * <p><b>为什么需要一个独立的小组件</b>：这条记录发生在<b>工具执行</b>路径上，
 * 而工具执行经常以一个失败收场——失败时最容易被写成"报个错就完了"，
 * 于是页面永远显示"从未调用过"，用户既看不到自己调了什么，也看不到为什么失败。
 * 探测结果（{@code last_error}）回答的是"这个 server 通不通"，调用结果回答的是
 * "这个工具刚才跑成了没有"，两件事混在一起，界面就只能说一句笼统的话。
 *
 * <p>实现上的两个克制：
 * <ul>
 *   <li><b>绝不抛异常</b>：记录失败不能把一次工具调用变成"工具调用失败"，
 *       那等于让监控自己的故障污染被监控对象；</li>
 *   <li><b>只按主键更新</b>：不带上用户条件，因为调用方（工具执行壳）没有 userId 上下文，
 *       而 server 对象本身就是从库里按用户查出来的。</li>
 * </ul>
 */
@Component
public class McpCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(McpCallRecorder.class);

    /** 与 {@code last_call_error} 列宽一致（VARCHAR(1000)）。 */
    private static final int ERROR_LIMIT = 1000;

    private final McpServerMapper serverMapper;

    public McpCallRecorder(McpServerMapper serverMapper) {
        this.serverMapper = serverMapper;
    }

    /**
     * 记一次调用结果。
     *
     * @param server   被调用的 server（必须有 id）
     * @param toolName 上游工具名（不是 {@code mcp__slug__tool} 那个 code——页面要显示的是用户认识的名字）
     * @param error    失败原因（成功时传 null）
     */
    public void record(McpServerPO server, String toolName, String error) {
        if (server == null || server.getId() == null) {
            return;
        }
        boolean ok = error == null;
        try {
            serverMapper.update(null, Wrappers.<McpServerPO>lambdaUpdate()
                    .eq(McpServerPO::getId, server.getId())
                    .set(McpServerPO::getLastCallAt, LocalDateTime.now())
                    .set(McpServerPO::getLastCallTool, clip(toolName))
                    .set(McpServerPO::getLastCallOk, ok ? 1 : 0)
                    .set(McpServerPO::getLastCallError, ok ? null : clip(error)));
        } catch (RuntimeException ex) {
            // 记不上是小事，把工具调用搞挂是大事
            log.warn("记录 MCP 调用结果失败 id={} tool={}：{}", server.getId(), toolName, ex.getMessage());
        }
    }

    private static String clip(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= ERROR_LIMIT ? value : value.substring(0, ERROR_LIMIT);
    }
}
