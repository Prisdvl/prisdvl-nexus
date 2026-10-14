// 卡片粒子跟随配色 + 引用高亮自动取消
import { chromium } from 'playwright-core'

const BASE = 'http://localhost:5175'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
// 引导只在真实首登时出现，避免污染用例/截图
await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
  /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
})
const issues = []
page.on('pageerror', (e) => issues.push(`pageerror: ${String(e.message).slice(0, 140)}`))
page.on('console', (m) => { if (m.type() === 'error') issues.push(`console.error: ${m.text().slice(0, 140)}`) })

const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`)
  if (!ok) issues.push(name)
}

/** 悬停一张卡片，采样画布里已绘制像素的平均色（粒子色） */
async function sampleParticles(index = 0) {
  const card = page.locator('.kb-grid .pixel-card').nth(index)
  await card.scrollIntoViewIfNeeded()
  await page.mouse.move(4, 4)   // 先移开，保证重新触发 mouseenter（否则粒子不会重绘）
  await page.waitForTimeout(120)
  await card.hover()
  await page.waitForTimeout(1100)
  return page.evaluate((i) => {
    const c = document.querySelectorAll('.kb-grid .pixel-card canvas')[i]
    if (!c) return null
    const ctx = c.getContext('2d')
    const { data } = ctx.getImageData(0, 0, c.width, c.height)
    let r = 0, g = 0, b = 0, n = 0
    for (let p = 0; p < data.length; p += 4) {
      if (data[p + 3] > 8) { r += data[p]; g += data[p + 1]; b += data[p + 2]; n++ }
    }
    return n === 0 ? null : { r: Math.round(r / n), g: Math.round(g / n), b: Math.round(b / n), n }
  }, index)
}

await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done','1')   // 引导只在真实首登时出现，避免污染用例/截图
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))   // 高风险工具预授权：常规用例不被确认卡打断
  localStorage.setItem('nx-proto-auth', '1')
  localStorage.setItem('nx-proto-theme-mode', 'light')
  localStorage.setItem('nx-proto-palette', 'terracotta')
})
await page.goto(BASE + '/#/app/kb', { waitUntil: 'networkidle' })
await page.waitForTimeout(1800)

/* ---------- 1. 粒子随配色变化（画布采样） ---------- */
const warm = await sampleParticles(0)
check('卡片粒子确实绘制了像素', warm !== null && warm.n > 50, JSON.stringify(warm))
check('陶土橘配色下粒子偏暖（r > b）', warm && warm.r > warm.b, JSON.stringify(warm))

// 切到松石青：粒子应变冷
await page.evaluate(() => window.__nxTheme.setPalette('turquoise'))
await page.waitForTimeout(700)
const cool = await sampleParticles(1)
check('松石青配色下粒子偏冷（b > r）', cool && cool.b > cool.r, JSON.stringify(cool))
check('切换配色后粒子颜色确实变了', warm && cool && (Math.abs(cool.r - warm.r) > 8 || Math.abs(cool.b - warm.b) > 8))

// 切到青柠
await page.evaluate(() => window.__nxTheme.setPalette('lime'))
await page.waitForTimeout(700)
const lime = await sampleParticles(2)
check('青柠配色下粒子偏绿（g 最大）', lime && lime.g >= lime.r && lime.g >= lime.b, JSON.stringify(lime))

// 暗色下也应跟着变（更亮/更暗）
await page.evaluate(() => window.__nxTheme.setPalette('terracotta'))
await page.waitForTimeout(500)
const lightWarm = await sampleParticles(0)
await page.evaluate(() => window.__nxTheme.setMode('dark'))
await page.waitForTimeout(700)
const darkWarm = await sampleParticles(0)
check('明暗切换后粒子颜色跟随变化', lightWarm && darkWarm && (Math.abs(darkWarm.r - lightWarm.r) > 8 || Math.abs(darkWarm.b - lightWarm.b) > 8), `${JSON.stringify(lightWarm)} → ${JSON.stringify(darkWarm)}`)
await page.evaluate(() => window.__nxTheme.setMode('light'))
await page.waitForTimeout(400)

/* ---------- 2. 徽标中性、圆点强调色 ---------- */
const decor = await page.evaluate(() => {
  const badge = document.querySelector('.kb-inner .badge')
  const dot = document.querySelector('.kb-inner .meta .dot')
  const probe = document.createElement('span')
  document.body.appendChild(probe)
  probe.style.background = 'var(--primary)'
  const primary = getComputedStyle(probe).backgroundColor
  probe.remove()
  return {
    badgeTone: badge ? badge.getAttribute('data-tone') : 'missing',
    badgeBg: badge ? getComputedStyle(badge).backgroundColor : null,
    dotBg: dot ? getComputedStyle(dot).backgroundColor : null,
    primary,
  }
})
check('文档数徽标已中性（无 accent tone）', decor.badgeTone === null, String(decor.badgeTone))
check('更新时间圆点 = 主题强调色', decor.dotBg === decor.primary, `dot=${decor.dotBg} primary=${decor.primary}`)

/* ---------- 3. 引用高亮：进入即亮 → 2.5s 后取消并清 URL ---------- */
await page.goto(BASE + '/#/app/kb/1?doc=' + encodeURIComponent('集合框架.md'), { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
const during = await page.evaluate(() => ({
  hl: document.querySelectorAll('.doc-row[data-hl]').length,
  url: location.hash,
  transition: getComputedStyle(document.querySelector('.doc-row')).transitionProperty,
}))
check('进入时目标文档被高亮', during.hl === 1, `高亮 ${during.hl} 个`)
check('URL 带着 ?doc= 参数', during.url.includes('doc='), during.url.slice(0, 60))
check('高亮有淡出过渡（background-color）', during.transition.includes('background-color'), during.transition)

await page.waitForTimeout(1900) // 累计 ~3.1s
const after = await page.evaluate(() => ({
  hl: document.querySelectorAll('.doc-row[data-hl]').length,
  url: location.hash,
}))
check('2.5s 后高亮自动取消', after.hl === 0, `高亮 ${after.hl} 个`)
check('同时清掉 URL 的 ?doc= 参数', !after.url.includes('doc='), after.url.slice(0, 60))

/* ---------- 3.5 引用预览不伪造历史文档内容 ---------- */
await page.goto(BASE + '/#/app/chat', { waitUntil: 'networkidle' })
await page.waitForTimeout(1600)
await page.locator('.thread .cite-chip[data-clickable]').first().click()
await page.waitForTimeout(500)
const ref = await page.evaluate(() => ({
  open: !!document.querySelector('.ref-panel'),
  url: location.hash,
  error: document.querySelector('.ref-panel [role="alert"]')?.textContent ?? '',
  body: document.querySelector('.ref-panel .ref-body')?.textContent ?? '',
  sourceButtonDisabled: document.querySelector('.ref-panel .ref-head button[disabled]') !== null,
  inside: (() => { const r = document.querySelector('.ref-panel')?.getBoundingClientRect(); return r ? r.right <= innerWidth + 1 : false })(),
}))
check('点击引用在右侧打开面板（不跳页）', ref.open && ref.url.includes('/app/chat'))
check('历史 mock 引用缺少 ID 时明确说明且不展示伪造正文', ref.error.includes('未包含知识库与文档 ID') && !ref.body.includes('虚构'))
check('缺少来源 ID 时禁止跳转到假知识库', ref.sourceButtonDisabled)
check('引用面板完整落在视口内', ref.inside)
await page.locator('.ref-panel button[aria-label="关闭引用面板"]').click()
await page.waitForTimeout(250)

/* ---------- 4. 迟到的高亮清理不得顶掉后续导航（历史记录必须完好） ---------- */
await page.goto(BASE + '/#/app/kb/1?doc=' + encodeURIComponent('集合框架.md'), { waitUntil: 'networkidle' })
await page.waitForTimeout(2300) // 距 2.5s 的清理只差 ~200ms
await page.locator('.sb-nav button.rubber-segment__item', { hasText: '工作台' }).first().click()
await page.waitForTimeout(1500) // 越过原本的清理时刻
const afterLateClear = await page.evaluate(() => location.hash)
check('迟到的清理不会顶掉新页面', afterLateClear.includes('/app/workbench'), afterLateClear)

await page.goBack()
await page.waitForTimeout(900)
await page.goForward()
await page.waitForTimeout(900)
check('前进/后退仍正确（历史未被改写）', (await page.evaluate(() => location.hash)).includes('/app/workbench'), await page.evaluate(() => location.hash))

await browser.close()
if (issues.length) {
  console.log(`\n发现 ${issues.length} 个问题:`)
  issues.forEach((i) => console.log('  - ' + i))
  process.exit(1)
} else {
  console.log('\n卡片粒子与引用高亮专项 QA 全部通过')
}