/**
 * Full Product Smoke Test（Phase 5.8 §十）——一条链路走完整个产品。
 *
 * ## 为什么还要有这一个脚本
 *
 * 仓库里已有 11 个 `verify-*.mjs`，覆盖了单个功能面（登录 / 导入 / 流式 / 多库 / Agent 管理 /
 * 安全…）。它们的共同前提是 **seed-demo 种好的演示数据**、以及一个**已经存在**的知识库和索引。
 * 也就是说：**没有任何一个脚本证明"一个全新用户从零开始，能把整条链路走通"**。
 *
 * 本脚本补的就是这一段，而且刻意覆盖了两个此前没人碰的面：
 *   · **异步导入**——上传只是入队，向量化与建索引要等任务到终态，这一段没有脚本端到端等过；
 *   · **Observability**——`/api/observability/summary` 与 `/agents` 此前没有任何 E2E 触及。
 *
 * ## 覆盖的用户要求（逐条对应）
 *   注册 → 登录 → 创建知识库 → 导入文档 → 等待异步处理 → RAG →
 *   创建 Agent → 绑定多个知识库 → Agent Chat → Tool Calling → Citation → Observability
 *
 * ## 与其它脚本不同的三条约定
 *   1. **自建账号**：每次运行注册一个 `smoke-*`，不依赖 seed-demo，也不读 `.demo-token`；
 *   2. **自清理**：会话 / Agent / 两个知识库在 finally 里删掉。**账号删不掉**——没有删号接口
 *      （这是已知缺口不是为了偷懒），所以每跑一次会留下一个 `smoke-*` 账号；
 *   3. **断言的是"独有事实"**：只有 KB-B 里写着 `NX-8153` 与 `180 毫秒`。回答里出现它，
 *      才证明"检索真的命中了我上传的那份文档"，而不是模型本来就说过这句话。
 *
 * ## 环境
 *   默认打**整栈容器化**的入口（`http://localhost:8088`），因为那才是部署形态：
 *     API_BASE=http://localhost:8088/api          （直接打后端需要单独设，容器里后端不发布端口）
 *     WEB_BASE=http://localhost:8088
 *   需要 ES + pgvector + 一个可用的模型（最后两条腿要用模型）。模型不可用时**跳过**而非失败，
 *   退出码 2——与其它脚本一致：**跳过不等于通过**。
 *
 * 退出码：0 = 全部通过；1 = 真实失败；2 = 有跳过（环境能力缺失）。
 */
import { chromium } from 'playwright-core'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const outDir = resolve(here, '../../docs/screenshots')

const API_BASE = process.env.API_BASE ?? 'http://localhost:8088/api'
const WEB_BASE = process.env.WEB_BASE ?? 'http://localhost:8088'

const sleep = (ms) => new Promise((resolve_) => setTimeout(resolve_, ms))

/** 只在 KB-B 里出现的事实。编得毫不像常识——模型不可能"本来就知道"它。 */
const UNIQUE_CODE = 'NX-8153'
const UNIQUE_TIMEOUT = '180 毫秒'

const stamp = Date.now()
const username = `smoke-${stamp}`
const password = 'Passw0rd!23'
const kbAName = `冒烟-A-通用 ${stamp}`
const kbBName = `冒烟-B-独有 ${stamp}`
const agentCode = `smoke-agent-${stamp}`

const docA = {
  fileName: '冒烟A-通用.md',
  body: '# 缓存击穿\n\n缓存击穿指某个热点 key 过期的瞬间，大量并发请求直接打到数据库上。\n常见解法是互斥锁与逻辑过期。\n'
}
const docB = {
  fileName: '冒烟B-独有.md',
  body: `# 缓存击穿回源规范\n\n缓存击穿的内部代号是 ${UNIQUE_CODE}，回源超时统一设为 ${UNIQUE_TIMEOUT}。\n`
}

const failures = []
const skips = []
const steps = []

function note(step, message) {
  steps.push({ step, ok: true, message })
  console.log(`  ✓ ${step}：${message}`)
}

function fail(step, message) {
  steps.push({ step, ok: false, message })
  failures.push(`[${step}] ${message}`)
  console.error(`  ✗ ${step}：${message}`)
}

