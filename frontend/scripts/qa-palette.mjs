// 用色扫描：页面上不得出现 vendored 组件的原始默认色（防止"默认值没接 token"再次回归）
import { chromium } from 'playwright-core'

const BASE = 'http://localhost:5175'

// 这些是各组件源码里**原本写死**的暗色取向默认值；一旦出现说明某处没接主题 token
const BLOCKLIST = [
  'rgb(82, 39, 255)',   // Stepper #5227ff 紫蓝
  'rgb(34, 34, 34)',    // Stepper #222
  'rgb(163, 163, 163)', // Stepper #a3a3a3
  'rgb(82, 82, 91)',    // Stepper #52525b
  'rgb(13, 13, 13)',    // Carousel #0d0d0d
  'rgb(85, 85, 85)',    // Carousel #555
  'rgb(51, 51, 51)',    // Carousel #333
  'rgb(18, 15, 23)',    // BorderGlow / Carousel #120F17
  'rgb(9, 9, 11)',      // PixelCard #09090b
  'rgb(192, 132, 252)', // BorderGlow #c084fc
  'rgb(244, 114, 182)', // BorderGlow #f472b6
  'rgb(38, 189, 248)',  // BorderGlow #38bdf8（写作 #38bdf8）
  'rgb(56, 189, 248)',
  'rgb(39, 39, 42)',    // SwipeRow/JellyRadio/RubberSegment #27272a
  'rgb(250, 250, 250)', // RubberSegment #fafafa
  'rgb(24, 24, 27)',    // CodeSlots/Stepper #18181b
  'rgb(255, 59, 48)',   // CodeSlots #ff3b30
]

// 只在"文字色"上检查（背景可能是合法的撞色）
const TEXT_BLOCKLIST = ['rgb(245, 245, 245)'] // Shredder #f5f5f5 墨色

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const issues = []

/* 页面内对比度工具：把前景/背景（必要时取另一元素的背景）算成 WCAG 比值 */
const CONTRAST_FN = `(() => {
  const cv = document.createElement('canvas'); cv.width = 1; cv.height = 1
  const ctx = cv.getContext('2d', { willReadFrequently: true })
  const norm = (css) => { ctx.clearRect(0,0,1,1); ctx.fillStyle='rgba(0,0,0,0)'; ctx.fillStyle=css; ctx.fillRect(0,0,1,1)
    const d = ctx.getImageData(0,0,1,1).data; return { r:d[0], g:d[1], b:d[2], a:d[3]/255 } }
  const lin = (v) => { v/=255; return v <= 0.03928 ? v/12.92 : Math.pow((v+0.055)/1.055, 2.4) }
  const lum = (c) => 0.2126*lin(c.r) + 0.7152*lin(c.g) + 0.0722*lin(c.b)
  const over = (fg,bg) => ({ r:fg.r*fg.a+bg.r*(1-fg.a), g:fg.g*fg.a+bg.g*(1-fg.a), b:fg.b*fg.a+bg.b*(1-fg.a), a:1 })
  const effBg = (el) => { let n=el; while(n){ const c=norm(getComputedStyle(n).backgroundColor); if(c.a>0.02) return c; n=n.parentElement } return {r:255,g:255,b:255,a:1} }
  window.__contrast = (sel, fgProp, bgSel) => {
    const el = document.querySelector(sel); if (!el) return null
    const base = effBg(el)
    let fg = norm(getComputedStyle(el)[fgProp || 'color']); if (fg.a < 0.999) fg = over(fg, base)
    const bgEl = bgSel ? document.querySelector(bgSel) : el
    let bg = norm(getComputedStyle(bgEl).backgroundColor); if (bg.a < 0.999) bg = over(bg, base)
    const l1 = lum(fg), l2 = lum(bg), hi = Math.max(l1,l2), lo = Math.min(l1,l2)
    return +((hi+0.05)/(lo+0.05)).toFixed(2)
  }
  return true })()`

