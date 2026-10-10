import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import {
  BookOpen, Bot, ChevronRight, LayoutDashboard, MessagesSquare, PanelLeftClose,
  Folder, FolderOpen, PanelLeftOpen, Pin, Plus, Search, Settings, Trash2,
} from 'lucide-react'
import SwipeRow from '@/bits/SwipeRow/SwipeRow'
import RubberSegment from '@/bits/RubberSegment/RubberSegment'
import { HugeiconsIcon } from '@hugeicons/react'
import { Archive02Icon, Delete02Icon } from '@hugeicons/core-free-icons'
import BrandMark from './BrandMark'
import SettingsDialog from './SettingsDialog'
import { useAuth } from '@/lib/auth'
import { sessionStore, useSessions } from '@/lib/sessions'
import { projectStore, useProjects } from '@/lib/projects'
import { toast } from '@/lib/toast'
import { api, errMessage, USE_MOCK } from '@/lib/api'
import { isTempId } from '@/lib/conversations'
import { agentStore } from '@/lib/agents'
import { PAGE_PATH, type PageKey } from '@/lib/types'
import type { SessionItem } from '@/data'
import '@/styles/sidebar.css'

const NAV: { key: PageKey; label: string; icon: typeof BookOpen }[] = [
  { key: 'workbench', label: '工作台', icon: LayoutDashboard },
  { key: 'kb', label: '知识库', icon: BookOpen },
  { key: 'chat', label: '对话', icon: MessagesSquare },
  { key: 'agents', label: 'Agent', icon: Bot },
  /* 「账号」不再作为导航项：它是冗余入口（设置已收在底部「个人设置」弹层里） */
]

