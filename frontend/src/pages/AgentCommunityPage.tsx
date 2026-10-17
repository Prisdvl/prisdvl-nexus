import { useMemo, useState, type FormEvent } from 'react'
import {
  ArrowUpRight, BarChart3, Bot, Briefcase, Check, Code2, Download, Eye, EyeOff, FileText,
  FlaskConical, GraduationCap, Heart, MessageSquare, PenLine, Pencil, Plus, Search,
  Settings2, Star, Trash2, User, X, Zap,
} from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import RubberSegment from '@/bits/RubberSegment/RubberSegment'
import Stepper, { Step } from '@/bits/Stepper/Stepper'
import Modal from '@/components/Modal'
import { StateEmpty } from '@/components/Skeleton'
import { toast } from '@/lib/toast'
import { appCopy } from '@/lib/appCopy'
import { pagesCopy } from '@/lib/pagesCopy'
import { useLocale } from '@/lib/locale'
import {
  COMMUNITY_AGENTS, COMMUNITY_CATEGORIES, readMyAgents, writeMyAgents,
  type CommunityAgent, type CommunityCategory, type CommunityStatus,
} from '@/lib/community'
import '@/styles/plugins.css'

type Tab = 'discover' | 'mine'
type Sort = 'popular' | 'newest' | 'rating'
type DetailTab = 'overview' | 'tools' | 'reviews'

const CATEGORY_ICONS: Record<CommunityCategory, typeof Bot> = {
  writing: PenLine,
  coding: Code2,
  research: FlaskConical,
  knowledge: Bot,
  business: Briefcase,
  education: GraduationCap,
  data: BarChart3,
}

const parseCount = (v: string) => {
  const n = Number.parseFloat(v)
  return v.endsWith('k') ? n * 1000 : n
}

const initialsOf = (name: string) => name.trim().slice(0, 2).toUpperCase()

