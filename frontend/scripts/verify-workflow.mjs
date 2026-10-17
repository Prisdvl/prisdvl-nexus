// 工作流设计器专项验证（真实后端）：创建 → 加节点 → 连线 → 保存 → 校验 → 发布 → 运行 → 轨迹
//
// 前置（⚠️ 本轮未执行，脚本已按新契约改写但标记 NOT VERIFIED）：
//   1) 后端在 8080（可用零外部依赖形态：java -jar nexus-app/target/nexus-app-0.0.1-SNAPSHOT.jar --spring.profiles.active=standalone）
//   2) 前端 dev server 在 5175（cd frontend && npm run dev），/api 由 Vite 代理到 8080
//   3) 一个可用账号。注意：注册接口已强制要求 email（RegisterRequest:30-33），
//      且注册后需邮箱验证（验证码默认走 console 通道、打印在后端日志里），
//      因此 scripts/seed-demo.mjs 与 scripts/smoke.ps1 目前的注册调用（不含 email）已过期，不能用来造这个账号。
//      用 NEXUS_USER / NEXUS_PASS 传入一个已创建的账号。
//
// 用例图刻意只用 START → TEMPLATE → END：TEMPLATE 只做模板渲染，不调模型，
// 所以在没有模型 API Key 的环境里也能真正跑通一次 SUCCEEDED。
import { chromium } from 'playwright-core'

const BASE = process.env.NEXUS_WEB ?? 'http://localhost:5175'
const USER = process.env.NEXUS_USER ?? 'qa-runner'
const PASS = process.env.NEXUS_PASS ?? 'qa-runner-password'
const WF_NAME = `qa-wf-${Date.now()}`

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.addInitScript(() => localStorage.setItem('nx-proto-tour-done', '1'))
const issues = []
const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`)
  if (!ok) issues.push(name)
}
/* 故意制造的失败运行 URL：本机没配对话模型，LLM 节点报 4006，
   而 ErrorCodeHttpStatusMapper 把 4006 映射成 502 BAD_GATEWAY（上游=模型，错在配置不在客户端）。
   这是既定契约，所以对该 URL 的 502 不是问题，而是要断言的事实。 */
let expectedFailRunUrl = ''
let observedFailRunStatus = 0

page.on('pageerror', (e) => issues.push(`pageerror: ${String(e.message).slice(0, 160)}`))
page.on('console', (m) => {
  if (m.type() !== 'error') return
  const url = m.location?.()?.url ?? ''
  if (expectedFailRunUrl && url.startsWith(expectedFailRunUrl)) return
  // 带上资源 URL：否则 "502 Bad Gateway" 这类报错看不出是哪个请求
  issues.push(`console.error: ${m.text().slice(0, 140)}${url ? ` @ ${url}` : ''}`)
})
// 网关类状态在本环境只应出现在"故意失败的那次运行"上，其余一律算问题
page.on('response', (r) => {
  const st = r.status()
  if (st !== 502 && st !== 503 && st !== 504) return
  if (expectedFailRunUrl && r.url().startsWith(expectedFailRunUrl)) {
    observedFailRunStatus = st
    return
  }
  issues.push(`gateway ${st} ${r.url()}`)
})

const nodeCount = () => page.locator('.wf-node').count()
const edgeCount = () => page.locator('.wf-edge').count()
/** 顶栏按钮在 busy 时会被禁用，点下一个之前必须等它恢复。 */
const clickHead = async (name) => {
  const btn = page.getByRole('button', { name, exact: true })
  await btn.waitFor({ state: 'visible' })
  await page.waitForFunction(
    (label) => {
      const el = [...document.querySelectorAll('.wf-head-actions button')]
        .find((b) => (b.textContent ?? '').trim() === label)
      return !!el && !el.disabled
    },
    name,
    { timeout: 30000 },
  )
  await btn.click()
}
/** 选中某节点（按画布上的 id 文本）。 */
const selectNode = async (id) => {
  await page.locator('.wf-node', { hasText: id }).first().click()
  await page.waitForTimeout(120)
}
/** 「连线」后点目标节点。 */
const link = async (fromId, toId, handle) => {
  await selectNode(fromId)
  const label = handle === 'true' ? '连 true' : handle === 'false' ? '连 false' : '连线'
  await page.locator('.wf-inspector .wf-actions button', { hasText: label }).first().click()
  await selectNode(toId)
}

// ---- 登录 ----
await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle' })
await page.waitForSelector('.auth-card', { timeout: 15000 })
await page.locator('#li-u').fill(USER)
await page.locator('#li-p').fill(PASS)
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
const loggedIn = await page.waitForSelector('.shell', { timeout: 12000 }).then(() => true).catch(() => false)
if (!loggedIn) {
  console.log(`ABORT 登录失败：请先按脚本头部说明准备账号 ${USER}（注册需 email + 邮箱验证，旧 seed 脚本已过期）`)
  await browser.close()
  process.exit(2)
}

await page.goto(`${BASE}/#/app/workflows`, { waitUntil: 'networkidle' })
await page.waitForTimeout(900)

