<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance } from 'element-plus'
import { useConfirm } from '@/components/common/useConfirm'

const { confirm } = useConfirm()
import { Cpu, Document, FolderOpened, Plus, UploadFilled, MoreFilled } from '@element-plus/icons-vue'
import {
  createKnowledgeBase,
  getImportTask,
  listKnowledgeBases,
  runWorkflow,
  uploadDocument,
  uploadVault,
  type ImportTask,
  type KnowledgeBase
} from '@/api/knowledge'

const router = useRouter()

const loading = ref(false)
const items = ref<KnowledgeBase[]>([])

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ name: '', description: '' })
const createFormRef = ref<FormInstance>()

/** 导入进度（每个知识库一份，支持同时看多个） */
const importTasks = reactive<Record<string, ImportTask>>({})
const uploading = ref('')
/** 正在建立索引的知识库（导入之后的第二步，见 ensureSearchable） */
const indexing = ref('')

onMounted(load)

async function load() {
  loading.value = true
  try {
    const page = await listKnowledgeBases()
    items.value = page.list
  } finally {
    loading.value = false
  }
}

async function submitCreate() {
  if (!createForm.name.trim()) {
    ElMessage.warning('请填写知识库名称')
    return
  }
  creating.value = true
  try {
    await createKnowledgeBase(createForm.name.trim(), createForm.description.trim() || undefined)
    ElMessage.success('知识库已创建')
    createVisible.value = false
    createForm.name = ''
    createForm.description = ''
    await load()
  } finally {
    creating.value = false
  }
}

async function remove(kb: KnowledgeBase) {
  // 旧写法没有 catch：用户点取消时 ElMessageBox 会 reject，
  // 于是这个函数变成 rejected promise、@click 上出现未处理 rejection。
  // confirm() 取消时返回 false 不抛，这里显式早退。
  const ok = await confirm({
    title: '确认删除知识库？',
    description: `将删除「${kb.name}」及其 ${kb.docCount} 篇文档、${kb.chunkCount} 个切片。此操作不可撤销。`,
    confirmText: '删除',
    danger: true,
    customClass: 'nexus-confirm-kb-delete'
  })
  if (!ok) return
  const { deleteKnowledgeBase } = await import('@/api/knowledge')
  await deleteKnowledgeBase(kb.id)
  ElMessage.success('已删除')
  await load()
}

/** 单文件导入接受的格式。与后端解析器注册表一致（MD / TXT / PDF / DOCX）。 */
const FILE_ACCEPT = '.md,.txt,.pdf,.docx'

/**
 * 打开系统文件选择器并导入。
 *
 * **踩过的坑（UA-001）**：这里曾经在 `input.click()` **之前**就置 `uploading`，
 * 指望 `onchange` 里的那一行把它复位。但用户在系统弹窗里点「取消」时，
 * 浏览器**根本不派发 change 事件**（files 为空也不派发），于是复位那行永远不执行，
 * 按钮就永久转圈——只能刷新页面。这不是"少了个兜底"，是状态机的顺序错了。
 *
 * 修法：只有真正拿到文件才置 loading，并且用 `finally` 无条件复位。
 * 取消 = files 为空 = 直接 return，界面与点击前完全一致（什么都不发生）。
 *
 * 详情页（KnowledgeBaseDetailView）从一开始就没有这个问题，因为它没有提前置位——
 * 两处实现本来就该是同一种写法，这里对齐它。
 */
function pickImport(kb: KnowledgeBase, accept: string, multiple: boolean, mode: 'files' | 'vault') {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = accept
  input.multiple = multiple
  input.onchange = async () => {
    const files = Array.from(input.files ?? [])
    // 取消选择：什么都没发生过。**此处绝不能碰 loading**。
    if (!files.length) return
    // 串行提交：后端"一个文件一个任务"，并行投递会让同一张卡片的进度互相覆盖
    for (const file of files) {
      await runImport(kb, file, mode)
    }
  }
  input.click()
}

