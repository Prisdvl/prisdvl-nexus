package com.lky.nexus.app.llm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.app.security.CredentialCipher;
import com.lky.nexus.app.security.NexusSecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 换过 KEK 之后，用户必须还能自救（P0）。
 *
 * <p>这钉的是一个<b>死结</b>：密文只有加密它的那把 KEK 解得开，而 KEK 是部署侧环境变量
 * （换机器、换 .env、容器里少挂一个变量都会变）。旧实现里 {@code upsert} 第一件事就是
 * 无条件解密旧值，于是：
 * <pre>
 *   保存新 Key → 先解密旧密文 → 抛 9999「请重新保存一次」
 *               ↑ 而"重新保存"走的正是同一条会抛错的路径
 * </pre>
 * 用户被永久锁在门外，且报错信息本身在教他做一件做不到的事。
 *
 * <p>修法有两条，这里都钉住：
 * <ul>
 *   <li>填了新 Key 的路径<b>不碰</b>旧密文 —— 重存一次就能恢复；</li>
 *   <li>读路径（GET / 关开关）容错 —— 页面能渲染出来，用户看得见自己被卡在哪。</li>
 * </ul>
 *
 * <p>用真的"另一把 KEK"产密文（而不是伪造一段坏 base64），因为线上就是这个形状。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("凭据加密：换过 KEK 后仍可重新保存（P0）")
class CredentialRotationRecoveryTest {

    private static final String PASSWORD = "Passw0rd!23";
    /** 与 application-test.yml 里的测试 KEK 不同的另一把（base64 的 16 字节）。 */
    private static final String OTHER_KEK =
            Base64.getEncoder().encodeToString("another-kek-16b!".getBytes());
    private static final String OLD_KEY = "sk-stale-abcdefghijklmn";
    private static final String NEW_KEY = "sk-newkey-1234567890";
    /** mask("sk-newkey-1234567890") = 前 3 + … + 后 2。 */
    private static final String NEW_MASK = "sk-…90";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserLlmConfigMapper userLlmConfigMapper;

    @Autowired
    private UserEmbeddingConfigMapper userEmbeddingConfigMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private CredentialCipher credentialCipher;

    @Test
    @DisplayName("聊天 Key：换了 KEK 后 GET 不再 500、页面能渲染；重新填 Key 即可恢复")
    void chatKeyRecoversAfterKekRotation() throws Exception {
        String username = "llm-rotated-recover";
        String token = registerAndLogin(username);
        insertStaleChatRow(username);

        // ① 解不开也不能让 GET 变成 9999：用户至少要知道"这儿有一行、但读不出来"
        mockMvc.perform(get("/api/users/me/llm-config").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasApiKey").value(true))
                .andExpect(jsonPath("$.data.apiKeyMasked").doesNotExist());

        // ② 重新保存一次必须成功（这就是修复前做不到的那一步）
        putChatConfig(token, NEW_KEY, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeyMasked").value(NEW_MASK));

        UserLlmConfigPO row = chatRowOf(username);
        assertThat(credentialCipher.decrypt(row.getApiKey()))
                .as("新 Key 用当前 KEK 加密，能解回")
                .isEqualTo(NEW_KEY);
    }

    @Test
    @DisplayName("聊天 Key：解不开时「留空 + 启用」给可自救的 400/1001，但关掉开关不拦")
    void chatKeyBlankEnableIsActionableError() throws Exception {
        String username = "llm-rotated-blank";
        String token = registerAndLogin(username);
        insertStaleChatRow(username);
        String staleCipher = chatRowOf(username).getApiKey();

        // 留空 + 想启用：不能启用（Key 用不了），但错误码必须是"你该怎么做"的 1001，不是 9999
        putChatConfig(token, "", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("重新填写")));

        assertThat(chatRowOf(username).getApiKey())
                .as("被拦下时不允许改动已存密文")
                .isEqualTo(staleCipher);

        // 关掉开关（enabled=false）不该被旧密文挡住：这行本来就解不开，用户仍有权利弃用它
        putChatConfig(token, "", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.hasApiKey").value(true));
    }

    @Test
    @DisplayName("嵌入 Key：同一死结在 BYOK 路线 A 上也已解开（GET 容错 + 可重新保存）")
    void embeddingKeyRecoversAfterKekRotation() throws Exception {
        String username = "emb-rotated-recover";
        String token = registerAndLogin(username);
        insertStaleEmbeddingRow(username);

        mockMvc.perform(get("/api/users/me/embedding-config").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasApiKey").value(true))
                .andExpect(jsonPath("$.data.apiKeyMasked").doesNotExist());

        mockMvc.perform(put("/api/users/me/embedding-config")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"bailian","modelName":"text-embedding-v4",
                                 "baseUrl":"https://byok-tenant.example.com/v1",
                                 "apiKey":"%s","dimensions":1024,"enabled":true}
                                """.formatted(NEW_KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeyMasked").value(NEW_MASK))
                .andExpect(jsonPath("$.data.enabled").value(true));
    }

    // ------------------------------------------------------------------

    /** 造一行"用另一把 KEK 加密"的凭据：等价于线上换过 KEK 之后的库。 */
    private CredentialCipher otherCipher() {
        NexusSecurityProperties properties = new NexusSecurityProperties();
        properties.getCrypto().setKek(OTHER_KEK);
        return new CredentialCipher(properties);
    }

    private void insertStaleChatRow(String username) {
        UserLlmConfigPO stale = new UserLlmConfigPO();
        stale.setUserId(userIdOf(username));
        stale.setBaseUrl("https://api.deepseek.com");
        stale.setModelName("deepseek-chat");
        stale.setApiKey(otherCipher().encrypt(OLD_KEY));
        stale.setEnabled(1);
        userLlmConfigMapper.insert(stale);
    }

    private void insertStaleEmbeddingRow(String username) {
        UserEmbeddingConfigPO stale = new UserEmbeddingConfigPO();
        stale.setUserId(userIdOf(username));
        stale.setProvider("bailian");
        stale.setModelName("text-embedding-v4");
        stale.setBaseUrl("https://byok-tenant.example.com/v1");
        stale.setApiKey(otherCipher().encrypt(OLD_KEY));
        stale.setDimensions(1024);
        stale.setEnabled(1);
        userEmbeddingConfigMapper.insert(stale);
    }

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

    private org.springframework.test.web.servlet.ResultActions putChatConfig(
            String token, String apiKey, boolean enabled) throws Exception {
        return mockMvc.perform(put("/api/users/me/llm-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"baseUrl":"https://api.deepseek.com","modelName":"deepseek-chat",
                         "apiKey":"%s","enabled":%s}
                        """.formatted(apiKey, enabled)));
    }

    private Long userIdOf(String username) {
        SysUserPO user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUserPO>().eq(SysUserPO::getUsername, username));
        assertThat(user).as("注册后应能查到用户 %s", username).isNotNull();
        return user.getId();
    }

    private UserLlmConfigPO chatRowOf(String username) {
        UserLlmConfigPO row = userLlmConfigMapper.selectOne(new LambdaQueryWrapper<UserLlmConfigPO>()
                .eq(UserLlmConfigPO::getUserId, userIdOf(username)));
        assertThat(row).as("用户 %s 应已有一行配置", username).isNotNull();
        return row;
    }
}