function skip(step, message) {
  skips.push(`[${step}] ${message}`)
  console.warn(`  ⚠ SKIPPED ${step}：${message}`)
}

/** 统一请求包装：返回 status / json / text，绝不吞掉非 JSON 响应（报错时要看得到原文）。 */
async function api(method, path, body, token, extraHeaders = {}) {
  const headers = { ...extraHeaders }
  if (token) headers.Authorization = `Bearer ${token}`
  let payload = body
  if (body && !(body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  }
  const response = await fetch(`${API_BASE}${path}`, { method, headers, body: payload })
  const text = await response.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    // 保留原文，报错时用得上
  }
  return { status: response.status, json, text }
}

/** 轮询直到条件成立，超时后返回最后一次的观测值（把"为什么没等到"带回去）。 */
async function poll(describe, attempt, isDone, times, intervalMs) {
  let last = null
  for (let i = 0; i < times; i += 1) {
    last = await attempt()
    if (isDone(last)) return { ok: true, value: last }
    await sleep(intervalMs)
  }
  return { ok: false, value: last, describe }
}

const auth = (token) => ({ token })

// ---------------------------------------------------------------- 主流程

let token = null
let kbA = null
let kbB = null
let agentId = null
let conversationId = null
let modelAvailable = true

try {
  // ---------- STEP 1 · 注册 + 登录 ----------
  const registered = await api('POST', '/auth/register', { username, password })
  if (registered.status === 429) {
    fail('1 注册', `被限流（429）：注册路径有 IP 级限流，短时间反复跑本脚本会撞上。请等一会儿再跑。响应：${registered.text}`)
    throw new Error('register rate limited')
  }
  if (registered.status !== 200) {
    fail('1 注册', `HTTP ${registered.status} ${registered.text}`)
    throw new Error('register failed')
  }
  const loggedIn = await api('POST', '/auth/login', { username, password })
  token = loggedIn.json?.data?.accessToken
  if (loggedIn.status !== 200 || !token) {
    fail('1 登录', `HTTP ${loggedIn.status} ${loggedIn.text}`)
    throw new Error('login failed')
  }
  note('1 注册 + 登录', `新账号 ${username} 拿到令牌（长度 ${token.length}）`)

  // ---------- STEP 2 · 创建两个知识库 ----------
  const createdA = await api('POST', '/knowledge-bases', { name: kbAName }, token)
  const createdB = await api('POST', '/knowledge-bases', { name: kbBName }, token)
  kbA = createdA.json?.data?.id
  kbB = createdB.json?.data?.id
  if (!kbA || !kbB || kbA === kbB) {
    fail('2 建库', `两个库的 id 异常：A=${kbA} B=${kbB}（${createdA.text} / ${createdB.text}）`)
    throw new Error('create kb failed')
  }
  note('2 创建知识库', `A=${kbA} B=${kbB}`)

  // ---------- STEP 3 · 上传文档（异步导入入队） ----------
  const taskIds = {}
  for (const [label, kbId, doc] of [['A', kbA, docA], ['B', kbB, docB]]) {
    const form = new FormData()
    form.append('file', new Blob([doc.body], { type: 'text/markdown' }), doc.fileName)
    const uploaded = await api('POST', `/knowledge-bases/${kbId}/documents`, form, token)
    const taskId = uploaded.json?.data?.id
    if (uploaded.status !== 200 || !taskId) {
      fail(`3 上传(${label})`, `HTTP ${uploaded.status} ${uploaded.text}`)
      throw new Error('upload failed')
    }
    taskIds[label] = taskId
  }
  note('3 上传文档', `两个导入任务入队：${JSON.stringify(taskIds)}`)

  // ---------- STEP 4 · 等异步导入到终态 ----------
  for (const [label, kbId, taskId] of [['A', kbA, taskIds.A], ['B', kbB, taskIds.B]]) {
    const settled = await poll(
      `导入任务 ${taskId}`,
      async () => (await api('GET', `/import-tasks/${taskId}`, undefined, token)).json?.data,
      (task) => task && ['DONE', 'PARTIAL', 'FAILED'].includes(task.status),
      90,
      1000
    )
    const task = settled.value
    if (!settled.ok) {
      fail(`4 异步导入(${label})`, `90 秒内没到终态，最后一次：${JSON.stringify(task)}`)
      throw new Error('import timeout')
    }
    if (task.status !== 'DONE') {
      fail(`4 异步导入(${label})`, `终态不是 DONE 而是 ${task.status}：${JSON.stringify(task)}`)
      throw new Error('import not done')
    }
    note(`4 异步导入(${label})`, `${task.status}（新增 ${task.createdCount} 篇，切片 ${task.totalChunks}）`)

    // 导入只做到"文档与切片落库"，向量化与建索引是另外两步——工作流把它们串起来
    const workflow = await api('GET', `/workflow/run/${kbId}`, undefined, token)
    const workflowSteps = workflow.json?.data
    if (workflow.status !== 200 || !Array.isArray(workflowSteps)) {
      fail(`5 向量化+索引(${label})`, `工作流没返回步骤列表：HTTP ${workflow.status} ${workflow.text}`)
      throw new Error('workflow failed')
    }
    const bad = workflowSteps.filter((step) => step.status !== 'SUCCESS')
    if (bad.length) {
      fail(`5 向量化+索引(${label})`, `有步骤未成功：${JSON.stringify(bad)}`)
      throw new Error('workflow step failed')
    }
    note(`5 向量化+索引(${label})`, `${workflowSteps.length} 步全部 SUCCESS（${workflowSteps.map((s) => s.name).join(' → ')}）`)

    // "工作流没抛异常"不等于"检索得到"——ES 与 pgvector 的写入是异步的，轮询到真有命中为止
    const searchable = await poll(
      `库 ${kbId} 可检索`,
      async () => (await api('GET', `/knowledge-bases/${kbId}/search?q=缓存击穿&mode=hybrid`, undefined, token)).json?.data,
      (hits) => Array.isArray(hits) && hits.length > 0,
      30,
      1000
    )
    if (!searchable.ok) {
      fail(`5b 可检索(${label})`, `30 秒内 hybrid 检索不到任何命中——向量或索引没生效，后面的断言没有意义`)
      throw new Error('not searchable')
    }
    note(`5b 可检索(${label})`, `${searchable.value.length} 条命中`)
  }

  // ---------- STEP 6 · RAG（单库，带引用） ----------
  const rag = await api(
    'POST',
    `/knowledge-bases/${kbB}/ask`,
    { question: '缓存击穿的内部代号是什么？回源超时设为多少？' },
    token
  )
  const ragData = rag.json?.data
  if (rag.status !== 200 || !ragData) {
    if (rag.json?.code === 4006 || rag.status === 403) {
      modelAvailable = false
      skip('6 RAG 问答', `模型不可用（HTTP ${rag.status} code=${rag.json?.code}）：${rag.json?.message}`)
    } else {
      fail('6 RAG 问答', `HTTP ${rag.status} ${rag.text}`)
    }
  } else {
    const answer = String(ragData.answer ?? '')
    const citations = ragData.citations ?? []
    if (!answer.includes(UNIQUE_CODE)) {
      fail('6 RAG 问答', `回答没有引用 ${UNIQUE_CODE}——检索没真正命中上传的文档：${answer}`)
    } else if (!citations.length) {
      fail('6 RAG 问答', `回答引用了独有事实，但 citations 为空——引用没有落库/没有返回`)
    } else {
      const src = citations[0]
      note('6 RAG 问答', `回答含 ${UNIQUE_CODE}，引用 ${citations.length} 条（首条来自文档「${src.title ?? src.documentId}」，chunk ${src.chunkId}）`)
    }
  }

  // ---------- STEP 7 · 创建 Agent 并绑定两个知识库 ----------
  const agent = await api(
    'POST',
    '/agents',
    {
      code: agentCode,
      name: `冒烟 Agent ${stamp}`,
      description: 'Full Product Smoke Test 自建，用后即删',
      systemPrompt: '你是知识库助手。只根据检索到的资料回答，资料里没有就直说没有。',
      kbIds: [kbA, kbB]
    },
    token
  )
  agentId = agent.json?.data?.id
  if (agent.status !== 200 || !agentId) {
    fail('7 创建 Agent', `HTTP ${agent.status} ${agent.text}`)
    throw new Error('create agent failed')
  }
  const boundKbs = agent.json.data.kbIds ?? []
  if (boundKbs.length !== 2) {
    fail('7 创建 Agent', `绑定知识库不是 2 个：${JSON.stringify(boundKbs)}`)
  } else {
    note('7 创建 Agent', `id=${agentId} status=${agent.json.data.status} kbIds=[${boundKbs}]（两个库都绑上了）`)
  }

  // ---------- STEP 8 · 发布（DRAFT → PUBLISHED） ----------
  const published = await api('PUT', `/agents/${agentId}`, { status: 'PUBLISHED' }, token)
  if (published.json?.data?.status !== 'PUBLISHED') {
    fail('8 发布 Agent', `状态没变成 PUBLISHED：HTTP ${published.status} ${published.text}`)
  } else {
    note('8 发布 Agent', 'DRAFT → PUBLISHED')
  }

  // ---------- STEP 9 · Agent Chat + Tool Calling（同步试跑，能直接看到工具痕迹） ----------
  const testChat = await api(
    'POST',
    `/agents/${agentId}/test-chat`,
    { question: '缓存击穿的内部代号是什么？回源超时设为多少？' },
    token
  )
  const testData = testChat.json?.data
  if (testChat.status !== 200 || !testData) {
    if (testChat.json?.code === 4006 || testChat.status === 403) {
      modelAvailable = false
      skip('9 Agent 试跑', `模型不可用：${testChat.json?.message}`)
    } else {
      fail('9 Agent 试跑', `HTTP ${testChat.status} ${testChat.text}`)
    }
  } else {
    const tools = testData.toolCalls ?? []
    const toolNames = tools.map((call) => call.tool ?? call.name).filter(Boolean)
    if (!testData.usedTools) {
      fail('9 Agent 试跑', `usedTools=false——Agent 没有调用任何工具，那"绑定的知识库"就没被用到`)
    } else if (!toolNames.includes('knowledge_search')) {
      fail('9 Agent 试跑', `工具痕迹里没有 knowledge_search：${JSON.stringify(toolNames)}`)
    } else {
      note('9 Agent Chat + Tool Calling', `usedTools=true，工具痕迹 ${JSON.stringify(toolNames)}`)
    }
    const agentAnswer = String(testData.answer ?? '')
    if (!agentAnswer.includes(UNIQUE_CODE)) {
      fail('9 Agent 试跑', `回答没有引用 ${UNIQUE_CODE}——Agent 的检索范围没覆盖到 KB-B：${agentAnswer}`)
    } else {
      note('9 Citation（Agent 路径）', `回答引用了 KB-B 的独有事实 ${UNIQUE_CODE}`)
    }
  }

  // ---------- STEP 10 · 会话式 Agent Chat（SSE 流式，走产品路径） ----------
  const conversation = await api('POST', '/chat/conversations', { mode: 'AGENT', agentId }, token)
  conversationId = conversation.json?.data?.id
  if (conversation.status !== 200 || !conversationId) {
    fail('10 建会话', `HTTP ${conversation.status} ${conversation.text}`)
  } else {
    note('10 建会话', `mode=AGENT agentId=${agentId} conversationId=${conversationId}`)

    const streamed = await streamChat(token, conversationId, '缓存击穿的内部代号是什么？')
    if (!streamed.ok) {
      if (streamed.status === 403 || streamed.code === 4006) {
        modelAvailable = false
        skip('10 流式 Agent Chat', `模型不可用：HTTP ${streamed.status}`)
      } else {
        fail('10 流式 Agent Chat', `流建立失败：HTTP ${streamed.status} ${streamed.text}`)
      }
    } else {
      const names = [...new Set(streamed.events.map((event) => event.name))]
      console.log(`     观测到的 SSE 事件：${names.join('、')}`)
      // 契约（SseEventNames）：meta 先来，工具调用是 tool_call/tool_result，文本是 delta，结束是 done
      if (names.includes('error')) {
        const err = streamed.events.find((event) => event.name === 'error')
        fail('10 流式 Agent Chat', `流里出现 error 事件：${JSON.stringify(err.payload)}`)
      }
      if (!names.includes('meta')) {
        fail('10 流式 Agent Chat', `没有收到 meta 事件（流的建立契约）：${JSON.stringify(names)}`)
      }
      if (!names.includes('tool_call') || !names.includes('tool_result')) {
        fail('10 流式 Agent Chat', `没有观测到 tool_call/tool_result——流式路径上的工具调用没发生：${JSON.stringify(names)}`)
      }
      if (!names.includes('done')) {
        fail('10 流式 Agent Chat', `没有收到 done 事件，流可能被截断：${JSON.stringify(names)}`)
      }
      if (!streamed.text.includes(UNIQUE_CODE)) {
        fail('10 流式 Agent Chat', `拼起来的回答没有引用 ${UNIQUE_CODE}：${streamed.text.slice(0, 200)}`)
      } else {
        note('10 流式 Agent Chat', `事件链完整（${names.join('→')}），回答含 ${UNIQUE_CODE}`)
      }
    }
  }

  // ---------- STEP 11 · Observability ----------
  const summary = await api('GET', '/observability/summary', undefined, token)
  const counters = summary.json?.data
  if (summary.status !== 200 || !counters || typeof counters !== 'object') {
    fail('11 Observability', `/summary 形状异常：HTTP ${summary.status} ${summary.text}`)
  } else {
    const entries = Object.entries(counters)
    note('11 Observability(/summary)', `进程内计数 ${entries.length} 条：${entries.map(([k, v]) => `${k}=${v}`).join('、') || '(全为 0)'}`)
  }

  const usage = await api('GET', '/observability/agents', undefined, token)
  const usageRows = usage.json?.data
  if (usage.status !== 200 || !Array.isArray(usageRows)) {
    fail('11 Observability', `/agents 形状异常：HTTP ${usage.status} ${usage.text}`)
  } else {
    const mine = usageRows.find((row) => String(row.agentId ?? row.id) === String(agentId))
    if (!modelAvailable) {
      skip('11 Observability(/agents)', '模型不可用，本轮不会有该 Agent 的用量')
    } else if (!mine) {
      fail('11 Observability(/agents)', `用量列表里没有刚建的 Agent ${agentId}：${JSON.stringify(usageRows)}`)
    } else {
      note('11 Observability(/agents)', `该 Agent 被记到用量里：${JSON.stringify(mine)}`)
    }
  }

  // ---------- STEP 12 · 用户隔离（越权拿不到别人的库） ----------
  const other = `smoke-other-${stamp}`
  const otherReg = await api('POST', '/auth/register', { username: other, password })
  if (otherReg.status === 429) {
    skip('12 用户隔离', '注册被限流（429），本腿跳过')
  } else {
    const otherToken = (await api('POST', '/auth/login', { username: other, password })).json?.data?.accessToken
    if (!otherToken) {
      fail('12 用户隔离', `第二个用户登录失败：${otherReg.text}`)
    } else {
      const peek = await api('GET', `/knowledge-bases/${kbA}`, undefined, otherToken)
      if (peek.status !== 404 || peek.json?.code !== 3001) {
        fail('12 用户隔离', `别人的库应该 404/3001，实际 HTTP ${peek.status} code=${peek.json?.code} ${peek.text}`)
      } else {
        note('12 用户隔离', `越权读 KB-A 被拒：HTTP ${peek.status} code=${peek.json?.code}`)
      }
    }
  }

  // ---------- STEP 13 · 界面一条腿（证明后端好 ≠ 页面好） ----------
  await uiLeg(token)
} catch (error) {
  failures.push(`脚本中断：${error instanceof Error ? error.message : String(error)}`)
} finally {
  // ---------- 自清理：不留垃圾会话 / Agent / 知识库 ----------
  if (conversationId) {
    const deleted = await api('DELETE', `/chat/conversations/${conversationId}`, undefined, token)
    console.log(`  · 清理会话 ${conversationId}：HTTP ${deleted.status}`)
  }
  if (agentId) {
    const deleted = await api('DELETE', `/agents/${agentId}`, undefined, token)
    console.log(`  · 清理 Agent ${agentId}：HTTP ${deleted.status}`)
  }
  for (const kbId of [kbA, kbB]) {
    if (kbId) {
      const deleted = await api('DELETE', `/knowledge-bases/${kbId}`, undefined, token)
      console.log(`  · 清理知识库 ${kbId}：HTTP ${deleted.status}`)
    }
  }
  console.log(`  ! 账号 ${username} 无法删除（没有删号接口）——这是已知缺口，不是残留 bug`)
}

