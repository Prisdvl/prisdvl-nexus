/**
 * 会话数据 store（模块 store + 后端数据源）
 *
 * 为什么要把会话从对话页里"提"出来：
 * 侧栏要显示「最近会话」（ChatGPT 那种信息架构），而对话页也需要读写同一份数据。
 * 两边各自 useState 会立刻分叉，所以用和 `lib/auth`、`lib/toast` 一致的模块 store +
 * `useSyncExternalStore`：不需要引入 Context/Redux，也不会多一层 Provider。
 *
 * ##接后端时改了什么（修正本文件此前的错误预期）
 *
 * 原注释写着"接后端时**只需要替换 loadPersisted/persist 这两个函数**，store 的对外接口不变"。
 * **实测下来不成立**，真实改动是：
 *   1. 全部 id 从 `number` 改成 `string`（后端雪花 ID，17 个成员里9 个签名直接带 id）
 *   2. 新增 `messagesLoaded`（决策 2的懒加载标记）
 *   3. `agent: string` → `agentId: string | null`（决策 4）
 *   4. 移除 `projectId`（后端无对应字段，决策为"先隐藏"）
 *   5. `STORE_VERSION` 1 → 2（否则旧快照里的 `id: 1` 会被当 string 用）
 *
 * ## 持久化策略
 *
 * 快照仍写 localStorage，但**只作为离线/ mock 模式的兜底**：
 * 真实模式下数据源是后端（见 lib/conversations.ts），
 * 每次进应用会以 `syncFromServer` 的结果覆盖。
 */
import { useSyncExternalStore } from 'react'
import { SESSIONS, type ChatMsg, type SessionItem } from '@/data'
import { TMP_PREFIX, isTempId, isSeedId } from '@/lib/conversations'

type Listener = () => void
const listeners = new Set<Listener>()

const STORE_KEY = 'nx-proto-sessions'
/**
 * 种子数据或结构变化时 +1：旧快照会被丢弃，避免读到不兼容的数据。
 *
 * ⚠️ **必须是 2**：`SessionItem.id` 从 number 变成 string、`agent` 拆成 `agentId`、
 * 新增 `messagesLoaded`。若还停在 1，旧快照里的 `id: 1`（数字）会被当字符串用，
 * 之后所有 `===` 比较静默失配 —— 这类 bug 不报错，最难查。
 */
const STORE_VERSION = 2
/** localStorage 大约 5MB；给快照设一个上限，超了就丢最旧的会话而不是写失败 */
const MAX_BYTES = 1_200_000

interface Persisted {
  version: number
  sessions: SessionItem[]
  /** 空态用 null，不用 0 —— `''` / `'0'` 在 `===` 下都是合法字符串，不是"无" */
  activeId: string | null
  archived: string[]
}

function loadPersisted(): Persisted | null {
  try {
    const raw = localStorage.getItem(STORE_KEY)
    if (!raw) return null
    const data = JSON.parse(raw) as Persisted
    if (!data || data.version !== STORE_VERSION || !Array.isArray(data.sessions) || data.sessions.length === 0) return null
    return data
  } catch {
    /* 存储被禁用 / JSON 损坏：退化为内存模式，不要让应用起不来 */
    return null
  }
}

const restored = loadPersisted()

let sessions: SessionItem[] = restored ? restored.sessions : SESSIONS
let activeId: string | null = restored ? restored.activeId : (SESSIONS[0]?.id ?? null)
/**
 * 已归档的会话 id。
 *
 * 决策 3：**归档是纯前端本地标记，不调后端**，所以它必须持久化，
 * 否则刷新后归档状态全丢。后端 `chat_conversation` 表确实没有 archived 列。
 */
let archived: string[] = restored ? restored.archived : []
/**
 * 本地临时 id 计数器。
 *
 * ⚠️ 未落库的会话用 `${TMP_PREFIX}${n}` 标记（见 lib/conversations.ts 的 `isTempId`）。
 * 原因：后端 id 是 19 位雪花数字，若本地也用纯数字，两套id 会混在同一命名空间，
 * `branchFrom()` 产出的临时 id 会被当后端 id 调API → 404。
 * 用前缀后一眼能区分，且永不与后端 id 冲突。
 */
let tmpSeq = 0
const nextTmpId = () => `${TMP_PREFIX}${++tmpSeq}`
/** 侧栏点「新对话」时自增，对话页据此打开新建向导 */
let createRequests = 0

