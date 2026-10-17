// 新页面视觉截图（供人工检查，不参与门禁）
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
await page.waitForTimeout(1000)

await page.goto(`${BASE}/#/app/plugins`, { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
await page.screenshot({ path: 'qa-market-plugins.png' })

await page.goto(`${BASE}/#/app/community`, { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
await page.screenshot({ path: 'qa-market-community.png' })

await page.locator('.community-grid .market-card').first().getByRole('button', { name: /详情|Details/ }).click()
await page.waitForSelector('.agent-detail')
await page.waitForTimeout(300)
await page.screenshot({ path: 'qa-market-community-detail.png' })
await page.keyboard.press('Escape')

await page.setViewportSize({ width: 390, height: 844 })
await page.goto(`${BASE}/#/app/community`, { waitUntil: 'networkidle' })
await page.waitForTimeout(1000)
await page.screenshot({ path: 'qa-market-community-mobile.png' })

await page.goto(`${BASE}/#/app/plugins`, { waitUntil: 'networkidle' })
await page.waitForTimeout(1000)
await page.screenshot({ path: 'qa-market-plugins-mobile.png' })

await browser.close()
console.log('shots written')
