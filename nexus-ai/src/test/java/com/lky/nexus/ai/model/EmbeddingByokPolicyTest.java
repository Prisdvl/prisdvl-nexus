package com.lky.nexus.ai.model;

import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BYOK 嵌入白名单与固定维度（路线 A）的纯单元测试。
 *
 * <p>为什么这几条要单独钉住：白名单与维度是"能不能往向量库里写"的硬约束，
 * 而它的失败方式很隐蔽——放宽一行配置不会报错，只会让一批宽度不对的向量悄悄入库，
 * 之后表现为"检索结果莫名其妙"。所以默认值、越界值、null 值都要有断言。
 */
@DisplayName("EmbeddingByokPolicy：嵌入模型白名单与固定维度")
class EmbeddingByokPolicyTest {

    @Test
    @DisplayName("默认策略只放行 bailian:text-embedding-v4 且维度 1024")
    void defaultsAreConservative() {
        EmbeddingByokPolicy policy = new EmbeddingByokPolicy(new AiModelProperties());

        assertThat(policy.allowedModels()).containsExactly("bailian:text-embedding-v4");
        assertThat(policy.requiredDimensions()).isEqualTo(1024);
        // 默认策略必须拒绝"看起来合理但不在白名单"的模型（例如上一代 v3）
        assertThat(policy.violationOf("bailian", "text-embedding-v3", 1024)).isPresent();
    }

    @Test
    @DisplayName("白名单内 + 维度 1024：通过")
    void allowsWhitelistedModelWithRequiredDimension() {
        EmbeddingByokPolicy policy = policy("bailian:text-embedding-v4");

        assertThat(policy.isAllowed("bailian", "text-embedding-v4")).isTrue();
        assertThat(policy.violationOf("bailian", "text-embedding-v4", 1024)).isEmpty();
        // 前后空白不算"另一个模型"，但也不该被当成合法输入悄悄放行——trim 后比对
        assertThat(policy.isAllowed(" bailian ", " text-embedding-v4 ")).isTrue();
    }

    @Test
    @DisplayName("白名单外的 provider:model 被拒绝，消息里列出当前白名单")
    void rejectsNonWhitelistedModel() {
        EmbeddingByokPolicy policy = policy("bailian:text-embedding-v4");

        assertThat(policy.isAllowed("openai", "text-embedding-3-large")).isFalse();
        String reason = policy.violationOf("openai", "text-embedding-3-large", 1024).orElseThrow();
        assertThat(reason).contains("白名单").contains("openai:text-embedding-3-large")
                .contains("bailian:text-embedding-v4");

        // 同一条件在两条路径上给不同错误码：保存侧是用户输入问题，使用侧是平台配置问题
        assertThatThrownBy(() -> policy.requireAllowed("openai", "text-embedding-3-large", 1024,
                ErrorCode.PARAM_INVALID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        assertThatThrownBy(() -> policy.requireAllowed("openai", "text-embedding-3-large", 1024,
                ErrorCode.MODEL_ROLE_NOT_CONFIGURED))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.MODEL_ROLE_NOT_CONFIGURED.getCode());
    }

    @Test
    @DisplayName("维度不是 1024（含 null / 0 / 负数）一律拒绝：不截断、不补零、不降级")
    void rejectsOtherDimensions() {
        EmbeddingByokPolicy policy = policy("bailian:text-embedding-v4");

        for (Integer bad : new Integer[]{768, 1536, 2048, 0, -1, null}) {
            assertThat(policy.violationOf("bailian", "text-embedding-v4", bad))
                    .as("维度 %s 必须被拒绝", bad)
                    .isPresent();
        }
        String reason = policy.violationOf("bailian", "text-embedding-v4", 768).orElseThrow();
        assertThat(reason).contains("1024").contains("768");
    }

    @Test
    @DisplayName("provider / 模型名缺一不可：null 与空白都不算白名单")
    void requiresBothProviderAndModel() {
        EmbeddingByokPolicy policy = policy("bailian:text-embedding-v4");

        assertThat(policy.isAllowed(null, "text-embedding-v4")).isFalse();
        assertThat(policy.isAllowed("bailian", null)).isFalse();
        assertThat(policy.isAllowed("bailian", "   ")).isFalse();
        assertThat(policy.violationOf(null, null, 1024).orElseThrow()).contains("同时");
        assertThat(policy.violationOf("bailian", null, 1024).orElseThrow()).contains("同时");
    }

    @Test
    @DisplayName("白名单是配置项：加一项即放行（不必改代码），且对外只给不可变快照")
    void whitelistIsConfigDrivenAndImmutable() {
        AiModelProperties properties = new AiModelProperties();
        properties.getByok().getEmbedding().setAllowedModels(new ArrayList<>(List.of("bailian:text-embedding-v4")));
        EmbeddingByokPolicy policy = new EmbeddingByokPolicy(properties);

        properties.getByok().getEmbedding().getAllowedModels().add("bailian:text-embedding-v3");

        assertThat(policy.isAllowed("bailian", "text-embedding-v3")).isTrue();
        assertThat(policy.allowedModels()).containsExactly("bailian:text-embedding-v4", "bailian:text-embedding-v3");
        assertThatThrownBy(() -> policy.allowedModels().add("bailian:whatever"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static EmbeddingByokPolicy policy(String... allowedModels) {
        AiModelProperties properties = new AiModelProperties();
        properties.getByok().getEmbedding().setAllowedModels(new ArrayList<>(List.of(allowedModels)));
        properties.getByok().getEmbedding().setRequiredDimensions(1024);
        return new EmbeddingByokPolicy(properties);
    }
}
