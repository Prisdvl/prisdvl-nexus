package com.lky.akh.app.auth.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.akh.app.auth.mapper.SysRoleMapper;
import com.lky.akh.app.auth.mapper.SysUserMapper;
import com.lky.akh.app.auth.mapper.SysUserRoleMapper;
import com.lky.akh.app.auth.model.RoleCode;
import com.lky.akh.app.auth.model.dto.AuthTokenResponse;
import com.lky.akh.app.auth.model.dto.LoginRequest;
import com.lky.akh.app.auth.model.dto.RegisterRequest;
import com.lky.akh.app.auth.model.dto.UserInfoResponse;
import com.lky.akh.app.auth.model.entity.SysRolePO;
import com.lky.akh.app.auth.model.entity.SysUserPO;
import com.lky.akh.app.auth.model.entity.SysUserRolePO;
import com.lky.akh.app.auth.service.AuthService;
import com.lky.akh.app.auth.token.TokenService;
import com.lky.akh.common.api.ErrorCode;
import com.lky.akh.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 认证服务实现。
 *
 * <p>安全要点（每一处都是真实项目的必修项）：
 * <ol>
 *   <li><b>登录失败信息统一</b>：用户不存在与密码错误都返回 2006 "用户名或密码错误"，
 *       不区分二者，避免攻击者用登录接口枚举出系统里有哪些用户名；</li>
 *   <li><b>密码只存 BCrypt 哈希</b>，校验用 {@code matches}，永不比较明文、永不解密；</li>
 *   <li><b>登录成功才更新 last_login_at</b>，且用局部更新（只带需要改的字段），
 *       避免把整行读出来再写回去造成并发覆盖；</li>
 *   <li><b>禁用账号在密码校验之后才提示</b>：先证明"你是本人"，再告知账号被禁用，
 *       否则这个接口又变成了账号存在性探测器。</li>
 * </ol>
 *
 * <p>为什么不用 {@code AuthenticationManager} + {@code UserDetailsService}：
 * 那套机制的默认行为会抛 {@code BadCredentialsException} 走 Security 的异常链，
 * 而我们需要统一的业务码与"账号枚举防护"的自定义语义。手工校验更直接、更可控；
 * 等接入 OAuth2 第三方登录时再引入 AuthenticationProvider 也不迟。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    /** 用户状态：1 正常。 */
    private static final int STATUS_ENABLED = 1;

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public AuthServiceImpl(SysUserMapper sysUserMapper,
                           SysRoleMapper sysRoleMapper,
                           SysUserRoleMapper sysUserRoleMapper,
                           PasswordEncoder passwordEncoder,
                           TokenService tokenService) {
        this.sysUserMapper = sysUserMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    @Override
    @Transactional
    public UserInfoResponse register(RegisterRequest request) {
        String username = request.username().trim();
        if (findByUsername(username) != null) {
            throw new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS, "用户名 " + username + " 已被占用");
        }

        SysRolePO defaultRole = sysRoleMapper.selectOne(
                Wrappers.<SysRolePO>lambdaQuery().eq(SysRolePO::getCode, RoleCode.USER));
        if (defaultRole == null) {
            // 属于"环境没准备好"而不是用户输入错误，必须让运维一眼看到
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "系统角色数据缺失（" + RoleCode.USER + "），请检查数据库初始化脚本");
        }

        SysUserPO user = new SysUserPO();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setEmail(StringUtils.hasText(request.email()) ? request.email().trim() : null);
        user.setNickname(StringUtils.hasText(request.nickname()) ? request.nickname().trim() : username);
        user.setStatus(STATUS_ENABLED);
        sysUserMapper.insert(user);

        SysUserRolePO userRole = new SysUserRolePO();
        userRole.setUserId(user.getId());
        userRole.setRoleId(defaultRole.getId());
        sysUserRoleMapper.insert(userRole);

        log.info("用户注册成功 userId={}, username={}", user.getId(), username);
        return toUserInfo(user, List.of(RoleCode.USER));
    }

    @Override
    public AuthTokenResponse login(LoginRequest request) {
        SysUserPO user = findByUsername(request.username().trim());

        // 用户不存在与密码错误返回同一个错误码：防止账号枚举
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("登录失败（凭证错误） username={}", request.username());
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!Integer.valueOf(STATUS_ENABLED).equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }

        List<String> roles = sysRoleMapper.selectRoleCodesByUserId(user.getId());

        SysUserPO lastLoginUpdate = new SysUserPO();
        lastLoginUpdate.setId(user.getId());
        lastLoginUpdate.setLastLoginAt(LocalDateTime.now());
        sysUserMapper.updateById(lastLoginUpdate);

        TokenService.IssuedToken issuedToken =
                tokenService.issueAccessToken(user.getId(), user.getUsername(), roles);

        log.info("登录成功 userId={}, username={}, roles={}", user.getId(), user.getUsername(), roles);
        return new AuthTokenResponse(issuedToken.token(), "Bearer", issuedToken.expiresIn(),
                toUserInfo(user, roles));
    }

    @Override
    public UserInfoResponse currentUser(Long userId) {
        SysUserPO user = sysUserMapper.selectById(userId);
        if (user == null) {
            // Token 合法但用户已被删除：按未认证处理，让前端回到登录页
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "用户不存在或已注销");
        }
        return toUserInfo(user, sysRoleMapper.selectRoleCodesByUserId(userId));
    }

    private SysUserPO findByUsername(String username) {
        return sysUserMapper.selectOne(Wrappers.<SysUserPO>lambdaQuery().eq(SysUserPO::getUsername, username));
    }

    private UserInfoResponse toUserInfo(SysUserPO user, List<String> roles) {
        return new UserInfoResponse(user.getId(), user.getUsername(), user.getNickname(), user.getEmail(), roles);
    }
}