// ---------------------------------------------------------------- 辅助实现

/** 把 SSE 流读干净：按 `event:` / `data:` 两行一组解析，拼出回答全文。 */
async function streamChat(token_, conversationId_, content) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 180000)
  try {
    const response = await fetch(`${API_BASE}/chat/stream`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token_}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversationId: conversationId_, content }),
      signal: controller.signal
    })
    if (!response.ok) {
      return { ok: false, status: response.status, text: await response.text(), events: [] }
    }
    const events = []
    const decoder = new TextDecoder()
    let buffer = ''
    let name = null
    for await (const chunk of response.body) {
      buffer += decoder.decode(chunk, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''
      for (const line of lines) {
        if (line.startsWith('event:')) {
          name = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          const raw = line.slice(5).trim()
          let payload = null
          try {
            payload = JSON.parse(raw)
          } catch {
            payload = raw
          }
          events.push({ name, payload })
        }
      }
    }
    const text = events
      .filter((event) => event.name === 'delta')
      .map((event) => event.payload?.text ?? '')
      .join('')
    return { ok: true, status: response.status, events, text }
  } catch (error) {
    return { ok: false, status: 0, text: String(error), events: [] }
  } finally {
    clearTimeout(timer)
  }
}

/** 界面一条腿：真登录表单 + 看到自己刚建的两个库。浏览器缺失算跳过，不算失败。 */
async function uiLeg(token_) {
  let browser = null
  try {
    browser = await chromium.launch({ channel: 'msedge', headless: true })
  } catch {
    try {
      browser = await chromium.launch({ headless: true })
    } catch (error) {
      skip('13 界面', `没有可用的浏览器（${error instanceof Error ? error.message : String(error)}）`)
      return
    }
  }
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 980 } })
    const page = await context.newPage()
    await page.goto(WEB_BASE, { waitUntil: 'networkidle' })

    // 真走一遍登录表单——不是塞 localStorage。这一步才能验"登录页 + 路由守卫"是通的
    const onLogin = await page.locator('input[type="password"]').count()
    if (!onLogin) {
      fail('13 界面', `打开 ${WEB_BASE} 没有落到登录页（找不到密码框）——SPA 路由或静态资源有问题`)
      return
    }
    await page.fill('input:not([type="password"]):visible', username)
    await page.fill('input[type="password"]', password)
    await page.click('button:has-text("登录")')
    await page.waitForSelector('.nav-item', { timeout: 20000 })

    const body = (await page.locator('body').innerText()).replace(/\s+/g, ' ')
    if (!body.includes(kbAName) || !body.includes(kbBName)) {
      fail('13 界面', `登录后知识库列表里没有刚建的两个库（${kbAName} / ${kbBName}）：${body.slice(0, 300)}`)
    } else {
      await page.screenshot({ path: `${outDir}/full-product-smoke.png` })
      note('13 界面', `真登录表单登录成功，列表里能看到两个新库；截图 docs/screenshots/full-product-smoke.png`)
    }
  } catch (error) {
    fail('13 界面', `界面腿中断：${error instanceof Error ? error.message : String(error)}`)
  } finally {
    await browser.close().catch(() => {})
  }
}

// ---------------------------------------------------------------- 汇总

console.log('\n──────── 结果汇总 ────────')
for (const step of steps) {
  console.log(`${step.ok ? '✓' : '✗'} ${step.step}：${step.message}`)
}
for (const item of skips) {
  console.log(`⚠ SKIPPED ${item}`)
}

if (failures.length) {
  console.error('\n✗ Full Product Smoke Test 失败：')
  for (const failure of failures) {
    console.error(`  - ${failure}`)
  }
  process.exit(1)
}
if (skips.length) {
  console.warn(`\n⚠ Full Product Smoke Test 通过（有 ${skips.length} 条跳过，跳过不等于通过）`)
  process.exit(2)
}
console.log('\n✓ Full Product Smoke Test 通过：注册 → 登录 → 建库 → 导入 → 异步 → RAG → Agent → 多库绑定 → Agent Chat → Tool Calling → Citation → Observability → 界面')
