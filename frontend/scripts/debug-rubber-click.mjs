import { chromium } from 'playwright-core'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')
  localStorage.setItem('nx-proto-auth', '1')
  localStorage.setItem('nx-proto-theme-mode', 'light')
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
})
await page.goto('http://localhost:5175/#/app/account', { waitUntil: 'networkidle' })
await page.waitForTimeout(1600)
await page.locator('.settings-item', { hasText: '外观' }).first().click()
await page.waitForTimeout(1200)

const clip = (i = 0) => page.evaluate((idx) => {
  const seg = document.querySelectorAll('.settings-body .rubber-segment')[idx]
  const t = seg?.querySelector('.rubber-segment__thumb')
  const items = Array.from(seg?.querySelectorAll('button.rubber-segment__item') || [])
  return {
    clip: t ? getComputedStyle(t).clipPath : null,
    checked: items.findIndex((b) => b.getAttribute('aria-checked') === 'true'),
  }
}, i)

console.log('初次进入（主题分段）:', JSON.stringify(await clip(0)))
await page.locator('.settings-body .rubber-segment').first().locator('button.rubber-segment__item', { hasText: '亮色' }).click()
await page.waitForTimeout(800)
console.log('点「亮色」后:      ', JSON.stringify(await clip(0)))
await page.locator('.settings-body .rubber-segment').first().locator('button.rubber-segment__item', { hasText: '跟随系统' }).click()
await page.waitForTimeout(800)
console.log('点「跟随系统」后:  ', JSON.stringify(await clip(0)))
console.log('配色分段:          ', JSON.stringify(await clip(1)))

// 对比：切到别的分区再切回来（重新挂载）
await page.locator('.settings-item', { hasText: '通知偏好' }).first().click()
await page.waitForTimeout(500)
await page.locator('.settings-item', { hasText: '外观' }).first().click()
await page.waitForTimeout(1200)
console.log('重新挂载后:        ', JSON.stringify(await clip(0)))
await browser.close()
