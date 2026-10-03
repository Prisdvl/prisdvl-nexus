/**
 * 多知识库真实验收：单库 / 双库 / 三库 / 部分命中 / **一个库真的失败**（Phase 5.7 §九、§十）。
 *
 * `verify-multi-kb.mjs` 已经钉住了"两个库、一份独有事实、跨库回答 + 越权 404"。
 * 它没有回答的三件事，正是这一期要的：
 *   ① 库数从 1 → 2 → 3 时，检索覆盖范围与答案来源是否逐级扩大（不是"绑了 3 个只用 1 个"）；
 *   ② 多库里**只有部分库命中**时，`empty` / `count` 的语义还是不是"关于内容的断言"；
 *   ③ 一个库**真的失败**时：A ✅ / B ❌ / C ✅——失败被隔离、其余库照常贡献、
 *      失败被一路可见（检索事件 `failedKnowledgeBases` + 计数 `retrieval.kb.failed` + 界面文案）。
 *
 * 【③ 的失败是真实故障，不是 mock】
 * 多库的检索依赖（ES 集群、pgvector 表、嵌入客户端）在这个架构里是**全局共享**的：
 * 停掉 Elasticsearch 会让**所有**库一起失败（那条路径由 `verify-retrieval-outage` 的手工
 * 演练覆盖，见 docs/deployment.md）。因此"只让一个库失败"必须落在这个库**自己的数据**上。
 * 本脚本用的是真实索引里的一个真实坏文档：往真实 Elasticsearch 里写一条
 * `_id` 非数字、但 `knowledge_base_id` 指向 KB-B 的文档。真实检索代码在把命中
 *
 * ⚠️ 写入位置是 **KB-B 自己的别名**（`<基名>_<库 id>`），不是全局基名：
 * Phase 5.9 起索引按知识库隔离（`ElasticsearchChunkIndex.aliasFor`），
 * 全局那个基名已经没有读方了（见下面 `ES_ALIAS_BASE` 处的说明）。
 * 下面这段的其余部分（坏在 `_id` 上、只在 B 的扇出里炸）不受影响。
 * 映射成 `ChunkSearchHit` 时会 `Long.valueOf(hit.id())` 抛 NumberFormatException——
 * 这个异常只在 KB-B 的那次扇出任务里抛出，A 与 C 不受影响。
 * 它没有替换任何 Bean、没有打桩、没有改写业务代码；注入的是真实基础设施里的真实坏数据。
 *
 * 需要：整栈在跑（`cd deploy && docker compose up -d`），访问日志与后端日志可读。
 * 退出码：0 = 通过；1 = 真实失败。
 */
import { chromium } from 'playwright-core'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const base = process.env.WEB_BASE ?? 'http://localhost:8088'
const es = process.env.ES_BASE ?? 'http://localhost:9200'
/**
 * ES 别名的**基名**（后端配置项 `nexus.search.index-alias`）。
 *
 * ⚠️ Phase 5.9 起索引按知识库隔离：每个库的别名是 `<基名>_<知识库 id>`
 * （见 `ElasticsearchChunkIndex.aliasFor`），**不存在**一个读全部库的全局别名。
 * 因此故障注入必须写进被测库自己的别名。写去全局基名的后果有两种，都不报错：
 *   - 基名不存在 → 而 ES 默认允许自动建索引，于是**凭空造出一个同名物理索引**，
 *     坏文档落进去，B 的检索（读别名）根本看不到它；
 *   - 基名还在（升级前留下的旧索引）→ 坏文档落进一个**谁都不读**的地方。
 * 两种都是"故障没发生"，而断言会以「恰好 1 个库失败（实际 0）」这种
 * **看起来像功能坏了**的方式红——这一条是 2026-10-03 真机跑出来的，见 decisions.md B.51。
 */
const ES_ALIAS_BASE = process.env.ES_INDEX_ALIAS ?? 'nexus_chunk'
const kbAlias = (knowledgeBaseId) => `${ES_ALIAS_BASE}_${knowledgeBaseId}`
const outDir = resolve(here, '../../docs/screenshots')

