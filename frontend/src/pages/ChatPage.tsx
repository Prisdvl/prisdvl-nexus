import { Fragment, useEffect, useLayoutEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import {
  AlertTriangle, BookOpen, Check, ChevronDown, ChevronRight, Copy, Download, GitBranch, Maximize2, MessagesSquare, Minimize2, Pencil, Pin, Plus, RefreshCw, Search, Settings2, Square, Trash2, X,
} from 'lucide-react'
import { HugeiconsIcon } from '@hugeicons/react'
import { Archive02Icon, Delete02Icon } from '@hugeicons/core-free-icons'
import MessageNavigator from '@/components/chat/MessageNavigator'
import ReferencePanel from '@/components/chat/ReferencePanel'
import FeedbackBar from '@/components/chat/FeedbackBar'
import ConfirmCard, { type ConfirmRequest } from '@/components/chat/ConfirmCard'
import ContextChips from '@/components/chat/ContextChips'
import ConversationTrace, { type TraceMeta } from '@/components/chat/ConversationTrace'
import ResultCard from '@/components/chat/ResultCard'
import QueueStrip from '@/components/chat/QueueStrip'
import { toolPolicy } from '@/lib/toolPolicy'
import '@/styles/reference.css'
import PromptBar, { type PromptBarSource } from '@/bits/PromptBar/PromptBar'
import LatticeLoader from '@/bits/LatticeLoader/LatticeLoader'
import PatternWaves from '@/bits/PatternWaves/PatternWaves'
import AgentThoughtChain from '@/components/chat/AgentThoughtChain'
import StatusMark from '@/bits/StatusMark/StatusMark'
import RubberSegment from '@/bits/RubberSegment/RubberSegment'
import Stepper, { Step } from '@/bits/Stepper/Stepper'
import Modal from '@/components/Modal'
import { StateEmpty, Skeleton } from '@/components/Skeleton'
import ParticleTitle from '@/components/ui/particle-title'
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
import { promptChatCommands, promptEfforts, promptModels } from '@/lib/promptBarPreset'
import { pagesCopy } from '@/lib/pagesCopy'
import { useLocale } from '@/lib/locale'
import { useTheme } from '@/lib/theme'
import { isWebGL2Available } from '@/lib/webgl'
import { LANDING_WAVE_COLOR, LANDING_WAVE_OPACITY, LANDING_WAVE_PARAMS } from '@/lib/landingWave'
import { buildStarters, type StarterDraft } from '@/lib/kbStarters'
import { useNavigate } from 'react-router'
import Loc from '@/components/Loc'

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

  /* 文案字典：`c` 在本文件里被大量回调形参（m.calls / m.cites 的 c）遮蔽，
     下面凡是需要字典的回调都把形参改名（call / cite），避免拿到错的 c。 */
  const { locale } = useLocale()
  const c = pagesCopy(locale).chat
  const tr = pagesCopy(locale).trace
  /* 知识库为空时给一个「去导入资料」的出口：对话页自己不占路由，借 App 的导航跳过去 */
  const navigate = useNavigate()

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
  /** 后端工具 code → 面向用户的步骤名（未知 code 原样展示，不编造功能） */
  const TOOL_STEP_NAME: Record<string, string> = {
    knowledge_search: c.toolStepSearch,
    calculator: c.toolStepCalc,
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
  const [chatView, setChatView] = useState<'chat' | 'trace'>('chat')
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
  /* 波浪背景的取色要按配色查表，路由只给了 theme，配色从主题 store 取 */
  const { palette } = useTheme()
  /*
   * WebGL2 探测只做一次（与落地页同一道门，见 lib/webgl.ts 的说明）：
   * 不支持就根本不挂背景层，退化为纯色底，而不是等 ogl 抛错再由错误边界兜。
   */
  const [webgl2Ok] = useState(() => isWebGL2Available())
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
        toast.error(c.loadFailTitle, errMessage(e))
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
      toast.success(c.agentSwitched, agentNameOf(agentId))
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
      toast.error(c.noAgentTitle, c.noAgentBody)
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
        needModeChange ? c.agentModeSwitched : c.agentSwitched,
        agentNameOf(agentId),
      )
    } catch (e) {
      toast.error(c.switchFailTitle, errMessage(e))
      /* 本地保持不变：宁可不动，也不能与后端不一致 */
    }
  }

  /* ---------------- 会话模式切换（PLAIN / RAG / AGENT） ---------------- */

  const MODE_OPTIONS: { value: ConversationMode; label: string }[] = [
    { value: 'PLAIN', label: c.modePlain },
    { value: 'RAG', label: c.modeRag },
    { value: 'AGENT', label: c.modeAgent },
  ]

  /**
   * 切模式 → `PUT /api/chat/conversations/{id}` 传 mode。
   *
   * 后端按目标模式清理不兼容绑定（PLAIN 清两者 / RAG 清 agentId / AGENT 清 kbIds），
   * 并重算 modelRole。**提示文案必须按"实际真的清理了什么"生成**——
   * 若某项本来就没绑定，就不提它，否则就是在撒谎。
   */
  /** 返回"切完之后是不是这个模式"：点示例问题要靠它决定能不能接着绑库、提问。 */
  const switchMode = async (mode: ConversationMode): Promise<boolean> => {
    if (!active) return false
    if ((active.mode ?? 'PLAIN') === mode) return true
    const prevMode = (active.mode ?? 'PLAIN') as ConversationMode

    if (USE_MOCK || isTempId(active.id)) {
      patch(active.id, (s) => ({
        ...s,
        mode,
        agentId: mode === 'AGENT' ? s.agentId : null,
        kbs: mode === 'RAG' ? s.kbs : [],
      }))
      toast.success(c.modeSwitchedTitle, c.modeSwitchedMock(prevMode, mode))
      return true
    }

    /* 切到 AGENT 但一个 Agent 都没有：先说清下一步，不假装绑定成功 */
    if (mode === 'AGENT' && agents.length === 0) {
      toast.error(
        c.modeNoAgentTitle,
        c.modeNoAgentBody,
      )
      return false
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
        cleaned.push(c.cleanedAgent)
      }
      if (active.kbs.length > 0 && (updated.kbIds ?? []).length === 0) {
        cleaned.push(c.cleanedKb)
      }
      const name = MODE_OPTIONS.find((o) => o.value === mode)?.label ?? mode
      toast.success(
        c.modeSwitchedTo(name),
        cleaned.length > 0
          ? c.modeSwitchedBody(cleaned)
          : c.historyKept,
      )
    } catch (e) {
      toast.error(c.switchFailTitle, errMessage(e))
      /* 本地不动：不先显示成功再等后端 */
      return false
    }
    return true
  }

  /* 每个会话各自记忆滚动位置：切走时存、切回时恢复（没记录过就落在最新一条） */
  const threadRef = useRef<HTMLDivElement>(null)
  const scrollMemo = useRef<Record<string, number>>({})

  const archiveSession = (id: string) => {
    archiveSessionStore(id)
    toast.info(c.archivedTitle, c.archivedBody)
  }

  const restoreSession = (id: string) => {
    restoreSessionStore(id)
    toast.success(c.restoredTitle, c.restoredBody)
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
    .map((kb) => ({ key: kbKey(kb), name: kb.name, description: c.kbSourceDesc, icon: <BookOpen size={14} /> }))
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
          if (!acc) addThoughtStep(c.stepGenerate)
          acc += t2
          patchMsg(sessionId, botId, (m) => ({ ...m, text: acc }))
        },
        onRetrieval: (d) => {
          if (!alive()) return
          /* 事件契约见 RetrievalEvent：count/hits[]/failedKnowledgeBases[]/emptyReason */
          const hit = Number(d?.count ?? 0)
          const hitsArr = Array.isArray(d?.hits) ? (d.hits as Record<string, unknown>[]) : []
          const usedDocs = new Set(
            hitsArr.map((h) => h.documentTitle).filter((v): v is string => typeof v === 'string' && !!v),
          ).size
          const failedKbs = Array.isArray(d?.failedKnowledgeBases) ? (d.failedKnowledgeBases as unknown[]) : []
          /*
           * 0 命中有两种截然不同的原因：没建索引（要用户去建）与确实没相关内容（换词句）。
           * 后端已经算好了，这里只负责带下来——不带的话界面只能显示「没找到」，用户做不出下一步。
           */
          const emptyReason = typeof d?.emptyReason === 'string' ? (d.emptyReason as 'NO_KB' | 'NO_INDEX' | 'NO_RESULTS') : null
          const kbIds = Array.isArray(d?.knowledgeBaseIds)
            ? (d.knowledgeBaseIds as unknown[]).map((v) => String(v)).filter((v) => v && v !== 'null' && v !== 'undefined')
            : []
          addThoughtStep(c.stepRetrieve(hit))
          if (failedKbs.length) addThoughtStep(c.stepRetrievePartial)
          patchMsg(sessionId, botId, (m) => ({
            ...m,
            context: {
              kbs: [],
              retrieved: hit,
              usedDocs,
              scannedDocs: hit,
              historyTurns: 0,
              web: false,
              ...(emptyReason ? { emptyReason } : {}),
              ...(kbIds.length ? { kbIds } : {}),
            },
          }))
        },
        onToolCall: (d) => {
          if (!alive()) return
          const toolName = String(d.tool ?? 'tool')
          addThoughtStep(c.toolStepLabel(TOOL_STEP_NAME[toolName] ?? toolName))
          patchMsg(sessionId, botId, (m) => {
            /*
             * 走了人工审批的工具：闸门在**执行前**挂起，`approval_required` 已经建过这一行，
             * 放行后这里只能原地改成"运行中"，再建一条就是同一件事两条记录。
             */
            const waiting = (m.calls ?? []).find((call) => call.name === toolName && call.status === 'awaiting')
            return {
              ...m,
              calls: waiting
                ? (m.calls ?? []).map((call) => (call.id === waiting.id
                  ? { ...call, status: 'running' as const, argument: typeof d.input === 'string' ? d.input : JSON.stringify(d.input ?? '') }
                  : call))
                : [
                    ...(m.calls ?? []),
                    {
                      id: `${Date.now()}-${toolName}`,
                      icon: 'search' as const,
                      name: toolName,
                      argument: typeof d.input === 'string' ? d.input : JSON.stringify(d.input ?? ''),
                      status: 'running' as const,
                    },
                  ],
            }
          })
        },
        onToolResult: (d) => {
          if (!alive()) return
          const summary = typeof d.resultSummary === 'string' ? d.resultSummary : ''
          patchMsg(sessionId, botId, (m) => ({
            ...m,
            /* 形参原为 `c`，会遮蔽文案字典 c，改名 call。
               已 cancelled 的那条是"用户拒绝"的结论（不是失败），工具结果的回流不能把它改写成"已完成"。 */
            calls: (m.calls ?? []).map((call) => (call.name === String(d.tool) && call.status !== 'cancelled'
              ? { ...call, status: 'done' as const, ...(summary ? { result: summary } : {}) }
              : call)),
          }))
        },
        /*
         * 工具被权限闸门拦下：后端已落库审批记录并挂起**同一条流**等决定。
         * 这里只做三件事——建一行"等待确认"、弹出已有的确认卡、把 runId 绑到决定按钮上。
         */
        onApprovalRequired: (d) => {
          if (!alive()) return
          const toolCode = String(d.toolCode ?? 'tool')
          const runId = String(d.runId ?? '')
          const arg = typeof d.arguments === 'string' ? d.arguments : JSON.stringify(d.arguments ?? '')
          const reason = typeof d.reason === 'string' ? d.reason : ''
          const callId = `ap_${runId}_${toolCode}`
          patchMsg(sessionId, botId, (m) => ({
            ...m,
            calls: [
              ...(m.calls ?? []),
              {
                id: callId, icon: 'terminal' as const, name: toolCode, argument: arg,
                status: 'awaiting' as const, requiresConfirm: true, explain: reason,
              },
            ],
          }))
          setPendingConfirm({
            callId, name: toolCode, argument: arg, explain: reason, risk: 'high',
            msgId: botId, sessionId,
          })
          resumeRef.current = (decision) => {
            if (!runId) return
            /* once / always / deny 由后端按 ApprovalScope 判定；放行后同一条流会继续推 tool_call */
            api.agentRuns.decide(runId, decision).catch((e) => {
              toast.error(c.chatFailTitle, errMessage(e))
              patchMsg(sessionId, botId, (m) => ({
                ...m,
                calls: (m.calls ?? []).map((call) => (call.id === callId ? { ...call, status: 'error' as const } : call)),
              }))
            })
          }
        },
        onDone: (d) => {
          if (!alive()) return
          const citationRefs = Array.isArray(d?.citations) ? d.citations as ChatCitationRef[] : []
          const cites = citationRefs.map((citation) => citation.documentTitle ?? c.unknownSource)
          patchMsg(sessionId, botId, (m) => ({ ...m, text: m.text || acc, ...(cites.length ? { cites, citationRefs } : {}) }))
          if (d?.stopped) setInterrupted(true)
        },
        onError: (m2) => {
          if (!alive()) return
          toast.error(c.genFailTitle, m2)
          patchMsg(sessionId, botId, (m) => ({ ...m, text: m.text || '> ⚠️ ' + m2 }))
        },
      }, ctrl.signal)
    } catch (e) {
      if (alive()) {
        toast.error(c.chatFailTitle, errMessage(e))
        patchMsg(sessionId, botId, (m) => ({ ...m, text: m.text || c.genFailBody }))
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
      toast.info(c.queuedTitle, c.queuedBody(queue.length + 1))
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
      { id: `c2-${botId}`, icon: 'file', name: 'doc.read', argument: DEFAULT_CITES[0] ?? c.docFallback, status: 'idle', expectedMs: 1200 },
      {
        id: `c3-${botId}`, icon: 'terminal', name: 'js.sandbox', argument: c.mockArgComplexity,
        status: 'idle', expectedMs: 1500,
        /* 高风险：要在沙箱里真的执行代码，必须先让用户点头 */
        requiresConfirm: true,
        explain: c.riskExplain,
      },
      /* 第 4 条演示"超时"：与失败区分开，文案与恢复动作都不同 */
      { id: `c4-${botId}`, icon: 'search', name: 'web.fetch', argument: c.mockArgDocs, status: 'idle', expectedMs: 9000 },
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
        /* 形参原为 `c`，会遮蔽文案字典 c，改名 call */
        setCalls(calls.map((call, j) => (j === i ? { ...call, status: 'awaiting' } : call)))
        setPendingConfirm({
          callId: call.id,
          name: call.name,
          argument: call.argument,
          explain: call.explain ?? c.riskFallback,
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
          setCalls(calls.map((call, j) => (j === i ? { ...call, status: 'running' } : call)))
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
      setCalls(calls.map((call, j) => (j === i ? { ...call, status: 'running' } : call)))
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
  /** 链路被确认卡"挂起"时的续跑函数（允许一次 / 始终允许 / 拒绝后调用） */
  const resumeRef = useRef<((decision: 'allow' | 'always' | 'deny') => void) | null>(null)

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
        /* 形参原为 `c`，会遮蔽文案字典 c，改名 call */
        calls: (m.calls ?? []).map((call) => (call.id === pending.callId ? { ...call, status: 'cancelled' as const } : call)),
      }))
    }
    resume?.(decision)
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
        text: m.text || c.stoppedBody,
        /* 形参原为 `c`，会遮蔽文案字典 c，改名 call */
        calls: (m.calls ?? []).map((call) => (call.status === 'running' || call.status === 'idle' ? { ...call, status: 'cancelled' as const } : call)),
      }))
    }
    setInterrupted(true)
    setPendingConfirm(null)
    resumeRef.current = null
    setStreaming(false)
    /* 停止 = 这一轮与排队都停：留着队列会在停止后立刻自动发出去，反而不符合预期 */
    if (queue.length > 0) {
      toast.info(c.queueClearedTitle, c.queueClearedBody(queue.length))
      setQueue([])
    }
    toast.info(c.stopTitle, c.stopBody)
  }

  /** 中断后继续：真实模式发一条真实的"继续"追问（新的一轮，走 SSE）；mock 模式本地补完演示 */
  const continueRun = () => {
    const sessionId = active.id
    const lastBot = [...active.messages].reverse().find((m) => m.role === 'bot' && m.calls?.length)
    if (!lastBot) return
    if (!USE_MOCK && !isTempId(sessionId)) {
      /* 后端没有"续写同一条消息"的接口；以一轮真实追问继续，不能本地伪造回答 */
      setInterrupted(false)
      sendText(c.continuePrompt)
      return
    }
    setInterrupted(false)
    setStreaming(true)
    const runId = (runSeq.current += 1)
    const alive = () => runSeq.current === runId
    patchMsg(sessionId, lastBot.id, (m) => ({
      ...m,
      /* 形参原为 `c`，会遮蔽文案字典 c，改名 call */
      calls: (m.calls ?? []).map((call) => (call.status === 'cancelled' ? { ...call, status: 'done' as const } : call)),
    }))
    setTimeout(() => {
      if (!alive()) return
      patchMsg(sessionId, lastBot.id, (m) => ({ ...m, text: REPLY }))
      setStreaming(false)
      toast.success(c.continueTitle, c.continueBody)
    }, 1200)
  }

  /** CallChip 的重试：把失败的那条重新跑一遍 */
  const retryCall = (msgId: string, callId: string) => {
    const sessionId = active.id
    patchMsg(sessionId, msgId, (m) => ({
      ...m,
      /* 形参原为 `c`，会遮蔽文案字典 c，改名 call */
      calls: (m.calls ?? []).map((call) => (call.id === callId ? { ...call, status: 'running' as const } : call)),
    }))
    setTimeout(() => {
      patchMsg(sessionId, msgId, (m) => ({
        ...m,
        calls: (m.calls ?? []).map((call) => (call.id === callId ? { ...call, status: 'done' as const } : call)),
      }))
      toast.success(c.retryOkTitle, c.retryOkBody)
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
      toast.success(c.branchMockTitle, c.branchMockBody)
      return
    }
    if (isTempId(active.id)) {
      toast.info(c.branchTempTitle, c.branchTempBody)
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
      toast.success(c.branchCreatedTitle, c.branchCreatedBody(title))
    } catch (e) {
      toast.error(c.branchFailTitle, errMessage(e))
    } finally {
      setBranching(false)
    }
  }

  const copyMessage = async (text: string) => {
    try {
      if (!navigator.clipboard?.writeText) throw new Error(c.copyDenied)
      await navigator.clipboard.writeText(text)
      toast.success(c.copyMsgToast)
    } catch (error) {
      toast.error(c.copyFailTitle, errMessage(error))
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
      c.exportAgent(agentName),
      c.exportKb(active.kbs.map(kbName).join(c.listSep) || c.exportKbNone),
      ``,
      ...active.messages.map((m) => `**${m.role === 'user' ? c.exportRoleUser : c.exportRoleAi}**${c.exportRoleSep}${m.text}${m.cites?.length ? `\n\n${c.exportCites(m.cites.join(c.listSep))}` : ''}`),
    ].join('\n')
    const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `${active.title}.md`
    a.click()
    URL.revokeObjectURL(a.href)
  }

  /**
   * 绑定 / 解绑知识库，返回"现在是不是已经处在目标状态"。
   *
   * 抽成带方向的版本，是因为点示例问题需要**幂等绑定**：那里不能"再点一下反而解绑"，
   * 也不能让问题看起来带着库问了、实际没绑上（所以失败返回 false，调用方就不再发问）。
   * 模式校验、鉴权、后端调用全部沿用原来那一份，没有另开捷径。
   */
  const setKbBound = async (kb: KbItem, bound: boolean): Promise<boolean> => {
    const key = kbKey(kb)
    const has = active.kbs.includes(key)
    if (bound === has) return true
    const next = bound
      ? Array.from(new Set([...active.kbs, key]))
      : active.kbs.filter((item) => item !== key)

    if (!USE_MOCK && !isTempId(active.id)) {
      if ((active.mode ?? 'PLAIN') !== 'RAG') {
        toast.error(c.kbModeTitle, c.kbModeBody)
        return false
      }
      try {
        const updated = await api.conversations.update(active.id, { kbIds: next })
        patch(active.id, (session) => ({ ...session, kbs: updated.kbIds ?? next }))
      } catch (error) {
        toast.error(c.kbBindFailTitle, errMessage(error))
        return false
      }
      return true
    }

    patch(active.id, (session) => ({ ...session, kbs: next }))
    return true
  }

  const toggleKb = async (kb: KbItem) => {
    await setKbBound(kb, !active.kbs.includes(kbKey(kb)))
  }

  /*
   * 欢迎区示例问题：输入只有用户自己的知识库（名字 / 描述 / 有没有资料），
   * 规则在 lib/kbStarters.ts，文案在 pagesCopy —— 这里只做两件事：拼当前语言的问句、执行点击。
   * 不进 useMemo：kbOptions 在 mock 下是模块常量、真实下是 useLoad 的 state，
   * 过滤+切片只有几个元素，不值得为它引依赖。
   */
  const starterKbs = kbOptions.map((kb) => ({ key: kbKey(kb), name: kb.name, desc: kb.desc, docs: kb.docs }))
  const starters = buildStarters(starterKbs)

  /*
   * 只有真实后端才谈得上"列表加载中 / 拉取失败"：mock 下 kbOptions 就是本地常量 KBS，
   * 套用失败态会在离线演示里凭空报一句"列表没加载出来"。
   */
  const startersPending = !USE_MOCK && kbsLoading
  const startersFailed = !USE_MOCK && Boolean(kbsError)

  /** 草稿 → 当前语言的问句：模板到文案的映射只写在这里，别处不再重复一遍。 */
  const starterText = (draft: StarterDraft): string =>
    draft.template === 'topic'
      ? c.starterTopic(draft.topic ?? '', kbName(draft.kbKey))
      : draft.template === 'pitfalls'
        ? c.starterPitfalls(kbName(draft.kbKey))
        : c.starterOverview(kbName(draft.kbKey))

  /**
   * 点示例问题 = 切模式 → 绑库 → 发消息，三步都走现成路径（含权限与后端校验）。
   *
   * 任何一步没成功就停下：不能出现"问题按知识库生成、实际没绑定"的假象。
   * 失败时 setKbBound / switchMode 已按原有规则弹出提示（含"必须切到知识库问答模式"）。
   */
  const useStarter = async (draft: StarterDraft) => {
    const kb = kbOptions.find((item) => kbKey(item) === draft.kbKey)
    if (kb) {
      if ((active.mode ?? 'PLAIN') !== 'RAG' && !(await switchMode('RAG'))) return
      if (!(await setKbBound(kb, true))) return
    }
    sendText(starterText(draft))
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
          title: c.defaultTitle,
          mode: wizMode,
          agentId: selectedAgent,
          kbs: selectedKbNames,
          updated: c.justNow,
          pinned: false,
          messagesLoaded: true,
          messages: [],
        })
      } else {
        const created = await api.conversations.create({
          title: c.defaultTitle,
          mode: wizMode,
          agentId: selectedAgent,
          kbIds: selectedKbIds,
        })
        addSession(toSessionItem(created))
      }
      setWizard(false)
      const modeName = MODE_OPTIONS.find((option) => option.value === wizMode)?.label ?? wizMode
      toast.success(c.createdTitle, c.createdBody(modeName))
    } catch (error) {
      toast.error(c.createFailTitle, errMessage(error))
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
  const traceMeta: Record<string, TraceMeta> = Object.fromEntries(runMeta.current)
  const traceModel = [...active.messages].reverse().map((m) => runMeta.current.get(m.id)?.model).find(Boolean)

  /**
   * 「新对话」空态：有会话、但这条会话还没有消息。
   *
   * 它同时决定三件事，所以提成一个变量、不在三处各写一遍条件：
   *   · `.chat-main-col` 的 `data-empty`（输入框居中、正文列收起）
   *   · 标题的粒子动画（ParticleTitle）
   *   · 落地页同款的波浪背景层
   * 一旦发出第一条消息（或正在流式输出）就全部撤掉 ——「开始对话之后」不再铺背景。
   */
  const emptyChat = !loadingMessages && active.messages.length === 0 && !streaming

  /*
   * 新建会话向导必须**在这里**就定义好：下面 isEmptyList 分支会提前 return，
   * 若向导只挂在正常分支的 JSX 里，0 会话（刚注册的新账号）点「新对话」时
   * 只会 setWizard(true)，而页面上根本没有那个 Modal —— 表现就是按钮完全没反应。
   */
  const wizardModal = (
    <Modal
      open={wizard}
      title={c.wizardTitle}
      onClose={() => { if (!creatingSession) setWizard(false) }}
      shouldBlockClose={() => creatingSession}
    >
      <Stepper
        backButtonText={c.stepBack}
        nextButtonText={c.stepNext}
        finalButtonText={c.wizardCreate}
        onFinalStepCompleted={() => { void createConversation() }}
      >
        <Step>
          <div className="wizard-field">
            <label><Loc>{c.wizardModeLabel}</Loc></label>
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
              aria-label={c.wizardModeAria}
            />
            <p className="hint" style={{ marginTop: 9 }}>
              <Loc>{c.wizardModeHint}</Loc>
            </p>
          </div>

          {wizMode === 'AGENT' && (
            <div className="wizard-field" style={{ marginTop: 18 }}>
              <label><Loc>{c.wizardAgentLabel}</Loc></label>
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
              <label><Loc>{c.wizardKbLabel}</Loc></label>
              {kbsLoading && <p className="hint">{c.wizardKbLoading}</p>}
              {kbsError && <p className="form-error active" role="alert">{kbsError} <button className="btn btn-ghost btn-sm" type="button" onClick={reloadKbs}><Loc>{c.retry}</Loc></button></p>}
              {!kbsLoading && !kbsError && kbOptions.length === 0 && <p className="hint"><Loc>{c.wizardKbEmpty}</Loc></p>}
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
          <p style={{ margin: 0 }}><Loc>{c.wizardConfirm(MODE_OPTIONS.find((option) => option.value === wizMode)?.label ?? '')}</Loc></p>
          <p className="hint" style={{ marginTop: 10 }}>
            {wizMode === 'RAG'
              ? <Loc>{c.wizardBindCount(wizKbs.length)}</Loc>
              : wizMode === 'AGENT'
                ? <Loc>{c.wizardUseAgent(agentNameOf(wizAgent))}</Loc>
                : <Loc>{c.wizardPlainReady}</Loc>}
          </p>
          {creatingSession && <p role="status" className="hint"><Loc>{c.wizardCreating}</Loc></p>}
        </Step>
      </Stepper>
    </Modal>
  )

  if (isEmptyList) {
    return (
      <div className="chat-fixed">
        <div className="chat-empty-first">
          <StateEmpty
            icon={<MessagesSquare size={20} />}
            title={c.emptyTitle}
            desc={c.emptyDesc}
            action={<button className="btn btn-primary" type="button" onClick={() => requestCreate()}><Loc>{c.newChat}</Loc></button>}
          />
        </div>
        {wizardModal}
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
          {/*
            新对话空态：铺一层**与落地页 hero 同款**的波浪背景。
            参数/取色见 lib/landingWave.ts —— 两处共用一份定义，不各写一份。

            为什么放在 .chat-main 内部而不是用全局 fixed 层：
            对话区自己有 --bg 底色，fixed 层会被它整片盖住，只剩"两个背景叠加"的浑浊；
            放在这里则是"底色 + 波浪"，与落地页的构成一致，也不会漫到侧栏底下。
            一旦开始对话（emptyChat 为假）整层卸载，背景立刻收回。
          */}
          {emptyChat && webgl2Ok && (
            <div className="chat-waves" aria-hidden="true">
              <PatternWaves
                {...LANDING_WAVE_PARAMS}
                color={LANDING_WAVE_COLOR[palette][theme]}
                opacity={theme === 'dark' ? LANDING_WAVE_OPACITY.dark : LANDING_WAVE_OPACITY.light}
              />
            </div>
          )}
          <div className="chat-main-col" data-empty={emptyChat ? 'true' : undefined}>
          <header className="chat-head">
            <div className="chat-head__id">
              <div data-testid="chat-title" className="chat-head__title">{active.title}</div>
              <div data-testid="chat-meta" className="chat-head__meta">{c.metaLine(active.messages.length, active.updated)}</div>
              {/*
                「对话 / 轨迹」只在**已经有内容**之后才出现：
                新对话（还没发第一条消息）时两栏都是空的，顶着一组切换器既没有
                可切的东西，也让空态页面显得像后台面板。发第一条消息后再出现。
              */}
              {!emptyChat && (
                <RubberSegment
                  className="chat-view-tabs"
                  size="sm"
                  items={[
                    { value: 'chat', label: tr.viewChat },
                    { value: 'trace', label: tr.viewTrace },
                  ]}
                  value={chatView}
                  onChange={(v) => setChatView(v as 'chat' | 'trace')}
                  aria-label={`${tr.viewChat} / ${tr.viewTrace}`}
                />
              )}
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
                title={c.configTitle}
              >
                <Settings2 size={13} aria-hidden="true" />
                <span className="session-config__summary">
                  {(active.mode ?? 'PLAIN') === 'RAG'
                    ? c.summaryRag(active.kbs.length)
                    : (active.mode ?? 'PLAIN') === 'AGENT'
                      ? agentName
                      : c.modePlain}
                </span>
                <ChevronDown size={12} aria-hidden="true" className={configOpen ? 'session-config__chev is-open' : 'session-config__chev'} />
              </button>

              {configOpen && (
                <div className="session-config__pop" role="dialog" aria-label={c.configAria}>
                  <div className="sc-section">
                    <div className="sc-label"><Loc>{c.modeLabel}</Loc></div>
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
                      <Loc>{c.agentLabel}</Loc>
                      {(active.mode ?? 'PLAIN') !== 'AGENT' && <span className="sc-hint"><Loc>{c.agentNeedMode}</Loc></span>}
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
                      <Loc>{c.kbLabel}</Loc>
                      {(active.mode ?? 'PLAIN') !== 'RAG' && <span className="sc-hint"><Loc>{c.kbNeedRag}</Loc></span>}
                    </div>
                    {kbsLoading && <p className="hint" style={{ margin: '4px 0 0' }}>{c.kbLoading}</p>}
                    {kbsError && (
                      <p className="form-error active" role="alert" style={{ margin: '4px 0 0' }}>
                        {kbsError} <button className="btn btn-ghost btn-sm" type="button" onClick={reloadKbs}><Loc>{c.retry}</Loc></button>
                      </p>
                    )}
                    {!kbsLoading && !kbsError && kbOptions.length === 0 && (
                      <p className="hint" style={{ margin: '4px 0 0' }}>{c.kbEmptyBind}</p>
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

            <button className="btn btn-ghost btn-sm" type="button" onClick={exportMd} title={c.exportMd} aria-label={c.exportMd}>
              <Download size={13} />
            </button>
            </div>
          </header>

          {/* 页面需要唯一 h1：用当前会话标题（视觉上由左侧列表承担，这里给辅助技术） */}
          <h1 className="sr-only">{c.h1Title(active.title)}</h1>
          <div className="thread-row">
          {chatView === 'trace' ? (
            <ConversationTrace
              messages={active.messages}
              meta={traceMeta}
              agent={agentName}
              model={traceModel}
              mode={MODE_OPTIONS.find((o) => o.value === (active.mode ?? 'PLAIN'))?.label ?? ''}
              kbs={active.kbs.map(kbName)}
            />
          ) : (
          <>
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
            {emptyChat && (
              <div className="chat-empty-state chat-empty-state--particle">
                {/* 粒子标题：进入新对话播一次「散开 → 聚拢」，之后静止（见 components/ui/particle-title.tsx） */}
                <ParticleTitle text={c.startTitle} />
                <p><Loc>{c.startDesc}</Loc></p>
                {/*
                  示例问题：按用户自己的知识库生成（规则 lib/kbStarters.ts，文案 pagesCopy）。
                  列表还在加载时什么都不显示 —— 宁可晚半秒出现，也不先摆一组跟他资料无关的问题。
                  三种"给不出问题"的情况各自说清下一步，且都不挡输入框：
                  拉取失败 → 可重试；没有库 / 库是空的 → 去导入资料。
                */}
                {!startersPending && (
                  starters.length > 0 ? (
                    <div className="chat-starters" aria-label={c.startersAria}>
                      {starters.map((draft) => (
                        <button
                          key={`${draft.kbKey}-${draft.template}`}
                          type="button"
                          className="chat-starters__item"
                          onClick={() => void useStarter(draft)}
                        >
                          {starterText(draft)}
                        </button>
                      ))}
                    </div>
                  ) : startersFailed ? (
                    <p className="chat-starters__note">
                      <Loc>{c.starterListFailed}</Loc>{' '}
                      <button className="btn btn-ghost btn-sm" type="button" onClick={reloadKbs}><Loc>{c.retry}</Loc></button>
                    </p>
                  ) : starterKbs.length === 0 ? (
                    <p className="chat-starters__note">
                      <Loc>{c.starterNoKb}</Loc>{' '}
                      <button className="btn btn-ghost btn-sm" type="button" onClick={() => navigate('/app/kb')}><Loc>{c.starterGoImport}</Loc></button>
                    </p>
                  ) : (
                    <p className="chat-starters__note">
                      <Loc>{c.starterEmptyKb}</Loc>{' '}
                      <button className="btn btn-ghost btn-sm" type="button" onClick={() => navigate('/app/kb')}><Loc>{c.starterGoImport}</Loc></button>
                    </p>
                  )
                )}
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
                        <button className="btn btn-primary btn-sm" type="button" onClick={saveEdit}><Loc>{c.saveResend}</Loc></button>
                        <button className="btn btn-ghost btn-sm" type="button" onClick={() => setEditing(null)}><Loc>{c.cancel}</Loc></button>
                      </div>
                    </div>
                  ) : m.role === 'bot' ? (
                    <>
                      {m.calls && m.calls.length > 0 && (
                        <AgentThoughtChain
                          calls={m.calls}
                          /*
                           * 必须把"这一轮还在流式"告诉时间线：两个工具之间（模型正在想下一步）没有任何
                           * call 处于 running，只按 call 状态推断的话摘要会当场显示「已完成」，而回答还没开始生成。
                           */
                          working={streaming && msgIdx === active.messages.length - 1}
                          context={m.context}
                          citations={m.cites?.length}
                          elapsedMs={runMeta.current.get(m.id)?.totalMs}
                          onRetry={(callId) => retryCall(m.id, callId)}
                        />
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
                      {m.cites.map((cite, i) => {
                        /* 形参原为 `c`，会遮蔽文案字典 c，改名 cite */
                        const citation = m.citationRefs?.find((item) => item.documentTitle === cite)
                        return (
                          <button key={`${cite}-${i}`} className="cite-chip" type="button" data-clickable onClick={() => setRefCitation(citation ?? { documentTitle: cite })} title={c.viewCite(cite)}>
                            <BookOpen size={12} /><span>{cite}</span>
                          </button>
                        )
                      })}
                    </div>
                  )}
                  {m.role === 'bot' && (m.context?.emptyReason === 'NO_INDEX' || m.context?.emptyReason === 'NO_KB') && (
                    /* 0 命中要分清两种相反的出路：库里没建索引（去详情页建索引）与
                       会话没绑库（去打开会话配置）。只报「没找到」会把配置问题说成查证结果。 */
                    <p className="cite-hint">
                      <AlertTriangle size={13} aria-hidden="true" />
                      <span>{m.context.emptyReason === 'NO_KB' ? c.retrievalNoKb : c.retrievalNoIndex}</span>
                      {m.context.emptyReason === 'NO_KB' ? (
                        <button type="button" className="cite-hint__cta" onClick={() => setConfigOpen(true)}>
                          {c.retrievalNoKbCta}
                        </button>
                      ) : m.context.kbIds?.[0] ? (
                        <a className="cite-hint__cta" href={`#/app/kb/${m.context.kbIds[0]}`} data-clickable>
                          {c.retrievalNoIndexCta}
                        </a>
                      ) : null}
                    </p>
                  )}
                  {m.role === 'bot' && (
                    <span className="msg-state">
                      <StatusMark status="done" size={13} />
                      <Loc>{c.done}</Loc>{m.cites?.length ? <> · <Loc>{c.doneWithCites(m.cites.length)}</Loc></> : null}
                    </span>
                  )}
                  {editing?.id !== m.id && (
                    <div className="msg-ops">
                      <button className="msg-op" type="button" title={c.copyMsg} aria-label={c.copyMsg} onClick={() => void copyMessage(m.text)}><Copy size={15} aria-hidden="true" /></button>
                      {m.role === 'user' ? (
                        <button className="msg-op" type="button" title={c.editResend} aria-label={c.editResend} onClick={() => setEditing({ id: m.id, text: m.text })}><Pencil size={15} aria-hidden="true" /></button>
                      ) : (
                        <button
                          className="msg-op"
                          type="button"
                          title={c.branchFromAria}
                          aria-label={c.branchFromAria}
                          disabled={branching || (!USE_MOCK && isTempId(active.id))}
                          onClick={() => void branchFromMsg(m.id)}
                        ><GitBranch size={15} aria-hidden="true" /></button>
                      )}
                      {m.role === 'bot' && interrupted && m.id === [...active.messages].reverse().find((x) => x.role === 'bot')?.id && (
                        <button className="msg-op" type="button" title={c.continueGen} aria-label={c.continueGen} onClick={continueRun}><RefreshCw size={15} aria-hidden="true" /></button>
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
                            /* 形参原为 `c`，会遮蔽文案字典 c，改名 call */
                            tools: (m.calls ?? []).map((call) => ({ name: call.name, status: call.status, ms: call.expectedMs })),
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
            {(streaming || showSettledThought) && !active.messages[active.messages.length - 1]?.calls?.length && (
              <div className="msg bot">
                <AgentThoughtChain calls={[]} working={streaming} steps={thoughtSteps} />
              </div>
            )}
          </div>

          {/* 消息导航：右侧横线轨道 + Hover 预览（浮层 Portal 到 body） */}
          <MessageNavigator messages={active.messages} containerRef={threadRef} />
          </>
          )}
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
                aria-label={c.collapseAria}
                aria-pressed="true"
                title={c.collapseTitle}
                onClick={() => setComposerExpanded(false)}
              >
                <Minimize2 size={15} aria-hidden="true" />
              </button>
            ) : composerLines > 3 ? (
              <button
                type="button"
                className="composer-expand"
                aria-label={c.expandAria}
                title={c.expandTitle}
                onClick={() => setComposerExpanded(true)}
              >
                <Maximize2 size={15} aria-hidden="true" />
              </button>
            ) : null}
            <PromptBar
              placeholder={c.composerPh}
              sources={promptSources}
              commands={promptChatCommands(locale)}
              models={promptModels(locale)}
              efforts={promptEfforts(locale)}
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
              <Loc>{c.hintKb(agentName, active.kbs.length)}</Loc>{' '}<code>@</code>{' '}<Loc>{c.hintAt}</Loc><code>/</code>{' '}<Loc>{c.hintSlash}</Loc>
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

      {wizardModal}
    </div>
  )
}
