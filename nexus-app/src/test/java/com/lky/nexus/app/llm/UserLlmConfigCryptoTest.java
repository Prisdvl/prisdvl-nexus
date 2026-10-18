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
 * 「模型 Key」的字段级加密端到端（P1 凭证安全）。
 *
 * <p>这条路要走通三件事，缺一件就等于没做：
 * ① 落库的是密文（而不是"接口掩码了、库里还是明文"）；
 * ② 回显的掩码打在<b>明文</b>上（打在密文上会把密文片段当 Key 露出去）；
 * ③ 加密之前写下的历史明文行会被就地升级，而不是永远留在库里。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("模型 Key：字段级加密 / 掩码回显 / 历史明文升级")
class UserLlmConfigCryptoTest {

    private static final String PASSWORD = "Passw0rd!23";
    private static final String PLAIN_KEY = "sk-abcdefghijklmnop123456";
    /** mask("sk-abcdefghijklmnop123456") = 前 3 + … + 后 2。 */
    private static final String MASKED = "sk-…56";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserLlmConfigMapper userLlmConfigMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private CredentialCipher credentialCipher;

    @Test
    @DisplayName("保存后库里是密文且能解回原文；回显是明文掩码，响应体里没有明文")
    void storesCiphertextAndMasksPlaintext() throws Exception {
        String token = registerAndLogin("llm-crypto-store");

        String body = putConfig(token, PLAIN_KEY, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasApiKey").value(true))
                .andExpect(jsonPath("$.data.apiKeyMasked").value(MASKED))
                .andReturn().getResponse().getContentAsString();
        // 接口永不返回明文（这条以前也成立——现在再加"库里也不存明文"）
        assertThat(body).doesNotContain(PLAIN_KEY);

        UserLlmConfigPO row = rowOf("llm-crypto-store");
        assertThat(row.getApiKey()).startsWith(CredentialCipher.VERSION_PREFIX);
        assertThat(row.getApiKey()).doesNotContain(PLAIN_KEY).doesNotContain("abcdefghijklmnop");
        assertThat(row.getApiKey()).isNotEqualTo(PLAIN_KEY);
        // 密文要能解回原文：否则不是"加密了"，是"数据丢了"
        assertThat(credentialCipher.decrypt(row.getApiKey())).isEqualTo(PLAIN_KEY);

        // 再读一次：密钥没变 ⇒ 密文不变（GET 不会重复加密，也不会破坏已有密文）
        String firstCipher = row.getApiKey();
        mockMvc.perform(get("/api/users/me/llm-config").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeyMasked").value(MASKED));
        assertThat(rowOf("llm-crypto-store").getApiKey()).isEqualTo(firstCipher);
    }

    @Test
    @DisplayName("apiKey 留空 = 保留旧值：密文原样保留，掩码不变")
    void blankKeyKeepsStoredCiphertext() throws Exception {
        String token = registerAndLogin("llm-crypto-keep");
        putConfig(token, PLAIN_KEY, true).andExpect(status().isOk());
        String cipherBefore = rowOf("llm-crypto-keep").getApiKey();

        putConfig(token, "", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeyMasked").value(MASKED));

        assertThat(rowOf("llm-crypto-keep").getApiKey()).isEqualTo(cipherBefore);
    }

    @Test
    @DisplayName("加密之前写下的历史明文行：GET 配置时就地升级为密文（幂等）")
    void upgradesLegacyPlaintextRow() throws Exception {
        String token = registerAndLogin("llm-crypto-legacy");
        // 模拟加密上线前留下的那一行：直接写明文
        UserLlmConfigPO legacy = new UserLlmConfigPO();
        legacy.setUserId(userIdOf("llm-crypto-legacy"));
        legacy.setBaseUrl("https://api.deepseek.com");
        legacy.setModelName("deepseek-chat");
        legacy.setApiKey(PLAIN_KEY);
        legacy.setEnabled(1);
        userLlmConfigMapper.insert(legacy);
        assertThat(rowOf("llm-crypto-legacy").getApiKey()).isEqualTo(PLAIN_KEY);

        // 历史明文仍要能读出来（否则升级上线即等于用户配置丢失）
        mockMvc.perform(get("/api/users/me/llm-config").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.apiKeyMasked").value(MASKED));

        UserLlmConfigPO upgraded = rowOf("llm-crypto-legacy");
        assertThat(upgraded.getApiKey()).startsWith(CredentialCipher.VERSION_PREFIX);
        assertThat(credentialCipher.decrypt(upgraded.getApiKey())).isEqualTo(PLAIN_KEY);

        // 幂等：再读一次不会又变一个密文
        String firstUpgrade = upgraded.getApiKey();
        mockMvc.perform(get("/api/users/me/llm-config").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(rowOf("llm-crypto-legacy").getApiKey()).isEqualTo(firstUpgrade);
    }

    @Test
    @DisplayName("Key 超长（> 320）→ 400/1001：密文必须塞得进 VARCHAR(500)，不在数据库层才炸")
    void rejectsOverlongKey() throws Exception {
        String token = registerAndLogin("llm-crypto-long");

        putConfigJson(token, """
                {"baseUrl":"https://api.deepseek.com","modelName":"deepseek-chat",
                 "apiKey":"%s","enabled":true}
                """.formatted("k".repeat(CredentialCipher.MAX_PLAINTEXT_LENGTH + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
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

    private ResultActions putConfig(String token, String apiKey, boolean enabled) throws Exception {
        return putConfigJson(token, """
                {"baseUrl":"https://api.deepseek.com","modelName":"deepseek-chat",
                 "apiKey":"%s","enabled":%s}
                """.formatted(apiKey, enabled));
    }

    private ResultActions putConfigJson(String token, String json) throws Exception {
        return mockMvc.perform(put("/api/users/me/llm-config")
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

    private UserLlmConfigPO rowOf(String username) {
        UserLlmConfigPO row = userLlmConfigMapper.selectOne(new LambdaQueryWrapper<UserLlmConfigPO>()
                .eq(UserLlmConfigPO::getUserId, userIdOf(username)));
        assertThat(row).as("用户 %s 应已有一行配置", username).isNotNull();
        return row;
    }
}
