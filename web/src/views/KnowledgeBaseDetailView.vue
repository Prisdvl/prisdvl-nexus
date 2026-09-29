<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { MagicStick, Refresh, Search, UploadFilled } from '@element-plus/icons-vue'
import {
  askQuestion,
  askQuestionStream,
  buildIndex,
  embedChunks,
  getDocument,
  getDocumentLinks,
  getImportTask,
  getQaHistory,
  listChunks,
  listDocuments,
  listKnowledgeBases,
  recreateIndex,
  search,
  uploadVault,
  type ChunkView,
  type DocumentDetail,
  type DocumentLinks,
  type DocumentSummary,
  type ImportTask,
  type KnowledgeBase,
  type RagAnswer,
  type SearchHit,
  type SearchMode
} from '@/api/knowledge'
import { applyHeadingAnchors, headingAnchor, highlightWithin, renderMarkdown } from '@/utils/markdown'

const route = useRoute()
const kbId = route.params.id as string

const kb = ref<KnowledgeBase | null>(null)
const documents = ref<DocumentSummary[]>([])
const loading = ref(false)
const busy = ref('')
const task = ref<ImportTask | null>(null)

const docFilter = ref('')
const activeTab = ref<'content' | 'chunks' | 'search' | 'ask'>('content')

const selectedId = ref<string>('')
const detail = ref<DocumentDetail | null>(null)
const detailLoading = ref(false)
const chunks = ref<ChunkView[]>([])
const links = ref<DocumentLinks | null>(null)
const articleRef = ref<HTMLElement | null>(null)
const renderedHtml = ref('')

const mode = ref<SearchMode>('hybrid')
const query = ref('')
const topK = ref(10)
const searching = ref(false)
const hits = ref<SearchHit[]>([])
const searchedFor = ref('')

// 问答（RAG）
const question = ref('')
const asking = ref(false)
const ragAnswer = ref<RagAnswer | null>(null)
const answerHtml = ref('')
const answerRef = ref<HTMLElement | null>(null)
/** 本次会话内的问答轮次（用于追问时带上上下文） */
const qaTurns = ref<Array<{ question: string; answer: string }>>([])
/** 服务端留档的历史记录 */
const qaHistory = ref<RagAnswer[]>([])
/** 流式开关：默认开——用户立刻看到字，而不是对着白屏等半分钟到三分钟 */
const useStream = ref(true)
/** 流式进行中的原文。先纯文本、结束后再渲染 Markdown：每来一个字就重新渲染 Markdown 太贵 */
const streamedText = ref('')
const streamingNow = ref(false)

const filteredDocuments = computed(() => {
  const keyword = docFilter.value.trim().toLowerCase()
  if (!keyword) {
    return documents.value
  }
  return documents.value.filter(
    (doc) =>
      doc.sourcePath.toLowerCase().includes(keyword) ||
      (doc.title ?? '').toLowerCase().includes(keyword) ||
      doc.tags.some((tag) => tag.toLowerCase().includes(keyword))
  )
})

const maxScore = computed(() => hits.value.reduce((max, hit) => Math.max(max, hit.score), 0) || 1)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const page = await listKnowledgeBases()
    kb.value = page.list.find((item) => item.id === kbId) ?? null
    const docs = await listDocuments(kbId, 1, 200)
    documents.value = docs.list
    await loadQaHistory()
    if (!selectedId.value && docs.list.length > 0) {
      await selectDocument(docs.list[0].id)
    }
  } finally {
    loading.value = false
  }
}

async function selectDocument(documentId: string) {
  if (selectedId.value === documentId && detail.value) {
    activeTab.value = activeTab.value === 'content' ? 'content' : activeTab.value
    return
  }
  selectedId.value = documentId
  detailLoading.value = true
  try {
    const [doc, chunkList, linkList] = await Promise.all([
      getDocument(kbId, documentId),
      listChunks(kbId, documentId),
      getDocumentLinks(kbId, documentId)
    ])
    detail.value = doc
    chunks.value = chunkList
    links.value = linkList
    renderedHtml.value = renderMarkdown(doc.content)
    await nextTick()
    applyHeadingAnchors(articleRef.value)
    highlightWithin(articleRef.value)
  } finally {
    detailLoading.value = false
  }
}