check('工作流页标题', (await page.locator('.page-title').first().innerText()).includes('工作流'))

// ---- 创建：后端建图后画布应出现 START → END 种子图 ----
// 注意：没有任何工作流时页面只显示空状态（不渲染三栏），
// 所以「面板 7 类」这条必须在创建/打开之后断言，否则读到 0。
await page.locator('.wf-bar__new').fill(WF_NAME)
await page.locator('.wf-bar button', { hasText: '新建' }).click()
// 必须等「新建的那个工作流」真正成为选中项：页面会先自动打开最近更新的工作流，
// 若只等节点数 >= 2，旧图就已经满足条件，断言会读到旧图的节点数（实测踩过）。
await page.waitForFunction(
  (name) => {
    const sel = document.querySelector('.wf-bar__select')
    if (!sel) return false
    const opt = [...sel.options].find((o) => (o.textContent ?? '') === name)
    return !!opt && sel.value === opt.value
  },
  WF_NAME,
  { timeout: 20000 },
)
await page.waitForTimeout(300)
check('创建后画布有种子节点', (await nodeCount()) === 2, `nodes=${await nodeCount()}`)
check('种子图有 START → END 连线', (await edgeCount()) === 1, `edges=${await edgeCount()}`)
check('选择栏出现新工作流', (await page.locator('.wf-bar__select').inputValue()).length > 0)
check('节点面板 7 类（后端 TYPES）', (await page.locator('.wf-palette-item').count()) === 7)
// 运行历史来自真实接口：还没跑过时应为空态，不能有残留行
check('新工作流运行历史为空', (await page.locator('.wf-run-row').count()) === 0, `rows=${await page.locator('.wf-run-row').count()}`)
// 当前工作流 id（选择栏的值就是它），后面既用于断言失败运行的 URL，也用于补足分页条数
const wid = await page.locator('.wf-bar__select').inputValue()
expectedFailRunUrl = `${BASE}/api/workflows/${wid}/runs`

// ---- 检查器：节点无 name 字段，只读 id + 类型 ----
await selectNode('start')
check('检查器显示只读节点 ID', (await page.locator('.wf-inspector #wf-node-id').count()) === 1)
check('不再伪造节点名称字段', (await page.locator('.wf-inspector #wf-name').count()) === 0)

// ---- 加一个 LLM 节点：应出现提示词输入，然后删掉 ----
const beforeAdd = await nodeCount()
await page.locator('.wf-palette-item', { hasText: '模型' }).first().click()
await page.waitForTimeout(200)
check('可从面板添加节点', (await nodeCount()) === beforeAdd + 1)
check('LLM 节点有提示词配置', (await page.locator('.wf-inspector #wf-prompt').count()) === 1)
await page.locator('.wf-inspector .wf-actions button', { hasText: '删除节点' }).click()
await page.waitForTimeout(200)
check('删除节点回到原数量', (await nodeCount()) === beforeAdd)

// ---- 加一个 TEMPLATE 节点并连成 START → TEMPLATE → END（可无模型跑通的图）----
await page.locator('.wf-palette-item', { hasText: '模板' }).first().click()
await page.waitForTimeout(200)
const tplId = await page.locator('.wf-node').nth(2).innerText().then((t) => t.split('\n')[0].trim())
await selectNode(tplId)
await page.locator('.wf-inspector input#wf-tpl, .wf-inspector textarea#wf-tpl').fill('qa: {{ input.question }}')
await page.waitForTimeout(150)
await link('start', tplId)
await link(tplId, 'end')
check('连线后画布有 3 条边', (await edgeCount()) === 3, `edges=${await edgeCount()}`)

