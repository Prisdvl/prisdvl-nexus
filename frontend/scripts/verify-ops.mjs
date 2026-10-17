// 自动化专项验证（mock 模式）
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

/* ---------- 自动化 ---------- */
await page.goto(`${BASE}/#/app/automations`, { waitUntil: 'networkidle' })
await page.waitForTimeout(900)
check('自动化页标题', (await page.locator('.page-title').first().innerText()).includes('自动化'))
check('自动化列表渲染', (await page.locator('.ops-row--static').count()) >= 3)
const before = await page.locator('.auto-switch').first().getAttribute('aria-checked')
await page.locator('.auto-switch').first().click()
await page.waitForTimeout(200)
const after = await page.locator('.auto-switch').first().getAttribute('aria-checked')
check('启停开关可切换', before !== after, `${before} → ${after}`)
await page.getByRole('button', { name: /新建自动化/ }).click()
await page.waitForSelector('#auto-name')
await page.locator('#auto-name').fill('QA 定时任务')
await page.locator('#auto-prompt').fill('汇总今天的知识库更新')
await page.locator('.dialog .btn-primary').click()
await page.waitForTimeout(300)
check('新建自动化成功', (await page.locator('.ops-row--static', { hasText: 'QA 定时任务' }).count()) === 1)
await page.locator('.ops-row--static', { hasText: 'QA 定时任务' }).getByRole('button', { name: /运行历史/ }).click()
await page.waitForTimeout(300)
check('运行历史弹窗', (await page.locator('.dialog').innerText()).includes('运行历史'))
await page.keyboard.press('Escape')
await page.waitForTimeout(200)

/* ---------- 移动端 ---------- */
await page.setViewportSize({ width: 390, height: 844 })
for (const [label, path] of [['自动化', '/#/app/automations']]) {
  await page.goto(BASE + path, { waitUntil: 'networkidle' })
  await page.waitForTimeout(800)
  check(`手机端${label}无横向溢出`, await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1))
}

console.log(issues.length === 0 ? 'ALL OK' : `ISSUES: ${issues.join(' | ')}`)
await browser.close()
process.exit(issues.length ? 1 : 0)
