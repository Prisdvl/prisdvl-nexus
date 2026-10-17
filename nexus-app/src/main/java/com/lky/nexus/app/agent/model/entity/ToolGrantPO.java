package com.lky.nexus.app.agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户级长期工具授权（{@code ai_tool_grant}，V22）。
 *
 * <p>只有 {@code scope=always} 会落这张表：{@code once} 是"这一次调用放行"，
 * 落库反而会让"下次不再问"这件事悄悄发生；{@code deny} 更不该被保存成授权。
 * 唯一键 {@code (user_id, tool_code)} 保证同一用户同一工具只有一条授权（幂等 upsert）。
 */
@Data
@TableName("ai_tool_grant")
public class ToolGrantPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private String toolCode;

    /** 长期授权只允许 ALWAYS（见 V22 的唯一约束与 ToolGrantService 的校验）。 */
    private String scope;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
