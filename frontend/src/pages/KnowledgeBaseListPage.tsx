import { useMemo, useState } from 'react'
import { FileText, MoreHorizontal, Plus, Search, SearchX } from 'lucide-react'
import PixelCard from '@/bits/PixelCard/PixelCard'
import { Skeleton, StateEmpty } from '@/components/Skeleton'
import { useParticleColors } from '@/lib/palette'
import { KBS, type KbItem } from '@/data'
import { api, USE_MOCK, errMessage } from '@/lib/api'
import { fetchKbs, useLoad } from '@/lib/backendData'
import { toast } from '@/lib/toast'

interface Props {
  onOpen: (kb: KbItem) => void
}

export default function KnowledgeBaseListPage({ onOpen }: Props) {
  /* 粒子色跟随当前配色与明暗（切换配色立即生效），不再按每张卡的"性格色" */
  const particleColors = useParticleColors()
  const [query, setQuery] = useState('')
  /* 真实实现：加载态由请求驱动；mock 模式下直接展示种子数据 */
  const { data, loading: remoteLoading, error, reload } = useLoad(() => fetchKbs())
  const rows = USE_MOCK ? KBS : (data ?? [])
  const loading = USE_MOCK ? false : remoteLoading
  const [creating, setCreating] = useState(false)

  const q = query.trim().toLowerCase()
  const list = useMemo(
    () => (q ? rows.filter((k) => `${k.name}${k.desc}`.toLowerCase().includes(q)) : rows),
    [q, rows],
  )

  const createKb = async () => {
    if (USE_MOCK) return
    const name = window.prompt('新知识库的名称')?.trim()
    if (!name) return
    setCreating(true)
    try {
      await api.knowledge.create({ name })
      toast.success('知识库已创建', name)
      reload()
    } catch (e) {
      toast.error('创建失败', errMessage(e))
    } finally {
      setCreating(false)
    }
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <p className="eyebrow" style={{ margin: 0 }}>知识库</p>
          <h1 className="page-title">我的知识库</h1>
          <p className="page-sub">把散落的笔记变成可检索、可对话的知识资产</p>
        </div>
        <div style={{ display: 'flex', gap: 10 }}>
          <div style={{ position: 'relative' }}>
            <Search size={15} style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-3)' }} />
            <input aria-label="搜索知识库"
              className="input"
              placeholder="搜索知识库"
              style={{ width: 220, paddingLeft: 36 }}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>
          <button className="btn btn-primary" type="button" onClick={createKb} disabled={creating}>
            <Plus size={16} /> {creating ? '创建中…' : '新建知识库'}
          </button>
        </div>
      </div>

      {error && (
        <div className="panel" style={{ marginBottom: 12 }}>
          <StateEmpty
            icon={<SearchX size={20} />}
            title="知识库加载失败"
            desc={error}
            action={<button className="btn btn-sm" type="button" onClick={reload}>重试</button>}
          />
        </div>
      )}

      {loading && (
        <div className="kb-grid">
          {Array.from({ length: 6 }, (_, i) => (
            <div key={i} className="panel" style={{ padding: 18, height: 168, display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
              <Skeleton w={86} h={20} radius={999} />
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <Skeleton w={`${64 - i * 4}%`} h={15} />
                <Skeleton w={`${86 - i * 5}%`} h={11} />
              </div>
              <Skeleton w={120} h={11} />
            </div>
          ))}
        </div>
      )}

      {!loading && list.length === 0 && (
        <div className="panel">
          <StateEmpty
            icon={<SearchX size={20} />}
            title={q ? `没有匹配「${query}」的知识库` : '还没有知识库'}
            desc={q ? '换个关键词，或直接新建一个知识库开始沉淀。' : '新建第一个知识库，然后把笔记导入进来。'}
            action={<button className="btn btn-primary btn-sm" type="button" onClick={createKb}><Plus size={14} /> 新建知识库</button>}
          />
        </div>
      )}

      {!loading && list.length > 0 && <div className="kb-grid">
        {list.map((kb) => (
          <PixelCard key={kb.id} gap={8} speed={45} colors={particleColors}>
            <div className="kb-inner" role="button" tabIndex={0} onClick={() => onOpen(kb)} onKeyDown={(e) => { if (e.key === 'Enter') onOpen(kb) }} style={{ cursor: 'pointer' }}>
              <div>
                <span className="badge" style={{ position: 'absolute', top: 14, left: 18 }}>
                  <FileText size={11} /> {kb.docs} 篇文档
                </span>
              </div>
              <div>
                <h2 className="name">{kb.name}</h2>
                <p className="desc">{kb.desc || '（无描述）'}</p>
              </div>
              <div className="meta">
                <span className="dot" style={{ background: 'var(--primary)' }} />
                更新于 {kb.updated || '—'}
              </div>
              <MoreHorizontal size={16} className="more" style={{ color: 'var(--text-3)' }} />
            </div>
          </PixelCard>
        ))}
      </div>}
    </div>
  )
}