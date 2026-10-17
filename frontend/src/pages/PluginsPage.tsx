import { useMemo, useRef, useState, type ChangeEvent } from 'react'
import { motion, useReducedMotion } from 'motion/react'
import {
  BadgeCheck, Blocks, BookOpen, Check, CircleDot, Download, FileUp, MoreHorizontal,
  Package, Plus, Puzzle, Search, Server, Settings2, Sparkles, Star, X,
} from 'lucide-react'
import Modal from '@/components/Modal'
import { StateEmpty } from '@/components/Skeleton'
import { toast } from '@/lib/toast'
import { appCopy } from '@/lib/appCopy'
import { pagesCopy } from '@/lib/pagesCopy'
import { useLocale } from '@/lib/locale'
import {
  MCP_SERVERS, PLUGIN_CATALOG, parseSkillContent,
  readInstalledPlugins, readMcpState, readSkills,
  writeInstalledPlugins, writeMcpState, writeSkills,
  type PluginCategory, type PluginMeta, type SkillPack,
} from '@/lib/plugins'
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

export default function PluginsPage() {
  const { locale } = useLocale()
  const common = appCopy(locale).common
  const p = pagesCopy(locale).plugins
  const reduce = useReducedMotion()
  const [tab, setTab] = useState<Tab>('market')
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState<PluginCategory | 'all'>('all')
  const [installed, setInstalled] = useState<string[]>(() => readInstalledPlugins())
  const [skills, setSkills] = useState<SkillPack[]>(() => readSkills())
  const [mcpState, setMcpState] = useState<Record<string, boolean>>(() => readMcpState())
  const [mcpConfigs, setMcpConfigs] = useState<Record<string, { name: string; endpoint: string }>>(() => {
    try {
      return JSON.parse(localStorage.getItem('nx-plugins-mcp-config') ?? '{}') as Record<string, { name: string; endpoint: string }>
    } catch {
      return {}
    }
  })
  const [detail, setDetail] = useState<PluginMeta | null>(null)
  const [importOpen, setImportOpen] = useState(false)
  const [importText, setImportText] = useState('')
  const [importFileName, setImportFileName] = useState('')
  const [mcpTarget, setMcpTarget] = useState<string | null>(null)
  const [mcpName, setMcpName] = useState('')
  const [mcpEndpoint, setMcpEndpoint] = useState('')
  const [mcpToken, setMcpToken] = useState('')
  const fileRef = useRef<HTMLInputElement>(null)

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
  const mcpServers = useMemo(() => MCP_SERVERS.map((s) => ({
    ...s,
    connected: mcpState[s.id] ?? s.connected,
  })), [mcpState])

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

  const persistInstalled = (ids: string[]) => {
    setInstalled(ids)
    writeInstalledPlugins(ids)
  }

  const toggleInstall = (plugin: PluginMeta) => {
    const on = installed.includes(plugin.id)
    persistInstalled(on ? installed.filter((id) => id !== plugin.id) : [...installed, plugin.id])
    toast[on ? 'info' : 'success'](on ? p.uninstall : p.install, `${plugin.name} ${plugin.version}`)
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

  const openMcp = (id: string) => {
    const server = MCP_SERVERS.find((s) => s.id === id)
    setMcpTarget(id)
    setMcpName(mcpConfigs[id]?.name ?? server?.name ?? '')
    setMcpEndpoint(mcpConfigs[id]?.endpoint ?? server?.endpoint ?? '')
    setMcpToken('')
  }

  const saveMcp = () => {
    if (!mcpTarget) return
    const nextConfigs = { ...mcpConfigs, [mcpTarget]: { name: mcpName, endpoint: mcpEndpoint } }
    setMcpConfigs(nextConfigs)
    localStorage.setItem('nx-plugins-mcp-config', JSON.stringify(nextConfigs))
    const nextState = { ...mcpState, [mcpTarget]: true }
    setMcpState(nextState)
    writeMcpState(nextState)
    setMcpTarget(null)
    toast.success(p.mcpSavedTitle, p.mcpSavedBody)
  }

  const toggleMcp = (id: string, connected: boolean) => {
    const next = { ...mcpState, [id]: !connected }
    setMcpState(next)
    writeMcpState(next)
    toast.success(!connected ? p.mcpConnectedTitle : p.mcpDisconnectedTitle, MCP_SERVERS.find((s) => s.id === id)?.name)
  }

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
        {tab === 'installed' && (
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

        {tab === 'market' && (
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

        {tab === 'mcp' && (
          <section className="mcp-section">
            <div className="section-head">
              <div>
                <h2 className="market-section-title">MCP</h2>
                <p className="page-sub">{p.mcpSub}</p>
              </div>
            </div>
            <div className="mcp-list">
              {mcpServers.map((server) => (
                <article className="mcp-row" key={server.id}>
                  <span className="mcp-row__icon"><Server size={18} /></span>
                  <div className="mcp-row__main">
                    <div className="mcp-row__name">
                      <strong>{mcpConfigs[server.id]?.name ?? server.name}</strong>
                      <span className="badge" data-tone={server.connected ? 'success' : 'warning'}>
                        {server.connected ? p.mcpConnected : p.mcpOffline}
                      </span>
                    </div>
                    <p>{server.description}</p>
                    <code>{mcpConfigs[server.id]?.endpoint ?? server.endpoint}</code>
                  </div>
                  <div className="mcp-row__tools">
                    <span className="meta-inline__item">{server.tools.slice(0, 3).join(' · ')}</span>
                    <span className="mcp-row__count">{p.mcpTools(server.tools.length)}</span>
                  </div>
                  <div className="mcp-row__actions">
                    <button className="btn btn-ghost btn-sm" type="button" onClick={() => openMcp(server.id)}>
                      <Settings2 size={13} /> {p.mcpConfigure}
                    </button>
                    <button
                      className={`btn ${server.connected ? 'btn-ghost' : 'btn-primary'} btn-sm`}
                      type="button"
                      onClick={() => toggleMcp(server.id, server.connected)}
                    >
                      {server.connected ? p.mcpDisconnect : p.mcpConnect}
                    </button>
                  </div>
                </article>
              ))}
            </div>
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
        open={mcpTarget !== null}
        title={p.mcpModalTitle}
        width={620}
        onClose={() => setMcpTarget(null)}
        footer={
          <>
            <span style={{ flex: 1 }} />
            <button className="btn btn-ghost" type="button" onClick={() => setMcpTarget(null)}>{common.cancel}</button>
            <button className="btn btn-primary" type="button" disabled={!mcpName.trim() || !mcpEndpoint.trim()} onClick={saveMcp}>
              {p.mcpSave}
            </button>
          </>
        }
      >
        <div className="form-stack">
          <div className="field"><label htmlFor="mcp-name">{p.mcpNameLabel}</label><input id="mcp-name" className="input" value={mcpName} autoFocus onChange={(e) => setMcpName(e.target.value)} /></div>
          <div className="field"><label htmlFor="mcp-endpoint">{p.mcpEndpointLabel}</label><input id="mcp-endpoint" className="input" value={mcpEndpoint} onChange={(e) => setMcpEndpoint(e.target.value)} /></div>
          <div className="field"><label htmlFor="mcp-token">{p.mcpTokenLabel}</label><input id="mcp-token" className="input" value={mcpToken} placeholder={p.mcpTokenPh} onChange={(e) => setMcpToken(e.target.value)} /></div>
          <div className="field">
            <label>{p.mcpToolsLabel}</label>
            <div className="meta-list">
              {MCP_SERVERS.find((s) => s.id === mcpTarget)?.tools.map((tool) => (
                <span className="meta-list__row" key={tool}><Check size={13} aria-hidden="true" /> {tool}</span>
              ))}
            </div>
            <p className="hint">{p.mcpToolsLabel}</p>
          </div>
        </div>
      </Modal>
    </div>
  )
}
