<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Aim,
  CopyDocument,
  Delete,
  Plus,
  Promotion,
  RefreshLeft
} from '@element-plus/icons-vue'
import {
  createAgent,
  deleteAgent,
  duplicateAgent,
  listAgents,
  setAgentStatus,
  testChat,
  updateAgent,
  type AgentResource,
  type AgentStatus,
  type AgentTestResult,
  type AnswerPolicy
} from '@/api/agents'
import { listKnowledgeBases, type KnowledgeBase } from '@/api/knowledge'
import { listTools, type ToolResource } from '@/api/tools'

/**
 * Agent 管理页（Phase 4.2；5.4 补工具与温度）。
 *
 * 页面的主线是 §十八 要求的"先测试再发布"，所以每个 Agent 的按钮顺序就是它的生命周期：
 * 新建（DRAFT）→ 试跑 → 发布 → 用（会话里可选）→ 停用。
 *
 * 三条界面上的硬约束，都是为了不让"配置"和"生效"之间出现错觉：
 * 1. **状态必须看得见**：草稿/已停用不能用于对话，界面上直接标出来，
 *    而不是让用户绑到会话里再吃一个 409；
 * 2. **范围必须看得见**：`kbIds` 为空 = 检索全部知识库（与 RAG 会话的 kbIds 语义相反），
 *    这句话必须写在界面上——它是本项目里最容易误读的一处语义。
 *    `toolCodes` 沿用同一条约定（空 = 全部工具），因此同样要写；
 * 3. **试跑不要求已发布**：草稿正是要被测的对象，所以试跑按钮对 DRAFT 也可用。
 *
 * 5.4 新增两处配置：**工具白名单**与**采样温度**。两个都必须回显到卡片上——
 * 后端从 4.3 起就支持 `toolCodes`，但界面一直没有入口、也不显示，等于这个能力
 * 对用户不存在（P1-9）；温度同理，配了却不显示就分不清"我配过没有"（P1-8）。
 */

const loading = ref(false)
const agents = ref<AgentResource[]>([])
const knowledgeBases = ref<KnowledgeBase[]>([])
const tools = ref<ToolResource[]>([])

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ code: '', name: '', description: '' })

const editVisible = ref(false)
const saving = ref(false)
const editTarget = ref<AgentResource | null>(null)
const editForm = reactive({
  name: '',
  description: '',
  systemPrompt: '',
  answerPolicy: 'KB_PREFERRED' as AnswerPolicy,
  kbIds: [] as string[],
  toolCodes: [] as string[],
  /**
   * 温度用 `number | null` 而不是 0 当空值：**null = 跟随服务端默认，0 = 显式要求确定性**。
   * el-input-number 清空后给的是 null/undefined，正好对应"不改"（后端也是这么读的）。
   */
  temperature: null as number | null
})

const testVisible = ref(false)
const testing = ref(false)
const testTarget = ref<AgentResource | null>(null)
const testQuestion = ref('')
const testResult = ref<AgentTestResult | null>(null)
const testError = ref('')

const STATUS_TEXT: Record<AgentStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  DISABLED: '已停用'
}

const STATUS_TAG: Record<AgentStatus, 'info' | 'success' | 'warning'> = {
  DRAFT: 'info',
  PUBLISHED: 'success',
  DISABLED: 'warning'
}

/** 卡片上的一句话说法（完整解释放在配置弹窗的单选项里，卡片只要一眼能分辨）。 */
const POLICY_TEXT: Record<AnswerPolicy, string> = {
  KB_PREFERRED: '优先用笔记（没有时可用自身知识，会注明）',
  KB_ONLY: '只依据笔记'
}

const publishedCount = computed(() => agents.value.filter((a) => a.status === 'PUBLISHED').length)

/**
 * 试跑弹窗里显示"这次跑的范围"：与线上一致，否则试跑结论不能代表会话里的行为。
 *
 * 5.4 起把工具也报出来——它和白名单是同一层的东西，只报知识库的话，
 * "试跑没检索"会被当成知识库没配好，而真正的原因可能是工具白名单里没有检索工具。
 */
const testScopeText = computed(() => (testTarget.value
  ? `${kbNames(testTarget.value)}；${toolNames(testTarget.value)}`
  : ''))

