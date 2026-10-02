package com.lky.nexus.app.auth;

import com.jayway.jsonpath.JsonPath;
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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5.6 验收测试：个人资料编辑 + 邮箱占用。
 *
 * <p>覆盖点：
 * <ol>
 *   <li>改昵称 / 改邮箱生效，且 {@code GET /me} 立刻反映；</li>
 *   <li>邮箱撞别人 → 409 + <b>2007</b>；撞自己（等于没改）不算占用；</li>
 *   <li><b>注册用重复邮箱 → 409 + 2007 而不是 9999/500</b>——这是这一期顺手修掉的
 *       静默不一致：{@code sys_user} 有 {@code uk_user_email}，而注册原先只预检用户名，
 *       撞唯一键时用户只能看到"系统内部错误"，不知道该去换邮箱；</li>
 *   <li>空串 = 清空（半更新语义的两半都要验，否则"传空串"到底是不改还是清空全靠猜）；</li>
 *   <li>两个字段都不传 → 1001，而不是"成功但什么都没发生"；</li>
 *   <li>不带 Token → 401。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UpdateProfileTest {

    private static final String PASSWORD = "Profile!Pwd123";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("改昵称与邮箱：立即生效，GET /me 能读到新值")
    void profileUpdateTakesEffect() throws Exception {
        register("profile-basic", "profile-basic@example.com");
        String token = loginToken("profile-basic");

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"新的昵称","email":"profile-new@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("新的昵称"))
                .andExpect(jsonPath("$.data.email").value("profile-new@example.com"));

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("新的昵称"))
                .andExpect(jsonPath("$.data.email").value("profile-new@example.com"))
                // 接口绝不返回密码哈希（即使改了资料也不例外）
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("只传昵称：邮箱保持不动（缺失 = 不修改，不能被误当成清空）")
    void omittedFieldIsNotCleared() throws Exception {
        register("profile-partial", "profile-partial@example.com");
        String token = loginToken("profile-partial");

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"只改名字"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("只改名字"))
                .andExpect(jsonPath("$.data.email").value("profile-partial@example.com"));
    }

    @Test
    @DisplayName("邮箱传空串 = 清空（能真的落成空值，不是静默空操作）")
    void blankEmailClearsIt() throws Exception {
        register("profile-clear", "profile-clear@example.com");
        String token = loginToken("profile-clear");

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":""}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(nullValue()));

        // 再读一次，确认落库了（不是只改了返回值）
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.email").value(nullValue()));
    }

    @Test
    @DisplayName("邮箱撞别人 → 409 + 2007")
    void emailOwnedByAnotherUserRejected() throws Exception {
        register("profile-owner", "profile-owned@example.com");
        register("profile-other", "profile-other@example.com");
        String otherToken = loginToken("profile-other");

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"profile-owned@example.com"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(2007));

        // 失败不能有副作用
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(jsonPath("$.data.email").value("profile-other@example.com"));
    }

    @Test
    @DisplayName("邮箱等于自己原有的 → 不算占用，正常成功")
    void keepingOwnEmailIsAllowed() throws Exception {
        register("profile-self", "profile-self@example.com");
        String token = loginToken("profile-self");

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"改名字不改邮箱","email":"profile-self@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("profile-self@example.com"));
    }

    @Test
    @DisplayName("★ 注册用重复邮箱 → 409 + 2007（修掉原先撞唯一键导致的 9999/500）")
    void duplicateEmailAtRegistrationReturnsConflictNotServerError() throws Exception {
        register("profile-reg-a", "profile-reg-dup@example.com");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"profile-reg-b","password":"%s","email":"profile-reg-dup@example.com"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(2007));
    }

    @Test
    @DisplayName("两个字段都不传 → 400 + 1001（而不是「成功但什么也没发生」）")
    void emptyUpdateRejected() throws Exception {
        register("profile-empty", "profile-empty@example.com");
        String token = loginToken("profile-empty");

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("邮箱格式非法 → 400 + 1001（由参数校验挡下，不进业务逻辑）")
    void malformedEmailRejected() throws Exception {
        register("profile-bad-email", "profile-bad-email@example.com");
        String token = loginToken("profile-bad-email");

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("不带 Token → 401")
    void anonymousCannotUpdateProfile() throws Exception {
        mockMvc.perform(put("/api/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"anonymous"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private void register(String username, String email) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","email":"%s"}
                                """.formatted(username, PASSWORD, email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private String loginToken(String username) throws Exception {
        ResultActions actions = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, PASSWORD)))
                .andExpect(status().isOk());
        String token = JsonPath.read(actions.andReturn().getResponse().getContentAsString(), "$.data.accessToken");
        assertThat(token).isNotBlank();
        return token;
    }
}