// ---- 保存 → 校验 → 发布 ----
await clickHead('保存草稿')
await page.waitForFunction(
  () => [...document.querySelectorAll('.wf-bar .wf-chip')].some((c) => (c.textContent ?? '').includes('已保存')),
  null,
  { timeout: 20000 },
)
check('保存后回到已保存状态', true)

await clickHead('校验')
await page.waitForFunction(() => !!document.querySelector('.wf-ok, .wf-errors'), null, { timeout: 20000 })
const validOk = await page.locator('.wf-ok').count()
check('校验通过（图可达、无环、配置齐全）', validOk === 1, validOk ? '' : `errors=${await page.locator('.wf-errors').innerText().catch(() => '')}`)

await clickHead('发布')
await page.waitForFunction(
  () => [...document.querySelectorAll('.wf-bar .wf-chip')].some((c) => (c.textContent ?? '').includes('已发布')),
  null,
  { timeout: 20000 },
)
check('发布后状态为已发布', true)

// ---- 正式运行（已发布版本）→ 轨迹 ----
await page.locator('.wf-panel #wf-inputs').fill('{ "question": "qa" }')
await clickHead('正式运行')
await page.waitForFunction(
  () => document.querySelectorAll('.wf-log li').length >= 3,
  null,
  { timeout: 60000 },
).catch(() => {})
const traceRows = await page.locator('.wf-log li').count()
check('运行写入节点轨迹（START/TEMPLATE/END）', traceRows >= 3, `rows=${traceRows}`)
const allDone = await page.locator('.wf-log li[data-state="done"]').count()
check('运行结果为成功', allDone >= 3, `done=${allDone}`)

// ---- 运行历史：真实接口列出、分页、可选中并切换轨迹 ----
const runsFirst = await page.locator('.wf-run-row').count()
check('运行历史列出本次运行', runsFirst >= 1, `rows=${runsFirst}`)
check('历史行显示成功状态', (await page.locator('.wf-run-row .wf-chip').first().innerText()).includes('成功'))
check('分页：第一页上一页禁用', await page.locator('.wf-run-page button', { hasText: '上一页' }).isDisabled())
check('分页：显示当前页/总页数', /1\s*\/\s*\d+/.test(await page.locator('.wf-run-page__now').innerText()))

// 再运行一次（走试跑草稿），历史应累积；间隔 >1s 让两行的时间戳可区分
await page.waitForTimeout(1500)
await clickHead('试跑草稿')
await page
  .waitForFunction(() => document.querySelectorAll('.wf-run-row').length >= 2, null, { timeout: 60000 })
  .catch(() => {})
const runsSecond = await page.locator('.wf-run-row').count()
check('再运行一次后历史累积', runsSecond >= 2, `rows=${runsSecond}`)

// 选中较早的那条：轨迹面板头部应换成为那一次运行的时间戳（证明读的是它自己的轨迹）
const olderRow = page.locator('.wf-run-row').nth(runsSecond - 1)
const olderTime = (await olderRow.locator('.wf-run-row__time').innerText()).trim()
await olderRow.click()
const tracePanel = page.locator('.wf-panel').filter({ has: page.locator('.wf-log') })
await page
  .waitForFunction(
    (t) => {
      const panels = [...document.querySelectorAll('.wf-panel')]
      const panel = panels.find((p) => p.querySelector('.wf-log'))
      const meta = panel?.querySelector('.wf-run-meta')
      return !!meta && (meta.textContent ?? '').includes(t)
    },
    olderTime,
    { timeout: 20000 },
  )
  .catch(() => {})
const panelMeta = (await tracePanel.locator('.wf-run-meta').innerText().catch(() => '')).replace(/\s+/g, ' ')
check('点历史记录切换为该次运行的轨迹', panelMeta.includes(olderTime), `row=${olderTime} panel=${panelMeta}`)
check('选中的历史行有选中态', (await olderRow.getAttribute('data-on')) !== null)

