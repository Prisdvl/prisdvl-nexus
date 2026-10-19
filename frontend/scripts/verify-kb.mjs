// 知识库列表 / 文档列表 / 分块管理 专项验证（mock 模式）
import { chromium } from 'playwright-core'

const BASE = 'http://localhost:5175'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.addInitScript(() => localStorage.setItem('nx-proto-tour-done', '1'))
const issues = []
const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`)
  if (!ok) issues.push(name)
}
page.on('pageerror', (e) => issues.push(`pageerror: ${String(e.message).slice(0, 160)}`))
page.on('console', (m) => { if (m.type() === 'error') issues.push(`console.error: ${m.text().slice(0, 160)}`) })

await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle' })
await page.waitForSelector('.auth-card')
await page.locator('#li-u').fill('qa-runner')
await page.locator('#li-p').fill('qa-runner-password')
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
await page.waitForSelector('.shell', { timeout: 10000 })

/* ---------- 知识库列表 ---------- */
await page.goto(`${BASE}/#/app/kb`, { waitUntil: 'networkidle' })
await page.waitForTimeout(900)
check('知识库统计卡 3 个', (await page.locator('.kb-stats .stat-card').count()) === 3)
check('知识库筛选 3 项', (await page.locator('.kb-filters button.rubber-segment__item').count()) === 3)
check('知识库数据表渲染', (await page.locator('.kb-table tbody tr').count()) >= 5)
check('表格含 Embedding 模型列且不编造 Collection', (await page.locator('.kb-table .kb-code').count()) >= 5 && (await page.locator('.kb-table th', { hasText: 'Collection' }).count()) === 0)
await page.locator('.kb-actions__search input').fill('Redis')
await page.waitForTimeout(300)
check('搜索可过滤', (await page.locator('.kb-table tbody tr').count()) === 1)
await page.locator('.kb-actions__search input').fill('')
await page.waitForTimeout(300)
await page.locator('.kb-table tbody tr').first().getByRole('button', { name: /重命名/ }).click()
await page.waitForSelector('#rename-kb')
await page.locator('#rename-kb').fill('重命名测试库')
await page.locator('.dialog .btn-primary').click()
await page.waitForTimeout(400)
check('重命名生效', (await page.locator('.kb-table tbody').innerText()).includes('重命名测试库'))
await page.screenshot({ path: 'qa-kb-list.png' })

/* ---------- 文档列表 ---------- */
await page.locator('.kb-table tbody tr').first().click()
/*
 * 等文档区真的就绪再断言，而不是"睡 1200ms 后立刻 count()"。
 * count() 不等待：CI 的慢 runner 上曾读到"行还没渲染"，可同一批断言里紧接着的点
 * `.doc-row`、找分块表都成功——说明只是量得太早。超时后照常断言，真的没有行依然判 FAIL。
 */
await page.locator('.kb-docs__toolbar').waitFor({ state: 'visible', timeout: 10000 }).catch(() => {})
await page.locator('.doc-row').first().waitFor({ state: 'visible', timeout: 10000 }).catch(() => {})
check('文档筛选含状态与刷新', (await page.locator('.kb-docs__toolbar select, .kb-docs__toolbar button').count()) >= 2)
check('文档行含启用开关', (await page.locator('.doc-row .doc-switch').count()) >= 1)
check('文档行含分块数', (await page.locator('.doc-row .kb-num').count()) >= 1)
await page.locator('.doc-row').first().getByRole('button', { name: /分块管理/ }).click()
await page.waitForSelector('.chunk-view')

/* ---------- 分块管理 ---------- */
check('分块表渲染', (await page.locator('.chunk-table tbody tr').count()) >= 5)
check('分块含字符与 Token 列', (await page.locator('.chunk-table th', { hasText: 'Token' }).count()) === 1)
check('分块批量操作齐全', (await page.locator('.chunk-batch button').count()) === 4)
const onBefore = await page.locator('.chunk-table .doc-switch').first().getAttribute('aria-checked')
await page.locator('.chunk-table .doc-switch').first().click()
await page.waitForTimeout(200)
const onAfter = await page.locator('.chunk-table .doc-switch').first().getAttribute('aria-checked')
check('分块可单独启停', onBefore !== onAfter, `${onBefore} → ${onAfter}`)
await page.locator('.chunk-table tbody tr').first().getByRole('button', { name: /选择分块/ }).click()
await page.locator('.chunk-batch button', { hasText: '批量禁用' }).click()
await page.waitForTimeout(200)
check('批量操作可用', (await page.locator('.chunk-table .doc-switch[aria-checked="false"]').count()) >= 1)
await page.screenshot({ path: 'qa-kb-chunks.png' })

await page.setViewportSize({ width: 390, height: 844 })
await page.waitForTimeout(500)
check('手机端知识库页无横向溢出', await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1))

console.log(issues.length === 0 ? 'ALL OK' : `ISSUES: ${issues.join(' | ')}`)
await browser.close()
process.exit(issues.length ? 1 : 0)
