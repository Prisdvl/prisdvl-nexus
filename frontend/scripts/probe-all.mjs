// 全面排查：所有路由 × 主题 × 视口，捕捉用户截图里描述的现象
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { chromium } from 'playwright-core'
import { setTheme, setPalette } from './_theme.mjs'
import { mkdirSync } from 'node:fs'

const BASE = 'http://localhost:5175'
const OUT = join(tmpdir(), 'prisma-recover-qa-probe')
mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
})
const errs = []
page.on('pageerror', (e) => errs.push('pageerror: ' + String(e.message).slice(0, 140)))
page.on('console', (m) => { if (m.type() === 'error' && !/vite|WebSocket|ERR_CONNECTION/i.test(m.text())) errs.push('console: ' + m.text().slice(0, 140)) })

// 真实登录
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(800)
await page.fill('#li-u', 'ui_probe')
await page.fill('#li-p', 'ui_probe_123')
await page.locator('button[type="submit"]').click()
await page.waitForTimeout(2200)

/** 找出"贴着视口顶/左/右边的非 0 边框元素" —— 整屏外框的来源 */
async function findStrayBorders(label) {
  const r = await page.evaluate(() => {
    const out = []
    const vw = innerWidth, vh = innerHeight
    document.querySelectorAll('*').forEach((el) => {
      const b = el.getBoundingClientRect()
      if (b.width < 200 || b.height < 100) return
      const cs = getComputedStyle(el)
      const sides = [
        ['top', cs.borderTopWidth, b.top, vw],
        ['left', cs.borderLeftWidth, b.left, vw],
        ['right', cs.borderRightWidth, vw - b.right, vw],
        ['bottom', cs.borderBottomWidth, vh - b.bottom, vh],
      ]
      for (const [side, w, dist] of sides) {
        if (parseFloat(w) > 0.5 && Math.abs(dist) < 3) {
          out.push({ el: el.tagName.toLowerCase() + '.' + (el.className?.toString?.().slice(0, 44) ?? ''), side, w, dist: +dist.toFixed(1), rect: `${b.width.toFixed(0)}x${b.height.toFixed(0)}@${b.left.toFixed(0)},${b.top.toFixed(0)}` })
        }
      }
    })
    return out
  })
  if (r.length) { console.log(`\n--- 贴边边框 (${label}) ---`); r.forEach((x) => console.log(' ', JSON.stringify(x))) }
  else console.log(`--- 贴边边框 (${label}): 无 ---`)
}

/** 原生按钮泄漏检测 */
async function nativeButtons(label) {
  const r = await page.evaluate(() => {
    const out = []
    document.querySelectorAll('button').forEach((el) => {
      const cs = getComputedStyle(el)
      const raw = cs.borderTopWidth
      if (cs.borderTopStyle === 'outset' || cs.appearance === 'button' && parseFloat(raw) >= 2 && cs.borderTopColor === 'rgb(0, 0, 0)') {
        out.push({ cls: el.className?.toString?.().slice(0, 50) ?? '(none)', txt: (el.textContent ?? '').trim().slice(0, 24), border: cs.border, radius: cs.borderRadius, appearance: cs.appearance })
      }
    })
    return out
  })
  console.log(`--- 原生按钮泄漏 (${label}): ${r.length} ---`)
  r.forEach((x) => console.log(' ', JSON.stringify(x)))
}

/** 文本溢出 / 控件重叠 */
async function overflow(label) {
  const r = await page.evaluate(() => {
    const out = []
    if (document.documentElement.scrollWidth > document.documentElement.clientWidth + 1) out.push({ kind: '页面横向溢出', w: document.documentElement.scrollWidth })
    document.querySelectorAll('*').forEach((el) => {
      if (el.scrollWidth > el.clientWidth + 2 && el.clientWidth > 0) {
        const cs = getComputedStyle(el)
        if (cs.overflowX === 'visible' && cs.display !== 'inline') {
          out.push({ kind: '内容横向溢出', el: el.tagName.toLowerCase() + '.' + (el.className?.toString?.().slice(0, 40) ?? ''), sw: el.scrollWidth, cw: el.clientWidth })
        }
      }
    })
    return out.slice(0, 12)
  })
  console.log(`--- 溢出 (${label}): ${r.length} ---`)
  r.forEach((x) => console.log(' ', JSON.stringify(x)))
}

