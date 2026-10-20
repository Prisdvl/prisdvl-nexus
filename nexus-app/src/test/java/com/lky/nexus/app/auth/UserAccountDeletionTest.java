package com.lky.nexus.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.mapper.SysUserSessionMapper;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.app.auth.model.entity.SysUserSessionPO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code DELETE /api/users/me} 的行为：账号软删、会话立刻失效、不碰别人的数据。
 *
 * <p>最后一条是重点——级联写错最常见的后果不是「没删干净」，而是把别人的行一起删了。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAccountDeletionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SysUserMapper userMapper;
    @Autowired private SysUserSessionMapper sessionMapper;

    private static final String PASSWORD = "Passw0rd!23";

    @Test
    @DisplayName("注销后旧令牌失效、账号被软删、用户名可重新注册，且不碰另一个用户")
    void deleteMeSoftDeletesAccountAndKeepsOthers() throws Exception {
        long a = registerAndLogin("acct-a", "acct-a@example.com");
        String tokenA = tokenOf("acct-a");
        long b = registerAndLogin("acct-b", "acct-b@example.com");

        assertThat(countSessions(a)).as("注销前 A 应有会话").isGreaterThan(0);

        MvcResult del = mockMvc.perform(delete("/api/users/me").header("Authorization", "Bearer " + tokenA))
                .andReturn();
        assertThat(del.getResponse().getStatus()).as("注销应成功").isEqualTo(200);
        assertThat(JsonPath.<Integer>read(del.getResponse().getContentAsString(), "$.code")).isZero();

        // 旧令牌立刻不可用（会话行已被删，鉴权查不到）
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokenA))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).as("旧令牌应失效").isEqualTo(401));

        // 账号本体：逻辑删除（SysUserPO 上有 @TableLogic，所以这里查不到就是「已删」）
        // 想直接看 status/deleted 的值要用裸 SQL 绕开逻辑删除，这里不这么做——对调用方来说
        // 「查不到」和「deleted=1」是同一件事。
        assertThat(userMapper.selectById(a)).as("注销后账号应不可见").isNull();

        assertThat(countSessions(a)).as("A 的会话应清零").isZero();
        assertThat(countSessions(b)).as("B 的会话不能被删").isGreaterThan(0);
        assertThat(String.valueOf(userMapper.selectById(b).getDeleted())).as("B 不受影响").isEqualTo("0");

        // 用户名被释放 → 同名可以重新注册
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"acct-a\",\"password\":\"" + PASSWORD
                                + "\",\"email\":\"acct-a2@example.com\",\"nickname\":\"again\"}"))
                .andExpect(r -> assertThat(JsonPath.<Integer>read(
                        r.getResponse().getContentAsString(), "$.code")).isZero());
    }

    private long countSessions(long userId) {
        return sessionMapper.selectCount(Wrappers.<SysUserSessionPO>query().eq("user_id", userId));
    }

    private long registerAndLogin(String username, String email) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD
                                + "\",\"email\":\"" + email + "\",\"nickname\":\"t\"}"))
                .andReturn();
        return ((Number) tokenAndIdRaw(username)[1]).longValue();
    }

    private String tokenOf(String username) throws Exception {
        return String.valueOf(tokenAndIdRaw(username)[0]);
    }

    /** @return {accessToken, userId} */
    private Object[] tokenAndIdRaw(String username) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        String body = login.getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.data.accessToken");
        String idStr = String.valueOf(JsonPath.<Object>read(body, "$.data.user.id"));
        return new Object[] {token, Long.parseLong(idStr)};
    }
}
