import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent } from 'react'
import { motion, useReducedMotion } from 'motion/react'
import {
  BadgeCheck, Blocks, BookOpen, Check, CircleDot, Download, FileUp, MoreHorizontal,
  Package, Plus, Puzzle, Search, Server, Settings2, Sparkles, Star, Trash2, X, Zap,
} from 'lucide-react'
import Modal from '@/components/Modal'
import { SkeletonRows, StateEmpty } from '@/components/Skeleton'
import { toast } from '@/lib/toast'
import { appCopy } from '@/lib/appCopy'
import { pagesCopy } from '@/lib/pagesCopy'
import { useLocale } from '@/lib/locale'
import {
  MCP_SERVERS, PLUGIN_CATALOG, parseSkillContent,
  readSkills, writeSkills,
  type PluginCategory, type PluginMeta, type SkillPack,
} from '@/lib/plugins'
import {
  api, errMessage, USE_MOCK,
  type BackendMcpServer, type McpProbeResult, type McpServerPayload, type McpTransport,
} from '@/lib/api'
import '@/styles/plugins.css'

type Tab = 'installed' | 'market' | 'mcp'

const CATEGORY_ICONS: Record<PluginCategory, typeof Puzzle> = {
  agent: Sparkles,
  knowledge: BookOpen,
  search: Search,
  automation: Settings2,
  productivity: Blocks,
  data: CircleDot,
  skill: Package,
}

/** 页面里的 MCP 视图：把后端的 `BackendMcpServer` 与 mock 的 `McpServerMeta` 收敛成一种形状。 */
interface McpView {
  id: string
  name: string
  transport: McpTransport
  url: string
  command: string
  enabled: boolean
  tools: { name: string; description: string }[]
  lastOkAt: string | null
  lastError: string | null
  hasAuthHeader: boolean
}

function fromBackend(server: BackendMcpServer): McpView {
  return {
    id: server.id,
    name: server.name,
    transport: server.transport === 'stdio' ? 'stdio' : 'http',
    url: server.url ?? '',
    command: server.command ?? '',
    enabled: server.enabled,
    tools: server.tools.map((tool) => ({ name: tool.name, description: tool.description })),
    lastOkAt: server.lastOkAt,
    lastError: server.lastError,
    hasAuthHeader: server.hasAuthHeader,
  }
}

/** 演示数据：房间里的四个假服务，让 mock 模式与 `verify-market.mjs` 有东西可渲染。 */
function mockServers(): McpView[] {
  return MCP_SERVERS.map((server) => ({
    id: server.id,
    name: server.name,
    transport: 'http' as const,
    url: server.endpoint,
    command: '',
    enabled: server.connected,
    tools: server.tools.map((name) => ({ name, description: '' })),
    lastOkAt: null,
    lastError: null,
    hasAuthHeader: false,
  }))
}

