/**
 * 多知识库 RAG 的真机 E2E（Phase 5.5 · P1-6）。
 *
 * 这一期把"一个 RAG 会话只能绑一个库"改成"可以绑多个、逐库检索后跨库归并"。
 * 前三层已经被别的测试钉住了：门面与归并的语义（`ChunkSearchServiceMultiKbTest`）、
 * 准备阶段的分派（`RagAnswerServicePrepareManyTest`）、写入口与越权（`ChatRagModeTest`）；
 * 事件负载形状（`RagStreamRetrievalEventTest`）。**它们全都在桩上跑**——
 * 没有任何一条证明"两篇真实文档、两个真实索引、一次真实提问"能给出跨库的答案。
 * 本脚本补的就是这一段。
 *
 * 四条腿：
 *   ① 两个库各导入一份**内容不同**的文档，各自向量化 + 建索引；
 *   ② 在界面上新建 RAG 会话并**同时选中两个库**（多选控件是本期新做的，必须真的点一次）；
 *   ③ 提问只有第二个库能回答的问题：检索卡片要显示两库的来源，回答要引用第二个库的独有事实；
 *   ④ 越权：把别人的库混进绑定列表 → 整批 404 / 3001，且文案指名是哪个 id。
 *
 * **为什么两份文档必须写不同的内容**：`ContextAssembler.isDuplicate` 只按 content 去重、
 * **不看库**。同一段文字进两个库会被塌成一条引用，"每个库都有贡献"就验不出来——
 * 这条约束是本期新发现的测试陷阱，见 decisions.md B.46。
 *
 * 需要：后端（`local` profile，含 ES + pgvector）+ 前端都在跑，且已种好演示数据
 * （`.demo-token` 是演示账号的令牌）。**本脚本不修改 seed-demo.mjs**：它自建自删。
 * 退出码：0 = 通过；1 = 真实失败。
 */
import { chromium } from 'playwright-core'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { demoToken } from './lib/demo-session.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const token = await demoToken()
const base = process.env.WEB_BASE ?? 'http://localhost:8088'
const outDir = resolve(here, '../../docs/screenshots')

const sleep = (ms) => new Promise((resolve_) => setTimeout(resolve_, ms))

/** 只在第二个库里出现的事实。编造得毫不像常识——模型不可能"本来就知道"它。 */
const UNIQUE_CODE = 'NX-7741'
const UNIQUE_TIMEOUT = '220 毫秒'

const stamp = Date.now()
const kbAName = `多库A-通用 ${stamp}`
const kbBName = `多库B-独有 ${stamp}`

const docA = {
  fileName: '多库A-通用.md',
  body: `# 缓存击穿\n\n缓存击穿指某个热点 key 过期的瞬间，大量并发请求直接打到数据库上。\n常见的两种解法是互斥锁与逻辑过期。\n`
}
const docB = {
  fileName: '多库B-独有.md',
  body: `# 缓存击穿回源规范\n\n缓存击穿的内部代号是 ${UNIQUE_CODE}，回源超时统一设为 ${UNIQUE_TIMEOUT}。\n`
}

const failures = []
const auth = { Authorization: `Bearer ${token}` }

/** 直接打后端（Vite 的 /api 代理同源，所以用前端地址也能通）。 */
async function api(method, path, body, extraHeaders = {}) {
  const headers = { ...auth, ...extraHeaders }
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
    // 非 JSON 响应留着原文，报错时才看得出到底返回了什么
  }
  return { status: response.status, json, text }
}

/** 建一个知识库并导入一份文档，返回库 id。 */
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

  // 导入是异步的（local 走 RabbitMQ）。等它到终态，否则后面嵌的是一篇还没落库的文档
  let task = null
  for (let i = 0; i < 60; i += 1) {
    await sleep(1000)
    const polled = await api('GET', `/api/import-tasks/${taskId}`)
    task = polled.json?.data
    if (task && ['DONE', 'PARTIAL', 'FAILED'].includes(task.status)) break
  }
  if (task?.status !== 'DONE') {
    throw new Error(`导入未成功（${name}）：${JSON.stringify(task)}`)
  }
  console.log(`· ${name}：导入完成（新增 ${task.createdCount} 篇，切片 ${task.totalChunks}）`)

  // 导入只做到"文档与切片落库"，向量化与建索引是另外两步（工作流把它们串起来）
  const workflow = await api('GET', `/api/workflow/run/${kbId}`)
  if (workflow.status !== 200) {
    throw new Error(`工作流失败（${name}）：HTTP ${workflow.status} ${workflow.text}`)
  }
  const steps = workflow.json.data ?? []
  const failed = steps.filter((step) => step.status !== 'SUCCESS')
  for (const step of steps) {
    console.log(`  ${name} · ${step.name}：${step.status ?? ''} ${step.message ?? ''}`.trimEnd())
  }
  if (failed.length) {
    throw new Error(`工作流有步骤未成功（${name}）：${JSON.stringify(failed)}`)
  }

  // 能被检索到才算真的可用：ES 索引与 pgvector 都是异步/批量的，
  // "工作流返回 OK"只说明调用没抛异常。轮询到真的有命中为止
  for (let i = 0; i < 30; i += 1) {
    const hit = await api('GET', `/api/knowledge-bases/${kbId}/search?q=缓存击穿&mode=hybrid`)
    if ((hit.json?.data ?? []).length > 0) {
      console.log(`· ${name}：可检索（${hit.json.data.length} 条命中）`)
      return kbId
    }
    await sleep(1000)
  }
  throw new Error(`${name} 建好之后检索不到任何命中——向量/索引没生效，后续断言没有意义`)
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 980 } })
await context.addInitScript((value) => {
  localStorage.setItem('nexus.token', value)
  localStorage.setItem('nexus.username', 'demo')
}, token)

