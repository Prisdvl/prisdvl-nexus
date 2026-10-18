package com.lky.nexus.app.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.ai.config.AiModelProperties;
import com.lky.nexus.ai.model.EmbeddingClient;
import com.lky.nexus.ai.model.EmbeddingClientFactory;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.app.llm.UserEmbeddingConfigMapper;
import com.lky.nexus.app.llm.UserEmbeddingConfigPO;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import com.lky.nexus.knowledge.mapper.KnowledgeBaseMapper;
import com.lky.nexus.knowledge.model.entity.KnowledgeBasePO;
import com.lky.nexus.knowledge.service.KnowledgeEmbeddingResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BYOK 嵌入凭据在<b>知识库侧</b>的端到端行为（路线 A）。
 *
 * <p>这里验证的是"生效配置"这条链：建库冻结什么、检索解析到哪个客户端、
 * 换模型之后旧库会怎样、以及<b>别人</b>的库会不会被我的配置影响。
 *
 * <p>刻意<b>不</b>调用真实模型：{@code resolve(..)} 只构建客户端、不发请求，
 * 所以这些断言在没有真实 Key / 没有向量的环境里也可信。真正的嵌入调用由带守卫的
 * LIVE 套件负责（不伪造通过结果）。
 *
 * <p>测试 profile 的白名单有两项（{@code bailian:text-embedding-v4} 与
 * {@code ...-v3}，见 {@code application-test.yml}），全局 {@code embedding.default}
 * 指向 v4——所以"用户库冻结在 v3"本身就证明走的是用户配置而不是全局配置。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("嵌入 BYOK：建库冻结 / 生效客户端 / 换模型拒绝 / 用户隔离")
class KnowledgeEmbeddingByokTest {

    private static final String PASSWORD = "Passw0rd!23";
    private static final String BYOK_BASE_URL = "https://byok-tenant.example.com/compatible-mode/v1";
    private static final String BYOK_KEY = "sk-byok-abcdefghijklmnop123456";
    /** 全局 embedding.default 指向的模型（application-test.yml）。 */
    private static final String GLOBAL_MODEL = "bailian:text-embedding-v4";
    private static final String OTHER_ALLOWED_MODEL = "bailian:text-embedding-v3";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    private UserEmbeddingConfigMapper userEmbeddingConfigMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private KnowledgeEmbeddingResolver embeddingResolver;

    @Autowired
    private EmbeddingClientFactory embeddingClientFactory;

    @Test
    @DisplayName("启用了自带配置的用户：建库冻结他自己的模型，检索用自己的客户端；没配的人完全不受影响")
    void freezesOwnerModelAndUsesOwnClient() throws Exception {
        String tokenA = registerAndLogin("emb-byok-a");
        String tokenB = registerAndLogin("emb-byok-b");
        saveConfig(tokenA, OTHER_ALLOWED_MODEL, true);

        // A 建库：冻结值必须是 A 自己的模型（v3），而不是全局的 v4
        KnowledgeBasePO kbA = createKnowledgeBase(tokenA, "A 的库");
        assertThat(kbA.getEmbeddingProvider()).isEqualTo("bailian");
        assertThat(kbA.getEmbeddingModel()).isEqualTo("text-embedding-v3");
        assertThat(kbA.getEmbeddingDim()).isEqualTo(1024);

        EmbeddingClient globalClient = embeddingClientFactory.get(AiModelProperties.Role.EMBEDDING_DEFAULT);
        EmbeddingClient clientA = embeddingResolver.resolve(kbA);
        assertThat(clientA.reference()).isEqualTo(OTHER_ALLOWED_MODEL);
        // 关键证据：不是全局那个实例，而是按 A 的凭据（端点 + Key）另建的客户端
        assertThat(clientA).isNotSameAs(globalClient);
        // 同一份凭据重复解析命中缓存，不会每次请求都重建
        assertThat(embeddingResolver.resolve(kbA)).isSameAs(clientA);

        // B 没配任何东西：建库冻结全局模型、解析回落到全局客户端（零回归）
        KnowledgeBasePO kbB = createKnowledgeBase(tokenB, "B 的库");
        assertThat(kbB.getEmbeddingModel()).isEqualTo("text-embedding-v4");
        EmbeddingClient clientB = embeddingResolver.resolve(kbB);
        assertThat(clientB).isSameAs(globalClient);
        assertThat(clientB).isNotSameAs(clientA);
        assertThat(clientB.reference()).isEqualTo(GLOBAL_MODEL);
    }

    @Test
    @DisplayName("换成另一个合法模型后：冻结在旧模型上的知识库被 4006 拒绝（禁用也一样，必须重建）")
    void switchingModelRejectsFrozenKnowledgeBase() throws Exception {
        String token = registerAndLogin("emb-byok-switch");
        saveConfig(token, OTHER_ALLOWED_MODEL, true);
        KnowledgeBasePO kb = createKnowledgeBase(token, "冻结在 v3 的库");

        // 换成白名单里的另一个模型：配置本身合法，但旧库冻结的是 v3
        saveConfig(token, GLOBAL_MODEL, true);
        assertThatThrownBy(() -> embeddingResolver.resolve(kb))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("冻结的嵌入模型")
                // 文案必须带"实际生效的模型标识"，且成句
                // （历史 bug：只传了主语，拼出「……而用户自带的嵌入配置。」）
                .hasMessageContaining("bailian:text-embedding-v3")
                .hasMessageContaining("用户自带的嵌入配置使用的是 bailian:text-embedding-v4")
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.EMBEDDING_FROZEN_MISMATCH.getCode());

