/**
 * 设置分区元数据（共享）
 *
 * 抽出来的原因：设置既要能整页看（`/app/account`），
 * 也要能从侧栏底部以**弹窗**形式打开（对齐 ChatGPT 的「个人设置」弹层）。
 * 两处必须复用同一份定义，否则内容会分叉（改了一处忘了另一处）。
 */
import type { ReactNode } from 'react'
import {
  BellRing, Cpu, Download, Gauge, KeyRound, Laptop, MailCheck, Palette, ShieldAlert,
  SlidersHorizontal, UserRound,
} from 'lucide-react'
import {
  AppearanceSection, DangerSection, EmailSection, ExportSection, ModelKeySection, NotifySection,
  PrefsSection, ProfileSection, SecuritySection, SessionsSection, UsageSection,
} from './sections'
import { appCopy } from '@/lib/appCopy'

export type SectionId =
  | 'profile' | 'model' | 'prefs' | 'appearance'
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

export interface SettingsGroup {
  title: string
  items: SettingsItem[]
}

/**
 * 分组 / 分区标题随语言生成。
 *
 * 为什么做成函数而不是模块级常量：label 是要显示给用户的文案，
 * 写成常量就等于把语言焊死在模块加载那一刻，切换语言后设置页仍是旧文案。
 * alias（搜索别名）保留中英关键词，两种语言下都能搜到。
 */
export function buildSettingsGroups(t: ReturnType<typeof appCopy>['settings']): SettingsGroup[] {
  return [
  {
    title: t.groupPersonal,
    items: [
      { id: 'profile', label: t.itemProfile, icon: UserRound, alias: 'profile username nickname email 用户名 昵称 邮箱 头像 资料', render: () => <ProfileSection /> },
      { id: 'model', label: t.itemModel, icon: Cpu, alias: 'model key api 大模型 key api key 自定义 模型 网关 base url deepseek', render: () => <ModelKeySection /> },
      { id: 'prefs', label: t.itemPrefs, icon: SlidersHorizontal, alias: 'preferences language timezone landing 语言 时区 默认 落地页', render: () => <PrefsSection /> },
      { id: 'appearance', label: t.itemAppearance, icon: Palette, alias: 'appearance theme palette dark light 主题 配色 暗色 浅色 亮色 跟随系统 颜色', render: () => <AppearanceSection /> },
    ],
  },
  {
    title: t.groupAccess,
    items: [
      { id: 'security', label: t.itemSecurity, icon: KeyRound, alias: 'password 2fa security 修改密码 两步验证 二次验证 登录', render: () => <SecuritySection /> },
      { id: 'email', label: t.itemEmail, icon: MailCheck, alias: 'email verify code 验证码 邮箱 找回密码', render: () => <EmailSection /> },
      { id: 'sessions', label: t.itemSessions, icon: Laptop, alias: 'sessions devices logout 登录设备 退出 会话 安全', render: () => <SessionsSection /> },
    ],
  },
  {
    title: t.groupNotify,
    items: [
      { id: 'notify', label: t.itemNotify, icon: BellRing, alias: 'notifications email digest 提醒 邮件 每周摘要 通知', render: () => <NotifySection /> },
    ],
  },
  {
    title: t.groupData,
    items: [
      { id: 'usage', label: t.itemUsage, icon: Gauge, alias: 'usage quota storage calls 存储 调用 配额 额度 统计', render: () => <UsageSection /> },
      { id: 'export', label: t.itemExport, icon: Download, alias: 'export backup json markdown csv 导出 备份 迁移', render: () => <ExportSection /> },
    ],
  },
  {
    title: t.groupDanger,
    items: [
      { id: 'danger', label: t.itemDanger, icon: ShieldAlert, alias: 'delete account danger 删除 注销 清空 危险', render: () => <DangerSection /> },
    ],
  },
  ]
}

/** 分区 → 所属分组标题（弹层顶栏显示"个人 / 公开资料"这样的定位信息） */
export function groupOf(groups: SettingsGroup[], id: SectionId): string | undefined {
  return groups.find((g) => g.items.some((it) => it.id === id))?.title
}
