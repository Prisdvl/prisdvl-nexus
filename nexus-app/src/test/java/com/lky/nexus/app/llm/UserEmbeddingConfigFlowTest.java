package com.lky.nexus.app.llm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.app.security.CredentialCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「嵌入模型」BYOK 配置的接口契约（BYOK 路线 A）。
 *
 * <p>这里钉的是<b>保存侧</b>的四件事：白名单、固定维度、字段级加密、以及启用时的完备性；
 * 使用侧（解析 / 冻结一致性 / 用户隔离）在 {@code KnowledgeEmbeddingByokTest} 里验证。
 * 两侧共用同一个 {@code EmbeddingByokPolicy}，所以规则的真相只有一份。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("嵌入模型配置：白名单 / 维度 / 加密 / 接口契约")
class UserEmbeddingConfigFlowTest {

    private static final String PASSWORD = "Passw0rd!23";
    private static final String PLAIN_KEY = "sk-byok-abcdefghijklmnop123456";
    private static final String BASE_URL = "https://byok-tenant.example.com/compatible-mode/v1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserEmbeddingConfigMapper userEmbeddingConfigMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private CredentialCipher credentialCipher;

    @Test
    @DisplayName("未配置时 GET 也返回白名单与允许维度，供前端直接渲染合法选项")
    void returnsWhitelistEvenWithoutConfig() throws Exception {
        String token = registerAndLogin("emb-cfg-empty");

        mockMvc.perform(get("/api/users/me/embedding-config").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.hasApiKey").value(false))
                .andExpect(jsonPath("$.data.provider").isEmpty())
                .andExpect(jsonPath("$.data.requiredDimensions").value(1024))
                // 测试 profile 的白名单有两项（见 application-test.yml），生产只有 v4
                .andExpect(jsonPath("$.data.allowedModels.length()").value(2))
                .andExpect(jsonPath("$.data.allowedModels[0]").value("bailian:text-embedding-v4"));
    }

    @Test
    @DisplayName("保存成功：库里是密文（≠ 明文、可解回），维度落库 1024，回显是明文掩码")
    void savesWithWhitelistedModelAndEncryptedKey() throws Exception {
        String token = registerAndLogin("emb-cfg-save");

        String body = putConfig(token, """
                {"provider":"bailian","modelName":"text-embedding-v4","baseUrl":"%s",
                 "apiKey":"%s","enabled":true}
                """.formatted(BASE_URL, PLAIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.hasApiKey").value(true))
                .andExpect(jsonPath("$.data.provider").value("bailian"))
                .andExpect(jsonPath("$.data.modelName").value("text-embedding-v4"))
                .andExpect(jsonPath("$.data.dimensions").value(1024))
                // mask("sk-byok-abcdefghijklmnop123456") = 前 3 + … + 后 2
                .andExpect(jsonPath("$.data.apiKeyMasked").value("sk-…56"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(PLAIN_KEY);

        UserEmbeddingConfigPO row = rowOf("emb-cfg-save");
        assertThat(row.getProvider()).isEqualTo("bailian");
        assertThat(row.getModelName()).isEqualTo("text-embedding-v4");
        assertThat(row.getDimensions()).isEqualTo(1024);
        assertThat(row.getApiKey()).startsWith(CredentialCipher.VERSION_PREFIX);
        assertThat(row.getApiKey()).doesNotContain(PLAIN_KEY);
        assertThat(credentialCipher.decrypt(row.getApiKey())).isEqualTo(PLAIN_KEY);
    }

    @Test
    @DisplayName("白名单外 / 非 1024 维 / 缺字段：一律 400 + 1001，不落库")
    void rejectsInvalidInputs() throws Exception {
        String token = registerAndLogin("emb-cfg-invalid");

        // 白名单外（openai:text-embedding-3-large 不在 ai.byok.embedding.allowed-models 里）
        putConfig(token, """
                {"provider":"openai","modelName":"text-embedding-3-large","baseUrl":"%s",
                 "apiKey":"%s","enabled":true}
                """.formatted(BASE_URL, PLAIN_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        // 白名单内但维度不是 1024：显式传了就按传入值校验，不静默忽略
        putConfig(token, """
                {"provider":"bailian","modelName":"text-embedding-v4","baseUrl":"%s",
                 "apiKey":"%s","dimensions":768,"enabled":true}
                """.formatted(BASE_URL, PLAIN_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        // provider 与模型名只给一个
        putConfig(token, """
                {"provider":"bailian","baseUrl":"%s","apiKey":"%s","enabled":true}
                """.formatted(BASE_URL, PLAIN_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        // 启用但没填 Base URL（前三组都没落库，这里也不该留下半条记录）
        putConfig(token, """
                {"provider":"bailian","modelName":"text-embedding-v4","apiKey":"%s","enabled":true}
                """.formatted(PLAIN_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        assertThat(userEmbeddingConfigMapper.selectCount(new LambdaQueryWrapper<UserEmbeddingConfigPO>()
                .eq(UserEmbeddingConfigPO::getUserId, userIdOf("emb-cfg-invalid")))).isZero();
    }

    @Test
    @DisplayName("apiKey 留空 = 保留旧密文（不无脑重加密）")
    void blankKeyKeepsStoredCiphertext() throws Exception {
        String token = registerAndLogin("emb-cfg-keep");
        putConfig(token, """
                {"provider":"bailian","modelName":"text-embedding-v4","baseUrl":"%s",
                 "apiKey":"%s","enabled":true}
                """.formatted(BASE_URL, PLAIN_KEY)).andExpect(status().isOk());
        String cipherBefore = rowOf("emb-cfg-keep").getApiKey();

        // 只改模型名，Key 留空
        putConfig(token, """
                {"provider":"bailian","modelName":"text-embedding-v3","baseUrl":"%s","enabled":true}
                """.formatted(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelName").value("text-embedding-v3"))
                .andExpect(jsonPath("$.data.apiKeyMasked").value("sk-…56"));

        assertThat(rowOf("emb-cfg-keep").getApiKey()).isEqualTo(cipherBefore);
    }

    @Test
    @DisplayName("用户隔离：A 的配置在 B 那里完全不可见，也不会被 B 读到")
    void isolatesBetweenUsers() throws Exception {
        String tokenA = registerAndLogin("emb-cfg-owner");
        String tokenB = registerAndLogin("emb-cfg-other");

        putConfig(tokenA, """
                {"provider":"bailian","modelName":"text-embedding-v4","baseUrl":"%s",
                 "apiKey":"%s","enabled":true}
                """.formatted(BASE_URL, PLAIN_KEY)).andExpect(status().isOk());

        mockMvc.perform(get("/api/users/me/embedding-config").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.hasApiKey").value(false))
                .andExpect(jsonPath("$.data.apiKeyMasked").isEmpty());

        assertThat(userEmbeddingConfigMapper.selectCount(new LambdaQueryWrapper<UserEmbeddingConfigPO>()
                .eq(UserEmbeddingConfigPO::getUserId, userIdOf("emb-cfg-other")))).isZero();
    }

    @Test
    @DisplayName("未认证访问 → 401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/users/me/embedding-config"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));
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

    private ResultActions putConfig(String token, String json) throws Exception {
        return mockMvc.perform(put("/api/users/me/embedding-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private Long userIdOf(String username) {
        SysUserPO user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUserPO>().eq(SysUserPO::getUsername, username));
        assertThat(user).as("注册后应能查到用户 %s", username).isNotNull();
        return user.getId();
    }

    private UserEmbeddingConfigPO rowOf(String username) {
        UserEmbeddingConfigPO row = userEmbeddingConfigMapper.selectOne(
                new LambdaQueryWrapper<UserEmbeddingConfigPO>()
                        .eq(UserEmbeddingConfigPO::getUserId, userIdOf(username)));
        assertThat(row).as("用户 %s 应已有一行嵌入配置", username).isNotNull();
        return row;
    }
}
