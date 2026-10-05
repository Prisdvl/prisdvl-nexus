/**
 * 零知识库的新用户提问（Phase 4.4）：用户报的那一幕，在浏览器里原样跑一遍。
 *
 * 为什么单开一个脚本：`verify-agent-chat.mjs` 与 `verify-agents.mjs` 都用 demo 账号，
 * 而 demo 有知识库——**"一个库都没有的新用户会看到什么"这条分支，那两个脚本都覆盖不到**。
 * 而这就是用户报的那一幕（新账号提问 → "没有找到相关资料，无法回答"）。
 * 接口级的证据（策略表、工具调用次数）在 docs/decisions.md B.41 第 11 条；本脚本补的是界面层：
 * 用户在浏览器里，先看到一句预期说明，再拿到一个注明"不是来自你的笔记"的回答，而不是一句拒绝。
 *
 * 覆盖：
 *   ① 新账号确实一个知识库都没有（**先自证前置条件**，否则后面那句话可能是为别的理由出现的）
 *   ② 提问前输入框上方的提示（KB_PREFERRED 那一支的措辞）
 *   ③ 提问后：工具痕迹在（摘要写明"没有知识库，0 条结果"）、回答自述不是来自笔记、旧拒绝措辞消失
 *   ④ 刷新后痕迹摘要仍在（"随消息落库"这句话得自己验，不能顺手写上）
 *   ⑤ KB_ONLY 那一支的提示语（同一个 v-if 里的另一句话，跑了默认策略不等于它也被验过）
 *
 * 退出码：0 = 全部通过；1 = 有真实失败；2 = 有跳过（本机没有可用模型，提问那条腿跑不了）。
 *
 * 成本（如实写明）：没有删号接口，所以每次运行会留下一个 `probe-nokb-*` 账号。
 * 这不是残留 bug——是这条腿必须用"零库的新账号"才能跑所付的代价。
 * 但**会话不再留下**：finally 里会删掉本脚本在该账号下建的全部会话（见 cleanupOwnConversations），
 * 账号本身则交给 `scripts/cleanup-test-data.ps1` 按 `probe` 前缀统一回收。
 * 账号级的残留始终与 demo 账号的数据隔离（demo 的截图与断言不受影响）。
 */
import { chromium } from 'playwright-core'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const api = process.env.API_BASE ?? 'http://localhost:8088'
const base = process.env.WEB_BASE ?? 'http://localhost:8088'
const here = dirname(fileURLToPath(import.meta.url))
/**
 * 截图目录按**脚本自己的位置**解析，不按 cwd——本脚本第一版写成相对路径 `docs/screenshots`，
 * 从 `web/` 下运行就写进了 `web/docs/screenshots/`（仓库里凭空多一个 web/docs）。
 * 同一目录下其它脚本都是这么做的；这次是漏了，补上。
 */
const outDir = resolve(here, '../../docs/screenshots')
/** 用用户报的那句话，不加改写：这条腿要先能复现他看到的场景 */
const question = process.env.AGENT_QUESTION ?? '什么是缓存击穿？该怎么解决？'

const stamp = Date.now().toString(36)
const username = `probe-nokb-${stamp}`
const password = 'Probe@12345'

const failures = []
const skipped = []