const page = await context.newPage()
/** 前端真正发出去的建会话请求体（证明"两个库是界面选出来的，不是一个都没选"） */
let createConversationBody = null
/** 那次建会话返回的会话 id（收尾时按它删，不靠"列表里找长度为 2 的那条"这种猜测） */
let createdConversationId = null
page.on('request', (request) => {
  if (request.method() === 'POST' && request.url().includes('/api/chat/conversations')) {
    try {
      createConversationBody = JSON.parse(request.postData() ?? '{}')
    } catch {
      createConversationBody = null
    }
  }
})
page.on('response', async (response) => {
  if (response.request().method() === 'POST' && response.url().includes('/api/chat/conversations')) {
    try {
      createdConversationId = (await response.json())?.data?.id ?? null
    } catch {
      // 建会话失败时响应体不是 JSON——真失败了会有断言兜着，这里不额外报错
      createdConversationId = null
    }
  }
})

let kbA = null
let kbB = null
let conversationId = null

try {
  // ---------- ① 两个库，两份不同的文档 ----------
  kbA = await createKnowledgeBaseWith(kbAName, docA)
  kbB = await createKnowledgeBaseWith(kbBName, docB)
  console.log(`① 两个库都就绪：A=${kbA} B=${kbB}`)

  // ---------- ② 界面上新建 RAG 会话并选中两个库 ----------
  await page.goto(base, { waitUntil: 'networkidle' })
  await page.click('[data-testid="nav-chat"]')
  await page.waitForSelector('[data-testid="chat-input"]', { timeout: 15000 })
  await page.click('[data-testid="chat-new"]')
  await page.click('[data-testid="chat-draft-mode-rag"]')
  await page.waitForSelector('[data-testid="chat-draft-kb-select"]', { timeout: 15000 })

  // 新建时默认选中列表里的第一个库；先清掉它，否则会变成"3 个库"，
  // 而这条腿要验的恰好是"我选的这两个"
  await page.hover('[data-testid="chat-draft-kb-select"]')
  const clearButton = page.locator('[data-testid="chat-draft-kb-select"] .el-select__clear')
  if (await clearButton.count()) {
    await clearButton.first().click()
  }

  await page.click('[data-testid="chat-draft-kb-select"]')
  for (const name of [kbAName, kbBName]) {
    await page.locator('.chat-draft-kb-select-popper .el-select-dropdown__item', { hasText: name }).last().click()
  }
  await page.keyboard.press('Escape')

  const picked = await page.locator('[data-testid="chat-draft-kb-select"] .el-tag').allInnerTexts()
  console.log(`② 界面上选中的知识库：${picked.map((text) => text.trim()).join(' | ')}`)
  if (picked.length !== 2) {
    failures.push(`多选控件里应有 2 个库，实际 ${picked.length}：${JSON.stringify(picked)}`)
  }

  // ---------- ③ 提问：只有第二个库能回答的问题 ----------
  await page.fill('[data-testid="chat-input"]', '缓存击穿的内部代号是什么？回源超时设为多少？')
  await page.click('[data-testid="chat-send"]')
  await page.waitForSelector('[data-testid="chat-message"][data-role="ASSISTANT"] .retrieval-count', { timeout: 60000 })

  const sent = createConversationBody ?? {}
  console.log(`③ 建会话请求体：${JSON.stringify(sent.kbIds ?? null)}`)
  if (!Array.isArray(sent.kbIds) || sent.kbIds.length !== 2) {
    failures.push(`界面建会话时没有带上两个库：${JSON.stringify(sent.kbIds)}`)
  } else if (!sent.kbIds.includes(kbA) || !sent.kbIds.includes(kbB)) {
    failures.push(`建会话带的知识库不是这两个：${JSON.stringify(sent.kbIds)}（期望 ${kbA} 与 ${kbB}）`)
  }
  conversationId = createdConversationId

  // 检索卡片：多库的标签 + 每条的来源库名
  const cardText = (await page.locator('[data-testid="chat-retrieval"]').last().innerText()).replace(/\s+/g, ' ').trim()
  console.log(`   检索卡片：${cardText}`)
  if (!/检索到 [0-9]+ 个片段/.test(cardText)) {
    failures.push(`检索卡片文案异常：${cardText}`)
  }
  if ((await page.locator('[data-testid="chat-retrieval-multi-kb"]').count()) !== 1) {
    failures.push('绑了两个库，但卡片上没有"2 个知识库"的标签（knowledgeBaseIds 没带上？）')
  }
  const hitKbTags = (await page.locator('.retrieval-hits .hit-kb').allInnerTexts())
    .map((text) => text.trim())
  console.log(`   命中来源标注：${hitKbTags.join(' | ') || '(无)'}`)
  if (!hitKbTags.includes(kbAName) || !hitKbTags.includes(kbBName)) {
    failures.push(`命中列表没有同时标出两个库：${JSON.stringify(hitKbTags)}`)
  }

  // 回答必须用上第二个库的独有事实——这是"多库真的生效"的最终证据
  await page.waitForSelector('[data-testid="chat-message"][data-role="ASSISTANT"] .markdown:not(.stream-text)', { timeout: 120000 })
  await page.waitForFunction(
    () => {
      const items = document.querySelectorAll('[data-testid="chat-message"][data-role="ASSISTANT"] .markdown:not(.stream-text)')
      return items.length > 0 && items[items.length - 1].textContent.length > 0
    },
    undefined,
    { timeout: 120000 }
  )
  const answer = (await page.locator('[data-testid="chat-message"][data-role="ASSISTANT"] .markdown:not(.stream-text)').last().innerText())
    .replace(/\s+/g, ' ')
    .trim()
  console.log(`   回答：${answer.slice(0, 200)}`)
  if (!answer.includes(UNIQUE_CODE)) {
    failures.push(`回答没有引用第二个库的独有事实 ${UNIQUE_CODE}——多库检索没真正生效：${answer}`)
  }
  if (!answer.includes('220')) {
    failures.push(`回答没有引用第二个库的回源超时（${UNIQUE_TIMEOUT}）：${answer}`)
  }
  await page.screenshot({ path: `${outDir}/agent-chat-multi-kb.png` })
  console.log('✓ 已截图 docs/screenshots/agent-chat-multi-kb.png')

  // ---------- ④ 越权：别人的库混进绑定列表 ----------
  const otherName = `multi-kb-other-${stamp}`
  const otherUser = await api('POST', '/api/auth/register', {
    username: otherName,
    password: 'Passw0rd!23'
  })
  const otherLogin = await api('POST', '/api/auth/login', {
    username: otherName,
    password: 'Passw0rd!23'
  })
  const otherToken = otherLogin.json?.data?.accessToken
  if (!otherToken) {
    failures.push(`第二个用户登录失败（${otherUser.status}）：${otherUser.text}`)
  } else {
    const otherKb = await api('POST', '/api/knowledge-bases', { name: `别人的库 ${stamp}` }, { Authorization: `Bearer ${otherToken}` })
    const otherKbId = otherKb.json?.data?.id
    // 自己的库放在前面：只校验第一个 id 的实现会在这里放行
    const rejected = await api('POST', '/api/chat/conversations', {
      mode: 'RAG',
      kbIds: [kbA, otherKbId]
    })
    console.log(`④ 越权绑定：HTTP ${rejected.status} code=${rejected.json?.code} ${rejected.json?.message}`)
    if (rejected.status !== 404 || rejected.json?.code !== 3001) {
      failures.push(`越权绑定没有被拒绝：HTTP ${rejected.status} ${rejected.text}`)
    } else if (!String(rejected.json?.message ?? '').includes(String(otherKbId))) {
      failures.push(`越权拒绝的文案没有指名是哪个库：${rejected.json?.message}`)
    }
    // 收尾：别人的库用别人的身份删（自己的 token 删不掉，那是对的）
    await api('DELETE', `/api/knowledge-bases/${otherKbId}`, undefined, { Authorization: `Bearer ${otherToken}` })
  }
} catch (error) {
  failures.push(`脚本中断：${error instanceof Error ? error.message : String(error)}`)
  try {
    await page.screenshot({ path: `${outDir}/agent-chat-multi-kb-failed.png` })
  } catch {
    // 截图失败不该盖住真正的错误
  }
} finally {
  // ---------- 收尾：不留"验证用"的垃圾库与垃圾会话 ----------
  if (conversationId) {
    await api('DELETE', `/api/chat/conversations/${conversationId}`)
  }
  for (const kbId of [kbA, kbB]) {
    if (kbId) {
      const deleted = await api('DELETE', `/api/knowledge-bases/${kbId}`)
      console.log(`· 清理知识库 ${kbId}：HTTP ${deleted.status}`)
    }
  }
  await browser.close()
}

if (failures.length) {
  console.error('\n✗ 多知识库 E2E 失败：')
  for (const failure of failures) {
    console.error(`  - ${failure}`)
  }
  process.exit(1)
}
console.log('\n✓ 多知识库 E2E 通过：两库检索、跨库归并、来源标注、越权拒绝')
