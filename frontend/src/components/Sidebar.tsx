import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import {
  Bot, CalendarClock, ChevronRight, Folder, FolderOpen, LayoutDashboard, Library,
  MessageSquare, PanelLeftClose, PanelLeftOpen, Pin, Plus, Puzzle, Search, Settings, Trash2, Users, Workflow,
} from 'lucide-react'
import MarqueeTitle from '@/bits/MarqueeTitle/MarqueeTitle'
import '@/bits/MarqueeTitle/MarqueeTitle.css'
import RubberSegment from '@/bits/RubberSegment/RubberSegment'
import { HugeiconsIcon } from '@hugeicons/react'
import { Archive02Icon, Delete02Icon } from '@hugeicons/core-free-icons'
import BrandMark from './BrandMark'
import Modal from './Modal'
import SettingsDialog from './SettingsDialog'
import Loc from './Loc'
import { useAuth } from '@/lib/auth'
import { sessionStore, useSessions } from '@/lib/sessions'
import { projectStore, useProjects } from '@/lib/projects'
import { toast } from '@/lib/toast'
import { api, errMessage, USE_MOCK } from '@/lib/api'
import { isTempId } from '@/lib/conversations'
import { agentStore } from '@/lib/agents'
import { appCopy } from '@/lib/appCopy'
import { useLocale } from '@/lib/locale'
import { PAGE_PATH, type PageKey } from '@/lib/types'
import type { SessionItem } from '@/data'
import '@/styles/sidebar.css'

const NAV: { key: PageKey; labelKey: 'workbench' | 'kb' | 'chat' | 'agents' | 'plugins' | 'community' | 'automations' | 'workflows'; icon: typeof Library }[] = [
  { key: 'workbench', labelKey: 'workbench', icon: LayoutDashboard },
  { key: 'kb', labelKey: 'kb', icon: Library },
  { key: 'chat', labelKey: 'chat', icon: MessageSquare },
  { key: 'agents', labelKey: 'agents', icon: Bot },
  { key: 'automations', labelKey: 'automations', icon: CalendarClock },
  { key: 'workflows', labelKey: 'workflows', icon: Workflow },
  { key: 'plugins', labelKey: 'plugins', icon: Puzzle },
  { key: 'community', labelKey: 'community', icon: Users },
  /* 「账号」不再作为导航项：它是冗余入口（设置已收在底部「个人设置」弹层里） */
]

interface SidebarProps {
  page: PageKey
  /** 手机端抽屉开关（桌面端忽略：CSS 只在 <768px 生效） */
  open?: boolean
  onClose?: () => void
}

/**
 * 全局侧栏（参照 ChatGPT 的信息架构）：
 *   顶部：品牌 + 搜索 + 折叠
 *   快捷：新对话
 *   导航：工作台 / 知识库 / 对话 / Agent / 账号
 *   **置顶**（在上）→ **最近**（在下），各自可收起
 *   底部：个人设置（账号信息不常驻侧栏，点开是设置弹层）
 */
