import { chromium } from 'playwright-core'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
const OUT = process.env.QA_SHOTS_OUT ?? join(tmpdir(), 'prisma-recover-qa-shots')
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1100, height: 620 }, deviceScaleFactor: 2 })
await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done','1')   // 引导只在真实首登时出现，避免污染用例/截图
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))   // 高风险工具预授权：常规用例不被确认卡打断
  localStorage.setItem('nx-proto-auth', '1'); localStorage.setItem('nx-proto-theme-mode', 'light')
  localStorage.setItem('nx-proto-palette', 'terracotta')
})
await page.goto('http://localhost:5175/#/app/workbench', { waitUntil: 'networkidle' })
await page.waitForTimeout(2000)
await page.keyboard.press('Tab')
await page.waitForTimeout(500)
await page.screenshot({ path: `${OUT}/z6-skip-link.png`, clip: { x: 0, y: 0, width: 560, height: 180 } })
console.log('ok  z6-skip-link')

// 暗色同样一张
await page.evaluate(() => window.__nxTheme.setMode('dark'))
await page.waitForTimeout(700)
await page.keyboard.press('Tab')
await page.waitForTimeout(500)
await page.screenshot({ path: `${OUT}/z6-skip-link-dark.png`, clip: { x: 0, y: 0, width: 560, height: 180 } })
console.log('ok  z6-skip-link-dark')
await browser.close()
