/**
 * 切片状态汇总（知识库详情「索引状态」面板用）。
 *
 * <p>后端的终态是 {@code DONE}（`PENDING / DONE / FAILED / STALE`），不是 `READY`。
 * 这里把"哪些数字算就绪"收敛到一个函数里，免得 JSX 里各写一份字符串比较——
 * 之前就是写成 `READY`，导致面板永远显示「向量 0 · 全文 0」，看着像功能坏了。
 */
export interface ChunkStatusLike {
  vectorStatus?: string | null
  esStatus?: string | null
}

export interface ChunkStatusCounts {
  total: number
  /** 已进 pgvector 的切片数 */
  vectors: number
  /** 已进全文索引（Elasticsearch）的切片数 */
  fullText: number
  /** 还有切片在等某一侧写入（含失败后待重试的） */
  pending: number
  /** 有切片但一片都没向量化：此时向量检索恒 0 命中 */
  notVectorized: boolean
}

export const CHUNK_STATUS_DONE = 'DONE'

export function chunkStatusCounts(chunks: ChunkStatusLike[]): ChunkStatusCounts {
  const total = chunks.length
  const vectors = chunks.filter((c) => c.vectorStatus === CHUNK_STATUS_DONE).length
  const fullText = chunks.filter((c) => c.esStatus === CHUNK_STATUS_DONE).length
  const pending = chunks.filter(
    (c) => c.vectorStatus === 'PENDING' || c.esStatus === 'PENDING',
  ).length
  return { total, vectors, fullText, pending, notVectorized: total > 0 && vectors === 0 }
}