onMounted(async () => {
  await Promise.all([load(), loadKnowledgeBases(), loadTools()])
})

async function load() {
  loading.value = true
  try {
    agents.value = (await listAgents()).list
  } finally {
    loading.value = false
  }
}

async function loadKnowledgeBases() {
  try {
    knowledgeBases.value = (await listKnowledgeBases()).list
  } catch {
    knowledgeBases.value = []
  }
}

async function loadTools() {
  try {
    tools.value = await listTools()
  } catch {
    // 工具目录拿不到不该让整页打不开：它是配置里的附加项，不是页面的主体。
    // 降级成一个空清单，用户在配置弹窗里看到"没有可选工具"时至少页面还能用。
    tools.value = []
  }
}

/**
 * 绑定的知识库（列表里显示名字而不是 id：id 对人没有意义）。
 *
 * 带上数量（Phase 5.5.1）："产品手册、内部 FAQ、运维手册"要数一遍才知道是 3 个，
 * 而数量正是用户判断这个 Agent 的检索范围是否符合预期的第一眼信息——
 * 卡片上还要显示模型角色、策略等好几行，长名字列表会把它挤成看不懂的一坨。
 * 上面的 AgentView（单选改多选）早就是这个格式，这里跟上，免得两个页面各说各的。
 *
 * 空绑定仍是"全部知识库"：那是**语义**（不限制），不是"绑了零个"，不能写成"0 个库"。
 */
function kbNames(agent: AgentResource): string {
  if (!agent.kbIds?.length) return '全部知识库'
  const names = agent.kbIds.map(
    (id) => knowledgeBases.value.find((kb) => kb.id === id)?.name ?? `#${id}`
  )
  return `${names.length} 个库（${names.join('、')}）`
}

/**
 * 工具白名单的显示名。**空 = 全部工具**（与 kbIds 同一套语义）。
 *
 * 已停用的工具后面标出来：白名单里留着一个被停用的 code 不会让它复活
 * （装配时取的是"启用中的 ∩ 白名单"，见 AgentService.replaceTools），
 * 但用户看到名字会以为它还在用——标出来才不会白排查。
 */
function toolNames(agent: AgentResource): string {
  if (!agent.toolCodes?.length) return '全部工具'
  return agent.toolCodes
    .map((code) => {
      const tool = tools.value.find((item) => item.code === code)
      if (!tool) return code
      return tool.enabled ? tool.name : `${tool.name}（已停用）`
    })
    .join('、')
}

/** 温度在卡片上的说法：null 必须显示成"服务端默认"而不是 0（两者含义不同）。 */
function temperatureText(agent: AgentResource): string {
  return agent.temperature === null || agent.temperature === undefined
    ? '服务端默认'
    : String(agent.temperature)
}

async function submitCreate() {
  const code = createForm.code.trim()
  const name = createForm.name.trim()
  if (!code || !name) {
    ElMessage.warning('标识与名称都要填')
    return
  }
  creating.value = true
  try {
    await createAgent({ code, name, description: createForm.description.trim() || undefined })
    ElMessage.success('已创建（草稿），先试跑一次再发布')
    createVisible.value = false
    createForm.code = ''
    createForm.name = ''
    createForm.description = ''
    await load()
  } finally {
    creating.value = false
  }
}

function openEdit(agent: AgentResource) {
  editTarget.value = agent
  editForm.name = agent.name
  editForm.description = agent.description ?? ''
  editForm.systemPrompt = agent.systemPrompt ?? ''
  editForm.answerPolicy = agent.answerPolicy ?? 'KB_PREFERRED'
  editForm.kbIds = [...(agent.kbIds ?? [])]
  editForm.toolCodes = [...(agent.toolCodes ?? [])]
  // 原样搬过来（含 null）——不能写 `?? 0`：那会把"没配过、跟随全局"变成"我显式要了 0"
  editForm.temperature = agent.temperature ?? null
  editVisible.value = true
}

