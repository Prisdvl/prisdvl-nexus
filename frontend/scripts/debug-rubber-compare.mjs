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
await page.waitForTimeout(1800)

const dumpSeg = (sel, label) => page.evaluate(({ sel, label }) => {
  const seg = document.querySelector(sel)
  if (!seg) return `${label}: 未找到`
  const thumb = seg.querySelector('.rubber-segment__thumb')
  const cs = thumb ? getComputedStyle(thumb) : null
  return {
    label,
    segW: Math.round(seg.getBoundingClientRect().width),
    segDisplay: getComputedStyle(seg).display,
    segParentCls: seg.parentElement?.className,
    thumbW: thumb ? Math.round(thumb.getBoundingClientRect().width) : null,
    thumbInline: thumb ? (thumb.getAttribute('style') || '(无内联)') : null,
    thumbCssW: cs ? cs.width : null,
    thumbCssTransform: cs ? cs.transform : null,
    thumbCssVars: thumb ? ['--rs-thumb-w', '--rs-travel', '--rs-index'].map((v) => `${v}=${cs.getPropertyValue(v).trim() || '(空)'}`).join(' ') : null,
    segInline: seg.getAttribute('style') || '(无内联)',
  }
}, { sel, label })

console.log('侧栏（正常）:', JSON.stringify(await dumpSeg('.sb-nav .rubber-segment', 'sb'), null, 1))
await page.locator('.settings-item', { hasText: '外观' }).first().click()
await page.waitForTimeout(1500)
console.log('\n设置页外观（异常）:', JSON.stringify(await dumpSeg('.settings-body .rubber-segment', 'appearance'), null, 1))
await browser.close()
