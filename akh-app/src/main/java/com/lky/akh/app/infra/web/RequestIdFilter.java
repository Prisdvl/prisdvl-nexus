package com.lky.akh.app.infra.web;

import com.lky.akh.common.constant.AkhHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 请求 ID 过滤器：为每个请求建立全链路追踪标识。
 *
 * <p>职责（对应 docs/api.md 1.1 与 docs/architecture.md §14）：
 * <ol>
 *   <li>读取上游传入的 {@code X-Request-Id}（便于 Nginx / 网关串联）；没有则生成；</li>
 *   <li>写入 MDC，使该请求的所有日志自动携带该 ID；</li>
 *   <li>回写响应头，前端报错时可把 ID 直接给后端定位。</li>
 * </ol>
 *
 * <p><b>安全细节（容易被忽略）</b>：直接把客户端传来的 header 塞进日志会造成
 * <i>日志伪造 / 日志注入</i>（攻击者塞入换行符就能伪造日志行）。因此这里对上游值做白名单
 * 校验：只允许 {@code [A-Za-z0-9_-]} 且长度 1–64，不合法则丢弃并重新生成。
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} 保证它在其他过滤器之前执行，
 * 这样后续任何过滤器/业务日志都能带上 requestId。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /** 合法请求 ID 的字符白名单，兼作日志注入防护。 */
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        RequestIdHolder.set(requestId);
        response.setHeader(AkhHeaders.REQUEST_ID, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理：Tomcat 线程是复用的，不清理会把上一个请求的 ID 泄漏给下一个请求
            RequestIdHolder.clear();
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String candidate = request.getHeader(AkhHeaders.REQUEST_ID);
        if (candidate != null && SAFE_REQUEST_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
