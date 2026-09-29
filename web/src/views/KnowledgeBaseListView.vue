<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus'
import { Document, FolderOpened, Plus, UploadFilled } from '@element-plus/icons-vue'
import {
  createKnowledgeBase,
  getImportTask,
  listKnowledgeBases,
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
  await ElMessageBox.confirm(
    `将删除「${kb.name}」及其 ${kb.docCount} 篇文档、${kb.chunkCount} 个切片。此操作不可撤销。`,
    '确认删除知识库？',
    { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
  )
  const { deleteKnowledgeBase } = await import('@/api/knowledge')
  await deleteKnowledgeBase(kb.id)
  ElMessage.success('已删除')
  await load()
}

function pickFile(kb: KnowledgeBase) {
  uploading.value = kb.id
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.zip'
  input.onchange = async () => {
    const file = input.files?.[0]
    uploading.value = ''
    if (!file) return
    await doUpload(kb, file)
  }
  input.click()
}

async function doUpload(kb: KnowledgeBase, file: File) {
  ElMessage.info(`正在上传 ${file.name}（${(file.size / 1024).toFixed(0)} KB）`)
  const task = await uploadVault(kb.id, file)
  importTasks[kb.id] = task
  // 后端是异步处理（QUEUED → RUNNING → DONE），轮询到终态为止
  await pollTask(kb, task.id)
}

async function pollTask(kb: KnowledgeBase, taskId: string) {
  for (let i = 0; i < 240; i++) {
    await new Promise((resolve) => setTimeout(resolve, 1200))
    const task = await getImportTask(taskId)
    importTasks[kb.id] = task
    if (['DONE', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(task.status)) {
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
        <p class="sub">导入 Obsidian Vault / Markdown 笔记，切分成切片后用于混合检索</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="createVisible = true">新建知识库</el-button>
    </header>

    <div v-loading="loading" class="content">
      <el-empty
        v-if="!loading && items.length === 0"
        description="还没有知识库"
      >
        <p class="empty-hint">先创建一个，然后把 Obsidian Vault 打包成 ZIP 上传即可。</p>
        <el-button type="primary" :icon="Plus" @click="createVisible = true">新建知识库</el-button>
      </el-empty>

      <div v-else class="grid">
        <article
          v-for="(kb, index) in items"
          :key="kb.id"
          class="card fade-up spotlight"
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
            <el-button :icon="Document" @click="router.push(`/kb/${kb.id}`)">浏览与检索</el-button>
            <el-button
              :icon="UploadFilled"
              :loading="uploading === kb.id"
              @click="pickFile(kb)"
            >
              上传 Vault
            </el-button>
            <el-button link type="danger" class="danger" @click="remove(kb)">删除</el-button>
          </div>
        </article>
      </div>
    </div>

    <el-dialog v-model="createVisible" title="新建知识库" width="440px">
      <el-form ref="createFormRef" :model="createForm" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" placeholder="例如 我的 Java 笔记" maxlength="128" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="createForm.description"
            type="textarea"
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
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 列表卡片的动效（参考 ReactBits 的入场与 SpotlightCard，但按开发者工具的语境收敛）：
 * - 入场：错落淡入上移，每张延迟 40ms。只播一次，且只动 transform/opacity；
 * - 悬停：整体轻微上浮 + 一层跟随卡片的浅色高光。
 * 列表是高频访问的界面（每次回列表都触发），所以入场必须短、不能有持续动画。 */
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
  margin: 6px 0 0;
  color: var(--neutral-500);
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
  background: var(--neutral-0);
  border: 1px solid var(--neutral-200);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  transition: box-shadow 150ms ease-out, border-color 150ms ease-out;
}

.card:hover {
  border-color: var(--neutral-300);
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
  margin: 6px 0 0;
  color: var(--neutral-500);
  font-size: 13px;
  min-height: 20px;
}

.metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-3);
  margin: 0;
  padding: var(--space-3) 0;
  border-top: 1px solid var(--neutral-200);
  border-bottom: 1px solid var(--neutral-200);
}

.metrics dt {
  color: var(--neutral-500);
  font-size: 11.5px;
}

.metrics dd {
  margin: 2px 0 0;
  font-size: 19px;
  font-weight: 600;
  color: var(--neutral-900);
  letter-spacing: -0.02em;
}

.meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  color: var(--neutral-500);
  font-size: 12.5px;
}

.task {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.task-text {
  color: var(--neutral-500);
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
  color: var(--neutral-500);
  font-size: 13px;
}

.hint {
  margin: 0;
  color: var(--neutral-500);
  font-size: 12.5px;
  line-height: 1.6;
}
</style>