const ROUTES = [
  ['workbench', '/#/app/workbench'],
  ['kb', '/#/app/kb'],
  ['agents', '/#/app/agents'],
  ['chat', '/#/app/chat'],
  ['account', '/#/app/account'],
]

for (const theme of ['light', 'dark']) {
  for (const [name, path] of ROUTES) {
    await page.goto(BASE + path, { waitUntil: 'networkidle' })
    await setTheme(page, theme)
    await page.waitForTimeout(900)
    await findStrayBorders(`${name}/${theme}`)
    await nativeButtons(`${name}/${theme}`)
    await overflow(`${name}/${theme}`)
    await page.screenshot({ path: `${OUT}/p-${name}-${theme}.png` })
  }
}

// 主题色轮换：确认多主题不串色
for (const pal of ['prisdvl', 'terracotta', 'turquoise', 'lime', 'indigo', 'ocean', 'violet', 'rose']) {
  await page.goto(BASE + '/#/app/chat', { waitUntil: 'networkidle' })
  await page.waitForTimeout(500)
  await setPalette(page, pal)
  await setTheme(page, 'dark')
  await page.waitForTimeout(600)
  const vars = await page.evaluate(() => {
    const cs = getComputedStyle(document.documentElement)
    return { primary: cs.getPropertyValue('--primary').trim(), bg: cs.getPropertyValue('--bg').trim(), surface: cs.getPropertyValue('--surface').trim(), text: cs.getPropertyValue('--text').trim(), border: cs.getPropertyValue('--border').trim(), undefinedCheck: ['--bg','--surface','--text','--text-2','--border','--primary','--primary-container','--card-bg','--input-bg','--on-primary'].filter((k) => !cs.getPropertyValue(k).trim()) }
  })
  console.log(`palette=${pal.padEnd(11)} dark  ${JSON.stringify(vars)}`)
}
await setPalette(page, 'terracotta')
await setTheme(page, 'light')
for (const pal of ['terracotta', 'lime', 'violet']) {
  await setPalette(page, pal)
  await page.waitForTimeout(400)
  const vars = await page.evaluate(() => {
    const cs = getComputedStyle(document.documentElement)
    return { primary: cs.getPropertyValue('--primary').trim(), bg: cs.getPropertyValue('--bg').trim(), missing: ['--bg','--surface','--text','--text-2','--border','--primary','--primary-container','--card-bg','--input-bg'].filter((k) => !cs.getPropertyValue(k).trim()) }
  })
  console.log(`palette=${pal.padEnd(11)} light ${JSON.stringify(vars)}`)
}

// PromptBar 高度行为
await setPalette(page, 'terracotta')
await page.goto(BASE + '/#/app/chat', { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
const ta = page.locator('.prompt-bar__input').first()
const h0 = await ta.evaluate((el) => ({ h: el.getBoundingClientRect().height, sh: el.scrollHeight }))
await ta.fill('第一行\n第二行\n第三行\n第四行\n第五行\n第六行\n第七行')
await page.waitForTimeout(500)
const h1 = await ta.evaluate((el) => ({ h: el.getBoundingClientRect().height, sh: el.scrollHeight, of: el.style.overflowY }))
await ta.fill('')
await page.waitForTimeout(500)
const h2 = await ta.evaluate((el) => ({ h: el.getBoundingClientRect().height, sh: el.scrollHeight, of: el.style.overflowY }))
console.log('\n== PromptBar 高度 ==', JSON.stringify({ 空: h0, 七行: h1, 清空后: h2 }))

console.log('\n== 运行时错误 ==', errs.length ? errs.join('\n') : '无')
await browser.close()