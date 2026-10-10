// 复验：反复进出「外观」分区 5 次，每次检查两个分段的可见拇指宽度是否恰好一格
import { chromium } from 'playwright-core'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const read = async (page) => page.evaluate(() => Array.from(document.querySelectorAll('.settings-body .rubber-segment')).map((s) => {
  const thumb = s.querySelector('.rubber-segment__thumb')
  const clip = getComputedStyle(thumb).clipPath
  const m = clip.match(/inset\(([^)]*)\)/)
  const nums = m ? m[1].replace(/round.*/, '').trim().split(/\s+/).map(parseFloat) : []
  const track = s.getBoundingClientRect()
  const inner = track.width - 6
  const [top, right, bottom, left] = nums.length === 4 ? nums : [0, 0, 0, 0]
  return { label: s.getAttribute('aria-label'), visibleW: Math.round(inner - left - right), slotW: Math.round(inner / 3), clip }
}))
for (let round = 1; round <= 5; round++) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')
  localStorage.setItem('nx-proto-auth', '1')
  localStorage.setItem('nx-proto-theme-mode', 'light')
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
})
  await page.goto('http://localhost:5175/#/app/account', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)
  await page.locator('.settings-item', { hasText: '外观' }).first().click()
  await page.waitForTimeout(1100)
  const res = await read(page)
  const ok = res.every((r) => Math.abs(r.visibleW - r.slotW) <= 3)
  console.log(`  第 ${round} 次: ${ok ? 'ok  ' : 'FAIL'} ` + res.map((r) => `${r.label}=${r.visibleW}(应${r.slotW})`).join('  '))
  await page.close()
}
await browser.close()