const sleep = (ms) => new Promise((resolve_) => setTimeout(resolve_, ms))
const failures = []
const check = (ok, message) => {
  if (ok) {
    console.log(`  ✓ ${message}`)
  } else {
    failures.push(message)
    console.log(`  ✗ ${message}`)
  }
}

// ---------- 登录（不写 .demo-token：那是别的脚本的输入，不该被本脚本改掉）----------
const login = await fetch(`${base}/api/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    username: process.env.DEMO_USER ?? 'demo',
    password: process.env.DEMO_PASSWORD ?? 'Demo@12345'
  })
}).then((r) => r.json())
if (!login?.data?.accessToken) {
  throw new Error(`登录失败：${JSON.stringify(login)}`)
}
const token = login.data.accessToken

async function api(method, path, body) {
  const headers = { Authorization: `Bearer ${token}` }
  let payload = body
  if (body && !(body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  }
  const response = await fetch(`${base}${path}`, { method, headers, body: payload })
  const text = await response.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    // 非 JSON 原文留在 text 里
  }
  return { status: response.status, json, text }
}

/** SSE over POST：与 web/src/utils/sse.ts 同一套解析规则（帧可能被 TCP 切开）。 */
async function postSse(path, body, onFrame) {
  const response = await fetch(`${base}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify(body)
  })
  if (!response.ok || !response.body) {
    throw new Error(`流式请求失败（HTTP ${response.status}）：${(await response.text()).slice(0, 300)}`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventName = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() ?? ''
    for (const line of lines) {
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        const raw = line.slice(5).trim()
        if (!raw) continue
        let payload
        try {
          payload = JSON.parse(raw)
        } catch {
          continue
        }
        onFrame(eventName, payload)
      }
    }
  }
}

// ---------- 建库 + 导入 + 向量化 + 建索引，并确认真的能检索到 ----------
async function createKnowledgeBaseWith(name, doc) {
  const created = await api('POST', '/api/knowledge-bases', { name })
  if (created.status !== 200) {
    throw new Error(`建库失败（${name}）：HTTP ${created.status} ${created.text}`)
  }
  const kbId = created.json.data.id

  const form = new FormData()
  form.append('file', new Blob([doc.body], { type: 'text/markdown' }), doc.fileName)
  const uploaded = await api('POST', `/api/knowledge-bases/${kbId}/documents`, form)
  if (uploaded.status !== 200) {
    throw new Error(`上传失败（${name}）：HTTP ${uploaded.status} ${uploaded.text}`)
  }
  const taskId = uploaded.json.data.id
  let task = null
  for (let i = 0; i < 60; i += 1) {
    await sleep(1000)
    task = (await api('GET', `/api/import-tasks/${taskId}`)).json?.data
    if (task && ['DONE', 'PARTIAL', 'FAILED'].includes(task.status)) break
  }
  if (task?.status !== 'DONE') {
    throw new Error(`导入未成功（${name}）：${JSON.stringify(task)}`)
  }

  const workflow = await api('GET', `/api/workflow/run/${kbId}`)
  const steps = workflow.json?.data ?? []
  const bad = steps.filter((step) => step.status !== 'SUCCESS')
  if (workflow.status !== 200 || bad.length) {
    throw new Error(`工作流有步骤未成功（${name}）：${JSON.stringify(bad.length ? bad : workflow.text)}`)
  }

  for (let i = 0; i < 30; i += 1) {
    const hit = await api('GET', `/api/knowledge-bases/${kbId}/search?q=${encodeURIComponent(doc.probe)}&mode=hybrid`)
    if ((hit.json?.data ?? []).length > 0) {
      console.log(`· ${name}（id=${kbId}）：可检索（${hit.json.data.length} 条命中）`)
      return kbId
    }
    await sleep(1000)
  }
  throw new Error(`${name} 建好之后检索不到任何命中——向量/索引没生效，后续断言没有意义`)
}

