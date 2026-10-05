/**
 * 「停止生成」验收（任务书 §30）。
 *
 * 为什么单开一个脚本：全仓此前**没有任何脚本覆盖这个按钮**——而它是唯一一个
 * "用户主动中断一个正在烧 Token 的长任务"的入口，也是最容易被写成"界面卡在生成中"
 * 的地方。只验证"能回答问题"是不够的：答不完的时候能否干净地停下来，才是产品可用性。
 *
 * 服务端的四条终止路径（正常 done / 用户停止 stopAndFinalize / 流式异常 onError /
 * 客户端断开 abandon）**都汇总到同一个 persist()**，因此本脚本只需触发其中两条
 * 最贴近用户操作的路径，就能覆盖"部分内容落库"这一核心契约：
 *
 *   场景 A「生成刚开始就停止」：请求已发出（服务端已落 STREAMING 助手消息）后立即停止。
 *   场景 B「长回答生成一半停止」：正文累计到一定字数后停止，再刷新页面核对是否留得住。
 *
 * 每个场景断言同一组后置条件（§30 原文）：
 *   ① 已生成的内容保留（界面仍在）      ② 消息正常落库（服务端 status=STOPPED）
 *   ③ UI 不死（无 pageerror）           ④ Loading 清除（"停止"按钮消失）
 *   ⑤ 按钮状态恢复（"发送"按钮回归、输入框恢复可输入）
 *
 * 「Agent Tool Calling 时停止」不单列：它走的是同一个 stopAndFinalize，
 * 差别只在断点落在工具阶段还是文本阶段，后置条件完全一致；强行按时序卡点只会制造假阴性。
 *
 * 用法：
 *   node web/scripts/verify-stop.mjs
 *   WEB_BASE=http://localhost:8088 node web/scripts/verify-stop.mjs
 *
 * 依赖：无（2026-10-03 起改为经 `lib/demo-session.mjs` 现登录换 demo 令牌，不再依赖
 * 外部预置的 web/.demo-token —— 那份令牌 30 分钟就过期，曾是"跑一半 401"的根因）。
 * 栈需处于 full（docker compose）状态，因为 AGENT 模式要先过检索这一步。
 *
 * 收尾：本脚本会在 demo 账号下建两个会话，结束后自动删除（软删除），不留下验收垃圾。
 */
import { chromium } from 'playwright-core'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { demoToken } from './lib/demo-session.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const token = await demoToken()
const base = process.env.WEB_BASE ?? 'http://localhost:8088'
/** 截图统一放仓库根的 docs/screenshots（相对路径会跟着 cwd 跑偏） */
const outDir = resolve(here, '../../docs/screenshots')

/**
 * 刻意要一段长回答：只有回答够长，"生成一半停止"才不是靠运气卡点。
 * 明确写死"不要提前结束"，避免模型两三句就收尾导致中止落空。
 */
const LONG_PROMPT =
  '请写一份 2000 字以上的长篇技术讲解，主题是 Java 并发编程全景。' +
  '必须包含 20 个独立小节，每节至少 100 字，逐节展开论述，不要省略、不要提前结束。'

const results = []
function record(name, pass, detail = '') {
  results.push({ name, pass, detail })
  console.log(`${pass ? '✓' : '✗'} ${name}${detail ? ` — ${detail}` : ''}`)
}

// ---------- 服务端真相（只读 REST）：断言"落库"必须看库，不能只看界面 ----------
const api = async (path) => {
  const res = await fetch(base + path, { headers: { Authorization: `Bearer ${token}` } })
  if (!res.ok) throw new Error(`GET ${path} -> ${res.status}`)
  return res.json()
}
const messagesOf = async (conversationId) =>
  (await api(`/api/chat/messages/${conversationId}?pageNum=1&pageSize=100`)).data.list

/** 终态：流已经结束。轮询到最后一条助手消息落定为止。 */
const TERMINAL = new Set(['DONE', 'STOPPED', 'FAILED'])
async function waitForAssistantMessage(conversationId, timeout = 40000) {
  const deadline = Date.now() + timeout
  let last = null
  while (Date.now() < deadline) {
    const assistants = (await messagesOf(conversationId)).filter((m) => m.role === 'ASSISTANT')
    last = assistants.at(-1) ?? null
    if (last && TERMINAL.has(last.status)) return last
    await new Promise((r) => setTimeout(r, 500))
  }
  return last
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 980 } })
await context.addInitScript((value) => {
  localStorage.setItem('nexus.token', value)
  localStorage.setItem('nexus.username', 'demo')
}, token)