export default function AgentCommunityPage() {
  const { locale } = useLocale()
  const common = appCopy(locale).common
  const c = pagesCopy(locale).community
  const reduce = useReducedMotion()
  const [tab, setTab] = useState<Tab>('discover')
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState<CommunityCategory | 'all'>('all')
  const [sort, setSort] = useState<Sort>('popular')
  const [detail, setDetail] = useState<CommunityAgent | null>(null)
  const [detailTab, setDetailTab] = useState<DetailTab>('overview')
  const [reviewDraft, setReviewDraft] = useState('')
  const [myAgents, setMyAgents] = useState<CommunityAgent[]>(() => readMyAgents())
  const [usedIds, setUsedIds] = useState<string[]>(() => {
    try {
      return JSON.parse(localStorage.getItem('nx-community-used') ?? '[]') as string[]
    } catch {
      return []
    }
  })
  const [likedIds, setLikedIds] = useState<string[]>(() => {
    try {
      return JSON.parse(localStorage.getItem('nx-community-liked') ?? '[]') as string[]
    } catch {
      return []
    }
  })
  const [publishOpen, setPublishOpen] = useState(false)
  const [wizStep, setWizStep] = useState(1)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [form, setForm] = useState({
    name: '',
    desc: '',
    category: 'writing' as CommunityCategory,
    tags: '',
    model: 'deepseek-v4-flash',
    version: '1.0.0',
    visibility: 'draft' as CommunityStatus,
    tools: [] as string[],
    readme: '',
  })

  const all = useMemo(() => [...COMMUNITY_AGENTS, ...myAgents], [myAgents])
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    const list = all.filter((a) => {
      if (category !== 'all' && a.category !== category) return false
      if (!q) return true
      return [a.name, a.author, a.description, ...a.tags].join(' ').toLowerCase().includes(q)
    })
    return [...list].sort((a, b) => {
      if (sort === 'popular') return parseCount(b.downloads) - parseCount(a.downloads)
      if (sort === 'rating') return b.rating - a.rating
      return new Date(b.updated).getTime() - new Date(a.updated).getTime()
    })
  }, [all, category, query, sort])
  const featured = useMemo(() => filtered.find((a) => a.verified) ?? filtered[0], [filtered])

  const persistUsed = (ids: string[]) => {
    setUsedIds(ids)
    localStorage.setItem('nx-community-used', JSON.stringify(ids))
  }
  const persistLiked = (ids: string[]) => {
    setLikedIds(ids)
    localStorage.setItem('nx-community-liked', JSON.stringify(ids))
  }

  const toggleUsed = (agent: CommunityAgent) => {
    const on = usedIds.includes(agent.id)
    persistUsed(on ? usedIds.filter((id) => id !== agent.id) : [...usedIds, agent.id])
    toast.success(on ? c.installed : c.use, agent.name)
  }

  const toggleLike = (agent: CommunityAgent) => {
    const on = likedIds.includes(agent.id)
    persistLiked(on ? likedIds.filter((id) => id !== agent.id) : [...likedIds, agent.id])
    toast[on ? 'info' : 'success'](agent.name, on ? common.remove : c.likes)
  }

  const openPublish = (agent?: CommunityAgent) => {
    setEditingId(agent?.id ?? null)
    setForm({
      name: agent?.name ?? '',
      desc: agent?.description ?? '',
      category: agent?.category ?? 'writing',
      tags: agent?.tags.join(', ') ?? '',
      model: agent?.model ?? 'deepseek-v4-flash',
      version: agent?.version ?? '1.0.0',
      visibility: agent?.status ?? 'draft',
      tools: agent?.tools ?? [],
      readme: agent?.readme ?? '',
    })
    setWizStep(1)
    setPublishOpen(true)
  }

  const commitPublish = () => {
    const name = form.name.trim()
    const tags = form.tags.split(',').map((t) => t.trim()).filter(Boolean)
    const now = new Date().toISOString().slice(0, 10)
    if (!name) {
      toast.error(c.publishTitle, c.namePh)
      return
    }
    if (editingId) {
      const next = myAgents.map((a) => a.id === editingId
        ? {
            ...a,
            name,
            description: form.desc.trim(),
            category: form.category,
            tags,
            model: form.model,
            version: form.version.trim(),
            status: form.visibility,
            tools: form.tools,
            readme: form.readme.trim(),
            updated: now,
          }
        : a)
      setMyAgents(next)
      writeMyAgents(next)
      toast.success(c.publishDoneTitle, name)
    } else {
      const item: CommunityAgent = {
        id: `mine-${Date.now()}`,
        name,
        description: form.desc.trim(),
        author: c.authorYou,
        avatar: initialsOf(name),
        category: form.category,
        tags,
        model: form.model,
        version: form.version.trim(),
        tools: form.tools,
        downloads: '0',
        likes: '0',
        rating: 0,
        reviews: 0,
        updated: now,
        status: form.visibility,
        readme: form.readme.trim(),
      }
      const next = [item, ...myAgents]
      setMyAgents(next)
      writeMyAgents(next)
      toast.success(c.publishDoneTitle, name)
    }
    setPublishOpen(false)
    setEditingId(null)
  }

  const removeMine = (agent: CommunityAgent) => {
    if (!window.confirm(c.deleteConfirm(agent.name))) return
    const next = myAgents.filter((a) => a.id !== agent.id)
    setMyAgents(next)
    writeMyAgents(next)
    toast.success(c.deletedTitle, agent.name)
  }

  const setField = <K extends keyof typeof form>(key: K, value: (typeof form)[K]) => {
    setForm((f) => ({ ...f, [key]: value }))
  }

  const toggleTool = (tool: string) => {
    setField('tools', form.tools.includes(tool) ? form.tools.filter((t) => t !== tool) : [...form.tools, tool])
  }

  const submitReview = (e: FormEvent) => {
    e.preventDefault()
    if (!reviewDraft.trim()) return
    setReviewDraft('')
    toast.success(c.reviewDoneTitle, c.reviewDoneBody)
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <p className="eyebrow" style={{ margin: 0 }}>{c.eyebrow}</p>
          <h1 className="page-title">{c.title}</h1>
          <p className="page-sub">{c.sub}</p>
        </div>
        <button className="btn btn-primary" type="button" onClick={() => openPublish()}>
          <Plus size={16} /> {c.publish}
        </button>
      </div>

      <motion.div
        className="community-hero-wrap"
        initial={false}
        animate={{
          height: tab === 'discover' && featured ? 'auto' : 0,
          opacity: tab === 'discover' && featured ? 1 : 0,
          marginBottom: tab === 'discover' && featured ? 20 : 0,
        }}
        transition={reduce ? { duration: 0 } : { duration: 0.3, ease: [0.23, 1, 0.32, 1] }}
      >
        {featured && (
        <section className="community-hero">
          <div className="community-hero__text">
            <span className="badge" data-tone="accent"><Star size={12} /> {c.featuredLabel}</span>
            <h2>{featured.name}</h2>
            <p>{featured.description}</p>
            <div className="market-meta">
              <span><Download size={13} /> {featured.downloads}</span>
              <span><Heart size={13} /> {featured.likes}</span>
              <span><Star size={13} /> {featured.rating}</span>
              <span>{c.byAuthor(featured.author)}</span>
            </div>
            <div className="community-hero__actions">
              <button className="btn btn-primary" type="button" onClick={() => toggleUsed(featured)}>
                {usedIds.includes(featured.id) ? <><Check size={15} /> {c.installed}</> : <><Zap size={15} /> {c.use}</>}
              </button>
              <button className="btn btn-ghost" type="button" onClick={() => { setDetail(featured); setDetailTab('overview') }}>
                {c.detail} <ArrowUpRight size={15} />
              </button>
            </div>
          </div>
          <div className="community-hero__visual" aria-hidden="true">
            <span className="community-avatar community-avatar--hero">{initialsOf(featured.name)}</span>
            <div className="community-hero__ring" />
            <span className="chip">v{featured.version}</span>
            {featured.tools.slice(0, 3).map((tool) => <span className="chip" key={tool}>{tool}</span>)}
          </div>
        </section>
        )}
      </motion.div>

      <div className="market-toolbar">
        <div className="market-tabs" role="tablist" aria-label={c.title}>
          {([
            { key: 'discover', label: c.tabDiscover },
            { key: 'mine', label: c.tabMine },
          ] as const).map((t) => (
            <button
              key={t.key}
              type="button"
              role="tab"
              aria-selected={tab === t.key}
              className={`market-tab${tab === t.key ? ' is-on' : ''}`}
              onClick={() => setTab(t.key)}
            >
              {tab === t.key && (
                <motion.span
                  className="market-tab__bg"
                  layoutId="community-tab-bg"
                  transition={reduce ? { duration: 0 } : { type: 'spring', stiffness: 420, damping: 34, mass: 0.7 }}
                />
              )}
              <span className="market-tab__label">{t.label}</span>
            </button>
          ))}
        </div>
        <div className="market-search">
          <Search size={15} aria-hidden="true" />
          <input
            className="input"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={c.searchPh}
            aria-label={c.searchAria}
          />
          {query && (
            <button className="market-search__clear" type="button" aria-label={common.close} onClick={() => setQuery('')}>
              <X size={14} />
            </button>
          )}
        </div>
      </div>

      <motion.div
        layout
        transition={reduce ? { duration: 0 } : { layout: { duration: 0.32, ease: [0.23, 1, 0.32, 1] } }}
      >
      <motion.div
        key={tab}
        initial={reduce ? undefined : { opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={reduce ? { duration: 0 } : { duration: 0.24, ease: [0.23, 1, 0.32, 1] }}
      >
      {tab === 'discover' && (
        <>
          <div className="filter-row" role="group" aria-label={c.categoryAll}>
            {['all', ...COMMUNITY_CATEGORIES].map((cat) => (
              <button
                key={cat}
                type="button"
                className={`chip filter-chip${category === cat ? ' filter-chip--on' : ''}`}
                aria-pressed={category === cat}
                onClick={() => setCategory(cat as CommunityCategory | 'all')}
              >
                {cat === 'all' ? c.categoryAll : c.categoryLabels[cat]}
              </button>
            ))}
            <select
              className="input market-sort"
              aria-label={c.sortPopular}
              value={sort}
              onChange={(e) => setSort(e.target.value as Sort)}
            >
              <option value="popular">{c.sortPopular}</option>
              <option value="newest">{c.sortNewest}</option>
              <option value="rating">{c.sortRating}</option>
            </select>
          </div>

          {filtered.length === 0 ? (
            <StateEmpty
              icon={<Bot size={20} />}
              title={query ? c.noResults(query) : c.emptyTitle}
              desc={c.emptyDesc}
            />
          ) : (
            <div className="market-grid community-grid">
              {filtered.map((agent) => {
                const CategoryIcon = CATEGORY_ICONS[agent.category]
                return (
                <article className="market-card" key={agent.id}>
                  <div className="market-card__head">
                    <span className="community-avatar">{agent.avatar || initialsOf(agent.name)}</span>
                    <div className="market-card__title">
                      <h2>{agent.name}</h2>
                      <p>{c.byAuthor(agent.author)}</p>
                    </div>
                    {agent.verified && <span className="badge" data-tone="accent">{c.verified}</span>}
                  </div>
                  <p className="market-card__desc">{agent.description}</p>
                  <div className="market-meta">
                    <span><Download size={13} /> {agent.downloads}</span>
                    <span><Star size={13} /> {agent.rating}</span>
                    <span><MessageSquare size={13} /> {agent.reviews}</span>
                  </div>
                  <div className="meta-inline">
                    <span className="meta-inline__item"><CategoryIcon size={13} aria-hidden="true" /> {c.categoryLabels[agent.category]}</span>
                    {agent.tags.slice(0, 3).map((tag) => <span className="meta-inline__item" key={tag}>{tag}</span>)}
                  </div>
                  <div className="market-card__foot market-card__foot--spread">
                    <button
                      className="btn btn-ghost btn-sm"
                      type="button"
                      aria-label={c.likes}
                      aria-pressed={likedIds.includes(agent.id)}
                      onClick={() => toggleLike(agent)}
                    >
                      <Heart size={13} fill={likedIds.includes(agent.id) ? 'currentColor' : 'none'} />
                    </button>
                    <button className="btn btn-ghost btn-sm" type="button" onClick={() => { setDetail(agent); setDetailTab('overview') }}>
                      {c.detail}
                    </button>
                    <button
                      className={`btn btn-sm ${usedIds.includes(agent.id) ? 'btn-ghost' : 'btn-primary'}`}
                      type="button"
                      onClick={() => toggleUsed(agent)}
                    >
                      {usedIds.includes(agent.id) ? <><Check size={13} /> {c.installed}</> : c.use}
                    </button>
                  </div>
                </article>
                )
              })}
            </div>
          )}
        </>
      )}

      {tab === 'mine' && (
        <div className="market-panel">
          {myAgents.length === 0 ? (
            <StateEmpty
              icon={<User size={20} />}
              title={c.myEmptyTitle}
              desc={c.myEmptyDesc}
              action={
                <button className="btn btn-primary btn-sm" type="button" onClick={() => openPublish()}>
                  <Plus size={14} /> {c.publish}
                </button>
              }
            />
          ) : (
            <div className="mine-list">
              {myAgents.map((agent) => (
                <article className="mine-row" key={agent.id}>
                  <span className="community-avatar">{agent.avatar || initialsOf(agent.name)}</span>
                  <div className="mine-row__main">
                    <div className="mine-row__name">
                      <strong>{agent.name}</strong>
                      <span className="badge" data-tone={agent.status === 'published' ? 'success' : agent.status === 'draft' ? 'warning' : undefined}>
                        {agent.status === 'published' ? c.statusPublished : agent.status === 'draft' ? c.statusDraft : c.statusUnlisted}
                      </span>
                    </div>
                    <p>{agent.description}</p>
                    <div className="market-meta">
                      <span><Download size={13} /> {agent.downloads}</span>
                      <span><Heart size={13} /> {agent.likes}</span>
                      <span><Star size={13} /> {agent.rating}</span>
                    </div>
                  </div>
                  <div className="mcp-row__actions">
                    <button className="btn btn-ghost btn-sm" type="button" onClick={() => openPublish(agent)}>
                      <Pencil size={13} /> {c.edit}
                    </button>
                    <button
                      className="btn btn-ghost btn-sm"
                      type="button"
                      onClick={() => {
                        const next = myAgents.map((a) => a.id === agent.id ? { ...a, status: a.status === 'published' ? ('draft' as CommunityStatus) : ('published' as CommunityStatus) } : a)
                        setMyAgents(next)
                        writeMyAgents(next)
                        toast.success(agent.status === 'published' ? c.statusDraft : c.statusPublished, agent.name)
                      }}
                    >
                      {agent.status === 'published' ? <EyeOff size={13} /> : <Eye size={13} />}
                      {agent.status === 'published' ? c.unpublish : c.republish}
                    </button>
                    <button className="btn btn-ghost btn-sm" type="button" onClick={() => removeMine(agent)}>
                      <Trash2 size={13} /> {c.delete}
                    </button>
                  </div>
                </article>
              ))}
            </div>
          )}
        </div>
      )}
      </motion.div>
      </motion.div>

      <Modal
        open={detail !== null}
        title={detail?.name ?? c.detail}
        width={720}
        onClose={() => setDetail(null)}
        footer={detail && (
          <>
            <span style={{ flex: 1 }} />
            <button className="btn btn-ghost" type="button" onClick={() => setDetail(null)}>{common.close}</button>
            <button className="btn btn-primary" type="button" onClick={() => { toggleUsed(detail); setDetail(null) }}>
              {usedIds.includes(detail.id) ? c.installed : c.use}
            </button>
          </>
        )}
      >
        {detail && (
          <div className="agent-detail">
            <div className="plugin-detail__hero">
              <span className="community-avatar community-avatar--lg">{detail.avatar || initialsOf(detail.name)}</span>
              <div>
                <h3>{detail.name} <span className="chip">v{detail.version}</span></h3>
                <p>{c.byAuthor(detail.author)} · {c.updated} {detail.updated}</p>
                <div className="market-meta">
                  <span><Download size={13} /> {detail.downloads}</span>
                  <span><Heart size={13} /> {detail.likes}</span>
                  <span><Star size={13} /> {detail.rating} ({detail.reviews})</span>
                </div>
              </div>
            </div>
            <RubberSegment
              className="agent-detail__tabs"
              items={[
                { value: 'overview', label: c.overviewTitle },
                { value: 'tools', label: c.toolsTitle },
                { value: 'reviews', label: c.reviewsTitle },
              ]}
              value={detailTab}
              onChange={(v) => setDetailTab(v as DetailTab)}
              size="sm"
              radius={10}
              aria-label={detail.name}
            />
            <motion.div
              key={detailTab}
              initial={reduce ? undefined : { opacity: 0, y: 4 }}
              animate={{ opacity: 1, y: 0 }}
              transition={reduce ? { duration: 0 } : { duration: 0.18, ease: [0.23, 1, 0.32, 1] }}
            >
            {detailTab === 'overview' && (
              <div className="agent-detail__body">
                <h4>{c.readmeTitle}</h4>
                <p className="agent-detail__readme">{detail.readme}</p>
                <div className="meta-inline">
                  <span className="meta-inline__item">
                    {(() => { const CategoryIcon = CATEGORY_ICONS[detail.category]; return <CategoryIcon size={13} aria-hidden="true" /> })()}
                    {' '}{c.categoryLabels[detail.category]}
                  </span>
                  {detail.tags.map((tag) => <span className="meta-inline__item" key={tag}>{tag}</span>)}
                </div>
                <div className="field">
                  <label>{c.modelLabel}</label>
                  <code className="chip">{detail.model}</code>
                </div>
              </div>
            )}
            {detailTab === 'tools' && (
              <div className="agent-detail__body">
                <h4>{c.toolsTitle}</h4>
                <div className="meta-list">
                  {detail.tools.map((tool) => (
                    <span className="meta-list__row" key={tool}><Settings2 size={14} aria-hidden="true" /> {tool}</span>
                  ))}
                </div>
                <p className="hint">{c.toolsTitle}</p>
              </div>
            )}
            {detailTab === 'reviews' && (
              <div className="agent-detail__body">
                <div className="review-list">
                  {c.sampleReviewers.map((reviewer, i) => (
                    <article className="review-row" key={`${reviewer}-${i}`}>
                      <div className="review-row__head">
                        <strong>{reviewer}</strong>
                        <span>{c.sampleReviewMeta[i]}</span>
                        <span className="market-meta"><Star size={12} /> {detail.rating}</span>
                      </div>
                      <p>{c.sampleReviewTexts[i]}</p>
                    </article>
                  ))}
                  {detail.reviews === 0 && <p className="hint">{c.noReviews}</p>}
                </div>
                <form className="review-compose" onSubmit={submitReview}>
                  <textarea
                    className="input"
                    rows={3}
                    value={reviewDraft}
                    onChange={(e) => setReviewDraft(e.target.value)}
                    placeholder={c.writeReview}
                    aria-label={c.writeReview}
                  />
                  <button className="btn btn-primary btn-sm" type="submit" disabled={!reviewDraft.trim()}>
                    {c.sendReview}
                  </button>
                </form>
              </div>
            )}
            </motion.div>
          </div>
        )}
      </Modal>

      <Modal
        open={publishOpen}
        title={c.publishTitle}
        width={720}
        onClose={() => setPublishOpen(false)}
        shouldBlockClose={() => false}
      >
        <p className="page-sub" style={{ marginBottom: 14 }}>{c.publishSub}</p>
        <Stepper
          initialStep={wizStep}
          onStepChange={setWizStep}
          onFinalStepCompleted={commitPublish}
          backButtonText={c.back}
          nextButtonText={c.next}
          finalButtonText={c.create}
          contentClassName="publish-content"
        >
          <Step>
            <div className="form-stack">
              <div className="field">
                <label htmlFor="cm-name">{c.nameLabel}</label>
                <input id="cm-name" className="input" autoFocus value={form.name} placeholder={c.namePh} aria-label={c.nameAria} onChange={(e) => setField('name', e.target.value)} />
              </div>
              <div className="field">
                <label htmlFor="cm-desc">{c.descLabel}</label>
                <textarea id="cm-desc" className="input" rows={3} value={form.desc} placeholder={c.descPh} aria-label={c.descAria} onChange={(e) => setField('desc', e.target.value)} />
              </div>
              <div className="field">
                <label htmlFor="cm-category">{c.categoryLabel}</label>
                <select id="cm-category" className="input" value={form.category} onChange={(e) => setField('category', e.target.value as CommunityCategory)}>
                  {COMMUNITY_CATEGORIES.map((cat) => <option key={cat} value={cat}>{c.categoryLabels[cat]}</option>)}
                </select>
              </div>
              <div className="field">
                <label htmlFor="cm-tags">{c.tagsLabel}</label>
                <input id="cm-tags" className="input" value={form.tags} placeholder={c.tagsPh} aria-label={c.tagsAria} onChange={(e) => setField('tags', e.target.value)} />
              </div>
            </div>
          </Step>
          <Step>
            <div className="form-stack">
              <div className="field">
                <label htmlFor="cm-model">{c.modelLabel}</label>
                <select id="cm-model" className="input" value={form.model} onChange={(e) => setField('model', e.target.value)}>
                  <option>deepseek-v4-flash</option>
                  <option>glm-5.3</option>
                  <option>kimi-k2.6</option>
                  <option>auto</option>
                </select>
              </div>
              <div className="field">
                <label>{c.toolsLabel}</label>
                <div className="tags">
                  {['knowledge_search', 'web_fetch', 'calculator', 'code_runner', 'spreadsheet'].map((tool) => (
                    <button
                      key={tool}
                      type="button"
                      className="chip wizard-chip"
                      data-on={form.tools.includes(tool) ? '' : undefined}
                      onClick={() => toggleTool(tool)}
                    >
                      <Settings2 size={13} /> {tool}
                    </button>
                  ))}
                </div>
              </div>
              <div className="field">
                <label htmlFor="cm-readme">{c.readmeLabel}</label>
                <textarea id="cm-readme" className="input" rows={7} value={form.readme} placeholder={c.readmePh} onChange={(e) => setField('readme', e.target.value)} />
              </div>
            </div>
          </Step>
          <Step>
            <div className="form-stack">
              <div className="field">
                <label htmlFor="cm-version">{c.versionLabel}</label>
                <input id="cm-version" className="input" value={form.version} onChange={(e) => setField('version', e.target.value)} />
              </div>
              <div className="field">
                <label>{c.visibilityLabel}</label>
                <div className="visibility-grid">
                  {([
                    { value: 'published', label: c.visibilityPublic, hint: c.visibilityPublicHint, icon: Eye },
                    { value: 'draft', label: c.visibilityDraft, hint: c.visibilityDraftHint, icon: FileText },
                    { value: 'unlisted', label: c.visibilityUnlisted, hint: c.visibilityUnlistedHint, icon: EyeOff },
                  ] as const).map((option) => (
                    <button
                      key={option.value}
                      type="button"
                      className={`visibility-card${form.visibility === option.value ? ' visibility-card--on' : ''}`}
                      aria-pressed={form.visibility === option.value}
                      onClick={() => setField('visibility', option.value)}
                    >
                      <option.icon size={16} />
                      <strong>{option.label}</strong>
                      <span>{option.hint}</span>
                    </button>
                  ))}
                </div>
              </div>
            </div>
          </Step>
        </Stepper>
      </Modal>
    </div>
  )
}