/** 写入排队：一次交互可能触发多次 emit，合并成一帧写一次，避免卡顿 */
let writeTimer: ReturnType<typeof setTimeout> | undefined
function persist() {
  if (writeTimer) return
  writeTimer = setTimeout(() => {
    writeTimer = undefined
    try {
      let payload: Persisted = { version: STORE_VERSION, sessions, activeId, archived }
      let json = JSON.stringify(payload)
      /* 超限就丢掉最旧的未置顶会话，直到塞得下（置顶的优先保留） */
      while (json.length > MAX_BYTES && payload.sessions.length > 1) {
        /* 不用 findLastIndex（当前 TS target 的 lib 里没有），手写一遍更稳 */
        let dropIdx = -1
        for (let i = payload.sessions.length - 1; i >= 0; i -= 1) {
          if (!payload.sessions[i].pinned) { dropIdx = i; break }
        }
        if (dropIdx < 0) break
        payload = { ...payload, sessions: payload.sessions.filter((_, i) => i !== dropIdx) }
        json = JSON.stringify(payload)
      }
      localStorage.setItem(STORE_KEY, json)
    } catch {
      /* 配额满 / 隐私模式：静默降级为内存模式 */
    }
  }, 300)
}

/** 清空持久化并回到种子数据（设置里的「重置演示数据」用） */
function resetToSeed() {
  sessions = SESSIONS
  activeId = SESSIONS[0]?.id ?? null
  archived = []
  try { localStorage.removeItem(STORE_KEY) } catch { /* 忽略 */ }
  emit()
}

/*
 * 快照必须**引用稳定**：useSyncExternalStore 会用 Object.is 比较快照，
 * 若每次 snapshot() 都新建对象，它会在每次渲染后都判定"变了" → 无限重渲染
 * （实测直接把页面打成 ErrorBoundary 的 "Maximum update depth exceeded"）。
 * 因此只在数据真正变更时重建一次快照。
 */
let cached = { sessions, activeId, archived, createRequests }
const emit = () => {
  cached = { sessions, activeId, archived, createRequests }
  persist()
  listeners.forEach((l) => l())
}
const subscribe = (l: Listener) => {
  listeners.add(l)
  return () => listeners.delete(l)
}

