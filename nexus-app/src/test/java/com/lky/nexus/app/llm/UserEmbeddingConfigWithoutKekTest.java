package com.lky.nexus.app.llm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KEK 未配置时 BYOK 嵌入凭据必须 <b>fail-closed</b>。
 *
 * <p>这是 P1 凭证安全的关键取舍，也是"掩码 ≠ 加密"之后最容易退化的地方：
 * 没有 KEK 时如果"顺手按明文存下去"，功能看起来完全正常——直到有人翻库。
 * 所以这里用一个<b>独立的 Spring 上下文</b>（把 {@code nexus.security.crypto.kek} 覆盖成空）
 * 钉住三件事：写入报 9999、库里不会留下那一行、读取路径照常可用（不因为写不了就整体不可用）。
 */
@SpringBootTest(properties = "nexus.security.crypto.kek=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("嵌入凭据：未配置 KEK 时写入 fail-closed")
class UserEmbeddingConfigWithoutKekTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserEmbeddingConfigMapper userEmbeddingConfigMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Test
    @DisplayName("保存 Key → 500/9999 且不落库；GET 仍能返回白名单（不是整体不可用）")
    void refusesToStorePlaintextWithoutKek() throws Exception {
        String token = registerAndLogin("emb-cfg-nokek");

        mockMvc.perform(put("/api/users/me/embedding-config")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"bailian","modelName":"text-embedding-v4",
                                 "baseUrl":"https://byok-tenant.example.com/v1",
                                 "apiKey":"sk-byok-abcdefghijklmnop123456","enabled":true}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(9999));

        assertThat(userEmbeddingConfigMapper.selectCount(new LambdaQueryWrapper<UserEmbeddingConfigPO>()
                .eq(UserEmbeddingConfigPO::getUserId, userIdOf("emb-cfg-nokek"))))
                .as("没有 KEK 时不允许写下任何一行（哪怕是明文）")
                .isZero();

        // 读取路径不碰密钥：没配置 KEK 也该能读到白名单，用户至少知道"平台允许什么"
        mockMvc.perform(get("/api/users/me/embedding-config").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasApiKey").value(false));
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

    private Long userIdOf(String username) {
        SysUserPO user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUserPO>().eq(SysUserPO::getUsername, username));
        assertThat(user).as("注册后应能查到用户 %s", username).isNotNull();
        return user.getId();
    }
}
