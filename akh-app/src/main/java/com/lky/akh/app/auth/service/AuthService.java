package com.lky.akh.app.auth.service;

import com.lky.akh.app.auth.model.dto.AuthTokenResponse;
import com.lky.akh.app.auth.model.dto.LoginRequest;
import com.lky.akh.app.auth.model.dto.RegisterRequest;
import com.lky.akh.app.auth.model.dto.UserInfoResponse;

/**
 * 认证服务。
 */
public interface AuthService {

    /** 注册新用户，默认授予 USER 角色。 */
    UserInfoResponse register(RegisterRequest request);

    /** 登录：校验凭证并签发访问令牌。 */
    AuthTokenResponse login(LoginRequest request);

    /** 查询当前用户信息（供 /api/users/me 使用）。 */
    UserInfoResponse currentUser(Long userId);
}
