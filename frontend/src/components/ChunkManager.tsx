import { useEffect, useMemo, useState } from 'react'
import { ArrowLeft, CheckSquare, Eye, Pencil, RefreshCw, Square, Trash2, VectorSquare } from 'lucide-react'
import { StateEmpty } from '@/components/Skeleton'
import { toast } from '@/lib/toast'
import { api, USE_MOCK, errMessage, type ChunkView } from '@/lib/api'
import type { DocItem } from '@/data'
import '@/styles/kb-manage.css'

interface Props {
  kbId: string
  doc: DocItem
  kbName: string
  onBack: () => void
}

type Status = 'all' | 'vector' | 'es'

const MOCK_CONTENT = [
  '程序计数器、虚拟机栈、本地方法栈是线程私有的运行时数据区，堆与方法区由所有线程共享。',
  '## 索引结构\n\n非叶子节点只存键值与子节点指针，单页可容纳更多键，树高更低；叶子节点按主键顺序串成双向链表。',
  '![架构图](assets/architecture.png)\n\n检索链路：问题 → 向量化 → 混合检索 → 重排 → 生成回答。',
  'synchronized 的锁升级路径：偏向锁 → 轻量级锁 → 重量级锁；升级后不可逆。',
  'RDB 是周期性快照，恢复快、文件小；AOF 记录每条写命令，数据更完整但文件更大。',
  '限流器实现要点：令牌桶平滑放行、漏桶稳定输出、滑动窗口统计精确但内存开销更高。',
]

const mockChunks = (doc: DocItem): ChunkView[] => MOCK_CONTENT.map((content, i) => ({
  id: `${doc.id}-c${i}`,
  chunkIndex: i,
  chunkType: i === 2 ? 'IMAGE' : 'TEXT',
  headingPath: i === 1 ? '索引结构' : null,
  content,
  tokenCount: Math.round(content.length / 1.6),
  charCount: content.length,
  vectorStatus: i === 4 ? 'PENDING' : 'DONE',
  esStatus: 'DONE',
}))

