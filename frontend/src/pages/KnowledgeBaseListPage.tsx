import { useEffect, useState } from 'react'
import { FileText, MoreHorizontal, Plus, Search, SearchX } from 'lucide-react'
import PixelCard from '@/bits/PixelCard/PixelCard'
import { Skeleton, StateEmpty } from '@/components/Skeleton'
import { useParticleColors } from '@/lib/palette'
import { KBS, type KbItem } from '@/data'

interface Props {
  onOpen: (kb: KbItem) => void
}

export default function KnowledgeBaseListPage({ onOpen }: Props) {
  /* 粒子色跟随当前配色与明暗（切换配色立即生效），不再按每张卡的"性格色" */
  const particleColors = useParticleColors()
  const [query, setQuery] = useState('')
  /* 原型：700ms 骨架屏演示加载态（真实实现由请求状态驱动） */
  const [loading, setLoading] = useState(true)
  useEffect(() => {
    const t = setTimeout(() => setLoading(false), 700)
    return () => clearTimeout(t)
  }, [])

  const q = query.trim().toLowerCase()
  const list = q ? KBS.filter((k) => `${k.name}${k.desc}`.toLowerCase().includes(q)) : KBS

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
          <button className="btn btn-primary" type="button"><Plus size={16} /> 新建知识库</button>
        </div>
      </div>

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
            title={`没有匹配「${query}」的知识库`}
            desc="换个关键词，或直接新建一个知识库开始沉淀。"
            action={<button className="btn btn-primary btn-sm" type="button"><Plus size={14} /> 新建知识库</button>}
          />
        </div>
      )}

      {!loading && <div className="kb-grid">
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
                <p className="desc">{kb.desc}</p>
              </div>
              <div className="meta">
                <span className="dot" style={{ background: 'var(--primary)' }} />
                更新于 {kb.updated}
              </div>
              <MoreHorizontal size={16} className="more" style={{ color: 'var(--text-3)' }} />
            </div>
          </PixelCard>
        ))}
      </div>}
    </div>
  )
}