package com.lky.prisma.app.auth.security;

import com.lky.prisma.common.api.ErrorCode;
import com.lky.prisma.common.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 当前登录用户读取工具。
 *
 * <p><b>user_id 的唯一可信来源是 JWT 的 sub 声明</b>，绝不能从请求参数/请求体里取 userId，
 * 否则任何人都能通过改参数越权访问他人数据（水平越权，最常见的越权漏洞）。
 *
 * <p><b>使用范围限制（对应 ADR-001）</b>：本类依赖 Spring Security 的 {@code SecurityContextHolder}，
 * 因此只允许在 {@code prisma-app} 的 Web 层使用。{@code prisma-knowledge} 里的检索方法必须把 userId
 * 作为方法参数显式传入，这样 Phase 7 的 MCP Server（无 Security 上下文）才能复用同一套代码。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * 取当前登录用户 ID；未认证时抛 2001。
     *
     * <p>正常情况下安全过滤器已保证"能进到 Controller 就是已认证"，
     * 这里的兜底是为了防止方法被非 Web 场景（如定时任务、内部调用）误用。
     */
    public static Long requireId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return Long.valueOf(jwt.getSubject());
    }
}
