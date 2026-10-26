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
  type PluginCategory, type PluginMeta,
} from '@/lib/plugins'
import {
  api, errMessage, USE_MOCK,
  type BackendMcpServer, type BackendPlugin, type BackendSkill,
  type McpProbeResult, type McpServerPayload, type McpTransport,
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
  /** 是否已显式允许在这台机器上跑 `command`（stdio 专用）。 */
  allowStdio: boolean
  /** 最近一次工具调用（与 probe 分开：一个是"配置通不通"，一个是"工具跑没跑成"）。 */
  lastCallAt: string | null
  lastCallTool: string | null
  lastCallOk: boolean | null
  lastCallError: string | null
}

/**
 * 插件页里的统一形状：目录字段（服务端 `plugin_catalog`）+ 当前用户装没装。
 *
 * mock 与真实模式都收敛到这一种，渲染层就不用到处判 `USE_MOCK`。
 */
interface PluginView {
  id: string
  name: string
  author: string
  version: string
  category: PluginCategory
  description: string
  icon: string | null
  rating: number | null
  downloads: string
  tools: string[]
  tags: string[]
  verified: boolean
  official: boolean
  updated: string
  enabled: boolean
}

/** 一份 SKILL.md 的列表视图：列表不带正文，只带"多大"；正文在打开编辑时按需取。 */
interface SkillView {
  code: string
  name: string
  version: string
  description: string
  sizeLabel: string
  updated: string
  /** 正文。列表接口不回，编辑时用 `GET /api/skills/{code}` 补上；mock 模式下直接带着。 */
  body: string
  /** 停用的技能仍在列表里，但不参与后续的注入（`PUT` 带 `enabled=false`）。 */
  enabled: boolean
}

function categoryOf(raw: string): PluginCategory {
  return (raw in CATEGORY_ICONS ? raw : 'agent') as PluginCategory
}

function fromPlugin(item: BackendPlugin): PluginView {
  return {
    id: item.code,
    name: item.name,
    author: item.author ?? '',
    version: item.version ?? '',
    category: categoryOf(item.category),
    description: item.description ?? '',
    icon: item.icon,
    rating: item.rating,
    downloads: item.downloads ?? '',
    tools: item.tools,
    tags: item.tags,
    verified: item.verified,
    official: item.official,
    updated: item.updated ?? '',
    enabled: item.enabled,
  }
}

/** mock 目录：只在 `USE_MOCK` 时用；真实模式一条都不读前端清单。 */
function mockPlugins(): PluginView[] {
  const installed = new Set(['nexus-mcp-bridge', 'web-fetch'])
  return PLUGIN_CATALOG.map((item: PluginMeta) => ({
    id: item.id,
    name: item.name,
    author: item.author,
    version: item.version,
    category: item.category,
    description: item.description,
    icon: null,
    rating: item.rating,
    downloads: item.downloads,
    tools: item.tools,
    tags: item.tags,
    verified: Boolean(item.verified),
    official: Boolean(item.official),
    updated: item.updated,
    enabled: installed.has(item.id),
  }))
}

function fromSkill(item: BackendSkill): SkillView {
  return {
    code: item.code,
    name: item.name,
    version: item.version ?? '',
    description: item.description ?? '',
    sizeLabel: `${Math.max(1, Math.round(item.sizeBytes / 1024))} KB`,
    updated: item.updatedAt,
    body: item.contentMd ?? '',
    enabled: item.enabled,
  }
}