/** 大纲跳转：滚动到对应标题（id 由渲染后按顺序赋上，与大纲序号一致）。 */
function scrollToHeading(index: number) {
  const target = articleRef.value?.querySelector(`#${headingAnchor(index)}`)
  target?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function doSearch() {
  if (!query.value.trim()) {
    ElMessage.warning('请输入检索内容')
    return
  }
  searching.value = true
  try {
    hits.value = await search(kbId, query.value.trim(), topK.value, mode.value)
    searchedFor.value = query.value.trim()
    activeTab.value = 'search'
    if (hits.value.length === 0) {
      ElMessage.info('没有命中——确认已「向量化」与「建索引」')
    }
  } finally {
    searching.value = false
  }
}

/** 点检索结果 → 定位到对应文档与标题（引用定位的第一步）。 */
async function openHit(hit: SearchHit) {
  await jumpTo(hit.documentId, hit.headingPath)
}

/** 点引用 → 同样定位到原文（引用必须能落到原文，否则只是装饰）。 */
async function openCitation(citation: RagAnswer['citations'][number]) {
  await jumpTo(citation.documentId, citation.headingPath)
}

async function jumpTo(documentId: string, headingPath: string | null) {
  await selectDocument(documentId)
  activeTab.value = 'content'
  if (headingPath) {
    const leaf = headingPath.split('>').pop()?.trim()
    const index = detail.value?.headings.findIndex((heading) => heading.text === leaf) ?? -1
    if (index >= 0) {
      await nextTick()
      scrollToHeading(index)
    }
  }
}

/** RAG 问答：检索 → 重排 → 组装 → 生成，返回带 [n] 引用的回答。 */
async function doAsk() {
  if (!question.value.trim()) {
    ElMessage.warning('请输入问题')
    return
  }
  asking.value = true
  const asked = question.value.trim()
  // 带上最近两轮问答：让"那它有什么缺点？"这类省略主语的追问能被理解
  const history = qaTurns.value.slice(-2).map((turn) => ({
    question: turn.question,
    answer: turn.answer
  }))
  try {
    activeTab.value = 'ask'
    if (useStream.value) {
      await askStreaming(asked, history)
    } else {
      const answer = await askQuestion(kbId, asked, topK.value, history)
      ragAnswer.value = answer
      answerHtml.value = renderMarkdown(answer.answer)
      await nextTick()
      highlightWithin(answerRef.value)
    }
    qaTurns.value.push({ question: asked, answer: ragAnswer.value?.answer ?? '' })
    await loadQaHistory()
  } finally {
    asking.value = false
    streamingNow.value = false
  }
}

/** 流式路径：逐字渲染，结束后用完整事件里的引用构造与同步路径一致的对象（复用同一套引用 UI）。 */
async function askStreaming(asked: string, history: Array<{ question: string; answer: string }>) {
  ragAnswer.value = null
  answerHtml.value = ''
  streamedText.value = ''
  streamingNow.value = true

  let citations: RagAnswer['citations'] = []
  let recordId: string | null = null
  let contextTokens = 0
  let truncated = 0
  let reranked = false

  await askQuestionStream(kbId, asked, topK.value, history, {
    onMeta: (meta) => {
      citations = meta.citations.map((item, index) => ({
        index: index + 1,
        chunkId: item.chunkId,
        documentId: item.documentId,
        documentTitle: item.documentTitle,
        headingPath: item.headingPath,
        snippet: '',
        score: 0,
        referenced: false
      }))
      contextTokens = meta.contextTokens
      truncated = meta.truncated
      reranked = meta.reranked
    },
    onDelta: (text) => {
      streamedText.value += text
    },
    onDone: (done) => {
      if (done.citations?.length) {
        citations = done.citations
      }
      recordId = done.recordId ?? null
      if (done.error) {
        ElMessage.error('生成失败：' + done.error)
      }
    }
  })

  ragAnswer.value = {
    recordId,
    answer: streamedText.value,
    citations,
    contextTokens,
    truncated,
    reranked,
    costMillis: 0
  }
  answerHtml.value = renderMarkdown(streamedText.value)
  await nextTick()
  highlightWithin(answerRef.value)
}

async function loadQaHistory() {
  try {
    qaHistory.value = await getQaHistory(kbId, 8)
  } catch {
    // 历史加载失败不影响当前回答
  }
}

/** 点历史记录 → 把它重新显示出来（含引用）。 */
function restoreHistory(record: RagAnswer) {
  ragAnswer.value = record
  answerHtml.value = renderMarkdown(record.answer)
  nextTick(() => highlightWithin(answerRef.value))
}

function clearTurns() {
  qaTurns.value = []
  ElMessage.info('已清空追问上下文（下一个问题将作为独立问题处理）')
}

/** 点关联项 → 跳到那篇文档（未解析的项不可点，它本来就是"待创建"）。 */
async function openLinked(documentId: string | null) {
  if (!documentId) return
  await jumpTo(documentId, null)
}

function linkTypeLabel(type: string): string {
  if (type === 'WIKILINK') return '双链'
  if (type === 'EMBED') return '嵌入'
  return '链接'
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
    selectedId.value = ''
    detail.value = null
    await load()
  }
  input.click()
}

