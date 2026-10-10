<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useConfirm } from '@/components/common/useConfirm'

// confirm() 取代手写的 ElMessageBox.confirm；
// ElMessageBox 仍保留给 renameConversation 的**输入框**（prompt）——
// ConfirmDialog 没有输入能力，那是有意的取舍，不是遗漏。
const { confirm } = useConfirm()
import {
  ChatDotRound,
  Delete,
  EditPen,
  Menu,
  Promotion,
  Plus,
  VideoPause
} from '@element-plus/icons-vue'
import {
  bindAgent,
  bindKnowledgeBase,
  createConversation,
  deleteConversation,
  listConversations,
  listMessages,
  renameConversation,
  resetConversationAgent,
  stopStream,
  streamChat,
  type ChatCitation,
  type Conversation,
  type MessageToolCall,
  type StreamRetrieval
} from '@/api/chat'
import {
  listAgents,
  type AgentResource,
  type AgentStatus,
  type AnswerPolicy
} from '@/api/agents'
import { listKnowledgeBases, type KnowledgeBase } from '@/api/knowledge'
import { getQuota, type Quota } from '@/api/user'
import { renderMarkdown } from '@/utils/markdown'

/**
 * 对话（Agent 模式 / 知识库问答 RAG 模式）。
 *
 * 两种模式的差别只在"检索由谁发起"，界面上的过程都看得见：
 *   AGENT：meta → tool_call（正在检索…）→ tool_result（命中多少条）→ delta… → done
 *   RAG　：meta → retrieval（检索到 N 个片段）→ delta… → done
 *
 * 界面上的四条原则：
 * 1. **实时**：检索与回答都是边发生边显示，不让用户对着空屏等；
 * 2. **可回看**：工具痕迹随消息落库，刷新/切换会话后仍在（RAG 的命中片段目前只在本次流里可见）；
 * 3. **可中断**：生成中随时能停，停掉是真的断开上游（不是只关界面）；
 * 4. **不装作检索了**：RAG 会话没绑知识库时服务端会明确报错，界面上也提前拦住。
 *
 * Phase 4.2 增补：AGENT 会话要能选"用哪个 Agent"（提示词 + 模型 + 检索范围都由它决定）。
 * 选择器里只列**已发布**的 Agent——草稿/停用的选了会被服务端拒绝（5008/5009），
 * 与其让用户到提问时才发现，不如在这里就标出为什么不能选。
 */

/** 界面上的一条消息。流式进行中的那条是临时消息，结束后用服务端数据校正。 */
interface ChatItem {
  id: string
  role: 'USER' | 'ASSISTANT'
  content: string
  toolCalls: MessageToolCall[]
  /** RAG 模式的检索结果（本次流内有效；历史消息不回放，见 toItems 的说明） */
  retrieval?: StreamRetrieval | null
  /**
   * 这条回答引用的资料片段，**随消息落库**，因此刷新/切换会话后仍在。
   *
   * 与上面的 `retrieval` 分工：那个是"这次搜到了什么"（含重排、截断、失败的库），
   * 只活在本次流里；这个是"这条回答照着什么说的"（含模型有没有真的引用），
   * 是历史记录。RAG 的流内视图里两者都在，此时只渲染检索卡片
   * （它已经把片段列出来了，再列一遍是同一件事说两遍）。
   */
  citations?: ChatCitation[]
  /**
   * 产出这条回答的 Agent（5.4，只有助手消息有）。
   *
   * 流式进行中的临时消息拿不到它——id 要等落库后由服务端给。所以下面渲染的是
   * "已知才显示"，而不是"显示不出来就当作内置默认"：猜一个名字比不显示更糟，
   * 用户会把猜错的那条当成事实。
   */
  agentId?: string | null
  streaming: boolean
  failed?: boolean
}

/** 工具"执行中"的占位结果文案：与真实结果文案不可能相同，用作状态标记 */
const PENDING = '检索中…'

const conversations = ref<Conversation[]>([])
const activeId = ref('')
const items = ref<ChatItem[]>([])
const input = ref('')
const loadingList = ref(false)
const loadingMessages = ref(false)
const streaming = ref(false)

/** 新建会话时选择的模式与知识库：只有"新建"这一刻能选（已建会话的模式不可更改） */
const draftMode = ref<'AGENT' | 'RAG'>('AGENT')
/** 可多选（Phase 5.5）：一次提问同时检索多个库，答案会标明每段出自哪个库 */
const draftKbIds = ref<string[]>([])
const knowledgeBases = ref<KnowledgeBase[]>([])

/** AGENT 会话选用的 Agent（'' = 内置默认）。已建会话可在头部换，草稿/停用的不列出来 */
const draftAgentId = ref('')
const agents = ref<AgentResource[]>([])
/** 只有已发布的能被选用：其余的在选择器下方给一句解释，不静默隐藏 */
const usableAgents = computed(() => agents.value.filter((agent) => agent.status === 'PUBLISHED'))
const blockedAgents = computed(() =>
  agents.value.filter((agent) => agent.status !== 'PUBLISHED' && !agent.builtin)
)

/**
 * 草稿会话当前会用的 Agent 的回答策略（'' = 内置默认）。
 *
 * 用在"还没有知识库"那句提示里：同样是一个空知识库，选「只依据笔记」与用默认的
 * 「优先用笔记」，用户该期待的结果完全不同——提示必须跟着策略走，不能写死一句。
 */
const draftAnswerPolicy = computed<AnswerPolicy>(() => {
  const selected = draftAgentId.value
    ? usableAgents.value.find((agent) => agent.id === draftAgentId.value)
    : agents.value.find((agent) => agent.builtin)
  return selected?.answerPolicy ?? 'KB_PREFERRED'
})

/**
 * 用户是否已经自己做过选择（点了"新对话"或点了某个会话）。
 *
 * onMounted 里的"进来就选中最近一次会话"要跨两个 RPC（会话列表、知识库列表），
 * 在这段时间里用户完全可能已经点了"新对话"。如果那边回来后无条件套用默认选中，
 * 就会把用户的操作覆盖掉：明明点了新对话，界面又跳回旧会话、旧消息重新铺满屏幕，
 * 用户接着发的消息会被算到旧会话名下。所以默认选中只在"用户还没动过"时生效。
 */
const pickedByUser = ref(false)

/**
 * 会话消息加载的请求序号（修"已经在飞的那次自动选中"）。
 *
 * `pickedByUser` 只挡住了"用户点过之后**才开始**的自动选中"，挡不住**已经在飞**的那一次：
 * 自动选中的 `listMessages` 在用户点「新对话」之前就发出去了，响应晚几十毫秒才回来，
 * 回来时无条件写 `items`，于是刚清空的界面又被上一条会话的消息铺满——
 * 屏幕上写着"新对话"，看到的却是旧消息，用户接着问的问题还会被算成新会话（更莫名其妙）。
 *
 * 这条竞态是 Phase 5.7 用真机 E2E 复现的：`verify-agent-chat.mjs` 的"新对话必须是空白"
 * 那条断言在 Vite 本地跑时窗口太窄没撞上，经过 nginx + 容器里的 MySQL 每跳多几十毫秒后稳定命中。
 * 修法不是把 `pickedByUser` 判得更早（它已经在起点挡了），而是给每次加载编号：
 * 只有最后一次请求的响应允许写 `items`，过期响应直接丢弃；「新对话」让在飞的那次作废。
 */
let messageLoadSeq = 0
const requestId = ref('')
const abortController = ref<AbortController | null>(null)
const scroller = ref<HTMLElement | null>(null)
/** 用户往上翻看历史时不要把他拽回底部 */
const stickToBottom = ref(true)