export default function ChunkManager({ kbId, doc, kbName, onBack }: Props) {
  const [chunks, setChunks] = useState<ChunkView[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [status, setStatus] = useState<Status>('all')
  const [selected, setSelected] = useState<string[]>([])
  const [enabled, setEnabled] = useState<Record<string, boolean>>({})

  const load = () => {
    setLoading(true)
    setError('')
    if (USE_MOCK) {
      const rows = mockChunks(doc)
      setChunks(rows)
      setEnabled(Object.fromEntries(rows.map((c) => [c.id, c.vectorStatus !== 'PENDING'])))
      setLoading(false)
      return
    }
    api.documents.chunks(kbId, doc.id)
      .then((rows) => {
        setChunks(rows)
        setEnabled(Object.fromEntries(rows.map((c) => [c.id, c.vectorStatus === 'DONE'])))
      })
      .catch((e) => setError(errMessage(e)))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [kbId, doc.id]) // eslint-disable-line react-hooks/exhaustive-deps

  const list = useMemo(() => chunks.filter((c) => {
    if (status === 'vector') return c.vectorStatus !== 'DONE'
    if (status === 'es') return c.esStatus !== 'DONE'
    return true
  }), [chunks, status])

  const allSelected = list.length > 0 && list.every((c) => selected.includes(c.id))
  const setMany = (ids: string[], on: boolean) => setEnabled((prev) => ({ ...prev, ...Object.fromEntries(ids.map((id) => [id, on])) }))
  const bulk = (on: boolean, all = false) => {
    const ids = all ? chunks.map((c) => c.id) : selected
    if (ids.length === 0) { toast.info('请先选择分块'); return }
    setMany(ids, on)
    toast.success(on ? `已启用 ${ids.length} 个分块` : `已禁用 ${ids.length} 个分块`)
  }

  return (
    <section className="chunk-view" aria-label="分块管理">
      <div className="chunk-head">
        <button className="btn btn-ghost btn-sm" type="button" onClick={onBack}><ArrowLeft size={14} /> 返回文档</button>
        <div className="chunk-head__path">
          <b>{doc.name}</b>
          <small>知识库：{kbName} · {kbId}</small>
        </div>
        <span style={{ flex: 1 }} />
        <button className="btn btn-ghost btn-sm" type="button" onClick={() => toast.success('已提交重建向量任务', doc.name)}><VectorSquare size={14} /> 重建向量</button>
      </div>

      <div className="doc-filters">
        <select className="input" value={status} aria-label="状态筛选" onChange={(e) => setStatus(e.target.value as Status)}>
          <option value="all">全部状态</option>
          <option value="vector">向量未完成</option>
          <option value="es">ES 未完成</option>
        </select>
        <button className="btn btn-ghost btn-sm" type="button" onClick={load}><RefreshCw size={13} /> 刷新</button>
        <span style={{ flex: 1 }} />
        <div className="chunk-batch">
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => bulk(true)}>批量启用</button>
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => bulk(false)}>批量禁用</button>
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => bulk(true, true)}>全量启用</button>
          <button className="btn btn-ghost btn-sm" type="button" onClick={() => bulk(false, true)}>全量禁用</button>
        </div>
      </div>

      {loading && <p className="hint" role="status">正在加载分块…</p>}
      {error && <p className="form-error active" role="alert">{error}</p>}
      {!loading && !error && list.length === 0 && <StateEmpty icon={<VectorSquare size={20} />} title="没有匹配的分块" desc="调整状态筛选或重建向量后再查看。" />}

      {!loading && list.length > 0 && (
        <div className="panel kb-table-wrap">
          <table className="chunk-table">
            <thead>
              <tr>
                <th>
                  <button className="plugin-icon-btn" type="button" aria-label="全选" onClick={() => setSelected(allSelected ? [] : list.map((c) => c.id))}>
                    {allSelected ? <CheckSquare size={15} /> : <Square size={15} />}
                  </button>
                </th>
                <th>序号</th><th>内容预览</th><th>字符数</th><th>Token</th><th>状态</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              {list.map((c) => (
                <tr key={c.id}>
                  <td>
                    <button className="plugin-icon-btn" type="button" aria-label={`选择分块 ${c.chunkIndex}`} onClick={() => setSelected((prev) => prev.includes(c.id) ? prev.filter((x) => x !== c.id) : [...prev, c.id])}>
                      {selected.includes(c.id) ? <CheckSquare size={15} /> : <Square size={15} />}
                    </button>
                  </td>
                  <td className="chunk-num">{c.chunkIndex}</td>
                  <td>
                    <div className="chunk-content">{c.content}</div>
                    {c.headingPath && <div className="hint" style={{ marginTop: 4 }}>#{c.headingPath}</div>}
                  </td>
                  <td className="chunk-num">{c.charCount}</td>
                  <td className="chunk-num">{c.tokenCount}</td>
                  <td>
                    <button className="doc-switch" type="button" role="switch" aria-checked={enabled[c.id] ?? false} aria-label={`分块 ${c.chunkIndex} 启用`} onClick={() => setEnabled((prev) => ({ ...prev, [c.id]: !prev[c.id] }))} />
                  </td>
                  <td>
                    <div className="chunk-row-actions">
                      <button className="plugin-icon-btn" type="button" title="预览" aria-label={`预览分块 ${c.chunkIndex}`} onClick={() => toast.info('分块预览', c.content.slice(0, 60))}><Eye size={14} /></button>
                      <button className="plugin-icon-btn" type="button" title="编辑" aria-label={`编辑分块 ${c.chunkIndex}`} onClick={() => toast.info('编辑分块', '编辑能力将在后端提供 PATCH 接口后开放')}><Pencil size={14} /></button>
                      <button className="plugin-icon-btn plugin-icon-btn--danger" type="button" title="删除" aria-label={`删除分块 ${c.chunkIndex}`} onClick={() => { setChunks((prev) => prev.filter((x) => x.id !== c.id)); toast.success('分块已删除', `#${c.chunkIndex}`) }}><Trash2 size={14} /></button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
