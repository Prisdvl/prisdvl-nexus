package com.lky.nexus.app.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.app.auth.mapper.SysRoleMapper;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.mapper.SysUserRoleMapper;
import com.lky.nexus.app.auth.model.RoleCode;
import com.lky.nexus.app.auth.model.dto.AuthTokenResponse;
import com.lky.nexus.app.auth.model.dto.ChangePasswordRequest;
import com.lky.nexus.app.auth.model.dto.LoginRequest;
import com.lky.nexus.app.auth.model.dto.RegisterRequest;
import com.lky.nexus.app.auth.model.dto.SessionClientInfo;
import com.lky.nexus.app.auth.model.dto.UpdateProfileRequest;
import com.lky.nexus.app.auth.model.dto.UserInfoResponse;
import com.lky.nexus.app.auth.email.Emails;
import com.lky.nexus.app.auth.model.entity.SysRolePO;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.app.auth.model.entity.SysUserRolePO;
import com.lky.nexus.app.auth.security.UserAuthCache;
import com.lky.nexus.app.auth.service.AuthService;
import com.lky.nexus.app.auth.session.RefreshTokenService;
import com.lky.nexus.app.auth.token.TokenService;
import com.lky.nexus.app.security.LoginAttemptLimiter;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

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

    /** 需要第二因子：1 已启用两步验证。 */
    private static final int TOTP_ENABLED = 1;

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final com.lky.nexus.app.workspace.WorkspaceService workspaceService;
    private final UserAuthCache userAuthCache;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final com.lky.nexus.app.auth.service.TwoFactorService twoFactorService;

    public AuthServiceImpl(SysUserMapper sysUserMapper,
                           SysRoleMapper sysRoleMapper,
                           SysUserRoleMapper sysUserRoleMapper,
                           PasswordEncoder passwordEncoder,
                           TokenService tokenService,
                           RefreshTokenService refreshTokenService,
                           com.lky.nexus.app.workspace.WorkspaceService workspaceService,
                           UserAuthCache userAuthCache,
                           LoginAttemptLimiter loginAttemptLimiter,
                           com.lky.nexus.app.auth.service.TwoFactorService twoFactorService) {
        this.sysUserMapper = sysUserMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.workspaceService = workspaceService;
        this.userAuthCache = userAuthCache;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.twoFactorService = twoFactorService;
    }

    @Override
    @Transactional
    public UserInfoResponse register(RegisterRequest request) {
        String username = request.username().trim();
        if (findByUsername(username) != null) {
            throw new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS, "用户名 " + username + " 已被占用");
        }
        // 邮箱同样预检（5.6 补的）：sys_user 上有 uk_user_email(email, deleted)，
        // 不预检就会以 9999/500 的形式暴露"撞唯一键"——用户看到"系统内部错误"，
        // 完全不知道该去换邮箱。残留竞态（预检通过后、insert 前被别人抢注）仍会撞唯一键，
        // 概率在微秒级，此处不额外 catch —— 没有可靠的依据判断撞的是用户名还是邮箱，
        // 硬猜一个码只会把错误信息编错。
        // 邮箱必填：规范化 + 格式校验（trim + 小写）；新注册一律未验证。
        String email = Emails.normalizeValid(request.email());
        requireEmailAvailable(email, null);

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
        user.setEmail(email);
        user.setEmailVerified(0);
        user.setNickname(StringUtils.hasText(request.nickname()) ? request.nickname().trim() : username);
        user.setStatus(STATUS_ENABLED);
        sysUserMapper.insert(user);

        SysUserRolePO userRole = new SysUserRolePO();
        userRole.setUserId(user.getId());
        userRole.setRoleId(defaultRole.getId());
        sysUserRoleMapper.insert(userRole);

        // 平台化 Phase 2：注册即拥有个人工作区（User → Workspace → 资源 的起点）
        Long personalWorkspaceId = workspaceService.requirePersonalWorkspace(user.getId());
        log.info("用户注册成功 userId={}, username={}, personalWorkspaceId={}",
                user.getId(), username, personalWorkspaceId);
        return toUserInfo(user, List.of(RoleCode.USER));
    }

    @Override
    public AuthTokenResponse login(LoginRequest request, SessionClientInfo clientInfo) {
        String username = request.username().trim();
        // 冻结检查放在最前面：连"这个用户存不存在"都不该在被冻结时泄露出去。
        // 命中冻结直接 7001/429，即使密码正确也拦——那正是"冻结"的含义。
        loginAttemptLimiter.checkAllowed(username);

        SysUserPO user = findByUsername(username);

        // 用户不存在与密码错误返回同一个错误码：防止账号枚举
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("登录失败（凭证错误） username={}", username);
            loginAttemptLimiter.recordFailure(username);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        // 账号被禁用不计入失败次数：密码是对的，不是"在猜"，
        // 记进去只会让一个持有正确密码的用户把自己刷到冻结
        if (!Integer.valueOf(STATUS_ENABLED).equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }

        // 两步验证：密码对了还差第二因子。
        //
        // 位置很关键——必须挡在 reset 之前：否则攻击者只要知道密码，
        // 就能用"密码正确 + 验证码乱填"的请求把受害人的失败计数清零，
        // 等于亲手帮他解冻（并且让 6 位码的爆破次数不再受限）。
        if (Integer.valueOf(TOTP_ENABLED).equals(user.getTotpEnabled())) {
            if (!StringUtils.hasText(request.totpCode())) {
                // 缺码不记失败：这不是"在猜"，用户只是还没输第二步。
                // 单独一个 2011 让前端知道该弹验证码输入框，而不是提示"用户名或密码错误"。
                throw new BusinessException(ErrorCode.TOTP_REQUIRED);
            }
            try {
                twoFactorService.verifyLogin(user.getId(), request.totpCode().trim());
            } catch (BusinessException e) {
                loginAttemptLimiter.recordFailure(username);
                log.warn("登录失败（第二因子无效） username={}", username);
                throw e;
            }
        }

        // 成功即清零：否则"错几次、成功一次"的正常用户会慢慢累加到冻结
        loginAttemptLimiter.reset(username);

        List<String> roles = sysRoleMapper.selectRoleCodesByUserId(user.getId());

        SysUserPO lastLoginUpdate = new SysUserPO();
        lastLoginUpdate.setId(user.getId());
        lastLoginUpdate.setLastLoginAt(LocalDateTime.now());
        sysUserMapper.updateById(lastLoginUpdate);

        // 版本号写进令牌（5.6）：改密码会把它 +1，于是这支令牌立刻失效
        long tokenVersion = user.getTokenVersion() == null ? 0L : user.getTokenVersion();
        TokenService.IssuedToken issuedToken =
                tokenService.issueAccessToken(user.getId(), user.getUsername(), roles, tokenVersion);
        String refreshToken = refreshTokenService.issueSession(user.getId(), clientInfo);

        log.info("登录成功 userId={}, username={}, roles={}", user.getId(), user.getUsername(), roles);
        return new AuthTokenResponse(
                issuedToken.token(),
                refreshToken,
                "Bearer",
                issuedToken.expiresIn(),
                toUserInfo(user, roles));
    }

    @Override
    public UserInfoResponse currentUser(Long userId) {
        SysUserPO user = requireUser(userId);
        return toUserInfo(user, sysRoleMapper.selectRoleCodesByUserId(userId));
    }

    /**
     * 修改密码（5.6）。
     *
     * <p><b>为什么原密码错误返回 1001/400 而不是 2006/401</b>：调用方已经用自己那支
     * Token 通过了认证，此时回 401 会让前端全局拦截器立刻清 Token 并跳登录页——
     * 把一个"打错一个字"的输入错误升级成会话中断。这里也不构成账号枚举：
     * 攻击者必须先持有该账号的合法 Token 才够得着这个接口。
     * 代价是"登录失败"与"改密失败"的口径不同（登录=未认证 2006/401，改密=已认证的参数错误 1001/400），
     * 这是刻意的区分，写在 docs/api.md 与 ADR-020 里。
     *
     * <p><b>为什么没有 {@code @Transactional}</b>：这里只有一条 UPDATE，单条语句本身原子，
     * 不需要事务。反过来，套上 {@code @Transactional} 会让 {@code invalidate} 跑在**提交之前**
     * ——缓存先被删掉、新版本还没提交，此时并发请求读到的是库里的旧版本并把它回填进缓存，
     * 于是缓存里留下一个过期值直到 TTL（正是 UserAuthCache 注释里那个竞态）。
     * 不挂事务，"语句返回"就等于"已提交"，失效顺序天然正确。
     */
    @Override
    public void changePassword(Long userId, ChangePasswordRequest request) {
        SysUserPO user = requireUser(userId);

        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "原密码不正确");
        }
        if (request.oldPassword().equals(request.newPassword())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "新密码不能与原密码相同");
        }

        // 密码与版本号必须在同一条语句里改掉：分成两次 UPDATE 的话，
        // 存在"密码已改、版本没动"的中间态，旧 Token 会继续有效。
        // setSql 自增的先例见 ChatMessageServiceImpl（那里也是"读出来再写回"会有并发覆盖）。
        sysUserMapper.update(null, Wrappers.<SysUserPO>lambdaUpdate()
                .eq(SysUserPO::getId, userId)
                .set(SysUserPO::getPasswordHash, passwordEncoder.encode(request.newPassword()))
                .setSql("token_version = token_version + 1"));

        // 让缓存里的旧版本立刻作废（尽力而为：失败只计数，旧令牌最多再活一个 TTL）
        userAuthCache.invalidate(userId);
        // 改密 = 所有设备重新登录：access 靠 token_version；refresh 靠会话撤销
        refreshTokenService.revokeAllSessions(userId);
        log.info("用户修改密码成功，已作废其此前签发的全部令牌与 refresh 会话 userId={}", userId);
    }

    @Override
    public UserInfoResponse updateProfile(Long userId, UpdateProfileRequest request) {
        SysUserPO current = requireUser(userId);

        // 用 LambdaUpdateWrapper 而不是 updateById(entity)：MyBatis-Plus 默认跳过实体里的 null 字段，
        // 那会让"清空邮箱"变成静默空操作——接口看起来成功了，值还在。
        // email_verified 也必须用 set 显式写入，避免 NOT_NULL 策略跳过 0。
        LambdaUpdateWrapper<SysUserPO> update = Wrappers.<SysUserPO>lambdaUpdate().eq(SysUserPO::getId, userId);
        boolean changed = false;

        if (request.nickname() != null) {
            update.set(SysUserPO::getNickname, blankToNull(request.nickname()));
            changed = true;
        }
        if (request.email() != null) {
            // 账号必须绑定邮箱：传了 email 字段就不允许空串清空
            String email = Emails.normalizeValid(request.email());
            requireEmailAvailable(email, userId);
            update.set(SysUserPO::getEmail, email);
            // 规范化后地址变化 → 必须重新验证；仅大小写差异不算更换
            String currentNorm = current.getEmail() == null
                    ? null
                    : Emails.normalizeOrNull(current.getEmail());
            if (!Objects.equals(currentNorm, email)) {
                update.set(SysUserPO::getEmailVerified, 0);
            }
            changed = true;
        }
        if (!changed) {
            // 两个字段都没传：与其回一个"成功但什么也没发生"，不如直说
            throw new BusinessException(ErrorCode.PARAM_INVALID, "没有需要更新的字段");
        }

        sysUserMapper.update(null, update);
        log.info("用户资料已更新 userId={}", userId);
        return toUserInfo(requireUser(userId), sysRoleMapper.selectRoleCodesByUserId(userId));
    }

    /** 取当前用户；Token 合法但用户已被删除时按未认证处理，让前端回到登录页。 */
    private SysUserPO requireUser(Long userId) {
        SysUserPO user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "用户不存在或已注销");
        }
        return user;
    }

    /** 去掉首尾空白；结果为空串时返回 {@code null}（用于"清空"语义）。 */
    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private SysUserPO findByUsername(String username) {
        return sysUserMapper.selectOne(Wrappers.<SysUserPO>lambdaQuery().eq(SysUserPO::getUsername, username));
    }

    private UserInfoResponse toUserInfo(SysUserPO user, List<String> roles) {
        boolean verified = Integer.valueOf(1).equals(user.getEmailVerified())
                && StringUtils.hasText(user.getEmail());
        return new UserInfoResponse(
                user.getId(), user.getUsername(), user.getNickname(), user.getEmail(), verified, roles);
    }

    /**
     * 邮箱占用预检（5.6）。{@code selfId} 用于"改资料时撞上自己"——那时不算占用。
     *
     * <p>空邮箱直接放行：{@code uk_user_email(email, deleted)} 在 MySQL / H2 下都允许多行 NULL，
     * 因此"没填邮箱"或"清空邮箱"不会被唯一键挡住。
     *
     * <p>用 {@code LOWER(email)} 比对，与 {@link Emails} 规范化一致，避免大小写变体绕过唯一性。
     */
    private void requireEmailAvailable(String email, Long selfId) {
        if (email == null) {
            return;
        }
        SysUserPO existing = sysUserMapper.selectOne(Wrappers.<SysUserPO>lambdaQuery()
                .apply("LOWER(email) = {0}", email));
        if (existing != null && !existing.getId().equals(selfId)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS, "邮箱 " + email + " 已被占用");
        }
    }
}
