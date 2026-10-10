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
  await page.getByRole('button', { name: /一键使用演示账号/ }).click()
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
await page.getByRole('button', { name: /一键使用演示账号/ }).click()
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
await page.goto(BASE + '/#/app/kb/1?doc=' + encodeURIComponent('集合框架.md'), { waitUntil: 'networkidle' })
await page.waitForTimeout(1800)
check('深链直达知识库详情', (await page.locator('.page-title').first().innerText()).includes('Java 笔记'))
check('深链带上 doc 参数即高亮目标文档', (await page.locator('.doc-row[data-hl]').count()) > 0)

/* ---------- 6. 知识库详情 tab 为横向橡皮分段且无黑框残留 ---------- */
const kbTabs = await page.locator('.page .rubber-segment button.rubber-segment__item').count()
check('KB 详情 tab 是橡皮分段（4 项）', kbTabs === 4, `实际 ${kbTabs}`)
check('页面中已无 GooeyNav 黑框层', (await page.locator('.gooey-nav-container').count()) === 0)
await page.locator('.page .rubber-segment button.rubber-segment__item', { hasText: '问答' }).click()
await page.waitForTimeout(600)
check('切到问答 tab 生效', (await page.locator('input[placeholder*="B+ 树为什么"]').count()) === 1)

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