async function post(path, body, token) {
  const response = await fetch(`${api}/api${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(body)
  })
  return response.json()
}

let token = ''
/** 第 ⑤ 步选用的那个 KB_ONLY Agent 的名字（在准备阶段定下，页面加载前就已存在） */
let kbOnlyName = ''
let page
try {
  const registered = await post('/auth/register', { username, password })
  if (registered.code !== 0) throw new Error(`注册失败：${registered.code} ${registered.message}`)
  const login = await post('/auth/login', { username, password })
  token = login.data?.accessToken
  if (!token) throw new Error(`登录没有拿到令牌：${JSON.stringify(login).slice(0, 200)}`)
  console.log(`① 已注册零知识库账号 ${username}（uid=${login.data?.user?.id ?? '?'}）`)

  // 前置条件：这个账号的知识库数必须是 0。这一条不是形式主义——若因为别的原因
  // （例如知识库接口报错、或账号其实继承了数据）导致界面显示提示，后面的断言全都会被满足
  const kbs = await fetch(`${api}/api/knowledge-bases?pageNum=1&pageSize=50`, {
    headers: { Authorization: `Bearer ${token}` }
  }).then((response) => response.json())
  const kbCount = (kbs.data?.list ?? []).length
  console.log(`   该账号知识库数：${kbCount}`)
  if (kbs.code !== 0 || kbCount !== 0) {
    failures.push(`前置条件不成立：新账号的知识库数应为 0（实际 ${kbCount}，code=${kbs.code}）`)
  }

  // 第 ⑤ 步要用的 KB_ONLY Agent：**必须在这里建**，不能等进了页面再建——
  // 对话页的 Agent 列表只在挂载时拉一次，页内新建的 Agent 在那个列表里根本不存在，
  // 之前那版就栽在这里（下拉里等一个永远不会出现的选项，超时）。
  kbOnlyName = `只依据笔记 ${stamp}`
  const created = await post(
    '/agents',
    {
      code: `probe-kb-only-${stamp}`,
      name: kbOnlyName,
      systemPrompt: '只依据检索到的资料回答。',
      answerPolicy: 'KB_ONLY'
    },
    token
  )
  const kbOnlyAgentId = created.data?.id
  if (!kbOnlyAgentId) {
    failures.push(`建 KB_ONLY Agent 失败：${created.code} ${created.message}`)
  } else {
    const published = await fetch(`${api}/api/agents/${kbOnlyAgentId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ status: 'PUBLISHED' })
    }).then((response) => response.json())
    if (published.data?.status !== 'PUBLISHED') {
      failures.push(`发布 KB_ONLY Agent 失败：${published.code} ${published.message}`)
    }
  }
} catch (error) {
  console.error('✗ 准备阶段失败：', error.message)
  process.exit(1)
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })

try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 980 } })
  await context.addInitScript(
    (value) => {
      localStorage.setItem('nexus.token', value.token)
      localStorage.setItem('nexus.username', value.username)
    },
    { token, username }
  )
  page = await context.newPage()

  // 流式失败的说明只在 ElMessage 上闪 3 秒，等断言超时后再去读 DOM 什么也读不到。
  // 所以从响应体里抓：这是本脚本能区分"环境没有模型"与"代码回归"的唯一可靠来源
  let backendRefusal = ''
  page.on('response', async (response) => {
    if (response.status() === 200) return
    if (!response.url().includes('/api/chat/stream')) return
    try {
      const text = await response.text()
      backendRefusal = text.split(/\s+/).join(' ').slice(0, 200)
    } catch {
      // 读不到就保持为空：宁可报失败，也不要凭空断言"环境不行"
    }
  })

  await page.goto(base, { waitUntil: 'networkidle' })
  await page.click('[data-testid="nav-chat"]')
  await page.waitForSelector('[data-testid="chat-composer-options"]', { timeout: 15000 })
  await page.click('[data-testid="chat-new"]')
  await page.waitForFunction(() => document.querySelectorAll('[data-testid="chat-message"]').length === 0, undefined, {
    timeout: 10000
  })

  // ---------- ② 提问前：把"会发生什么"说在用户点发送之前 ----------
  const hint = (await page.locator('.composer-hint').allInnerTexts()).join(' | ').replace(/\s+/g, ' ')
  console.log(`② 提问前的提示：${hint.slice(0, 100)}`)
  if (!hint.includes('还没有知识库')) {
    // 零库用户最需要的一句话：不说，他只会以为"检索坏了"
    failures.push('零知识库账号没有看到"还没有知识库"的提示')
  }
  if (!hint.includes('不是来自你的笔记')) {
    failures.push('提示没有说明回答会注明"不是来自你的笔记"（KB_PREFERRED 的承诺）')
  }
  if (hint.includes('尚未绑定知识库')) {
    failures.push('出现的是 RAG 那句警告：草稿的默认模式错了（应为 AGENT）')
  }
  await page.screenshot({ path: `${outDir}/agent-no-kb-hint.png` })
  console.log('✓ 已截图 docs/screenshots/agent-no-kb-hint.png')

  // ---------- ③ 提问：用户报的那一幕 ----------
  await page.fill('[data-testid="chat-input"]', question)
  await page.click('[data-testid="chat-send"]')
  const answered = await page
    .waitForSelector('[data-testid="chat-message"][data-role="ASSISTANT"] .markdown:not(.stream-text)', { timeout: 180000 })
    .then(() => true)
    .catch(() => false)
  if (!answered) {
    // 没有回答：是环境缺模型，还是产品坏了？只有后端原话能分得清
    if (/模型|4001|4004|4005|4006|429|超时/.test(backendRefusal)) {
      skipped.push(`提问腿：${backendRefusal || '（无后端原话）'}`)
    } else {
      failures.push(`提问后没有等到回答，后端原话：${backendRefusal || '（无）'}`)
    }
  } else {
    await page.waitForTimeout(800)
    const traces = (await page.locator('[data-testid="chat-message"][data-role="ASSISTANT"] .trace li').allInnerTexts()).map((text) =>
      text.replace(/\s+/g, ' ').trim()
    )
    const answer = (await page
      .locator('[data-testid="chat-message"][data-role="ASSISTANT"] .markdown:not(.stream-text)')
      .last()
      .innerText()).replace(/\s+/g, ' ').trim()
    console.log(`③ 工具痕迹 ${traces.length} 条：`)
    traces.forEach((trace) => console.log(`   ${trace}`))
    console.log(`   回答（前 120 字）：${answer.slice(0, 120)}`)

    if (traces.length === 0) {
      // 规则 1 要求先检索：没有工具痕迹说明这条链路根本没走知识库，"零库也能答"就无从谈起
      failures.push('回答没有经过任何工具调用（检索链路没跑通）')
    }
    if (!traces.some((trace) => trace.includes('没有知识库'))) {
      // "为什么没检索到"必须由工具如实说出来：这是用户在界面上唯一的可见证据
      failures.push(`工具痕迹没有说明库里是空的（摘要：${traces.join(' / ') || '无'}）`)
    }
    if (/没有找到相关资料|不能凭自己的知识|不能用自己的知识/.test(answer)) {
      failures.push(`回答仍是旧的拒绝措辞：${answer.slice(0, 120)}`)
    }
    if (!/不是来自(你的|您的)笔记/.test(answer)) {
      failures.push(`回答没有注明"不是来自你的笔记"：${answer.slice(0, 120)}`)
    }
    if (!/0\s*条结果/.test(traces.join(' '))) {
      failures.push('工具摘要里没有"0 条结果"（未命中这件事没有被说出来）')
    }
    await page.screenshot({ path: `${outDir}/agent-no-kb-answer.png` })
    console.log('✓ 已截图 docs/screenshots/agent-no-kb-answer.png')

    // ---------- ④ 刷新后痕迹还在 ----------
    await page.reload({ waitUntil: 'networkidle' })
    await page.waitForSelector('[data-testid="chat-message"][data-role="ASSISTANT"] .trace-result', { timeout: 30000 })
    const afterReload = (await page.locator('[data-testid="chat-message"][data-role="ASSISTANT"] .trace-result').allInnerTexts()).map(
      (text) => text.replace(/\s+/g, ' ').trim()
    )
    console.log(`④ 刷新后痕迹摘要：${JSON.stringify(afterReload)}`)
    if (!afterReload.some((text) => text.includes('没有知识库'))) {
      failures.push('刷新后"没有知识库"的摘要不见了（痕迹只存在于内存？）')
    }

    // ---------- ⑤ KB_ONLY 那一支提示 ----------
    // 上面验的是默认策略（内置 Agent = KB_PREFERRED）。KB_ONLY 的提示是**另一句话**，
    // 写在同一个 v-if 分支里，跑了其中一支不等于另一支也跑过。
    // 那个 Agent 是在**进页面之前**就建好的（见准备阶段）：页内新建的 Agent 不在已拉取的列表里
    if (kbOnlyName) {
      await page.click('[data-testid="chat-new"]')
      await page.waitForSelector('[data-testid="chat-draft-agent-select"]', { timeout: 15000 })
      await page.click('[data-testid="chat-draft-agent-select"]')
      const agentOption = page
        .locator('.chat-draft-agent-select-popper .el-select-dropdown__item')
        .filter({ hasText: kbOnlyName })
        .first()
      await agentOption.waitFor({ timeout: 15000 })
      await agentOption.click()
      await page.waitForTimeout(500)
      const kbOnlyHint = (await page.locator('.composer-hint').allInnerTexts())
        .join(' | ')
        .replace(/\s+/g, ' ')
      console.log(`⑤ 选中 KB_ONLY Agent 后的提示：${kbOnlyHint.slice(0, 120)}`)
      if (!kbOnlyHint.includes('只依据笔记')) {
        failures.push(`KB_ONLY 的提示没有出现（实际：${kbOnlyHint.slice(0, 120) || '无'}）`)
      }
      if (kbOnlyHint.includes('会用自己的知识回答')) {
        // 两句提示混在一起 = 界面在同时承诺两件相反的事
        failures.push('KB_ONLY 下仍显示"会用自身知识回答"（策略没接到提示上）')
      }
      await page.screenshot({ path: `${outDir}/agent-no-kb-hint-kb-only.png` })
      console.log('✓ 已截图 docs/screenshots/agent-no-kb-hint-kb-only.png')
    }
  }
} catch (error) {
  failures.push(error.message)
  console.error('✗ 验证失败：', error.message)
  await page?.screenshot({ path: `${outDir}/agent-no-kb-failure.png` }).catch(() => {})
} finally {
  await browser.close()
  await cleanupOwnConversations()
}

