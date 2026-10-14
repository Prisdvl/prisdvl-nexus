package com.lky.nexus.app.auth.session;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lky.nexus.app.auth.config.NexusJwtProperties;
import com.lky.nexus.app.auth.mapper.SysRoleMapper;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.mapper.SysUserSessionMapper;
import com.lky.nexus.app.auth.model.dto.RefreshTokenResponse;
import com.lky.nexus.app.auth.model.dto.SessionClientInfo;
import com.lky.nexus.app.auth.model.dto.UserSessionResponse;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.app.auth.model.entity.SysUserSessionPO;
import com.lky.nexus.app.auth.token.TokenService;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * Refresh Token 签发、rotation 与会话管理。
 *
 * <p>与 {@link TokenService} 分工：后者只签 access JWT；本类负责 opaque refresh、
 * 行级会话与 rotation 竞争控制。不复用 EmailCodeStore / SecurityStore。
 *
 * <p>轮换原子性：旧会话的条件撤销在独立事务中提交；随后签发失败时旧 refresh
 * <b>不会</b>因回滚而重新生效（调用方须重新登录）。
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private static final int STATUS_ENABLED = 1;

    private final SysUserSessionMapper sessionMapper;
    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final TokenService tokenService;
    private final NexusJwtProperties jwtProperties;
    private final Clock clock;
    private final TransactionTemplate requiresNewTx;

    /**
     * 必须显式 {@link Autowired}：本类另有包可见的多参构造供测试注入 Clock，
     * 多构造且无标记时 Spring 会去找无参构造并启动失败。
     */
    @Autowired
    public RefreshTokenService(SysUserSessionMapper sessionMapper,
                               SysUserMapper sysUserMapper,
                               SysRoleMapper sysRoleMapper,
                               TokenService tokenService,
                               NexusJwtProperties jwtProperties,
                               PlatformTransactionManager transactionManager) {
        this(sessionMapper, sysUserMapper, sysRoleMapper, tokenService, jwtProperties,
                Clock.systemUTC(), transactionManager);
    }

    /** 测试可注入固定时钟。 */
    RefreshTokenService(SysUserSessionMapper sessionMapper,
                        SysUserMapper sysUserMapper,
                        SysRoleMapper sysRoleMapper,
                        TokenService tokenService,
                        NexusJwtProperties jwtProperties,
                        Clock clock,
                        PlatformTransactionManager transactionManager) {
        this.sessionMapper = sessionMapper;
        this.sysUserMapper = sysUserMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.tokenService = tokenService;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 登录成功后创建会话，返回明文 refresh（仅此一次交给调用方写入响应）。
     */
    public String issueSession(Long userId, SessionClientInfo clientInfo) {
        String refreshToken = RefreshTokenGenerator.generate();
        persistNewSession(userId, refreshToken, clientInfo);
        return refreshToken;
    }

    /**
     * Refresh rotation：校验会话与账户 → 条件撤销（独立提交）→ 签发新 access + refresh。
     *
     * <p>账户状态在撤销前检查，与登录一致：禁用 / 软删用户不得换发新令牌。
     * 旧会话一旦条件撤销成功即提交；后续签发失败会记 error 日志且旧 refresh 保持失效。
     */
    public RefreshTokenResponse refresh(String refreshToken, SessionClientInfo clientInfo) {
        SysUserSessionPO session = requireActiveSession(refreshToken);
        // 必须在 revoke 之前：禁用账号不应消耗（并提交撤销）合法 refresh
        SysUserPO user = requireEnabledUser(session.getUserId());

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Boolean won = requiresNewTx.execute(status ->
                sessionMapper.revokeIfActive(session.getId(), now) == 1);
        if (!Boolean.TRUE.equals(won)) {
            // 并发 refresh：另一请求已抢先撤销
            log.warn("refresh 竞争失败（会话已被撤销） sessionId={} userId={}",
                    session.getId(), session.getUserId());
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "刷新令牌无效或已失效");
        }

        try {
            List<String> roles = sysRoleMapper.selectRoleCodesByUserId(user.getId());
            long tokenVersion = user.getTokenVersion() == null ? 0L : user.getTokenVersion();
            TokenService.IssuedToken access =
                    tokenService.issueAccessToken(user.getId(), user.getUsername(), roles, tokenVersion);

            String newRefresh = RefreshTokenGenerator.generate();
            SessionClientInfo info = clientInfo != null ? clientInfo
                    : SessionClientInfo.of(session.getIpAddress(), session.getUserAgent());
            // 继承设备信息：刷新时 UA 可能变化，优先用本次请求；缺失则沿用旧行
            if (info.userAgent() == null && session.getUserAgent() != null) {
                info = SessionClientInfo.of(
                        info.ipAddress() != null ? info.ipAddress() : session.getIpAddress(),
                        session.getUserAgent());
            }
            persistNewSession(user.getId(), newRefresh, info);

            log.info("refresh rotation 成功 userId={} oldSessionId={}", user.getId(), session.getId());
            return new RefreshTokenResponse(
                    access.token(), newRefresh, "Bearer", access.expiresIn());
        } catch (BusinessException ex) {
            log.error("refresh rotation 旧会话已撤销但签发失败（旧 refresh 不会恢复） sessionId={} userId={}",
                    session.getId(), user.getId());
            throw ex;
        } catch (RuntimeException ex) {
            log.error("refresh rotation 旧会话已撤销但签发失败（旧 refresh 不会恢复） sessionId={} userId={}",
                    session.getId(), user.getId(), ex);
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "刷新令牌无效或已失效");
        }
    }

    /** 当前设备退出：按 refresh hash 撤销；幂等。 */
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "refreshToken 不能为空");
        }
        String hash = RefreshTokenHasher.hash(refreshToken);
        SysUserSessionPO session = sessionMapper.selectOne(Wrappers.<SysUserSessionPO>lambdaQuery()
                .eq(SysUserSessionPO::getTokenHash, hash)
                .last("LIMIT 1"));
        if (session == null) {
            // 不暴露"token 是否存在"
            return;
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        sessionMapper.revokeIfActive(session.getId(), now);
        log.info("会话已退出（当前设备） sessionId={} userId={}", session.getId(), session.getUserId());
    }

    /** 指定设备退出：只能撤销自己的会话。 */
    public void revokeSession(Long userId, Long sessionId) {
        SysUserSessionPO session = sessionMapper.selectById(sessionId);
        if (session == null || !Objects.equals(session.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        sessionMapper.revokeIfActive(session.getId(), now);
        log.info("会话已退出（指定设备） sessionId={} userId={}", sessionId, userId);
    }

    /** 全部设备退出（方案 A：遍历/批量 revoke）。 */
    public void revokeAllSessions(Long userId) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int n = sessionMapper.revokeAllActiveForUser(userId, now);
        log.info("已撤销用户全部 refresh 会话 userId={} count={}", userId, n);
    }

    /**
     * 列出当前用户未过期的会话（含已撤销的近期行？——只列未撤销且未过期，更贴近"设备管理"）。
     *
     * <p>{@code currentRefreshToken} 可选：用于标记 {@code current=true}；不传则全部 false。
     */
    public List<UserSessionResponse> listSessions(Long userId, String currentRefreshToken) {
        String currentHash = null;
        if (currentRefreshToken != null && !currentRefreshToken.isBlank()) {
            currentHash = RefreshTokenHasher.hash(currentRefreshToken);
        }
        String finalCurrentHash = currentHash;
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        return sessionMapper.selectList(Wrappers.<SysUserSessionPO>lambdaQuery()
                        .eq(SysUserSessionPO::getUserId, userId)
                        .eq(SysUserSessionPO::getRevoked, 0)
                        .gt(SysUserSessionPO::getExpiresAt, now)
                        .orderByDesc(SysUserSessionPO::getLastUsedAt))
                .stream()
                .map(s -> new UserSessionResponse(
                        s.getId(),
                        s.getDeviceName() != null ? s.getDeviceName() : "Unknown device",
                        s.getIpAddress(),
                        s.getLastUsedAt(),
                        s.getCreatedAt(),
                        finalCurrentHash != null && finalCurrentHash.equals(s.getTokenHash())))
                .toList();
    }

    private SysUserSessionPO requireActiveSession(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "刷新令牌无效或已失效");
        }
        String hash = RefreshTokenHasher.hash(refreshToken);
        SysUserSessionPO session = sessionMapper.selectOne(Wrappers.<SysUserSessionPO>lambdaQuery()
                .eq(SysUserSessionPO::getTokenHash, hash)
                .last("LIMIT 1"));
        if (session == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "刷新令牌无效或已失效");
        }
        if (Integer.valueOf(1).equals(session.getRevoked())) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "刷新令牌无效或已失效");
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (session.getExpiresAt() == null || !session.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "刷新令牌无效或已失效");
        }
        return session;
    }

    private SysUserPO requireEnabledUser(Long userId) {
        SysUserPO user = sysUserMapper.selectById(userId);
        if (user == null || !Integer.valueOf(STATUS_ENABLED).equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "刷新令牌无效或已失效");
        }
        return user;
    }

    private void persistNewSession(Long userId, String refreshToken, SessionClientInfo clientInfo) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDateTime expiresAt = now.plus(jwtProperties.getRefreshTokenTtl());

        SysUserSessionPO row = new SysUserSessionPO();
        row.setUserId(userId);
        row.setTokenHash(RefreshTokenHasher.hash(refreshToken));
        if (clientInfo != null) {
            row.setDeviceName(clientInfo.deviceName());
            row.setUserAgent(clientInfo.userAgent());
            row.setIpAddress(clientInfo.ipAddress());
        }
        row.setCreatedAt(now);
        row.setLastUsedAt(now);
        row.setExpiresAt(expiresAt);
        row.setRevoked(0);
        sessionMapper.insert(row);
    }
}
