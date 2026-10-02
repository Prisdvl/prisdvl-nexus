package com.lky.nexus.app.auth.security;

import com.lky.nexus.app.auth.config.NexusJwtProperties;
import com.lky.nexus.app.auth.mapper.SysUserMapper;
import com.lky.nexus.app.auth.model.entity.SysUserPO;
import com.lky.nexus.app.security.SecurityStore;
import com.lky.nexus.common.observe.ObservabilityRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 令牌版本缓存的单元测试。
 *
 * <p>重点全在**故障路径**上：这个类存在的意义是"Redis 挂了也要照样正确"，
 * 所以"store 抛异常 → 回落查库"必须有一条用例钉住——否则某次重构把 catch 去掉，
 * 表现会是"Redis 一挂全站 401"，而那要等真出事才发现。
 */
class UserAuthCacheTest {

    private final SysUserMapper sysUserMapper = mock(SysUserMapper.class);
    private final SecurityStore securityStore = mock(SecurityStore.class);
    private final ObservabilityRegistry observability = mock(ObservabilityRegistry.class);
    private final NexusJwtProperties jwtProperties = new NexusJwtProperties();

    private UserAuthCache cache() {
        return new UserAuthCache(sysUserMapper, securityStore, jwtProperties, observability);
    }

    private static SysUserPO user(long tokenVersion) {
        SysUserPO user = new SysUserPO();
        user.setId(100L);
        user.setTokenVersion(tokenVersion);
        return user;
    }

    @Test
    @DisplayName("缓存命中：直接用缓存值，不查库")
    void cacheHitSkipsDatabase() {
        when(securityStore.get("nexus:auth:tv:100")).thenReturn(OptionalLong.of(5L));

        assertThat(cache().expectedTokenVersion(100L)).isEqualTo(5L);
        verifyNoInteractions(sysUserMapper);
    }

    @Test
    @DisplayName("缓存未命中：查库并用配置的 TTL 回填")
    void cacheMissLoadsFromDatabaseAndFillsCache() {
        when(securityStore.get("nexus:auth:tv:100")).thenReturn(OptionalLong.empty());
        when(sysUserMapper.selectById(100L)).thenReturn(user(4L));

        assertThat(cache().expectedTokenVersion(100L)).isEqualTo(4L);

        verify(securityStore).put(eq("nexus:auth:tv:100"), eq(4L), eq(jwtProperties.getTokenVersionCacheTtl()));
    }

    @Test
    @DisplayName("token_version 为 null（迁移前的行）按 0 处理，不会因拆箱 NPE")
    void nullVersionTreatedAsZero() {
        when(securityStore.get(anyString())).thenReturn(OptionalLong.empty());
        SysUserPO legacy = user(0L);
        legacy.setTokenVersion(null);
        when(sysUserMapper.selectById(100L)).thenReturn(legacy);

        assertThat(cache().expectedTokenVersion(100L)).isZero();
    }

    @Test
    @DisplayName("★ store 读抛异常：回落查库，仍然给出正确版本（这就是「降级而非 fail-open/closed」）")
    void storeFailureFallsBackToDatabase() {
        when(securityStore.get(anyString())).thenThrow(new IllegalStateException("Redis 不可用"));
        when(sysUserMapper.selectById(100L)).thenReturn(user(9L));

        assertThat(cache().expectedTokenVersion(100L)).isEqualTo(9L);

        verify(observability).increment(ObservabilityRegistry.SECURITY_STORE_UNAVAILABLE);
    }

    @Test
    @DisplayName("store 写抛异常：本次请求已用真实值校验，不让缓存故障把请求变成 500")
    void storeWriteFailureDoesNotBreakRequest() {
        when(securityStore.get(anyString())).thenReturn(OptionalLong.empty());
        when(sysUserMapper.selectById(100L)).thenReturn(user(1L));
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis 不可用"))
                .when(securityStore).put(anyString(), anyLong(), any(Duration.class));

        assertThat(cache().expectedTokenVersion(100L)).isEqualTo(1L);
        verify(observability).increment(ObservabilityRegistry.SECURITY_STORE_UNAVAILABLE);
    }

    @Test
    @DisplayName("用户不存在：返回 null 且**不缓存**，避免删号后留一个「复活」窗口")
    void missingUserIsNotCached() {
        when(securityStore.get(anyString())).thenReturn(OptionalLong.empty());
        when(sysUserMapper.selectById(100L)).thenReturn(null);

        assertThat(cache().expectedTokenVersion(100L)).isNull();
        verify(securityStore, never()).put(anyString(), anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("失效：删除缓存键")
    void invalidateDeletesKey() {
        cache().invalidate(100L);

        verify(securityStore).delete("nexus:auth:tv:100");
    }

    @Test
    @DisplayName("失效失败：只计数不抛——改密码本身不能因为缓存删不掉而失败")
    void invalidateFailureIsSwallowedAndCounted() {
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis 不可用"))
                .when(securityStore).delete(any(String[].class));

        assertThatCode(() -> cache().invalidate(100L)).doesNotThrowAnyException();

        verify(observability).increment(ObservabilityRegistry.SECURITY_TOKEN_VERSION_INVALIDATE_FAILED);
    }
}
