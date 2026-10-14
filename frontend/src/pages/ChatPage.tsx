import { Fragment, useEffect, useLayoutEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import {
  BookOpen, Check, ChevronDown, ChevronRight, Copy, Download, GitBranch, Maximize2, MessagesSquare, Minimize2, Pencil, Pin, Plus, RefreshCw, Search, Settings2, Square, Trash2, Wrench, X,
} from 'lucide-react'
import { HugeiconsIcon } from '@hugeicons/react'
import { Archive02Icon, Delete02Icon } from '@hugeicons/core-free-icons'
import MessageNavigator from '@/components/chat/MessageNavigator'
import ReferencePanel from '@/components/chat/ReferencePanel'
import FeedbackBar from '@/components/chat/FeedbackBar'
import ConfirmCard, { type ConfirmRequest } from '@/components/chat/ConfirmCard'
import ContextChips from '@/components/chat/ContextChips'
import ResultCard from '@/components/chat/ResultCard'
import QueueStrip from '@/components/chat/QueueStrip'
import { toolPolicy } from '@/lib/toolPolicy'
import '@/styles/reference.css'
import CallChip from '@/bits/CallChip/CallChip'
import PromptBar, { type PromptBarSource } from '@/bits/PromptBar/PromptBar'
import ThoughtLine from '@/bits/ThoughtLine/ThoughtLine'
import StatusMark from '@/bits/StatusMark/StatusMark'
import RubberSegment from '@/bits/RubberSegment/RubberSegment'
import Stepper, { Step } from '@/bits/Stepper/Stepper'
import Modal from '@/components/Modal'
import { StateEmpty, Skeleton } from '@/components/Skeleton'
import { toast } from '@/lib/toast'
import Markdown from '@/components/Markdown'
import { KBS, type ChatCitationRef, type ChatMsg, type KbItem, type RunContext, type SessionItem, type ToolCall } from '@/data'
import { useTokenColor } from '@/lib/palette'
import type { ThemeMode } from '@/lib/types'
import { useSessions, nextBranchTitle } from '@/lib/sessions'
import { ensureAgents, useAgents, DEFAULT_AGENT_NAME } from '@/lib/agents'
import { fetchKbs, useLoad } from '@/lib/backendData'
import { api, errMessage, USE_MOCK, type ConversationMode } from '@/lib/api'
import { isTempId, fetchMessages, toSessionItem } from '@/lib/conversations'
import { postSse } from '@/lib/sse'

const REPLY = `已在绑定的知识库中检索到相关内容，下面是**结构化回答**：

## 结论

RDB 与 AOF 的取舍取决于你能接受多少数据丢失。

| 方案 | 恢复速度 | 文件大小 | 最多丢多少 |
| --- | --- | --- | --- |
| RDB | 快 | 小 | 两次快照之间的写入 |
| AOF | 慢 | 大 | 最多 1 秒（everysec） |

### 写入流程

\`\`\`mermaid
flowchart LR
  A[客户端写命令] --> B{是否开启 AOF}
  B -- 是 --> C[写 AOF 缓冲区]
  C --> D[每秒 fsync 落盘]
  B -- 否 --> E[仅内存执行]
  D --> F[重启后重放恢复]
  E --> G[重启读 RDB 快照]
\`\`\`

### 关键代码

\`\`\`conf
appendonly yes
appendfsync everysec
save 900 1
\`\`\`

> 生产常见做法：两者同时开启，AOF 保可靠性、RDB 做冷备与快速恢复。`

const DEFAULT_CITES = ['集合框架.md', 'JVM 内存模型.md']

/**
 * 会话列表为空时的兜底会话。
 *
 * ⚠️ 它是**模块级常量**（不是每次新建），这样所有渲染路径拿到的引用稳定，
 * 不会因为"空 → 有 → 空"反复创建新对象而额外触发重渲染。
 *
 * `messagesLoaded: true` 是刻意的：没有真实数据可拉，
 * 标成"已加载"才不会去请求一个不存在的会话 id。
 */
const EMPTY_SESSION: SessionItem = {
  id: '',
  title: '',
  agentId: null,
  kbs: [],
  updated: '',
  pinned: false,
  messagesLoaded: true,
  messages: [],
}

interface ChatPageProps {
  theme: ThemeMode
  initialConversationId?: string
  /** 点引用 → 跳到知识库详情并高亮该文档 */
  onOpenDoc: (citation: ChatCitationRef) => void
}

export default function ChatPage({ theme, onOpenDoc, initialConversationId }: ChatPageProps) {

  /* 会话数据来自共享 store：侧栏的「最近会话」与这里读写同一份 */
  const {
    sessions, activeId, archived, createRequests,
    setId: setActiveId, patch, patchMsg, pushMsg, add: addSession,
    branchFrom, remove: removeSessionStore, archive: archiveSessionStore,
    restore: restoreSessionStore, requestCreate, consumeCreate, nextTmpId,
    setMessages, setMessagesFailed,
  } = useSessions()

  const [query, setQuery] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [showSettledThought, setShowSettledThought] = useState(false)
  /**
   * 思考步骤：只由真实 SSE 事件驱动（P2-5.2）：
   * retrieval → 检索步骤；tool_call → 工具步骤；首个 delta → 生成步骤。
   * 绝不写死"正在读取/检索 N 个切片"这类无事件依据的文案。
   */
  const [thoughtSteps, setThoughtSteps] = useState<string[]>([])
  const addThoughtStep = (step: string) =>
    setThoughtSteps((list) => (list.includes(step) ? list : [...list, step]))
  /** 后端工具 code → 面向用户的中文步骤名（未知 code 原样展示，不编造功能） */
  const TOOL_STEP_NAME: Record<string, string> = {
    knowledge_search: '知识库检索',
    calculator: '数学计算',
  }
  const previousStreaming = useRef(false)
  const abortRef = useRef<AbortController | null>(null)
  const requestIdRef = useRef<string | null>(null)
  /*
   * 运行令牌：每次发送 +1，停止/继续时也 +1。异步链在每步比对，过期就直接返回 ——
   * 这是"停止"能真正生效的关键（原来 onStop 只 setStreaming(false)，
   * 已排队的 setTimeout 仍会跑完，工具会永远卡在 running）。
   */
  const runSeq = useRef(0)
  /** 待发送队列：busy 期间输入的消息排在这里，上一轮结束后自动接着发 */
  const [queue, setQueue] = useState<string[]>([])
  /**
   * 每条回答的运行元数据（反馈要能归因到"哪套配置 / 哪个环节慢"）。
   * 模型与档位来自 PromptBar 的 onSend detail；耗时在真实实现里来自后端事件流。
   */
  const runMeta = useRef(new Map<string, { model?: string; effort?: string; startedAt: number; firstProgressMs?: number; totalMs?: number }>())
  /** 上一条回答是否被用户中断（用来给出「继续生成」） */
  const [interrupted, setInterrupted] = useState(false)
  /** 右上角「会话配置」浮层：收纳 模式 / Agent / 知识库 三段选择 */
  const [configOpen, setConfigOpen] = useState(false)
  const configRef = useRef<HTMLDivElement>(null)
  const [wizard, setWizard] = useState(false)
  /* 点引用不跳页：在右侧面板就地查看；需要原文时再点面板里的跳转 */
  const [refCitation, setRefCitation] = useState<ChatCitationRef | null>(null)
  /*
   * 新建会话向导的选区必须是**受控状态**。
   * 之前直接改 DOM（setAttribute('data-on')），React 每次重渲染都会把属性写回去，
   * 结果就是"点了没反应、选不中知识库和 Agent"。
   */
  /* 决策 4：向导选的也是 **id**，初值为 null（默认助手） */
  const [wizMode, setWizMode] = useState<ConversationMode>('PLAIN')
  const [wizAgent, setWizAgent] = useState<string | null>(null)
  const [wizKbs, setWizKbs] = useState<string[]>([])
  const [creatingSession, setCreatingSession] = useState(false)
  const [showArchived, setShowArchived] = useState(false)
  /* 双击标题原地重命名 */
  const [renaming, setRenaming] = useState<number | null>(null)
  const [draft, setDraft] = useState('')
  const [editing, setEditing] = useState<{ id: string; text: string } | null>(null)
  /** 输入框"专注写作"放大模式 */
  const [composerExpanded, setComposerExpanded] = useState(false)
  /** 当前输入框渲染行数（PromptBar 上报）；未放大时仅在 >3 行才显示放大入口 */
  const [composerLines, setComposerLines] = useState(1)
  /** 分支请求进行中：防止重复提交 */
  const [branching, setBranching] = useState(false)
  const listRef = useRef<HTMLDivElement>(null)

  /* 侧栏点了「新对话」→ 这里把向导打开（跨组件的意图用 store 里的计数器传递） */
  useEffect(() => {
    if (createRequests > 0) {
      setWizard(true)
      setWizMode('PLAIN')
      setWizAgent(null)
      setWizKbs([])
      consumeCreate()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [createRequests])

  /* Esc 退出输入框放大模式 */
  useEffect(() => {
    if (!composerExpanded) return
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key === 'Escape') setComposerExpanded(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [composerExpanded])

  /* 会话配置浮层：点外部或 Esc 关闭 */
  useEffect(() => {
    if (!configOpen) return
    const onDown = (e: globalThis.PointerEvent) => {
      if (configRef.current && !configRef.current.contains(e.target as Node)) setConfigOpen(false)
    }
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key === 'Escape') setConfigOpen(false)
    }
    document.addEventListener('pointerdown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('pointerdown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [configOpen])

  useEffect(() => {
    if (streaming) {
      previousStreaming.current = true
      setShowSettledThought(false)
      return
    }
    if (!previousStreaming.current) return
    previousStreaming.current = false
    setShowSettledThought(true)
    const timeout = window.setTimeout(() => setShowSettledThought(false), 1400)
    return () => window.clearTimeout(timeout)
  }, [streaming])

  /* 滑动手势里主动作（置顶）的底色：跟随主题强调色，而不是危险红 */
  const actionAccent = useTokenColor('--primary-strong')
  /* 火花是画在 canvas 上的：canvas 不解析 var()，必须给具体色值 */
  const sparkAccent = useTokenColor('--primary')
  const C = theme === 'dark'
    ? { row: '#1b1b1f', drawer: '#2c2c33', sel: '#2a2a31', text: '#e8e8ec', del: '#e5484d', arch: '#9a9aa3' }
    : { row: '#ffffff', drawer: '#e9eaee', sel: '#f4efe8', text: '#1a1a1d', del: '#e5484d', arch: '#6f7782' }

  /*
   * 当前会话。
   *
   * ⚠️ **必须做 undefined-safe 兜底，而不是"空列表时提前 return"**。
   *
   * 列表为空时（新用户首次进入、或后端一条会话都没有），
   * `sessions.find(...) ?? sessions[0]` 就是 `undefined`。
   * 而 ChatPage 里有十几处读 `active.kbs` / `active.messages`，
   * 其中一部分（如 `promptSources`）是**在 return 之前求值的普通 const** ——
   * 靠 JSX 之前的 `if (!active) return` 守卫**挡不住**，
   * 会直接 `TypeError: Cannot read properties of undefined (reading 'kbs')`，
   * 整棵树崩到 ErrorBoundary，表现为白屏。
   *
   * 所以用 `EMPTY_SESSION` 兜底，保证任何字段访问都有值；
   * 真正的"空态 UI"由渲染层用 `isEmptyList` 判断。
   */
  /* 工作台点开的会话通过 query 传入；等会话同步到 store 后再选中。 */
  useEffect(() => {
    if (initialConversationId && sessions.some((s) => s.id === initialConversationId)) setActiveId(initialConversationId)
  }, [initialConversationId, sessions, setActiveId])

  const isEmptyList = sessions.length === 0
  const active: SessionItem =
    sessions.find((s) => s.id === activeId) ?? sessions[0] ?? EMPTY_SESSION

  /* ---------------- 决策 4：Agent 存 id，展示名从缓存映射 ---------------- */

  const { agents, nameOf: agentNameOf } = useAgents()
  const { data: loadedKbs, loading: kbsLoading, error: kbsError, reload: reloadKbs } = useLoad(fetchKbs)
  const kbOptions = USE_MOCK ? KBS : (loadedKbs ?? [])
  const kbKey = (kb: KbItem) => USE_MOCK ? kb.name : kb.id
  const kbName = (key: string) => kbOptions.find((kb) => kbKey(kb) === key)?.name ?? key
  /* 进对话页就拉一次 Agent 列表（幂等，失败会静默重试） */
  useEffect(() => { void ensureAgents() }, [])

  /** 会话当前 Agent 的展示名；`agentId === null` → 「默认助手」 */
  const agentName = agentNameOf(active?.agentId ?? null)

  /* ---------------- 决策 2：消息懒加载 ---------------- */

  /** 当前会话的消息是否还在拉取中（用于骨架 / "加载中"提示） */
  const [loadingMessages, setLoadingMessages] = useState(false)

  /*
   * 进会话时按需拉消息。
   *
   * 三个必须遵守的约束：
   * 1. **`messagesLoaded` 为 true 就不再请求** —— 已加载过的（包括空会话）
   *    不重复打接口。绝不能用 `messages.length === 0` 判断，那会把
   *    "已加载但是空的会话"当成"没加载"，于是每次切会话都重新请求。
   * 2. **临时会话不请求**：`tmp_` 前缀的会话还没落库，后端查不到。
   * 3. **用 requestId 防止竞态**：快速来回切会话时，
   *    先发的响应可能后到，会把**当前**会话的消息覆盖成别的会话的。
   *    只有当发起时记录的那个会话仍是当前会话，才写入。
   */
  useEffect(() => {
    if (!active) return
    const { id, messagesLoaded } = active
    if (messagesLoaded) return
    if (isTempId(id)) { setMessages(id, []); return }
    if (USE_MOCK) { setMessages(id, active.messages); return }

    let cancelled = false
    setLoadingMessages(true)
    fetchMessages(id)
      .then((msgs) => {
        if (cancelled) return
        setMessages(id, msgs)
      })
      .catch((e) => {
        if (cancelled) return
        setMessagesFailed(id)
        toast.error('消息加载失败', errMessage(e))
      })
      .finally(() => { setLoadingMessages(false) })
    return () => { cancelled = true }
    // active.messages 只作为 mock 分支的初值，不进依赖——它一变就会重发请求
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active?.id, active?.messagesLoaded])

  /*
   * 选择器选项：默认助手永远排第一（对应 `agentId = null`），
   * 其余是后端 Agent 列表。
   *
   * ⚠️ 为什么把"默认助手"显式化成 `null` 而不是用内置 Agent 的 id：
   * 后端 `ConversationResponse.agentId` 允许为 null，语义是"用内置默认 Agent"。
   * 若选择器里显示的是一个真实 id，用户选它与"不选"在数据上就分不开了。
   */
  const agentOptions = useMemo(
    () => [
      { id: null as string | null, label: DEFAULT_AGENT_NAME },
      ...agents
        .filter((a) => a.name !== DEFAULT_AGENT_NAME)
        .map((a) => ({ id: a.id as string | null, label: a.name })),
    ],
    [agents],
  )

  /**
   * 换 Agent → 调 `PUT /api/chat/conversations/{id}` 传 agentId（决策 4）。
   *
   */
  const switchAgent = async (agentId: string | null) => {
    if (!active || active.agentId === agentId) return
    /*
     * 两种"不调后端"的情况：
     *   - `USE_MOCK`：离线演示，没有后端可调（种子会话 id 是 `seed_*`，
     *     不是 `tmp_`，所以单看 id 判断会漏掉它→ 会去请求后端并失败）。
     *   - `tmp_` 前缀：本地临时会话，还没落库。
     */
    if (USE_MOCK || isTempId(active.id)) {
      patch(active.id, (s) => ({ ...s, agentId }))
      toast.success('已切换 Agent', agentNameOf(agentId))
      return
    }
    /*
     * 选Agent 前先确保会话是 AGENT 模式。
     *
     * 后端 `resolveAgentId` 只在 AGENT 模式允许绑 Agent，PLAIN/RAG 会直接报
     * 1001「只有 AGENT 会话可以选用 Agent」。而本轮起**模式已可以切换**，
     * 所以这里直接把模式一并切过去 —— 这是用户意图的自然表达
     *（"我要用这个 Agent"），而不是替他做决定。
     */
    const needModeChange = active.mode !== 'AGENT'
    if (needModeChange && agentId !== null && agents.length === 0) {
      toast.error('没有可用的 Agent', '请先到 Agent 页面创建一个并发布，再回到这里选用。')
      return
    }
    try {
      /*
       * ⚠️ **"切回默认助手"必须发 `resetAgent: true`，不能发 `agentId: null`**。
       *
       * 后端 `update` 的语义：
       *   - `agentId == null` → **不改**（局部更新约定）
       *   - `resetAgent == true` → 清空（回到内置默认 Agent）
       * 注释写明：*"null 既是不改又是解绑分不清，所以清空必须有独立信号"*。
       *
       * ⚠️ 顺序也很重要：**必须先调后端、成功后再改本地**。
       * 反过来做的话失败后 UI 会显示"已切换"，实际后端没动 —— 假成功。
       */
      const body: Parameters<typeof api.conversations.update>[1] =
        agentId === null ? { resetAgent: true } : { agentId }
      if (needModeChange) body.mode = 'AGENT'
      const updated = await api.conversations.update(active.id, body)
      /* 用后端返回值回填，而不是用我们发过去的值 —— 后端可能在迁移时清理了别的字段 */
      patch(active.id, (s) => ({
        ...s,
        agentId: updated.agentId,
        mode: (updated.mode as ConversationMode | undefined) ?? s.mode,
        kbs: updated.kbIds ?? [],
      }))
      toast.success(
        needModeChange ? '已切换到 Agent 模式并选定 Agent' : '已切换 Agent',
        agentNameOf(agentId),
      )
    } catch (e) {
      toast.error('切换失败', errMessage(e))
      /* 本地保持不变：宁可不动，也不能与后端不一致 */
    }
  }

  /* ---------------- 会话模式切换（PLAIN / RAG / AGENT） ---------------- */

  const MODE_OPTIONS: { value: ConversationMode; label: string }[] = [
    { value: 'PLAIN', label: '普通对话' },
    { value: 'RAG', label: '知识库问答' },
    { value: 'AGENT', label: 'Agent' },
  ]

  /**
   * 切模式 → `PUT /api/chat/conversations/{id}` 传 mode。
   *
   * 后端按目标模式清理不兼容绑定（PLAIN 清两者 / RAG 清 agentId / AGENT 清 kbIds），
   * 并重算 modelRole。**提示文案必须按"实际真的清理了什么"生成**——
   * 若某项本来就没绑定，就不提它，否则就是在撒谎。
   */
  const switchMode = async (mode: ConversationMode) => {
    if (!active || (active.mode ?? 'PLAIN') === mode) return
    const prevMode = (active.mode ?? 'PLAIN') as ConversationMode

    if (USE_MOCK || isTempId(active.id)) {
      patch(active.id, (s) => ({
        ...s,
        mode,
        agentId: mode === 'AGENT' ? s.agentId : null,
        kbs: mode === 'RAG' ? s.kbs : [],
      }))
      toast.success('已切换模式', `${prevMode} → ${mode}（原型演示，未落库）`)
      return
    }

    /* 切到 AGENT 但一个 Agent 都没有：先说清下一步，不假装绑定成功 */
    if (mode === 'AGENT' && agents.length === 0) {
      toast.error(
        '还没有可用的 Agent',
        'AGENT 模式需要一个已发布的 Agent。请先到 Agent 页面创建并发布，再切回这里；切换后可在顶部选择。',
      )
      return
    }

    try {
      const updated = await api.conversations.update(active.id, { mode })
      patch(active.id, (s) => ({
        ...s,
        mode: (updated.mode as ConversationMode | undefined) ?? mode,
        agentId: updated.agentId,
        kbs: updated.kbIds ?? [],
      }))

      /*
       * 提示依据**后端响应**生成，而不是切换前的本地推断。
       *
       * 理由：要求是「提示必须根据实际发生的变更生成」。
       * 本地状态只是"我们以为切换前是什么样"，真正发生了什么以后端返回为准——
       * 本地缓存过期、或绑定早已在别处被清掉时，按本地推断会说错。
       */
      const cleaned: string[] = []
      if (active.agentId !== null && updated.agentId === null) {
        cleaned.push('Agent 绑定已清除')
      }
      if (active.kbs.length > 0 && (updated.kbIds ?? []).length === 0) {
        cleaned.push('知识库绑定已清除')
      }
      const name = MODE_OPTIONS.find((o) => o.value === mode)?.label ?? mode
      toast.success(
        `已切换到${name}模式`,
        cleaned.length > 0
          ? `${cleaned.join('，')}。历史回答保持原样，新回答按当前模式执行。`
          : '历史回答保持原样，新回答按当前模式执行。',
      )
    } catch (e) {
      toast.error('切换失败', errMessage(e))
      /* 本地不动：不先显示成功再等后端 */
    }
  }

  /* 每个会话各自记忆滚动位置：切走时存、切回时恢复（没记录过就落在最新一条） */
  const threadRef = useRef<HTMLDivElement>(null)
  const scrollMemo = useRef<Record<string, number>>({})

  const archiveSession = (id: string) => {
    archiveSessionStore(id)
    toast.info('会话已归档', '可在侧栏「已归档」中找回')
  }

  const restoreSession = (id: string) => {
    restoreSessionStore(id)
    toast.success('会话已恢复', '已回到最近会话')
  }

  const commitRename = (id: string) => {
    const name = draft.trim()
    if (name) patch(id, (x) => ({ ...x, title: name }))
    setRenaming(null)
    setDraft('')
  }

  const switchSession = (id: string) => {
    if (id === activeId) return
    const el = threadRef.current
    /* activeId 可能为 null（会话列表为空时的初值），null 不能作索引 */
    if (el && activeId !== null) scrollMemo.current[activeId] = el.scrollTop
    setActiveId(id)
  }

  useLayoutEffect(() => {
    const el = threadRef.current
    if (!el) return
    const saved = activeId !== null ? scrollMemo.current[activeId] : undefined
    el.scrollTop = saved ?? el.scrollHeight
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeId, streaming])

  /*
   * 持续记录当前会话的滚动位置。
   * 切换会话的动作现在发生在**全局侧栏**（store.setId），对话页不再有机会在"离开前"保存，
   * 所以改成滚动时就节流写进 memo，切回来才能恢复（否则一律跳到底部）。
   */
  useEffect(() => {
    const el = threadRef.current
    if (!el) return
    let raf = 0
    const save = () => {
      raf = 0
      /* activeId 可能为 null（列表为空时的初值），null 不能作对象索引 */
      if (activeId !== null) scrollMemo.current[activeId] = el.scrollTop
    }
    const onScroll = () => { if (!raf) raf = requestAnimationFrame(save) }
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      el.removeEventListener('scroll', onScroll)
      if (raf) cancelAnimationFrame(raf)
    }
  }, [activeId])

  const filtered = useMemo(() => {
    const q = query.trim()
    const list = q ? sessions.filter((s) => s.title.toLowerCase().includes(q.toLowerCase())) : sessions
    return [...list].sort((a, b) => Number(!!b.pinned) - Number(!!a.pinned))
  }, [sessions, query])

  /* 已归档的不出现在主列表里 */
  const visible = useMemo(() => filtered.filter((s) => !archived.includes(s.id)), [filtered, archived])


  const pushMsgToActive = (msg: ChatMsg) => pushMsg(active.id, msg)

  /*
   *删除会话的逻辑已移到 `Sidebar.deleteSession`（决策 3）。
   * 这里原本有一份带 `toast.undo` 撤销的版本，但**后端 DELETE 是级联物理删除**
   * （消息 + 会话记忆都清空），撤销是假的——所以那份实现被删掉了。
   * 保留这里的删除会造成两个入口行为不一致，因此直接移除。
   */

  /** PromptBar 把文本交给我们，不再依赖本地 textarea 状态 */
  /* PromptBar 的 @ 数据源：当前会话绑定的知识库 + 上传/联网（原型示意） */
  const promptSources: PromptBarSource[] = (active.mode === 'RAG' ? kbOptions : [])
    .filter((kb) => active.kbs.includes(kbKey(kb)))
    .map((kb) => ({ key: kbKey(kb), name: kb.name, description: '当前会话绑定的知识库', icon: <BookOpen size={14} /> }))
  const PROMPT_COMMANDS = [
    { key: 'summarize', name: '/总结', description: '把当前会话浓缩成要点' },
    { key: 'explain', name: '/讲解', description: '用更直白的话重讲一遍' },
    { key: 'tasks', name: '/待办', description: '整理成可执行的清单' },
  ]
  const PROMPT_MODELS = [
    { key: 'nexus-pro', name: 'Nexus Pro', tag: '默认' },
    { key: 'nexus-fast', name: 'Nexus Fast', tag: '快' },
  ]
  const PROMPT_EFFORTS = ['低', '中', '高', '极高']


  /**
   * 真实流式对话：POST /api/chat/stream（SSE）。
   * 与下方的模拟分支并存：USE_MOCK 时仍走模拟（离线演示 + 既有 QA 不受影响）。
   */
  const realStream = async (text: string, detail?: { model?: { key?: string; label?: string }; effort?: string }) => {
    const sessionId = active.id
    const botId = `m_${Date.now()}`
    const runId = (runSeq.current += 1)
    const alive = () => runSeq.current === runId
    setInterrupted(false)
    runMeta.current.set(botId, {
      model: detail?.model?.label ?? detail?.model?.key,
      effort: detail?.effort,
      startedAt: Date.now(),
    })
    pushMsgToActive({ id: botId, role: 'bot', text: '', calls: [] })
    let acc = ''
    const ctrl = new AbortController()
    abortRef.current = ctrl
    try {
      await postSse('/chat/stream', { conversationId: sessionId, content: text }, {
        onMeta: (d) => {
          if (typeof d.requestId === 'string') requestIdRef.current = d.requestId
        },
        onDelta: (t2) => {
          if (!alive()) return
          if (!acc) addThoughtStep('生成回答')
          acc += t2
          patchMsg(sessionId, botId, (m) => ({ ...m, text: acc }))
        },
        onRetrieval: (d) => {
          if (!alive()) return
          /* 事件契约见 RetrievalEvent：count/hits[]/failedKnowledgeBases[] */
          const hit = Number(d?.count ?? 0)
          const hitsArr = Array.isArray(d?.hits) ? (d.hits as Record<string, unknown>[]) : []
          const usedDocs = new Set(
            hitsArr.map((h) => h.documentTitle).filter((v): v is string => typeof v === 'string' && !!v),
          ).size
          const failedKbs = Array.isArray(d?.failedKnowledgeBases) ? (d.failedKnowledgeBases as unknown[]) : []
          addThoughtStep(`检索知识库 · 命中 ${hit} 个切片`)
          if (failedKbs.length) addThoughtStep('部分知识库检索失败，已跳过')
          patchMsg(sessionId, botId, (m) => ({
            ...m,
            context: { kbs: [], retrieved: hit, usedDocs, scannedDocs: hit, historyTurns: 0, web: false },
          }))
        },
        onToolCall: (d) => {
          if (!alive()) return
          const toolName = String(d.tool ?? 'tool')
          addThoughtStep(`调用工具 · ${TOOL_STEP_NAME[toolName] ?? toolName}`)
          patchMsg(sessionId, botId, (m) => ({
            ...m,
            calls: [
              ...(m.calls ?? []),
              {
                id: `${Date.now()}-${toolName}`,
                icon: 'search' as const,
                name: toolName,
                argument: typeof d.input === 'string' ? d.input : JSON.stringify(d.input ?? ''),
                status: 'running' as const,
              },
            ],
          }))
        },
        onToolResult: (d) => {
          if (!alive()) return
          patchMsg(sessionId, botId, (m) => ({
            ...m,
            calls: (m.calls ?? []).map((c) => (c.name === String(d.tool) ? { ...c, status: 'done' as const } : c)),
          }))
        },
        onDone: (d) => {
          if (!alive()) return
          const citationRefs = Array.isArray(d?.citations) ? d.citations as ChatCitationRef[] : []
          const cites = citationRefs.map((citation) => citation.documentTitle ?? '未知来源')
          patchMsg(sessionId, botId, (m) => ({ ...m, text: m.text || acc, ...(cites.length ? { cites, citationRefs } : {}) }))
          if (d?.stopped) setInterrupted(true)
        },
        onError: (m2) => {
          if (!alive()) return
          toast.error('生成失败', m2)
          patchMsg(sessionId, botId, (m) => ({ ...m, text: m.text || '> ⚠️ ' + m2 }))
        },
      }, ctrl.signal)
    } catch (e) {
      if (alive()) {
        toast.error('对话失败', errMessage(e))
        patchMsg(sessionId, botId, (m) => ({ ...m, text: m.text || '（生成失败）' }))
      }
    } finally {
      if (alive()) {
        const rm = runMeta.current.get(botId)
        if (rm) rm.totalMs = Date.now() - rm.startedAt
        setStreaming(false)
      }
      abortRef.current = null
    }
  }
  const sendText = (
    raw: string,
    detail?: { model?: { key?: string; label?: string }; effort?: string },
    opts?: { skipUserPush?: boolean },
  ) => {
    const t = raw.trim()
    if (!t) return
    /*
     * busy 时**收进队列**（P1-4）：Agent 回答几十秒很正常，用户这时想到第二问也很正常。
     * 之前是静默丢弃、后来只是提示，用户都得自己记着"等它完了再问一遍"。
     * 现在能看见、能删，当前这轮结束后自动接着发。
     */
    if (streaming) {
      setQueue((q) => [...q, t])
      toast.info('已加入队列', `当前回答结束后自动发送（第 ${queue.length + 1} 条）`)
      return
    }
    /* skipUserPush：编辑重发时用户消息已经随截断写回，不能再推一条 */
    if (!opts?.skipUserPush) pushMsgToActive({ id: nextTmpId(), role: 'user', text: t })
    /* PromptBar 自己会清空草稿与附件，这里不再需要 setText */
    setStreaming(true)
    setThoughtSteps([])
    /* 真实后端：非 mock 且会话已在服务端存在时，走 SSE 流式 */
    if (!USE_MOCK && !isTempId(active.id)) {
      void realStream(t, detail)
      return
    }

    /*
     * 模拟一次 Agent 执行：先落一条只有"工具调用轨迹"的机器人消息（第 1 条 running、其余 idle），
     * 再逐条推进成 done / error，最后补上答案文本。
     * 真实实现里这些状态来自后端推送的工具调用事件流。
     */
    /* 消息 id 必须是字符串（与 SessionItem.id 一致）。
       仍用时间戳保证单调递增，但转成字符串，避免同一毫秒内的两条消息撞 id。 */
    const botId = `m_${Date.now()}`
    /* 运行令牌：停止/切会话时 +1，异步链每步比对，过期就直接退出（"停止"才真正生效） */
    const runId = (runSeq.current += 1)
    const alive = () => runSeq.current === runId
    setInterrupted(false)
    const calls: ToolCall[] = [
      { id: `c1-${botId}`, icon: 'search', name: 'kb.search', argument: t.slice(0, 18), status: 'running', expectedMs: 900 },
      { id: `c2-${botId}`, icon: 'file', name: 'doc.read', argument: DEFAULT_CITES[0] ?? '文档', status: 'idle', expectedMs: 1200 },
      {
        id: `c3-${botId}`, icon: 'terminal', name: 'js.sandbox', argument: '复杂度过一遍',
        status: 'idle', expectedMs: 1500,
        /* 高风险：要在沙箱里真的执行代码，必须先让用户点头 */
        requiresConfirm: true,
        explain: '这一步会在服务端沙箱里执行一段代码来验证复杂度结论。沙箱与生产隔离，但会消耗执行配额。',
      },
      /* 第 4 条演示"超时"：与失败区分开，文案与恢复动作都不同 */
      { id: `c4-${botId}`, icon: 'search', name: 'web.fetch', argument: '官方文档最新版本', status: 'idle', expectedMs: 9000 },
    ]
    const sessionId = active.id
    runMeta.current.set(botId, {
      model: detail?.model?.label ?? detail?.model?.key,
      effort: detail?.effort,
      startedAt: Date.now(),
    })
    const setCalls = (next: ToolCall[]) => patchMsg(sessionId, botId, (m) => ({ ...m, calls: next }))
    /*
     * 记录这一轮的上下文：用户判断"答得不好"的原因时，靠的就是这几个数
     * （命中切片少 → 检索问题；引用正常但答错 → 生成问题；省略历史 → 上下文问题）。
     */
    const scannedDocs = KBS.filter((k) => active.kbs.includes(k.name)).reduce((n, k) => n + k.docs, 0)
    const historyTurns = Math.floor(active.messages.filter((m) => m.role === 'user').length)
    const context: RunContext = {
      kbs: [...active.kbs],
      retrieved: DEFAULT_CITES.length * 3,
      usedDocs: DEFAULT_CITES.length,
      scannedDocs,
      historyTurns,
      web: true,
      /* 超出窗口的早期轮次：原型按 8 轮模拟，真实实现由后端返回被截断的轮数 */
      ...(historyTurns > 8 ? { truncated: historyTurns - 8 } : {}),
    }
    pushMsgToActive({ id: botId, role: 'bot', text: '', cites: DEFAULT_CITES, calls, context })

    const step = (i: number) => {
      if (!alive()) return
      if (i >= calls.length) {
        setTimeout(() => {
          if (!alive()) return
          patchMsg(sessionId, botId, (m) => ({ ...m, text: REPLY }))
          const rm = runMeta.current.get(botId)
          if (rm) rm.totalMs = Date.now() - rm.startedAt
          setStreaming(false)
        }, 400)
        return
      }
      const call = calls[i]
      /* 演示三种收尾：正常完成 / 失败(可重试) / 超时(可重试)。
         必须**在确认分支之前**声明：确认的续跑闭包会用到它，否则闭包捕获到 TDZ，
         用户点「允许」时会抛 "Cannot access 'kind' before initialization"，链路永远卡住。 */
      const kind = call.name === 'js.sandbox' ? 'error' : call.name === 'web.fetch' ? 'timeout' : 'done'
      /* 高风险工具：先挂起链路，等用户在确认卡上做决定（除非已"始终允许"） */
      if (call.requiresConfirm && !toolPolicy.isAllowed(call.name)) {
        setCalls(calls.map((c, j) => (j === i ? { ...c, status: 'awaiting' } : c)))
        setPendingConfirm({
          callId: call.id,
          name: call.name,
          argument: call.argument,
          explain: call.explain ?? '这一步会执行一个高风险操作，需要你确认后再继续。',
          risk: 'high',
          msgId: botId,
          sessionId,
        })
        /* 把"续跑"交给用户决定：允许→照常执行；拒绝→跳过这一步继续 */
        resumeRef.current = (decision) => {
          if (!alive()) return
          if (decision === 'deny') {
            calls[i] = { ...calls[i], status: 'cancelled' }
            setCalls([...calls])
            const rmDeny = runMeta.current.get(botId)
            if (rmDeny && rmDeny.firstProgressMs === undefined) rmDeny.firstProgressMs = Date.now() - rmDeny.startedAt
            step(i + 1)
            return
          }
          setCalls(calls.map((c, j) => (j === i ? { ...c, status: 'running' } : c)))
          setTimeout(() => {
            if (!alive()) return
            calls[i] = { ...calls[i], status: kind }
            setCalls([...calls])
            const rm = runMeta.current.get(botId)
            if (rm && rm.firstProgressMs === undefined) rm.firstProgressMs = Date.now() - rm.startedAt
            step(i + 1)
          }, kind === 'done' ? 800 : 1400)
        }
        return
      }
      setCalls(calls.map((c, j) => (j === i ? { ...c, status: 'running' } : c)))
      setTimeout(() => {
        if (!alive()) return
        calls[i] = { ...calls[i], status: kind }
        setCalls([...calls])
        /* 第一个工具出结果 = 首个进展（反馈里的"是不是太慢"就看它） */
        const rm = runMeta.current.get(botId)
        if (rm && rm.firstProgressMs === undefined) rm.firstProgressMs = Date.now() - rm.startedAt
        step(i + 1)
      }, kind === 'done' ? 800 : 1400)
    }
    step(0)
  }

  /*
   * 队列出队：等 streaming 的 state 真正落定再发（直接在 setStreaming(false) 后面调 sendText
   * 会读到还没更新的 streaming=true，消息又被塞回队列，形成死循环）。
   */
  useEffect(() => {
    if (streaming || queue.length === 0) return
    const [next, ...rest] = queue
    setQueue(rest)
    sendText(next)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [streaming, queue])

  /** 当前等待用户确认的工具（null 表示没有待确认项） */
  const [pendingConfirm, setPendingConfirm] = useState<(ConfirmRequest & { msgId: string; sessionId: string }) | null>(null)
  /** 链路被确认卡"挂起"时的续跑函数（允许/拒绝后调用） */
  const resumeRef = useRef<((decision: 'allow' | 'deny') => void) | null>(null)

  /** 用户在确认卡上做了决定：允许一次 / 始终允许 / 拒绝 */
  const decideConfirm = (decision: 'allow' | 'always' | 'deny') => {
    const pending = pendingConfirm
    const resume = resumeRef.current
    setPendingConfirm(null)
    resumeRef.current = null
    if (!pending) return
    if (decision === 'always') toolPolicy.allowAlways(pending.name)
    /* 拒绝 → 该工具落 cancelled（说明是用户拒绝，不是失败），链路**继续**往下走 */
    if (decision === 'deny') {
      patchMsg(pending.sessionId, pending.msgId, (m) => ({
        ...m,
        calls: (m.calls ?? []).map((c) => (c.id === pending.callId ? { ...c, status: 'cancelled' as const } : c)),
      }))
    }
    resume?.(decision === 'deny' ? 'deny' : 'allow')
  }

  /**
   * 停止：让令牌失效 → 所有排队中的回调不再改状态；
   * 正在跑的工具落成 cancelled（而不是永远 running），已产出的内容保留。
   */
  const stopRun = () => {
    runSeq.current += 1
    /* 服务端也要停：否则后端会继续生成并落库，前端只是"不看了" */
    const rid = requestIdRef.current
    if (!USE_MOCK && rid) {
      requestIdRef.current = null
      api.stream.stop(rid).catch(() => { /* 流可能已自然结束；失败不打扰用户 */ })
    }
    abortRef.current?.abort()
    const sessionId = active.id
    const lastBot = [...active.messages].reverse().find((m) => m.role === 'bot' && m.calls?.length)
    if (lastBot) {
      patchMsg(sessionId, lastBot.id, (m) => ({
        ...m,
        text: m.text || '（已停止，本次回答不完整）',
        calls: (m.calls ?? []).map((c) => (c.status === 'running' || c.status === 'idle' ? { ...c, status: 'cancelled' as const } : c)),
      }))
    }
    setInterrupted(true)
    setPendingConfirm(null)
    resumeRef.current = null
    setStreaming(false)
    /* 停止 = 这一轮与排队都停：留着队列会在停止后立刻自动发出去，反而不符合预期 */
    if (queue.length > 0) {
      toast.info('已清空待发送队列', `取消了 ${queue.length} 条排队消息`)
      setQueue([])
    }
    toast.info('已停止生成', '已完成的部分保留，可继续追问或点「继续生成」')
  }

  /** 中断后继续：真实模式发一条真实的"继续"追问（新的一轮，走 SSE）；mock 模式本地补完演示 */
  const continueRun = () => {
    const sessionId = active.id
    const lastBot = [...active.messages].reverse().find((m) => m.role === 'bot' && m.calls?.length)
    if (!lastBot) return
    if (!USE_MOCK && !isTempId(sessionId)) {
      /* 后端没有"续写同一条消息"的接口；以一轮真实追问继续，不能本地伪造回答 */
      setInterrupted(false)
      sendText('请接着上一条被中断的回答，从中断处继续输出剩余内容。')
      return
    }
    setInterrupted(false)
    setStreaming(true)
    const runId = (runSeq.current += 1)
    const alive = () => runSeq.current === runId
    patchMsg(sessionId, lastBot.id, (m) => ({
      ...m,
      calls: (m.calls ?? []).map((c) => (c.status === 'cancelled' ? { ...c, status: 'done' as const } : c)),
    }))
    setTimeout(() => {
      if (!alive()) return
      patchMsg(sessionId, lastBot.id, (m) => ({ ...m, text: REPLY }))
      setStreaming(false)
      toast.success('已继续生成', '补完了剩余部分')
    }, 1200)
  }

  /** CallChip 的重试：把失败的那条重新跑一遍 */
  const retryCall = (msgId: string, callId: string) => {
    const sessionId = active.id
    patchMsg(sessionId, msgId, (m) => ({
      ...m,
      calls: (m.calls ?? []).map((c) => (c.id === callId ? { ...c, status: 'running' as const } : c)),
    }))
    setTimeout(() => {
      patchMsg(sessionId, msgId, (m) => ({
        ...m,
        calls: (m.calls ?? []).map((c) => (c.id === callId ? { ...c, status: 'done' as const } : c)),
      }))
      toast.success('工具调用已重试成功', '这次没有报错')
    }, 1200)
  }

  /**
   * 从某条消息**分支出一条新对话**（取代原来的"原地重新生成"）。
   *
   * 语义差别很重要：
   * - 原地重新生成会**覆盖**原回答，历史就丢了；
   * - 分支保留原会话不动，新会话带着"到这条为止"的上下文继续问，
   *   于是两种问法可以并排对比（真实产品里常用于对比 prompt / 模型）。
   */
  /**
   * 从某条回答分支出一条新对话。
   * 真实模式调 POST /chat/conversations/{id}/fork：后端复制会话绑定与到该消息为止的内容，
   * 标题由前端按「分支 / 分支1 / …」算出，持久化在服务端，刷新/重开不丢。
   * mock（离线 QA）走本地 store 复制。
   */
  const branchFromMsg = async (msgId: string) => {
    if (streaming || branching) return
    if (USE_MOCK) {
      const id = branchFrom(msgId)
      if (id === null) return
      setStreaming(false)
      toast.success('已在新对话中分支', '原会话保持不变，可在新会话里换个问法继续')
      return
    }
    if (isTempId(active.id)) {
      toast.info('当前会话还没落库', '先发一条消息创建服务端会话后，才能创建持久化分支。')
      return
    }
    /* 只有后端雪花数字 id 才能作为截断点；m_ 前缀的本地消息则整会话复制 */
    const beforeId = /^\d+$/.test(msgId) ? msgId : null
    const title = nextBranchTitle(sessions.map((s) => s.title))
    setBranching(true)
    try {
      const created = await api.conversations.fork(active.id, { title, beforeMessageId: beforeId })
      addSession(toSessionItem(created))
      setStreaming(false)
      toast.success('已创建分支对话', title + '：原会话保持不变')
    } catch (e) {
      toast.error('创建分支失败', errMessage(e))
    } finally {
      setBranching(false)
    }
  }

  const copyMessage = async (text: string) => {
    try {
      if (!navigator.clipboard?.writeText) throw new Error('当前浏览器未授权剪贴板访问。')
      await navigator.clipboard.writeText(text)
      toast.success('已复制消息')
    } catch (error) {
      toast.error('复制失败', errMessage(error))
    }
  }

  const saveEdit = () => {
    if (!editing) return
    const text = editing.text.trim()
    const idx = active.messages.findIndex((m) => m.id === editing.id)
    setEditing(null)
    if (idx < 0 || !text) return
    /* 编辑 = 用新问题重跑一轮：截掉该消息之后的所有内容，改写问题文本，
       然后走与正常发送完全相同的真实链路（SSE / mock 模拟），不再本地伪造回答。 */
    patch(active.id, (s) => ({
      ...s,
      messages: [...s.messages.slice(0, idx), { ...s.messages[idx], text }],
    }))
    sendText(text, undefined, { skipUserPush: true })
  }

  const exportMd = () => {
    const md = [
      `# ${active.title}`,
      ``,
      `- Agent：${agentName}`,
      `- 知识库：${active.kbs.map(kbName).join('、') || '未绑定'}`,
      ``,
      ...active.messages.map((m) => `**${m.role === 'user' ? '我' : 'AI'}**：${m.text}${m.cites?.length ? `\n\n引用：${m.cites.join('、')}` : ''}`),
    ].join('\n')
    const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `${active.title}.md`
    a.click()
    URL.revokeObjectURL(a.href)
  }

  const toggleKb = async (kb: KbItem) => {
    const key = kbKey(kb)
    const next = active.kbs.includes(key)
      ? active.kbs.filter((item) => item !== key)
      : [...active.kbs, key]

    if (!USE_MOCK && !isTempId(active.id)) {
      if ((active.mode ?? 'PLAIN') !== 'RAG') {
        toast.error('当前模式不支持绑定知识库', '请先切换到「知识库问答」模式。')
        return
      }
      try {
        const updated = await api.conversations.update(active.id, { kbIds: next })
        patch(active.id, (session) => ({ ...session, kbs: updated.kbIds ?? next }))
      } catch (error) {
        toast.error('知识库绑定失败', errMessage(error))
      }
      return
    }

    patch(active.id, (session) => ({ ...session, kbs: next }))
  }

  const createConversation = async () => {
    if (creatingSession) return
    setCreatingSession(true)
    const selectedAgent = wizMode === 'AGENT' ? wizAgent : null
    const selectedKbIds = wizMode === 'RAG' && !USE_MOCK ? wizKbs : []
    const selectedKbNames = wizMode === 'RAG'
      ? wizKbs.map((key) => kbOptions.find((kb) => kbKey(kb) === key)?.name ?? key)
      : []
    try {
      if (USE_MOCK) {
        addSession({
          id: nextTmpId(),
          title: '新会话',
          mode: wizMode,
          agentId: selectedAgent,
          kbs: selectedKbNames,
          updated: '刚刚',
          pinned: false,
          messagesLoaded: true,
          messages: [],
        })
      } else {
        const created = await api.conversations.create({
          title: '新会话',
          mode: wizMode,
          agentId: selectedAgent,
          kbIds: selectedKbIds,
        })
        addSession(toSessionItem(created))
      }
      setWizard(false)
      const modeName = MODE_OPTIONS.find((option) => option.value === wizMode)?.label ?? wizMode
      toast.success('会话已创建', `${modeName}模式`)
    } catch (error) {
      toast.error('会话创建失败', errMessage(error))
    } finally {
      setCreatingSession(false)
    }
  }

  /*
   * 空列表守卫：**必须**。
   *
   * `active = sessions.find(...) ?? sessions[0]` 在列表为空时是 `undefined`
   * （新用户首次进入、或后端一条会话都没有时就是这个状态），
   * 而下面所有渲染代码都在读 `active.kbs` / `active.messages` ——
   * 没有这层守卫会直接 `TypeError: Cannot read properties of undefined`，
   * React 整棵树崩到 ErrorBoundary，表现为**白屏**。
   *
   * 这个 bug 是接后端后才出现的：原型期种子数据永远有 4 条会话，
   * `sessions[0]` 永不 undefined。
   */
  if (isEmptyList) {
    return (
      <div className="chat-fixed">
        <div className="chat-empty-first">
          <StateEmpty
            icon={<MessagesSquare size={20} />}
            title="还没有任何会话"
            desc="从侧栏点「新对话」开一个，或先在知识库里准备资料。"
            action={<button className="btn btn-primary" type="button" onClick={() => requestCreate()}>新对话</button>}
          />
        </div>
      </div>
    )
  }


  return (
    <div className="chat-fixed">
      <div className="chat-layout">

        {/* ---------- 对话主体 ---------- */}
        {/*
          不再挂 .panel：对话区是整屏工作面，不是卡片。
          .panel 带圆角 + 阴影，在窄屏会变成"屏幕里再套一张卡"，
          那是截图里那个整屏外框的真实来源。
        */}
        <section className="chat-main">
          <div className="chat-main-col" data-empty={!loadingMessages && active.messages.length === 0 && !streaming ? 'true' : undefined}>
          <header className="chat-head">
            <div className="chat-head__id">
              <div data-testid="chat-title" className="chat-head__title">{active.title}</div>
              <div data-testid="chat-meta" className="chat-head__meta">{active.messages.length} 条消息 · {active.updated}</div>
            </div>

            <div className="chat-head__tools">
            {/*
              会话模式切换（P1-1任务书要求"新增模式切换控件"）。

              放在 Agent 分段**左侧**：模式决定"这条会话怎么跑"，
              是比"用哪个 Agent"更上层的东西，视觉上也该更靠前。
              切换语义（后端按目标模式清理不兼容绑定）：
              PLAIN 清两者 / RAG 清 agentId / AGENT 清 kbIds。
              提示只说**响应里确实变了**的东西，见 switchMode 里的 cleaned。
            */}
            <div className="session-config" ref={configRef}>
              <button
                className="session-config__btn"
                type="button"
                onClick={() => setConfigOpen(v => !v)}
                aria-expanded={configOpen}
                aria-haspopup="dialog"
                title="会话设置：模式 / Agent / 知识库"
              >
                <Settings2 size={13} aria-hidden="true" />
                <span className="session-config__summary">
                  {(active.mode ?? 'PLAIN') === 'RAG'
                    ? `知识库问答 · ${active.kbs.length}`
                    : (active.mode ?? 'PLAIN') === 'AGENT'
                      ? agentName
                      : '普通对话'}
                </span>
                <ChevronDown size={12} aria-hidden="true" className={configOpen ? 'session-config__chev is-open' : 'session-config__chev'} />
              </button>

              {configOpen && (
                <div className="session-config__pop" role="dialog" aria-label="会话设置">
                  <div className="sc-section">
                    <div className="sc-label">模式</div>
                    {MODE_OPTIONS.map(o => (
                      <button
                        key={o.value}
                        type="button"
                        className="sc-opt"
                        data-on={(active.mode ?? 'PLAIN') === o.value ? '' : undefined}
                        aria-pressed={(active.mode ?? 'PLAIN') === o.value}
                        onClick={() => void switchMode(o.value)}
                      >
                        <Check size={13} className="sc-check" aria-hidden="true" />
                        <span>{o.label}</span>
                      </button>
                    ))}
                  </div>

                  <div className="sc-section">
                    <div className="sc-label">
                      Agent
                      {(active.mode ?? 'PLAIN') !== 'AGENT' && <span className="sc-hint">需切换到 Agent 模式</span>}
                    </div>
                    {agentOptions.map(o => (
                      <button
                        key={o.id ?? 'default'}
                        type="button"
                        className="sc-opt"
                        data-on={active.agentId === (o.id ?? null) ? '' : undefined}
                        disabled={(active.mode ?? 'PLAIN') !== 'AGENT'}
                        aria-pressed={active.agentId === (o.id ?? null)}
                        onClick={() => void switchAgent(o.id ?? null)}
                      >
                        <Check size={13} className="sc-check" aria-hidden="true" />
                        <span>{o.label}</span>
                      </button>
                    ))}
                  </div>

                  <div className="sc-section">
                    <div className="sc-label">
                      知识库
                      {(active.mode ?? 'PLAIN') !== 'RAG' && <span className="sc-hint">需切换到知识库问答</span>}
                    </div>
                    {kbsLoading && <p className="hint" style={{ margin: '4px 0 0' }}>正在加载…</p>}
                    {kbsError && (
                      <p className="form-error active" role="alert" style={{ margin: '4px 0 0' }}>
                        {kbsError} <button className="btn btn-ghost btn-sm" type="button" onClick={reloadKbs}>重试</button>
                      </p>
                    )}
                    {!kbsLoading && !kbsError && kbOptions.length === 0 && (
                      <p className="hint" style={{ margin: '4px 0 0' }}>还没有可绑定的知识库。</p>
                    )}
                    {!kbsLoading && !kbsError && kbOptions.map(kb => (
                      <button
                        key={kb.id}
                        type="button"
                        className="sc-opt"
                        data-on={active.kbs.includes(kbKey(kb)) ? '' : undefined}
                        disabled={(active.mode ?? 'PLAIN') !== 'RAG'}
                        aria-pressed={active.kbs.includes(kbKey(kb))}
                        onClick={() => void toggleKb(kb)}
                      >
                        <Check size={13} className="sc-check" aria-hidden="true" />
                        <BookOpen size={13} className="sc-opt-icon" aria-hidden="true" />
                        <span className="sc-opt-name">{kb.name}</span>
                      </button>
                    ))}
                  </div>
                </div>
              )}
            </div>

            <button className="btn btn-ghost btn-sm" type="button" onClick={exportMd} title="导出为 Markdown" aria-label="导出为 Markdown">
              <Download size={13} />
            </button>
            </div>
          </header>

          {/* 页面需要唯一 h1：用当前会话标题（视觉上由左侧列表承担，这里给辅助技术） */}
          <h1 className="sr-only">对话 · {active.title}</h1>
          <div className="thread-row">
          <div className="thread chat-swap" key={active.id} ref={threadRef}>
            {/* 决策 2：区分「还在拉消息」与「已加载但确实是空的」——
                否则懒加载期间会闪一下"还没有消息"，看起来像数据丢了。 */}
            {loadingMessages && (
              /* 复用项目现成的 Skeleton，不新造样式类（保持最小改动） */
              <div style={{ padding: '18px 4px' }}>
                <Skeleton w="62%" h={13} />
                <Skeleton w="88%" h={13} style={{ marginTop: 10 }} />
                <Skeleton w="45%" h={13} style={{ marginTop: 10 }} />
              </div>
            )}
            {!loadingMessages && active.messages.length === 0 && !streaming && (
              <div className="chat-empty-state">
                <h2>从一个问题开始</h2>
                <p>回答将保存在当前会话中，可随时继续或切换模式。</p>
              </div>
            )}
            {(() => {
              let qSeq = 0
              return active.messages.map((m, msgIdx) => {
              /* 提问序号（1-based）：导航高亮/跳转用它，避免雪花 id 撞车时多根同时亮 */
              const qidx = m.role === 'user' ? ++qSeq : undefined
              return (
              <div
                className={`msg ${m.role}`}
                key={`${m.id}-${msgIdx}`}
                data-mid={m.id}
                data-qidx={qidx}
              >
                <div>
                  {editing?.id === m.id ? (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                      <textarea className="input" rows={3} value={editing.text} onChange={(e) => setEditing({ id: m.id, text: e.target.value })} />
                      <div style={{ display: 'flex', gap: 8 }}>
                        <button className="btn btn-primary btn-sm" type="button" onClick={saveEdit}>保存并重发</button>
                        <button className="btn btn-ghost btn-sm" type="button" onClick={() => setEditing(null)}>取消</button>
                      </div>
                    </div>
                  ) : m.role === 'bot' ? (
                    <>
                      {m.calls && m.calls.length > 0 && (
                        <div className="tool-calls">
                          <span className="tool-calls-label">
                            <Wrench size={11} /> 工具调用
                          </span>
                          {m.calls.map((c) => (
                            <CallChip
                              key={c.id}
                              icon={c.icon}
                              name={c.name}
                              argument={c.argument}
                              status={c.status}
                              expectedMs={c.expectedMs}
                              size={32}
                              radius={9}
                              color="currentColor"
                              surfaceColor="var(--surface-muted)"
                              progressColor="var(--primary)"
                              progressOpacity={0.12}
                              doneColor="var(--success)"
                              errorColor="var(--danger)"
                              washOpacity={0.16}
                              /* 失败与超时都可以重试（超时不是错误，但用户同样需要"再试一次"） */
            onRetry={c.status === 'error' || c.status === 'timeout' ? () => retryCall(m.id, c.id) : undefined}
                            />
                          ))}
                        </div>
                      )}
                      {/* 上下文胶囊：说明这轮"拿到了什么"，与工具轨迹互补 */}
                      {m.role === 'bot' && m.context && (
                        <ContextChips
                          context={m.context}
                          onOpenRefs={() => setRefCitation(m.citationRefs?.[0] ?? (m.cites?.[0] ? { documentTitle: m.cites[0] } : null))}
                        />
                      )}
                      {/* 高风险工具：内联确认卡（不用弹窗 —— 确认是"这一步"的事） */}
                      {pendingConfirm && pendingConfirm.msgId === m.id && (
                        <ConfirmCard
                          request={pendingConfirm}
                          onAllowOnce={() => decideConfirm('allow')}
                          onAllowAlways={() => decideConfirm('always')}
                          onDeny={() => decideConfirm('deny')}
                        />
                      )}
                      {/* 结构化结果：可复制成 CSV/JSON、可下载（Markdown 里的表格取不走） */}
                      {m.role === 'bot' && m.result && <ResultCard result={m.result} />}
                      {m.text && <div className="bub"><Markdown text={m.text} theme={theme} /></div>}
                    </>
                  ) : (
                    <div className="bub">{m.text}</div>
                  )}
                  {m.role === 'bot' && m.cites && (
                    <div className="citation">
                      {m.cites.map((c, i) => {
                        const citation = m.citationRefs?.find((item) => item.documentTitle === c)
                        return (
                          <button key={`${c}-${i}`} className="cite-chip" type="button" data-clickable onClick={() => setRefCitation(citation ?? { documentTitle: c })} title={`在右侧查看「${c}」`}>
                            <BookOpen size={12} /><span>{c}</span>
                          </button>
                        )
                      })}
                    </div>
                  )}
                  {m.role === 'bot' && (
                    <span className="msg-state">
                      <StatusMark status="done" size={13} />
                      已完成{m.cites?.length ? ` · 引用 ${m.cites.length} 篇` : ''}
                    </span>
                  )}
                  {editing?.id !== m.id && (
                    <div className="msg-ops">
                      <button className="msg-op" type="button" title="复制消息" aria-label="复制消息" onClick={() => void copyMessage(m.text)}><Copy size={15} aria-hidden="true" /></button>
                      {m.role === 'user' ? (
                        <button className="msg-op" type="button" title="编辑并重新发送" aria-label="编辑并重新发送" onClick={() => setEditing({ id: m.id, text: m.text })}><Pencil size={15} aria-hidden="true" /></button>
                      ) : (
                        <button
                          className="msg-op"
                          type="button"
                          title="从此处创建分支"
                          aria-label="从此处创建分支"
                          disabled={branching || (!USE_MOCK && isTempId(active.id))}
                          onClick={() => void branchFromMsg(m.id)}
                        ><GitBranch size={15} aria-hidden="true" /></button>
                      )}
                      {m.role === 'bot' && interrupted && m.id === [...active.messages].reverse().find((x) => x.role === 'bot')?.id && (
                        <button className="msg-op" type="button" title="继续生成" aria-label="继续生成" onClick={continueRun}><RefreshCw size={15} aria-hidden="true" /></button>
                      )}
                      {/* 反馈：只有真正产出过文字的回答才问，避免"空回答也让人点赞" */}
                      {m.role === 'bot' && m.text.trim().length > 0 && (
                        <FeedbackBar
                          meta={{
                            msgId: m.id,
                            sessionId: active.id,
                            model: runMeta.current.get(m.id)?.model,
                            effort: runMeta.current.get(m.id)?.effort,
                            agent: agentName,
                            kbCount: active.kbs.length,
                            citedCount: m.cites?.length ?? 0,
                            tools: (m.calls ?? []).map((c) => ({ name: c.name, status: c.status, ms: c.expectedMs })),
                            firstProgressMs: runMeta.current.get(m.id)?.firstProgressMs,
                            totalMs: runMeta.current.get(m.id)?.totalMs,
                          }}
                        />
                      )}
                    </div>
                  )}
                </div>
              </div>
              )
              })
            })()}
            {(streaming || showSettledThought) && (
              <div className="msg bot">
                <div className="thinking-block" style={{ paddingTop: 8 }}>
                  <ThoughtLine
                    working={streaming}
                    label="正在生成回答"
                    doneLabel={interrupted ? '已停止' : '本轮已结束'}
                    steps={USE_MOCK ? [] : thoughtSteps}
                    showTimer
                    fontSize={13}
                  />
                </div>
              </div>
            )}
          </div>

          {/* 消息导航：右侧横线轨道 + Hover 预览（浮层 Portal 到 body） */}
          <MessageNavigator messages={active.messages} containerRef={threadRef} />
          </div>

          {/* 输入区：PromptBar（@ 数据源 / 命令 / 模型 / 档位 / 附件 / 停止） */}
          <div
            className="composer-wrap"
            data-expanded={composerExpanded ? 'true' : undefined}
            data-can-expand={!composerExpanded && composerLines > 3 ? 'true' : undefined}
          >
            {composerExpanded ? (
              <button
                type="button"
                className="composer-expand"
                aria-label="退出放大"
                aria-pressed="true"
                title="退出放大 (Esc)"
                onClick={() => setComposerExpanded(false)}
              >
                <Minimize2 size={15} aria-hidden="true" />
              </button>
            ) : composerLines > 3 ? (
              <button
                type="button"
                className="composer-expand"
                aria-label="放大输入框"
                title="放大输入框"
                onClick={() => setComposerExpanded(true)}
              >
                <Maximize2 size={15} aria-hidden="true" />
              </button>
            ) : null}
            <PromptBar
              placeholder="输入你的问题…"
              sources={promptSources}
              commands={PROMPT_COMMANDS}
              models={PROMPT_MODELS}
              efforts={PROMPT_EFFORTS}
              busy={streaming}
              expanded={composerExpanded}
              onLineCountChange={setComposerLines}
              maxRows={8}
              onSend={(t, detail) => sendText(t, detail)}
              onStop={stopRun}
              background="var(--surface)"
              color="var(--text)"
              menuBackground="var(--surface-muted)"
              sparkColor={sparkAccent}
              width={9999}
              radius={26}
            />
            <QueueStrip
              items={queue}
              onRemove={(i) => setQueue((q) => q.filter((_, j) => j !== i))}
              onClear={() => setQueue([])}
            />
            <p className="composer-hint">
              Agent：{agentName} · 知识库 {active.kbs.length} 个 · 输入 <code>@</code> 引用数据源，<code>/</code> 使用快捷命令
            </p>
            </div>
          </div>

          {/* 引用面板：点知识库引用后从右侧滑出，不打断对话 */}
          {refCitation && (
            <ReferencePanel
              citation={refCitation}
              theme={theme}
              onClose={() => setRefCitation(null)}
              onOpenInKb={onOpenDoc}
            />
          )}
        </section>
      </div>

      {/* ---------- 新建会话向导 ---------- */}
      <Modal
        open={wizard}
        title="新建会话"
        onClose={() => { if (!creatingSession) setWizard(false) }}
        shouldBlockClose={() => creatingSession}
      >
        <Stepper
          backButtonText="上一步"
          nextButtonText="继续"
          onFinalStepCompleted={() => { void createConversation() }}
        >
          <Step>
            <div className="wizard-field">
              <label>选择对话模式</label>
              <RubberSegment
                items={MODE_OPTIONS.map((option) => option.label)}
                value={MODE_OPTIONS.find((option) => option.value === wizMode)?.label ?? MODE_OPTIONS[0].label}
                onChange={(label) => {
                  const option = MODE_OPTIONS.find((item) => item.label === label)
                  if (option) setWizMode(option.value)
                }}
                size="sm"
                equalSlots
                trackColor="var(--surface-muted)"
                thumbColor="var(--primary-container)"
                textColor="var(--text-2)"
                activeTextColor="var(--primary-text)"
                aria-label="新会话模式"
              />
              <p className="hint" style={{ marginTop: 9 }}>
                普通对话不绑定资料；知识库问答使用所选知识库；Agent 模式使用已发布 Agent 的配置。
              </p>
            </div>

            {wizMode === 'AGENT' && (
              <div className="wizard-field" style={{ marginTop: 18 }}>
                <label>选择 Agent</label>
                <div className="wizard-opts">
                  {agentOptions.map((option) => (
                    <button
                      key={option.id ?? '__default__'}
                      type="button"
                      className="chip wizard-chip"
                      data-on={option.id === wizAgent ? '' : undefined}
                      aria-pressed={option.id === wizAgent}
                      onClick={() => setWizAgent(option.id)}
                    >
                      {option.label}
                    </button>
                  ))}
                </div>
              </div>
            )}

            {wizMode === 'RAG' && (
              <div className="wizard-field" style={{ marginTop: 18 }}>
                <label>绑定知识库（可多选）</label>
                {kbsLoading && <p className="hint">正在加载你的知识库…</p>}
                {kbsError && <p className="form-error active" role="alert">{kbsError} <button className="btn btn-ghost btn-sm" type="button" onClick={reloadKbs}>重试</button></p>}
                {!kbsLoading && !kbsError && kbOptions.length === 0 && <p className="hint">还没有知识库。你可以先创建空会话，再到知识库页面创建并导入资料。</p>}
                <div className="wizard-opts">
                  {kbOptions.map((kb) => {
                    const key = kbKey(kb)
                    return (
                      <button
                        key={kb.id}
                        type="button"
                        className="chip wizard-chip"
                        data-on={wizKbs.includes(key) ? '' : undefined}
                        aria-pressed={wizKbs.includes(key)}
                        onClick={() => setWizKbs((current) => current.includes(key) ? current.filter((item) => item !== key) : [...current, key])}
                      >
                        {kb.name}
                      </button>
                    )
                  })}
                </div>
              </div>
            )}
          </Step>
          <Step>
            <p style={{ margin: 0 }}>确认创建「{MODE_OPTIONS.find((option) => option.value === wizMode)?.label}」会话。</p>
            <p className="hint" style={{ marginTop: 10 }}>
              {wizMode === 'RAG'
                ? `将绑定 ${wizKbs.length} 个知识库。`
                : wizMode === 'AGENT'
                  ? `将使用 Agent「${agentNameOf(wizAgent)}」。`
                  : '会话创建后可以立即提问。'}
            </p>
            {creatingSession && <p role="status" className="hint">正在创建会话…</p>}
          </Step>
        </Stepper>
      </Modal>
    </div>
  )
}