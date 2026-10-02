<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound,
  DataAnalysis,
  Delete,
  EditPen,
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
  type Conversation,
  type MessageToolCall,
  type StreamRetrieval
} from '@/api/chat'
import { DEFAULT_EVAL_CASES, runEval, type EvalReport } from '@/api/agent'
import {
  listAgents,
  type AgentResource,
  type AgentStatus,
  type AnswerPolicy
} from '@/api/agents'
import { listKnowledgeBases, type KnowledgeBase } from '@/api/knowledge'
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

/** 示例问题：直接点就能跑，省去"第一句该问什么"的犹豫 */
const samples = ['我的笔记里缓存击穿怎么解决？', '线程池的核心参数有哪些坑？', 'G1 和 ZGC 该怎么选？']

onMounted(async () => {
  await loadConversations()
  await loadKnowledgeBases()
  await loadAgents()
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
 * 注意：这里不带 retrieval。RAG 的命中片段目前<b>只在本次流里可见</b>——它没有随消息落库
 * （chat_message 只存正文与工具痕迹），因此刷新后不显示。这是已知缺口，不是渲染 bug：
 * 若要回放，需要把命中片段写进消息 metadata（与 Agent 的工具痕迹同一处）。
 * 本次会话内不丢：见 send() 结尾按 assistantMessageId 把命中片段贴回来的那几行。
 */
function toItems(
  list: Array<{
    id: string
    role: string
    content: string | null
    agentId: string | null
    toolCalls: MessageToolCall[]
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
  try {
    items.value = toItems((await listMessages(id)).list)
    await scrollToBottom(true)
  } finally {
    loadingMessages.value = false
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
  await ElMessageBox.confirm(
    `将删除会话「${conversation.title || '未命名'}」及其全部消息。`,
    '确认删除会话？',
    { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
  )
  await deleteConversation(conversation.id)
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
    const created = await createConversation(draftMode.value, {
      kbIds: draftMode.value === 'RAG' ? draftKbIds.value : [],
      // Agent 只在 AGENT 模式下有意义；空串 = 不传 = 用内置默认 Agent
      agentId: draftMode.value === 'AGENT' && draftAgentId.value ? draftAgentId.value : null
    })
    conversationId = created.id
    activeId.value = created.id
    conversations.value = [created, ...conversations.value]
  }

  input.value = ''
  items.value.push({ id: `local-user-${Date.now()}`, role: 'USER', content, toolCalls: [], streaming: false })
  const answer: ChatItem = {
    id: `local-assistant-${Date.now()}`,
    role: 'ASSISTANT',
    content: '',
    toolCalls: [],
    retrieval: null,
    streaming: true
  }
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
        onDone: () => {
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
    // 检索卡片必须活过这次校正：服务端不存命中片段（见 toItems 的注释），校正会把本地这份冲掉。
    // 这一点是**真机跑出来的**（2026-09-30，见 decisions.md B.37）：本地服务+本地模型下
    // 整段回答一口气到达，卡片在 DOM 里只活了 41ms 就被冲掉——用户等于从没见过它；
    // 而在无 ES 的 standalone 里这条链路根本走不到，所以之前没暴露。
    // 按 assistantMessageId 贴回去，至少保证"本次会话里回看得到"；刷新页面仍会丢，
    // 那是同一个已知缺口（要彻底解决得把命中片段落库，与工具痕迹同处）。
    if (answer.retrieval && assistantMessageId) {
      const reloaded = items.value.find((item) => item.id === assistantMessageId)
      if (reloaded) reloaded.retrieval = answer.retrieval
    }
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

// ---------- 评测（保留原有能力，收进弹窗，不占用对话区域） ----------
const evalVisible = ref(false)
const evalRunning = ref(false)
const evalReport = ref<EvalReport | null>(null)
const evalKbName = ref('')

async function runEvaluation() {
  evalRunning.value = true
  try {
    const kbs = (await listKnowledgeBases()).list
    if (!kbs.length) {
      ElMessage.warning('还没有知识库，先导一份笔记再评测')
      return
    }
    evalKbName.value = kbs[0].name
    evalReport.value = await runEval(kbs[0].id, DEFAULT_EVAL_CASES)
  } catch (error) {
    ElMessage.error('评测失败：' + (error instanceof Error ? error.message : String(error)))
  } finally {
    evalRunning.value = false
  }
}

watch(activeId, () => {
  stickToBottom.value = true
})
</script>

<template>
  <div class="chat">
    <!-- 左：会话列表 -->
    <aside class="rail">
      <el-button type="primary" class="new-chat" :icon="Plus" @click="newConversation">
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
            @click.stop="renameConversationItem(conversation)"
          >
            <el-icon><EditPen /></el-icon>
          </span>
          <span
            class="conversation-delete"
            role="button"
            aria-label="删除会话"
            @click.stop="removeConversation(conversation)"
          >
            <el-icon><Delete /></el-icon>
          </span>
        </button>
      </div>

      <div class="rail-foot">
        <el-button link size="small" :icon="DataAnalysis" @click="evalVisible = true">跑评测集</el-button>
      </div>
    </aside>

    <!-- 右：对话区 -->
    <section class="thread">
      <header class="thread-head">
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
          <span v-if="activeConversation?.mode === 'AGENT'" class="agent-hint">
            {{ activeAgentName }}
          </span>
        </div>
      </header>

      <div ref="scroller" v-loading="loadingMessages" class="messages" @scroll="onScroll">
        <div v-if="!items.length" class="welcome">
          <div class="welcome-mark">
            <el-icon :size="26"><ChatDotRound /></el-icon>
          </div>
          <h2>问点什么？</h2>
          <p>涉及你笔记的问题，模型会先检索知识库再回答，并把检索过程显示在这里。</p>
          <div class="samples">
            <button
              v-for="sample in samples"
              :key="sample"
              class="sample"
              @click="send(sample)"
            >
              {{ sample }}
            </button>
          </div>
        </div>

        <article
          v-for="message in items"
          :key="message.id"
          class="message"
          :class="message.role === 'USER' ? 'from-user' : 'from-agent'"
        >
          <div class="bubble">
            <!-- RAG 的检索结果：先于回答出现，用户不必对着空白等生成 -->
            <div v-if="message.retrieval" class="retrieval">
              <span class="retrieval-count">检索到 {{ message.retrieval.count }} 个片段</span>
              <!-- 多库时才说"来自 N 个知识库"：单库下这是废话，只是占地方 -->
              <el-tag
                v-if="message.retrieval.knowledgeBaseIds.length > 1"
                size="small"
                effect="plain"
                type="info"
              >
                {{ message.retrieval.knowledgeBaseIds.length }} 个知识库
              </el-tag>
              <el-tag v-if="message.retrieval.empty" size="small" effect="plain" type="warning">
                知识库里没有命中
              </el-tag>
              <el-tag v-if="message.retrieval.reranked" size="small" effect="plain" type="success">
                已重排
              </el-tag>
              <el-tag
                v-if="message.retrieval.truncated"
                size="small"
                effect="plain"
                type="info"
              >
                预算截断 {{ message.retrieval.truncated }} 段
              </el-tag>
              <ul v-if="message.retrieval.hits.length" class="retrieval-hits">
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
            <ol v-if="message.toolCalls.length" class="trace">
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
      <div v-if="!activeId" class="composer-options">
        <el-radio-group v-model="draftMode" size="small">
          <el-radio-button value="AGENT">Agent（自己决定检索）</el-radio-button>
          <el-radio-button value="RAG">知识库问答（先检索再答）</el-radio-button>
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
        >
          <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
        </el-select>
        <el-select
          v-else
          v-model="draftAgentId"
          size="small"
          placeholder="选择 Agent"
          class="kb-select"
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

      <footer class="composer">
        <el-input
          v-model="input"
          type="textarea"
          :rows="1"
          :autosize="{ minRows: 1, maxRows: 6 }"
          resize="none"
          placeholder="问一个涉及你笔记的问题，Enter 发送、Shift+Enter 换行"
          :disabled="streaming"
          @keydown.enter="onEnter"
        />
        <el-button v-if="streaming" class="send" :icon="VideoPause" @click="stop">停止</el-button>
        <el-button
          v-else
          type="primary"
          class="send"
          :icon="Promotion"
          :disabled="!input.trim()"
          @click="send()"
        >
          发送
        </el-button>
      </footer>
    </section>

    <!-- 评测弹窗 -->
    <el-dialog v-model="evalVisible" title="评测集" width="560px">
      <p class="hint">
        对「问题 + 预期要点」跑一遍 Agent，报告要点命中率与引用有效性
        <template v-if="evalKbName">（知识库：{{ evalKbName }}）</template>
      </p>
      <div class="eval-actions">
        <el-button type="primary" :loading="evalRunning" @click="runEvaluation">开始</el-button>
      </div>

      <div v-if="evalReport" class="eval-report">
        <div class="eval-summary">
          <span class="num">平均命中率 <b>{{ evalReport.avgPointRecall.toFixed(2) }}</b></span>
          <span class="num">通过 <b>{{ evalReport.passed }}</b> / {{ evalReport.results.length }}</span>
        </div>
        <ul>
          <li v-for="(result, index) in evalReport.results" :key="index">
            <span class="eval-q">{{ result.testCase.question }}</span>
            <el-tag size="small" :type="result.pointRecall === 1 ? 'success' : 'warning'">
              {{ result.pointRecall.toFixed(2) }}
            </el-tag>
            <el-tag v-if="result.citationsValid" size="small" effect="plain" type="success">有来源</el-tag>
            <span v-if="result.missingPoints.length" class="eval-missing">
              缺：{{ result.missingPoints.join('、') }}
            </span>
          </li>
        </ul>
      </div>
    </el-dialog>
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

.markdown :deep(p) {
  margin: 0.7em 0;
}

.markdown :deep(ul),
.markdown :deep(ol) {
  padding-left: 1.4em;
  margin: 0.7em 0;
}

.markdown :deep(code) {
  font-family: var(--font-mono);
  font-size: 12.5px;
  background: var(--surface-sunken);
  border-radius: 6px;
  padding: 2px 6px;
}

.markdown :deep(pre) {
  background: var(--text-strong);
  border-radius: var(--radius-card);
  padding: var(--space-4) var(--space-5);
  overflow: auto;
}

.markdown :deep(pre code) {
  background: transparent;
  color: #e8e6e3;
  padding: 0;
}

.markdown :deep(blockquote) {
  margin: 0.8em 0;
  padding: 2px 0 2px var(--space-4);
  border-left: 2px solid var(--accent-300);
  color: var(--text-muted);
}

.markdown :deep(a) {
  color: var(--accent-600);
  text-decoration: underline;
  text-underline-offset: 2px;
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

.eval-actions {
  margin-top: var(--space-4);
}

.eval-report {
  margin-top: var(--space-4);
  padding: var(--space-3) var(--space-4);
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
}

.eval-summary {
  display: flex;
  gap: var(--space-5);
  font-size: 13px;
  color: var(--text-muted);
}

.eval-summary b {
  font-family: var(--font-mono);
  font-size: 15px;
  color: var(--text-strong);
}

.eval-report ul {
  list-style: none;
  margin: var(--space-3) 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.eval-report li {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  font-size: 12.5px;
}

.eval-q {
  color: var(--text);
}

.eval-missing {
  color: var(--text-muted);
  font-size: 12px;
}
</style>