/**
 * 收尾：删掉本脚本在这个一次性账号下建的会话。
 *
 * 此前这里只有 `browser.close()`，会话就留在了 `probe-nokb-*` 账号名下（脚本头部原话是
 * "这是这条腿必须付的代价"）。实际上**会话是能自清的**——真正删不掉的是账号本身
 * （系统没有删号接口），那部分由 `scripts/cleanup-test-data.ps1` 按 `probe` 前缀统一回收。
 *
 * 分页响应的字段名这里取了多种可能：宁可多写一行判断，也不要因为后端换了个包装键
 * 就让收尾静默失效——"看起来清理了、实际没清"比不清理更难发现。
 */
async function cleanupOwnConversations() {
  if (!token) {
    return
  }
  try {
    const response = await fetch(`${api}/api/chat/conversations?pageNum=1&pageSize=100`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    const body = await response.json().catch(() => null)
    const records = body?.data?.records ?? body?.data?.list ?? body?.data?.items ?? []
    if (!Array.isArray(records) || records.length === 0) {
      return
    }
    for (const conversation of records) {
      await fetch(`${api}/api/chat/conversations/${conversation.id}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${token}` },
      }).catch(() => {})
    }
    console.log(`[cleanup] 已删除本脚本在该账号下建的 ${records.length} 个会话；账号残留交给 cleanup-test-data`)
  } catch (error) {
    console.warn('[cleanup] 会话收尾失败（不影响本次结论）：', error.message)
  }
}

if (failures.length > 0) {
  console.error('失败项：' + failures.join('; '))
  process.exit(1)
}
if (skipped.length > 0) {
  console.error('跳过项：' + skipped.join('; '))
  process.exit(2)
}
console.log(
  '\n零知识库新用户验证通过：说清了会发生什么（两种策略各一句）→ 检索如实报空 → ' +
    '回答注明不是来自笔记 → 痕迹刷新后仍在。'
)
