// 对比度 + 间距体检（颜色用 canvas 归一化，兼容 oklch 序列化）
import { chromium } from 'playwright-core'

const BASE = 'http://localhost:5175'
const browser = await chromium.launch({ channel: 'msedge', headless: true })

const PROBE = `(() => {
  // 颜色归一化：画 1×1 像素后读回，必然得到 sRGB 8bit 数值（不受 oklch/color() 序列化影响）
  const cv = document.createElement('canvas')
  cv.width = 1; cv.height = 1
  const ctx = cv.getContext('2d', { willReadFrequently: true })
  const norm = (css) => {
    if (!css) return null
    ctx.clearRect(0, 0, 1, 1)
    ctx.fillStyle = 'rgba(0,0,0,0)'
    ctx.fillStyle = css
    ctx.fillRect(0, 0, 1, 1)
    const d = ctx.getImageData(0, 0, 1, 1).data
    return { r: d[0], g: d[1], b: d[2], a: d[3] / 255 }
  }
  const lin = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4) }
  const lum = (c) => 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b)
  const over = (fg, bg) => ({ r: fg.r * fg.a + bg.r * (1 - fg.a), g: fg.g * fg.a + bg.g * (1 - fg.a), b: fg.b * fg.a + bg.b * (1 - fg.a), a: 1 })
  const ratio = (a, b) => { const l1 = lum(a), l2 = lum(b); const hi = Math.max(l1, l2), lo = Math.min(l1, l2); return (hi + 0.05) / (lo + 0.05) }
  const effBg = (el) => {
    let n = el
    while (n) {
      const c = norm(getComputedStyle(n).backgroundColor)
      if (c && c.a > 0.02) return c
      n = n.parentElement
    }
    return { r: 255, g: 255, b: 255, a: 1 }
  }
  const rows = []
  const add = (label, sel, fgProp = 'color', bgProp = null) => {
    const el = document.querySelector(sel)
    if (!el) { rows.push({ label, missing: true }); return }
    const cs = getComputedStyle(el)
    const base = effBg(el)
    let fg = norm(cs[fgProp])
    if (!fg) { rows.push({ label, missing: true }); return }
    fg = fg.a < 0.999 ? over(fg, base) : fg
    let bg = bgProp ? norm(cs[bgProp]) : norm(cs.backgroundColor)
    if (!bg) bg = base
    if (bg.a < 0.999) bg = over(bg, base)
    rows.push({ label, ratio: +ratio(fg, bg).toFixed(2) })
  }
  add('主按钮 文字/底色', '.btn-primary')
  add('次按钮 文字/底色', '.btn-ghost')
  add('次按钮 边框/底色', '.btn-ghost', 'borderTopColor')
  add('输入框 文字/底色', '.input')
  add('输入框 边框/底色', '.input', 'borderTopColor')
  add('面板 边框/页面底', '.panel', 'borderTopColor')
  add('次要文字/底', '.page-sub, .lp-sub')
  add('弱化文字/底', '.lp-hint, .lp-skeleton-note')
  add('侧栏激活项 文字/底', '.sb-nav button.rubber-segment__item[aria-checked="true"]')
  add('侧栏未激活项 文字/底', '.sb-nav button.rubber-segment__item[aria-checked="false"]')
  add('胶囊导航项/底', '.lp-pill-nav a')
  add('胶囊导航 边框/底', '.lp-pill', 'borderTopColor')
  add('徽标文字/底', '.badge, .lp-badge')
  add('能力卡 边框/页面底', '.lp-caps article', 'borderTopColor')
  add('骨架图 边框/底', '.lp-skeleton', 'borderTopColor')
  add('流程编号色/底', '.lp-flow-num')
  add('用户气泡 文字/底', '.msg.user .bub')
  add('版本切换 选中项', '.lp-variant button[aria-pressed="true"]')
  add('分段拇指 文字/底', '.rubber-segment__copy')
  const box = (sel) => {
    const el = document.querySelector(sel)
    if (!el) return null
    const cs = getComputedStyle(el)
    return { pad: cs.padding, gap: cs.gap, mb: cs.marginBottom }
  }
  const spacing = {
    '段落内边距': box('.lp-section'),
    'Hero 按钮 gap': box('.lp-cta-row'),
    '能力网格 gap': box('.lp-caps'),
    '能力卡内边距': box('.lp-caps article'),
    '胶囊导航 gap': box('.lp-pill'),
    '胶囊项内边距': (() => { const e = document.querySelector('.lp-pill-nav a'); return e ? getComputedStyle(e).padding : null })(),
    '字段下边距': (() => { const e = document.querySelector('.field'); return e ? getComputedStyle(e).marginBottom : null })(),
    '面板内边距': (() => { const e = document.querySelector('.panel-pad'); return e ? getComputedStyle(e).padding : null })(),
    '统计卡内边距': (() => { const e = document.querySelector('.stat-card'); return e ? getComputedStyle(e).padding : null })(),
    '侧栏项高度': (() => { const e = document.querySelector('.sb-nav button'); return e ? getComputedStyle(e).height : null })(),
    '按钮高度': (() => { const e = document.querySelector('.btn'); return e ? getComputedStyle(e).height : null })(),
  }
  return { rows, spacing }
})()`

for (const theme of ['light', 'dark']) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  // 引导只在真实首登时出现，避免污染用例/截图
  await page.addInitScript(() => {
    localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
    /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
    localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
  })
  await page.addInitScript((t) => {
    localStorage.setItem('nx-proto-theme', t)
    localStorage.setItem('nx-proto-palette', 'terracotta')
  }, theme)
  await page.goto(BASE, { waitUntil: 'networkidle' })
  await page.waitForTimeout(2400)

  const show = (title, data) => {
    console.log(`\n===== ${title} (${theme}) =====`)
    data.rows.forEach((r) => {
      if (r.missing) { console.log(`      -  : (无此元素) ${r.label}`); return }
      const tag = r.ratio >= 4.5 ? 'AA ✓' : r.ratio >= 3 ? 'AA大字' : '偏低!'
      console.log(`  ${String(r.ratio).padStart(5)} : ${tag.padEnd(6)} ${r.label}`)
    })
    if (data.spacing) console.log('  间距:', JSON.stringify(data.spacing))
  }

  show('入口页', await page.evaluate(PROBE))

  await page.locator('.lp-pill button', { hasText: '登录' }).first().click()
  await page.waitForSelector('.auth-card')
  await page.waitForTimeout(1000)
  show('登录页', await page.evaluate(PROBE))

  await page.locator('#li-u').fill('qa-runner')
await page.locator('#li-p').fill('qa-runner-password')
  await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
  await page.waitForSelector('.shell')
  await page.waitForTimeout(2000)
  show('工作台', await page.evaluate(PROBE))
  await page.close()
}

await browser.close()