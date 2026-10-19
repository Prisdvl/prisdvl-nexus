/**
 * 会话数据源：把后端 `Conversation` 映射成 UI 的 `SessionItem`，
 * 并按决策 2 实现**消息懒加载**。
 *
 * ## 为什么要懒加载（决策 2）
 *
 * 后端会话列表**不返回消息**（实测响应里只有 `messageCount`，没有 messages 字段），
 * 消息在 `GET /api/chat/messages/{id}` 单独分页取。
 * 而原型的 `SessionItem.messages` 是**内嵌完整数组**——
 * 于是「侧栏一次拿到全部会话+消息」的模型在新后端下不成立。
 *
 * 已核实侧栏**不显示消息预览**（`Sidebar.tsx` 只渲染标题与时间），
 * 所以懒加载没有额外成本。
 *
 * ## 加载态怎么表达（关键设计）
 *
 * 不能用 `messages.length === 0` 表示"没加载"——那会把"已加载但确实是空的"
 * 和"还没加载"混成同一个状态，UI 会永远转圈。
 * 所以显式引入 `messagesLoaded` 标记：
 *   false → 没请求过 → UI 显示骨架/加载中
 *   true  → 请求完成（哪怕是空数组）→ UI 显示真实内容或空态
 *
 * ## id 类型
 *
 * 后端雪花 ID 是字符串（实测 `"2108423642637959170"`），
 * 这里**全程 string**，不做任何 Number() 转换。
 * 本地新建但尚未落库的会话用 `tmp_` 前缀标记，见`isTempId`。
 */
import { api, type Conversation, type BackendMessage } from '@/lib/api'
import type { ChatMsg, SessionItem } from '@/data'

/** 本地临时 id 前缀：未落库的乐观态会话不会拿去调后端 */
export const TMP_PREFIX = 'tmp_'
export const isTempId = (id: string): boolean => id.startsWith(TMP_PREFIX)

/**
 * 演示数据（`data.ts` 里 SESSIONS）的 id 前缀。
 *
 * <p>它和真实后端 id 天然不是一回事，但有个陷阱：**种子会话的
 * `messagesLoaded` 是 true**（种子自带消息），而 {@code mergeFromServer}
 * 会保留所有"已加载消息"的本地会话 —— 于是种子会话永远刷不掉，
 * 拿 `seed_s1` 去调后端就是 400（实测踩过：
 * `conversationId=seed_s1（需要 Long）`）。
 *
 * <p>所以真实模式下必须显式把种子会话排除在"保留本地"之外。
 */
export const SEED_PREFIX = 'seed_'
export const isSeedId = (id: string): boolean => id.startsWith(SEED_PREFIX)

/**
 * 后端 role → 前端 role。
 *
 * ⚠️ **这不是改名，是值域映射**（实测 `V2__chat.sql` 注释）：
 * 后端是 `USER / ASSISTANT / SYSTEM / TOOL` 四个大写值，
 * 前端 UI 用的是 `'user' | 'bot'`，**后端没有 `bot`**，最接近的是 `ASSISTANT`。
 * `SYSTEM` / `TOOL` 在现有 UI 里没有对应展示位置，先并入 'bot'
 * （保守：宁可多显示也不要丢消息）。
 */
function mapRole(role: string): ChatMsg['role'] {
  return role === 'USER' ? 'user' : 'bot'
}

/**
 * 后端消息 → 前端 `ChatMsg`。
 *
 * 后端有 16 个字段，前端只有 7 个，这里只映射语义明确的：
 *   content → text / citations → cites / retrieval → context
 *
 * ⚠️ **未映射的字段是有原因的**，不是遗漏：
 *   - `role` 之外的 `agentId`：前端 `ChatMsg` 没有这个概念
 *   - `latencyMs` / `firstTokenMs` / `totalTokens`：前端 `RunContext` 是手写演示数据，
 *     真实值要走 SSE 事件流才有意义，历史消息回放时这些数字意义不大
 *   - `status` / `errorMsg`：前端目前有自己的一套"已停止/超时"本地状态机，
 *     两套并存会打架，等 P1-2 真实流式接入时统一
 *
 * `retrieval` 映射成 `context`（而不是新加一个字段）：流式那一侧显示的
 * 「命中 N 个片段」本来就是 ContextChips 读 `context` 画出来的，
 * 历史消息只要把同一份数据填回同一个字段，刷新前后就是同一张卡片，
 * 不必为"回放"再写一套渲染（roadmap 待办 #6 的成因正是没有把它落库）。
 */