async function submitEdit() {
  const target = editTarget.value
  if (!target) return
  saving.value = true
  try {
    await updateAgent(target.id, {
      name: editForm.name.trim() || undefined,
      description: editForm.description,
      systemPrompt: editForm.systemPrompt,
      answerPolicy: editForm.answerPolicy,
      kbIds: editForm.kbIds,
      // [] = 回到"不限制"（不是"一个都不给"），后端 replaceTools 是这个语义
      toolCodes: editForm.toolCodes,
      // 清空输入框 = undefined = 不改。后端没有"改回跟随默认"的传法，
      // 所以提示文案必须写在后端字段注释与界面 hint 里，别让用户以为清空就是清掉
      temperature: editForm.temperature ?? undefined
    })
    ElMessage.success('已保存（下一轮对话就生效）')
    editVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

/** 发布 / 停用 / 重新启用：一个按钮走完状态机，文案由当前状态决定。 */
async function toggleStatus(agent: AgentResource) {
  const next: AgentStatus = agent.status === 'PUBLISHED' ? 'DISABLED' : 'PUBLISHED'
  const label = next === 'PUBLISHED'
    ? (agent.status === 'DISABLED' ? '重新启用' : '发布')
    : '停用'
  if (next === 'DISABLED') {
    await ElMessageBox.confirm(
      `停用后，正在使用「${agent.name}」的会话会直接报错（不会悄悄换回内置 Agent）。`,
      '确认停用？',
      { confirmButtonText: '停用', cancelButtonText: '取消', type: 'warning' }
    )
  }
  await setAgentStatus(agent.id, next)
  ElMessage.success(next === 'PUBLISHED' ? `${label}成功，会话里可以选它了` : `${label}成功`)
  await load()
}

async function duplicate(agent: AgentResource) {
  const copy = await duplicateAgent(agent.id)
  ElMessage.success(`已复制为「${copy.name}」（草稿，先试跑再发布）`)
  await load()
}

async function remove(agent: AgentResource) {
  await ElMessageBox.confirm(
    `将删除 Agent「${agent.name}」。使用它的会话不会被删除，但会失去这个 Agent。`,
    '确认删除？',
    { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
  )
  await deleteAgent(agent.id)
  ElMessage.success('已删除')
  await load()
}

function openTest(agent: AgentResource) {
  testTarget.value = agent
  testQuestion.value = ''
  testResult.value = null
  testError.value = ''
  testVisible.value = true
}

/**
 * 试跑。失败时把后端原话显示在弹窗里而不是只弹一条 3 秒就消失的提示——
 * 没有配置模型时错误信息本身很重要（它会列出已注册的角色，见 4006）。
 */
async function runTest() {
  const target = testTarget.value
  const question = testQuestion.value.trim()
  if (!target || !question) {
    ElMessage.warning('先写一个问题')
    return
  }
  testing.value = true
  testResult.value = null
  testError.value = ''
  try {
    testResult.value = await testChat(target.id, question)
  } catch (error) {
    testError.value = error instanceof Error ? error.message : String(error)
  } finally {
    testing.value = false
  }
}

/**
 * 填一个示例问题。
 *
 * 必须**具体到有主题**——这一点是 E2E 抓出来的：原来绑定知识库时填的是
 * "我的笔记里和这个问题相关的内容是什么？"，而"这个问题"没有指代对象。
 * 模型于是反问"你指的是哪个问题"、一次都没检索，界面随即弹出
 * "未使用工具……别急着发布"的警告——**对一份完全正常的配置误报**。
 * 模型不检索是正确的行为，错的是样例本身。绑了库时范围已经确定，问得短一点即可。
 */
function applyTestQuestion() {
  testQuestion.value = testTarget.value?.kbIds?.length
    ? '缓存击穿怎么解决？'
    : '我的笔记里缓存击穿怎么解决？'
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1>Agent</h1>
        <p class="sub">
          每个 Agent 是一套"提示词 + 模型 + 能检索哪些知识库 + 能用哪些工具"的配置。
          对话时选它，模型就按这套配置执行——改完提示词不必重建会话，下一轮就生效。
        </p>
      </div>
      <div class="actions">
        <el-button type="primary" :icon="Plus" @click="createVisible = true">新建 Agent</el-button>
      </div>
    </header>

    <div v-loading="loading" class="content">
      <el-empty v-if="!loading && !agents.length" description="还没有 Agent" />

      <div v-else class="grid">
        <article v-for="(agent, index) in agents" :key="agent.id" class="card fade-up" :style="{ '--i': index }">
          <div class="card-head">
            <div class="title-row">
              <h2 class="name">{{ agent.name }}</h2>
              <el-tag size="small" effect="plain" :type="STATUS_TAG[agent.status]">
                {{ STATUS_TEXT[agent.status] }}
              </el-tag>
              <el-tag v-if="agent.builtin" size="small" effect="plain" type="info">内置</el-tag>
            </div>
            <code class="code">{{ agent.code }}</code>
            <p class="desc">{{ agent.description || '（无描述）' }}</p>
          </div>

          <dl class="facts">
            <div>
              <dt>检索范围</dt>
              <dd>{{ kbNames(agent) }}</dd>
            </div>
            <div>
              <dt>模型角色</dt>
              <dd class="mono">{{ agent.modelRole || '服务端默认' }}</dd>
            </div>
            <div>
              <dt>回答策略</dt>
              <dd>{{ POLICY_TEXT[agent.answerPolicy] ?? POLICY_TEXT.KB_PREFERRED }}</dd>
            </div>
            <div>
              <dt>可用工具</dt>
              <dd>{{ toolNames(agent) }}</dd>
            </div>
            <div>
              <dt>温度</dt>
              <dd class="mono">{{ temperatureText(agent) }}</dd>
            </div>
          </dl>

          <p v-if="!agent.kbIds?.length" class="range-hint">
            未绑定知识库 = 可检索你的全部知识库（与"知识库问答"会话的绑定语义相反）
          </p>

          <p v-if="agent.systemPrompt" class="prompt">{{ agent.systemPrompt }}</p>

          <div class="card-actions">
            <el-button size="small" :icon="Promotion" @click="openTest(agent)">试跑</el-button>
            <el-button size="small" :icon="Aim" @click="openEdit(agent)">配置</el-button>
            <el-button
              v-if="!agent.builtin"
              size="small"
              :icon="agent.status === 'PUBLISHED' ? RefreshLeft : Promotion"
              @click="toggleStatus(agent)"
            >
              {{ agent.status === 'PUBLISHED' ? '停用' : (agent.status === 'DISABLED' ? '重新启用' : '发布') }}
            </el-button>
            <el-button size="small" :icon="CopyDocument" @click="duplicate(agent)">复制</el-button>
            <el-button
              v-if="!agent.builtin"
              size="small"
              link
              type="danger"
              class="danger"
              :icon="Delete"
              @click="remove(agent)"
            >
              删除
            </el-button>
          </div>

          <p v-if="agent.builtin" class="builtin-note">
            内置默认 Agent：不选 Agent 的对话会落到它。不可删除、不可停用，但可以改名与改提示词。
          </p>
        </article>
      </div>
    </div>

    <p class="foot-note">
      当前可用（已发布）的 Agent：{{ publishedCount }} 个 ·
      只有"已发布"能被对话选用：草稿要先试跑通过再发布，停用后使用它的会话会明确报错
    </p>

    <!-- 新建 -->
    <el-dialog v-model="createVisible" title="新建 Agent" width="520px">
      <el-form label-position="top">
        <el-form-item label="标识（code）" required>
          <el-input v-model="createForm.code" placeholder="例如 note-helper" maxlength="64" />
        </el-form-item>
        <p class="hint">
          稳定标识，只能用小写字母/数字/-/_/. ，创建后不可改：会话、工具、工作流引用的是它，
          不是显示名。
        </p>
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" placeholder="例如 笔记助手" maxlength="64" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" type="textarea" :rows="2" maxlength="512" />
        </el-form-item>
        <p class="hint">
          新建后是<b>草稿</b>：草稿不能被对话选用，但可以试跑——改完提示词、
          绑完知识库、试跑满意了再发布。提示词与知识库在"配置"里改。
        </p>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 配置 -->
    <el-dialog v-model="editVisible" :title="`配置「${editTarget?.name ?? ''}」`" width="640px">
      <el-form label-position="top">
        <el-form-item label="名称">
          <el-input v-model="editForm.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="2" maxlength="512" />
        </el-form-item>
        <el-form-item label="系统提示词">
          <el-input
            v-model="editForm.systemPrompt"
            type="textarea"
            :rows="7"
            placeholder="留空 = 服务端的默认提示词"
          />
        </el-form-item>
        <p class="hint">
          提示词决定它的行为边界。留空时用服务端默认，不会变成"没有提示词"。
          <b>"没有资料时怎么办"不在提示词里写</b>——由下面的「回答策略」统一控制，
          两处都写会互相矛盾。
        </p>
        <el-form-item label="回答策略">
          <el-radio-group v-model="editForm.answerPolicy">
            <el-radio value="KB_PREFERRED">
              优先用笔记（笔记里没有时，可用自身知识回答，并注明不是来自笔记）
            </el-radio>
            <el-radio value="KB_ONLY">
              只依据笔记（没有找到就直说，不用自身知识）
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <p class="hint">
          两种策略都<b>禁止</b>把模型自身的知识说成是笔记里的内容——区别只在"没有可引用资料时答不答"。
          还没有导入笔记的新用户，选「优先用笔记」才不会第一次提问就被拒。
        </p>
        <el-form-item label="可检索的知识库">
          <el-select
            v-model="editForm.kbIds"
            multiple
            clearable
            placeholder="不选 = 全部知识库"
            class="kb-multi"
          >
            <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
          </el-select>
        </el-form-item>
        <p class="hint">
          <b>不选 = 可检索你的全部知识库</b>（不是"检索不到"）。这与"知识库问答"会话必须绑定一个库
          的语义正好相反——两处都写清楚了，是因为它最容易误读。
        </p>
        <el-form-item label="可用工具">
          <!-- 类名与知识库那个选择器分开（样式共用一条规则）：两者在同一弹窗里，
               共用一个类名会让"按类名定位"这件事变得歧义——界面上看不出来，
               但 scripts/verify-agents.mjs 那种按元素定位的脚本会直接报"匹配到 2 个" -->
          <el-select
            v-model="editForm.toolCodes"
            multiple
            clearable
            placeholder="不选 = 全部工具"
            class="tool-multi"
          >
            <el-option
              v-for="tool in tools"
              :key="tool.code"
              :label="tool.enabled ? tool.name : `${tool.name}（已停用）`"
              :value="tool.code"
              :disabled="!tool.enabled"
            />
          </el-select>
        </el-form-item>
        <p class="hint">
          <b>不选 = 允许它使用你的全部工具</b>（不是"一个工具都不给"）——与上面的知识库是同一套语义。
          想收窄范围时才在这里勾选；已停用的工具显示为灰，它不会被装配进任何 Agent，
          即使白名单里留着它（真要用先去"工具"里启用）。
        </p>
        <el-form-item label="采样温度">
          <el-input-number
            v-model="editForm.temperature"
            :min="0"
            :max="2"
            :step="0.1"
            :precision="2"
            controls-position="right"
            placeholder="留空 = 服务端默认"
          />
        </el-form-item>
        <p class="hint">
          0–2，越小越确定、越大越发散。<b>留空 = 跟随服务端的全局默认值</b>，与填 0
          不是一回事：0 是"我要最确定的回答"，留空是"用系统的默认"。
          改动只对<b>由 Agent 驱动的对话</b>生效（Agent 模式的会话与试跑）；
          普通对话与"知识库问答"会话仍用全局默认。注意：这里<b>没有</b>"改回跟随默认"的按钮，
          留空表示"不动它"，想清掉请复制一个新 Agent。
        </p>
        <p class="hint">
          模型角色暂不在界面上配置：可选值由服务端的模型注册表决定，而列出角色的接口还没有
          （属平台化的 Model Config 增量）。留空即用服务端默认角色。
        </p>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 试跑 -->
    <el-dialog v-model="testVisible" :title="`试跑「${testTarget?.name ?? ''}」`" width="680px">
      <p class="hint">
        试跑是一次同步问答：不进会话、不落消息表，草稿也能跑。
        它带 <b>{{ testScopeText }}</b> 的检索范围——
        与线上一致，否则"试跑正常、用到会话里搜不到"这种情况没法提前发现。
      </p>
      <div class="test-question">
        <el-input
          v-model="testQuestion"
          placeholder="问一个与它绑定的知识库相关的问题"
          @keydown.enter="runTest"
        />
        <el-button link size="small" @click="applyTestQuestion">用示例问题</el-button>
      </div>
      <div class="test-actions">
        <el-button type="primary" :loading="testing" @click="runTest">开始试跑</el-button>
      </div>

      <div v-if="testError" class="test-error">
        <strong>试跑失败</strong>
        <p>{{ testError }}</p>
        <p class="hint">
          没有配置模型时这里是模型注册表的报错（例如 4006，它会列出已注册的模型），
          不是 Agent 配置本身的问题。
        </p>
      </div>

      <div v-if="testResult" class="test-result">
        <div class="test-meta">
          <el-tag size="small" effect="plain" :type="testResult.usedTools ? 'success' : 'info'">
            {{ testResult.usedTools ? `用了 ${testResult.toolCalls.length} 次工具` : '未使用工具' }}
          </el-tag>
          <span v-if="!testResult.usedTools" class="hint">
            这一轮模型没有检索。若你的问题明显需要笔记内容，说明检索链路没跑通，
            别急着发布。
          </span>
        </div>
        <ol v-if="testResult.toolCalls.length" class="test-trace">
          <li v-for="(call, index) in testResult.toolCalls" :key="index">
            <span class="trace-input">{{ call.input }}</span>
            <span class="trace-result">{{ call.resultSummary }}</span>
          </li>
        </ol>
        <p class="answer">{{ testResult.answer || '（空回答）' }}</p>
      </div>

      <template #footer>
        <el-button @click="testVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  padding: var(--space-4) 0 var(--space-8);
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-5);
  margin-bottom: var(--space-6);
}

.sub {
  margin: var(--space-2) 0 0;
  max-width: 68ch;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.7;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: var(--space-4);
}

.card {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-5);
  background: var(--surface-raised);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  transition:
    transform var(--dur-base) var(--ease-out-expo),
    box-shadow var(--dur-base) var(--ease-out-expo);
}

.card:hover {
  transform: translate3d(0, -2px, 0);
  box-shadow: var(--shadow-raised);
}

.card-head {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.title-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.name {
  font-size: 16px;
}

.code {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--text-faint);
}

.desc {
  margin: var(--space-1) 0 0;
  color: var(--text-muted);
  font-size: 13px;
}

.facts {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--space-1);
  margin: 0;
  padding: var(--space-3) var(--space-4);
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
}