const activeConversation = computed(() =>
  conversations.value.find((conversation) => conversation.id === activeId.value)
)

/** RAG 会话改绑/解绑知识库（新建时没绑定、或想换一批库再问）。可以选多个。 */
async function changeBoundKnowledgeBase(value: unknown) {
  const conversation = activeConversation.value
  if (!conversation) return
  // el-select 开了 multiple 之后给的是数组（清空是 []，不是 ''）
  const kbIds = Array.isArray(value) ? value.map((item) => String(item)) : []
  try {
    await bindKnowledgeBase(conversation.id, kbIds)
    await loadConversations()
    ElMessage.success(kbIds.length ? '已更新知识库绑定' : '已解绑知识库')
  } catch (error) {
    ElMessage.error('绑定失败：' + (error instanceof Error ? error.message : String(error)))
  }
}

/**
 * 命中片段出自哪个知识库（多库时才显示）。
 *
 * 单库下这句话是废话（"检索到 3 个片段，全来自你刚选的那个库"），只会占地方；
 * 绑了两个库之后它就是关键信息——用户要靠它判断"答案引的是产品手册还是内部 FAQ"。
 * 查不到名字时返回空串，界面按"没有这段"处理，不显示一个假的库名。
 */
function knowledgeBaseName(id: string | null) {
  if (!id) return ''
  return knowledgeBases.value.find((kb) => kb.id === id)?.name ?? ''
}

/**
 * 这组引用是不是来自**不止一个**知识库。
 *
 * 单库时出处是废话（整个会话就绑着这一个），只在多库时才标出来。
 * 判据是引用自身跨了几个库，而不是"会话绑了几个"：绑了三个库、
 * 这次只从其中一个检索到资料时，标出处反而会让用户以为另外两个库也贡献了。
 */
function citationsFromMultipleKnowledgeBases(citations: ChatCitation[]): boolean {
  return new Set(citations.map((citation) => citation.knowledgeBaseId).filter(Boolean)).size > 1
}

/**
 * 空结果那句话的措辞，取决于这次**有没有库根本没被搜成**（Phase 5.5.1）。
 *
 * "知识库里没有命中"是一句关于**内容**的断言：它告诉用户可以不用再追问了。
 * 而某个库这次超时/不可用时，这句话是假的——那一库里可能就有答案。
 * 服务端已经为此做了两件事（提示词里明令模型不许说"资料中没有"、空结果换成
 * "有知识库暂时无法检索"），界面若还一律显示"知识库里没有命中"，
 * 同一件事就有了三种口径，而用户看到的恰好是最笃定的那一种。
 */
function emptyLabel(retrieval: StreamRetrieval): string {
  const usable = retrieval.knowledgeBaseIds.length - retrieval.failedKnowledgeBases.length
  if (usable <= 0) return '知识库本次都没有检索成功'
  return usable < retrieval.knowledgeBaseIds.length ? '其余知识库没有命中' : '知识库里没有命中'
}

/**
 * `emptyLabel()` 三句话的机器可读版本。
 *
 * 这三句是**三种不同的事实**，不是三种说法：全失败 / 部分失败其余没命中 / 全都命中过但没结果。
 * 界面必须能区分，E2E 也必须能区分——原先脚本靠 `has-text('知识库里没有命中')` 匹配中文原文，
 * 改一个字断言就静默失效（这正是本次去中文文案定位要根治的一类脆弱性）。
 * 有了这个枚举，文案可以自由改，断言仍然钉在事实上。判定逻辑与 emptyLabel 同源，不会漂移。
 */
function emptyVariant(retrieval: StreamRetrieval): 'all-failed' | 'partial' | 'none' {
  const usable = retrieval.knowledgeBaseIds.length - retrieval.failedKnowledgeBases.length
  if (usable <= 0) return 'all-failed'
  return usable < retrieval.knowledgeBaseIds.length ? 'partial' : 'none'
}

/**
 * AGENT 会话换 Agent（或回到内置默认）。
 *
 * 换的是"执行者"：提示词、模型角色、能检索哪些库都跟着变，模式不变，
 * 所以同一段历史里的回答仍然可比。清空走独立字段 `resetAgent`
 * （`agentId: null` 在服务端表示"不改"，一个值不能同时是"不改"和"清空"）。
 */
async function changeBoundAgent(value: unknown) {
  const conversation = activeConversation.value
  if (!conversation) return
  const agentId = String(value ?? '')
  try {
    if (agentId) {
      await bindAgent(conversation.id, agentId)
    } else {
      await resetConversationAgent(conversation.id)
    }
    await loadConversations()
    ElMessage.success(agentId ? '已切换 Agent（下一轮生效）' : '已回到内置默认 Agent')
  } catch (error) {
    ElMessage.error('切换失败：' + (error instanceof Error ? error.message : String(error)))
  }
}

/** 当前会话用的 Agent 名称：给头部一个可读的标签，而不是只显示一个 id */
const activeAgentName = computed(() => {
  const agentId = activeConversation.value?.agentId
  if (!agentId) return '内置默认'
  return agents.value.find((agent) => agent.id === agentId)?.name ?? '已删除的 Agent'
})

/**
 * 把消息上的 `agentId` 翻成可读的来源标签（5.4）。返回 null = 不显示。
 *
 * 三种情况必须分开，因为它们的含义不同：
 * - **没有 id**：不显示。流式进行中的临时消息还没有 id（要等落库），5.4 之前的旧消息也没有。
 *   这里绝不能"取不到就当作内置默认"——那是在替服务端猜，猜错了比不显示更糟。
 * - **有 id 但列表里没有**：显示短 id 而不是"已删除的 Agent"。列表是分页拉的前 50 条，
 *   找不到也可能是没加载到；乱贴"已删除"这个结论就是在编。
 * - **正常**：名字。
 *
 * 注意它和 `activeAgentName` 的取值来源不同：那个读**会话**当前的绑定（会随换绑而变），
 * 这个读**消息**上固化的事实（换绑之后旧回答仍指回原来那个）。这正是 5.4 要回答的问题。
 */
function agentLabel(agentId: string | null | undefined): string | null {
  if (!agentId) return null
  const found = agents.value.find((agent) => agent.id === agentId)
  return found ? found.name : `Agent #${agentId.slice(-6)}`
}

const AGENT_STATE_TEXT: Record<AgentStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  DISABLED: '已停用'
}

/**
 * 头部选择器的候选项：可用的 Agent，外加**当前正绑着的那个**（即使它已不可用）。
 *
 * 为什么必须把"不可用但正绑着"的也列进来：会话绑定的 Agent 之后可能被停用或退回草稿。
 * 那时若选项里没有它，el-select 会把这串雪花 id 原样显示出来——用户看不懂那是什么，
 * 也找不到当前值。带上状态后缀，用户一眼能明白"下一轮会失败，原因是这个"，并顺手切走。
 */
const headerAgentOptions = computed(() => {
  const options = usableAgents.value.filter((agent) => !agent.builtin)
  const boundId = activeConversation.value?.agentId
  if (!boundId || options.some((agent) => agent.id === boundId)) return options
  const bound = agents.value.find((agent) => agent.id === boundId)
  return bound ? [...options, bound] : options
})

/** 头部下拉里的显示名：只有"正绑着且已不可用"的那一项需要标状态 */
function boundAgentLabel(agent: AgentResource): string {
  if (agent.id !== activeConversation.value?.agentId || agent.status === 'PUBLISHED') {
    return agent.name
  }
  return `${agent.name}（${AGENT_STATE_TEXT[agent.status]}）`
}

const router = useRouter()

