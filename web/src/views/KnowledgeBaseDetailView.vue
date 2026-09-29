<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Search, UploadFilled, MagicStick } from '@element-plus/icons-vue'
import {
  buildIndex,
  embedChunks,
  getImportTask,
  listChunks,
  listDocuments,
  listKnowledgeBases,
  recreateIndex,
  search,
  uploadVault,
  type ChunkView,
  type DocumentSummary,
  type ImportTask,
  type KnowledgeBase,
  type SearchHit
} from '@/api/knowledge'

const route = useRoute()
const kbId = route.params.id as string

const kb = ref<KnowledgeBase | null>(null)
const documents = ref<DocumentSummary[]>([])
const chunksByDoc = reactive<Record<string, ChunkView[]>>({})
const loading = ref(false)
const busy = ref('')

const task = ref<ImportTask | null>(null)

const query = ref('')
const topK = ref(10)
const searching = ref(false)
const hits = ref<SearchHit[]>([])
const searchedFor = ref('')

const maxScore = computed(() => hits.value.reduce((max, hit) => Math.max(max, hit.score), 0) || 1)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const page = await listKnowledgeBases()
    kb.value = page.list.find((item) => item.id === kbId) ?? null
    const docs = await listDocuments(kbId)
    documents.value = docs.list
  } finally {
    loading.value = false
  }
}

async function onExpand(row: DocumentSummary, expanded: DocumentSummary[]) {
  if (!expanded.includes(row) || chunksByDoc[row.id]) return
  chunksByDoc[row.id] = await listChunks(kbId, row.id)
}

async function doSearch() {
  if (!query.value.trim()) {
    ElMessage.warning('请输入检索内容')
    return
  }
  searching.value = true
  try {
    hits.value = await search(kbId, query.value.trim(), topK.value)
    searchedFor.value = query.value.trim()
    if (hits.value.length === 0) {
      ElMessage.info('没有命中任何切片——先确认已「建索引」（BM25 需要 ES 索引）')
    }
  } finally {
    searching.value = false
  }
}

function pickFile() {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.zip'
  input.onchange = async () => {
    const file = input.files?.[0]
    if (!file) return
    const created = await uploadVault(kbId, file)
    task.value = created
    ElMessage.info('已提交导入，处理中…')
    for (let i = 0; i < 240; i++) {
      await new Promise((resolve) => setTimeout(resolve, 1200))
      task.value = await getImportTask(created.id)
      if (['DONE', 'PARTIAL', 'FAILED'].includes(task.value.status)) break
    }
    await load()
  }
  input.click()
}

async function runEmbed() {
  busy.value = 'embed'
  try {
    const result = await embedChunks(kbId)
    if (result.total === 0) {
      ElMessage.info('没有待向量化的切片（可能已全部完成）')
    } else {
      ElMessage.success(
        `向量化完成：处理 ${result.total} 条，调用模型 ${result.embedded} 条，缓存命中 ${result.cacheHits} 条，耗时 ${result.costMillis} ms`
      )
    }
    await load()
  } finally {
    busy.value = ''
  }
}

async function runIndex() {
  busy.value = 'index'
  try {
    const result = await buildIndex(kbId)
    ElMessage.success(
      result.total === 0
        ? '没有待建索引的切片（可能已全部完成）'
        : `建索引完成：写入 ${result.indexed} 条，耗时 ${result.costMillis} ms`
    )
    await load()
  } finally {
    busy.value = ''
  }
}

async function runRecreate() {
  busy.value = 'recreate'
  try {
    await recreateIndex(kbId)
    ElMessage.success('索引已重建（别名已原子切换），切片状态重置为待建索引')
    await load()
  } finally {
    busy.value = ''
  }
}

/** 检索来源标签：BM25 与向量用不同的视觉重量区分，但只用一个强调色系。 */
function sourceLabel(source: string): string {
  if (source === 'vector') return '向量'
  if (source === 'hybrid') return '融合'
  return 'BM25'
}

function pendingChunks(): number {
  return documents.value.reduce(
    (sum, doc) => sum + (chunksByDoc[doc.id]?.filter((c) => c.esStatus !== 'DONE').length ?? 0),
    0
  )
}
</script>

