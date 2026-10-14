// 整改验收：在真实渲染上逐条量测本轮修复的效果（只读，不改业务数据）
import { chromium } from 'playwright-core'
import { setTheme, setPalette, readAppearance } from './_theme.mjs'
import { mkdirSync } from 'node:fs'

const BASE = 'http://localhost:5175'
const OUT = 'D:/develop/Prisma/docs/prototypes/verify'
mkdirSync(OUT, { recursive: true })

const log = (...a) => console.log(...a)
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('pageerror', (e) => log('  [pageerror]', e.message.slice(0, 160)))
await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
})
const shot = async (n) => { await page.screenshot({ path: `${OUT}/${n}.png` }); log('  shot', n) }

/* ---------- 量测器 ---------- */

// 顶部是否还有一条高彩度的横带（Aurora 从半透明 shell-main 透出来）
async function topBand(label) {
  const buf = await page.screenshot({ clip: { x: 500, y: 0, width: 260, height: 56 } })
  const rows = await page.evaluate(async (b64) => {
    const img = new Image()
    img.src = 'data:image/png;base64,' + b64
    await img.decode()
    const c = document.createElement('canvas')
    c.width = img.width; c.height = img.height
    const ctx = c.getContext('2d', { willReadFrequently: true })
    ctx.drawImage(img, 0, 0)
    const out = []
    for (let y = 0; y < img.height; y += 8) {
      const d = ctx.getImageData(0, y, img.width, 1).data
      let r = 0, g = 0, b = 0, n = 0
      for (let i = 0; i < d.length; i += 4) { r += d[i]; g += d[i + 1]; b += d[i + 2]; n++ }
      r /= n; g /= n; b /= n
      out.push({ y, rgb: [r, g, b].map(Math.round), chroma: Math.round(Math.max(r, g, b) - Math.min(r, g, b)) })
    }
    return out
  }, buf.toString('base64'))
  log(`  顶部像素 ${label}: ` + rows.map((r) => `y${r.y}=${r.rgb.join(',')}|c${r.chroma}`).join('  '))
}

async function overflow() {
  return page.evaluate(() => ({
    docOverflowX: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    bodyOverflowX: document.body.scrollWidth - document.body.clientWidth,
  }))
}

async function sidebarRows() {
  return page.evaluate(() => {
    const rows = [...document.querySelectorAll('.sb-recent-list .swipe-row')].slice(0, 6)
    return rows.map((r) => {
      const sel = r.querySelector('.session-item')?.hasAttribute('data-selected')
      const g = (s) => { const e = r.querySelector(s); return e ? getComputedStyle(e).backgroundColor : null }
      return {
        sel: !!sel,
        clip: g('.swipe-row__clip'),
        rail: g('.swipe-row__rail'),
        railW: r.querySelector('.swipe-row__rail')?.getBoundingClientRect().width,
        rowW: r.getBoundingClientRect().width,
        surface: g('.swipe-row__surface'),
      }
    })
  })
}

async function chatAlign() {
  return page.evaluate(() => {
    const t = document.querySelector('.chat-main .thread')
    const c = document.querySelector('.chat-main-col > .composer-wrap')
    const nav = document.querySelector('.msg-nav')
    const msg = document.querySelector('.chat-main .thread > .msg')
    if (!t || !c) return { err: 'thread/composer 不存在' }
    const tb = t.getBoundingClientRect(), cb = c.getBoundingClientRect(), cs = getComputedStyle(t)
    const cl = tb.left + parseFloat(cs.paddingLeft), cr = tb.right - parseFloat(cs.paddingRight)
    const mb = msg?.getBoundingClientRect()
    const nb = nav?.getBoundingClientRect()
    return {
      padL: parseFloat(cs.paddingLeft), padR: parseFloat(cs.paddingRight),
      threadCenter: +((cl + cr) / 2).toFixed(1),
      composerCenter: +((cb.left + cb.right) / 2).toFixed(1),
      msgCenter: mb ? +((mb.left + mb.right) / 2).toFixed(1) : null,
      composerW: +cb.width.toFixed(1),
      navW: nb ? +nb.width.toFixed(1) : null,
      navLeft: nb ? +nb.left.toFixed(1) : null,
      msgRight: mb ? +mb.right.toFixed(1) : null,
      railOverlapsMsg: nb && mb ? nb.left < mb.right - 0.5 : null,
    }
  })
}

async function promptGrow() {
  const input = page.locator('.prompt-bar__input').first()
  if (!(await input.count())) return { err: 'no input' }
  const h = async () => page.evaluate(() => {
    const el = document.querySelector('.prompt-bar__input')
    return { h: +el.getBoundingClientRect().height.toFixed(1), oy: getComputedStyle(el).overflowY }
  })
  const base = await h()
  await input.click()
  await input.fill(Array.from({ length: 8 }, (_, i) => `第 ${i + 1} 行内容，用来触发输入框自动增高`).join('\n'))
  await page.waitForTimeout(320)
  const grown = await h()
  await input.fill('')
  await page.waitForTimeout(320)
  const back = await h()
  return { base, grown, back, shrinkOk: Math.abs(back.h - base.h) < 1.5, capped: grown.h <= 200 }
}

