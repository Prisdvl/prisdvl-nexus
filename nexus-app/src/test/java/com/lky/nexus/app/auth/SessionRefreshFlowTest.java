package com.lky.nexus.app.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jayway.jsonpath.JsonPath;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.mapper.SysUserSessionMapper;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.app.auth.model.entity.SysUserSessionPO;
import com.lky.nexus.app.auth.security.UserAuthCache;
import com.lky.nexus.app.auth.session.RefreshTokenHasher;
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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4：Refresh Token + 设备会话管理验收。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionRefreshFlowTest {

    private static final String PASSWORD = "Passw0rd!23";
    private static final String NEW_PASSWORD = "NewPassw0rd!45";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SysUserSessionMapper sessionMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private UserAuthCache userAuthCache;

    @Test
    @DisplayName("登录返回 refreshToken 并创建会话行（库中仅存 hash）")
    void loginReturnsRefreshAndCreatesHashedSession() throws Exception {
        register("sess-login");
        String body = login("sess-login", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").isString())
                .andExpect(jsonPath("$.data.expiresIn").isNumber())
                .andExpect(jsonPath("$.data.user.username").value("sess-login"))
                .andReturn().getResponse().getContentAsString();

        String refresh = JsonPath.read(body, "$.data.refreshToken");
        Long userId = Long.valueOf(JsonPath.read(body, "$.data.user.id").toString());
        assertThat(refresh).isNotBlank();

        List<SysUserSessionPO> rows = sessionMapper.selectList(Wrappers.<SysUserSessionPO>lambdaQuery()
                .eq(SysUserSessionPO::getUserId, userId));
        assertThat(rows).hasSize(1);
        SysUserSessionPO row = rows.get(0);
        assertThat(row.getTokenHash()).isEqualTo(RefreshTokenHasher.hash(refresh));
        assertThat(row.getTokenHash()).isNotEqualTo(refresh);
        assertThat(row.getRevoked()).isZero();
        // 响应/库行不得把明文写进可序列化字段以外的地方；库绝不存明文
        assertThat(row.getTokenHash()).doesNotContain(refresh);
    }

    @Test
    @DisplayName("refresh：正常换发 + rotation；旧 refresh 再用不行")
    void refreshRotatesAndInvalidatesOldToken() throws Exception {
        register("sess-refresh");
        String loginBody = login("sess-refresh", PASSWORD).andReturn().getResponse().getContentAsString();
        String oldRefresh = JsonPath.read(loginBody, "$.data.refreshToken");
        String oldAccess = JsonPath.read(loginBody, "$.data.accessToken");

        String refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + oldRefresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").isString())
                .andExpect(jsonPath("$.data.expiresIn").isNumber())
                .andReturn().getResponse().getContentAsString();

        String newRefresh = JsonPath.read(refreshed, "$.data.refreshToken");
        String newAccess = JsonPath.read(refreshed, "$.data.accessToken");
        assertThat(newRefresh).isNotEqualTo(oldRefresh);
        assertThat(newAccess).isNotEqualTo(oldAccess);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + oldRefresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + newAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("sess-refresh"));
    }

    @Test
    @DisplayName("过期 / 已撤销 refresh → 401")
    void expiredOrRevokedRefreshFails() throws Exception {
        register("sess-exp");
        String loginBody = login("sess-exp", PASSWORD).andReturn().getResponse().getContentAsString();
        String refresh = JsonPath.read(loginBody, "$.data.refreshToken");
        Long userId = Long.valueOf(JsonPath.read(loginBody, "$.data.user.id").toString());

        SysUserSessionPO row = sessionMapper.selectOne(Wrappers.<SysUserSessionPO>lambdaQuery()
                .eq(SysUserSessionPO::getUserId, userId)
                .eq(SysUserSessionPO::getTokenHash, RefreshTokenHasher.hash(refresh)));
        // 与 RefreshTokenService 一致：会话时间按 UTC LocalDateTime 比较
        row.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        sessionMapper.updateById(row);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));

        // 再登录一支，logout 撤销后 refresh 失败
        String body2 = login("sess-exp", PASSWORD).andReturn().getResponse().getContentAsString();
        String refresh2 = JsonPath.read(body2, "$.data.refreshToken");
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh2 + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh2 + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    @DisplayName("logout：当前设备退出后 refresh 失效；access 仍可至 exp/version")
    void logoutRevokesCurrentDeviceOnly() throws Exception {
        register("sess-logout");
        String a = login("sess-logout", PASSWORD).andReturn().getResponse().getContentAsString();
        String b = login("sess-logout", PASSWORD).andReturn().getResponse().getContentAsString();
        String refreshA = JsonPath.read(a, "$.data.refreshToken");
        String refreshB = JsonPath.read(b, "$.data.refreshToken");
        String accessA = JsonPath.read(a, "$.data.accessToken");

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshA + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshA + "\"}"))
                .andExpect(status().isUnauthorized());

        // 另一设备仍可 refresh
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshB + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // access 未立刻删除（仍可用，直到 exp / token_version）
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("会话列表 + 指定设备退出 + 全设备退出")
    void listRevokeOneAndRevokeAll() throws Exception {
        register("sess-mgmt");
        String a = login("sess-mgmt", PASSWORD).andReturn().getResponse().getContentAsString();
        String b = login("sess-mgmt", PASSWORD).andReturn().getResponse().getContentAsString();
        String accessA = JsonPath.read(a, "$.data.accessToken");
        String refreshA = JsonPath.read(a, "$.data.refreshToken");
        String refreshB = JsonPath.read(b, "$.data.refreshToken");

        String listBody = mockMvc.perform(get("/api/users/me/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessA)
                        .header("X-Refresh-Token", refreshA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        assertThat(listBody).doesNotContain(refreshA).doesNotContain(refreshB);
        assertThat(listBody).doesNotContain("tokenHash").doesNotContain("token_hash");

        // 找非 current 的那条删掉
        List<Object> ids = JsonPath.read(listBody, "$.data[*].id");
        List<Boolean> currents = JsonPath.read(listBody, "$.data[*].current");
        String otherId = null;
        for (int i = 0; i < ids.size(); i++) {
            if (!Boolean.TRUE.equals(currents.get(i))) {
                otherId = ids.get(i).toString();
                break;
            }
        }
        assertThat(otherId).isNotBlank();

        mockMvc.perform(delete("/api/users/me/sessions/" + otherId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessA))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshB + "\"}"))
                .andExpect(status().isUnauthorized());

        // 全设备退出
        mockMvc.perform(post("/api/users/me/sessions/revoke-all")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessA))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshA + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("安全：A 不能删除 B 的会话")
    void userCannotRevokeAnotherUsersSession() throws Exception {
        register("sess-alice");
        register("sess-bob");
        String alice = login("sess-alice", PASSWORD).andReturn().getResponse().getContentAsString();
        String bob = login("sess-bob", PASSWORD).andReturn().getResponse().getContentAsString();
        String aliceAccess = JsonPath.read(alice, "$.data.accessToken");
        Long bobUserId = Long.valueOf(JsonPath.read(bob, "$.data.user.id").toString());

        SysUserSessionPO bobSession = sessionMapper.selectOne(Wrappers.<SysUserSessionPO>lambdaQuery()
                .eq(SysUserSessionPO::getUserId, bobUserId)
                .last("LIMIT 1"));
        assertThat(bobSession).isNotNull();

        mockMvc.perform(delete("/api/users/me/sessions/" + bobSession.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceAccess))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(2010));
    }

    @Test
    @DisplayName("并发 refresh：同一旧 token 最多一次成功")
    void concurrentRefreshOnlyOneSucceeds() throws Exception {
        register("sess-race");
        String loginBody = login("sess-race", PASSWORD).andReturn().getResponse().getContentAsString();
        String refresh = JsonPath.read(loginBody, "$.data.refreshToken");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    var result = mockMvc.perform(post("/api/auth/refresh")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"refreshToken\":\"" + refresh + "\"}"))
                            .andReturn();
                    int status = result.getResponse().getStatus();
                    if (status == 200) {
                        ok.incrementAndGet();
                    } else {
                        fail.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(ok.get()).isEqualTo(1);
        assertThat(fail.get()).isEqualTo(threads - 1);
    }

    @Test
    @DisplayName("禁用用户：既有 refresh 不得换发新 access；会话保持未撤销（未消耗）")
    void disabledUserCannotRefresh() throws Exception {
        register("sess-disabled");
        String loginBody = login("sess-disabled", PASSWORD).andReturn().getResponse().getContentAsString();
        String refresh = JsonPath.read(loginBody, "$.data.refreshToken");
        String access = JsonPath.read(loginBody, "$.data.accessToken");
        Long userId = Long.valueOf(JsonPath.read(loginBody, "$.data.user.id").toString());

        // 显式 set：updateById 在 NOT_NULL 策略下可能跳过 status=0
        sysUserMapper.update(null, Wrappers.<SysUserPO>lambdaUpdate()
                .eq(SysUserPO::getId, userId)
                .set(SysUserPO::getStatus, 0));
        userAuthCache.invalidate(userId);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));

        // 禁用检查在 revoke 之前：失败不得签发，且不得提交撤销旧会话
        SysUserSessionPO row = sessionMapper.selectOne(Wrappers.<SysUserSessionPO>lambdaQuery()
                .eq(SysUserSessionPO::getUserId, userId)
                .eq(SysUserSessionPO::getTokenHash, RefreshTokenHasher.hash(refresh)));
        assertThat(row).isNotNull();
        assertThat(row.getRevoked()).isZero();

        // access：UserAuthCache 在缓存未命中时拒绝禁用账号
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("改密：token_version +1 且全部 refresh 会话撤销")
    void changePasswordRevokesAllRefreshSessions() throws Exception {
        register("sess-chgpwd");
        String a = login("sess-chgpwd", PASSWORD).andReturn().getResponse().getContentAsString();
        String b = login("sess-chgpwd", PASSWORD).andReturn().getResponse().getContentAsString();
        String access = JsonPath.read(a, "$.data.accessToken");
        String refreshA = JsonPath.read(a, "$.data.refreshToken");
        String refreshB = JsonPath.read(b, "$.data.refreshToken");
        Long userId = Long.valueOf(JsonPath.read(a, "$.data.user.id").toString());

        SysUserPO before = sysUserMapper.selectById(userId);
        long verBefore = before.getTokenVersion() == null ? 0L : before.getTokenVersion();

        mockMvc.perform(put("/api/users/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldPassword":"%s","newPassword":"%s"}
                                """.formatted(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isOk());

        SysUserPO after = sysUserMapper.selectById(userId);
        assertThat(after.getTokenVersion()).isEqualTo(verBefore + 1);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshA + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshB + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------

    private ResultActions register(String username) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","nickname":"%s","email":"%s@example.com"}
                                """.formatted(username, PASSWORD, username, username)))
                .andExpect(jsonPath("$.code").value(0));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .content("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, password)));
    }
}