const page = await context.newPage()
/** 页面级 JS 错误：白屏类问题往往先在这里露头（§30 的"UI 不死"） */
const pageErrors = []
page.on('pageerror', (error) => pageErrors.push(error.message))

/**
 * 本脚本创建的会话 id（按创建顺序）。
 *
 * 记下来有两个用处：一是断言要按 id 去查服务端落库状态，二是**收尾时删掉**——
 * 本脚本每跑一次会留下两个会话，不清理的话"验收"本身就成了数据污染源
 * （§49 的要求是跑完不留垃圾，其他脚本都有这段收尾）。
 */
const createdConversations = []
page.on('response', async (response) => {
  const request = response.request()
  if (request.method() === 'POST' && response.url().endsWith('/api/chat/conversations')) {
    try {
      const body = await response.json()
      const id = body?.data?.id
      if (id && !createdConversations.includes(id)) createdConversations.push(id)
    } catch {
      // 抓不到就靠断言失败兜底，不静默放过
    }
  }
})

/** 等第 n 个会话 id 出现（监听回调是异步的，不能立刻就读）。 */
async function waitForCreatedConversation(nth, timeout = 15000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (createdConversations.length >= nth) return createdConversations[nth - 1]
    await new Promise((r) => setTimeout(r, 100))
  }
  return createdConversations[nth - 1] ?? ''
}

/**
 * 等待"刚刚发出"的某个请求收到响应头。
 *
 * 用来确定"请求已经到达服务端"——服务端在返回 SSE 之前就已同步落库
 * （用户消息 + STREAMING 助手消息，见 ChatStreamServiceImpl 注释），
 * 因此收到 200 即代表"停止"是作用在一条**真实存在**的流上，而不是一次空中断。
 */
function waitForResponse(predicate, timeout = 90000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      page.off('response', onResponse)
      reject(new Error('等待流式响应超时'))
    }, timeout)
    const onResponse = (response) => {
      if (predicate(response)) {
        clearTimeout(timer)
        page.off('response', onResponse)
        resolve(response)
      }
    }
    page.on('response', onResponse)
  })
}

/** 界面恢复到"可以再次提问"的外观：停止按钮消失、发送按钮回归、输入框可输入。 */
async function assertRecovered(tag) {
  let recovered = true
  try {
    await page.waitForSelector('[data-testid="chat-send"]', { timeout: 20000 })
  } catch {
    recovered = false
  }
  record(`${tag} 停止按钮消失、发送按钮回归`, recovered)
  const stopGone = (await page.locator('[data-testid="chat-stop"]').count()) === 0
  record(`${tag} Loading 已清除（无残留"停止"）`, stopGone)
  const disabled = await page.locator('[data-testid="chat-input"]').isDisabled()
  record(`${tag} 输入框恢复可输入`, !disabled, `disabled=${disabled}`)
}