async function runEmbed() {
  busy.value = 'embed'
  try {
    const result = await embedChunks(kbId)
    report(result.total === 0 ? '没有待向量化的切片（可能已全部完成）' : null,
      `向量化完成：处理 ${result.total} 条，调用模型 ${result.embedded} 条，缓存命中 ${result.cacheHits} 条，耗时 ${result.costMillis} ms`)
    await refreshChunks()
  } finally {
    busy.value = ''
  }
}

async function runIndex() {
  busy.value = 'index'
  try {
    const result = await buildIndex(kbId)
    report(result.total === 0 ? '没有待建索引的切片（可能已全部完成）' : null,
      `建索引完成：写入 ${result.indexed} 条，耗时 ${result.costMillis} ms`)
    await refreshChunks()
  } finally {
    busy.value = ''
  }
}

async function runRecreate() {
  busy.value = 'recreate'
  try {
    await recreateIndex(kbId)
    ElMessage.success('索引已重建（别名原子切换），切片状态重置为待建索引')
    await refreshChunks()
  } finally {
    busy.value = ''
  }
}

function report(info: string | null, success: string) {
  if (info) {
    ElMessage.info(info)
  } else {
    ElMessage.success(success)
  }
}

/** 只刷新切片状态（不动正文，避免闪烁）。 */
async function refreshChunks() {
  if (!selectedId.value) return
  chunks.value = await listChunks(kbId, selectedId.value)
}

function sourceLabel(source: string): string {
  if (source === 'vector') return '向量'
  if (source === 'hybrid') return '融合'
  return 'BM25'
}

function sourceType(source: string): 'success' | 'info' | 'warning' {
  if (source === 'vector') return 'success'
  if (source === 'hybrid') return 'warning'
  return 'info'
}