/** 跑一轮 RAG：建会话（绑 kbIds）→ 流式提问 → 收 retrieval 事件与答案正文。 */
async function ragAsk(kbIds, question) {
  const created = await api('POST', '/api/chat/conversations', {
    title: `多库真实验收 ${Date.now()}`,
    mode: 'RAG',
    kbIds
  })
  if (created.status !== 200) {
    throw new Error(`建会话失败：HTTP ${created.status} ${created.text}`)
  }
  const conversationId = created.json.data.id

  let retrieval = null
  let answer = ''
  let streamError = null
  await postSse('/api/chat/stream', { conversationId, content: question }, (event, data) => {
    if (event === 'retrieval') retrieval = data
    else if (event === 'delta') answer += data.text ?? ''
    else if (event === 'error') streamError = data.message ?? '未知错误'
  })
  if (streamError) {
    throw new Error(`RAG 流返回 error 事件：${streamError}`)
  }
  if (!retrieval) {
    throw new Error('整条流结束了也没收到 retrieval 事件')
  }
  return { conversationId, retrieval, answer }
}

const stamp = Date.now()
const A = { name: `验收A-Java ${stamp}`, doc: {
  fileName: '验收A-Java.md',
  probe: '堆外内存上限',
  body: `# JVM 调优笔记\n\n## 堆外内存\n我们的服务把堆外内存上限固定为 512MB，内部代号 JVM-ALPHA-31。\n超过这个上限时，DirectByteBuffer 的分配会退化成一次 Full GC。\n`
} }
const B = { name: `验收B-SpringAI ${stamp}`, doc: {
  fileName: '验收B-SpringAI.md',
  probe: '重试上限',
  body: `# Spring AI 接入笔记\n\n## 重试\n网关重试上限是 3 次，内部代号 SPRING-BETA-77。\n超过次数后请求直接失败，不做降级。\n`
} }
const C = { name: `验收C-Agent ${stamp}`, doc: {
  fileName: '验收C-Agent.md',
  probe: '最大步数',
  body: `# Agent 循环笔记\n\n## 步数上限\nAgent 循环的最大步数是 12，内部代号 AGENT-GAMMA-19。\n到达上限会强制收尾并给出当前结论。\n`
} }

// 只属于各自库的 token：模型不可能"本来就知道"，只能来自检索
const CODE_A = 'JVM-ALPHA-31'
const CODE_B = 'SPRING-BETA-77'
const CODE_C = 'AGENT-GAMMA-19'

let kbA = null
let kbB = null
let kbC = null
let corruptDocId = null
let browser = null

const kbIdsOf = (retrieval) => (retrieval.hits ?? []).map((hit) => String(hit.knowledgeBaseId))
const names = (list) => list.map((label) => label).join(' | ') || '(无)'

