import { api } from './client'

/**
 * 文档关联与相关推荐（R11 补齐 —— 后端早已提供，前端此前未接）。
 *
 * ```text
 * GET /knowledge-bases/{kbId}/documents/{docId}/links    → { outlinks, backlinks, dangling }
 * GET /knowledge-bases/{kbId}/documents/{docId}/related  → [{ documentId, title, sharedTags, linked, reasons, score }]
 * ```
 */

/** 一条关联边（出链或反向链接）。 */
export interface DocumentLinkView {
  linkId: string
  /** WIKILINK / MARKDOWN_LINK / EMBED */
  linkType: string
  /** 原始目标（笔记名或相对路径）——**未解析时也要显示它**，那正是"待创建笔记"的名字 */
  target: string
  display: string | null
  /** 解析到的文档；null = 暂未解析（目标文档还没导入） */
  resolvedDocumentId: string | null
  resolvedTitle: string | null
  /** http(s) 外链：不参与内部关联图 */
  external: boolean
  startOffset: number
}

export interface DocumentLinks {
  outlinks: DocumentLinkView[]
  backlinks: DocumentLinkView[]
  /** 指向尚不存在文档的引用数 —— 知识库最有用的"待创建笔记"清单 */
  dangling: number
}

export interface RelatedDocument {
  documentId: string
  title: string | null
  sharedTags: number
  linked: boolean
  /** 为什么相关（"互相链接"、"共同标签: java"） */
  reasons: string[]
  /** 排序分（后端给，仅供参考，界面默认不展示——分数属于技术细节） */
  score: number
}

export function getDocumentLinks(kbId: string, documentId: string, signal?: AbortSignal) {
  return api.get<DocumentLinks>(`/knowledge-bases/${kbId}/documents/${documentId}/links`, {
    ...(signal ? { signal } : {})
  })
}

export function getRelatedDocuments(
  kbId: string,
  documentId: string,
  limit = 5,
  signal?: AbortSignal
) {
  return api.get<RelatedDocument[]>(`/knowledge-bases/${kbId}/documents/${documentId}/related`, {
    query: { limit },
    ...(signal ? { signal } : {})
  })
}
