/**
 * 认证与用户相关类型 —— **逐字段抄自后端 record，不做任何"合理推测"**。
 *
 * 权威来源：
 * ```text
 * nexus-app/.../auth/model/dto/LoginRequest.java
 *   record LoginRequest(@NotBlank String username, @NotBlank String password)
 *
 * nexus-app/.../auth/model/dto/AuthTokenResponse.java
 *   record AuthTokenResponse(String accessToken, String tokenType, long expiresIn, UserInfoResponse user)
 *
 * nexus-app/.../auth/model/dto/UserInfoResponse.java
 *   record UserInfoResponse(Long id, String username, String nickname, String email, List<String> roles)
 * ```
 *
 * ## 一个曾经踩过的坑，这里显式挡住
 *
 * Vue 侧出现过「欢迎回来 undefined」（UA-002）。根因是**登录响应的用户信息嵌在
 * `user` 里，没有顶层 `username`**，而前端按 `result.username` 取。
 *
 * 修法不是 `user?.name ?? '用户'`（那只是把类型错误藏起来），而是让类型如实反映
 * 后端形状——**`AuthTokenResponse` 顶层没有 username，username 在 `user` 里**。
 * 编译器于是会在写错的地方报错。
 */

/** `POST /api/auth/login` 的请求体。 */
export interface LoginRequest {
  username: string
  password: string
}

/**
 * `POST /api/auth/register` 的请求体。
 *
 * 注意后端 `RegisterRequest` 还有 `nickname`（可选）；确认页的完整规则属 R5。
 */
export interface RegisterRequest {
  username: string
  password: string
  nickname?: string
}

/**
 * 用户信息。
 *
 * `id` 是 **string**：后端是 `Long`，Jackson 序列化成 JSON 字符串
 * （精度问题见 `knowledge.ts` 文件头的说明）。
 * `nickname` / `email` 在后端是包装类型，可能为 null。
 */
export interface UserInfo {
  id: string | null
  username: string
  nickname: string | null
  email: string | null
  roles: string[]
}

/**
 * `POST /api/auth/login` 的响应。
 *
 * **用户信息嵌在 `user` 里，顶层没有 `username`** —— 这一点必须如实反映，
 * 否则前端会重蹈「欢迎回来 undefined」。
 */
export interface AuthTokenResponse {
  accessToken: string
  tokenType: string
  /** 秒。由后端算好；前端**不据此自动过期**（多标签页场景下容易互相踢）。 */
  expiresIn: number
  user: UserInfo
}

/**
 * `POST /api/auth/register` 的响应。
 *
 * **注册只返回用户信息，不返回 token** —— 所以注册后必须再调一次 `login`。
 * 这是后端 Controller 的实际行为（`register` 返回 `Result<UserInfoResponse>`）。
 */
export type RegisterResponse = UserInfo

/** `GET /api/users/me` 的响应。 */
export type ProfileResponse = UserInfo