/**
 * 欢迎区的示例问题。
 *
 * **踩过的坑（UA-003）**：这里曾经写死三个技术问题（"我的笔记里缓存击穿怎么解决？"等）。
 * 对一个刚注册、**一个知识库都没有**的用户来说，那三个问题等于在暗示系统里已经有别人的笔记——
 * 既不是他的资料，点下去也必然答不出来。那不是"示例"，是**伪造的已有内容**。
 *
 * 现在的规则：
 * - 一个知识库都没有 → 不显示任何示例，改为引导"导入资料"（见模板）；
 * - 有知识库 → 用**他自己的库名**生成示例，问下去确实能在他导入的资料里找到答案。
 */
const samples = computed<string[]>(() => {
  const first = knowledgeBases.value[0]
  if (!first) return []
  return [`${first.name} 里主要讲了什么？`]
})

/** 新用户空态里的引导入口：知识库列表页就是"创建 / 导入"的地方 */
function goCreateKnowledgeBase() {
  router.push('/')
}

/**
 * 会话侧栏是否收起（UA-007）。
 *
 * 用户收起它，是为了把横向空间让给回答——所以这里**只切一个 class**：
 * 不重新拉会话、不重置 items、不动输入框，SSE 进行中也照样能收（不影响发送与流）。
 * 另外必须**记住**选择：每次进页面都弹回来，等于没收。
 */
const railCollapsed = ref(localStorage.getItem('nexus.chatRailCollapsed') === '1')

function toggleRail() {
  railCollapsed.value = !railCollapsed.value
  localStorage.setItem('nexus.chatRailCollapsed', railCollapsed.value ? '1' : '0')
}

// ---------- 当日额度（5.6） ----------

/**
 * 配额在界面上只做一件事：让"还剩多少"成为用户能看到的数字。
 *
 * <p>在此之前它是个隐形旋钮——被 403 拦住（7002）之前完全不知道有这回事，
 * 被拦住之后也不知道自己用掉了多少、什么时候恢复。对一个会真实花钱的产品，
 * 那种体验就是"莫名其妙不能用了"。
 *
 * <p>拿不到（接口失败）时**整行不显示**，而不是显示 0/200：一个假的 0 会让用户
 * 以为额度刚被重置，比不显示更糟。
 */
const quota = ref<Quota | null>(null)

async function loadQuota() {
  try {
    quota.value = await getQuota()
  } catch {
    quota.value = null
  }
}

/**
 * 已用次数要收敛到上限内再显示。
 *
 * <p>被拒的请求也占了一次计数（否则"连点"就没有成本了），所以这个数字可能
 * 短暂超过上限。界面上出现 "205/200" 读起来像个 bug，用户会来问"是不是没拦住"——
 * 而它恰恰证明拦住了。这里用 min 收敛，真实数值以接口为准。
 */
const usedRequests = computed(() =>
  quota.value ? Math.min(quota.value.requestUsed, quota.value.requestLimit) : 0
)

const usedTokens = computed(() =>
  quota.value ? Math.min(quota.value.tokenUsed, quota.value.tokenLimit) : 0
)

/** 额度用尽：这一行变红，让"为什么发不出去了"在尝试之前就有答案 */
const quotaExhausted = computed(() =>
  quota.value
    ? quota.value.requestUsed >= quota.value.requestLimit || quota.value.tokenUsed >= quota.value.tokenLimit
    : false
)

/** 大数用 k 记（3.4k / 200k）：token 数动辄六位数，原样写出来没人读得出量级 */
function shortNumber(value: number): string {
  if (value < 1000) return String(value)
  const thousands = value / 1000
  // 200k 这种整数不带小数点，3.4k 这种保留一位
  return (Number.isInteger(thousands) ? String(thousands) : thousands.toFixed(1)) + 'k'
}

/** 重置时刻：额度按自然日算，说清"什么时候回来"比只说"用完了"有用 */
const quotaResetLabel = computed(() => {
  if (!quota.value) return ''
  const reset = new Date(quota.value.resetAt)
  return `${String(reset.getHours()).padStart(2, '0')}:${String(reset.getMinutes()).padStart(2, '0')} 重置`
})

onMounted(async () => {
  await loadConversations()
  await loadKnowledgeBases()
  await loadAgents()
  await loadQuota()
  // 进来就选中最近一次会话：比让用户看一个空面板更省一步。
  // 但两个 await 期间用户可能已经点过"新对话"——那就尊重他的选择，别再抢回来
  if (!pickedByUser.value && conversations.value.length) {
    await selectConversation(conversations.value[0].id)
  }
})

async function loadAgents() {
  try {
    agents.value = (await listAgents()).list
  } catch {
    // Agent 列表加载失败不影响对话：选择器为空 = 用内置默认 Agent
    agents.value = []
  }
}

async function loadKnowledgeBases() {
  try {
    knowledgeBases.value = (await listKnowledgeBases()).list
    // 默认选中第一个库：少一步点击，同时避免"选了 RAG 但没选库"的空状态。
    // 只选一个（不是全选）：多库是用户明确要做的事，替他全勾上只会让每次都慢一倍
    if (!draftKbIds.value.length && knowledgeBases.value.length) {
      draftKbIds.value = [knowledgeBases.value[0].id]
    }
  } catch {
    // 知识库列表加载失败不影响 Agent 模式；选到 RAG 时会提示
    knowledgeBases.value = []
  }
}

async function loadConversations() {
  loadingList.value = true
  try {
    conversations.value = (await listConversations()).list
  } finally {
    loadingList.value = false
  }
}

/**
 * 后端消息 → 界面消息。空内容的骨架消息（生成中崩溃留下的）不显示。
 *
 * 注意：这里不带 retrieval。RAG 的**检索事件**（含重排/截断/失败的库）只在本次流里可见，
 * 它没有随消息落库，因此刷新后不显示。但其中真正要紧的那半——**这一轮用了哪几段资料**——
 * 现在由 `citations` 回放（随消息落库，见 update.txt §五），所以"刷新后引用就没了"
 * 这件事不再成立：片段、出处、模型有没有真的引用，历史里都还在。
 * 本次会话内更不丢：见 send() 结尾按 assistantMessageId 把检索结果贴回来的那几行。
 */
function toItems(
  list: Array<{
    id: string
    role: string
    content: string | null
    agentId: string | null
    toolCalls: MessageToolCall[]
    citations: ChatCitation[]
  }>
) {
  return list
    .filter((message) => message.role === 'USER' || message.role === 'ASSISTANT')
    .filter((message) => (message.content ?? '').length > 0 || message.toolCalls.length > 0)
    .map((message): ChatItem => ({
      id: message.id,
      role: message.role as 'USER' | 'ASSISTANT',
      content: message.content ?? '',
      toolCalls: message.toolCalls ?? [],
      retrieval: null,
      citations: message.citations ?? [],
      // 服务端保证用户消息这里是 null（只有助手消息记产出来源，见 5.4 的 V17）
      agentId: message.agentId ?? null,
      streaming: false
    }))
}

/** 点会话列表：先记下"用户自己选的"，再走统一的选中逻辑。 */
async function pickConversation(id: string) {
  pickedByUser.value = true
  await selectConversation(id)
}

async function selectConversation(id: string) {
  if (streaming.value) {
    ElMessage.warning('请先停止当前生成')
    return
  }
  activeId.value = id
  loadingMessages.value = true
  stickToBottom.value = true
  const seq = ++messageLoadSeq
  try {
    const list = (await listMessages(id)).list
    // 过期响应既不写消息、也不关 loading：否则会把「新对话」清空的界面又填回旧消息，
    // 或者把后一次请求正在显示的加载态提前收掉
    if (seq !== messageLoadSeq) return
    items.value = toItems(list)
    await scrollToBottom(true)
  } finally {
    if (seq === messageLoadSeq) loadingMessages.value = false
  }
}