<template>
  <div v-loading="loading" class="page">
    <header class="page-head">
      <div class="head-main">
        <RouterLink to="/" class="back">← 知识库</RouterLink>
        <h1>{{ kb?.name || '知识库' }}</h1>
        <p class="sub">
          {{ kb?.embeddingModel }} · {{ kb?.embeddingDim }} 维 ·
          {{ kb?.chunkStrategy === 'MARKDOWN_HEADING' ? '按标题切分' : '按段落切分' }}
          {{ kb?.chunkSize }}/{{ kb?.chunkOverlap }}
        </p>
      </div>

      <div class="head-actions">
        <el-button :icon="UploadFilled" @click="pickFile">上传 Vault</el-button>
        <el-button :icon="MagicStick" :loading="busy === 'embed'" @click="runEmbed">向量化</el-button>
        <el-button :loading="busy === 'index'" @click="runIndex">建索引</el-button>
        <el-button :icon="Refresh" :loading="busy === 'recreate'" @click="runRecreate">
          重建索引
        </el-button>
      </div>
    </header>

    <dl class="metrics">
      <div>
        <dt>文档</dt>
        <dd class="num">{{ kb?.docCount ?? 0 }}</dd>
      </div>
      <div>
        <dt>切片</dt>
        <dd class="num">{{ kb?.chunkCount ?? 0 }}</dd>
      </div>
      <div>
        <dt>Token 估算</dt>
        <dd class="num">{{ kb?.totalTokens ?? 0 }}</dd>
      </div>
      <div v-if="task">
        <dt>最近导入</dt>
        <dd class="num">
          {{ task.status }} · {{ task.createdCount }} 新增 / {{ task.skippedCount }} 跳过
        </dd>
      </div>
    </dl>

    <div class="columns">
      <!-- 左：文档与切片 -->
      <section class="panel">
        <div class="panel-head">
          <h2>文档与切片</h2>
          <span class="count">{{ documents.length }} 篇</span>
        </div>

        <el-empty v-if="documents.length === 0" description="还没有文档">
          <el-button type="primary" :icon="UploadFilled" @click="pickFile">上传 Vault</el-button>
        </el-empty>

        <el-table v-else :data="documents" size="small" row-key="id" @expand-change="onExpand">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="chunks">
                <div v-if="!chunksByDoc[row.id]" class="chunk-loading">加载切片…</div>
                <article v-for="chunk in chunksByDoc[row.id]" :key="chunk.id" class="chunk">
                  <header>
                    <span class="idx num">#{{ chunk.chunkIndex }}</span>
                    <span class="path">{{ chunk.headingPath || '（无标题）' }}</span>
                    <el-tag size="small" effect="plain" type="info">{{ chunk.chunkType }}</el-tag>
                    <span class="tokens num">{{ chunk.tokenCount }} tok</span>
                    <span class="status">
                      <span :class="['pip', chunk.vectorStatus === 'DONE' ? 'on' : 'off']" />
                      向量 {{ chunk.vectorStatus }}
                      <span :class="['pip', chunk.esStatus === 'DONE' ? 'on' : 'off']" />
                      索引 {{ chunk.esStatus }}
                    </span>
                  </header>
                  <p class="chunk-text">{{ chunk.content }}</p>
                </article>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="sourcePath" label="路径" min-width="200" show-overflow-tooltip />
          <el-table-column prop="title" label="标题" width="150" show-overflow-tooltip />
          <el-table-column label="标签" width="150">
            <template #default="{ row }">
              <el-tag v-for="tag in row.tags" :key="tag" size="small" effect="plain" class="tag">
                {{ tag }}
              </el-tag>
              <span v-if="row.tags.length === 0" class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="切片" width="64" align="right">
            <template #default="{ row }"><span class="num">{{ row.chunkCount }}</span></template>
          </el-table-column>
          <el-table-column label="版本" width="64" align="right">
            <template #default="{ row }"><span class="num">v{{ row.version }}</span></template>
          </el-table-column>
        </el-table>
      </section>

      <!-- 右：检索面板（本页的签名细节：命中的"来源 + 得分"并排可见） -->
      <aside class="panel search-panel">
        <div class="panel-head">
          <h2>检索</h2>
          <span class="count">BM25 · 已接入 IK 分词</span>
        </div>

        <div class="search-box">
          <el-input
            v-model="query"
            placeholder="例如：缓存击穿怎么解决"
            clearable
            @keyup.enter="doSearch"
          />
          <el-button type="primary" :icon="Search" :loading="searching" @click="doSearch">
            检索
          </el-button>
        </div>

        <div class="topk">
          <span>返回条数</span>
          <el-radio-group v-model="topK" size="small">
            <el-radio-button :value="5">5</el-radio-button>
            <el-radio-button :value="10">10</el-radio-button>
            <el-radio-button :value="20">20</el-radio-button>
          </el-radio-group>
        </div>

        <p v-if="!searchedFor" class="hint">
          检索走 Elasticsearch 的 BM25（IK 中文分词），标题权重高于正文。
          向量检索与 RRF 融合是下一步。
        </p>

        <div v-else-if="hits.length === 0" class="hint">「{{ searchedFor }}」没有命中。</div>

        <ol v-else class="hits">
          <li v-for="hit in hits" :key="hit.chunkId" class="hit">
            <header>
              <el-tag size="small" :type="hit.source === 'vector' ? 'success' : 'info'" effect="plain">
                {{ sourceLabel(hit.source) }}
              </el-tag>
              <span class="score num">{{ hit.score.toFixed(2) }}</span>
            </header>
            <div class="bar">
              <span :style="{ width: `${Math.max(4, (hit.score / maxScore) * 100)}%` }" />
            </div>
            <p class="where">
              {{ hit.documentTitle || '未命名文档' }}
              <template v-if="hit.headingPath"> › {{ hit.headingPath }}</template>
            </p>
            <p class="snippet">{{ hit.content }}</p>
          </li>
        </ol>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-5);
  margin-bottom: var(--space-5);
}

