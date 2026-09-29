package com.lky.nexus.app.auth.security;

import com.lky.nexus.common.api.ErrorCode;
import com.lky.nexus.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 已认证但权限不足：例如普通用户访问 {@code /api/admin/**}。
 *
 * <p>与 {@link RestAuthenticationEntryPoint} 的区别是本项目的 RBAC 分界线：
 * <ul>
 *   <li>401（entry point）= "你是谁我不知道" → 业务码 2001；</li>
 *   <li>403（access denied）= "我知道你是谁，但你不能干这个" → 业务码 2003。</li>
 * </ul>
 * 前端据此决定"跳登录页"还是"提示无权限"，两者的交互完全不同。
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    public RestAccessDeniedHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(jsonMapper.writeValueAsString(Result.fail(ErrorCode.FORBIDDEN)));
    }
}
