// 路由专项 QA：URL 变化 / 刷新保持 / 深链 / 未登录守卫 / 登录回到来源页
import { chromium } from 'playwright-core'

const BASE = 'http://localhost:5175'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  // 引导只在真实首登时出现，避免污染用例/截图
  await page.addInitScript(() => {
    localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
    /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
    localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
  })
const issues = []
page.on('pageerror', (e) => issues.push(`pageerror: ${String(e.message).slice(0, 140)}`))
page.on('console', (m) => { if (m.type() === 'error') issues.push(`console.error: ${m.text().slice(0, 140)}`) })

const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`)
  if (!ok) issues.push(name)
}
const url = () => page.evaluate(() => location.hash)
const login = async (from = '/#/login') => {
  await page.goto(BASE + from, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)
  if ((await page.locator('.auth-card').count()) === 0) return
  await page.locator('#li-u').fill('qa-runner')
  await page.locator('#li-p').fill('qa-runner-password')
  await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
  await page.waitForSelector('.shell', { timeout: 8000 })
  await page.waitForTimeout(1200)
}

/* ---------- 1. 未登录访问受保护页 → 重定向 /login 且记住来源 ---------- */
await page.goto(BASE + '/#/app/chat', { waitUntil: 'networkidle' })
await page.waitForTimeout(1500)
check('未登录访问 /app/chat 被重定向到 /login', (await url()).includes('/login'), await url())
check('登录页已渲染', (await page.locator('.auth-card').count()) === 1)

/* ---------- 2. 登录后回到来源页 ---------- */
await page.locator('#li-u').fill('qa-runner')
await page.locator('#li-p').fill('qa-runner-password')
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
await page.waitForSelector('.shell', { timeout: 8000 })
await page.waitForTimeout(1200)
check('登录后回到来源页 /app/chat', (await url()).includes('/app/chat'), await url())

/* ---------- 3. 侧栏切换会改 URL ---------- */
const nav = (l) => page.locator('.sb-nav button.rubber-segment__item', { hasText: l }).first()
await nav('知识库').click()
await page.waitForTimeout(800)
check('切到知识库时 URL 变化', (await url()).includes('/app/kb'), await url())
await nav('Agent').click()
await page.waitForTimeout(800)
check('切到 Agent 时 URL 变化', (await url()).includes('/app/agents'), await url())

/* ---------- 4. 刷新保持当前页 ---------- */
await page.goto('http://localhost:5175/#/app/account', { waitUntil: 'networkidle' })   // 「账号」导航项已移除，直接走路由
  await page.waitForTimeout(900)
await page.waitForTimeout(800)
await page.reload({ waitUntil: 'networkidle' })
await page.waitForTimeout(1800)
check('刷新后仍在 /app/account', (await url()).includes('/app/account'), await url())
check('刷新后仍是已登录（未被踢回登录页）', (await page.locator('.shell').count()) === 1)
check('刷新后账号页内容在', (await page.locator('.page-title').first().innerText()).includes('设置'))

/* ---------- 5. 深链：知识库详情 + 引用定位 ---------- */
/* 以前这里硬编码 http://localhost:8080 取库 ID：mock 模式下没有真后端，必然
 * ECONNREFUSED（AggregateError，就是这个脚本进不了基础 CI 的原因）。
 * 改成「从列表页点第一行进详情、再从 URL 取 ID」——真栈与 mock 两种模式都成立，
 * 而且库名与文档名都从页面上读，不绑死种子数据。 */
await page.goto(BASE + '/#/app/kb', { waitUntil: 'networkidle' })
await page.waitForTimeout(1400)
const firstRow = page.locator('.kb-table tbody tr').first()
let kbId = null
let kbName = ''
if ((await firstRow.count()) > 0) {
  const cellText = await firstRow.locator('.kb-cell').first().innerText().catch(() => '')
  kbName = (cellText || '').split('\n')[0].trim()
  await firstRow.click()
  await page.waitForTimeout(1400)
  const m = (await url()).match(/\/app\/kb\/([^/?#]+)/)
  if (m) kbId = m[1]
}
if (!kbId) {
  console.log('SKIP 深链：列表里没有可用的知识库')
} else {
  await page.waitForSelector('.doc-row', { timeout: 10000 }).catch(() => {})
  const docName = ((await page.locator('.doc-row').first().innerText().catch(() => '')) || '').split('\n')[0].trim()
  await page.goto(BASE + '/#/app/kb/' + kbId + (docName ? '?doc=' + encodeURIComponent(docName) : ''), { waitUntil: 'networkidle' })
  await page.waitForSelector('.kb-top__title-row', { timeout: 10000 })
  await page.waitForTimeout(1200)
  const detailTitle = await page.locator('.kb-top__title-row').first().innerText()
  check(
    '深链直达知识库详情',
    kbName ? detailTitle.includes(kbName) : detailTitle.trim().length > 0,
    kbName || detailTitle.replace(/\s+/g, ' ').slice(0, 40),
  )
  if (docName) {
    check('深链带上 doc 参数即高亮目标文档', (await page.locator('.doc-row[data-hl]').count()) > 0, docName)
  }

/* ---------- 6. 知识库详情 tab 为横向橡皮分段且无黑框残留 ---------- */
const kbTabs = await page.locator('.kb-top__tabs button.rubber-segment__item').count()
check('KB 详情 tab 是橡皮分段（4 项）', kbTabs === 4, `实际 ${kbTabs}`)
check('页面中已无 GooeyNav 黑框层', (await page.locator('.gooey-nav-container').count()) === 0)
await page.locator('.kb-top__tabs button.rubber-segment__item', { hasText: '问答' }).click()
/*
 * 等问答面板真的挂上再断言：切 tab 后内容是一次渲染的结果，runner 上比本机慢，
 * 不等就会把"还没渲染"读成"切 tab 没生效"（CI 上就是这样红的）。超时后照旧断言。
 */
/*
 * 等面板根节点 `.kb-qa` 出现：空态 starters 只在库里有文档时才渲染（roadmap #9 之后），
 * 拿它当"切成功"的证据在空库上必然落空——改成面板根节点 + tab 的 aria-pressed，
 * 与库里有没有文档无关（橡皮分段是 radio 语义，选中态在 aria-checked 而不是 aria-pressed）；失败时把观测值打出来，省得下次再猜。
 */
await page.locator(".kb-qa").first().waitFor({ state: "visible", timeout: 15000 }).catch(() => {})
const qaTab = page.locator(".kb-top__tabs button.rubber-segment__item", { hasText: "问答" })
const qaVisible = await page.locator(".kb-qa").first().isVisible().catch(() => false)
const qaPressed = await qaTab.getAttribute("aria-checked").catch(() => null)
const qaComposer = await page.locator(".kb-qa input, .kb-qa textarea").count()
const qaStarters = await page.locator(".kb-qa__starters").count()
check('切到问答 tab 生效', qaVisible && qaPressed === 'true', `visible=${qaVisible} pressed=${qaPressed} composer=${qaComposer} starters=${qaStarters}`)
}

/* ---------- 7. 浏览器前进/后退可用 ---------- */
await nav('工作台').click()
await page.waitForTimeout(700)
await page.goBack()
await page.waitForTimeout(900)
check('后退回到上一路由', (await url()).includes('/app/kb'), await url())
await page.goForward()
await page.waitForTimeout(900)
check('前进回到工作台', (await url()).includes('/app/workbench'), await url())

/* ---------- 8. 已登录访问 /login 会被送回工作台 ---------- */
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(1500)
check('已登录访问 /login 自动跳工作台', (await url()).includes('/app/workbench'), await url())

/* ---------- 9. 未知路由兜底 ---------- */
await page.goto(BASE + '/#/app/nope', { waitUntil: 'networkidle' })
await page.waitForTimeout(1400)
check('未知 /app/* 显示 404 页', (await page.locator('.page-title').first().innerText()).includes('没有页面'))

/* ---------- 10. 退出登录回 /login 且受保护页不可再进 ---------- */
await page.goto(BASE + '/#/app/workbench', { waitUntil: 'networkidle' })
await page.waitForTimeout(1400)
await page.locator('.sb-row--settings').click()   // 账号入口改为底部「个人设置」弹层
  await page.waitForTimeout(600)
await page.waitForTimeout(300)
await page.locator('.settings-dialog__logout').click()
await page.waitForTimeout(1200)
check('退出登录后 URL 为 /login', (await url()).includes('/login'), await url())
await page.goto(BASE + '/#/app/workbench', { waitUntil: 'networkidle' })
await page.waitForTimeout(1400)
check('退出后无法再进受保护页', (await url()).includes('/login'), await url())

await browser.close()
if (issues.length) {
  console.log(`\n发现 ${issues.length} 个问题:`)
  issues.forEach((i) => console.log('  - ' + i))
  process.exit(1)
} else {
  console.log('\n路由 QA 全部通过')
}