// 切换文档时若正在看切片/检索，保持当前视图（不强制跳回正文）
watch(activeTab, () => {
  if (activeTab.value === 'content') {
    nextTick(() => highlightWithin(articleRef.value))
  }
})
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
        <el-button :icon="Refresh" :loading="busy === 'recreate'" @click="runRecreate">重建索引</el-button>
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
        <dd class="num">{{ task.status }} · {{ task.createdCount }} 新增 / {{ task.skippedCount }} 跳过</dd>
      </div>
    </dl>

    <div class="workspace">
      <!-- 左：文档列表 -->
      <aside class="pane doc-list">
        <div class="pane-head">
          <h2>文档</h2>
          <span class="count">{{ filteredDocuments.length }}</span>
        </div>
        <el-input v-model="docFilter" size="small" placeholder="按路径 / 标题 / 标签过滤" clearable />
        <ul class="docs">
          <li
            v-for="doc in filteredDocuments"
            :key="doc.id"
            :class="{ active: doc.id === selectedId }"
            @click="selectDocument(doc.id)"
          >
            <span class="doc-title">{{ doc.title || doc.fileName }}</span>
            <span class="doc-path">{{ doc.sourcePath }}</span>
            <span class="doc-meta num">{{ doc.chunkCount }} 切片 · v{{ doc.version }}</span>
          </li>
        </ul>
        <p v-if="filteredDocuments.length === 0" class="empty">没有匹配的文档</p>
      </aside>

      <!-- 右：正文 / 切片 / 检索 -->
      <section class="pane content">
        <el-tabs v-model="activeTab">
          <el-tab-pane label="正文" name="content">
            <div v-loading="detailLoading" class="reader">
              <nav v-if="detail?.headings?.length" class="outline">
                <div class="outline-head">大纲</div>
                <ul>
                  <li
                    v-for="(heading, index) in detail.headings"
                    :key="index"
                    :style="{ paddingLeft: `${(heading.level - 1) * 12}px` }"
                    @click="scrollToHeading(index)"
                  >
                    {{ heading.text }}
                  </li>
                </ul>
              </nav>

              <div class="article-wrap">
                <div v-if="!detail" class="empty">左侧选择一篇文档</div>
                <template v-else>
                  <div class="article-head">
                    <h2>{{ detail.title || detail.fileName }}</h2>
                    <div class="article-meta">
                      <span class="num">{{ detail.sourcePath }}</span>
                      <span class="num">{{ detail.chunkCount }} 切片</span>
                      <span class="num">{{ detail.tokenCount }} tok</span>
                      <span class="num" v-if="detail.fileSizeKb">{{ detail.fileSizeKb }} KB</span>
                      <el-tag v-for="tag in detail.tags" :key="tag" size="small" effect="plain">{{ tag }}</el-tag>
                    </div>
                  </div>
                  <div v-if="detail.content === null" class="empty">
                    原始文件已丢失，无法展示正文（切片仍可用于检索）。
                  </div>
                  <!-- eslint-disable-next-line vue/no-v-html -- 内容经 marked + DOMPurify 处理 -->
                  <article v-else ref="articleRef" class="markdown" v-html="renderedHtml" />

                  <!-- 关联（Phase 4）：出链 / 反向链接 / 待创建清单 -->
                  <section v-if="links && (links.outlinks.length || links.backlinks.length)" class="links">
                    <div class="links-head">
                      <h3>关联</h3>
                      <span class="num">
                        {{ links.outlinks.length }} 出链 · {{ links.backlinks.length }} 反向链接
                        <template v-if="links.dangling"> · {{ links.dangling }} 待创建</template>
                      </span>
                    </div>

                    <div v-if="links.outlinks.length" class="link-group">
                      <div class="outline-head">出链（本文档指向）</div>
                      <ul>
                        <li
                          v-for="link in links.outlinks"
                          :key="link.linkId"
                          :class="{
                            unresolved: !link.external && !link.resolvedDocumentId,
                            external: link.external
                          }"
                          @click="openLinked(link.resolvedDocumentId)"
                        >
                          <el-tag size="small" effect="plain" type="info">
                            {{ linkTypeLabel(link.linkType) }}
                          </el-tag>
                          <span class="link-name">{{ link.resolvedTitle || link.target }}</span>
                          <el-tag v-if="link.external" size="small" effect="plain">外链</el-tag>
                          <el-tag v-else-if="!link.resolvedDocumentId" size="small" effect="plain" type="warning">
                            待创建
                          </el-tag>
                        </li>
                      </ul>
                    </div>

                    <div v-if="links.backlinks.length" class="link-group">
                      <div class="outline-head">反向链接（谁引用了本文档）</div>
                      <ul>
                        <li
                          v-for="link in links.backlinks"
                          :key="link.linkId"
                          @click="openLinked(link.resolvedDocumentId)"
                        >
                          <el-tag size="small" effect="plain" type="success">被引用</el-tag>
                          <span class="link-name">{{ link.resolvedTitle || '未知文档' }}</span>
                        </li>
                      </ul>
                    </div>
                  </section>
                </template>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane :label="`切片 (${chunks.length})`" name="chunks">
            <div v-if="chunks.length === 0" class="empty">这篇文档还没有切片</div>
            <div class="chunks">
              <article v-for="chunk in chunks" :key="chunk.id" class="chunk">
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
          </el-tab-pane>

          <el-tab-pane label="检索" name="search">
            <div class="search-head">
              <el-radio-group v-model="mode" size="small">
                <el-radio-button value="hybrid">融合</el-radio-button>
                <el-radio-button value="bm25">BM25</el-radio-button>
                <el-radio-button value="vector">向量</el-radio-button>
              </el-radio-group>
              <el-radio-group v-model="topK" size="small">
                <el-radio-button :value="5">5</el-radio-button>
                <el-radio-button :value="10">10</el-radio-button>
                <el-radio-button :value="20">20</el-radio-button>
              </el-radio-group>
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

            <p class="hint">
              融合模式用 RRF（倒数排名融合）：<b>只看排名不看分数</b>，
              因为 BM25 分数无上界、余弦相似度在 [-1,1]，直接相加等于比较苹果和橘子。
              每条结果都标出它来自哪几路、各排第几。
            </p>

            <div v-if="searchedFor && hits.length === 0" class="empty">「{{ searchedFor }}」没有命中。</div>

            <ol v-else class="hits">
              <li v-for="hit in hits" :key="hit.chunkId" class="hit" @click="openHit(hit)">
                <header>
                  <el-tag size="small" :type="sourceType(hit.source)" effect="plain">
                    {{ sourceLabel(hit.source) }}
                  </el-tag>
                  <span v-for="label in hit.matchedBy" :key="label" class="matched num">{{ label }}</span>
                  <span class="score num">RRF {{ hit.score.toFixed(4) }}</span>
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
          </el-tab-pane>
          <el-tab-pane label="问答" name="ask">
            <div class="search-box">
              <el-input
                v-model="question"
                placeholder="例如：缓存击穿有哪些解决方案？"
                clearable
                @keyup.enter="doAsk"
              />
              <el-button type="primary" :icon="MagicStick" :loading="asking" @click="doAsk">
                提问
              </el-button>
              <el-switch v-model="useStream" size="small" active-text="流式" inline-prompt />
            </div>
            <p class="hint">
              链路：混合检索（RRF）→ LLM 重排 → 上下文组装（Token 预算 + 去重 + 编号）→ 生成。
              流式模式下几百毫秒内就能看到第一个字（非流式要等整段生成完）。
              回答里的 [n] 对应下方引用，点引用可跳到原文小节。
            </p>

            <div v-if="ragAnswer || streamingNow" class="answer-block">
              <div class="answer-meta">
                <span class="num" v-if="streamingNow">生成中…</span>
                <template v-else>
                  <span class="num">资料 {{ ragAnswer?.citations.length ?? 0 }} 段 / {{ ragAnswer?.contextTokens }} tok</span>
                  <span class="num" v-if="ragAnswer?.truncated">被预算截断 {{ ragAnswer?.truncated }} 段</span>
                  <el-tag size="small" effect="plain" :type="ragAnswer?.reranked ? 'success' : 'info'">
                    {{ ragAnswer?.reranked ? '已重排' : '未重排（保持召回顺序）' }}
                  </el-tag>
                </template>
              </div>
              <!-- 流式进行中先显示纯文本（逐字重渲染 Markdown 太贵），结束后再渲染 -->
              <article v-if="streamingNow" class="markdown answer stream-text">
                {{ streamedText }}<span class="caret" />
              </article>
              <!-- eslint-disable-next-line vue/no-v-html -- 内容经 marked + DOMPurify 处理 -->
              <article v-else ref="answerRef" class="markdown answer" v-html="answerHtml" />

              <div v-if="ragAnswer?.citations?.length" class="citations">
                <div class="outline-head">引用</div>
                <ol>
                  <li v-for="citation in ragAnswer.citations" :key="citation.chunkId" @click="openCitation(citation)">
                    <span class="cite-index num">[{{ citation.index }}]</span>
                    <span class="cite-where">
                      {{ citation.documentTitle || '未命名文档' }}
                      <template v-if="citation.headingPath"> › {{ citation.headingPath }}</template>
                    </span>
                    <el-tag v-if="citation.referenced" size="small" effect="plain" type="success">已引用</el-tag>
                    <span class="cite-snippet">{{ citation.snippet }}</span>
                  </li>
                </ol>
              </div>

              <div class="ask-actions">
                <span class="hint">
                  追问时会带上最近 2 轮问答作为上下文（{{ qaTurns.length }} 轮）
                </span>
                <el-button v-if="qaTurns.length" link size="small" @click="clearTurns">清空上下文</el-button>
              </div>
            </div>

            <!-- 留档历史：回看"当时依据了哪些资料" -->
            <div v-if="qaHistory.length" class="qa-history">
              <div class="outline-head">问答历史（已留档 {{ qaHistory.length }} 条）</div>
              <ul>
                <li
                  v-for="(record, index) in qaHistory"
                  :key="record.recordId ?? index"
                  @click="restoreHistory(record)"
                >
                  <span class="cite-where">{{ record.answer.slice(0, 48) }}…</span>
                  <span class="num">{{ record.citations.length }} 引用 · {{ record.costMillis }} ms</span>
                </li>
              </ul>
            </div>
          </el-tab-pane>
        </el-tabs>
      </section>
    </div>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-5);
  margin-bottom: var(--space-4);
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
  margin: 0 0 var(--space-4);
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
  font-size: 16px;
  font-weight: 600;
  color: var(--neutral-900);
}