export default function Sidebar({ page, open = false, onClose }: SidebarProps) {
  const navigate = useNavigate()
  const { logout } = useAuth()
  const { locale } = useLocale()
  const t = appCopy(locale)
  const { sessions, activeId, archived, setId, patch, archive: archiveSession, restore: restoreSession, remove: removeSessionStore } = useSessions()
  const { projects, activeProjectId } = useProjects()

  const [settingsOpen, setSettingsOpen] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem('nx-proto-sb-collapsed') === '1')
  /* 768–1023px 是强制图标栏（CSS 把宽度钉在 --sb-w-collapsed），
     必须跟折叠态走同一套内容，否则会话标题/「个人设置」文字会从 68px 里漏出来。
     手机抽屉（<768）始终展开内容，不受桌面折叠状态影响。 */
  const [tabletRail, setTabletRail] = useState(() =>
    typeof window !== 'undefined'
    && window.matchMedia('(min-width: 768px) and (max-width: 1023px)').matches,
  )
  const [phone, setPhone] = useState(() =>
    typeof window !== 'undefined' && window.matchMedia('(max-width: 767px)').matches,
  )
  const iconRail = !phone && (collapsed || tabletRail)
  const [showArchived, setShowArchived] = useState(false)
  /* 重命名走居中弹窗（不再在侧栏行内改名） */
  const [renaming, setRenaming] = useState<SessionItem | null>(null)
  const [draft, setDraft] = useState('')
  const [renamingBusy, setRenamingBusy] = useState(false)
  /* 两个分区都可收起（置顶在上、最近在下），状态持久化 */
  const [projectsOpen, setProjectsOpen] = useState(true)
  const [creating, setCreating] = useState(false)
  const [newName, setNewName] = useState('')
  const [pinnedOpen, setPinnedOpen] = useState(() => localStorage.getItem('nx-proto-sb-pinned') !== '0')
  const [recentOpen, setRecentOpen] = useState(() => localStorage.getItem('nx-proto-sb-recent') !== '0')
  /* 删除走应用内二次确认弹窗（不再用 window.confirm） */
  const [confirmDelete, setConfirmDelete] = useState<SessionItem | null>(null)
  const [deleting, setDeleting] = useState(false)
  const searchRef = useRef<HTMLInputElement>(null)
  /* 置顶请求去重：同一行请求未返回前忽略重复点击，避免来回翻转 */
  const pinPending = useRef<Set<string>>(new Set())

  /* 侧栏可调宽：范围 200–420px，默认 236，持久化到 localStorage；
     拖拽越过 96px 阈值自动折叠成图标栏，从图标栏往右拖再展开。
     96–200 是软过渡带：宽度跟手，--sb-soft 驱动品牌/文字与点击折叠同一套缓动。 */
  const SB_MIN = 200
  const SB_MAX = 420
  const SB_DEFAULT = 236
  const SB_COLLAPSE_AT = 96
  const [sbWidth, setSbWidth] = useState(() => {
    const saved = Number(localStorage.getItem('nx-proto-sb-width'))
    return Number.isFinite(saved) && saved >= SB_MIN && saved <= SB_MAX ? saved : SB_DEFAULT
  })
  const [resizing, setResizing] = useState(false)
  const dragging = useRef(false)
  const collapsedRef = useRef(collapsed)
  const sbWidthRef = useRef(sbWidth)
  const lastExpandedWidth = useRef(sbWidth)

  const setSbSoft = (soft: number) => {
    const v = Math.min(1, Math.max(0, soft))
    document.documentElement.style.setProperty('--sb-soft', String(v))
  }
  const softFromWidth = (w: number) => {
    if (w <= SB_COLLAPSE_AT) return 0
    if (w >= SB_MIN) return 1
    return (w - SB_COLLAPSE_AT) / (SB_MIN - SB_COLLAPSE_AT)
  }

  /* 把宽度写到 :root 的 --sb-w，.sidebar 与 .shell-main 同源读取；
     软过渡带（< SB_MIN）的临时宽度不写入 localStorage */
  useEffect(() => {
    sbWidthRef.current = sbWidth
    document.documentElement.style.setProperty('--sb-w', `${sbWidth}px`)
    if (sbWidth >= SB_MIN && sbWidth <= SB_MAX) {
      lastExpandedWidth.current = sbWidth
      localStorage.setItem('nx-proto-sb-width', String(sbWidth))
    }
  }, [sbWidth])

  useEffect(() => { collapsedRef.current = collapsed }, [collapsed])

  /* 非拖拽时 --sb-soft 跟随折叠态，交给 CSS transition 做与点击折叠相同的软动画 */
  useEffect(() => {
    if (resizing) return
    setSbSoft(iconRail ? 0 : 1)
  }, [iconRail, resizing])

  /* 拖拽右缘调宽：pointer 全局监听，拖出手柄也不丢；
     96–200 软过渡带跟手插值，松手再吸附到折叠或 SB_MIN（带软动画） */
  useEffect(() => {
    const onMove = (e: PointerEvent) => {
      if (!dragging.current) return
      const raw = e.clientX
      if (raw <= SB_COLLAPSE_AT) {
        setCollapsed(true)
        setSbSoft(0)
        return
      }
      setCollapsed(false)
      const w = Math.min(SB_MAX, Math.max(SB_COLLAPSE_AT + 1, raw))
      setSbWidth(w)
      setSbSoft(softFromWidth(w))
    }
    const onUp = () => {
      if (!dragging.current) return
      dragging.current = false
      if (collapsedRef.current) {
        setSbWidth(lastExpandedWidth.current)
        setSbSoft(0)
        setResizing(false)
        return
      }
      const w = sbWidthRef.current
      if (w >= SB_MIN) {
        setSbSoft(1)
      } else if (w < (SB_MIN + SB_COLLAPSE_AT) / 2) {
        setCollapsed(true)
        setSbWidth(lastExpandedWidth.current)
        setSbSoft(0)
      } else {
        setSbWidth(SB_MIN)
        setSbSoft(1)
      }
      /* 先落定折叠/宽度，再关 data-resizing，松手后的软过渡才能播起来 */
      setResizing(false)
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
    }
  }, [])

  useEffect(() => { localStorage.setItem('nx-proto-sb-collapsed', collapsed ? '1' : '0') }, [collapsed])
  useEffect(() => {
    const tablet = window.matchMedia('(min-width: 768px) and (max-width: 1023px)')
    const mobile = window.matchMedia('(max-width: 767px)')
    const sync = () => {
      setTabletRail(tablet.matches)
      setPhone(mobile.matches)
    }
    sync()
    tablet.addEventListener('change', sync)
    mobile.addEventListener('change', sync)
    return () => {
      tablet.removeEventListener('change', sync)
      mobile.removeEventListener('change', sync)
    }
  }, [])
  useEffect(() => { localStorage.setItem('nx-proto-sb-pinned', pinnedOpen ? '1' : '0') }, [pinnedOpen])
  useEffect(() => { localStorage.setItem('nx-proto-sb-recent', recentOpen ? '1' : '0') }, [recentOpen])
  useEffect(() => { if (searchOpen) searchRef.current?.focus() }, [searchOpen])

  const go = (key: PageKey) => {
    navigate(PAGE_PATH[key])
    onClose?.()
  }

  const visibleAll = useMemo(() => {
    const q = query.trim().toLowerCase()
    let list = q ? sessions.filter((s) => s.title.toLowerCase().includes(q)) : sessions
    list = list.filter((s) => !archived.includes(s.id))
    /* 决策待确认项：projectId 栏目先隐藏。
       后端 chat_conversation 没有 project 相关字段（V9__workspace 是另一套模型），
       与其在前端本地维护一个"刷新就丢"的假归属，不如先不显示。
       activeProjectId 仍留在 store 里，后端支持了一行就能恢复。 */
    return list
  }, [sessions, query, archived])

  const pinned = visibleAll.filter((s) => s.pinned)
  const recent = visibleAll.filter((s) => !s.pinned)

  const openRename = (s: SessionItem) => {
    setRenaming(s)
    setDraft(s.title)
  }

  const closeRename = () => {
    if (renamingBusy) return
    setRenaming(null)
    setDraft('')
  }

  const commitRename = async () => {
    const target = renaming
    if (!target || renamingBusy) return
    const name = draft.trim()
    if (!name || name === target.title) {
      closeRename()
      return
    }
    setRenamingBusy(true)
    try {
      /* 本地会话（tmp）/离线演示不调后端；真实会话先持久化再改本地，失败不翻转 */
      if (USE_MOCK || isTempId(target.id)) {
        patch(target.id, (x) => ({ ...x, title: name }))
        setRenaming(null)
        setDraft('')
        return
      }
      try {
        await api.conversations.update(target.id, { title: name })
        patch(target.id, (x) => ({ ...x, title: name }))
        setRenaming(null)
        setDraft('')
      } catch (e) {
        toast.error(t.nav.renameFail, errMessage(e))
      }
    } finally {
      setRenamingBusy(false)
    }
  }

  /**
   * 置顶/取消置顶：真实会话调 PATCH 后端成功后再改本地，
   * 否则刷新页面置顶状态会丢（后端列表本身按 pinned 排序）。
   */
  const togglePin = async (s: SessionItem) => {
    if (pinPending.current.has(s.id)) return
    if (USE_MOCK || isTempId(s.id)) {
      patch(s.id, (x) => ({ ...x, pinned: !x.pinned }))
      toast.success(s.pinned ? t.nav.unpinOk : t.nav.pinOk, s.title)
      return
    }
    const next = !s.pinned
    pinPending.current.add(s.id)
    try {
      await api.conversations.update(s.id, { pinned: next })
      patch(s.id, (x) => ({ ...x, pinned: next }))
      toast.success(next ? t.nav.pinOk : t.nav.unpinOk, s.title)
    } catch (e) {
      /* 失败不本地翻转，保持与后端一致 */
      toast.error(next ? t.nav.pinFail : t.nav.unpinFail, errMessage(e))
    } finally {
      pinPending.current.delete(s.id)
    }
  }

  /**
   * 删除会话（决策 3：**不可恢复**，与归档严格分开）。
   *
   * ⚠️ 后端 `DELETE /api/chat/conversations/{id}` 是**级联物理删除**
   * （`ConversationServiceImpl`，已读源码确认）：
   *   - 会话置`deleted=1`
   *   - **消息物理删除**（不可恢复）
   *   - **会话记忆清空**（`chatMemory.clear()`）
   * 后端注释说明了为什么不能省：不清记忆会导致同 id 复用时，
   * 新会话"凭空记得旧会话说过的话"。
   *
   * 三个直接后果：
   *   1. **不能提供"撤销"** —— 本地恢复回来，后端消息已被物理删光，是假的撤销。
   *   2. 必须**应用内弹窗二次确认**（不用 window.confirm），文案说清不可恢复，
   *      并指向"归档"这个可撤销选项。
   *   3. **先调后端、成功后再改本地** —— 反过来做失败后两边状态不一致。
   *   4. 临时 id（`tmp_`，未落库）不需要也不能调后端。
   */
  const performDelete = async () => {
    const target = confirmDelete
    if (!target || deleting) return
    setDeleting(true)
    try {
      if (!USE_MOCK && !isTempId(target.id)) {
        try {
          await api.conversations.remove(target.id)
        } catch (e) {
          toast.error(t.nav.deleteFail, errMessage(e))
          return // 本地保持不动，与后端保持一致；弹窗保留以便重试
        }
      }
      removeSessionStore(target.id)
      setConfirmDelete(null)
      toast.success(t.nav.deleteOk)
    } finally {
      setDeleting(false)
    }
  }

  const openSession = (id: string) => {
    setId(id)
    navigate(PAGE_PATH.chat)
    onClose?.()
  }

  /** 会话行（置顶与最近共用同一套渲染） */
  const renderSession = (s: SessionItem) => (
    <div className="session-item sb-session" data-selected={s.id === activeId ? '' : undefined} key={s.id}>
      <button
        className="session-open"
        type="button"
        aria-current={s.id === activeId ? 'true' : undefined}
        title={t.nav.renameTitle}
        onClick={() => openSession(s.id)}
        onDoubleClick={(e) => { e.stopPropagation(); openRename(s) }}
      >
        <MarqueeTitle text={s.title} />
      </button>
      <div className="session-actions" role="group" aria-label={`${s.title} ·`}>
        <button
          className="session-action"
          type="button"
          title={s.pinned ? t.nav.unpin : t.nav.pin}
          aria-label={`${s.pinned ? t.nav.unpin : t.nav.pin}: ${s.title}`}
          onPointerDown={(e) => e.stopPropagation()}
          onClick={(e) => { e.stopPropagation(); void togglePin(s) }}
        ><Pin size={14} aria-hidden="true" /></button>
        <button
          className="session-action"
          type="button"
          title={t.nav.archive}
          aria-label={`${t.nav.archive}: ${s.title}`}
          onPointerDown={(e) => e.stopPropagation()}
          onClick={(e) => { e.stopPropagation(); archiveSession(s.id); toast.info(t.nav.archivedToast) }}
        ><HugeiconsIcon icon={Archive02Icon} size={14} aria-hidden="true" /></button>
        <button
          className="session-action session-action--danger"
          type="button"
          title={t.common.delete}
          aria-label={`${t.common.delete}: ${s.title}`}
          onPointerDown={(e) => e.stopPropagation()}
          onClick={(e) => { e.stopPropagation(); setConfirmDelete(s) }}
        ><Trash2 size={14} aria-hidden="true" /></button>
      </div>
    </div>
  )

  return (
    <>
      {open && <div className="sb-scrim" onClick={onClose} aria-hidden="true" />}
      <aside className="sidebar" data-open={open ? '' : undefined} data-collapsed={iconRail ? '' : undefined} data-resizing={resizing ? '' : undefined} aria-label={t.shell.sidebarAria}>
        {/* 右缘拖拽手柄：展开态调宽，折叠态往右拖即展开（手机抽屉态不显示） */}
        {!open && (
          <div
            className="sb-resizer"
            role="separator"
            aria-orientation="vertical"
            aria-label={t.shell.resizeAria}
            onPointerDown={(e) => {
              dragging.current = true
              setResizing(true)
              e.preventDefault()
            }}
          />
        )}
        {/* 顶部：品牌 + 搜索 + 折叠 */}
        <div className="sb-top">
          <span className="sb-brand-mini">
            {/* 尺寸由 CSS 在展开 52 ↔ 折叠 22 之间过渡；勿用 props 切换，否则会瞬间跳变 */}
            <BrandMark wordmark size={52} />
          </span>
          <span className="sb-top-actions">
            {!iconRail && (
              <button
                className="sb-icon-btn"
                type="button"
                aria-label={t.nav.searchSessions}
                aria-pressed={searchOpen}
                onClick={() => setSearchOpen((v) => !v)}
              >
                <Search size={16} />
              </button>
            )}
            {!tabletRail && (
              <button
                className="sb-icon-btn"
                type="button"
                aria-label={collapsed ? t.nav.expand : t.nav.collapse}
                onClick={() => setCollapsed((v) => !v)}
              >
                {collapsed ? <PanelLeftOpen size={16} /> : <PanelLeftClose size={16} />}
              </button>
            )}
          </span>
        </div>

        {searchOpen && !iconRail && (
          <div className="chat-search sb-search">
            <Search size={14} />
            <input
              ref={searchRef}
              className="input"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={t.nav.searchSessions}
              aria-label={t.nav.searchSessions}
            />
          </div>
        )}

        {/* 快捷：新对话 */}
        <button className="sb-row sb-row--quick" type="button" onClick={() => { sessionStore.requestCreate(); go('chat') }}>
          <Plus size={16} aria-hidden="true" />
          <span><Loc>{t.nav.newChat}</Loc></span>
        </button>

        {/* 导航 */}
        <nav className="sb-nav" aria-label={t.shell.navAria}>
          <RubberSegment
            direction="vertical"
            items={NAV.map((n) => ({
              value: n.key,
              label: (
                <span className="sb-item">
                  <n.icon size={16} aria-hidden="true" />
                  <span><Loc>{t.nav[n.labelKey]}</Loc></span>
                </span>
              ),
            }))}
            value={page}
            onChange={(v) => go(v as PageKey)}
            size="lg"
            radius={10}
            inset={2}
            equalSlots
            animateExternal
            trackColor="transparent"
            thumbColor="color-mix(in oklab, var(--primary) 13%, transparent)"
            textColor="var(--text-3)"
            activeTextColor="var(--text)"
            aria-label={t.shell.navAria}
          />
        </nav>

        {/*
          项目栏目：**暂时隐藏**（老大拍板的待确认项）。
          原因：后端 `chat_conversation` 表没有 project 字段（`V9__workspace` 是另一套模型），
          前端 `SessionItem.projectId` 已移除。与其在前端本地维护一个"刷新就丢"的假归属，
          不如先不显示这个栏目。
          整段代码保留没删 —— 后端支持项目归属后，把`false &&` 去掉即可，
          同时给 SessionItem 加回 projectId 并同步 store 的 Persisted 类型。
          期间 `projectStore` 仍照常读写，只是没有 UI 入口。
        */}
        {false && !iconRail && (
          <section className="sb-group sb-projects">
            <button
              className="sb-section sb-section--toggle"
              type="button"
              aria-expanded={projectsOpen}
              onClick={() => setProjectsOpen((v) => !v)}
            >
              <span><Loc>{t.nav.projectGroup}</Loc></span>
              <ChevronRight size={14} className={projectsOpen ? 'rot90' : ''} />
            </button>

            {projectsOpen && (
              <div className="sb-project-list">
                {creating ? (
                  <form
                    className="sb-project-form"
                    onSubmit={(e) => {
                      e.preventDefault()
                      const name = newName.trim()
                      if (!name) return
                      projectStore.add(name)
                      setNewName('')
                      setCreating(false)
                      toast.success(t.nav.projectCreated, name)
                    }}
                  >
                    <input
                      className="input"
                      autoFocus
                      value={newName}
                      placeholder={t.nav.projectName}
                      aria-label={t.nav.projectNameAria}
                      onChange={(e) => setNewName(e.target.value)}
                      onKeyDown={(e) => { if (e.key === 'Escape') { setCreating(false); setNewName('') } }}
                    />
                    <button className="btn btn-primary btn-sm" type="submit"><Loc>{t.nav.createProjectBtn}</Loc></button>
                  </form>
                ) : (
                  <button className="sb-project sb-project--new" type="button" onClick={() => setCreating(true)}>
                    <Plus size={14} aria-hidden="true" />
                    <span><Loc>{t.nav.createProject}</Loc></span>
                  </button>
                )}

                <button
                  className="sb-project"
                  type="button"
                  aria-current={activeProjectId === null ? 'true' : undefined}
                  onClick={() => projectStore.setActive(null)}
                >
                  <FolderOpen size={14} aria-hidden="true" />
                  <span><Loc>{t.nav.allSessions}</Loc></span>
                  <span className="sb-project-count">{sessions.length}</span>
                </button>

                {projects.map((p) => {
                  /* 项目栏目已隐藏（见上方注释），但这段 JSX 仍会被 TS 检查；
                     * 后端无 project 归属，计数暂时为 0。 */
                  const count = 0
                  return (
                    <div className="sb-project-row" key={p.id}>
                      <button
                        className="sb-project"
                        type="button"
                        aria-current={activeProjectId === p.id ? 'true' : undefined}
                        onClick={() => projectStore.setActive(p.id)}
                      >
                        <Folder size={14} aria-hidden="true" />
                        <span className="t">{p.name}</span>
                        <span className="sb-project-count">{count}</span>
                      </button>
                      <button
                        className="sb-project-del"
                        type="button"
                        aria-label={t.nav.deleteProjectAria(p.name)}
                        onClick={() => {
                          projectStore.remove(p.id)
                          toast.info(t.nav.projectDeleted, p.name)
                        }}
                      >
                        <Trash2 size={13} />
                      </button>
                    </div>
                  )
                })}
              </div>
            )}
          </section>
        )}

        {/* 会话：置顶在上，最近在下 */}
        {!iconRail && (
          <div className="sb-recent">
            {archived.length > 0 && (
              <div className="session-arch">
                <button className="session-arch-toggle" type="button" aria-expanded={showArchived} onClick={() => setShowArchived((v) => !v)}>
                  <HugeiconsIcon icon={Archive02Icon} size={14} /> <Loc>{t.nav.archived(archived.length)}</Loc>
                </button>
                {showArchived && (
                  <div className="session-arch-list">
                    {sessions.filter((s) => archived.includes(s.id)).map((s) => (
                      <div className="list-row session-arch-row" key={s.id}>
                        <span className="t">{s.title}</span>
                        <button className="btn btn-ghost btn-sm" type="button" onClick={() => { restoreSession(s.id); toast.success(t.nav.restored, s.title) }}>{t.nav.restore}</button>
                        <button className="btn btn-ghost btn-sm" type="button" aria-label={`${t.common.delete} ${s.title}`} onClick={() => setConfirmDelete(s)}>
                          <Trash2 size={13} />
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}

            {pinned.length > 0 && (
              <section className="sb-group">
                <button
                  className="sb-section sb-section--toggle"
                  type="button"
                  aria-expanded={pinnedOpen}
                  onClick={() => setPinnedOpen((v) => !v)}
                >
                  <span><Loc>{t.nav.pinned}</Loc></span>
                  {/* 计数直接由 store 现算 —— 收起时也能看出里面有几条，且不依赖任何缓存快照 */}
                  <span className="sb-section__count" aria-hidden="true">{pinned.length}</span>
                  <ChevronRight size={14} className={pinnedOpen ? 'rot90' : ''} />
                </button>
                {pinnedOpen && <div className="session-list sb-recent-list">{pinned.map(renderSession)}</div>}
              </section>
            )}

            <section className="sb-group">
              <button
                  className="sb-section sb-section--toggle"
                  type="button"
                  aria-expanded={recentOpen}
                  onClick={() => setRecentOpen((v) => !v)}
                >
                  <span><Loc>{t.nav.recent}</Loc></span>
                  {recent.length > 0 && <span className="sb-section__count" aria-hidden="true">{recent.length}</span>}
                  <ChevronRight size={14} className={recentOpen ? 'rot90' : ''} />
                </button>
              {recentOpen && <div className="session-list sb-recent-list">{recent.map(renderSession)}</div>}
            </section>

            {visibleAll.length === 0 && (
              <p className="sb-empty">{query ? t.nav.noMatch(query) : t.nav.noSessions}</p>
            )}
          </div>
        )}

        {/* 底部：个人设置绝对钉在侧栏左下角；折叠只藏文字，齿轮 left/bottom 不变。 */}
        <div className="sb-foot">
          <button
            className="sb-row sb-row--settings"
            type="button"
            aria-label={t.nav.settingsAria}
            title={t.nav.settingsAria}
            onClick={() => setSettingsOpen(true)}
          >
            <Settings size={16} aria-hidden="true" />
            <span><Loc>{t.nav.settings}</Loc></span>
          </button>
        </div>
      </aside>

      <Modal
        open={renaming !== null}
        title={t.nav.renameTitle}
        width={420}
        onClose={closeRename}
        shouldBlockClose={() => renamingBusy}
        footer={
          <>
            <span style={{ flex: 1 }} />
            <button className="btn btn-ghost" type="button" disabled={renamingBusy} onClick={closeRename}>{t.common.cancel}</button>
            <button
              className="btn btn-primary"
              type="button"
              disabled={renamingBusy || !draft.trim()}
              onClick={() => void commitRename()}
            >
              {renamingBusy ? t.nav.savingShort : t.common.save}
            </button>
          </>
        }
      >
        <p style={{ margin: '0 0 14px', fontSize: 13, color: 'var(--text-3)' }}>{t.nav.renameHint}</p>
        <input
          className="input"
          value={draft}
          aria-label={t.nav.renameAria}
          autoFocus
          disabled={renamingBusy}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              void commitRename()
            }
          }}
        />
      </Modal>

      <Modal
        open={confirmDelete !== null}
        title={t.nav.deleteTitle}
        width={440}
        onClose={() => { if (!deleting) setConfirmDelete(null) }}
        footer={
          <>
            {confirmDelete && !archived.includes(confirmDelete.id) && (
              <button
                className="btn btn-ghost"
                type="button"
                disabled={deleting}
                onClick={() => { archiveSession(confirmDelete.id); setConfirmDelete(null); toast.info(t.nav.archivedToast) }}
              >
                {t.nav.useArchive}
              </button>
            )}
            <span style={{ flex: 1 }} />
            <button className="btn btn-ghost" type="button" disabled={deleting} onClick={() => setConfirmDelete(null)}>{t.common.cancel}</button>
            <button className="btn btn-danger" type="button" disabled={deleting} onClick={() => void performDelete()}>
              {deleting ? t.nav.deleting : t.common.delete}
            </button>
          </>
        }
      >
        <p style={{ margin: 0 }}>
          {confirmDelete && t.nav.deleteBody(confirmDelete.title)}
        </p>
        <p className="hint" style={{ marginBottom: 0 }}>
          {t.nav.deleteBodyHint}
        </p>
      </Modal>

      <SettingsDialog
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        onLogout={() => {
          setSettingsOpen(false)
          onClose?.()
          logout()
          /*
           * Agent 列表是**按用户**的（后端 `GET /api/agents` 按 ownerId 过滤），
           * 登出必须清掉，否则下一个登录的人会看到上一个人的 Agent ——
           * 属于越权展示。localStorage 里的会话快照同理。
           */
          agentStore.clear()
          sessionStore.clearLocal()
          navigate('/login', { replace: true })
        }}
      />
    </>
  )
}