function newConversation() {
  if (streaming.value) {
    ElMessage.warning('请先停止当前生成')
    return
  }
  pickedByUser.value = true
  activeId.value = ''
  items.value = []
  input.value = ''
  // 让"已经在飞"的会话加载作废：它的响应若晚于这次点击回来，会把上面清空的 items 又填满
  // （见 messageLoadSeq）。那次响应已经不算数，所以 loading 也在这里收掉
  messageLoadSeq += 1
  loadingMessages.value = false
}

/**
 * 重命名会话（5.4 · P1-12）。
 *
 * 接口从 Phase 1 就在（`renameConversation` 一直没被调用过），但界面上没有入口，
 * 于是用户唯一的改名方式就是——再开一个会话。标题是自动用首条提问生成的，
 * 问了一句就把话题带偏是常事，改不了名等于这个自动标题变成了枷锁。
 *
 * 两点值得注意：
 * 1. 服务端会把 `title_auto` 置 0（见 ConversationServiceImpl.update），
 *    所以改过名之后的新提问不会再被自动标题覆盖——这正是"用户改的就该留住"；
 * 2. 改完**重新拉一次列表**而不是只改本地那一项：标题归一化（trim）发生在服务端，
 *    在这里照着猜一遍迟早会和它对不上（多一个空格、或将来加了长度截断）。
 */
async function renameConversationItem(conversation: Conversation) {
  if (streaming.value) {
    ElMessage.warning('请先停止当前生成')
    return
  }
  let value: string
  try {
    const result = await ElMessageBox.prompt('新的会话标题', '重命名会话', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      customClass: 'nexus-confirm-conv-rename',
      inputValue: conversation.title ?? '',
      // 空标题服务端会报 1001（标题不能为空字符串），这里先拦一道——
      // 让用户吃一条本可以避免的报错没有意义
      inputValidator: (text) => (text && text.trim() ? true : '标题不能为空'),
      inputErrorMessage: '标题不能为空'
    })
    value = result.value.trim()
  } catch {
    // 用户点了取消：ElMessageBox 以 reject 表示取消，不是错误
    return
  }
  await renameConversation(conversation.id, value)
  ElMessage.success('已重命名（之后的新提问不会再自动改标题）')
  await loadConversations()
}

async function removeConversation(conversation: Conversation) {
  const ok = await confirm({
    title: '确认删除会话？',
    description: `将删除会话「${conversation.title || '未命名'}」及其全部消息。`,
    confirmText: '删除',
    danger: true,
    customClass: 'nexus-confirm-conv-delete'
  })
  if (!ok) return
  try {
    await deleteConversation(conversation.id)
  } catch (e) {
    ElMessage.error('删除会话失败：' + (e instanceof Error ? e.message : String(e)))
    return
  }
  ElMessage.success('已删除')
  if (activeId.value === conversation.id) {
    activeId.value = ''
    items.value = []
  }
  await loadConversations()
}

/** 发送一轮：没有会话就先建一个（按新建时选的模式），再流式生成。 */
async function send(preset?: string) {
  const content = (preset ?? input.value).trim()
  if (!content || streaming.value) return

  let conversationId = activeId.value
  if (!conversationId) {
    // RAG 必须在建会话时就确定知识库：检索范围是会话属性，服务端也会拒绝没绑库的 RAG 提问。
    // 这里提前拦住，用户不必等一个来回才知道少选了东西。
    if (draftMode.value === 'RAG' && !draftKbIds.value.length) {
      ElMessage.warning(knowledgeBases.value.length
        ? '知识库问答需要先选择至少一个知识库'
        : '还没有知识库，先到"知识库"页导一份笔记')
      return
    }
    let created: Conversation
    try {
      created = await createConversation(draftMode.value, {
        kbIds: draftMode.value === 'RAG' ? draftKbIds.value : [],
        // Agent 只在 AGENT 模式下有意义；空串 = 不传 = 用内置默认 Agent
        agentId: draftMode.value === 'AGENT' && draftAgentId.value ? draftAgentId.value : null
      })
    } catch (e) {
      // 建会话失败就退回草稿态：否则界面停在"看起来在生成"的状态，
      // 而实际上什么都没开始
      ElMessage.error('新建会话失败：' + (e instanceof Error ? e.message : String(e)))
      return
    }
    conversationId = created.id
    activeId.value = created.id
    conversations.value = [created, ...conversations.value]
  }

  input.value = ''
  items.value.push({
    id: `local-user-${Date.now()}`,
    role: 'USER',
    content,
    toolCalls: [],
    citations: [],
    streaming: false
  })
  // 必须是 reactive 而不是普通对象字面量（UA-016，2026-10-03 实证）：
  // ref 数组的 push 只追踪"数组结构"的变化，推入的**原始对象**后续被原地改写
  // （answer.content += text）不会穿过 Proxy 的 set 陷阱，因而不触发重渲染——
  // 表现为"整段回答憋到结束才一次性出现"（流式假死）。用 reactive 包一层，
  // 之后所有 answer.* 的写入都会通知视图。RAG 页用 streamedText.value += text，
  // 天然走代理，所以没有这个问题。
  const answer = reactive<ChatItem>({
    id: `local-assistant-${Date.now()}`,
    role: 'ASSISTANT',
    content: '',
    toolCalls: [],
    retrieval: null,
    citations: [],
    streaming: true
  })
  items.value.push(answer)
  streaming.value = true
  stickToBottom.value = true
  await scrollToBottom(true)

  const controller = new AbortController()
  abortController.value = controller

  // 本轮的助手消息 id（meta 事件给）：流结束后要用它把检索卡片贴回"校正过"的那条消息
  let assistantMessageId = ''

  try {
    await streamChat(
      conversationId,
      content,
      {
        onMeta: (meta) => {
          requestId.value = meta.requestId
          assistantMessageId = meta.assistantMessageId
        },
        onRetrieval: (retrieval) => {
          // 检索事件早于第一个 delta：用户先看到"检索到 N 个片段"，再看回答逐字出现
          answer.retrieval = retrieval
          void scrollToBottom()
        },
        onToolCall: (call) => {
          // 工具"正在执行"：先占位再填结果，用户能看到过程而不是只有结果
          answer.toolCalls.push({ tool: call.tool, input: call.input, resultSummary: PENDING })
          void scrollToBottom()
        },
        onToolResult: (call) => {
          const pending = answer.toolCalls.findIndex(
            (item) => item.tool === call.tool && item.resultSummary === PENDING
          )
          if (pending >= 0) {
            answer.toolCalls[pending] = call
          } else {
            answer.toolCalls.push(call)
          }
          void scrollToBottom()
        },
        onDelta: (text) => {
          answer.content += text
          void scrollToBottom()
        },
        onDone: (done) => {
          // 引用随 done 一起到（`referenced` 要在完整正文里比对编号才算得出来）：
          // 先写进本地这条，紧接着的校正会用服务端那一份（同一个形状）覆盖它，
          // 因此实时与刷新后看到的是同一份数据
          answer.citations = done.citations ?? []
          answer.streaming = false
        }
      },
      controller.signal
    )
  } catch (error) {
    // 用户主动停止不是错误（AbortError），只有真正的失败才提示
    if (!(error instanceof DOMException && error.name === 'AbortError')) {
      answer.failed = true
      if (!answer.content) {
        answer.content = '生成失败：' + (error instanceof Error ? error.message : String(error))
      }
      ElMessage.error('生成失败：' + (error instanceof Error ? error.message : String(error)))
    }
  } finally {
    answer.streaming = false
    streaming.value = false
    requestId.value = ''
    abortController.value = null
    // 用服务端数据校正一次（真实 id、真实工具痕迹），同时刷新会话列表的顺序与标题
    await reloadActiveConversation()
    // 检索卡片必须活过这次校正：它承载的重排/截断/失败的库没有落库（见 toItems 的注释），
    // 校正会把本地这份冲掉。这一点是**真机跑出来的**（2026-09-30，见 decisions.md B.37）：
    // 本地服务+本地模型下整段回答一口气到达，卡片在 DOM 里只活了 41ms 就被冲掉——
    // 用户等于从没见过它；而在无 ES 的 standalone 里这条链路根本走不到，所以之前没暴露。
    // 按 assistantMessageId 贴回去，保证本次会话里回看得到。
    // （引用那一半没有这个问题：它已随消息落库，校正拿回来的就是服务端那一份。）
    if (answer.retrieval && assistantMessageId) {
      const reloaded = items.value.find((item) => item.id === assistantMessageId)
      if (reloaded) reloaded.retrieval = answer.retrieval
    }
    // 每轮结束刷新额度：这一轮一定花掉了次数（和 token），不刷新的话那一行会停在旧数字上，
    // 而它恰恰是用户用来判断"还能问几次"的依据。失败时 loadQuota 自己会把它清成 null。
    await loadQuota()
  }
}

