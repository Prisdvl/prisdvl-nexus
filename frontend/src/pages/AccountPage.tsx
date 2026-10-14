import { useMemo, useState, type ReactNode } from 'react'
import {
  BellRing, Cpu, Download, Gauge, KeyRound, Laptop, MailCheck, Palette,
  Search, ShieldAlert, SlidersHorizontal, UserRound,
} from 'lucide-react'
import type { ThemeMode } from '@/lib/types'
import {
  AppearanceSection, DangerSection, EmailSection, ExportSection, ModelKeySection, NotifySection,
  PrefsSection, ProfileSection, SecuritySection, SessionsSection, UsageSection,
} from '@/pages/settings/sections'

/**
 * 设置页：GitHub 式的多级界面。
 *
 * 单路由 + 内部状态（按约定不使用子路由）：左侧按组分节，右侧只渲染当前分区。
 * 手机端左侧导航降级为**横向可滑的分类 tab**。
 * 顶部有设置搜索：输入关键词过滤分区（含别名，例如"主题"能搜到"外观"）。
 */

type SectionId =
  | 'profile' | 'prefs' | 'appearance' | 'model'
  | 'security' | 'email' | 'sessions'
  | 'notify'
  | 'usage' | 'export'
  | 'danger'

interface Item {
  id: SectionId
  label: string
  icon: typeof UserRound
  /** 搜索别名：中文关键词能搜到对应分区 */
  alias: string
  render: () => ReactNode
}

const GROUPS: { title: string; items: Item[] }[] = [
  {
    title: '个人',
    items: [
      { id: 'profile', label: '公开资料', icon: UserRound, alias: '用户名 昵称 邮箱 头像 资料', render: () => <ProfileSection /> },
      { id: 'model', label: '模型 Key', icon: Cpu, alias: '大模型 key api key 自定义 模型 网关 base url deepseek', render: () => <ModelKeySection /> },
      { id: 'prefs', label: '账号偏好', icon: SlidersHorizontal, alias: '语言 时区 默认 落地页', render: () => <PrefsSection /> },
      { id: 'appearance', label: '外观', icon: Palette, alias: '主题 配色 暗色 浅色 亮色 跟随系统 颜色', render: () => <AppearanceSection /> },
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

export default function AccountPage({ theme }: { theme: ThemeMode }) {
  const [active, setActive] = useState<SectionId>('profile')
  const [query, setQuery] = useState('')

  const q = query.trim().toLowerCase()
  const groups = useMemo(() => {
    if (!q) return GROUPS
    return GROUPS
      .map((g) => ({ ...g, items: g.items.filter((i) => (i.label + ' ' + i.alias).toLowerCase().includes(q)) }))
      .filter((g) => g.items.length > 0)
  }, [q])

  const all = GROUPS.flatMap((g) => g.items)
  const current = all.find((i) => i.id === active) ?? all[0]
  const visibleCount = groups.reduce((n, g) => n + g.items.length, 0)

  return (
    <div className="page settings-page">
      <div className="page-head">
        <div>
          <p className="eyebrow" style={{ margin: 0 }}>账号</p>
          <h1 className="page-title">设置</h1>
          <p className="page-sub">管理公开资料、安全、通知与用量配额</p>
        </div>
      </div>

      <div className="settings-layout">
        <aside className="settings-nav" aria-label="设置分区">
          <div className="settings-search">
            <Search size={15} aria-hidden="true" />
            <input
              className="input"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="搜索设置项"
              aria-label="搜索设置项"
            />
          </div>

          {groups.map((g) => (
            <div className="settings-group-wrap" key={g.title}>
              <p className="settings-group">{g.title}</p>
              {g.items.map((i) => (
                <button
                  className="settings-item"
                  type="button"
                  key={i.id}
                  aria-current={i.id === active ? 'true' : undefined}
                  onClick={() => { setActive(i.id); setQuery('') }}
                >
                  <i.icon size={15} aria-hidden="true" />
                  <span>{i.label}</span>
                </button>
              ))}
            </div>
          ))}

          {visibleCount === 0 && (
            <p className="settings-empty">没有匹配「{query}」的设置项</p>
          )}
        </aside>

        <div className="settings-content">
          <nav className="settings-crumbs" aria-label="当前位置">
            <span>{GROUPS.find((g) => g.items.some((i) => i.id === current.id))?.title}</span>
            <span aria-hidden="true">/</span>
            <strong>{current.label}</strong>
          </nav>
          {/* 分区切换：key 变化触发淡入，与全站切换过渡一致 */}
          <div className="settings-body route-fade" key={current.id}>
            {current.render()}
          </div>
          <p className="foot-note">资料、密码与用量连接后端；偏好与通知开关保存在当前浏览器。尚未支持的服务端操作会明确标注。</p>
        </div>
      </div>
    </div>
  )
}