const scan = async (page, label) => {
  await page.evaluate(CONTRAST_FN)
  const hits = await page.evaluate(({ bgL, fgL }) => {
    const out = []
    for (const el of Array.from(document.querySelectorAll('*'))) {
      const cs = getComputedStyle(el)
      const bg = cs.backgroundColor
      const fg = cs.color
      if (bgL.includes(bg)) out.push({ what: 'background', val: bg, cls: (el.className || el.tagName).toString().slice(0, 60) })
      if (fgL.includes(fg)) out.push({ what: 'color', val: fg, cls: (el.className || el.tagName).toString().slice(0, 60) })
    }
    return out
  }, { bgL: BLOCKLIST, fgL: TEXT_BLOCKLIST })
  if (hits.length) {
    issues.push(`${label}: 出现外来默认色 ${hits.length} 处`)
    hits.slice(0, 6).forEach((h) => console.log(`      ${h.what} ${h.val} → .${h.cls}`))
  }
  return hits.length === 0
}

const login = async (page) => {
  await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1400)
  await page.getByRole('button', { name: /一键使用演示账号/ }).click()
  await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
  await page.waitForSelector('.shell', { timeout: 8000 })
  await page.waitForTimeout(1200)
}

for (const theme of ['light', 'dark']) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  // 引导只在真实首登时出现，避免污染用例/截图
  await page.addInitScript(() => {
    localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
    /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
    localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
  })
  await page.addInitScript((t) => {
    localStorage.setItem('nx-proto-theme-mode', t)
    localStorage.setItem('nx-proto-palette', 'terracotta')
  }, theme)

  await page.goto(BASE, { waitUntil: 'networkidle' })
  await page.waitForTimeout(2200)
  console.log(`[${theme}] 入口页:`, (await scan(page, `${theme}/入口页`)) ? 'ok' : 'FAIL')

  await login(page)
  for (const [label, path] of [['工作台', '/#/app/workbench'], ['知识库列表', '/#/app/kb'], ['知识库详情', '/#/app/kb/1'], ['对话', '/#/app/chat'], ['Agent', '/#/app/agents'], ['账号', '/#/app/account']]) {
    await page.goto(BASE + path, { waitUntil: 'networkidle' })
    await page.waitForTimeout(1600)
    if (label === '工作台') await page.waitForTimeout(1200) // 等轮播渲染
    console.log(`[${theme}] ${label}:`, (await scan(page, `${theme}/${label}`)) ? 'ok' : 'FAIL')
  }

  // 展开向导（Stepper 只在这里出现）+ 关键对比度断言
  await page.goto(BASE + '/#/app/kb/1', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1600)
  await page.getByRole('button', { name: /导入文档/ }).click()
  await page.waitForTimeout(800)
  console.log(`[${theme}] 导入向导(Stepper):`, (await scan(page, `${theme}/导入向导`)) ? 'ok' : 'FAIL')
  const nextContrast = await page.evaluate(() => window.__contrast('.next-button'))
  if (nextContrast !== null && nextContrast >= 4.5) {
    console.log(`[${theme}] 向导「下一步」按钮文字对比度: ok (${nextContrast})`)
  } else {
    console.log(`[${theme}] 向导「下一步」按钮文字对比度: FAIL (${nextContrast})`)
    issues.push(`${theme}/向导下一步按钮对比度 ${nextContrast} < 4.5`)
  }
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)

  // 轮播指示点（非文字元素，门槛 3:1）
  await page.goto(BASE + '/#/app/workbench', { waitUntil: 'networkidle' })
  await page.waitForTimeout(2200)
  const indContrast = await page.evaluate(() => window.__contrast('.carousel-indicator.active', 'backgroundColor', '.lp-carousel .carousel-item'))
  if (indContrast !== null && indContrast >= 3) {
    console.log(`[${theme}] 轮播激活指示点对比度: ok (${indContrast})`)
  } else {
    console.log(`[${theme}] 轮播激活指示点对比度: FAIL (${indContrast})`)
    issues.push(`${theme}/轮播指示点对比度 ${indContrast} < 3`)
  }

  // 新建会话向导（同样是 Stepper）
  await page.goto(BASE + '/#/app/chat', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)
  await page.locator('.sb-row--quick').click()   // 「新对话」已移到全局侧栏
  await page.waitForTimeout(800)
  console.log(`[${theme}] 新建会话向导:`, (await scan(page, `${theme}/会话向导`)) ? 'ok' : 'FAIL')

  await page.close()
}

await browser.close()
if (issues.length) {
  console.log(`\n发现 ${issues.length} 类问题:`)
  issues.forEach((i) => console.log('  - ' + i))
  process.exit(1)
} else {
  console.log('\n用色扫描通过：页面上没有出现组件原始默认色')
}
