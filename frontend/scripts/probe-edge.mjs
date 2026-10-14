// 专项：顶部/边缘异常线、伪元素、原生样式泄漏的实际来源
import { chromium } from 'playwright-core'
import { setTheme, setPalette } from './_theme.mjs'

const BASE = 'http://localhost:5175'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
})
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(700)
await page.fill('#li-u', 'ui_probe'); await page.fill('#li-p', 'ui_probe_123')
await page.locator('button[type="submit"]').click()
await page.waitForTimeout(2000)

/** 含伪元素：找贴边/细高、颜色接近 primary 或带背景图的元素 */
async function edgeScan(path, theme, palette) {
  await page.goto(BASE + path, { waitUntil: 'networkidle' })
  await page.waitForTimeout(500)
  await setTheme(page, theme); await setPalette(page, palette)
  await page.waitForTimeout(700)
  const res = await page.evaluate(() => {
    const primary = getComputedStyle(document.documentElement).getPropertyValue('--primary').trim()
    const out = []
    const scan = (el, tag) => {
      for (const pseudo of [null, '::before', '::after']) {
        const cs = getComputedStyle(el, pseudo)
        if (cs.content === 'none' && pseudo) continue
        const hasBg = cs.backgroundColor !== 'rgba(0, 0, 0, 0)' || (cs.backgroundImage !== 'none')
        if (!hasBg) continue
        const r = pseudo ? el.getBoundingClientRect() : el.getBoundingClientRect()
        const thin = r.height <= 12 || r.width <= 12
        const nearEdge = r.top <= 6 || r.left <= 2 || r.right >= innerWidth - 2 || r.bottom >= innerHeight - 2
        if (!thin && !nearEdge) continue
        out.push({
          el: tag + (pseudo ?? ''),
          cls: el.className?.toString?.().slice(0, 46) ?? '',
          rect: `${r.width.toFixed(0)}x${r.height.toFixed(0)}@${r.left.toFixed(0)},${r.top.toFixed(0)}`,
          bg: cs.backgroundColor, bi: cs.backgroundImage === 'none' ? '' : cs.backgroundImage.slice(0, 70),
          b: `${cs.borderTopWidth}/${cs.borderBottomWidth}/${cs.borderLeftWidth}/${cs.borderRightWidth} ${cs.borderTopColor}`,
          isPrimary: cs.backgroundColor.includes(primary) || cs.backgroundImage.includes('158'),
          z: cs.zIndex, pos: cs.position,
        })
      }
    }
    document.querySelectorAll('*').forEach((el) => scan(el, el.tagName.toLowerCase()))
    return { primary, out }
  })
  const suspicious = res.out.filter((x) => x.isPrimary || x.bi.includes('gradient'))
  console.log(`\n### ${path} / ${theme} / ${palette}  primary=${res.primary}`)
  console.log(`  贴边/细高元素 ${res.out.length} 条，其中疑似主色/渐变 ${suspicious.length} 条`)
  res.out.slice(0, 14).forEach((x) => console.log('   ·', JSON.stringify(x)))
}

for (const pal of ['turquoise', 'lime', 'terracotta']) {
  for (const th of ['light', 'dark']) {
    await edgeScan('/#/app/chat', th, pal)
  }
}
await edgeScan('/#/app/workbench', 'light', 'turquoise')
await edgeScan('/#/app/agents', 'light', 'turquoise')

// 伪元素审计：谁在画装饰线
const pseudos = await page.evaluate(() => {
  const out = []
  document.querySelectorAll('*').forEach((el) => {
    for (const p of ['::before', '::after']) {
      const cs = getComputedStyle(el, p)
      if (cs.content === 'none') continue
      if (cs.content === '""' || cs.content === "''") continue
      if (cs.backgroundImage === 'none' && cs.backgroundColor === 'rgba(0, 0, 0, 0)' && parseFloat(cs.borderTopWidth) === 0 && parseFloat(cs.borderBottomWidth) === 0 && parseFloat(cs.borderLeftWidth) === 0 && parseFloat(cs.borderRightWidth) === 0) continue
      out.push({ el: el.tagName.toLowerCase() + '.' + (el.className?.toString?.().slice(0, 40) ?? ''), p, content: cs.content.slice(0, 20), w: cs.width, h: cs.height, bg: cs.backgroundColor, bi: cs.backgroundImage.slice(0, 60), b: cs.borderTopWidth + ' ' + cs.borderTopColor })
    }
  })
  return out
})
console.log('\n### 真实绘制内容的伪元素 ===')
pseudos.forEach((x) => console.log(' ', JSON.stringify(x)))

await browser.close()