async function runImport(kb: KnowledgeBase, file: File, mode: 'files' | 'vault') {
  uploading.value = kb.id
  try {
    ElMessage.info(`正在上传 ${file.name}（${formatSize(file.size)}）`)
    const task =
      mode === 'vault' ? await uploadVault(kb.id, file) : await uploadDocument(kb.id, file)
    importTasks[kb.id] = task
    // 后端是异步处理（QUEUED → RUNNING → DONE），轮询到终态为止
    await pollTask(kb, task.id)
  } catch {
    // 失败原因已由 client.ts 的响应拦截器统一 toast（后端返回的是中文原话，
    // 例如"暂不支持的文件类型：slides.pptx（当前支持 […]）"），这里只收掉未处理的 rejection
  } finally {
    // 成功、失败、异常，三条路径都必须回到稳定状态
    uploading.value = ''
  }
}

/** 文件大小：列表里写"2.4 MB"比"2516582"有用 */
function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/**
 * 导入完成 ≠ 可以问答 —— 中间还差"向量化 + 建索引"。
 *
 * **实测发现（UA-014）**：TXT / MD / PDF / DOCX / ZIP 五种格式导入全部 `DONE`、
 * 切片数正常、失败 0，但拿文件里的独有标记去检索是 **0 命中**。
 * 回代码确认 `ImportTaskExecutor` 只做"解析 + 分块"，**没有任何 embedding / index 调用**；
 * 可检索性由 `GET /workflow/run/{kbId}`（统计 → 向量化 → 建索引）负责。
 *
 * 后果是：新用户上传完看到"已完成"，去提问却什么都搜不到，
 * 而产品唯一的提示是检索失败之后那句「确认已『向量化』与『建索引』」——
 * 也就是说，**它把两个不该让用户理解的概念，变成了使用前必须知道的隐性前提**。
 *
 * 这里改为导入终态后自动串接这一步：用户只需要看"解析中 → 建立索引中 → 已完成"。
 * workflow 的四步是增量的（只处理待向量化/待建索引的切片），重复执行安全。
 */
async function ensureSearchable(kb: KnowledgeBase) {
  indexing.value = kb.id
  try {
    const steps = await runWorkflow(kb.id)
    const failedStep = steps.find((step) => step.status !== 'SUCCESS')
    if (failedStep) {
      ElMessage.warning(`「${kb.name}」索引未完成：${failedStep.name} — ${failedStep.message}`)
    } else {
      ElMessage.success(`「${kb.name}」已可提问`)
    }
  } catch {
    // 不阻断：文件已经进来了，索引可以稍后在详情页手动重跑
    ElMessage.warning('自动建立索引失败，可到知识库详情页手动执行「向量化」「建索引」')
  } finally {
    indexing.value = ''
  }
}

