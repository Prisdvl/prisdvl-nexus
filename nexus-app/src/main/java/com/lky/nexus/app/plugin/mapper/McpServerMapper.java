package com.lky.nexus.app.plugin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lky.nexus.app.plugin.model.entity.McpServerPO;
import org.apache.ibatis.annotations.Mapper;

/** 外部 MCP server（{@code mcp_server}）。查询一律带 {@code user_id}。 */
@Mapper
public interface McpServerMapper extends BaseMapper<McpServerPO> {
}
