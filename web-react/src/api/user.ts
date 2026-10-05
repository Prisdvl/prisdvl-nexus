import { api } from './client'
import type { ProfileResponse } from './types/auth'

/**
 * 当前用户：资料、改密码、当日额度。
 *
 * 这里所有接口都**没有 userId 参数**——身份由请求头里的令牌决定，
 * 服务端只认 JWT 的 `sub`（ADR-018）。前端也不该有"查别人资料"的能力。
 */

/**
 * 资料更新（**半更新**语义）：不传的字段 = 不修改，传空串 = 清空。
 *
 * 注意不能把"改成空"写成 `null`：`null` 在本接口里表示"这个字段不改"，
 * 一个值不能同时是"不改"和"清空"。要清空昵称就传 `''`
 * （语义由后端 UpdateProfileRequest 定义，两侧必须一致）。
 */
export interface UpdateProfilePayload {
  nickname?: string
  email?: string
}

/**
 * 当日额度快照（只读）。
 *
 * `requestUsed` 可能大于"实际发生的请求数"：被拒的请求也占一次计数，
 * 展示时用 `min(已用, 上限)` 收敛，否则界面上会出现"205/200"这种读起来像 bug 的数字。
 */
export interface Quota {
  requestUsed: number
  requestLimit: number
  tokenUsed: number
  tokenLimit: number
  /** 下次重置时刻（ISO 字符串，次日 00:00） */
  resetAt: string
}

export function updateProfile(payload: UpdateProfilePayload, signal?: AbortSignal) {
  return api.put<ProfileResponse>('/users/me', payload, {
    ...(signal ? { signal } : {})
  })
}

/**
 * 修改密码。
 *
 * **成功后当前令牌也会失效**（服务端吊销的是该用户**全部**已签发令牌，
 * 包括发起本次请求的这一支）。所以调用方必须主动登出并跳登录页，
 * 而不是等下一个请求撞上 401 —— 那时用户看到"登录已过期"，与"我刚改了密码"对不上。
 */
export function changePassword(oldPassword: string, newPassword: string, signal?: AbortSignal) {
  return api.put<void>('/users/me/password', { oldPassword, newPassword }, {
    ...(signal ? { signal } : {})
  })
}

export function fetchQuota(signal?: AbortSignal) {
  return api.get<Quota>('/users/me/quota', {
    ...(signal ? { signal } : {})
  })
}