export function toChatMsg(m: BackendMessage): ChatMsg {
  const citationRefs = (m.citations as ChatMsg['citationRefs'] | undefined) ?? []
  return {
    id: m.id,
    role: mapRole(m.role),
    text: m.content ?? '',
    cites: citationRefs.map((citation) => citation.documentTitle ?? '未知来源'),
    citationRefs,
    ...(toRunContext(m.retrieval) ? { context: toRunContext(m.retrieval)! } : {}),
  }
}

/**
 * `retrieval` 事件负载 → 历史消息的上下文胶囊。
 *
 * 只取能算准的三个量：命中切片数、命中涉及的文档数、因预算被丢弃的段数——
 * 其余（历史轮数、联网开关）在回放时没有可信来源，一律给"无"，
 * 不用编造的数字把胶囊填满（前端宁可少显示一个胶囊，也不要显示假的）。
 */
function toRunContext(retrieval: BackendMessage['retrieval']): ChatMsg['context'] | null {
  if (!retrieval || typeof retrieval !== 'object') return null
  const count = typeof retrieval.count === 'number' ? retrieval.count : 0
  const hits = Array.isArray(retrieval.hits) ? (retrieval.hits as Record<string, unknown>[]) : []
  const usedDocs = new Set(
    hits.map((hit) => hit.documentTitle).filter((v): v is string => typeof v === 'string' && !!v),
  ).size
  const truncated = typeof retrieval.truncated === 'number' ? retrieval.truncated : 0
  return {
    kbs: [],
    retrieved: count,
    usedDocs,
    scannedDocs: count,
    historyTurns: 0,
    web: false,
    ...(truncated > 0 ? { truncated } : {}),
  }
}

/**
 * 后端会话 → 前端 `SessionItem`。
 *
 * `updated` 是**相对时间字符串**（原型的 `updated: '2 小时前'` 是硬编码的中文串，
 * `ChatPage.tsx:531` 直接渲染它）。后端给的是 ISO 时间戳，
 * 所以必须在这里换算——**不能把ISO 串直接塞进去**。
 *
 * ⚠️ 后端时间精度不一致（实测：POST 返回 7 位小数、列表返回 3 位），
 * 所以用 `new Date()` 解析而不是字符串切片。
 */
function formatRelative(iso: string | null | undefined): string {
  if (!iso) return ''
  const t = new Date(iso)
  if (Number.isNaN(t.getTime())) return ''
  const diff = Date.now() - t.getTime()
  const min = Math.floor(diff / 60_000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour} 小时前`
  const day = Math.floor(hour / 24)
  if (day < 30) return `${day} 天前`
  const month = Math.floor(day / 30)
  if (month < 12) return `${month} 个月前`
  return `${Math.floor(month / 12)} 年前`
}

/**
 * 会话映射。
 *
 * `messages` 一律初始化为**空数组 + messagesLoaded=false**：
 * 列表接口本来就不返回消息，填任何内容都是编的。
 * 真正的消息由 `loadMessages` 拉到后再patch 进去。
 */
export function toSessionItem(c: Conversation): SessionItem {
  return {
    id: c.id,
    title: c.title ?? '未命名会话',
    /** 决策 4：存 id，null = 用内置默认 Agent */
    agentId: c.agentId,
    mode: (c.mode as 'PLAIN' | 'RAG' | 'AGENT') ?? 'PLAIN',
    /** ⚠️ 后端叫 kbIds；这里保持原字段名 kbs 以免动13 个引用方 */
    kbs: c.kbIds ?? [],
    updated: formatRelative(c.lastMessageAt ?? c.updatedAt),
    pinned: c.pinned,
    messages: [],
    messagesLoaded: false,
  }
}

/** 供 store 复用：把后端分页结果整批映射 */
export function toSessionItems(list: Conversation[]): SessionItem[] {
  return list.map(toSessionItem)
}

/**
 * 拉取某个会话的消息并映射成 `ChatMsg[]`。
 * 空会话实测返回 `{ list: [], total: 0 }` → 返回空数组（这叫"已加载"）。
 */
export async function fetchMessages(conversationId: string): Promise<ChatMsg[]> {
  const page = await api.messages.list(conversationId)
  return page.list.map(toChatMsg)
}