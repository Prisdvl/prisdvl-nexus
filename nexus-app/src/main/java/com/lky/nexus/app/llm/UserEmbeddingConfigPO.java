package com.lky.nexus.app.llm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户自定义<b>嵌入</b>模型凭据（表 {@code user_embedding_config}，迁移 V25）。
 *
 * <p>一个用户至多一行（{@code uk_user_embedding_user}）。{@code enabled=0} 时即使填了字段也不生效，
 * 便于"先填好、暂时停用"；{@code enabled=0} 或整行不存在都回落全局 {@code embedding.default}。
 *
 * <p>与 {@link UserLlmConfigPO} 的区别只有两点，都源于嵌入的特殊性：
 * <ul>
 *   <li>{@code provider} 与 {@code modelName} 一起受<b>白名单</b>约束
 *       （{@code ai.byok.embedding.allowed-models}）——嵌入模型决定向量空间，不能随便填；</li>
 *   <li>{@code dimensions} 落库且必须是平台允许的维度——向量列写死 {@code vector(1024)}。</li>
 * </ul>
 * {@code apiKey} 与对话侧同一口径：落库是 AES-GCM 密文（{@code CredentialCipher}），接口只回掩码。
 */
@Data
@TableName("user_embedding_config")
public class UserEmbeddingConfigPO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所有者；所有查询必须带此条件。 */
    private Long userId;

    /** 供应商标识（白名单 key 的前半段），如 {@code bailian}。 */
    private String provider;

    /** 模型名（白名单 key 的后半段），如 {@code text-embedding-v4}。 */
    private String modelName;

    /** OpenAI 兼容端点。 */
    private String baseUrl;

    /** 该用户自己的 API Key（AES-GCM 密文；接口回显掩码）。 */
    private String apiKey;

    /** 声明维度；必须等于平台允许维度（默认 1024）。 */
    private Integer dimensions;

    /** 1 = 该用户的知识库走他自己的嵌入配置；0 = 回落服务端全局角色。 */
    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