/* 工作区：左列表固定宽，右侧内容自适应 */
.workspace {
  display: grid;
  grid-template-columns: 288px minmax(0, 1fr);
  gap: var(--space-4);
  align-items: start;
}

@media (max-width: 1100px) {
  .workspace {
    grid-template-columns: 1fr;
  }
}

.pane {
  background: var(--neutral-0);
  border: 1px solid var(--neutral-200);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  padding: var(--space-4);
}

.pane-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: var(--space-3);
}

.count {
  color: var(--neutral-500);
  font-size: 12px;
}

.docs {
  list-style: none;
  margin: var(--space-3) 0 0;
  padding: 0;
  max-height: 620px;
  overflow: auto;
}

.docs li {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border-radius: var(--radius-input);
  cursor: pointer;
  transition: background-color 120ms ease-out;
}

.docs li:hover {
  background: var(--neutral-100);
}

.docs li.active {
  background: var(--accent-50);
  box-shadow: inset 2px 0 0 var(--accent-500);
}

.doc-title {
  font-size: 13.5px;
  font-weight: 500;
  color: var(--neutral-800);
}

.doc-path,
.doc-meta {
  font-size: 11.5px;
  color: var(--neutral-500);
}

.content {
  min-width: 0;
}

/* 正文：大纲 + 文章 */
.reader {
  display: grid;
  grid-template-columns: 190px minmax(0, 1fr);
  gap: var(--space-5);
  align-items: start;
}

