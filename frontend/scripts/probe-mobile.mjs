// 移动端 + 平板专项：定位顶部贯穿线、卡片化外框、抽屉
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { chromium } from 'playwright-core'
import { setTheme, setPalette } from './_theme.mjs'
import { mkdirSync } from 'node:fs'

const BASE = 'http://localhost:5175'
const OUT = join(tmpdir(), 'prisma-recover-qa-probe')
mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 })
await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
})
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(700)
await page.fill('#li-u', 'ui_probe'); await page.fill('#li-p', 'ui_probe_123')
await page.locator('button[type="submit"]').click()
await page.waitForTimeout(2000)

await page.goto(BASE + '/#/app/chat', { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
await setTheme(page, 'dark')
await setPalette(page, 'turquoise')
await page.waitForTimeout(800)
await page.screenshot({ path: `${OUT}/m-chat-dark-turquoise.png` })

/** 逐像素扫描顶部 80px，找出发主色/高对比的行 */
const scan = await page.evaluate(() => {
  const cs = getComputedStyle(document.documentElement)
  const bg = cs.backgroundColor
  return { note: 'below', primary: cs.getPropertyValue('--primary').trim(), bg }
})
console.log('primary =', scan.primary)

const stripe = await page.evaluate(async () => {
  // 用 elementFromPoint 逐行找出"该行的最上层元素"
  const rows = []
  for (let y = 0; y <= 90; y += 2) {
    const els = document.elementsFromPoint(195, y).slice(0, 4)
    rows.push({ y, els: els.map((e) => `${e.tagName.toLowerCase()}.${(e.className?.toString?.().slice(0, 34) ?? '')}`) })
  }
  return rows
})
let prev = ''
for (const r of stripe) {
  const k = r.els.join('|')
  if (k !== prev) { console.log(`y=${String(r.y).padStart(3)}  ${r.els.join('  ←  ')}`); prev = k }
}

/** 顶部 90px 内所有元素的边框与背景 */
const tops = await page.evaluate(() => {
  const out = []
  document.querySelectorAll('*').forEach((el) => {
    const b = el.getBoundingClientRect()
    if (b.top > 92 || b.height <= 0 || b.width < 100) return
    const cs = getComputedStyle(el)
    out.push({
      el: el.tagName.toLowerCase() + '.' + (el.className?.toString?.().slice(0, 40) ?? ''),
      rect: `${b.width.toFixed(0)}x${b.height.toFixed(0)}@${b.left.toFixed(0)},${b.top.toFixed(0)}`,
      bg: cs.backgroundColor, bi: cs.backgroundImage === 'none' ? '' : cs.backgroundImage.slice(0, 60),
      bt: `${cs.borderTopWidth}/${cs.borderBottomWidth} ${cs.borderTopColor}`,
      radius: cs.borderRadius, shadow: cs.boxShadow.slice(0, 50), z: cs.zIndex,
    })
  })
  return out
})
console.log('\n== 顶部 92px 内元素 ==')
tops.forEach((x) => console.log(' ', JSON.stringify(x)))

/** 对话主面板在移动端是否被"卡片化" */
const main = await page.evaluate(() => {
  const el = document.querySelector('.chat-main')
  const cs = getComputedStyle(el)
  const b = el.getBoundingClientRect()
  const wrap = document.querySelector('.chat-fixed')
  const wc = getComputedStyle(wrap)
  return { main: `${b.width.toFixed(0)}x${b.height.toFixed(0)}@${b.left.toFixed(0)},${b.top.toFixed(0)}`, radius: cs.borderRadius, bg: cs.backgroundColor, shadow: cs.boxShadow, chatFixedPad: wc.padding, chatFixedBg: wc.backgroundColor }
})
console.log('\n== 移动端对话主区 ==', JSON.stringify(main, null, 1))

/** 平板 768 */
await page.setViewportSize({ width: 768, height: 1024 })
await page.goto(BASE + '/#/app/chat', { waitUntil: 'networkidle' })
await page.waitForTimeout(1000)
await page.screenshot({ path: `${OUT}/t-chat-768.png` })
const t = await page.evaluate(() => {
  const sb = document.querySelector('.sidebar')
  const sm = document.querySelector('.shell-main')
  return { sidebar: getComputedStyle(sb).width, main: getComputedStyle(sm).marginLeft, drawerHidden: sb.getBoundingClientRect().left }
})
console.log('\n== 768 平板 ==', JSON.stringify(t))

/** 抽屉 */
await page.setViewportSize({ width: 390, height: 844 })
await page.waitForTimeout(500)
await page.locator('.mobile-bar button').first().click()
await page.waitForTimeout(600)
await page.screenshot({ path: `${OUT}/m-drawer.png` })

await browser.close()