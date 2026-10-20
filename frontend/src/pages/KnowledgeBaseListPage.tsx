import { useMemo, useState, type FormEvent } from 'react'
import { motion, useReducedMotion } from 'motion/react'
import { ChevronRight, Database, FileText, Files, Pencil, Plus, RefreshCw, Search, SearchX } from 'lucide-react'
import Modal from '@/components/Modal'
import RubberSegment from '@/bits/RubberSegment/RubberSegment'
import { Skeleton, StateEmpty } from '@/components/Skeleton'
import { KBS, type KbItem } from '@/data'
import { api, USE_MOCK, errMessage } from '@/lib/api'
import { fetchKbs, useLoad } from '@/lib/backendData'
import { toast } from '@/lib/toast'
import Loc from '@/components/Loc'
import { appCopy } from '@/lib/appCopy'
import { useLocale } from '@/lib/locale'
import '@/styles/plugins.css'
import '@/styles/kb-manage.css'

interface Props {
  onOpen: (kb: KbItem) => void
}

type Filter = 'all' | 'withDocs' | 'empty'

export default function KnowledgeBaseListPage({ onOpen }: Props) {
  const reduce = useReducedMotion()
  const { locale } = useLocale()
  const t = appCopy(locale)
  const { data, loading: remoteLoading, error, reload } = useLoad(() => fetchKbs())
  const [localRows, setLocalRows] = useState<KbItem[] | null>(null)
  const rows = localRows ?? (USE_MOCK ? KBS : (data ?? []))
  const loading = USE_MOCK ? false : remoteLoading

  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<Filter>('all')
  const [createOpen, setCreateOpen] = useState(false)
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [desc, setDesc] = useState('')
  const [createError, setCreateError] = useState('')
  const [renaming, setRenaming] = useState<KbItem | null>(null)
  const [renameDraft, setRenameDraft] = useState('')
  const [renameBusy, setRenameBusy] = useState(false)

  const q = query.trim().toLowerCase()
  const list = useMemo(() => rows
    .filter((kb) => (filter === 'withDocs' ? kb.docs > 0 : filter === 'empty' ? kb.docs === 0 : true))
    .filter((kb) => (!q ? true : `${kb.name}${kb.desc}`.toLowerCase().includes(q))), [rows, filter, q])

  const totalDocs = rows.reduce((sum, kb) => sum + kb.docs, 0)
  const withDocs = rows.filter((kb) => kb.docs > 0).length

  const createKb = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const cleanName = name.trim()
    const cleanDesc = desc.trim()
    if (!cleanName) { setCreateError(t.kbList.errNameRequired); return }
    if (cleanName.length > 80) { setCreateError(t.kbList.errNameLong); return }
    if (cleanDesc.length > 512) { setCreateError(t.kbList.errDescLong); return }
    if (USE_MOCK) {
      const item: KbItem = { id: `kb-${Date.now()}`, name: cleanName, desc: cleanDesc, docs: 0, updated: '刚刚', tone: 'ocean' }
      setLocalRows([item, ...rows])
      setCreateOpen(false)
      toast.success(t.kbList.created, cleanName)
      return
    }
    setCreating(true)
    try {
      await api.knowledge.create({ name: cleanName, description: cleanDesc || undefined })
      setCreateOpen(false)
      toast.success(t.kbList.created, cleanName)
      await reload()
    } catch (e) {
      setCreateError(errMessage(e))
    } finally {
      setCreating(false)
    }
  }

  const saveRename = async () => {
    if (!renaming || renameBusy) return
    const next = renameDraft.trim()
    if (!next || next === renaming.name) { setRenaming(null); return }
    if (USE_MOCK) {
      setLocalRows(rows.map((kb) => kb.id === renaming.id ? { ...kb, name: next } : kb))
      setRenaming(null)
      toast.success(t.common.save, next)
      return
    }
    setRenameBusy(true)
    try {
      await api.knowledge.update(renaming.id, { name: next })
      await reload()
      setRenaming(null)
      toast.success(t.common.save, next)
    } catch (e) {
      toast.error(t.kbList.loadFailTitle, errMessage(e))
    } finally {
      setRenameBusy(false)
    }
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <p className="eyebrow" style={{ margin: 0 }}><Loc>{t.kbList.eyebrow}</Loc></p>
          <h1 className="page-title"><Loc>{t.kbList.title}</Loc></h1>
          <p className="page-sub"><Loc>{t.kbList.sub}</Loc></p>
        </div>
        <div className="kb-actions">
          <div className="market-search kb-actions__search">
            <Search size={15} aria-hidden="true" />
            <input className="input" aria-label={t.kbList.searchAria} placeholder={t.kbList.searchPlaceholder} value={query} onChange={(e) => setQuery(e.target.value)} />
          </div>
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => void reload()}><RefreshCw size={14} /> {locale === 'en' ? 'Refresh' : '刷新'}</button>
          <motion.button className="btn btn-primary" type="button" onClick={() => { setName(''); setDesc(''); setCreateError(''); setCreateOpen(true) }} whileHover={reduce ? undefined : { y: -1 }} whileTap={reduce ? undefined : { scale: 0.97 }}>
            <Plus size={16} /> <Loc>{t.kbList.new}</Loc>
          </motion.button>
        </div>
      </div>

      <div className="stat-grid kb-stats">
        <div className="stat-card"><div className="label"><Database size={13} /> {t.kbList.statsKbs}</div><div className="num">{rows.length}</div><div className="sub">{t.kbList.statsKbsSub}</div></div>
        <div className="stat-card accent"><div className="label"><Files size={13} /> {t.kbList.statsDocs}</div><div className="num">{totalDocs}</div><div className="sub">{t.kbList.statsDocsSub}</div></div>
        <div className="stat-card"><div className="label"><FileText size={13} /> {t.kbList.statsWithDocs}</div><div className="num">{withDocs}</div><div className="sub">{t.kbList.statsWithDocsSub}</div></div>
      </div>

      <RubberSegment
        className="kb-filters"
        size="sm"
        items={[
          { value: 'all', label: t.kbList.filterAll(rows.length) },
          { value: 'withDocs', label: t.kbList.filterWithDocs(withDocs) },
          { value: 'empty', label: t.kbList.filterEmpty(rows.length - withDocs) },
        ]}
        value={filter}
        onChange={(v) => setFilter(v as Filter)}
        aria-label={t.kbList.filterAria}
      />

      {error && <div className="kb-list-empty"><StateEmpty icon={<SearchX size={20} />} title={t.kbList.loadFailTitle} desc={error} action={<button className="btn btn-sm" type="button" onClick={() => void reload()}>{t.common.retry}</button>} /></div>}

      {loading ? (
        /* 骨架按真实表格的几何来：行高 52、图标 30、两行文字 —— 数据到达时表头与行高都不变，页面不跳 */
        <div className="panel kb-table-wrap kb-skeleton" role="status" aria-label={t.kbList.loadingAria}>
          <div className="kb-skeleton__head">
            <Skeleton w={120} h={12} />
            <Skeleton w={92} h={12} />
            <Skeleton w={56} h={12} />
            <Skeleton w={84} h={12} />
          </div>
          {Array.from({ length: 6 }, (_, i) => (
            <div className="kb-skeleton__row" key={i}>
              <Skeleton w={30} h={30} radius={8} />
              <div className="kb-skeleton__lines">
                <Skeleton w={`${46 + ((i * 7) % 20)}%`} h={13} />
                <Skeleton w={`${28 + ((i * 5) % 16)}%`} h={11} />
              </div>
              <Skeleton w={64} h={12} />
              <Skeleton w={56} h={12} />
            </div>
          ))}
        </div>
      ) : list.length === 0 && rows.length === 0 ? (
        <div className="kb-list-empty">
          <StateEmpty
            icon={<SearchX size={20} />}
            title={t.kbList.emptyTitle}
            desc={t.kbList.emptyDesc}
            action={<button className="btn btn-primary" type="button" onClick={() => setCreateOpen(true)}><Plus size={16} /> {t.kbList.new}</button>}
          />
        </div>
      ) : list.length === 0 ? (
        <div className="panel kb-filter-empty" role="status">
          <SearchX size={16} aria-hidden="true" />
          <span>
            {q
              ? (locale === 'en' ? `No knowledge base matches “${query}”.` : `没有匹配「${query}」的知识库。`)
              : (locale === 'en' ? 'No knowledge base matches the current filter.' : '没有符合当前筛选条件的知识库。')}
          </span>
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => { setQuery(''); setFilter('all') }}>
            {locale === 'en' ? 'Clear filters' : '清除筛选'}
          </button>
        </div>
      ) : (
        <div className="panel kb-table-wrap">
          <table className="kb-table">
            <thead>
              <tr><th>{t.kbList.tableName}</th><th>{t.kbList.tableModel}</th><th>{t.kbList.tableDocs}</th><th>{t.kbList.tableUpdated}</th><th aria-label="actions" /></tr>
            </thead>
            <tbody>
              {list.map((kb) => {
                return (
                  <tr key={kb.id} onClick={() => onOpen(kb)}>
                    <td>
                      <div className="kb-cell">
                        <span className="kb-cell__icon"><Database size={15} /></span>
                        <span className="kb-cell__text"><b>{kb.name}</b><small>{kb.desc || t.kbList.noDesc}</small></span>
                      </div>
                    </td>
                    <td><code className="kb-code">{kb.embeddingModel ?? '—'}</code></td>
                    <td className="kb-num">{kb.docs}</td>
                    <td className="kb-time">{kb.updated}</td>
                    <td className="kb-row-actions">
                      <button className="plugin-icon-btn" type="button" title="重命名" aria-label={`重命名 ${kb.name}`} onClick={(e) => { e.stopPropagation(); setRenaming(kb); setRenameDraft(kb.name) }}><Pencil size={14} /></button>
                      <ChevronRight size={15} className="kb-row-chev" aria-hidden="true" />
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      <Modal open={createOpen} title={t.kbList.createTitle} onClose={() => { if (!creating) setCreateOpen(false) }} shouldBlockClose={() => creating} width={480}
        footer={<><button className="btn btn-ghost" type="button" onClick={() => setCreateOpen(false)} disabled={creating}>{t.common.cancel}</button><button className="btn btn-primary" type="submit" form="create-kb-form" disabled={creating}>{creating ? t.kbList.creating : t.kbList.createSubmit}</button></>}>
        <form id="create-kb-form" className="kb-meta-form" onSubmit={createKb} noValidate>
          <div className="field">
            <label htmlFor="create-kb-name">{t.kbList.createName}</label>
            <input id="create-kb-name" className="input" autoComplete="off" maxLength={80} placeholder={t.kbList.createNamePlaceholder} value={name} onChange={(e) => { setName(e.target.value); setCreateError('') }} aria-invalid={Boolean(createError)} autoFocus />
          </div>
          <div className="field">
            <label htmlFor="create-kb-desc">{t.kbList.createDesc}</label>
            <textarea id="create-kb-desc" className="input kb-meta-form__desc" rows={4} maxLength={512} placeholder={t.kbList.createDescPlaceholder} value={desc} onChange={(e) => { setDesc(e.target.value); setCreateError('') }} />
            <div className="kb-meta-form__foot"><p className="hint" style={{ margin: 0 }}>{t.kbList.createAfterHint}</p><p className="hint" style={{ margin: 0 }}>{desc.trim().length} / 512</p></div>
          </div>
          {createError && <p className="form-error active" role="alert">{createError}</p>}
        </form>
      </Modal>

      <Modal open={renaming !== null} title="重命名知识库" width={440} onClose={() => { if (!renameBusy) setRenaming(null) }} shouldBlockClose={() => renameBusy}
        footer={<><button className="btn btn-ghost" type="button" onClick={() => setRenaming(null)} disabled={renameBusy}>{t.common.cancel}</button><button className="btn btn-primary" type="button" onClick={() => void saveRename()} disabled={renameBusy || !renameDraft.trim()}>{t.common.save}</button></>}>
        <div className="field">
          <label htmlFor="rename-kb">名称</label>
          <input id="rename-kb" className="input" autoFocus value={renameDraft} maxLength={80} onChange={(e) => setRenameDraft(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void saveRename() } }} />
        </div>
      </Modal>
    </div>
  )
}
