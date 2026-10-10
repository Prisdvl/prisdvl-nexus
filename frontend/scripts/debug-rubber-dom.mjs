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

const dump = async (label) => {
  const info = await page.evaluate(() => {
    const seg = document.querySelector('.settings-body .rubber-segment')
    if (!seg) return null
    const walk = (el, depth = 0) => {
      const r = el.getBoundingClientRect()
      const cs = getComputedStyle(el)
      const row = {
        depth,
        tag: el.tagName.toLowerCase(),
        cls: el.className.toString(),
        w: Math.round(r.width), x: Math.round(r.left - seg.getBoundingClientRect().left),
        transform: cs.transform === 'none' ? '-' : cs.transform.replace(/matrix\(|\)/g, '').split(',').slice(4).join(','),
        inlineW: el.style.width || '-',
        color: cs.color,
      }
      const kids = Array.from(el.children).flatMap((c) => walk(c, depth + 1))
      return [row, ...kids]
    }
    return walk(seg)
  })
  console.log(`\n--- ${label} ---`)
  info.forEach((n) => console.log(`  ${'  '.repeat(n.depth)}${n.tag}.${n.cls.slice(0, 42)} w=${n.w} x=${n.x} tx=${n.transform} inlineW=${n.inlineW}`))
}
await page.waitForTimeout(30); await dump('30ms')
await page.waitForTimeout(300); await dump('330ms')
await page.waitForTimeout(1200); await dump('1.5s')
await browser.close()
