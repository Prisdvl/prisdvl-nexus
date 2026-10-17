// 工作流设计界面专项验证（mock 模式）
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
await page.goto(`${BASE}/#/app/workflows`, { waitUntil: 'networkidle' })
await page.waitForTimeout(900)

check('工作流页标题', (await page.locator('.page-title').first().innerText()).includes('工作流'))
check('节点面板 6 类', (await page.locator('.wf-palette-item').count()) === 6)
check('画布初始 6 个节点', (await page.locator('.wf-node').count()) === 6)
check('节点之间有线', (await page.locator('.wf-edge').count()) === 6)

await page.locator('.wf-node').first().click()
await page.waitForTimeout(200)
check('选中节点后检查器可编辑', (await page.locator('.wf-inspector #wf-name').count()) === 1)

const before = await page.locator('.wf-node').count()
await page.locator('.wf-palette-item').first().click()
await page.waitForTimeout(250)
check('可从面板添加节点', (await page.locator('.wf-node').count()) === before + 1)

const zoomBefore = await page.locator('.wf-zoom').innerText()
await page.locator('.wf-canvas-toolbar .plugin-icon-btn').nth(1).click()
await page.waitForTimeout(200)
const zoomAfter = await page.locator('.wf-zoom').innerText()
check('支持缩放', zoomBefore !== zoomAfter, `${zoomBefore} → ${zoomAfter}`)

await page.getByRole('button', { name: '运行', exact: true }).click()
await page.waitForTimeout(400)
check('运行中显示状态', (await page.locator('.wf-log li[data-state="running"], .wf-log li[data-state="done"]').count()) >= 1)
await page.waitForFunction(() => !document.querySelector('.wf-node[data-state="running"]'), null, { timeout: 8000 }).catch(() => {})
check('运行完成写入日志', (await page.locator('.wf-log li[data-state="done"]').count()) >= 2)

await page.setViewportSize({ width: 390, height: 844 })
await page.waitForTimeout(600)
check('手机端工作流无横向溢出', await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1))

console.log(issues.length === 0 ? 'ALL OK' : `ISSUES: ${issues.join(' | ')}`)
await browser.close()
process.exit(issues.length ? 1 : 0)