.back {
  display: inline-block;
  margin-bottom: 6px;
  color: var(--neutral-500);
  font-size: 12.5px;
}

.back:hover {
  color: var(--accent-600);
}

.sub {
  margin: 6px 0 0;
  color: var(--neutral-500);
  font-size: 13px;
}

.head-actions {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.metrics {
  display: flex;
  gap: var(--space-7);
  margin: 0 0 var(--space-5);
  padding: var(--space-4) 0;
  border-top: 1px solid var(--neutral-200);
  border-bottom: 1px solid var(--neutral-200);
}

.metrics dt {
  color: var(--neutral-500);
  font-size: 11.5px;
}

.metrics dd {
  margin: 2px 0 0;
  font-size: 17px;
  font-weight: 600;
  color: var(--neutral-900);
}

.columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 400px;
  gap: var(--space-4);
  align-items: start;
}

@media (max-width: 1280px) {
  .columns {
    grid-template-columns: 1fr;
  }
}

.panel {
  background: var(--neutral-0);
  border: 1px solid var(--neutral-200);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  padding: var(--space-4);
}

.panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: var(--space-3);
}

.count {
  color: var(--neutral-500);
  font-size: 12px;
}

.chunks {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3) var(--space-4);
}

.chunk {
  border-left: 2px solid var(--neutral-200);
  padding-left: var(--space-3);
}

.chunk header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--neutral-500);
}

.idx {
  font-family: var(--font-mono);
  color: var(--neutral-400);
}

.path {
  color: var(--neutral-700);
  font-weight: 500;
}

.status {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 4px;
}

.pip {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: inline-block;
}

.pip.on {
  background: var(--ok);
}

.pip.off {
  background: var(--neutral-300);
}

.chunk-text {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--neutral-700);
  white-space: pre-wrap;
}

.chunk-loading,
.muted,
.hint {
  color: var(--neutral-500);
  font-size: 12.5px;
  line-height: 1.7;
}

.hint {
  margin: var(--space-3) 0 0;
}

.tag {
  margin-right: 4px;
}

.search-box {
  display: flex;
  gap: var(--space-2);
}

.topk {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-3);
  color: var(--neutral-500);
  font-size: 12.5px;
}

.hits {
  list-style: none;
  margin: var(--space-4) 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.hit {
  padding: var(--space-3);
  background: var(--neutral-50);
  border: 1px solid var(--neutral-200);
  border-radius: var(--radius-input);
}

.hit header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.score {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--neutral-600);
}

.bar {
  height: 3px;
  margin: 6px 0 8px;
  background: var(--neutral-200);
  border-radius: 2px;
  overflow: hidden;
}

.bar span {
  display: block;
  height: 100%;
  background: var(--accent-400);
  transition: width 200ms ease-out;
}

.where {
  margin: 0;
  font-size: 12px;
  color: var(--neutral-600);
}

.snippet {
  margin: 6px 0 0;
  font-size: 12.5px;
  color: var(--neutral-700);
  display: -webkit-box;
  -webkit-line-clamp: 4;
  line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