@media (max-width: 900px) {
  .reader {
    grid-template-columns: 1fr;
  }
}

.outline {
  position: sticky;
  top: var(--space-4);
  border-right: 1px solid var(--neutral-200);
  padding-right: var(--space-3);
  max-height: 560px;
  overflow: auto;
}

.outline-head {
  font-size: 11px;
  letter-spacing: 0.04em;
  color: var(--neutral-400);
  margin-bottom: var(--space-2);
}

.outline ul {
  list-style: none;
  margin: 0;
  padding: 0;
}

.outline li {
  padding: 4px 0;
  font-size: 12.5px;
  color: var(--neutral-600);
  cursor: pointer;
  border-radius: 4px;
  line-height: 1.45;
}

.outline li:hover {
  color: var(--accent-600);
  background: var(--accent-50);
}

.article-head h2 {
  font-size: 19px;
}

.article-meta {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
  margin: 8px 0 var(--space-4);
  padding-bottom: var(--space-3);
  border-bottom: 1px solid var(--neutral-200);
  color: var(--neutral-500);
  font-size: 12px;
}

/* 渲染后的 Markdown 样式：只影响 v-html 内容，用 :deep 穿透作用域 */
.markdown {
  max-width: 76ch;
  font-size: 14px;
  line-height: 1.75;
  color: var(--neutral-800);
}

.markdown :deep(h1),
.markdown :deep(h2),
.markdown :deep(h3) {
  margin: 1.6em 0 0.5em;
  scroll-margin-top: 16px;
}

.markdown :deep(h1) {
  font-size: 21px;
}

.markdown :deep(h2) {
  font-size: 17px;
}

.markdown :deep(h3) {
  font-size: 15px;
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
  background: var(--neutral-100);
  border-radius: 4px;
  padding: 1px 5px;
}

.markdown :deep(pre) {
  background: var(--neutral-900);
  border-radius: var(--radius-input);
  padding: var(--space-4);
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
  border-left: 2px solid var(--accent-400);
  color: var(--neutral-600);
}

.markdown :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 0.8em 0;
  font-size: 13px;
}

.markdown :deep(th),
.markdown :deep(td) {
  border: 1px solid var(--neutral-200);
  padding: 6px 10px;
  text-align: left;
}

.markdown :deep(th) {
  background: var(--neutral-100);
  font-weight: 600;
}

