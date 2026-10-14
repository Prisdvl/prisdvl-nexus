package com.lky.nexus.app.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.app.auth.email.EmailCodeService;
import com.lky.nexus.app.auth.email.EmailPurpose;
import com.lky.nexus.app.auth.email.Emails;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.model.dto.UserInfoResponse;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 已登录用户验证当前绑定邮箱归属。
 *
 * <p>复用 {@link EmailCodeService}（用途 {@link EmailPurpose#VERIFY_EMAIL}），不另起存储。
 * 发送目标与校验邮箱一律取自数据库当前绑定值，不接受客户端指定邮箱或 userId。
 *
 * <p><b>校验成功与落库的失败策略</b>：先原子消费验证码，再写 {@code email_verified=1}。
 * 若落库失败或并发下邮箱已变（更新行数 ≠ 1），抛 {@link ErrorCode#INTERNAL_ERROR} /
 * 业务错误，<b>绝不</b>向客户端宣称已验证成功。验证码已被消费时需重新发送。
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    private static final int VERIFIED = 1;

    private final SysUserMapper sysUserMapper;
    private final EmailCodeService emailCodeService;
    private final AuthService authService;

    public EmailVerificationService(SysUserMapper sysUserMapper,
                                    EmailCodeService emailCodeService,
                                    AuthService authService) {
        this.sysUserMapper = sysUserMapper;
        this.emailCodeService = emailCodeService;
        this.authService = authService;
    }

    /** 向当前用户数据库中的绑定邮箱发送 {@link EmailPurpose#VERIFY_EMAIL} 验证码。 */
    public void send(Long userId, String clientIp) {
        SysUserPO user = requireUser(userId);
        String email = requireBoundEmail(user);
        emailCodeService.send(email, EmailPurpose.VERIFY_EMAIL, clientIp);
        log.info("已请求发送邮箱验证码 userId={} purpose={}", userId, EmailPurpose.VERIFY_EMAIL.keyToken());
    }

    /**
     * 校验验证码并将当前用户标记为邮箱已验证。
     *
     * @return 更新后的用户视图（含 {@code emailVerified=true}）
     */
    public UserInfoResponse verify(Long userId, String code) {
        SysUserPO user = requireUser(userId);
        String email = requireBoundEmail(user);
        String normalized = Emails.normalize(email);

        // 先消费验证码：成功则键已删除；失败抛业务异常，数据库不动
        emailCodeService.verify(normalized, EmailPurpose.VERIFY_EMAIL, code);

        // 条件更新：邮箱在发送后被改掉时 rows=0，不得误报成功
        int rows;
        try {
            rows = sysUserMapper.update(null, Wrappers.<SysUserPO>lambdaUpdate()
                    .eq(SysUserPO::getId, userId)
                    .eq(SysUserPO::getEmail, email)
                    .set(SysUserPO::getEmailVerified, VERIFIED));
        } catch (RuntimeException ex) {
            log.error("邮箱验证落库失败 userId={}", userId, ex);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "邮箱验证状态更新失败，请重新获取验证码后再试", ex);
        }
        if (rows != 1) {
            log.warn("邮箱验证落库未生效（邮箱可能已变更） userId={} rows={}", userId, rows);
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "邮箱已变更，请重新发送验证码");
        }

        log.info("用户邮箱已验证 userId={}", userId);
        return authService.currentUser(userId);
    }

    private SysUserPO requireUser(Long userId) {
        SysUserPO user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "用户不存在或已注销");
        }
        return user;
    }

    private static String requireBoundEmail(SysUserPO user) {
        if (!StringUtils.hasText(user.getEmail())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "请先在个人资料中绑定邮箱，再进行验证");
        }
        // 规范化 + 格式：发送/校验都走同一规则；条件更新仍用库中原值匹配
        Emails.normalizeValid(user.getEmail());
        return user.getEmail().trim();
    }
}
