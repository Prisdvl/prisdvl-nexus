// 对话轨迹 / 自动化 截图（人工检查用）
import { chromium } from 'playwright-core'

const BASE = 'http://localhost:5175'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
})

await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle' })
await page.waitForSelector('.auth-card')
await page.locator('#li-u').fill('qa-runner')
await page.locator('#li-p').fill('qa-runner-password')
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
await page.waitForSelector('.shell', { timeout: 10000 })

await page.goto(`${BASE}/#/app/chat`, { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
await page.locator('.prompt-bar__input').fill('解释一下 B+ 树索引，并给一个例子')
await page.locator('.prompt-bar__input').press('Enter')
await page.waitForSelector('.tool-calls .call-chip', { timeout: 8000 })
await page.waitForTimeout(260)
await page.screenshot({ path: 'qa-chat-tooltrace.png' })
await page.waitForTimeout(6000)
await page.screenshot({ path: 'qa-chat-settled.png' })

await page.goto(`${BASE}/#/app/workbench`, { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
await page.screenshot({ path: 'qa-workbench.png' })

await page.goto(`${BASE}/#/app/workflows`, { waitUntil: 'networkidle' })
await page.waitForTimeout(1000)
await page.screenshot({ path: 'qa-workflow.png' })

await page.goto(`${BASE}/#/app/automations`, { waitUntil: 'networkidle' })
await page.waitForTimeout(900)
await page.screenshot({ path: 'qa-automations.png' })

await page.setViewportSize({ width: 390, height: 844 })
await page.goto(`${BASE}/#/app/chat`, { waitUntil: 'networkidle' })
await page.waitForTimeout(800)
await page.locator('.chat-view-tabs button.rubber-segment__item', { hasText: /轨迹|Trace/ }).click()
await page.waitForTimeout(500)
await page.screenshot({ path: 'qa-chat-trace-mobile.png' })

await browser.close()
console.log('shots written')