// ---- 失败运行：加 LLM 节点（本机未配任何对话模型）→ 运行必失败，但仍要能查历史、读轨迹 ----
await page.locator('.wf-palette-item', { hasText: '模型' }).first().click()
await page.waitForTimeout(200)
const llmId = await page.locator('.wf-node').nth(3).innerText().then((t) => t.split('\n')[0].trim())
await link(tplId, llmId)
await link(llmId, 'end')
await clickHead('保存草稿')
await clickHead('发布')
await page.waitForFunction(
  () => [...document.querySelectorAll('.wf-bar .wf-chip')].some((c) => (c.textContent ?? '').includes('已发布')),
  null,
  { timeout: 20000 },
)
await page.waitForTimeout(1200)
await clickHead('正式运行')
// 请求会失败（错误体里没有 runId），但失败运行已落库：历史应刷新出「失败」行并自动选中
await page
  .waitForFunction(
    () => [...document.querySelectorAll('.wf-run-row .wf-chip')].some((c) => (c.textContent ?? '').includes('失败')),
    null,
    { timeout: 60000 },
  )
  .catch(() => {})
check('失败运行出现在历史里', (await page.locator('.wf-run-row .wf-chip', { hasText: '失败' }).count()) >= 1)
check('失败运行的 HTTP 状态为 502（4006 → BAD_GATEWAY 既定映射）', observedFailRunStatus === 502, `status=${observedFailRunStatus}`)
check('请求失败时给出错误与说明', (await page.locator('.wf-errors__single').count()) >= 1)
const autoSelected = await page.locator('.wf-run-row[data-on] .wf-chip').first().innerText().catch(() => '')
check('失败后自动选中最新的那条运行', autoSelected.includes('失败'), `selected=${autoSelected}`)
await page
  .waitForFunction(() => {
    const panel = [...document.querySelectorAll('.wf-panel')].find((p) => p.querySelector('.wf-log'))
    return !!panel && [...panel.querySelectorAll('.wf-log li')].some((li) => li.dataset.state === 'failed')
  }, null, { timeout: 20000 })
  .catch(() => {})
check('失败轨迹里能看到失败节点', (await page.locator('.wf-log li[data-state="failed"]').count()) >= 1)
const failErr = (await page.locator('.wf-log__err').first().innerText().catch(() => '')).trim()
check('失败节点带错误消息', failErr.length > 0, `err=${failErr.slice(0, 48)}`)

// ---- 分页：用真实凭据在 Node 侧直接调 API 补足条数（>1 页），再验证翻页 ----
const auth = await (await fetch(`${BASE}/api/auth/login`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: USER, password: PASS }),
})).json()
for (let i = 0; i < 8; i += 1) {
  // 这几条会失败（图里有 LLM），这里刻意不看结果：目的只是把历史补到 11 条以产生第 2 页
  await fetch(`${BASE}/api/workflows/${wid}/runs`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${auth.data.accessToken}` },
    body: JSON.stringify({ inputs: { question: `seed-${i}` } }),
  })
}
await page.reload({ waitUntil: 'networkidle' })
await page.waitForFunction(() => document.querySelectorAll('.wf-run-row').length > 0, null, { timeout: 30000 })
const page1Rows = await page.locator('.wf-run-row').count()
check('分页：第 1 页满 10 条', page1Rows === 10, `rows=${page1Rows}`)
const now1 = (await page.locator('.wf-run-page__now').innerText()).trim()
check('分页：显示第 1 / 2 页', /^1\s*\/\s*2$/.test(now1), now1)
check('分页：首页「上一页」禁用', await page.locator('.wf-run-page button', { hasText: '上一页' }).isDisabled())
await page.locator('.wf-run-page button', { hasText: '下一页' }).click()
await page
  .waitForFunction(() => (document.querySelector('.wf-run-page__now')?.textContent ?? '').trim().startsWith('2'), null, { timeout: 20000 })
  .catch(() => {})
const page2Rows = await page.locator('.wf-run-row').count()
check('分页：第 2 页只剩余下条数', page2Rows >= 1 && page2Rows < 10, `rows=${page2Rows}`)
check('分页：末页「下一页」禁用', await page.locator('.wf-run-page button', { hasText: '下一页' }).isDisabled())

// ---- 手机端不横向溢出 ----
await page.setViewportSize({ width: 390, height: 844 })
await page.waitForTimeout(600)
check('手机端工作流无横向溢出', await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1))

console.log(issues.length === 0 ? 'ALL OK' : `ISSUES: ${issues.join(' | ')}`)
await browser.close()
process.exit(issues.length ? 1 : 0)