/** 停止生成：先告诉后端停（真的断上游），再中断本地读取。 */
async function stop() {
  const id = requestId.value
  if (id) {
    try {
      await stopStream(id)
    } catch {
      // 停止失败不阻塞用户：本地照样中断，避免界面卡在"生成中"
    }
  }
  abortController.value?.abort()
}

async function reloadActiveConversation() {
  const conversationId = activeId.value
  if (!conversationId) return
  await loadConversations()
  if (!conversations.value.some((conversation) => conversation.id === conversationId)) return
  loadingMessages.value = true
  try {
    items.value = toItems((await listMessages(conversationId)).list)
    await scrollToBottom()
  } finally {
    loadingMessages.value = false
  }
}

function onScroll() {
  const element = scroller.value
  if (!element) return
  stickToBottom.value = element.scrollHeight - element.scrollTop - element.clientHeight < 80
}

async function scrollToBottom(force = false) {
  if (!force && !stickToBottom.value) return
  await nextTick()
  const element = scroller.value
  if (element) {
    element.scrollTop = element.scrollHeight
  }
}

function onEnter(event: KeyboardEvent) {
  // Enter 发送、Shift+Enter 换行（输入多行提示词的常规约定）
  if (!event.shiftKey) {
    event.preventDefault()
    void send()
  }
}

function html(message: ChatItem): string {
  return renderMarkdown(message.content)
}

function isPending(call: MessageToolCall): boolean {
  return call.resultSummary === PENDING
}