async function pollTask(kb: KnowledgeBase, taskId: string) {
  for (let i = 0; i < 240; i++) {
    await new Promise((resolve) => setTimeout(resolve, 1200))
    const task = await getImportTask(taskId)
    importTasks[kb.id] = task
    if (['DONE', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(task.status)) {
      if (task.status === 'DONE' || task.status === 'PARTIAL') {
        // 解析完成后必须再建索引，否则"已完成"却搜不到（见 ensureSearchable）
        await ensureSearchable(kb)
      }
      if (task.status === 'DONE') {
        ElMessage.success(
          `导入完成：新增 ${task.createdCount} / 更新 ${task.updatedCount} / 跳过 ${task.skippedCount} / 切片 ${task.totalChunks}`
        )
      } else {
        ElMessage.warning(`导入${task.status === 'PARTIAL' ? '部分成功' : '失败'}，失败 ${task.failedCount} 项`)
      }
      await load()
      return
    }
  }
  ElMessage.warning('导入仍在进行，可稍后刷新查看')
}

function statusText(kb: KnowledgeBase): string {
  const task = importTasks[kb.id]
  if (!task) return ''
  const map: Record<string, string> = {
    QUEUED: '排队中',
    RUNNING: '处理中',
    DONE: '已完成',
    PARTIAL: '部分成功',
    FAILED: '失败'
  }
  return map[task.status] ?? task.status
}

function taskType(kb: KnowledgeBase): 'success' | 'warning' | 'danger' | 'info' {
  const status = importTasks[kb.id]?.status
  if (status === 'DONE') return 'success'
  if (status === 'PARTIAL') return 'warning'
  if (status === 'FAILED') return 'danger'
  return 'info'
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1>知识库</h1>
        <p class="sub">导入你的资料（PDF / Word / Markdown / TXT，或整个 Obsidian Vault 打包成 ZIP），系统切分成片段后用于问答</p>
      </div>
      <div class="actions">
        <el-button :icon="Cpu" data-testid="kb-ask-agent" @click="router.push('/agent')">问 Agent</el-button>
        <el-button type="primary" :icon="Plus" data-testid="kb-create-open" @click="createVisible = true">新建知识库</el-button>
      </div>
    </header>

    <div v-loading="loading" class="content">
      <el-empty
        v-if="!loading && items.length === 0"
        data-testid="kb-empty"
        description="还没有知识库"
      >
        <p class="empty-hint">
          先创建一个知识库，再导入你的资料——支持 PDF、DOCX、Markdown、TXT，
          也可以把整个 Obsidian Vault 打包成 ZIP 导入。
        </p>
        <el-button type="primary" :icon="Plus" data-testid="kb-create-open-empty" @click="createVisible = true">新建知识库</el-button>
      </el-empty>

      <div v-else class="grid">
        <article
          v-for="(kb, index) in items"
          :key="kb.id"
          class="card fade-up spotlight"
          data-testid="kb-card"
          :data-kb-name="kb.name"
          :style="{ '--i': index }"
        >
          <div class="card-head">
            <div class="title-row">
              <h2 class="name" @click="router.push(`/kb/${kb.id}`)">{{ kb.name }}</h2>
              <el-tag
                size="small"
                effect="plain"
                :type="kb.sourceType === 'OBSIDIAN' ? 'success' : 'info'"
              >
                {{ kb.sourceType === 'OBSIDIAN' ? 'Vault' : '手工' }}
              </el-tag>
            </div>
            <p class="desc">{{ kb.description || '（无描述）' }}</p>
          </div>

          <dl class="metrics">
            <div>
              <dt>文档</dt>
              <dd class="num">{{ kb.docCount }}</dd>
            </div>
            <div>
              <dt>切片</dt>
              <dd class="num">{{ kb.chunkCount }}</dd>
            </div>
            <div>
              <dt>Token</dt>
              <dd class="num">{{ kb.totalTokens }}</dd>
            </div>
          </dl>

          <p class="meta">
            <el-icon><FolderOpened /></el-icon>
            {{ kb.embeddingModel }} · {{ kb.embeddingDim }} 维 ·
            {{ kb.chunkStrategy === 'MARKDOWN_HEADING' ? '按标题切分' : '按段落切分' }}
            {{ kb.chunkSize }}/{{ kb.chunkOverlap }}
          </p>

          <!-- 导入完成 ≠ 可以问答：还要向量化 + 建索引（UA-014）。
               不让用户去判断这一步要不要做，导入终态后自动串接，这里只如实显示进度 -->
          <div v-if="indexing === kb.id" class="task">
            <el-progress :percentage="100" :indeterminate="true" :stroke-width="4" />
            <span class="task-text">建立索引中…（完成后即可提问）</span>
          </div>
          <div v-if="importTasks[kb.id]" class="task">
            <el-progress
              :percentage="importTasks[kb.id].progress"
              :status="importTasks[kb.id].status === 'FAILED' ? 'exception' : undefined"
              :stroke-width="4"
            />
            <span class="task-text">
              {{ statusText(kb) }}
              <template v-if="importTasks[kb.id].status === 'RUNNING' || importTasks[kb.id].status === 'DONE'">
                · 新增 {{ importTasks[kb.id].createdCount }} / 跳过 {{ importTasks[kb.id].skippedCount }} /
                切片 {{ importTasks[kb.id].totalChunks }}
              </template>
            </span>
          </div>

          <div class="actions">
            <el-button :icon="Document" data-testid="kb-open" @click="router.push(`/kb/${kb.id}`)">浏览与检索</el-button>
            <!-- 主入口是"导入资料"，Vault 只是其中一种来源，收进下拉——
                 不再让用户误以为这个产品只吃得下 Obsidian（UA-004） -->
            <el-button
              type="primary"
              :icon="UploadFilled"
              data-testid="kb-import-files"
              :loading="uploading === kb.id || indexing === kb.id"
              @click="pickImport(kb, FILE_ACCEPT, true, 'files')"
            >
              导入资料
            </el-button>
            <el-dropdown
              trigger="click"
              popper-class="kb-import-more-popper"
              @command="pickImport(kb, '.zip', false, 'vault')"
            >
              <el-button
                :icon="MoreFilled"
                data-testid="kb-import-more"
                :disabled="uploading === kb.id || indexing === kb.id"
                aria-label="更多导入方式"
              />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item>导入 Obsidian Vault（ZIP）</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button link type="danger" class="danger" data-testid="kb-delete" @click="remove(kb)">删除</el-button>
          </div>
        </article>
      </div>
    </div>

    <el-dialog v-model="createVisible" title="新建知识库" width="440px">
      <el-form
        ref="createFormRef"
        :model="createForm"
        data-testid="kb-create-form"
        label-position="top"
      >
        <el-form-item label="名称" required>
          <el-input
            v-model="createForm.name"
            data-testid="kb-create-name"
            placeholder="例如 我的 Java 笔记"
            maxlength="128"
          />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="createForm.description"
            type="textarea"
            data-testid="kb-create-desc"
            :rows="2"
            placeholder="可选，便于以后区分"
            maxlength="512"
          />
        </el-form-item>
        <p class="hint">
          嵌入模型与切分参数在此刻冻结（向量维度、切片标准一经确定不再变更），
          因此不需要在这里选择。
        </p>
      </el-form>
      <template #footer>
        <el-button data-testid="kb-create-cancel" @click="createVisible = false">取消</el-button>
        <el-button type="primary" data-testid="kb-create-submit" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 列表卡片的动效（参考 ReactBits 的入场与 SpotlightCard，但按开发者工具的语境收敛）：
 * - 入场：错落淡入上移，每张延迟 40ms。只播一次，且只动 transform/opacity；
 * - 悬停：整体轻微上浮 + 一层跟随卡片的浅色高光。
 * 列表是高频访问的界面（每次回列表都触发），所以入场必须短、不能有持续动画。 */
.page {
  /* 外壳已经提供了四周留白，这里只补页面自己的纵向节奏 */
  padding: var(--space-4) 0 var(--space-8);
}

.card {
  transition:
    transform var(--dur-base) var(--ease-out-expo),
    box-shadow var(--dur-base) var(--ease-out-expo);
}

.card:hover {
  transform: translate3d(0, -2px, 0);
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
  color: var(--text-muted);
  font-size: 13px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: var(--space-4);
}

.card {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: var(--space-5);
  background: var(--surface-raised);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  transition: box-shadow var(--dur-base) var(--ease-out-expo);
}

.card:hover {
  box-shadow: var(--shadow-raised);
}

.title-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.name {
  cursor: pointer;
  font-size: 16px;
}

.name:hover {
  color: var(--accent-600);
}

.desc {
  margin: var(--space-2) 0 0;
  color: var(--text-muted);
  font-size: 13px;
  min-height: 20px;
}

/* 指标不再用上下分隔线（圆润路线里横线越少越软），改成一块浅色圆角底 */
.metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-3);
  margin: 0;
  padding: var(--space-3) var(--space-4);
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
}

.metrics dt {
  color: var(--text-muted);
  font-size: 11.5px;
}

.metrics dd {
  margin: 2px 0 0;
  font-size: 19px;
  font-weight: 600;
  color: var(--text-strong);
  letter-spacing: -0.02em;
}

.meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  color: var(--text-faint);
  font-size: 12.5px;
}

.task {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.task-text {
  color: var(--text-muted);
  font-size: 12px;
}

.actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: auto;
}

.danger {
  margin-left: auto;
}

.empty-hint {
  margin: 0 0 var(--space-4);
  color: var(--text-muted);
  font-size: 13px;
}

.hint {
  margin: 0;
  color: var(--text-muted);
  font-size: 12.5px;
  line-height: 1.6;
}
</style>
