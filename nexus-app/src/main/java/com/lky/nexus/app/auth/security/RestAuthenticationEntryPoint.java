package com.lky.nexus.app.auth.security;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 未认证入口：Token 缺失、过期、签名非法、格式错误都会走到这里。
 *
 * <p><b>为什么必须自定义</b>：Spring Security 默认返回的是它自己的错误响应
 * （401 + 空 body 或 HTML），会破坏本项目 {@code {code,message,data}} 的统一契约，
 * 前端拦截器就没法按业务码区分"没登录"和"Token 过期"。
 *
 * <p>注入的是 Boot 4 自动配置的 {@code JsonMapper}（Jackson 3），
 * 因此序列化策略（例如雪花 ID 转字符串）与业务接口完全一致。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonMapper jsonMapper;

    public RestAuthenticationEntryPoint(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(jsonMapper.writeValueAsString(Result.fail(ErrorCode.UNAUTHENTICATED)));
    }
}