.markdown :deep(a) {
  color: var(--accent-600);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.markdown :deep(img) {
  max-width: 100%;
  border-radius: var(--radius-input);
}

.chunks {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
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

.tokens {
  font-size: 11.5px;
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
  max-width: 76ch;
}

.search-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
  flex-wrap: wrap;
}

.search-box {
  display: flex;
  gap: var(--space-2);
  max-width: 560px;
}

.hint,
.empty {
  color: var(--neutral-500);
  font-size: 12.5px;
  line-height: 1.7;
}

.hint {
  margin: var(--space-3) 0 0;
  max-width: 76ch;
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
  cursor: pointer;
  transition: border-color 120ms ease-out, box-shadow 120ms ease-out;
}

.hit:hover {
  border-color: var(--accent-400);
  box-shadow: var(--shadow-card);
}

.hit header {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.matched {
  font-size: 11px;
  color: var(--neutral-500);
  background: var(--neutral-100);
  border-radius: 4px;
  padding: 1px 6px;
}

.score {
  margin-left: auto;
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

/* 关联面板：放在正文下方（阅读完正文再看"它和别的笔记有什么关系"最自然） */
.links {
  margin-top: var(--space-6);
  padding-top: var(--space-4);
  border-top: 1px solid var(--neutral-200);
  max-width: 76ch;
}

.links-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-3);
}

.links-head h3 {
  font-size: 15px;
}

.links-head .num {
  color: var(--neutral-500);
  font-size: 12px;
}

.link-group {
  margin-top: var(--space-4);
}

.link-group ul {
  list-style: none;
  margin: var(--space-2) 0 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.link-group li {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border: 1px solid var(--neutral-200);
  border-radius: 999px;
  font-size: 12.5px;
  background: var(--neutral-0);
  transition: border-color 120ms ease-out, background-color 120ms ease-out;
}

.link-group li:not(.unresolved):not(.external) {
  cursor: pointer;
}

.link-group li:not(.unresolved):not(.external):hover {
  border-color: var(--accent-400);
  background: var(--accent-50);
}

/* 未解析的引用是"待创建笔记"：虚线 + 弱化，且不可点（点了也没有目标） */
.link-group li.unresolved {
  border-style: dashed;
  color: var(--neutral-500);
}

/* 外链不是内部关联，点击交给浏览器新开标签 */
.link-group li.external .link-name {
  color: var(--accent-600);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.link-name {
  color: var(--neutral-700);
}

/* 问答块 */
.answer-block {
  margin-top: var(--space-4);
}

.answer-meta {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
  color: var(--neutral-500);
  font-size: 12px;
  margin-bottom: var(--space-2);
}

.answer {
  padding: var(--space-4);
  background: var(--neutral-50);
  border: 1px solid var(--neutral-200);
  border-left: 2px solid var(--accent-400);
  border-radius: var(--radius-input);
  max-width: 80ch;
}

/* 流式进行中：保留换行按原文显示，并在末尾给一个光标，让"还在生成"可见 */
.stream-text {
  white-space: pre-wrap;
}

.caret {
  display: inline-block;
  width: 7px;
  height: 1em;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: var(--accent-500);
  animation: caret-blink 1s steps(2) infinite;
}

@keyframes caret-blink {
  50% {
    opacity: 0;
  }
}

.citations {
  margin-top: var(--space-4);
}

.citations ol {
  list-style: none;
  margin: var(--space-2) 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.citations li {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr) auto;
  gap: var(--space-2);
  align-items: baseline;
  padding: var(--space-2) var(--space-3);
  background: var(--neutral-0);
  border: 1px solid var(--neutral-200);
  border-radius: var(--radius-input);
  cursor: pointer;
  transition: border-color 120ms ease-out;
}

.citations li:hover {
  border-color: var(--accent-400);
}

.ask-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-3);
}

.qa-history {
  margin-top: var(--space-5);
  padding-top: var(--space-4);
  border-top: 1px solid var(--neutral-200);
}

.qa-history ul {
  list-style: none;
  margin: var(--space-2) 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.qa-history li {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-3);
  padding: 6px 10px;
  border-radius: var(--radius-input);
  cursor: pointer;
  font-size: 12.5px;
  color: var(--neutral-600);
}

.qa-history li:hover {
  background: var(--accent-50);
  color: var(--accent-600);
}

.cite-index {
  font-family: var(--font-mono);
  color: var(--accent-600);
  font-size: 12px;
}

.cite-where {
  font-size: 12.5px;
  color: var(--neutral-700);
}

.cite-snippet {
  grid-column: 2 / -1;
  font-size: 12px;
  color: var(--neutral-500);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