try {
  // ---------- 0. 三个真实知识库，三份内容不同的真实文档 ----------
  console.log('① 建三个真实知识库（Java / Spring AI / Agent）')
  kbA = await createKnowledgeBaseWith(A.name, A.doc)
  kbB = await createKnowledgeBaseWith(B.name, B.doc)
  kbC = await createKnowledgeBaseWith(C.name, C.doc)

  // ---------- 1. 单库检索 ----------
  console.log('\n② 单库检索（只绑 A）')
  {
    const { retrieval, answer } = await ragAsk([kbA], '堆外内存上限是多少？')
    console.log(`   覆盖 ${retrieval.knowledgeBaseIds.length} 个库，命中 ${retrieval.count} 条，失败 ${retrieval.failedKnowledgeBases.length} 个`)
    check(retrieval.knowledgeBaseIds.length === 1, '单库：检索事件只覆盖 1 个库')
    check(retrieval.count > 0 && !retrieval.empty, '单库：检索到内容')
    check(retrieval.failedKnowledgeBases.length === 0, '单库：没有失败的知识库')
    check(kbIdsOf(retrieval).every((id) => id === String(kbA)), '单库：命中全部来自 A')
    check(answer.includes('512') || answer.includes(CODE_A), `单库：回答用上了 A 的独有事实（${CODE_A} / 512MB）`)
  }

  // ---------- 2. 双库检索 ----------
  console.log('\n③ 双库检索（绑 A + C）')
  {
    const { retrieval, answer } = await ragAsk([kbA, kbC],
      '堆外内存上限是多少？Agent 循环的最大步数是多少？')
    const hitKbIds = [...new Set(kbIdsOf(retrieval))]
    console.log(`   覆盖 ${retrieval.knowledgeBaseIds.length} 个库，命中 ${retrieval.count} 条，来自：${names(hitKbIds)}`)
    check(retrieval.knowledgeBaseIds.length === 2, '双库：检索事件覆盖 2 个库')
    check(hitKbIds.includes(String(kbA)) && hitKbIds.includes(String(kbC)), '双库：A 与 C 都贡献了命中')
    check(retrieval.failedKnowledgeBases.length === 0, '双库：没有失败的知识库')
    check(answer.includes(CODE_A) || answer.includes('512'), '双库：回答含 A 的事实')
    check(answer.includes(CODE_C) || answer.includes('12'), '双库：回答含 C 的事实')
  }

  // ---------- 3. 三库检索 ----------
  console.log('\n④ 三库检索（绑 A + B + C）')
  {
    const { retrieval, answer } = await ragAsk([kbA, kbB, kbC],
      '这三个内部代号分别对应什么数值：JVM-ALPHA-31、SPRING-BETA-77、AGENT-GAMMA-19？')
    const hitKbIds = [...new Set(kbIdsOf(retrieval))]
    console.log(`   覆盖 ${retrieval.knowledgeBaseIds.length} 个库，命中 ${retrieval.count} 条，来自：${names(hitKbIds)}`)
    check(retrieval.knowledgeBaseIds.length === 3, '三库：检索事件覆盖 3 个库')
    check(
      [kbA, kbB, kbC].every((kb) => hitKbIds.includes(String(kb))),
      '三库：A / B / C 三个库都贡献了命中（绑了三个库就真的搜了三个）'
    )
    check(retrieval.failedKnowledgeBases.length === 0, '三库：没有失败的知识库')
    check(answer.includes(CODE_A) && answer.includes(CODE_B) && answer.includes(CODE_C),
      '三库：回答同时用上三个库的独有事实')
  }

  // ---------- 4. 多库中只有部分库命中 ----------
  //
  // 要让"只有 A 命中"成立，得同时挡住两路召回：关键词那一路靠"问句里只出现 A 独有的词"
  // （JVM-ALPHA-31 只在 A 的文档里），向量那一路靠"把 B/C 的相似度下限调到 1.0"——
  // 阈值是知识库自己的真实配置（PUT /api/knowledge-bases/{id}），不是打桩。
  // 两路都空，B/C 才是真的"搜了但没命中"，而不是"没搜"。
  // 下限取 1.0 而不是 0.9x：余弦相似度 1.0 意味着两段文本的向量完全同向，
  // 用 0.99 仍可能被"两段无关中文短文本"撞上（真实模型下 0.9+ 的同向分数并不罕见）。
  console.log('\n⑤ 部分命中（绑 A + B + C；把 B/C 的相似度下限临时调到 1.0，再问只有 A 能匹配的词）')
  {
    const originalThresholds = {}
    for (const [label, kbId] of [['B', kbB], ['C', kbC]]) {
      const detail = (await api('GET', `/api/knowledge-bases/${kbId}`)).json?.data
      originalThresholds[label] = detail?.similarityThreshold
      const updated = await api('PUT', `/api/knowledge-bases/${kbId}`, { similarityThreshold: 1.0 })
      if (updated.status !== 200) {
        throw new Error(`调 ${label} 的相似度下限失败：HTTP ${updated.status} ${updated.text}`)
      }
    }
    console.log(`   已把 B/C 的相似度下限调到 1.0（原值 ${JSON.stringify(originalThresholds)}）`)

    try {
      const { retrieval, answer } = await ragAsk([kbA, kbB, kbC], 'JVM-ALPHA-31')
      const hitKbIds = [...new Set(kbIdsOf(retrieval))]
      console.log(`   覆盖 ${retrieval.knowledgeBaseIds.length} 个库，命中 ${retrieval.count} 条，来自：${names(hitKbIds)}，empty=${retrieval.empty}`)
      check(retrieval.knowledgeBaseIds.length === 3, '部分命中：检索事件仍覆盖 3 个库')
      check(retrieval.failedKnowledgeBases.length === 0, '部分命中：没有失败的知识库（三个库都搜了，只是两个没命中）')
      check(retrieval.count > 0 && !retrieval.empty, '部分命中：有命中时 empty=false（不能把"部分库没命中"说成"没检索到"）')
      check(hitKbIds.length === 1 && hitKbIds[0] === String(kbA), `部分命中：命中只来自 A（实际 ${names(hitKbIds)}）`)
      check(answer.includes('512') || answer.includes(CODE_A), '部分命中：回答仍然答得出来')
    } finally {
      for (const [label, kbId] of [['B', kbB], ['C', kbC]]) {
        await api('PUT', `/api/knowledge-bases/${kbId}`, { similarityThreshold: originalThresholds[label] })
      }
      console.log('   已把 B/C 的相似度下限改回去')
    }
  }

  // ---------- 5. 一个库真的失败：真实 ES 索引里的坏文档 ----------
  console.log('\n⑥ 一个库真的失败（往真实 ES 里给 B 写一条 _id 非数字的坏文档）')
  const question5 = '堆外内存上限是多少？Agent 循环的最大步数是多少？'
  corruptDocId = `acceptance-corrupt-${stamp}`
  // 注入前先确认 B 的别名真的存在：别名不存在时 ES 会**自动建一个同名物理索引**，
  // 坏文档落进去、B 的检索读不到 → 故障静默失效（详见文件头与 ES_ALIAS_BASE 的说明）。
  // 不带这一步的话，"注入失败"会伪装成"失败隔离功能坏了"。
  const aliasHead = await fetch(`${es}/_alias/${kbAlias(kbB)}`)
  if (aliasHead.status !== 200) {
    throw new Error(
      `KB-B 的 ES 别名不存在（${kbAlias(kbB)}，HTTP ${aliasHead.status}）：`
        + 'B 尚未建立索引，注入坏文档无从谈起（先跑通前面的检索步骤）'
    )
  }
  const put = await fetch(`${es}/${kbAlias(kbB)}/_doc/${encodeURIComponent(corruptDocId)}?refresh=true`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      chunk_id: 1,
      // 库 id 是 19 位 Long，JS 的 number 装不下（会被四舍五入到最近的 2 的幂）：
      // 这里交字符串，由 ES 对 long 字段做默认的字符串→数值强制转换
      knowledge_base_id: kbB,
      document_id: 1,
      user_id: 1,
      chunk_index: 0,
      title: '验收B-SpringAI',
      heading_path: '重试',
      content: `${question5} ${B.doc.body}`,
      tags: [],
      folder: ''
    })
  })
  if (put.status !== 201) {
    throw new Error(`注入坏文档失败：HTTP ${put.status} ${await put.text()}`)
  }
  console.log(`   已写入 ${kbAlias(kbB)}/${corruptDocId}（knowledge_base_id=${kbB}）`)

  const before = (await api('GET', '/api/observability/summary')).json?.data?.['retrieval.kb.failed'] ?? 0
  const { retrieval, answer } = await ragAsk([kbA, kbB, kbC], question5)
  const after = (await api('GET', '/api/observability/summary')).json?.data?.['retrieval.kb.failed'] ?? 0

  const failedIds = retrieval.failedKnowledgeBases.map((item) => String(item.knowledgeBaseId))
  const hitKbIds = [...new Set(kbIdsOf(retrieval))]
  console.log(`   覆盖 ${retrieval.knowledgeBaseIds.length} 个库，命中 ${retrieval.count} 条，来自：${names(hitKbIds)}`)
  console.log(`   失败的知识库：${retrieval.failedKnowledgeBases.map((item) => `${item.knowledgeBaseName}(${item.reason})`).join(' | ') || '(无)'}`)
  console.log(`   retrieval.kb.failed：${before} → ${after}`)

  check(retrieval.knowledgeBaseIds.length === 3, '失败隔离：检索事件仍覆盖 3 个库（含失败的 B）')
  check(retrieval.failedKnowledgeBases.length === 1, `失败隔离：恰好 1 个库失败（实际 ${retrieval.failedKnowledgeBases.length}）`)
  check(failedIds.length === 1 && failedIds[0] === String(kbB), '失败隔离：失败的正是 B')
  check(retrieval.failedKnowledgeBases[0]?.knowledgeBaseName === B.name, '失败隔离：失败项带上了库名（界面能指名道姓）')
  check((retrieval.failedKnowledgeBases[0]?.reason ?? '').length > 0, `失败隔离：失败项带了原因（${retrieval.failedKnowledgeBases[0]?.reason}）`)
  check(hitKbIds.includes(String(kbA)) && hitKbIds.includes(String(kbC)), '失败隔离：A ✅ 与 C ✅ 仍然返回命中')
  check(!hitKbIds.includes(String(kbB)), '失败隔离：B 一条命中都没贡献（失败没有被当成"没命中"混进来）')
  check(retrieval.count > 0 && !retrieval.empty, '失败隔离：整次检索照常成功，回答仍能建立在其余库上')
  check(answer.includes(CODE_A) || answer.includes('512'), '失败隔离：回答含 A 的事实')
  check(answer.includes(CODE_C) || answer.includes('12'), '失败隔离：回答含 C 的事实')
  check(!answer.includes(CODE_B), `失败隔离：回答没有引用失败库的独有事实（${CODE_B}）`)
  check(after - before === 1, `失败隔离：retrieval.kb.failed 计数 +1（${before} → ${after}）`)

  // ---------- 6. 界面必须明说"其中 N 个知识库检索失败" ----------
  console.log('\n⑦ 界面：绑三个库、其中一个失败，必须明说少搜了谁')
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const context = await browser.newContext({ viewport: { width: 1440, height: 980 } })
  await context.addInitScript((value) => {
    localStorage.setItem('nexus.token', value)
    localStorage.setItem('nexus.username', 'demo')
  }, token)
  const page = await context.newPage()
  /** 前端真正发出去的建会话请求体：界面上的 tag 会折叠成"+ 2"，只看 tag 数不出真实选中项 */
  let createConversationBody = null
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().includes('/api/chat/conversations')) {
      try {
        createConversationBody = JSON.parse(request.postData() ?? '{}')
      } catch {
        createConversationBody = null
      }
    }
  })

  await page.goto(base, { waitUntil: 'networkidle' })
  await page.click('.nav-item:has-text("对话")')
  await page.waitForSelector('.composer textarea', { timeout: 15000 })
  await page.click('.rail .new-chat')
  await page.click('.composer-options .el-radio-button:has-text("知识库问答")')
  await page.waitForSelector('.composer-options .kb-select', { timeout: 15000 })
  await page.hover('.composer-options .kb-select')
  const clearButton = page.locator('.composer-options .kb-select .el-select__clear')
  if (await clearButton.count()) {
    await clearButton.first().click()
  }
  await page.click('.composer-options .kb-select')
  for (const name of [A.name, B.name, C.name]) {
    await page.locator('.el-select-dropdown__item', { hasText: name }).last().click()
  }
  await page.keyboard.press('Escape')
  // 多选控件默认 collapse-tags：3 个库只显示 1 个名字 + "+ 2"。所以总数要加起来算
  const tagTexts = (await page.locator('.composer-options .kb-select .el-tag').allInnerTexts())
    .map((text) => text.trim())
  const collapsed = tagTexts
    .filter((text) => /^\+\s*\d+$/.test(text))
    .reduce((sum, text) => sum + Number(text.replace(/\D/g, '')), 0)
  const pickedCount = tagTexts.length - (collapsed > 0 ? 1 : 0) + collapsed
  check(pickedCount === 3, `界面：多选框里一共选中 3 个库（标签显示 ${JSON.stringify(tagTexts)}）`)

  await page.fill('.composer textarea', question5)
  await page.click('.composer .send')
  await page.waitForSelector('.message.from-agent .retrieval', { timeout: 90000 })

  const sent = createConversationBody ?? {}
  const sentKbIds = (sent.kbIds ?? []).map(String)
  check(
    sentKbIds.length === 3 && sentKbIds.includes(String(kbA)) && sentKbIds.includes(String(kbB)) && sentKbIds.includes(String(kbC)),
    `界面：建会话时真的带上了 A/B/C 三个库（实际 ${JSON.stringify(sentKbIds)}）`
  )

  const dangerTag = page.locator('.message.from-agent .retrieval .el-tag--danger')
  await dangerTag.first().waitFor({ timeout: 30000 })
  const tagText = (await dangerTag.first().innerText()).trim()
  console.log(`   检索卡片上的失败标签：${tagText}`)
  check(tagText === '其中 1 个知识库检索失败', `界面：卡片上写着「其中 1 个知识库检索失败」（实际「${tagText}」）`)

  await dangerTag.first().hover()
  // 页面上同时存在 el-select 的 popper（隐藏的），所以按 is-dark 选 tooltip 那个
  await page.waitForSelector('.el-popper.is-dark:visible', { timeout: 10000 })
  await sleep(400)
  const tooltip = (await page.locator('.el-popper.is-dark:visible').last().innerText()).replace(/\s+/g, ' ').trim()
  console.log(`   悬浮提示：${tooltip}`)
  check(tooltip.includes(B.name), '界面：提示里点明了是哪 1 个库（库名，不是"知识库 1003"）')
  check(/：\s*\S/.test(tooltip), '界面：提示里带了失败原因')

  await page.screenshot({ path: resolve(outDir, 'multi-kb-partial-failure.png') })
  console.log(`   截图：docs/screenshots/multi-kb-partial-failure.png`)

  const cardText = (await page.locator('.message.from-agent .retrieval').last().innerText()).replace(/\s+/g, ' ')
  check(/3 个知识库/.test(cardText), '界面：卡片同时说明本次覆盖 3 个库（不是只字不提）')
} catch (error) {
  failures.push(`异常中断：${error.message}`)
  console.log(`\n!! ${error.stack ?? error.message}`)
} finally {
  // ---------- 清理：坏文档与三个库都要删掉，不留痕迹 ----------
  if (corruptDocId) {
    await fetch(`${es}/${kbAlias(kbB)}/_doc/${encodeURIComponent(corruptDocId)}?refresh=true`, { method: 'DELETE' })
      .then((r) => console.log(`清理坏文档：HTTP ${r.status}`))
      .catch((error) => console.log(`清理坏文档失败：${error.message}`))
  }
  if (browser) await browser.close()
  for (const kbId of [kbA, kbB, kbC]) {
    if (kbId) {
      const removed = await api('DELETE', `/api/knowledge-bases/${kbId}`)
      console.log(`清理知识库 ${kbId}：HTTP ${removed.status}`)
    }
  }
}

console.log(`\n${failures.length === 0 ? 'PASS' : `FAIL（${failures.length} 条）`}`)
for (const failure of failures) {
  console.log(`  - ${failure}`)
}
process.exit(failures.length === 0 ? 0 : 1)
