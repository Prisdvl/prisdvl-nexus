package com.lky.nexus.app.security;

import com.lky.nexus.app.auth.security.CurrentUser;
import com.lky.nexus.app.infra.web.ErrorCodeHttpStatusMapper;
import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.Result;
import com.lky.nexus.common.exception.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 限流与每日配额过滤器（5.6）：模型入口的唯一收口点。
 *
 * <p><b>为什么放在 HTTP 层而不是各个 Service 里</b>：五个会话/知识库/Agent/评测/MCP 入口
 * 各写一遍检查，等于把"哪些入口花钱"这件事散布到五处，漏一个就静默免配额。
 * 在这里收口，路径清单成了唯一的事实来源（{@code nexus.security.quota.paths}）。
 *
 * <p><b>为什么挂在 {@code BearerTokenAuthenticationFilter} 之后</b>：
 * 那个位置 {@code SecurityContextHolder} 已经填好（所以 {@link CurrentUser} 可用），
 * 但仍在业务控制器之前——因此超限时返回的是统一 JSON，
 * 而不是已经开流的 SSE 帧。这条很要紧：{@code /api/chat/stream} 一旦
 * {@code new SseEmitter} 就已经向客户端承诺了 {@code text/event-stream}，
 * 那时再报错只能塞进事件里（Phase 5.2 的口径是"开流前失败走 JSON"）。
 *
 * <p><b>三个分支</b>：
 * <ol>
 *   <li>注册路径：未认证入口，按来源 IP 限流（每小时）；</li>
 *   <li>配额路径（会真的调用模型）：限流 → 占用请求额度 → 检查 token 预算；</li>
 *   <li>额外路径（改密码）：只限流，不计 token 配额——它不花模型钱。</li>
 * </ol>
 * 其余路径一律直接放行：把不限流变成默认，是为了不误伤普通读接口
 * （一个页面同时发十几个请求就够呛，20 次/分钟罩住全部接口只会招骂）。
 */
@Component
public class RateLimitQuotaFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitQuotaFilter.class);

    /** 分钟级限流命中时的 Retry-After。 */
    private static final int RETRY_AFTER_SECONDS = 60;

    private final RateLimiter rateLimiter;
    private final QuotaService quotaService;
    private final NexusSecurityProperties properties;
    private final JsonMapper jsonMapper;

    private final PathPattern registerPattern;
    private final List<PathPattern> quotaPatterns;
    private final List<PathPattern> extraPatterns;

    public RateLimitQuotaFilter(RateLimiter rateLimiter,
                                QuotaService quotaService,
                                NexusSecurityProperties properties,
                                JsonMapper jsonMapper) {
        this.rateLimiter = rateLimiter;
        this.quotaService = quotaService;
        this.properties = properties;
        this.jsonMapper = jsonMapper;

        // 路径模式在构造期解析一次：PathPattern 的解析不便宜，而这是每个请求都要用的东西
        PathPatternParser parser = PathPatternParser.defaultInstance;
        this.registerPattern = parser.parse(properties.getRegister().getPath());
        this.quotaPatterns = properties.getQuota().getPaths().stream().map(parser::parse).toList();
        this.extraPatterns = properties.getRateLimit().getExtraPaths().stream().map(parser::parse).toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = resolvePath(request);
        PathContainer container = PathContainer.parsePath(path);

        int retryAfterSeconds = RETRY_AFTER_SECONDS;
        try {
            if (registerPattern.matches(container)) {
                // 公开端点，没有 SecurityContext：身份只有来源 IP。
                // 这个桶按小时计，所以 Retry-After 也给一小时（回 60 秒会诱导客户端
                // 白白重试 59 次，每次都拿同一个 429）
                retryAfterSeconds = (int) Duration.ofHours(1).toSeconds();
                rateLimiter.checkRegister(request.getRemoteAddr());
            } else if (matchesAny(quotaPatterns, container)) {
                Long userId = CurrentUser.idOrNull();
                if (userId != null) {
                    rateLimiter.checkUser(userId);
                    quotaService.checkAndConsumeRequest(userId);
                    quotaService.checkTokenBudget(userId);
                }
            } else if (matchesAny(extraPatterns, container)) {
                Long userId = CurrentUser.idOrNull();
                if (userId != null) {
                    rateLimiter.checkUser(userId);
                }
            }
        } catch (BusinessException ex) {
            reject(request, response, ex, retryAfterSeconds);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean matchesAny(List<PathPattern> patterns, PathContainer path) {
        for (PathPattern pattern : patterns) {
            if (pattern.matches(path)) {
                return true;
            }
        }
        return false;
    }

    /** 去掉 context path 后用于匹配（本项目部署在根路径，但写成通用形式以免将来加前缀时静默失效）。 */
    private String resolvePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    /**
     * 就地写统一返回体。
     *
     * <p>为什么不抛给 {@code @ControllerAdvice}：这个过滤器挂在安全链上，
     * 位置在 DispatcherServlet **之前**，异常根本没有机会进入 Spring MVC 的异常解析器
     * ——抛出去的结果是容器的错误页，而不是 {@code {code,message,data}}。
     * 手法与 {@code RestAuthenticationEntryPoint} 一致（同一个 JsonMapper，序列化策略相同）。
     */
    private void reject(HttpServletRequest request,
                        HttpServletResponse response,
                        BusinessException ex,
                        int retryAfterSeconds) throws IOException {
        ErrorCode errorCode = ErrorCode.fromCode(ex.getCode());
        HttpStatus status = ErrorCodeHttpStatusMapper.toHttpStatus(errorCode);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Retry-After 只对"稍后重试就行"的限流有意义。
        // 配额用尽是次日 00:00 才重置，回一个 60 秒会让客户端白试一整天。
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        }
        response.getWriter().write(jsonMapper.writeValueAsString(Result.fail(errorCode, ex.getMessage())));

        log.warn("请求被拦截 code={}, path={}, message={}",
                errorCode.getCode(), resolvePath(request), ex.getMessage());
    }
}
