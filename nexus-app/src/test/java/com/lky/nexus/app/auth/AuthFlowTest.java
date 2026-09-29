package com.lky.nexus.app.auth;

import com.lky.nexus.app.auth.mapper.SysRoleMapper;
import com.lky.nexus.app.auth.mapper.SysUserRoleMapper;
import com.lky.nexus.app.auth.model.RoleCode;
import com.lky.nexus.app.auth.model.entity.SysRolePO;
import com.lky.nexus.app.auth.model.entity.SysUserRolePO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step 1.1 验收测试：注册 → 登录 → 带 Token 访问受保护接口 → RBAC 越权被拒。
 *
 * <p>覆盖点：
 * <ol>
 *   <li>完整闭环：注册后能用同一密码登录，并把拿到的 Token 用于访问 {@code /api/users/me}；</li>
 *   <li>无 Token / 伪造 Token → 401 且 body 仍是统一格式（业务码 2001）；</li>
 *   <li>重复用户名 → 409 + 2005；</li>
 *   <li>密码错误与用户不存在返回<b>完全相同</b>的提示（防账号枚举）；</li>
 *   <li>弱密码被参数校验拦截 → 400 + 1001；</li>
 *   <li>普通用户访问 {@code /api/admin/**} → 403 + 2003；授予 ADMIN 角色后重新登录即可访问。</li>
 * </ol>
 *
 * <p>用 H2 内存库跑完整鉴权链路，因此不依赖 Docker（见 docs/roadmap.md 的验证策略）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowTest {

    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Test
    @DisplayName("闭环：注册 → 登录 → 带 Token 访问受保护接口")
    void registerThenLoginThenAccessProtectedResource() throws Exception {
        register("lky");

        String loginBody = login("lky", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                // expiresIn 是基本类型 long，必须是数字（ADR-017 的配套约定）
                .andExpect(jsonPath("$.data.expiresIn").isNumber())
                .andExpect(jsonPath("$.data.user.roles[0]").value(RoleCode.USER))
                .andReturn().getResponse().getContentAsString();

        String accessToken = JsonPath.read(loginBody, "$.data.accessToken");
        assertThat(accessToken).isNotBlank();

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("lky"))
                .andExpect(jsonPath("$.data.roles[0]").value(RoleCode.USER));
    }

    @Test
    @DisplayName("注册响应：id 是字符串（雪花 ID 不丢精度），密码哈希绝不返回")
    void registerResponseNeverLeaksPasswordHash() throws Exception {
        String body = register("noleak")
                .andExpect(jsonPath("$.data.id").isString())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("passwordHash").doesNotContain("password");
    }

    @Test
    @DisplayName("未携带 Token 访问受保护接口 → 401 + 业务码 2001（统一返回体，不是 Security 默认页面）")
    void missingTokenReturns401WithUnifiedBody() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.message").value("未认证或凭证缺失"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("伪造 Token → 401（签名校验失败）")
    void forgedTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    @DisplayName("重复用户名 → 409 + 2005")
    void duplicateUsernameRejected() throws Exception {
        register("duplicated");

        // 第二次注册必须失败，因此不能复用 register() 辅助方法（它断言 code=0）
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"duplicated","password":"%s","nickname":"duplicated"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(2005));
    }

    @Test
    @DisplayName("密码错误与用户不存在的提示完全一致（防账号枚举）")
    void loginFailureDoesNotRevealAccountExistence() throws Exception {
        register("enumeration");

        String wrongPasswordMessage = login("enumeration", "WrongPassw0rd!")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2006))
                .andReturn().getResponse().getContentAsString();
        String noSuchUserMessage = login("no-such-user-at-all", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2006))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.read(wrongPasswordMessage, "$.message").toString())
                .isEqualTo(JsonPath.read(noSuchUserMessage, "$.message").toString());
    }

    @Test
    @DisplayName("弱密码被参数校验拦截 → 400 + 1001")
    void weakPasswordRejectedByValidation() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"shortpwd","password":"123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("RBAC：普通用户访问 /api/admin/** → 403 + 2003；授予 ADMIN 后重新登录即可访问")
    void roleBasedAccessControlWorks() throws Exception {
        register("rbacuser");

        String userToken = JsonPath.read(
                login("rbacuser", PASSWORD).andReturn().getResponse().getContentAsString(),
                "$.data.accessToken");

        mockMvc.perform(get("/api/admin/ping").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(2003))
                .andExpect(jsonPath("$.message").value("无权限访问该资源"));

        // 授予 ADMIN 角色（真实场景由管理后台完成，这里直接写库模拟）
        String loginBody = login("rbacuser", PASSWORD).andReturn().getResponse().getContentAsString();
        Long userId = Long.valueOf(JsonPath.read(loginBody, "$.data.user.id").toString());
        SysRolePO adminRole = sysRoleMapper.selectOne(
                Wrappers.<SysRolePO>lambdaQuery().eq(SysRolePO::getCode, RoleCode.ADMIN));
        assertThat(adminRole).as("初始化脚本应预置 ADMIN 角色").isNotNull();

        SysUserRolePO relation = new SysUserRolePO();
        relation.setUserId(userId);
        relation.setRoleId(adminRole.getId());
        sysUserRoleMapper.insert(relation);

        // 权限来源是 Token 里的 roles 声明，因此必须重新登录才能拿到含 ADMIN 的新 Token
        String adminToken = JsonPath.read(
                login("rbacuser", PASSWORD).andReturn().getResponse().getContentAsString(),
                "$.data.accessToken");

        mockMvc.perform(get("/api/admin/ping").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("pong"));
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions register(String username) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","nickname":"%s"}
                                """.formatted(username, PASSWORD, username)))
                .andExpect(jsonPath("$.code").value(0));
    }

    private org.springframework.test.web.servlet.ResultActions login(String username, String password)
            throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, password)));
    }
}