export default function PluginsPage() {
  const { locale } = useLocale()
  const common = appCopy(locale).common
  const p = pagesCopy(locale).plugins
  const reduce = useReducedMotion()
  const [tab, setTab] = useState<Tab>('market')
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState<PluginCategory | 'all'>('all')
  const [installed, setInstalled] = useState<string[]>(USE_MOCK ? ['nexus-mcp-bridge', 'web-fetch'] : [])
  const [skills, setSkills] = useState<SkillPack[]>(() => readSkills())
  const [servers, setServers] = useState<McpView[]>(USE_MOCK ? mockServers() : [])
  const [loading, setLoading] = useState(!USE_MOCK)
  const [detail, setDetail] = useState<PluginMeta | null>(null)
  const [importOpen, setImportOpen] = useState(false)
  const [importText, setImportText] = useState('')
  const [importFileName, setImportFileName] = useState('')
  const fileRef = useRef<HTMLInputElement>(null)

  /* MCP 编辑弹窗：editing 为 null 表示新建 */
  const [mcpOpen, setMcpOpen] = useState(false)
  const [editing, setEditing] = useState<McpView | null>(null)
  const [mcpName, setMcpName] = useState('')
  const [mcpTransport, setMcpTransport] = useState<McpTransport>('http')
  const [mcpUrl, setMcpUrl] = useState('')
  const [mcpCommand, setMcpCommand] = useState('')
  const [mcpToken, setMcpToken] = useState('')
  const [probing, setProbing] = useState(false)
  const [probe, setProbe] = useState<McpProbeResult | null>(null)
  const [saving, setSaving] = useState(false)

  const when = useCallback((iso: string | null) => (
    iso ? new Date(iso).toLocaleString(locale === 'en' ? 'en-US' : 'zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : ''
  ), [locale])

  /* 真实模式：插件状态与 MCP server 都从后端来；mock 模式用本地演示数据 */
  useEffect(() => {
    if (USE_MOCK) return
    let alive = true
    setLoading(true)
    Promise.all([api.plugins.list(), api.mcpServers.list()])
      .then(([plugins, mcpList]) => {
        if (!alive) return
        setInstalled(plugins.filter((item) => item.enabled).map((item) => item.code))
        setServers(mcpList.map(fromBackend))
      })
      .catch((error: unknown) => {
        if (alive) toast.error(p.loadFailed, errMessage(error))
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const categories = useMemo(() => ['all', ...new Set(PLUGIN_CATALOG.map((x) => x.category))], [])
  const marketList = useMemo(() => {
    const q = query.trim().toLowerCase()
    return PLUGIN_CATALOG.filter((x) => {
      if (category !== 'all' && x.category !== category) return false
      if (!q) return true
      return [x.name, x.author, x.description, ...x.tags].join(' ').toLowerCase().includes(q)
    })
  }, [category, query])
  const installedList = useMemo(() => {
    const q = query.trim().toLowerCase()
    return PLUGIN_CATALOG
      .filter((x) => installed.includes(x.id))
      .filter((x) => !q || [x.name, x.author, x.description, ...x.tags].join(' ').toLowerCase().includes(q))
  }, [installed, query])
  const filteredSkills = useMemo(() => {
    const q = query.trim().toLowerCase()
    return skills.filter((s) => !q || [s.name, s.description].join(' ').toLowerCase().includes(q))
  }, [query, skills])
  const visibleServers = useMemo(() => {
    const q = query.trim().toLowerCase()
    return servers.filter((s) => !q || [s.name, s.url, s.command].join(' ').toLowerCase().includes(q))
  }, [query, servers])

  /* Codex 式分组：推荐在前，其余按能力类别归组，行内不再用卡片包裹 */
  const pluginGroups = useMemo(() => {
    const featured = marketList.filter((x) => x.official || x.verified)
    const featuredIds = new Set(featured.map((x) => x.id))
    const groups: { key: string; title: string; items: PluginMeta[] }[] = []
    if (featured.length) groups.push({ key: 'featured', title: p.featured, items: featured })
    for (const c of [...new Set(marketList.map((x) => x.category))]) {
      const items = marketList.filter((x) => x.category === c && !featuredIds.has(x.id))
      if (items.length) groups.push({ key: c, title: p.categoryLabels[c], items })
    }
    return groups
  }, [marketList, p])

  const renderPluginRow = (plugin: PluginMeta) => {
    const Icon = CATEGORY_ICONS[plugin.category]
    const on = installed.includes(plugin.id)
    return (
      <article className="plugin-row" key={plugin.id}>
        <span className="plugin-row__icon"><Icon size={18} /></span>
        <div className="plugin-row__main">
          <div className="plugin-row__name">
            <strong>{plugin.name}</strong>
            {plugin.verified && <BadgeCheck size={14} className="plugin-row__verified" aria-label={p.verified} />}
          </div>
          <p>{plugin.description}</p>
        </div>
        <div className="plugin-row__meta">
          <span>{plugin.downloads}</span>
          <span>{plugin.rating}</span>
        </div>
        <div className="plugin-row__actions">
          {!on && (
            <button
              className="plugin-icon-btn"
              type="button"
              aria-label={`${p.install}: ${plugin.name}`}
              title={p.install}
              onClick={() => toggleInstall(plugin)}
            >
              <Plus size={16} />
            </button>
          )}
          <button
            className="plugin-icon-btn"
            type="button"
            aria-label={`${p.details}: ${plugin.name}`}
            title={p.details}
            onClick={() => setDetail(plugin)}
          >
            <MoreHorizontal size={16} />
          </button>
        </div>
      </article>
    )
  }

  /**
   * 安装 / 卸载 = 把 `user_plugin.enabled` 写成 true / false。
   *
   * 目录在前端，服务端只记"这个用户对这个 code 做了什么"，所以没有"新增一行"这种操作，
   * 也就没有卸载后配置丢失的问题（重装回来配置还在）。
   */
  const toggleInstall = (plugin: PluginMeta) => {
    const on = installed.includes(plugin.id)
    const next = on ? installed.filter((id) => id !== plugin.id) : [...installed, plugin.id]
    setInstalled(next)
    if (USE_MOCK) {
      toast[on ? 'info' : 'success'](on ? p.uninstall : p.install, `${plugin.name} ${plugin.version}`)
      return
    }
    api.plugins.update(plugin.id, { enabled: !on })
      .then(() => toast[on ? 'info' : 'success'](on ? p.uninstall : p.install, `${plugin.name} ${plugin.version}`))
      .catch((error: unknown) => {
        setInstalled(installed) // 回滚：开关留在原状，比"看起来装上了其实没写进库"诚实
        toast.error(p.saveFailed, errMessage(error))
      })
  }

  const onImportFile = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    setImportFileName(file.name)
    const reader = new FileReader()
    reader.onload = () => setImportText(String(reader.result ?? ''))
    reader.readAsText(file)
  }

  const commitImport = () => {
    const parsed = parseSkillContent(importText)
    const name = parsed.name?.trim()
    if (!name) {
      toast.error(p.importFail, p.importInvalid)
      return
    }
    const item: SkillPack = {
      id: `skill-${Date.now()}`,
      name,
      version: parsed.version ?? '0.1.0',
      description: parsed.description ?? '',
      source: importFileName || 'manual',
      importedAt: new Date().toLocaleDateString(locale === 'en' ? 'en-US' : 'zh-CN'),
    }
    const next = [item, ...skills].slice(0, 50)
    setSkills(next)
    writeSkills(next)
    setImportOpen(false)
    setImportText('')
    setImportFileName('')
    toast.success(p.importDone, `${name} ${item.version}`)
  }

  /* ---------- MCP ---------- */

  const openMcpCreate = () => {
    setEditing(null)
    setMcpName('')
    setMcpTransport('http')
    setMcpUrl('')
    setMcpCommand('')
    setMcpToken('')
    setProbe(null)
    setMcpOpen(true)
  }

  const openMcpEdit = (server: McpView) => {
    setEditing(server)
    setMcpName(server.name)
    setMcpTransport(server.transport)
    setMcpUrl(server.url)
    setMcpCommand(server.command)
    setMcpToken('')
    setProbe(null)
    setMcpOpen(true)
  }

  const payloadOf = (): McpServerPayload => ({
    name: mcpName.trim(),
    transport: mcpTransport,
    ...(mcpTransport === 'http' ? { url: mcpUrl.trim() } : { command: mcpCommand.trim() }),
    // 只在真的填了新令牌时才上行：空串会被后端当成"清除凭据"
    ...(mcpToken.trim() ? { authHeader: mcpToken.trim() } : {}),
  })

  const applySaved = (saved: BackendMcpServer) => {
    const view = fromBackend(saved)
    setServers((prev) => {
      const exists = prev.some((item) => item.id === view.id)
      return exists ? prev.map((item) => (item.id === view.id ? view : item)) : [...prev, view]
    })
    setEditing(view)
    return view
  }

  /** 保存；`thenProbe` 为 true 时保存成功后立刻探测一次（新建服务最常用的路径）。 */
  const saveMcp = async (thenProbe = false) => {
    setSaving(true)
    try {
      if (USE_MOCK) {
        const view: McpView = {
          id: editing?.id ?? `mcp-${Date.now()}`,
          name: mcpName.trim(),
          transport: mcpTransport,
          url: mcpTransport === 'http' ? mcpUrl.trim() : '',
          command: mcpTransport === 'stdio' ? mcpCommand.trim() : '',
          enabled: editing?.enabled ?? true,
          tools: editing?.tools ?? [],
          lastOkAt: editing?.lastOkAt ?? null,
          lastError: null,
          hasAuthHeader: Boolean(mcpToken.trim()) || Boolean(editing?.hasAuthHeader),
        }
        setServers((prev) => (editing ? prev.map((item) => (item.id === view.id ? view : item)) : [...prev, view]))
        setEditing(view)
        toast.success(editing ? p.mcpUpdatedTitle : p.mcpCreatedTitle, view.name)
        if (thenProbe) await probeMock(view)
        return
      }
      const body = payloadOf()
      const saved = editing
        ? await api.mcpServers.update(editing.id, body)
        : await api.mcpServers.create(body)
      applySaved(saved)
      setMcpToken('')
      toast.success(editing ? p.mcpUpdatedTitle : p.mcpCreatedTitle, saved.name)
      if (thenProbe) await probeServer(saved.id)
    } catch (error) {
      toast.error(p.saveFailed, errMessage(error))
    } finally {
      setSaving(false)
    }
  }

  const probeMock = async (view: McpView) => {
    setProbing(true)
    setProbe({
      ok: true,
      error: null,
      tools: view.tools.map((tool) => ({ name: tool.name, description: tool.description, inputSchema: null })),
      tookMs: 12,
      lastOkAt: new Date().toISOString(),
    })
    setProbing(false)
  }

  /**
   * 探测 = 真连一次 `tools/list`。
   *
   * 失败**不是**异常：后端恒 200，结果在 `ok/error` 里。这里必须把 `error` 原样显示出来，
   * 否则用户只会看到"没有工具"，然后把好好的服务反复删了重配。
   */
  const probeServer = async (id: string) => {
    setProbing(true)
    setProbe(null)
    try {
      const result = await api.mcpServers.probe(id)
      setProbe(result)
      if (result.ok) {
        const list = await api.mcpServers.list()
        setServers(list.map(fromBackend))
      }
    } catch (error) {
      setProbe({ ok: false, error: errMessage(error), tools: [], tookMs: 0, lastOkAt: null })
    } finally {
      setProbing(false)
    }
  }

  const removeMcp = (server: McpView) => {
    if (!window.confirm(p.mcpDeleteConfirm(server.name))) return
    setServers((prev) => prev.filter((item) => item.id !== server.id))
    setMcpOpen(false)
    if (USE_MOCK) {
      toast.success(p.mcpDeletedTitle, server.name)
      return
    }
    api.mcpServers.remove(server.id)
      .then(() => toast.success(p.mcpDeletedTitle, server.name))
      .catch((error: unknown) => {
        toast.error(p.saveFailed, errMessage(error))
        void api.mcpServers.list().then((list) => setServers(list.map(fromBackend))).catch(() => undefined)
      })
  }

  const toggleMcp = (server: McpView) => {
    const next = servers.map((item) => (item.id === server.id ? { ...item, enabled: !item.enabled } : item))
    setServers(next)
    if (USE_MOCK) {
      toast.success(!server.enabled ? p.mcpConnectedTitle : p.mcpDisconnectedTitle, server.name)
      return
    }
    api.mcpServers.update(server.id, { enabled: !server.enabled })
      .then((saved) => {
        setServers((prev) => prev.map((item) => (item.id === saved.id ? fromBackend(saved) : item)))
        toast.success(!server.enabled ? p.mcpConnectedTitle : p.mcpDisconnectedTitle, server.name)
      })
      .catch((error: unknown) => {
        setServers(servers)
        toast.error(p.saveFailed, errMessage(error))
      })
  }

  const canSave = Boolean(mcpName.trim()) && (mcpTransport === 'http' ? Boolean(mcpUrl.trim()) : Boolean(mcpCommand.trim()))

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <p className="eyebrow" style={{ margin: 0 }}>{p.eyebrow}</p>
          <h1 className="page-title">{p.title}</h1>
          <p className="page-sub">{p.sub}</p>
        </div>
        <button className="btn btn-primary" type="button" onClick={() => setImportOpen(true)}>
          <FileUp size={16} /> {p.importSkill}
        </button>
      </div>

      <div className="market-toolbar">
        <div className="market-tabs" role="tablist" aria-label={p.title}>
          {([
            { key: 'market', label: p.tabMarket },
            { key: 'installed', label: p.tabInstalled },
            { key: 'mcp', label: p.tabMcp },
          ] as const).map((t) => (
            <button
              key={t.key}
              type="button"
              role="tab"
              aria-selected={tab === t.key}
              className={`market-tab${tab === t.key ? ' is-on' : ''}`}
              onClick={() => setTab(t.key)}
            >
              {t.label}
            </button>
          ))}
        </div>
        <div className="market-search">
          <Search size={15} aria-hidden="true" />
          <input
            className="input"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={p.searchPh}
            aria-label={p.searchAria}
          />
          {query && (
            <button className="market-search__clear" type="button" aria-label={common.close} onClick={() => setQuery('')}>
              <X size={14} />
            </button>
          )}
        </div>
      </div>

      {tab === 'market' && (
        <div className="filter-row" role="group" aria-label={p.categoryAll}>
          {categories.map((c) => (
            <button
              key={c}
              type="button"
              className={`chip filter-chip${category === c ? ' filter-chip--on' : ''}`}
              aria-pressed={category === c}
              onClick={() => setCategory(c as PluginCategory | 'all')}
            >
              {c === 'all' ? p.categoryAll : p.categoryLabels[c]}
            </button>
          ))}
        </div>
      )}

      <motion.div
        className="market-panel"
        key={tab}
        initial={reduce ? undefined : { opacity: 0, y: 6 }}
        animate={{ opacity: 1, y: 0 }}
        transition={reduce ? { duration: 0 } : { duration: 0.2, ease: [0.23, 1, 0.32, 1] }}
      >
        {loading && <SkeletonRows rows={4} />}

        {!loading && tab === 'installed' && (
          <>
            {installedList.length === 0 && filteredSkills.length === 0 && (
              <StateEmpty
                icon={<Puzzle size={20} />}
                title={p.installedEmptyTitle}
                desc={p.installedEmptyDesc}
              />
            )}
            {installedList.length > 0 && (
              <section className="plugin-group">
                <h2 className="plugin-group__title"><Check size={14} aria-hidden="true" /> {p.tabInstalled}</h2>
                <div className="plugin-list">{installedList.map(renderPluginRow)}</div>
              </section>
            )}
            {filteredSkills.length > 0 && (
              <section className="skill-section">
                <h2 className="market-section-title">{p.tabInstalled} · Skills</h2>
                <div className="skill-list">
                  {filteredSkills.map((skill) => (
                    <article className="skill-row" key={skill.id}>
                      <span className="skill-row__icon"><Package size={16} /></span>
                      <div className="skill-row__main">
                        <strong>{skill.name}</strong>
                        <span>{skill.version} · {skill.source}</span>
                      </div>
                      <p>{skill.description || p.importDropHint}</p>
                      <time>{skill.importedAt}</time>
                    </article>
                  ))}
                </div>
              </section>
            )}
          </>
        )}

        {!loading && tab === 'market' && (
          marketList.length === 0 ? (
            <StateEmpty
              icon={<Search size={20} />}
              title={query ? p.noResults(query) : p.marketEmpty}
              desc={p.installedEmptyDesc}
            />
          ) : (
            <div className="plugin-groups">
              {pluginGroups.map((group) => (
                <section className="plugin-group" key={group.key}>
                  <h2 className="plugin-group__title">
                    {group.key === 'featured'
                      ? <Star size={14} aria-hidden="true" />
                      : (() => { const GroupIcon = CATEGORY_ICONS[group.key as PluginCategory]; return <GroupIcon size={14} aria-hidden="true" /> })()}
                    {' '}{group.title}
                  </h2>
                  <div className="plugin-list">{group.items.map(renderPluginRow)}</div>
                </section>
              ))}
            </div>
          )
        )}

        {!loading && tab === 'mcp' && (
          <section className="mcp-section">
            <div className="section-head">
              <div>
                <h2 className="market-section-title">MCP</h2>
                <p className="page-sub">{p.mcpSub}</p>
              </div>
              <button className="btn btn-ghost btn-sm" type="button" onClick={openMcpCreate}>
                <Plus size={14} /> {p.mcpNew}
              </button>
            </div>
            {visibleServers.length === 0 ? (
              <StateEmpty icon={<Server size={20} />} title={p.mcpEmptyTitle} desc={p.mcpEmptyDesc} />
            ) : (
              <div className="mcp-list">
                {visibleServers.map((server) => (
                  <article className="mcp-row" key={server.id}>
                    <span className="mcp-row__icon"><Server size={18} /></span>
                    <div className="mcp-row__main">
                      <div className="mcp-row__name">
                        <strong>{server.name}</strong>
                        <span className="badge" data-tone={server.enabled ? 'success' : 'warning'}>
                          {server.enabled ? p.mcpConnected : p.mcpOffline}
                        </span>
                        <span className="badge">{server.transport === 'stdio' ? p.mcpTransportStdio : p.mcpTransportHttp}</span>
                      </div>
                      <code>{server.transport === 'stdio' ? server.command : server.url}</code>
                      {server.lastError && (
                        <p className="mcp-row__error" role="alert">
                          {p.mcpLastError}：{server.lastError}
                        </p>
                      )}
                      {!server.lastError && server.lastOkAt && (
                        <p className="mcp-row__ok">{p.mcpLastOk(when(server.lastOkAt))}</p>
                      )}
                    </div>
                    <div className="mcp-row__tools">
                      <span className="meta-inline__item">{server.tools.slice(0, 3).map((tool) => tool.name).join(' · ') || p.mcpNoTools}</span>
                      <span className="mcp-row__count">{p.mcpTools(server.tools.length)}</span>
                    </div>
                    <div className="mcp-row__actions">
                      <button className="btn btn-ghost btn-sm" type="button" onClick={() => openMcpEdit(server)}>
                        <Settings2 size={13} /> {p.mcpConfigure}
                      </button>
                      <button className="btn btn-ghost btn-sm" type="button" onClick={() => toggleMcp(server)}>
                        {server.enabled ? p.mcpDisconnect : p.mcpConnect}
                      </button>
                    </div>
                  </article>
                ))}
              </div>
            )}
          </section>
        )}
      </motion.div>

      <Modal
        open={detail !== null}
        title={detail?.name ?? p.details}
        width={620}
        onClose={() => setDetail(null)}
        footer={detail && (
          <>
            <span style={{ flex: 1 }} />
            <button className="btn btn-ghost" type="button" onClick={() => setDetail(null)}>{common.close}</button>
            <button
              className="btn btn-primary"
              type="button"
              onClick={() => { toggleInstall(detail); setDetail(null) }}
            >
              {installed.includes(detail.id) ? p.uninstall : p.install}
            </button>
          </>
        )}
      >
        {detail && (
          <div className="plugin-detail">
            <div className="plugin-detail__hero">
              <span className="market-card__icon">{(() => { const Icon = CATEGORY_ICONS[detail.category]; return <Icon size={22} /> })()}</span>
              <div>
                <h3>{detail.name} <span className="chip">{detail.version}</span></h3>
                <p>{p.byAuthor(detail.author)} · {p.updated} {detail.updated}</p>
              </div>
            </div>
            <p className="plugin-detail__desc">{detail.description}</p>
            <div className="market-meta">
              <span><Check size={13} /> {p.rating} {detail.rating}</span>
              <span><Download size={13} /> {detail.downloads}</span>
              {detail.verified && <span className="badge" data-tone="accent">{p.verified}</span>}
            </div>
            <h4>{p.detailTools}</h4>
            <div className="meta-list">
              {detail.tools.map((tool) => <span className="meta-list__row" key={tool}><Settings2 size={14} aria-hidden="true" /> {tool}</span>)}
            </div>
            <h4>{p.detailCompat}</h4>
            <p>{p.detailCompatBody}</p>
            <h4>{p.detailTags}</h4>
            <div className="meta-inline">
              {detail.tags.map((tag) => <span className="meta-inline__item" key={tag}>{tag}</span>)}
            </div>
          </div>
        )}
      </Modal>

      <Modal
        open={importOpen}
        title={p.importTitle}
        width={620}
        onClose={() => setImportOpen(false)}
        footer={
          <>
            <span style={{ flex: 1 }} />
            <button className="btn btn-ghost" type="button" onClick={() => setImportOpen(false)}>{common.cancel}</button>
            <button className="btn btn-primary" type="button" disabled={!importText.trim()} onClick={commitImport}>
              <FileUp size={15} /> {p.importBtn}
            </button>
          </>
        }
      >
        <div className="import-block">
          <p className="page-sub">{p.importDesc}</p>
          <textarea
            className="input import-block__text"
            value={importText}
            onChange={(e) => setImportText(e.target.value)}
            placeholder={p.importPh}
            aria-label={p.importAria}
            rows={9}
          />
          <input
            ref={fileRef}
            className="hidden-file"
            type="file"
            accept=".md,.markdown,.json,text/markdown,application/json"
            aria-label={p.importBrowseAria}
            onChange={onImportFile}
          />
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => fileRef.current?.click()}>
            <BookOpen size={14} /> {p.importBrowse}
          </button>
          {importFileName && <span className="hint">{importFileName}</span>}
          <p className="hint">{p.importDropHint}</p>
        </div>
      </Modal>

      <Modal
        open={mcpOpen}
        title={editing ? p.mcpModalTitle : `${p.mcpModalTitle} · ${p.mcpNew}`}
        width={620}
        onClose={() => setMcpOpen(false)}
        footer={
          <>
            {editing && (
              <button className="btn btn-ghost" type="button" onClick={() => removeMcp(editing)}>
                <Trash2 size={14} /> {p.mcpDelete}
              </button>
            )}
            <span style={{ flex: 1 }} />
            <button className="btn btn-ghost" type="button" onClick={() => setMcpOpen(false)}>{common.cancel}</button>
            <button
              className="btn btn-ghost"
              type="button"
              disabled={!canSave || probing || saving}
              onClick={() => void saveMcp(true)}
            >
              <Zap size={14} /> {probing ? p.mcpProbing : p.mcpProbe}
            </button>
            <button
              className="btn btn-primary"
              type="button"
              disabled={!canSave || saving}
              onClick={() => void saveMcp(false)}
            >
              {p.mcpSave}
            </button>
          </>
        }
      >
        <div className="form-stack">
          <div className="field">
            <label htmlFor="mcp-name">{p.mcpNameLabel}</label>
            <input id="mcp-name" className="input" value={mcpName} autoFocus onChange={(e) => setMcpName(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="mcp-transport">{p.mcpTransportLabel}</label>
            <select
              id="mcp-transport"
              className="input"
              value={mcpTransport}
              onChange={(e) => setMcpTransport(e.target.value === 'stdio' ? 'stdio' : 'http')}
            >
              <option value="http">{p.mcpTransportHttp}</option>
              <option value="stdio">{p.mcpTransportStdio}</option>
            </select>
          </div>
          {mcpTransport === 'http' ? (
            <div className="field">
              <label htmlFor="mcp-endpoint">{p.mcpUrlLabel}</label>
              <input id="mcp-endpoint" className="input" value={mcpUrl} placeholder="https://example.com/mcp" onChange={(e) => setMcpUrl(e.target.value)} />
            </div>
          ) : (
            <div className="field">
              <label htmlFor="mcp-command">{p.mcpCommandLabel}</label>
              <input id="mcp-command" className="input" value={mcpCommand} placeholder={p.mcpCommandPh} onChange={(e) => setMcpCommand(e.target.value)} />
              <p className="hint">{p.mcpStdioHint}</p>
            </div>
          )}
          <div className="field">
            <label htmlFor="mcp-token">{p.mcpTokenLabel}</label>
            <input
              id="mcp-token"
              className="input"
              value={mcpToken}
              placeholder={editing?.hasAuthHeader ? '••••••••' : p.mcpTokenPh}
              onChange={(e) => setMcpToken(e.target.value)}
            />
          </div>
          {probe && (
            probe.ok
              ? <p className="mcp-probe mcp-probe--ok" role="status">{p.mcpProbeOk(probe.tools.length)}</p>
              : <p className="mcp-probe mcp-probe--fail" role="alert">{p.mcpProbeFail}：{probe.error ?? '—'}</p>
          )}
          <div className="field">
            <label>{p.mcpToolsLabel}</label>
            <div className="meta-list">
              {(probe?.ok ? probe.tools : editing?.tools ?? []).map((tool) => (
                <span className="meta-list__row" key={tool.name}>
                  <Check size={13} aria-hidden="true" /> {tool.name}
                  {tool.description ? <span className="hint">{tool.description}</span> : null}
                </span>
              ))}
              {(probe?.ok ? probe.tools : editing?.tools ?? []).length === 0 && <span className="hint">{p.mcpNoTools}</span>}
            </div>
          </div>
        </div>
      </Modal>
    </div>
  )
}
