package com.lky.nexus.app.llm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户自定义对话模型凭据（表 {@code user_llm_config}，迁移 V19）。
 *
 * <p>一个用户至多一行（{@code uk_user_llm_user}）。{@code enabled=0} 时即使填了字段也不生效，
 * 便于"先填好、暂时停用"。嵌入模型不纳入本表（维度在建库时冻结，ADR-008）。
 */
@Data
@TableName("user_llm_config")
public class UserLlmConfigPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所有者；所有查询必须带此条件。 */
    private Long userId;

    /** OpenAI 兼容端点。 */
    private String baseUrl;

    /** 该用户自己的 API Key（敏感，接口回显掩码）。 */
    private String apiKey;

    /** 模型名。 */
    private String modelName;

    /** 1 = 启用自定义模型；0 = 回落服务端全局配置。 */
    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