/** mock 的两份技能：保证"导入后立刻出现在已安装"这条演示路径不依赖后端。 */
function mockSkills(): SkillView[] {
  return [
    {
      code: 'skill-rag-checklist', name: 'RAG 上线检查单', version: '1.0.0',
      description: '检索质量、切分与回落策略的逐项核对。', sizeLabel: '4 KB', updated: '2026-10-02',
      body: '---\nname: RAG 上线检查单\nversion: 1.0.0\ndescription: 检索质量、切分与回落策略的逐项核对。\n---\n\n- [ ] 切分粒度是否覆盖最长段落\n- [ ] 混合检索权重是否跑过 A/B\n',
      enabled: true,
    },
    {
      code: 'skill-review-copy', name: '评审话术', version: '0.3.1',
      description: '把 diff 讲成对方能接受的修改建议。', sizeLabel: '2 KB', updated: '2026-09-21',
      body: '---\nname: 评审话术\nversion: 0.3.1\ndescription: 把 diff 讲成对方能接受的修改建议。\n---\n\n先说结论，再说依据，最后给替代方案。\n',
      enabled: true,
    },
  ]
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
    allowStdio: server.allowStdio ?? false,
    lastCallAt: server.lastCallAt ?? null,
    lastCallTool: server.lastCallTool ?? null,
    lastCallOk: server.lastCallOk ?? null,
    lastCallError: server.lastCallError ?? null,
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
    allowStdio: false,
    lastCallAt: null,
    lastCallTool: null,
    lastCallOk: null,
    lastCallError: null,
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
  const [catalog, setCatalog] = useState<PluginView[]>(USE_MOCK ? mockPlugins() : [])
  const [skills, setSkills] = useState<SkillView[]>(USE_MOCK ? mockSkills() : [])
  const [servers, setServers] = useState<McpView[]>(USE_MOCK ? mockServers() : [])
  const [loading, setLoading] = useState(!USE_MOCK)
  const [detail, setDetail] = useState<PluginView | null>(null)
  const [importOpen, setImportOpen] = useState(false)
  const [importText, setImportText] = useState('')
  const [importFileName, setImportFileName] = useState('')
  /** 非 null = 这个弹窗在编辑已有技能（code 固定，改名不会造出第二条）。 */
  const [editingSkill, setEditingSkill] = useState<SkillView | null>(null)
  /** 编辑时的停用开关：停用的技能仍在列表里，但不参与后续注入。 */
  const [skillEnabled, setSkillEnabled] = useState(true)
  const fileRef = useRef<HTMLInputElement>(null)

  /* MCP 编辑弹窗：editing 为 null 表示新建 */
  const [mcpOpen, setMcpOpen] = useState(false)
  const [editing, setEditing] = useState<McpView | null>(null)
  const [mcpName, setMcpName] = useState('')
  const [mcpTransport, setMcpTransport] = useState<McpTransport>('http')
  const [mcpUrl, setMcpUrl] = useState('')
  const [mcpCommand, setMcpCommand] = useState('')
  const [mcpToken, setMcpToken] = useState('')
  /* 起子进程的许可：默认 false，必须用户在这条 server 上明确勾一次 */
  const [mcpAllowStdio, setMcpAllowStdio] = useState(false)
  const [probing, setProbing] = useState(false)
  const [probe, setProbe] = useState<McpProbeResult | null>(null)
  const [saving, setSaving] = useState(false)

  const when = useCallback((iso: string | null) => (
    iso ? new Date(iso).toLocaleString(locale === 'en' ? 'en-US' : 'zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : ''
  ), [locale])

  /* 真实模式：目录、装没装、技能正文、MCP server 都从后端来；mock 模式用本地演示数据 */
  useEffect(() => {
    if (USE_MOCK) return
    let alive = true
    setLoading(true)
    Promise.all([api.plugins.list(), api.skills.list(), api.mcpServers.list()])
      .then(([plugins, skillList, mcpList]) => {
        if (!alive) return
        setCatalog(plugins.map(fromPlugin))
        setSkills(skillList.map(fromSkill))
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

  const categories = useMemo(
    () => ['all', ...new Set(catalog.map((x) => x.category))] as (PluginCategory | 'all')[],
    [catalog],
  )
  const marketList = useMemo(() => {
    const q = query.trim().toLowerCase()
    return catalog.filter((x) => {
      if (category !== 'all' && x.category !== category) return false
      if (!q) return true
      return [x.name, x.author, x.description, ...x.tags].join(' ').toLowerCase().includes(q)
    })
  }, [catalog, category, query])
  const installedList = useMemo(() => {
    const q = query.trim().toLowerCase()
    return catalog
      .filter((x) => x.enabled)
      .filter((x) => !q || [x.name, x.author, x.description, ...x.tags].join(' ').toLowerCase().includes(q))
  }, [catalog, query])
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
    const groups: { key: string; title: string; items: PluginView[] }[] = []
    if (featured.length) groups.push({ key: 'featured', title: p.featured, items: featured })
    for (const c of [...new Set(marketList.map((x) => x.category))]) {
      const items = marketList.filter((x) => x.category === c && !featuredIds.has(x.id))
      if (items.length) groups.push({ key: c, title: p.categoryLabels[c] ?? c, items })
    }
    return groups
  }, [marketList, p])

  const renderPluginRow = (plugin: PluginView) => {
    const on = plugin.enabled
    return (
      <article className="plugin-row" key={plugin.id}>
        {/* 目录里没有真实图标资源：有 `icon` 就显示，没有就用首字母占位 */}
        <span className="plugin-row__icon">
          {plugin.icon
            ? <img src={plugin.icon} alt="" loading="lazy" />
            : <span className="plugin-row__initial" aria-hidden="true">{plugin.name.trim().charAt(0).toUpperCase()}</span>}
        </span>
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
          {on ? (
            // 已安装的行必须有卸载入口：只在详情弹窗里能卸，等于"装了以后找不到出口"
            <button
              className="plugin-icon-btn"
              type="button"
              aria-label={`${p.uninstall}: ${plugin.name}`}
              title={p.uninstall}
              onClick={() => toggleInstall(plugin)}
            >
              <X size={16} />
            </button>
          ) : (
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
   * 目录在服务端，这里只翻这一行：没有"新增目录项"这种操作，也就没有卸载后配置
   * 丢失的问题（重装回来配置还在）。乐观更新失败必须回滚——开关留在原状，
   * 比"看起来装上了其实没写进库"诚实。
   */
  const toggleInstall = (plugin: PluginView) => {
    const on = plugin.enabled
    setCatalog(catalog.map((item) => (item.id === plugin.id ? { ...item, enabled: !on } : item)))
    if (USE_MOCK) {
      toast[on ? 'info' : 'success'](on ? p.uninstall : p.install, `${plugin.name} ${plugin.version}`)
      return
    }
    api.plugins.update(plugin.id, { enabled: !on })
      .then(() => toast[on ? 'info' : 'success'](on ? p.uninstall : p.install, `${plugin.name} ${plugin.version}`))
      .catch((error: unknown) => {
        setCatalog(catalog) // 回滚
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

  /** 把名字折成后端认的 code：小写、非 `[a-z0-9._-]` 折成 `-`。 */
  const codeOf = (name: string) => {
    const slug = name.trim().toLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^[-.]+|[-.]+$/g, '')
    return `skill-${slug || 'imported'}`.slice(0, 64)
  }

  const closeImport = () => {
    setImportOpen(false)
    setImportText('')
    setImportFileName('')
    setEditingSkill(null)
    if (fileRef.current) fileRef.current.value = ''
  }

  /** 打开"导入"弹窗（新建）。 */
  const openImport = () => {
    setEditingSkill(null)
    setSkillEnabled(true)
    setImportText('')
    setImportFileName('')
    setImportOpen(true)
  }

  /**
   * 打开编辑：正文按需取（`GET /api/skills/{code}`）。
   *
   * <p>列表为了轻不携带正文，所以这里必须补一次请求——否则编辑框会是空的，
   * 用户一保存就把正文清空了，那是最坏的一种"功能不完善"。
   */
  const openSkillEdit = (skill: SkillView) => {
    setEditingSkill(skill)
    setSkillEnabled(skill.enabled)
    setImportFileName('')
    setImportOpen(true)
    if (skill.body) {
      setImportText(skill.body)
      return
    }
    setImportText('')
    if (USE_MOCK) return
    void api.skills.get(skill.code)
      .then((full) => {
        setImportText(full.contentMd ?? '')
        setSkillEnabled(full.enabled)
      })
      .catch((error: unknown) => toast.error(p.skillLoadFail, errMessage(error)))
  }

  const deleteSkill = (skill: SkillView) => {
    if (!window.confirm(p.skillDeleteConfirm(skill.name))) return
    const remove = () => {
      setSkills((prev) => prev.filter((s) => s.code !== skill.code))
      if (editingSkill?.code === skill.code) closeImport()
      toast.success(p.skillDeletedTitle, skill.name)
    }
    if (USE_MOCK) {
      remove()
      return
    }
    api.skills.remove(skill.code)
      .then(remove)
      .catch((error: unknown) => toast.error(p.saveFailed, errMessage(error)))
  }

  /**
   * 导入 / 保存 SKILL.md = 把正文写到服务端（`PUT /api/skills/{code}`），不是写 localStorage。
   *
   * <p>编辑已有技能时 code 固定：改名不会造出第二条——名字是展示字段，code 是身份。
   *
   * <p>正文超过 200 KB 会被后端挡下来（400/1001），这里把原话透出去：
   * 前端自己算大小容易和后端算得不一样（UTF-8 字节 vs 字符）。
   */
  const commitImport = () => {
    const parsed = parseSkillContent(importText)
    const name = parsed.name?.trim()
    if (!name) {
      toast.error(p.importFail, p.importInvalid)
      return
    }
    const version = parsed.version ?? '0.1.0'
    const description = parsed.description ?? ''
    const code = editingSkill?.code ?? codeOf(name)
    if (USE_MOCK) {
      const sizeBytes = new TextEncoder().encode(importText).length
      const item: SkillView = {
        code,
        name,
        version,
        description,
        sizeLabel: `${Math.max(1, Math.round(sizeBytes / 1024))} KB`,
        updated: new Date().toISOString(),
        body: importText,
        enabled: skillEnabled,
      }
      setSkills((prev) => [item, ...prev.filter((s) => s.code !== code)])
      closeImport()
      toast.success(editingSkill ? p.skillSavedTitle : p.importDone, `${name} ${version}`)
      return
    }
    void (async () => {
      try {
        const saved = await api.skills.save(code, {
          name,
          version,
          description,
          contentMd: importText,
          // 新建一律启用；编辑时把开关一起写回去（否则"停用"在界面上永远改不掉）
          ...(editingSkill ? { enabled: skillEnabled } : {}),
        })
        const view = fromSkill(saved)
        setSkills((prev) => [view, ...prev.filter((s) => s.code !== view.code)])
        closeImport()
        toast.success(editingSkill ? p.skillSavedTitle : p.importDone, `${name} ${view.version}`)
      } catch (error) {
        toast.error(p.importFail, errMessage(error))
      }
    })()
  }

  /* ---------- MCP ---------- */

  const openMcpCreate = () => {
    setEditing(null)
    setMcpName('')
    setMcpTransport('http')
    setMcpUrl('')
    setMcpCommand('')
    setMcpToken('')
    setMcpAllowStdio(false)
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
    setMcpAllowStdio(server.allowStdio)
    setProbe(null)
    setMcpOpen(true)
  }

  const payloadOf = (): McpServerPayload => ({
    name: mcpName.trim(),
    transport: mcpTransport,
    ...(mcpTransport === 'http' ? { url: mcpUrl.trim() } : { command: mcpCommand.trim() }),
    // 只在真的填了新令牌时才上行：空串会被后端当成"清除凭据"
    ...(mcpToken.trim() ? { authHeader: mcpToken.trim() } : {}),
    // stdio 才带这个开关；http 传上去没意义，反而会让"这条 server 被许可执行命令"变得含糊
    ...(mcpTransport === 'stdio' ? { allowStdio: mcpAllowStdio } : {}),
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
          allowStdio: mcpAllowStdio,
          lastCallAt: editing?.lastCallAt ?? null,
          lastCallTool: editing?.lastCallTool ?? null,
          lastCallOk: editing?.lastCallOk ?? null,
          lastCallError: editing?.lastCallError ?? null,
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
      errorCode: null,
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
      setProbe({ ok: false, error: errMessage(error), errorCode: null, tools: [], tookMs: 0, lastOkAt: null })
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
        <button className="btn btn-primary" type="button" onClick={openImport}>
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
                    <article className="skill-row" key={skill.code}>
                      <span className="skill-row__icon"><Package size={16} /></span>
                      <div className="skill-row__main">
                        <strong>
                          {skill.name}
                          {!skill.enabled && <span className="badge" data-tone="warning" style={{ marginLeft: 6 }}>{p.skillDisabled}</span>}
                        </strong>
                        <span>{skill.version} · {skill.sizeLabel}</span>
                      </div>
                      <p>{skill.description || p.importDropHint}</p>
                      <time>{when(skill.updated)}</time>
                      <div className="skill-row__actions">
                        <button
                          className="plugin-icon-btn"
                          type="button"
                          aria-label={`${p.skillEdit}: ${skill.name}`}
                          title={p.skillEdit}
                          onClick={() => openSkillEdit(skill)}
                        >
                          <Settings2 size={15} />
                        </button>
                        <button
                          className="plugin-icon-btn"
                          type="button"
                          aria-label={`${p.skillDelete}: ${skill.name}`}
                          title={p.skillDelete}
                          onClick={() => deleteSkill(skill)}
                        >
                          <Trash2 size={15} />
                        </button>
                      </div>
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
                      {/* 最近一次工具调用：探测通 ≠ 工具能跑，这两件事必须分开看得见 */}
                      {server.lastCallTool ? (
                        <p className={server.lastCallOk ? 'mcp-row__ok' : 'mcp-row__error'} role={server.lastCallOk ? 'status' : 'alert'}>
                          {p.mcpLastCall(server.lastCallTool, when(server.lastCallAt))} · {server.lastCallOk ? p.mcpCallOk : p.mcpCallFail}
                          {server.lastCallOk || !server.lastCallError ? '' : `：${server.lastCallError}`}
                        </p>
                      ) : (
                        <p className="mcp-row__call">{p.mcpNoCall}</p>
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
              {detail.enabled ? p.uninstall : p.install}
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
        title={editingSkill ? p.skillEditTitle : p.importTitle}
        width={620}
        onClose={closeImport}
        footer={
          <>
            {editingSkill && (
              <button className="btn btn-ghost" type="button" onClick={() => deleteSkill(editingSkill)}>
                <Trash2 size={14} /> {p.skillDelete}
              </button>
            )}
            <span style={{ flex: 1 }} />
            <button className="btn btn-ghost" type="button" onClick={closeImport}>{common.cancel}</button>
            <button className="btn btn-primary" type="button" disabled={!importText.trim()} onClick={commitImport}>
              <FileUp size={15} /> {editingSkill ? p.skillSave : p.importBtn}
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
          {/* 停用开关只在编辑时给：新建的技能一律先启用，再决定要不要关掉 */}
          {editingSkill && (
            <label htmlFor="skill-enabled" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13 }}>
              <input
                id="skill-enabled"
                type="checkbox"
                checked={skillEnabled}
                onChange={(e) => setSkillEnabled(e.target.checked)}
              />
              <span>{p.skillEnabledLabel}</span>
            </label>
          )}
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
              {/* 子进程的许可单独一个勾：不勾就一条命令都不跑，探测也只会说"没被允许" */}
              <label htmlFor="mcp-allow-stdio" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13 }}>
                <input
                  id="mcp-allow-stdio"
                  type="checkbox"
                  checked={mcpAllowStdio}
                  onChange={(e) => setMcpAllowStdio(e.target.checked)}
                />
                <span>{p.mcpAllowStdio}</span>
              </label>
              <p className="hint">{p.mcpAllowStdioHint}</p>
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
              : <p className="mcp-probe mcp-probe--fail" role="alert">{p.mcpProbeFail}{probe.errorCode ? `（${probe.errorCode}）` : ''}：{probe.error ?? '—'}</p>
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