async function settingsBox() {
  await page.locator('.sb-row--settings').first().click()
  await page.waitForTimeout(700)
  const m = await page.evaluate(() => {
    const d = document.querySelector('.settings-dialog')
    if (!d) return { err: 'no dialog' }
    const content = d.querySelector('.settings-dialog__content')
    const nav = d.querySelector('.settings-dialog__nav')
    const cur = d.querySelector('.settings-dialog__item[aria-current="true"]')
    const btn = (s) => {
      const el = d.querySelector(s); if (!el) return null
      const cs = getComputedStyle(el)
      return { border: cs.borderTopWidth + ' ' + cs.borderTopStyle + ' ' + cs.borderTopColor, bg: cs.backgroundColor, radius: cs.borderRadius, appearance: cs.appearance }
    }
    return {
      dialogH: +d.getBoundingClientRect().height.toFixed(1),
      contentScrollH: content?.scrollHeight,
      contentClientH: content?.clientHeight,
      navScrollH: nav?.scrollHeight,
      deadSpace: content ? +(content.clientHeight - Math.min(content.scrollHeight, content.clientHeight)).toFixed(1) : null,
      currentBg: cur ? getComputedStyle(cur).backgroundColor : null,
      currentText: cur?.textContent?.trim(),
      currentCount: d.querySelectorAll('.settings-dialog__item[aria-current="true"]').length,
      replay: btn('.settings-dialog__replay'),
      logout: btn('.settings-dialog__logout'),
    }
  })
  return m
}

/* ---------- 登录 ---------- */
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
await page.fill('#li-u', 'ui_probe')
await page.fill('#li-p', 'ui_probe_123')
await page.waitForTimeout(250)
log('登录前校验态:', JSON.stringify(await page.evaluate(() => [...document.querySelectorAll('.field .err')].map((e) => ({ t: e.textContent, shown: getComputedStyle(e).display !== 'none' && e.className })))))
await page.locator('button[type="submit"]').click()
await page.waitForTimeout(2600)
log('登录后 url =', page.url())

/* ---------- 主题矩阵 ---------- */
const CASES = [
  ['turquoise', 'light'], ['turquoise', 'dark'],
  ['terracotta', 'light'], ['violet', 'dark'],
  ['lime', 'light'], ['indigo', 'dark'],
]

for (const [palette, mode] of CASES) {
  await page.goto(BASE + '/#/app/workbench', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  await setPalette(page, palette)
  await setTheme(page, mode)
  await page.waitForTimeout(700)
  const tag = `${palette}-${mode}`
  log(`\n===== ${tag} · ${JSON.stringify(await readAppearance(page))} =====`)
  log('  workbench overflow:', JSON.stringify(await overflow()))
  await topBand(tag)
  await shot(`wb-${tag}`)

  await page.goto(BASE + '/#/app/chat', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1600)
  await setPalette(page, palette); await setTheme(page, mode)
  await page.waitForTimeout(600)
  log('  chat overflow:', JSON.stringify(await overflow()))
  log('  chat align:', JSON.stringify(await chatAlign()))
  log('  sidebar rows:', JSON.stringify(await sidebarRows()))
  await shot(`chat-${tag}`)
  if (palette === 'turquoise') log('  promptbar:', JSON.stringify(await promptGrow()))

  for (const r of ['app/kb', 'app/agents']) {
    await page.goto(BASE + '/#/' + r, { waitUntil: 'networkidle' })
    await page.waitForTimeout(1200)
    await setPalette(page, palette); await setTheme(page, mode)
    await page.waitForTimeout(400)
    log(`  ${r} overflow:`, JSON.stringify(await overflow()))
    await shot(`${r.split('/')[1]}-${tag}`)
  }

  if (palette === 'turquoise' || palette === 'violet') {
    await page.goto(BASE + '/#/app/chat', { waitUntil: 'networkidle' })
    await page.waitForTimeout(1400)
    await setPalette(page, palette); await setTheme(page, mode)
    log('  settings:', JSON.stringify(await settingsBox()))
    await shot(`settings-${tag}`)
    await page.keyboard.press('Escape')
    await page.waitForTimeout(400)
  }
}

/* ---------- 视口矩阵 ---------- */
const VPS = [[1920, 1080], [1440, 900], [1024, 768], [768, 1024], [390, 844]]
await setPalette(page, 'turquoise'); await setTheme(page, 'light')
for (const [w, h] of VPS) {
  await page.setViewportSize({ width: w, height: h })
  await page.waitForTimeout(500)
  log(`\n----- 视口 ${w}x${h} -----`)
  for (const r of ['app/workbench', 'app/chat', 'app/kb', 'app/agents']) {
    await page.goto(BASE + '/#/' + r, { waitUntil: 'networkidle' })
    await page.waitForTimeout(1400)
    const name = r.split('/')[1]
    log(`  ${name} overflow:`, JSON.stringify(await overflow()))
    if (name === 'chat') log('  chat align:', JSON.stringify(await chatAlign()))
    await shot(`vp-${w}x${h}-${name}`)
  }
  await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  log('  login overflow:', JSON.stringify(await overflow()))
  await shot(`vp-${w}x${h}-login`)
}

await browser.close()
log('\n完成，截图在', OUT)
