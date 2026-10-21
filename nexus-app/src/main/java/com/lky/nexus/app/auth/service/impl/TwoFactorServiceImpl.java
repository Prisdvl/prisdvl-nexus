package com.lky.nexus.app.auth.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.app.auth.config.NexusTotpProperties;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.mapper.UserRecoveryCodeMapper;
import com.lky.nexus.app.auth.model.dto.TotpEnableResponse;
import com.lky.nexus.app.auth.model.dto.TotpSetupResponse;
import com.lky.nexus.app.auth.model.dto.TotpStatusResponse;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.app.auth.model.entity.UserRecoveryCodePO;
import com.lky.nexus.app.auth.service.TwoFactorService;
import com.lky.nexus.app.auth.totp.TotpAlgorithm;
import com.lky.nexus.app.security.CredentialCipher;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 两步验证实现。
 *
 * <p>四件必须写清楚的事：
 * <ol>
 *   <li><b>候选密钥与"已开启"分开存</b>：{@code beginSetup} 只把密钥写进
 *       {@code totp_secret} 而 {@code totp_enabled} 保持 0。用户放着不校验，
 *       下次登录也不会被要求验证码——不会因为"点了一下绑定"就把自己锁住。</li>
 *   <li><b>密钥加密落库</b>：TOTP 密钥能推出任意时刻的验证码，等价于密码的第二半。
 *       用 {@link CredentialCipher}（同一个 KEK 体系）而不是明文，理由与 BYOK 的 API Key 一致。</li>
 *   <li><b>恢复码只存摘要</b>：明文只在 {@code enable} 的响应里出现一次。</li>
 *   <li><b>校验用 {@link MessageDigest#isEqual}</b>（在 {@link TotpAlgorithm} 里）而不是
 *       {@code String.equals}：后者按字符短路比较，泄露"前几位对了"的时间差。</li>
 * </ol>
 */
@Service
public class TwoFactorServiceImpl implements TwoFactorService {

    private static final Logger log = LoggerFactory.getLogger(TwoFactorServiceImpl.class);

    /** 恢复码去掉易混字符（0/O、1/I）后的字母表，Base32 风格 32 个字符。 */
    private static final char[] RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    /** 一枚恢复码的有效字符数（展示成 5-5 两组）。 */
    private static final int RECOVERY_CODE_LENGTH = 10;

    private final SysUserMapper sysUserMapper;
    private final UserRecoveryCodeMapper recoveryCodeMapper;
    private final PasswordEncoder passwordEncoder;
    private final CredentialCipher credentialCipher;
    private final NexusTotpProperties properties;
    private final SecureRandom random = new SecureRandom();

    public TwoFactorServiceImpl(SysUserMapper sysUserMapper,
                               UserRecoveryCodeMapper recoveryCodeMapper,
                               PasswordEncoder passwordEncoder,
                               CredentialCipher credentialCipher,
                               NexusTotpProperties properties) {
        this.sysUserMapper = sysUserMapper;
        this.recoveryCodeMapper = recoveryCodeMapper;
        this.passwordEncoder = passwordEncoder;
        this.credentialCipher = credentialCipher;
        this.properties = properties;
    }

    @Override
    public TotpStatusResponse status(Long userId) {
        SysUserPO user = requireUser(userId);
        boolean enabled = isEnabled(user);
        long left = enabled ? recoveryCodeMapper.countUnused(userId) : 0L;
        return new TotpStatusResponse(enabled, left, user.getTotpConfirmedAt());
    }

    @Override
    @Transactional
    public TotpSetupResponse beginSetup(Long userId) {
        SysUserPO user = requireUser(userId);
        if (isEnabled(user)) {
            throw new BusinessException(ErrorCode.TOTP_ALREADY_ENABLED);
        }
        String secret = TotpAlgorithm.randomSecret();
        // 只写候选密钥，不动 totp_enabled：这一步失败（用户扫完没回填）不能影响他下次登录。
        sysUserMapper.update(null, Wrappers.<SysUserPO>lambdaUpdate()
                .eq(SysUserPO::getId, userId)
                .set(SysUserPO::getTotpSecret, credentialCipher.encrypt(secret)));
        log.info("TOTP 生成候选密钥 userId={}", userId);
        return new TotpSetupResponse(
                secret,
                TotpAlgorithm.otpauthUri(properties.getIssuer(), user.getUsername(), secret,
                        properties.getDigits(), properties.getPeriodSeconds()),
                properties.getIssuer(),
                user.getUsername(),
                properties.getDigits(),
                properties.getPeriodSeconds());
    }

    @Override
    @Transactional
    public TotpEnableResponse enable(Long userId, String code) {
        SysUserPO user = requireUser(userId);
        if (isEnabled(user)) {
            throw new BusinessException(ErrorCode.TOTP_ALREADY_ENABLED);
        }
        if (!StringUtils.hasText(user.getTotpSecret())) {
            // 没走过 setup 就没有候选密钥，任何码都不该通过
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请先获取验证器密钥");
        }
        String secret = credentialCipher.decrypt(user.getTotpSecret());
        if (!TotpAlgorithm.verify(secret, code, Instant.now(), properties.getPeriodSeconds(),
                properties.getDigits(), properties.getWindow())) {
            // 这里回 1001/400 而不是 2012/401：调用方已经用自己的 Token 通过认证，
            // 401 会被前端全局拦截器当成"会话失效"→ 清 token + 跳登录页，
            // 把"这一格填错了"升级成登出（与改密码接口同一取舍，见 docs/api.md）。
            throw new BusinessException(ErrorCode.PARAM_INVALID, "验证码不正确，请核对验证器后重试");
        }

        LocalDateTime now = LocalDateTime.now();
        sysUserMapper.update(null, Wrappers.<SysUserPO>lambdaUpdate()
                .eq(SysUserPO::getId, userId)
                .set(SysUserPO::getTotpEnabled, 1)
                .set(SysUserPO::getTotpConfirmedAt, now));

        // 换绑即作废旧恢复码：旧的那套对应的是上一台设备，留着会给人"还有退路"的错觉。
        recoveryCodeMapper.delete(Wrappers.<UserRecoveryCodePO>lambdaQuery()
                .eq(UserRecoveryCodePO::getUserId, userId));
        List<String> plainCodes = new ArrayList<>(properties.getRecoveryCodes());
        for (int i = 0; i < properties.getRecoveryCodes(); i++) {
            String plain = generateRecoveryCode();
            UserRecoveryCodePO row = new UserRecoveryCodePO();
            row.setUserId(userId);
            row.setCodeHash(sha256Hex(normalizeRecoveryCode(plain)));
            row.setCreatedAt(now);
            recoveryCodeMapper.insert(row);
            plainCodes.add(plain);
        }
        log.info("TOTP 已启用 userId={}, recoveryCodes={}", userId, plainCodes.size());
        return new TotpEnableResponse(List.copyOf(plainCodes));
    }

    @Override
    @Transactional
    public void disable(Long userId, String password) {
        SysUserPO user = requireUser(userId);
        if (!isEnabled(user)) {
            throw new BusinessException(ErrorCode.TOTP_NOT_ENABLED);
        }
        // 关掉第二因子 = 降级安全等级，必须再证明一次"知道密码"。
        // 回 1001/400 而不是 401：调用方已通过 Token 认证，401 会被前端拦成"会话失效"。
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "密码不正确");
        }
        // 注意用 wrapper 显式 set null：MyBatis-Plus 的 updateById 默认跳过 null 字段，
        // 那样 totp_secret 会留在库里，下次 setup 之前都得防着它被误用。
        sysUserMapper.update(null, Wrappers.<SysUserPO>lambdaUpdate()
                .eq(SysUserPO::getId, userId)
                .set(SysUserPO::getTotpSecret, null)
                .set(SysUserPO::getTotpEnabled, 0)
                .set(SysUserPO::getTotpConfirmedAt, null));
        recoveryCodeMapper.delete(Wrappers.<UserRecoveryCodePO>lambdaQuery()
                .eq(UserRecoveryCodePO::getUserId, userId));
        log.info("TOTP 已关闭 userId={}", userId);
    }

    @Override
    public void verifyLogin(Long userId, String code) {
        SysUserPO user = requireUser(userId);
        String secret = StringUtils.hasText(user.getTotpSecret())
                ? credentialCipher.decrypt(user.getTotpSecret())
                : null;
        if (secret != null && TotpAlgorithm.verify(secret, code, Instant.now(),
                properties.getPeriodSeconds(), properties.getDigits(), properties.getWindow())) {
            return;
        }
        // 退到恢复码：格式不同（带连字符/空格、大小写都可能），先规范化再比对摘要。
        String normalized = normalizeRecoveryCode(code);
        if (normalized.length() == RECOVERY_CODE_LENGTH
                && recoveryCodeMapper.consumeIfUnused(userId, sha256Hex(normalized), LocalDateTime.now()) == 1) {
            log.warn("用户使用恢复码登录 userId={}，剩余 {} 枚", userId, recoveryCodeMapper.countUnused(userId));
            return;
        }
        throw new BusinessException(ErrorCode.TOTP_CODE_INVALID);
    }

    private SysUserPO requireUser(Long userId) {
        SysUserPO user = sysUserMapper.selectById(userId);
        if (user == null) {
            // 与 AuthServiceImpl.requireUser 同码同文案：调用方在 Token 有效但账号已注销时会看到它
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "用户不存在或已注销");
        }
        return user;
    }

    private static boolean isEnabled(SysUserPO user) {
        return Integer.valueOf(1).equals(user.getTotpEnabled());
    }

    /** 生成形如 {@code ABCDE-FGHJK} 的恢复码（5-5，方便抄写）。 */
    private String generateRecoveryCode() {
        StringBuilder raw = new StringBuilder(RECOVERY_CODE_LENGTH);
        for (int i = 0; i < RECOVERY_CODE_LENGTH; i++) {
            raw.append(RECOVERY_ALPHABET[random.nextInt(RECOVERY_ALPHABET.length)]);
        }
        return raw.substring(0, 5) + "-" + raw.substring(5);
    }

    /** 去掉连字符/空格并转大写：用户抄回来时会带上这些装饰。 */
    private static String normalizeRecoveryCode(String code) {
        return code == null ? "" : code.replace("-", "").replace(" ", "").trim().toUpperCase(Locale.ROOT);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走不到这里；真走到了也不能"降级成明文比较"
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
