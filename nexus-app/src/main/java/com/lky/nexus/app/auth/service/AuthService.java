package com.lky.nexus.app.auth.service;

import com.lky.nexus.app.auth.model.dto.AuthTokenResponse;
import com.lky.nexus.app.auth.model.dto.ChangePasswordRequest;
import com.lky.nexus.app.auth.model.dto.LoginRequest;
import com.lky.nexus.app.auth.model.dto.RegisterRequest;
import com.lky.nexus.app.auth.model.dto.UpdateProfileRequest;
import com.lky.nexus.app.auth.model.dto.UserInfoResponse;

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

    /**
     * 修改当前用户密码（5.6）。
     *
     * <p>成功后该用户**此前签发的全部令牌立即失效**（含发起这次请求的那一支），
     * 语义就是"改密码 = 所有设备下线"，调用方需要引导用户重新登录。
     */
    void changePassword(Long userId, ChangePasswordRequest request);

    /** 修改当前用户资料（5.6）：昵称 / 邮箱，只认 userId，不接受请求体里的身份字段。 */
    UserInfoResponse updateProfile(Long userId, UpdateProfileRequest request);
}
