<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, DataAnalysis, Delete, Promotion, Plus, VideoPause } from '@element-plus/icons-vue'
import {
  createConversation,
  deleteConversation,
  listConversations,
  listMessages,
  stopStream,
  streamAgentChat,
  type Conversation,
  type MessageToolCall
} from '@/api/chat'
import { DEFAULT_EVAL_CASES, runEval, type EvalReport } from '@/api/agent'
import { listKnowledgeBases } from '@/api/knowledge'
import { renderMarkdown } from '@/utils/markdown'

/**
 * Agent 对话。
 *
 * 一轮问答的完整过程（每一步都看得见）：
 *   meta → tool_call（正在检索…）→ tool_result（命中多少条）→ delta…（逐字回答）→ done
 *
 * 界面上的三条原则：
 * 1. **实时**：工具调用与回答都是边发生边显示，不让用户对着空屏等；
 * 2. **可回看**：工具痕迹随消息落库，刷新/切换会话后仍在；
 * 3. **可中断**：生成中随时能停，停掉是真的断开上游（不是只关界面）。
 */

/** 界面上的一条消息。流式进行中的那条是临时消息，结束后用服务端数据校正。 */
interface ChatItem {
  id: string
  role: 'USER' | 'ASSISTANT'
  content: string
  toolCalls: MessageToolCall[]
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

const requestId = ref('')
const abortController = ref<AbortController | null>(null)
const scroller = ref<HTMLElement | null>(null)
/** 用户往上翻看历史时不要把他拽回底部 */
const stickToBottom = ref(true)

const activeConversation = computed(() =>
  conversations.value.find((conversation) => conversation.id === activeId.value)
)

/** 示例问题：直接点就能跑，省去"第一句该问什么"的犹豫 */
const samples = ['我的笔记里缓存击穿怎么解决？', '线程池的核心参数有哪些坑？', 'G1 和 ZGC 该怎么选？']

onMounted(async () => {
  await loadConversations()
  // 进来就选中最近一次会话：比让用户看一个空面板更省一步
  if (conversations.value.length) {
    await selectConversation(conversations.value[0].id)
  }
})

async function loadConversations() {
  loadingList.value = true
  try {
    conversations.value = (await listConversations()).list
  } finally {
    loadingList.value = false
  }
}

/** 后端消息 → 界面消息。空内容的骨架消息（生成中崩溃留下的）不显示。 */
function toItems(list: Array<{ id: string; role: string; content: string | null; toolCalls: MessageToolCall[] }>) {
  return list
    .filter((message) => message.role === 'USER' || message.role === 'ASSISTANT')
    .filter((message) => (message.content ?? '').length > 0 || message.toolCalls.length > 0)
    .map((message) => ({
      id: message.id,
      role: message.role as 'USER' | 'ASSISTANT',
      content: message.content ?? '',
      toolCalls: message.toolCalls ?? [],
      streaming: false
    }))
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
  activeId.value = ''
  items.value = []
  input.value = ''
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

/** 发送一轮：没有会话就先建一个（Agent 模式），再流式生成。 */
async function send(preset?: string) {
  const content = (preset ?? input.value).trim()
  if (!content || streaming.value) return

  let conversationId = activeId.value
  if (!conversationId) {
    const created = await createConversation('AGENT')
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
    streaming: true
  }
  items.value.push(answer)
  streaming.value = true
  stickToBottom.value = true
  await scrollToBottom(true)

  const controller = new AbortController()
  abortController.value = controller

  try {
    await streamAgentChat(
      conversationId,
      content,
      {
        onMeta: (meta) => {
          requestId.value = meta.requestId
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
          @click="selectConversation(conversation.id)"
        >
          <span class="conversation-title">{{ conversation.title || '新对话' }}</span>
          <span class="conversation-meta num">
            {{ conversation.messageCount }} 条 ·
            {{ timeLabel(conversation.lastMessageAt || conversation.createdAt) }}
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
          <h1>{{ activeConversation?.title || 'Agent 对话' }}</h1>
          <p class="sub">
            模型先决定是否检索你的知识库（工具调用），再基于检索结果回答；每次检索都留痕
          </p>
        </div>
        <el-tag v-if="activeConversation" size="small" effect="plain">AGENT</el-tag>
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
            <!-- 工具痕迹：过程可见。检索中与已完成用两种状态区分 -->
            <ol v-if="message.toolCalls.length" class="trace">
              <li v-for="(call, index) in message.toolCalls" :key="index">
                <el-tag size="small" effect="plain" :type="isPending(call) ? 'info' : 'success'">
                  {{ isPending(call) ? '检索中' : '已检索' }}
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
          </div>
        </article>
      </div>

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

.conversation-delete {
  position: absolute;
  top: 50%;
  right: var(--space-2);
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

.conversation:hover .conversation-delete {
  opacity: 1;
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