        // HTTP 层：4009 → 409（不是 4006/502）。这条是"映射改动"唯一的对外证据：
        // 502 会说"上游坏了、稍后重试"，而这里用户的正确动作是重建索引/恢复配置。
        mockMvc.perform(post("/api/knowledge-bases/" + kb.getId() + "/embed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(4009));

        // 关掉开关也不行：关掉之后回落全局 v4，同样不等于冻结值 v3
        saveConfig(token, GLOBAL_MODEL, false);
        assertThatThrownBy(() -> embeddingResolver.resolve(kb))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("冻结的嵌入模型");

        // 新库按当前生效配置冻结（v4），可以正常解析——"重建/新建"才是恢复路径
        KnowledgeBasePO rebuilt = createKnowledgeBase(token, "换模型后新建的库");
        assertThat(rebuilt.getEmbeddingModel()).isEqualTo("text-embedding-v4");
        assertThat(embeddingResolver.resolve(rebuilt)).isNotNull();
    }

    @Test
    @DisplayName("配置保存但未启用：建库与检索都走全局（与 BYOK 上线前一致）")
    void disabledConfigUsesGlobalRole() throws Exception {
        String token = registerAndLogin("emb-byok-disabled");
        saveConfig(token, OTHER_ALLOWED_MODEL, false);

        KnowledgeBasePO kb = createKnowledgeBase(token, "未启用时的库");
        assertThat(kb.getEmbeddingModel()).isEqualTo("text-embedding-v4");
        assertThat(embeddingResolver.resolve(kb))
                .isSameAs(embeddingClientFactory.get(AiModelProperties.Role.EMBEDDING_DEFAULT));
    }

    @Test
    @DisplayName("库里躺着白名单外模型 / 非 1024 维的配置：使用侧 4006 拒绝，而不是拿去写向量")
    void rejectsStoredConfigViolatingPolicy() throws Exception {
        String token = registerAndLogin("emb-byok-badrow");
        Long userId = userIdOf("emb-byok-badrow");
        KnowledgeBasePO kb = createKnowledgeBase(token, "已有库");

        // ① 白名单外的模型（例如白名单被收紧后遗留的旧行，或有人直接改库）
        writeRowDirectly(userId, "bailian", "text-embedding-v9", 1024);
        assertThatThrownBy(() -> embeddingResolver.resolve(kb))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("白名单")
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.MODEL_ROLE_NOT_CONFIGURED.getCode());

        // ② 白名单内但维度不是平台允许值（768）：拒绝，不截断不补零
        writeRowDirectly(userId, "bailian", "text-embedding-v4", 768);
        assertThatThrownBy(() -> embeddingResolver.resolve(kb))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1024")
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.MODEL_ROLE_NOT_CONFIGURED.getCode());
    }

    // ------------------------------------------------------------------

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","email":"%s@example.com"}
                                """.formatted(username, PASSWORD, username)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 409));

        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(loginBody, "$.data.accessToken");
    }

    /** 通过接口保存嵌入配置（走真实的加密与白名单校验路径）。 */
    private void saveConfig(String token, String reference, boolean enabled) throws Exception {
        String[] parts = reference.split(":", 2);
        mockMvc.perform(put("/api/users/me/embedding-config")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"%s","modelName":"%s","baseUrl":"%s",
                                 "apiKey":"%s","enabled":%s}
                                """.formatted(parts[0], parts[1], BYOK_BASE_URL, BYOK_KEY, enabled)))
                .andExpect(status().isOk());
    }

    private KnowledgeBasePO createKnowledgeBase(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long id = Long.valueOf(JsonPath.read(body, "$.data.id").toString());
        return knowledgeBaseMapper.selectById(id);
    }

    /** 直接写库：模拟"白名单收紧后的遗留行"与"有人手工改库"，这两条路径接口不会产生。 */
    private void writeRowDirectly(Long userId, String provider, String modelName, int dimensions) {
        userEmbeddingConfigMapper.delete(new LambdaQueryWrapper<UserEmbeddingConfigPO>()
                .eq(UserEmbeddingConfigPO::getUserId, userId));
        UserEmbeddingConfigPO po = new UserEmbeddingConfigPO();
        po.setUserId(userId);
        po.setProvider(provider);
        po.setModelName(modelName);
        po.setBaseUrl(BYOK_BASE_URL);
        // 这里刻意存明文：只关心白名单/维度这一层，凭据加密另有 UserEmbeddingConfigFlowTest 覆盖
        po.setApiKey("sk-direct-abcdefghijklmnop123456");
        po.setDimensions(dimensions);
        po.setEnabled(1);
        userEmbeddingConfigMapper.insert(po);
    }

    private Long userIdOf(String username) {
        SysUserPO user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUserPO>().eq(SysUserPO::getUsername, username));
        assertThat(user).as("注册后应能查到用户 %s", username).isNotNull();
        return user.getId();
    }
}