.facts > div {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
}

.facts dt {
  flex: none;
  width: 60px;
  color: var(--text-muted);
  font-size: 11.5px;
}

.facts dd {
  margin: 0;
  font-size: 12.5px;
  color: var(--text);
  overflow-wrap: anywhere;
}

.mono {
  font-family: var(--font-mono);
}

.range-hint {
  margin: 0;
  color: var(--text-faint);
  font-size: 11.5px;
  line-height: 1.6;
}

.prompt {
  margin: 0;
  padding-left: var(--space-3);
  border-left: 2px solid var(--accent-300);
  color: var(--text-muted);
  font-size: 12.5px;
  line-height: 1.7;
  max-height: 4.8em;
  overflow: hidden;
}

.card-actions {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  flex-wrap: wrap;
  margin-top: auto;
}

.danger {
  margin-left: auto;
}

.builtin-note {
  margin: 0;
  color: var(--text-faint);
  font-size: 11.5px;
  line-height: 1.6;
}

.foot-note {
  margin: var(--space-5) 0 0;
  color: var(--text-muted);
  font-size: 12.5px;
}

.hint {
  margin: 0 0 var(--space-3);
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.7;
}

.kb-multi,
.tool-multi {
  width: 100%;
}

/* ---------- 试跑弹窗 ---------- */
.test-question {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.test-actions {
  margin-top: var(--space-3);
}

.test-error {
  margin-top: var(--space-4);
  padding: var(--space-3) var(--space-4);
  background: var(--danger-soft);
  border-radius: var(--radius-sm);
  font-size: 12.5px;
  line-height: 1.7;
}

.test-error p {
  margin: var(--space-1) 0 0;
  color: var(--text);
  overflow-wrap: anywhere;
}

.test-result {
  margin-top: var(--space-4);
  padding: var(--space-4);
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
}

.test-meta {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.test-trace {
  list-style: none;
  margin: var(--space-3) 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  font-size: 12px;
}

.test-trace li {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
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

.answer {
  margin: var(--space-3) 0 0;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text);
  white-space: pre-wrap;
}
</style>