function timeLabel(value: string | null): string {
  if (!value) return ''
  const date = new Date(value)
  const today = new Date()
  const time = `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
  return date.toDateString() === today.toDateString()
    ? time
    : `${date.getMonth() + 1}/${date.getDate()} ${time}`
}

/** 工具名 → 人话（界面上不该出现 knowledge_search 这种内部标识） */
function toolLabel(tool: string): string {
  return tool === 'knowledge_search' ? '检索知识库' : tool
}

// 评测集入口不在这里：它是对 Agent 配置效果的回归手段，不属于普通用户聊天流程的一部分，
// 已移到「Agent 管理」页（见 AgentsView）。后端 /eval/kb/{kbId} 与 api/agent.ts 原样保留。

watch(activeId, () => {
  stickToBottom.value = true
})
</script>

<template>
  <div class="chat" :class="{ collapsed: railCollapsed }">
    <!-- 左：会话列表（可收起，见 toggleRail） -->
    <aside v-show="!railCollapsed" class="rail" data-testid="chat-rail">
      <el-button type="primary" class="new-chat" data-testid="chat-new" :icon="Plus" @click="newConversation">
        新对话
      </el-button>

      <div v-loading="loadingList" class="conversations">
        <p v-if="!conversations.length && !loadingList" class="rail-empty">
          还没有对话。问一个涉及你笔记的问题，Agent 会先检索再回答。
        </p>
        <button
          v-for="conversation in conversations"
          :key="conversation.id"
          class="conversation"
          :class="{ active: conversation.id === activeId }"
          data-testid="chat-conversation"
          :data-conv-id="conversation.id"
          @click="pickConversation(conversation.id)"
        >
          <span class="conversation-title">{{ conversation.title || '新对话' }}</span>
          <span class="conversation-meta num">
            {{ conversation.messageCount }} 条 ·
            {{ timeLabel(conversation.lastMessageAt || conversation.createdAt) }}
          </span>
          <span
            class="conversation-rename"
            role="button"
            aria-label="重命名会话"
            title="重命名"
            data-testid="chat-conversation-rename"
            @click.stop="renameConversationItem(conversation)"
          >
            <el-icon><EditPen /></el-icon>
          </span>
          <span
            class="conversation-delete"
            role="button"
            aria-label="删除会话"
            data-testid="chat-conversation-delete"
            @click.stop="removeConversation(conversation)"
          >
            <el-icon><Delete /></el-icon>
          </span>
        </button>
      </div>

      <div class="rail-foot">
        <!-- 当日额度（5.6）。放在左栏底部而不是对话头部：它是**账号级**的数，
             与当前选中哪个会话无关，挂在会话标题旁边会被读成"这个会话用掉的"。
             拿不到数据时整行不渲染（v-if），而不是显示 0/200。 -->
        <p
          v-if="quota"
          class="quota num"
          :class="{ exhausted: quotaExhausted }"
          :title="`每日额度按自然日计算，${quotaResetLabel}`"
        >
          今日 {{ usedRequests }}/{{ quota.requestLimit }} 次 ·
          {{ shortNumber(usedTokens) }}/{{ shortNumber(quota.tokenLimit) }} tokens
        </p>
      </div>
    </aside>

    <!-- 右：对话区 -->
    <section class="thread">
      <header class="thread-head">
        <!-- ☰ 收起/展开会话列表：只切一个 class，不重新拉数据、不打断流式生成 -->
        <el-button
          class="rail-toggle"
          data-testid="chat-rail-toggle"
          :icon="Menu"
          text
          :title="railCollapsed ? '展开会话列表' : '收起会话列表'"
          :aria-label="railCollapsed ? '展开会话列表' : '收起会话列表'"
          @click="toggleRail"
        />
        <div class="thread-title">
          <h1>{{ activeConversation?.title || '对话' }}</h1>
          <p class="sub">
            {{
              activeConversation?.mode === 'RAG'
                ? '先在你的知识库里检索，再基于检索到的片段回答；检索到几个片段会实时显示'
                : '模型先决定是否检索你的知识库（工具调用），再基于检索结果回答；每次检索都留痕'
            }}
          </p>
        </div>
        <div class="thread-meta">
          <el-tag v-if="activeConversation" size="small" effect="plain">
            {{ activeConversation.mode }}
          </el-tag>
          <!-- RAG 会话的知识库可以改绑（可多选）：换了之后，后续轮次检索的就是这一批库 -->
          <el-select
            v-if="activeConversation?.mode === 'RAG'"
            :model-value="activeConversation.kbIds ?? []"
            multiple
            collapse-tags
            :multiple-limit="10"
            size="small"
            placeholder="未绑定知识库"
            class="kb-select"
            data-testid="chat-kb-select"
            popper-class="chat-kb-select-popper"
            @change="changeBoundKnowledgeBase"
          >
            <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
          </el-select>
          <!-- AGENT 会话换 Agent：提示词/模型/检索范围都跟着换，模式不变。
               空值 = 内置默认，走 resetAgent 清空而不是传 null（null 在服务端表示"不改"） -->
          <el-select
            v-if="activeConversation?.mode === 'AGENT'"
            class="kb-select"
            size="small"
            :model-value="activeConversation.agentId ?? ''"
            placeholder="内置默认 Agent"
            data-testid="chat-agent-select"
            popper-class="chat-agent-select-popper"
            @change="changeBoundAgent"
          >
            <el-option label="内置默认 Agent" value="" />
            <el-option
              v-for="agent in headerAgentOptions"
              :key="agent.id"
              :label="boundAgentLabel(agent)"
              :value="agent.id"
            />
          </el-select>
          <span v-if="activeConversation?.mode === 'AGENT'" class="agent-hint" data-testid="chat-agent-hint">
            {{ activeAgentName }}
          </span>
        </div>
      </header>

      <div ref="scroller" v-loading="loadingMessages" class="messages" data-testid="chat-messages" @scroll="onScroll">
        <div v-if="!items.length" class="welcome">
          <div class="welcome-mark">
            <el-icon :size="26"><ChatDotRound /></el-icon>
          </div>

          <!-- 新用户（0 知识库）：不显示任何示例问题，直接说清下一步做什么（UA-003）。
               写死几个技术问题只会让他以为系统里已经有别人的笔记。 -->
          <template v-if="!knowledgeBases.length">
            <h2>还没有知识库</h2>
            <p>先导入你的资料，系统会基于你的资料回答问题。</p>
            <div class="samples">
              <el-button
                type="primary"
                :icon="Plus"
                data-testid="chat-welcome-import"
                @click="goCreateKnowledgeBase"
              >
                导入资料
              </el-button>
            </div>
          </template>

          <!-- 已经有资料：示例来自他自己的知识库，而不是写死的技术问题 -->
          <template v-else>
            <h2>问点什么？</h2>
            <p>涉及你笔记的问题，模型会先检索知识库再回答，并把检索过程显示在这里。</p>
            <div class="samples">
              <button
                v-for="sample in samples"
                :key="sample"
                class="sample"
                data-testid="chat-sample"
                @click="send(sample)"
              >
                {{ sample }}
              </button>
            </div>
          </template>
        </div>

        <article
          v-for="message in items"
          :key="message.id"
          class="message"
          :class="message.role === 'USER' ? 'from-user' : 'from-agent'"
          data-testid="chat-message"
          :data-role="message.role"
        >
          <div class="bubble">
            <!-- RAG 的检索结果：先于回答出现，用户不必对着空白等生成 -->
            <div v-if="message.retrieval" class="retrieval" data-testid="chat-retrieval">
              <span class="retrieval-count">检索到 {{ message.retrieval.count }} 个片段</span>
              <!-- 多库时才说"来自 N 个知识库"：单库下这是废话，只是占地方 -->
              <el-tag
                v-if="message.retrieval.knowledgeBaseIds.length > 1"
                size="small"
                effect="plain"
                type="info"
                data-testid="chat-retrieval-multi-kb"
              >
                {{ message.retrieval.knowledgeBaseIds.length }} 个知识库
              </el-tag>
              <!-- 检索失败的知识库（Phase 5.5.1）。多库并行之后，单个库失败是被**隔离**掉的：
                   整次检索照常成功、卡片照常显示"检索到 5 个片段"，而那个库压根没被搜过。
                   如果答案恰恰在它里面，用户看到的会是一个漏了一整个库的否定结论——
                   所以他必须能看见"这次少搜了谁"。原因放进悬浮提示：只说"1 个库失败"，
                   用户无从判断"等会儿重试"还是"去找管理员"。 -->
              <el-tooltip
                v-if="message.retrieval.failedKnowledgeBases.length"
                effect="dark"
                placement="top"
              >
                <template #content>
                  <div
                    v-for="failure in message.retrieval.failedKnowledgeBases"
                    :key="failure.knowledgeBaseId"
                  >
                    {{ failure.knowledgeBaseName || `知识库 ${failure.knowledgeBaseId}` }}：{{
                      failure.reason
                    }}
                  </div>
                </template>
                <el-tag size="small" effect="plain" type="danger" data-testid="chat-retrieval-failed">
                  其中 {{ message.retrieval.failedKnowledgeBases.length }} 个知识库检索失败
                </el-tag>
              </el-tooltip>
              <el-tag
                v-if="message.retrieval.empty"
                size="small"
                effect="plain"
                type="warning"
                data-testid="chat-retrieval-empty"
                :data-empty-variant="emptyVariant(message.retrieval)"
              >
                {{ emptyLabel(message.retrieval) }}
              </el-tag>
              <el-tag
                v-if="message.retrieval.reranked"
                size="small"
                effect="plain"
                type="success"
                data-testid="chat-retrieval-reranked"
              >
                已重排
              </el-tag>
              <el-tag
                v-if="message.retrieval.truncated"
                size="small"
                effect="plain"
                type="info"
                data-testid="chat-retrieval-truncated"
              >
                预算截断 {{ message.retrieval.truncated }} 段
              </el-tag>
              <ul v-if="message.retrieval.hits.length" class="retrieval-hits" data-testid="chat-retrieval-hits">
                <li v-for="hit in message.retrieval.hits.slice(0, 5)" :key="hit.chunkId">
                  <!-- 每段的出处（多库时才有意义）：没有它，用户无法判断答案是哪个库给的 -->
                  <span
                    v-if="message.retrieval.knowledgeBaseIds.length > 1
                      && knowledgeBaseName(hit.knowledgeBaseId)"
                    class="hit-kb"
                  >{{ knowledgeBaseName(hit.knowledgeBaseId) }}</span>
                  {{ hit.documentTitle || '未命名文档' }}
                  <template v-if="hit.headingPath"> › {{ hit.headingPath }}</template>
                </li>
              </ul>
            </div>

            <!-- 工具痕迹：过程可见。检索中与已完成用两种状态区分 -->
            <ol v-if="message.toolCalls.length" class="trace" data-testid="chat-trace">
              <li v-for="(call, index) in message.toolCalls" :key="index">
                <!-- 标签只说"这一步做完了"，不说"找到了"：未命中时摘要写的是
                     「0 条结果（无匹配）」，绿色对勾式的"已检索"会被读成"检索到了" -->
                <el-tag size="small" effect="plain" :type="isPending(call) ? 'warning' : 'info'">
                  {{ isPending(call) ? '检索中' : '检索完成' }}
                </el-tag>
                <span class="trace-tool">{{ toolLabel(call.tool) }}</span>
                <code class="trace-input">{{ call.input }}</code>
                <span v-if="!isPending(call)" class="trace-result">{{ call.resultSummary }}</span>
              </li>
            </ol>

            <div v-if="message.role === 'USER'" class="plain">{{ message.content }}</div>
            <!-- 流式中先显示纯文本（每个字都重渲染 Markdown 太贵），结束后再渲染 -->
            <div v-else-if="message.streaming" class="markdown stream-text">
              {{ message.content }}<span class="caret" />
            </div>
            <!-- eslint-disable-next-line vue/no-v-html -- 内容经 marked + DOMPurify 处理 -->
            <div v-else class="markdown" v-html="html(message)" />

            <!-- 引用（update.txt §五）：这条回答照着哪几段资料说的。**随消息落库**，
                 因此刷新页面、切换会话、过几天再回来都还在——"出处"必须活得过刷新。
                 RAG 的流内视图里上面那张检索卡片已经把同样的片段列出来了，
                 这里就不再重复一遍（避免同一份资料在一屏里出现两次）。 -->
            <div
              v-if="message.role === 'ASSISTANT' && message.citations?.length && !message.retrieval"
              class="citations"
              data-testid="chat-citations"
            >
              <div class="citations-title">参考 {{ message.citations.length }} 段资料</div>
              <ul class="citation-list">
                <li v-for="citation in message.citations" :key="citation.index">
                  <span class="citation-index">[{{ citation.index }}]</span>
                  <!-- 出处（多库时才有意义）：会话绑了多个库时，用户要靠它判断答案来自哪个库 -->
                  <span
                    v-if="citationsFromMultipleKnowledgeBases(message.citations)
                      && knowledgeBaseName(citation.knowledgeBaseId)"
                    class="hit-kb"
                  >{{ knowledgeBaseName(citation.knowledgeBaseId) }}</span>
                  {{ citation.documentTitle || '未命名文档' }}
                  <template v-if="citation.headingPath"> › {{ citation.headingPath }}</template>
                  <!-- 模型漏引：这段资料摆在了它面前，正文里却没有对应的 [n]。
                       如实标出来，而不是替它补一个编号——那会让人以为模型真的引用了。 -->
                  <span v-if="!citation.referenced" class="citation-unused">未引用</span>
                </li>
              </ul>
            </div>

            <!-- 来源（5.4）：这条回答是哪个 Agent 产出的。存的是**当时**那一行，
                 所以会话后来换了 Agent，这里也仍然是答它的那个（见 agentLabel 的说明）。 -->
            <div v-if="message.role === 'ASSISTANT' && agentLabel(message.agentId)" class="provenance">
              由「{{ agentLabel(message.agentId) }}」回答
            </div>
          </div>
        </article>
      </div>

      <!-- 新建会话时的三个选择：模式决定走哪条链路，知识库决定 RAG 检索哪里，
           Agent 决定 AGENT 模式用哪套提示词/模型/检索范围。
           已有会话时不再显示——模式是会话属性，中途改等于换了一种问答（服务端也拒绝）。 -->
      <div v-if="!activeId" class="composer-options" data-testid="chat-composer-options">
        <el-radio-group v-model="draftMode" size="small" data-testid="chat-draft-mode">
          <el-radio-button value="AGENT" data-testid="chat-draft-mode-agent">Agent（自己决定检索）</el-radio-button>
          <el-radio-button value="RAG" data-testid="chat-draft-mode-rag">知识库问答（先检索再答）</el-radio-button>
        </el-radio-group>
        <!-- 可多选：一次提问同时检索这几个库，回答里每一段引用都会标明出自哪个库 -->
        <el-select
          v-if="draftMode === 'RAG'"
          v-model="draftKbIds"
          multiple
          collapse-tags
          clearable
          :multiple-limit="10"
          size="small"
          placeholder="选择知识库（可多选）"
          class="kb-select"
          data-testid="chat-draft-kb-select"
          popper-class="chat-draft-kb-select-popper"
        >
          <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
        </el-select>
        <el-select
          v-else
          v-model="draftAgentId"
          size="small"
          placeholder="选择 Agent"
          class="kb-select"
          data-testid="chat-draft-agent-select"
          popper-class="chat-draft-agent-select-popper"
        >
          <el-option label="内置默认 Agent" value="" />
          <el-option
            v-for="agent in usableAgents.filter((item) => !item.builtin)"
            :key="agent.id"
            :label="agent.name"
            :value="agent.id"
          />
        </el-select>
      </div>
      <!-- 没有可选 Agent 时要说清楚为什么（草稿要先发布），而不是给一个空下拉 -->
      <p v-if="!activeId && draftMode === 'AGENT' && blockedAgents.length" class="composer-hint">
        还有 {{ blockedAgents.length }} 个 Agent 不能选用（草稿需先发布、已停用需先启用）：
        {{ blockedAgents.map((agent) => agent.name).join('、') }} —— 到「Agent 管理」里试跑后发布
      </p>
      <!-- 一个知识库都没有时提前讲清楚会得到什么：说清"会不会答"取决所选 Agent 的回答策略 -->
      <p v-if="!activeId && draftMode === 'AGENT' && !knowledgeBases.length" class="composer-hint">
        <template v-if="draftAnswerPolicy === 'KB_ONLY'">
          还没有知识库，而当前选用的 Agent 是「只依据笔记」：提问会如实回答"没有在笔记中找到可引用的资料"。
          到「知识库」页导入笔记后就能检索到内容。
        </template>
        <template v-else>
          还没有知识库：现在提问，Agent 会用自己的知识回答，并注明内容不是来自你的笔记。
          到「知识库」页导入笔记后，回答就能引用你的资料。
        </template>
      </p>

      <footer class="composer" data-testid="chat-composer">
        <el-input
          v-model="input"
          type="textarea"
          data-testid="chat-input"
          :rows="1"
          :autosize="{ minRows: 1, maxRows: 6 }"
          resize="none"
          placeholder="问一个涉及你笔记的问题，Enter 发送、Shift+Enter 换行"
          :disabled="streaming"
          @keydown.enter="onEnter"
        />
        <el-button v-if="streaming" class="send" data-testid="chat-stop" :icon="VideoPause" @click="stop">停止</el-button>
        <el-button
          v-else
          type="primary"
          class="send"
          data-testid="chat-send"
          :icon="Promotion"
          :disabled="!input.trim()"
          @click="send()"
        >
          发送
        </el-button>
      </footer>
    </section>
  </div>
</template>

<style scoped>
/* 对话页占满外壳高度：输入框固定、消息区独立滚动 */
.chat {
  display: grid;
  grid-template-columns: 264px minmax(0, 1fr);
  gap: var(--space-4);
  height: 100%;
  padding: var(--space-2) 0 var(--space-4);
}

/* 会话列表收起后，轨道宽度归零，对话区自然铺满（UA-007） */
.chat.collapsed {
  grid-template-columns: minmax(0, 1fr);
}

.rail-toggle {
  flex: none;
  margin-right: calc(-1 * var(--space-2));
  color: var(--text-muted);
}

@media (max-width: 900px) {
  .chat {
    grid-template-columns: 1fr;
  }

  .rail {
    display: none;
  }
}

/* ---------- 左栏 ---------- */
.rail {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  min-height: 0;
  padding: var(--space-4);
  background: var(--surface-raised);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
}

.new-chat {
  width: 100%;
  border-radius: var(--radius-chip);
}

.conversations {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.conversation {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: none;
  background: transparent;
  border-radius: var(--radius-input);
  text-align: left;
  cursor: pointer;
  font-family: inherit;
  transition: background-color var(--dur-fast) var(--ease-soft);
}

.conversation:hover {
  background: var(--surface-sunken);
}

.conversation.active {
  background: var(--accent-50);
}

.conversation-title {
  font-size: 13.5px;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  /* 给删除按钮留位，避免长标题压到图标上 */
  padding-right: 22px;
}

.conversation.active .conversation-title {
  color: var(--accent-600);
  font-weight: 600;
}

.conversation-meta {
  font-size: 11px;
  color: var(--text-faint);
}

/* 行尾的两个操作按钮：默认隐形，悬停整行时才出现（平时不该抢标题的注意力）。
   两条规则共用一份定位，只有 right 与 hover 配色不同——分开写会漂移。 */
.conversation-rename,
.conversation-delete {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: var(--radius-chip);
  color: var(--text-faint);
  opacity: 0;
  transition: opacity var(--dur-fast) var(--ease-soft), color var(--dur-fast) var(--ease-soft);
}

.conversation-delete {
  right: var(--space-2);
}

/* 重命名在删除的左边：两个都靠右堆叠，顺序是"轻的在前、危险的在后" */
.conversation-rename {
  right: calc(var(--space-2) + 26px);
}

.conversation:hover .conversation-rename,
.conversation:hover .conversation-delete {
  opacity: 1;
}

.conversation-rename:hover {
  color: var(--accent-600);
  background: var(--surface-sunken);
}

.conversation-delete:hover {
  color: var(--danger);
  background: var(--danger-soft);
}

.rail-empty {
  margin: 0;
  padding: var(--space-2) var(--space-3);
  color: var(--text-muted);
  font-size: 12.5px;
  line-height: 1.7;
}

.rail-foot {
  padding-top: var(--space-3);
  border-top: 1px solid var(--border-soft);
}

/* 额度那一行：它是**账号级**的数，与当前选中哪个会话无关，
   所以挂在会话列表底部而不是对话标题旁边（那样会被读成"这个会话用掉的"） */
.quota {
  margin: 0;
  padding: 0 var(--space-1);
  color: var(--text-faint);
  font-size: 11.5px;
  line-height: 1.5;
  /* 长数字不该把左栏撑开 */
  overflow-wrap: anywhere;
}

/* 用尽：换成危险色。用户"发不出去"的原因在这一行就能看到，不必等一条 403 */
.quota.exhausted {
  color: var(--danger);
}

/* ---------- 右栏 ---------- */
.thread {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--surface-raised);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
}

.thread-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-5) var(--space-5) var(--space-4);
  border-bottom: 1px solid var(--border-soft);
}

.thread-title h1 {
  font-size: 17px;
}

.sub {
  margin: var(--space-1) 0 0;
  color: var(--text-muted);
  font-size: 12.5px;
}

.messages {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

/* ---------- 空状态 ---------- */
.welcome {
  margin: auto;
  max-width: 460px;
  text-align: center;
}

.welcome-mark {
  display: grid;
  place-items: center;
  width: 52px;
  height: 52px;
  margin: 0 auto var(--space-4);
  border-radius: var(--radius-card);
  background: var(--accent-50);
  color: var(--accent-500);
}

.welcome h2 {
  font-size: 17px;
}

.welcome p {
  margin: var(--space-2) 0 var(--space-5);
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.7;
}

.samples {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.sample {
  padding: var(--space-3) var(--space-4);
  border: none;
  border-radius: var(--radius-chip);
  background: var(--surface-sunken);
  color: var(--text);
  font-size: 13px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition: background-color var(--dur-fast) var(--ease-soft), color var(--dur-fast) var(--ease-soft);
}

.sample:hover {
  background: var(--accent-50);
  color: var(--accent-600);
}

/* ---------- 消息 ---------- */
.message {
  display: flex;
}

.from-user {
  justify-content: flex-end;
}

/* 用户消息：右侧浅色气泡（一眼分辨"我说的"和"它说的"） */
.from-user .bubble {
  max-width: 78%;
  padding: var(--space-3) var(--space-4);
  background: var(--accent-50);
  border-radius: var(--radius-card) var(--radius-card) var(--radius-sm) var(--radius-card);
}

.from-agent .bubble {
  width: 100%;
}

.from-user .plain {
  white-space: pre-wrap;
  word-break: break-word;
}

/* 工具痕迹：细窄的步骤条，强调"过程"而不是抢回答的注意力 */
.trace {
  list-style: none;
  margin: 0 0 var(--space-3);
  padding: var(--space-2) var(--space-3);
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.trace li {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--text-muted);
}

.trace-tool {
  color: var(--text);
}

.trace-input {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--accent-600);
  background: var(--surface-raised);
  border-radius: 6px;
  padding: 1px 6px;
}

.trace-result {
  color: var(--text-muted);
}

/* 来源标注：压在回答末尾的一行小字。刻意做得很轻——它是"可追溯"的证据，
   不是用户每轮都要读的内容，抢了正文的注意力就过了。 */
.provenance {
  margin-top: var(--space-2);
  font-size: 11.5px;
  /* 用 muted 而不是 faint：faint 按约定只给占位/禁用这类装饰，
     而"这条是谁答的"是真实信息，得满足对比度 */
  color: var(--text-muted);
}

.markdown {
  font-size: 14px;
  line-height: 1.75;
  color: var(--text);
  max-width: 76ch;
}

.stream-text {
  white-space: pre-wrap;
}

.markdown :deep(h1),
.markdown :deep(h2),
.markdown :deep(h3) {
  margin: 1.4em 0 0.5em;
}

.markdown :deep(h1) {
  font-size: 19px;
}

.markdown :deep(h2) {
  font-size: 16px;
}

.markdown :deep(h3) {
  font-size: 14.5px;
}











.markdown :deep(blockquote) {
  margin: 0.8em 0;
  padding: 2px 0 2px var(--space-4);
  border-left: 2px solid var(--accent-300);
  color: var(--text-muted);
}



/* ---------- 新建会话的模式/知识库选择 ---------- */
.composer-options {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-5) 0;
  flex-wrap: wrap;
}

.kb-select {
  width: 200px;
}

.agent-hint {
  color: var(--text-faint);
  font-size: 12px;
}

.composer-hint {
  margin: 0;
  padding: var(--space-1) var(--space-5) 0;
  color: var(--text-faint);
  font-size: 11.5px;
  line-height: 1.6;
}

/* ---------- 检索卡片（RAG 模式：先于回答出现） ---------- */
.retrieval {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  margin-bottom: var(--space-2);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-chip);
  background: var(--surface-2, transparent);
  font-size: 12.5px;
}

.retrieval-count {
  color: var(--accent-600);
  font-weight: 600;
}

.retrieval-hits {
  flex-basis: 100%;
  margin: 0;
  padding-left: 1.1em;
  color: var(--text-muted);
  line-height: 1.7;
}

/* 命中片段的来源库（只在绑了多个库时出现） */
.hit-kb {
  margin-right: 0.4em;
  padding: 0 0.4em;
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-chip);
  color: var(--accent-600);
  font-size: 11px;
}

/* ---------- 引用（随消息落库：刷新后仍在） ---------- */
.citations {
  margin-top: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-chip);
  background: var(--surface-2, transparent);
  font-size: 12.5px;
}

.citations-title {
  color: var(--text-muted);
  font-weight: 600;
}

.citation-list {
  margin: var(--space-1) 0 0;
  padding-left: 1.1em;
  color: var(--text-muted);
  line-height: 1.7;
}

.citation-index {
  margin-right: 0.3em;
  color: var(--accent-600);
  font-variant-numeric: tabular-nums;
}

/* 模型漏引：摆在了它面前却没用上。不是错误，但用户有权知道 */
.citation-unused {
  margin-left: 0.4em;
  padding: 0 0.4em;
  border: 1px dashed var(--border-soft);
  border-radius: var(--radius-chip);
  color: var(--text-faint);
  font-size: 11px;
}

/* ---------- 输入区 ---------- */
.composer {
  display: flex;
  align-items: flex-end;
  gap: var(--space-2);
  padding: var(--space-4) var(--space-5);
  border-top: 1px solid var(--border-soft);
}

.composer :deep(.el-textarea__inner) {
  border-radius: var(--radius-card);
  padding: var(--space-3) var(--space-4);
}

.send {
  flex: none;
  height: 38px;
  border-radius: var(--radius-chip);
}

/* ---------- 评测弹窗 ---------- */
.hint {
  margin: 0;
  color: var(--text-muted);
  font-size: 12.5px;
  line-height: 1.7;
}

</style>