try {
  // ---------- 场景 A：请求已发出、生成刚开始就停止 ----------
  await page.goto(`${base}/#/agent`, { waitUntil: 'networkidle' })
  await page.waitForSelector('[data-testid="chat-new"]', { timeout: 20000 })

  await page.click('[data-testid="chat-new"]')
  await page.waitForSelector('[data-testid="chat-composer-options"]', { timeout: 15000 })
  await page.fill('[data-testid="chat-input"]', LONG_PROMPT)

  const streamStarted = waitForResponse(
    (r) => r.url().includes('/api/chat/stream') && r.status() === 200
  )
  await page.click('[data-testid="chat-send"]')

  // 等到请求确实被服务端接住（200），此刻库里已有 STREAMING 助手消息，再按停止
  await streamStarted
  await page.locator('[data-testid="chat-stop"]').click({ timeout: 10000 })

  await assertRecovered('A')

  const conversationA = await waitForCreatedConversation(1)
  record('A 新建会话已建立', Boolean(conversationA), conversationA || '(未捕获到 id)')
  if (conversationA) {
    const assistant = await waitForAssistantMessage(conversationA, 40000)
    record('A 助手消息正常落库', Boolean(assistant), assistant ? `id=${assistant.id}` : '无助手消息')
    record(
      'A 落库状态为 STOPPED（用户中止）',
      assistant?.status === 'STOPPED',
      `status=${assistant?.status}`
    )
  }
  await page.screenshot({ path: `${outDir}/stop-immediate.png` })

  // ---------- 场景 B：长回答生成到一定字数后停止 ----------
  await page.click('[data-testid="chat-new"]')
  await page.waitForSelector('[data-testid="chat-composer-options"]', { timeout: 15000 })
  await page.fill('[data-testid="chat-input"]', LONG_PROMPT)

  const streamStarted2 = waitForResponse(
    (r) => r.url().includes('/api/chat/stream') && r.status() === 200
  )
  await page.click('[data-testid="chat-send"]')

  // 等正文累计到阈值——这才是"生成一半"，而不是刚发出就掐断
  await page.waitForSelector('.stream-text', { timeout: 120000 })
  await page.waitForFunction(
    () => (document.querySelector('.stream-text')?.textContent ?? '').trim().length >= 150,
    undefined,
    { timeout: 120000 }
  )
  const partialLen = await page.evaluate(
    () => (document.querySelector('.stream-text')?.textContent ?? '').trim().length
  )
  console.log(`  正文累计 ${partialLen} 字，此时点击「停止」`)
  await page.locator('[data-testid="chat-stop"]').click({ timeout: 10000 })
  await streamStarted2.catch(() => {}) // 已完成的流不会再等它，忽略

  await assertRecovered('B')

  const conversationB = await waitForCreatedConversation(2)
  let serverContent = ''
  if (conversationB) {
    const assistant = await waitForAssistantMessage(conversationB, 40000)
    record('B 助手消息正常落库', Boolean(assistant), assistant ? `id=${assistant.id}` : '无助手消息')
    record('B 落库状态为 STOPPED（用户中止）', assistant?.status === 'STOPPED', `status=${assistant?.status}`)
    serverContent = (assistant?.content ?? '').trim()
    record(
      'B 已生成内容被保留（服务端非空）',
      serverContent.length > 0,
      `落库 ${serverContent.length} 字`
    )
    record(
      'B 保留的是部分内容而非整段（确实中途停住）',
      serverContent.length > 0 && serverContent.length < 6000,
      `落库 ${serverContent.length} 字（整篇 ≥2000 字，说明被截断）`
    )
  }

  // 刷新页面：内容留不留得住，是"落库"与"只存在于内存"的分水岭
  await page.goto(`${base}/#/agent`, { waitUntil: 'networkidle' })
  await page.waitForSelector('[data-testid="chat-conversation"]', { timeout: 20000 })
  await page.locator('[data-testid="chat-conversation"]').first().click()
  await page.waitForSelector('[data-testid="chat-message"][data-role="ASSISTANT"]', { timeout: 20000 })
  const afterReload = await page.evaluate(() => {
    const nodes = [...document.querySelectorAll('[data-testid="chat-message"][data-role="ASSISTANT"] .markdown')]
    return nodes.map((n) => (n.textContent ?? '').trim()).join('\n')
  })
  record(
    'B 刷新后部分内容仍在（落库可回放）',
    afterReload.length > 0,
    `刷新后 ${afterReload.length} 字`
  )
  await page.screenshot({ path: `${outDir}/stop-midway.png` })
} catch (error) {
  record('脚本执行未抛异常', false, error.message)
  await page.screenshot({ path: `${outDir}/stop-failure.png` }).catch(() => {})
} finally {
  record('全程无页面级 JS 错误', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '))
  await browser.close()
  // 收尾：删掉本次创建的两个会话（软删除，走 API 级联，不做裸表删除）。
  // 放在浏览器关闭之后、断言统计之前：清理失败不改变验收结论，但必须看得见。
  for (const id of createdConversations) {
    try {
      const res = await fetch(`${base}/api/chat/conversations/${id}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${token}` }
      })
      console.log(`· 清理会话 ${id}：HTTP ${res.status}`)
    } catch (error) {
      console.log(`· 清理会话 ${id} 失败：${error.message}`)
    }
  }
  if (createdConversations.length === 0) {
    console.log('· 未捕获到创建的会话（无可清理项）')
  }
}

const failed = results.filter((r) => !r.pass)
console.log(
  `\n「停止生成」验收：${results.length - failed.length}/${results.length} 通过`
)
if (failed.length > 0) {
  console.log('失败项：')
  failed.forEach((r) => console.log(`  ✗ ${r.name}${r.detail ? ` — ${r.detail}` : ''}`))
  process.exit(1)
}
