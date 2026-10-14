package com.lky.nexus.app.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.app.auth.email.EmailCodeService;
import com.lky.nexus.app.auth.email.EmailPurpose;
import com.lky.nexus.app.auth.email.Emails;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.app.auth.security.UserAuthCache;
import com.lky.nexus.app.auth.session.RefreshTokenService;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 忘记密码：向已验证邮箱发送重置验证码，并在校验通过后安全更新密码。
 *
 * <p>复用 {@link EmailCodeService}（用途 {@link EmailPurpose#RESET_PASSWORD}），不另起存储。
 * 请求验证码接口对「未注册 / 未验证 / 符合条件」返回一致的模糊成功提示，避免账号枚举。
 *
 * <p><b>校验成功与落库的失败策略</b>：先原子消费验证码，再按「规范化邮箱 + 仍已验证」条件更新密码
 * 并递增 {@code token_version}。落库失败时不得向客户端宣称成功；验证码已消费，须重新申请。
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private static final int VERIFIED = 1;

    /** 防枚举统一提示（请求验证码接口成功时使用）。 */
    public static final String REQUEST_ACK_MESSAGE =
            "如果该邮箱对应符合条件的账户，请查收邮件或稍后重试。";

    public static final String CONFIRM_OK_MESSAGE = "密码已重置，请使用新密码登录";

    private final SysUserMapper sysUserMapper;
    private final EmailCodeService emailCodeService;
    private final PasswordEncoder passwordEncoder;
    private final UserAuthCache userAuthCache;
    private final RefreshTokenService refreshTokenService;

    public PasswordResetService(SysUserMapper sysUserMapper,
                                EmailCodeService emailCodeService,
                                PasswordEncoder passwordEncoder,
                                UserAuthCache userAuthCache,
                                RefreshTokenService refreshTokenService) {
        this.sysUserMapper = sysUserMapper;
        this.emailCodeService = emailCodeService;
        this.passwordEncoder = passwordEncoder;
        this.userAuthCache = userAuthCache;
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * 请求重置验证码。
     *
     * <p>对所有格式合法的申请先做统一 IP 维限流（与账户是否存在无关）；超限仍返回 429。
     * 仅当库中存在绑定该规范化邮箱且 {@code email_verified=1} 的账户时才发信。
     * 邮箱冷却命中、发信失败均吞掉并记安全日志，对外仍走模糊成功，避免账号枚举。
     */
    public void requestReset(String email, String clientIp) {
        String normalized = Emails.normalize(email);
        // 必须在查库之前：未注册/未验证邮箱也要计入同一 IP 配额
        emailCodeService.checkIpSendLimit(clientIp);

        SysUserPO user = findVerifiedByEmail(normalized);
        if (user == null) {
            log.info("密码重置验证码请求：无符合条件账户 purpose={}", EmailPurpose.RESET_PASSWORD.keyToken());
            return;
        }

        try {
            // 已计过 IP 配额，避免 send() 再计一次
            emailCodeService.sendSkippingIpLimit(normalized, EmailPurpose.RESET_PASSWORD);
            log.info("已请求发送密码重置验证码 userId={} purpose={}",
                    user.getId(), EmailPurpose.RESET_PASSWORD.keyToken());
        } catch (BusinessException ex) {
            if (ex.getCode() == ErrorCode.RATE_LIMITED.getCode()) {
                // 邮箱冷却：不向客户端暴露「该邮箱已绑定已验证账户」
                log.info("密码重置验证码请求：邮箱冷却中 userId={} purpose={}",
                        user.getId(), EmailPurpose.RESET_PASSWORD.keyToken());
                return;
            }
            // 发信失败不得用 500/专用文案区分「有账户」
            log.error("密码重置验证码发送失败 userId={} purpose={}",
                    user.getId(), EmailPurpose.RESET_PASSWORD.keyToken(), ex);
        } catch (RuntimeException ex) {
            log.error("密码重置验证码发送异常 userId={} purpose={}",
                    user.getId(), EmailPurpose.RESET_PASSWORD.keyToken(), ex);
        }
    }

    /**
     * 校验重置验证码并更新密码；成功后递增 {@code token_version} 并失效认证缓存。
     *
     * <p>不签发新 JWT。不接受客户端 userId。
     */
    public void confirmReset(String email, String code, String newPassword) {
        String normalized = Emails.normalize(email);

        // 先消费验证码：成功则键已删除；失败抛业务异常，数据库不动
        emailCodeService.verify(normalized, EmailPurpose.RESET_PASSWORD, code);

        SysUserPO user = findVerifiedByEmail(normalized);
        if (user == null) {
            log.warn("密码重置：验证码已消费但无符合条件账户 emailHash={}", Integer.toHexString(normalized.hashCode()));
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "无法完成密码重置，请重新获取验证码后再试");
        }

        int rows;
        try {
            rows = sysUserMapper.update(null, Wrappers.<SysUserPO>lambdaUpdate()
                    .eq(SysUserPO::getId, user.getId())
                    .eq(SysUserPO::getEmail, normalized)
                    .eq(SysUserPO::getEmailVerified, VERIFIED)
                    .set(SysUserPO::getPasswordHash, passwordEncoder.encode(newPassword))
                    .setSql("token_version = token_version + 1"));
        } catch (RuntimeException ex) {
            log.error("密码重置落库失败 userId={}", user.getId(), ex);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "密码更新失败，请重新获取验证码后再试", ex);
        }
        if (rows != 1) {
            log.warn("密码重置落库未生效（邮箱或验证状态可能已变更） userId={} rows={}", user.getId(), rows);
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "无法完成密码重置，请重新获取验证码后再试");
        }

        userAuthCache.invalidate(user.getId());
        // 与改密一致：token_version 吊销 access；再撤销全部 refresh 会话
        refreshTokenService.revokeAllSessions(user.getId());
        log.info("用户通过邮箱重置密码成功，已作废其此前签发的全部令牌与 refresh 会话 userId={}", user.getId());
    }

    private SysUserPO findVerifiedByEmail(String normalizedEmail) {
        return sysUserMapper.selectOne(Wrappers.<SysUserPO>lambdaQuery()
                .eq(SysUserPO::getEmail, normalizedEmail)
                .eq(SysUserPO::getEmailVerified, VERIFIED)
                .last("LIMIT 1"));
    }
}
