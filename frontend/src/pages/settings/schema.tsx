/**
 * 设置分区元数据（共享）
 *
 * 抽出来的原因：设置既要能整页看（`/app/account`），
 * 也要能从侧栏底部以**弹窗**形式打开（对齐 ChatGPT 的「个人设置」弹层）。
 * 两处必须复用同一份定义，否则内容会分叉（改了一处忘了另一处）。
 */
import type { ReactNode } from 'react'
import {
  BellRing, Download, Gauge, KeyRound, Laptop, MailCheck, Palette, ShieldAlert,
  SlidersHorizontal, UserRound,
} from 'lucide-react'
import {
  AppearanceSection, DangerSection, EmailSection, ExportSection, NotifySection,
  PrefsSection, ProfileSection, SecuritySection, SessionsSection, UsageSection,
} from './sections'

export type SectionId =
  | 'profile' | 'prefs' | 'appearance'
  | 'security' | 'email' | 'sessions'
  | 'notify'
  | 'usage' | 'export'
  | 'danger'

export interface SettingsItem {
  id: SectionId
  label: string
  icon: typeof UserRound
  /** 搜索别名：中文关键词能搜到对应分区 */
  alias: string
  render: () => ReactNode
}

export const SETTINGS_GROUPS: { title: string; items: SettingsItem[] }[] = [
  {
    title: '个人',
    items: [
      { id: 'profile', label: '公开资料', icon: UserRound, alias: '用户名 昵称 邮箱 头像 资料', render: () => <ProfileSection /> },
      { id: 'prefs', label: '账号偏好', icon: SlidersHorizontal, alias: '语言 时区 默认 落地页', render: () => <PrefsSection /> },
      { id: 'appearance', label: '外观', icon: Palette, alias: '主题 配色 暗色 亮色 跟随系统 颜色', render: () => <AppearanceSection /> },
    ],
  },
  {
    title: '访问与安全',
    items: [
      { id: 'security', label: '密码与登录', icon: KeyRound, alias: '修改密码 两步验证 二次验证 登录', render: () => <SecuritySection /> },
      { id: 'email', label: '邮箱验证', icon: MailCheck, alias: '验证码 邮箱 找回密码', render: () => <EmailSection /> },
      { id: 'sessions', label: '会话与设备', icon: Laptop, alias: '登录设备 退出 会话 安全', render: () => <SessionsSection /> },
    ],
  },
  {
    title: '通知',
    items: [
      { id: 'notify', label: '通知偏好', icon: BellRing, alias: '提醒 邮件 每周摘要 通知', render: () => <NotifySection /> },
    ],
  },
  {
    title: '数据与用量',
    items: [
      { id: 'usage', label: '用量配额', icon: Gauge, alias: '存储 调用 配额 额度 统计', render: () => <UsageSection /> },
      { id: 'export', label: '数据导出', icon: Download, alias: '导出 备份 迁移 json markdown csv', render: () => <ExportSection /> },
    ],
  },
  {
    title: '危险区',
    items: [
      { id: 'danger', label: '注销账号', icon: ShieldAlert, alias: '删除 注销 清空 危险', render: () => <DangerSection /> },
    ],
  },
]