/** 会话行滑动操作的颜色 */
const C = {
  /* 选中行：把表面色与会话文字色混合，两种主题下都能拉开明显亮度差
     （纯 --surface-muted 在亮色下与侧栏底色只差 0.016，等于没有高亮） */
  selActive: 'color-mix(in oklab, var(--surface-muted) 78%, var(--text) 22%)',
  row: 'transparent',
  drawer: 'var(--surface-muted)',
  text: 'var(--text)',
  del: 'var(--danger)',
  arch: 'var(--text-3)',
}

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
  const { sessions, activeId, archived, setId, patch, archive: archiveSession, restore: restoreSession, remove: removeSessionStore } = useSessions()
  const { projects, activeProjectId } = useProjects()

  const [settingsOpen, setSettingsOpen] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem('nx-proto-sb-collapsed') === '1')
  const [showArchived, setShowArchived] = useState(false)
  const [renaming, setRenaming] = useState<string | null>(null)
  const [draft, setDraft] = useState('')
  /* 两个分区都可收起（置顶在上、最近在下），状态持久化 */
  const [projectsOpen, setProjectsOpen] = useState(true)
  const [creating, setCreating] = useState(false)
  const [newName, setNewName] = useState('')
  const [pinnedOpen, setPinnedOpen] = useState(() => localStorage.getItem('nx-proto-sb-pinned') !== '0')
  const [recentOpen, setRecentOpen] = useState(() => localStorage.getItem('nx-proto-sb-recent') !== '0')
  const searchRef = useRef<HTMLInputElement>(null)

  useEffect(() => { localStorage.setItem('nx-proto-sb-collapsed', collapsed ? '1' : '0') }, [collapsed])
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

  const commitRename = (id: string) => {
    const name = draft.trim()
    if (name) patch(id, (x) => ({ ...x, title: name }))
    setRenaming(null)
    setDraft('')
  }

  /**
   * 删除会话（决策 3：**不可恢复**，与归档严格分开）。
   *
   * ⚠️ 后端 `DELETE /api/chat/conversations/{id}` 是**级联物理删除**
   * （`ConversationServiceImpl:198-213`，已读源码确认）：
   *   - 会话置`deleted=1`
   *   - **消息物理删除**（不可恢复）
   *   - **会话记忆清空**（`chatMemory.clear()`）
   * 后端注释说明了为什么不能省：不清记忆会导致同 id 复用时，
   * 新会话"凭空记得旧会话说过的话"。
   *
   * 三个直接后果：
   *   1. **不能提供"撤销"** —— 原来的 `toast.undo` 在真实后端下是假的
   *      （本地恢复回来，后端消息已被物理删光）。
   *   2. 必须**二次确认**，文案要说清不可恢复，并指向"归档"这个可撤销选项。
   *   3. **先调后端、成功后再改本地** —— 反过来做失败后两边状态不一致。
   *   4. 临时 id（`tmp_`，未落库）不需要也不能调后端。
   */
  const deleteSession = async (id: string) => {
    const target = sessions.find((s) => s.id === id)
    if (!target) return
    const ok = window.confirm(
      `确定删除会话「${target.title}」吗？\n\n` +
        `消息与对话记忆会被一并清除，**无法恢复**。\n` +
        `（只是想收起的话请用「归档」，归档可以随时找回。）`,
    )
    if (!ok) return

    /*
     * `USE_MOCK` 或 `tmp_` 前缀都表示"后端没有这条记录"，不调 DELETE：
     * USE_MOCK 是离线演示（种子会话 id 是 `seed_*`，单看 id 判断会漏掉）；
     * `tmp_` 是尚未落库的本地会话。
     */
    if (!USE_MOCK && !isTempId(id)) {
      try {
        await api.conversations.remove(id)
      } catch (e) {
        toast.error('删除失败', errMessage(e))
        return // 本地保持不动，与后端保持一致
      }
    }
    removeSessionStore(id)
    toast.success('会话已删除', '消息与对话记忆已一并清除')
  }

  const openSession = (id: string) => {
    setId(id)
    navigate(PAGE_PATH.chat)
    onClose?.()
  }

  /** 会话行（置顶与最近共用同一套渲染） */
  const renderSession = (s: SessionItem) => (
    <SwipeRow
      key={s.id}
      label={s.title}
      actions={[{ id: 'pin', label: s.pinned ? '取消置顶' : '置顶', icon: <Pin size={17} />, dismiss: true, color: '#6b7280' }]}
      startActions={[
        { id: 'archive', label: '归档', icon: <HugeiconsIcon icon={Archive02Icon} size={18} />, dismiss: true, color: '#6b7280' },
        { id: 'delete', label: '删除', icon: <HugeiconsIcon icon={Delete02Icon} size={18} />, color: C.del },
      ]}
      actionColor={C.arch}
      /* 滑动轨用与侧栏相同的底色：静止时不会从透明的行底下透出来（那是一层假的"选中底色"），
         滑动时操作按钮就浮在同一底色上 */
      drawerColor="var(--surface)"
      /* 当前会话高亮放在**整行**上（SwipeRow 的 rowColor）；
         内层不再重复填色，否则会变成"行里套一个色块" */
      rowColor={s.id === activeId ? C.selActive : C.row}
      textColor={C.text}
      /* 高度与项目行一致（34）：否则高亮块会比行内容高出一截，看着不对齐 */
      height={34}
      radius={8}
      actionWidth={68}
      collapseOnAction={false}
      onAction={(a) => {
        if (a.id === 'pin') {
          patch(s.id, (x) => ({ ...x, pinned: !x.pinned }))
          toast.success(s.pinned ? '已取消置顶' : '已置顶会话', s.title)
        }
      }}
      onStartAction={(a) => {
        /* 归档 vs 删除（决策 3）：归档是纯本地、可恢复；删除走后端、不可恢复 */
        if (a.id === 'archive') { archiveSession(s.id); toast.info('会话已归档', '可在「已归档」中找回') }
        if (a.id === 'delete') void deleteSession(s.id)
      }}
    >
      <div
        role="button"
        tabIndex={0}
        className="session-item sb-session"
        aria-selected={s.id === activeId}
        onClick={() => openSession(s.id)}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openSession(s.id) } }}
      >
        {renaming === s.id ? (
          <input
            className="session-rename"
            value={draft}
            autoFocus
            aria-label="重命名会话"
            onClick={(e) => e.stopPropagation()}
            onChange={(e) => setDraft(e.target.value)}
            onBlur={() => commitRename(s.id)}
            onKeyDown={(e) => {
              e.stopPropagation()
              if (e.key === 'Enter') commitRename(s.id)
              if (e.key === 'Escape') { setRenaming(null); setDraft('') }
            }}
          />
        ) : (
          <span
            className="t"
            title="双击重命名"
            onDoubleClick={(e) => { e.stopPropagation(); setRenaming(s.id); setDraft(s.title) }}
          >
            {/* 置顶与否由上方「置顶」分组表达，行内不再放图标（否则文字起点被顶偏、三层对不齐） */}
            {s.title}
          </span>
        )}
      </div>
    </SwipeRow>
  )

  return (
    <>
      {open && <div className="sb-scrim" onClick={onClose} aria-hidden="true" />}
      <aside className="sidebar" data-open={open ? '' : undefined} data-collapsed={collapsed ? '' : undefined} aria-label="侧栏导航">
        {/* 顶部：品牌 + 搜索 + 折叠 */}
        <div className="sb-top">
          <span className="sb-brand-mini"><BrandMark wordmark={!collapsed} /></span>
          <span className="sb-top-actions">
            <button
              className="sb-icon-btn"
              type="button"
              aria-label="搜索会话"
              aria-pressed={searchOpen}
              onClick={() => setSearchOpen((v) => !v)}
            >
              <Search size={16} />
            </button>
            <button
              className="sb-icon-btn"
              type="button"
              aria-label={collapsed ? '展开侧栏' : '折叠侧栏'}
              onClick={() => setCollapsed((v) => !v)}
            >
              {collapsed ? <PanelLeftOpen size={16} /> : <PanelLeftClose size={16} />}
            </button>
          </span>
        </div>

        {searchOpen && !collapsed && (
          <div className="chat-search sb-search">
            <Search size={14} />
            <input
              ref={searchRef}
              className="input"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="搜索会话"
              aria-label="搜索会话"
            />
          </div>
        )}

        {/* 快捷：新对话 */}
        <button className="sb-row sb-row--quick" type="button" onClick={() => { sessionStore.requestCreate(); go('chat') }}>
          <Plus size={16} aria-hidden="true" />
          <span>新对话</span>
        </button>

        {/* 导航 */}
        <nav className="sb-nav" aria-label="主导航">
          <RubberSegment
            direction="vertical"
            items={NAV.map((n) => ({
              value: n.key,
              label: (
                <span className="sb-item">
                  <n.icon size={16} aria-hidden="true" />
                  <span>{n.label}</span>
                </span>
              ),
            }))}
            value={page}
            onChange={(v) => go(v as PageKey)}
            size="lg"
            radius={10}
            inset={3}
            equalSlots
            trackColor="transparent"
            thumbColor="var(--surface-muted)"
            textColor="var(--text-2)"
            activeTextColor="var(--text)"
            aria-label="主导航"
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
        {false && !collapsed && (
          <section className="sb-group sb-projects">
            <button
              className="sb-section sb-section--toggle"
              type="button"
              aria-expanded={projectsOpen}
              onClick={() => setProjectsOpen((v) => !v)}
            >
              <span>项目</span>
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
                      toast.success('项目已创建', name)
                    }}
                  >
                    <input
                      className="input"
                      autoFocus
                      value={newName}
                      placeholder="项目名称"
                      aria-label="项目名称"
                      onChange={(e) => setNewName(e.target.value)}
                      onKeyDown={(e) => { if (e.key === 'Escape') { setCreating(false); setNewName('') } }}
                    />
                    <button className="btn btn-primary btn-sm" type="submit">创建</button>
                  </form>
                ) : (
                  <button className="sb-project sb-project--new" type="button" onClick={() => setCreating(true)}>
                    <Plus size={14} aria-hidden="true" />
                    <span>创建项目</span>
                  </button>
                )}

                <button
                  className="sb-project"
                  type="button"
                  aria-current={activeProjectId === null ? 'true' : undefined}
                  onClick={() => projectStore.setActive(null)}
                >
                  <FolderOpen size={14} aria-hidden="true" />
                  <span>全部会话</span>
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
                        aria-label={`删除项目 ${p.name}`}
                        onClick={() => {
                          projectStore.remove(p.id)
                          toast.info('项目已删除', p.name)
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
        {!collapsed && (
          <div className="sb-recent">
            {archived.length > 0 && (
              <div className="session-arch">
                <button className="session-arch-toggle" type="button" aria-expanded={showArchived} onClick={() => setShowArchived((v) => !v)}>
                  <HugeiconsIcon icon={Archive02Icon} size={14} /> 已归档（{archived.length}）
                </button>
                {showArchived && (
                  <div className="session-arch-list">
                    {sessions.filter((s) => archived.includes(s.id)).map((s) => (
                      <div className="list-row session-arch-row" key={s.id}>
                        <span className="t">{s.title}</span>
                        <button className="btn btn-ghost btn-sm" type="button" onClick={() => { restoreSession(s.id); toast.success('会话已恢复', s.title) }}>恢复</button>
                        <button className="btn btn-ghost btn-sm" type="button" aria-label={`删除 ${s.title}`} onClick={() => void deleteSession(s.id)}>
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
                  <span>置顶</span>
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
                <span>最近</span>
                <ChevronRight size={14} className={recentOpen ? 'rot90' : ''} />
              </button>
              {recentOpen && <div className="session-list sb-recent-list">{recent.map(renderSession)}</div>}
            </section>

            {visibleAll.length === 0 && (
              <p className="sb-empty">{query ? `没有匹配「${query}」的会话` : '还没有会话'}</p>
            )}
          </div>
        )}

        {/* 底部：只保留「个人设置」，账号信息在弹层里看 */}
        <div className="sb-foot">
          <button className="sb-row sb-row--settings" type="button" onClick={() => setSettingsOpen(true)}>
            <Settings size={16} aria-hidden="true" />
            <span>个人设置</span>
          </button>
        </div>
      </aside>

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
          sessionStore.resetToSeed()
          navigate('/login', { replace: true })
        }}
      />
    </>
  )
}