export const sessionStore = {
  sessions: () => sessions,
  activeId: () => activeId,
  archived: () => archived,
  snapshot: () => cached,
  createRequests: () => createRequests,

  setId: (id: string) => { activeId = id; emit() },

  patch: (id: string, fn: (s: SessionItem) => SessionItem) => {
    sessions = sessions.map((s) => (s.id === id ? fn(s) : s))
    emit()
  },

  patchMsg: (sessionId: string, msgId: string, fn: (m: ChatMsg) => ChatMsg) => {
    sessions = sessions.map((s) =>
      s.id === sessionId ? { ...s, messages: s.messages.map((m) => (m.id === msgId ? fn(m) : m)) } : s
    )
    emit()
  },

  pushMsg: (sessionId: string, msg: ChatMsg) => {
    sessions = sessions.map((s) => (s.id === sessionId ? { ...s, messages: [...s.messages, msg] } : s))
    emit()
  },

  add: (s: SessionItem) => {
    sessions = [s, ...sessions]
    activeId = s.id
    emit()
  },

  /**
   * 从某条消息分支出一条新对话（复制到该条为止的上下文）。
   *
   * ⚠️ 分支会话是**本地临时态**（`tmp_` 前缀 id），不会调后端——
   * 真实实现需要后端提供"基于某条消息分叉"的接口，本轮不新增。
   * 复制出的消息 id 也换成临时 id，避免与被复制的原消息 id 撞车
   * （原来用同一个数字 id，在 React key 和 patchMsg 里都会指错对象）。
   */
  branchFrom: (msgId: string): string | null => {
    const cur = sessions.find((s) => s.id === activeId)
    const idx = cur ? cur.messages.findIndex((m) => m.id === msgId) : -1
    if (!cur || idx < 0) return null
    const id = nextTmpId()
    const copy: SessionItem = {
      id,
      title: `${cur.title} · 分支`,
      agentId: cur.agentId,
      kbs: [...cur.kbs],
      updated: '刚刚',
      pinned: false,
      /* 分支里的消息已经存在本地了，所以标为已加载 —— 否则进分支会触发一次无意义的请求 */
      messagesLoaded: true,
      messages: cur.messages.slice(0, idx + 1).map((m) => ({ ...m, id: nextTmpId() })),
    }
    sessions = [copy, ...sessions]
    activeId = id
    emit()
    return id
  },

  /**
   * 从 store 移除（决策 3：真正的删除动作）。
   *
   * 调用方**必须先**调 `api.conversations.remove(id)`并拿到成功响应，
   * 再调这里 —— 因为后端 DELETE 是级联物理删（消息 + 会话记忆），
   * 不可恢复，所以不能"先删本地再发请求"（那样失败后两边不一致）。
   * 临时 id（`tmp_`）没落库，不需要也不能调后端。
   */
  remove: (id: string) => {
    sessions = sessions.filter((s) => s.id !== id)
    archived = archived.filter((x) => x !== id)
    if (activeId === id) activeId = sessions[0]?.id ?? null
    emit()
  },

  /**
   * 归档（决策 3）：**纯前端本地标记，不调后端**，可恢复。
   *
   * 后端 `chat_conversation` 表没有 archived 列（已查 V2 建表语句），
   * 所以归档只能存在本地，靠 persist() 落localStorage 才不会刷新丢失。
   *
   * 归档 ≠ 删除：归档只是从主列表隐藏，数据与消息都还在；
   * 而 `remove` 对应的后端 DELETE 会物理删消息并清会话记忆，不可恢复。
   */
  archive: (id: string) => {
    if (!archived.includes(id)) archived = [...archived, id]
    if (activeId === id) {
      const next = sessions.find((s) => s.id !== id)
      if (next) activeId = next.id
    }
    emit()
  },

  /** 从归档恢复（同样纯本地） */
  restore: (id: string) => {
    archived = archived.filter((x) => x !== id)
    emit()
  },

  /** 整体替换列表并选中某条（后端列表刷新时用） */
  replaceAll: (list: SessionItem[], selectId?: string) => {
    sessions = list
    if (selectId !== undefined) activeId = selectId
    emit()
  },

  /** 回到种子数据（设置弹层里的「重置演示数据」） */
  resetToSeed,

  /**
   * 用后端返回的会话列表整体替换本地列表（P1-1 的验收标准：
   * 登录后侧栏会话列表来自 `GET /api/chat/conversations`）。
   *
   * 三条必须注意：
   * 1. **保留本地临时会话**（`tmp_` 前缀）。刚在向导里建的会话还没落库，
   *    如果直接整体替换，用户会发现"刚建的对话凭空消失了"。
   * 2. **保留已拉过消息的会话内容**。后端列表不带消息（决策 2），
   *    整体替换会让已加载的消息被清空、退回骨架屏。
   * 3. **保留归档状态**。归档是纯本地标记（决策 3），后端不存，
   *    替换时丢了就等于"归档记录凭空消失"。
   */
  mergeFromServer: (list: SessionItem[]) => {
    /*
     * ⚠️ **种子会话（`seed_` 前缀）必须排除**，这是实测踩过的坑：
     * 种子会话的 `messagesLoaded` 是 true（它自带演示消息），
     * 而下面"保留已加载消息的本地会话"这条规则会把它们全留下 ——
     * 结果真实模式下侧栏混进 `seed_s1`，点它去调后端就是
     * 400 `conversationId=seed_s1（需要 Long）`。
     */
    const isRealLocal = (s: SessionItem) => !isSeedId(s.id)
    const localOnly = sessions.filter((s) => isTempId(s.id) || (s.messagesLoaded && isRealLocal(s)))
    const serverIds = new Set(list.map((s) => s.id))
    /* 后端也有、且本地已加载过消息的：保留本地那份（含消息） */
    const keepLoaded = localOnly.filter((s) => !isTempId(s.id) && serverIds.has(s.id))
    const fresh = list.filter((s) => !keepLoaded.some((k) => k.id === s.id))
    sessions = [...localOnly.filter((s) => isTempId(s.id)), ...fresh, ...keepLoaded]
    if (!sessions.some((s) => s.id === activeId)) {
      activeId = sessions[0]?.id ?? null
    }
    emit()
  },

  requestCreate: () => { createRequests += 1; emit() },
  consumeCreate: () => { if (createRequests > 0) { createRequests = 0; emit() } },

  /** 下一个本地临时 id（`tmp_` 前缀，不与后端雪花 id 冲突） */
  nextTmpId,

  /* ---------------- 决策 2：消息懒加载 ---------------- */

  /**
   * 把某会话的消息标记为「已加载」并替换内容。
   *
   * ⚠️ 必须在请求**成功**后调用（包括后端返回空列表），
   * 否则会永久停在"加载中"。失败时要单独标记，让 UI 能给出重试入口。
   */
  setMessages: (sessionId: string, msgs: ChatMsg[]) => {
    sessions = sessions.map((s) =>
      s.id === sessionId ? { ...s, messages: msgs, messagesLoaded: true } : s
    )
    emit()
  },

  /** 标记消息为已加载但内容不可信（请求失败），UI 据此显示「加载失败，点击重试」 */
  setMessagesFailed: (sessionId: string) => {
    sessions = sessions.map((s) => (s.id === sessionId ? { ...s, messagesLoaded: false } : s))
    emit()
  },
}

/** 组件订阅入口：返回一份不可变快照，供 useSyncExternalStore 做变更比较 */
export function useSessions() {
  const snap = useSyncExternalStore(subscribe, sessionStore.snapshot, sessionStore.snapshot)
  /* 注意顺序：先展开 store 的动作，再用快照值覆盖同名字段——
     否则 store 上的 `sessions()` 这类**函数**会把快照数组覆盖掉（TS 也会报重复属性）。 */
  return {
    ...sessionStore,
    sessions: snap.sessions,
    activeId: snap.activeId,
    archived: snap.archived,
    createRequests: snap.createRequests,
  }
}
