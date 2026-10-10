import { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Search, X, LogOut, Sparkles, RotateCcw } from 'lucide-react'
import { SETTINGS_GROUPS, type SectionId } from '@/pages/settings/schema'
import { TOUR_START_EVENT } from './OnboardingTour'
import { sessionStore } from '@/lib/sessions'
import { toast } from '@/lib/toast'
import '@/styles/settings-dialog.css'

interface Props {
  open: boolean
  onClose: () => void
  onLogout: () => void
}

/**
 * 「个人设置」弹层（对齐 ChatGPT 的设置面板：左侧导航 + 右侧内容）。
 *
 * 为什么用 Portal：对话页有 `overflow: hidden` 的祖先（`.chat-fixed` / `.shell-main`），
 * 侧栏也在 `overflow: hidden` 容器里；就地渲染的浮层会被裁掉。
 * 挂到 body + `position: fixed` 后与任何祖先的 overflow / transform 无关。
 */
export default function SettingsDialog({ open, onClose, onLogout }: Props) {
  const [active, setActive] = useState<SectionId>('profile')
  const [query, setQuery] = useState('')
  const panelRef = useRef<HTMLDivElement>(null)

  /* Esc 关闭 + 打开时锁定背景滚动 */
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = prev
    }
  }, [open, onClose])

  /* 打开时聚焦面板，方便键盘用户 */
  useEffect(() => {
    if (open) panelRef.current?.focus()
  }, [open])

  const groups = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return SETTINGS_GROUPS
    return SETTINGS_GROUPS
      .map((g) => ({ ...g, items: g.items.filter((it) => it.label.toLowerCase().includes(q) || it.alias.includes(q)) }))
      .filter((g) => g.items.length > 0)
  }, [query])

  const flat = groups.flatMap((g) => g.items)
  const current = flat.find((it) => it.id === active) ?? flat[0] ?? SETTINGS_GROUPS[0].items[0]

  if (!open) return null

  return createPortal(
    <div
      className="settings-overlay"
      onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}
    >
      <div
        className="settings-dialog"
        role="dialog"
        aria-modal="true"
        aria-label="个人设置"
        ref={panelRef}
        tabIndex={-1}
      >
        <aside className="settings-dialog__nav">
          <div className="settings-dialog__search">
            <Search size={14} />
            <input
              className="input"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="搜索设置"
              aria-label="搜索设置"
            />
            {query && (
              <button className="settings-dialog__clear" type="button" aria-label="清除搜索" onClick={() => setQuery('')}>
                <X size={13} />
              </button>
            )}
          </div>

          <nav className="settings-dialog__list" aria-label="设置分类">
            {groups.map((g) => (
              <div className="settings-dialog__group" key={g.title}>
                <p className="settings-dialog__group-title">{g.title}</p>
                {g.items.map((it) => (
                  <button
                    key={it.id}
                    type="button"
                    className="settings-dialog__item"
                    aria-current={it.id === current?.id ? 'true' : undefined}
                    onClick={() => setActive(it.id)}
                  >
                    <it.icon size={15} aria-hidden="true" />
                    <span>{it.label}</span>
                  </button>
                ))}
              </div>
            ))}
            {flat.length === 0 && <p className="settings-dialog__empty">没有匹配「{query}」的设置</p>}
          </nav>

          <div className="settings-dialog__foot">
            <button
              className="settings-dialog__reset"
              type="button"
              onClick={() => {
                /* 会话已持久化，演示/验收后需要能一键回到种子数据 */
                sessionStore.resetToSeed()
                onClose()
                toast.success('已重置演示数据', '会话与归档恢复到初始状态')
              }}
            >
              <RotateCcw size={14} /> 重置演示数据
            </button>
            <button
              className="settings-dialog__replay"
              type="button"
              onClick={() => { onClose(); window.dispatchEvent(new Event(TOUR_START_EVENT)) }}
            >
              <Sparkles size={14} /> 重新查看新手引导
            </button>
            <button className="settings-dialog__logout" type="button" onClick={onLogout}>
              <LogOut size={14} /> 退出登录
            </button>
          </div>
        </aside>

        <section className="settings-dialog__body">
          <button className="settings-dialog__close" type="button" aria-label="关闭设置" onClick={onClose}>
            <X size={16} />
          </button>
          <div className="settings-dialog__content">{current?.render()}</div>
        </section>
      </div>
    </div>,
    document.body
  )
}
