// 无障碍与键盘可达性审计：只看客观可测项，输出问题清单
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
page.on('pageerror', (e) => console.log('  pageerror:', String(e.message).slice(0, 100)))

const findings = []
const note = (page_, kind, detail) => {
  findings.push({ page: page_, kind, detail })
  console.log(`  [${kind}] ${detail}`)
}

const login = async () => {
  await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1400)
  // 上一步的方向键审计可能把 tab 切到了注册，而演示账号按钮只在登录 tab 里
  const demo = page.getByRole('button', { name: /一键使用演示账号/ })
  if ((await demo.count()) === 0) {
    await page.locator('.auth-card .rubber-segment button.rubber-segment__item', { hasText: '登录' }).first().click()
    await page.waitForTimeout(500)
  }
  await demo.click()
  await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
  await page.waitForSelector('.shell', { timeout: 8000 })
  await page.waitForTimeout(1200)
}

/* ---------- 通用检查 ---------- */
async function auditPage(label, openExtras) {
  const res = await page.evaluate(() => {
    const out = {}

    // 1. 图标按钮是否有可访问名（无文字且无 aria-label/title）
    out.namelessButtons = Array.from(document.querySelectorAll('button')).filter((b) => {
      const text = (b.innerText || '').trim()
      const aria = b.getAttribute('aria-label') || b.getAttribute('title') || b.getAttribute('aria-labelledby')
      return !text && !aria
    }).map((b) => (b.className || 'button').toString().slice(0, 50))

    // 2. 表单控件是否有标签
    out.unlabeledInputs = Array.from(document.querySelectorAll('input, textarea, select')).filter((el) => {
      if (el.type === 'hidden') return false
      const id = el.id
      const hasLabel = id && document.querySelector(`label[for="${id}"]`)
      return !hasLabel && !el.getAttribute('aria-label') && !el.getAttribute('placeholder') && !el.closest('label')
    }).map((el) => (el.id || el.name || el.type || 'input').toString().slice(0, 40))

    // 3. 标题层级是否跳级
    const hs = Array.from(document.querySelectorAll('h1,h2,h3,h4,h5,h6'))
    const levels = hs.map((h) => Number(h.tagName[1]))
    out.headingSkips = levels.filter((lv, i) => i > 0 && lv - levels[i - 1] > 1).length
    out.h1Count = levels.filter((l) => l === 1).length

    // 4. 地标与跳转链接
    out.landmarks = {
      main: document.querySelectorAll('main').length,
      nav: document.querySelectorAll('nav').length,
      aside: document.querySelectorAll('aside').length,
      header: document.querySelectorAll('header').length,
    }
    out.hasSkipLink = !!document.querySelector('a[href^="#"]')?.textContent?.includes('跳到') ||
      Array.from(document.querySelectorAll('a')).some((a) => /跳到|跳至|skip/i.test(a.textContent || ''))

    // 5. 正 tabindex（会打乱 Tab 顺序）
    out.positiveTabindex = Array.from(document.querySelectorAll('[tabindex]'))
      .filter((el) => Number(el.getAttribute('tabindex')) > 0)
      .map((el) => (el.className || el.tagName).toString().slice(0, 40))

    // 6. 橡皮分段的 ARIA 与键盘支持线索
    const seg = document.querySelector('.rubber-segment')
    out.segAttrs = seg ? {
      role: seg.getAttribute('role'),
      itemRole: seg.querySelector('[role]')?.getAttribute('role'),
      itemCount: seg.querySelectorAll('button.rubber-segment__item').length,
      checkedCount: seg.querySelectorAll('[aria-checked="true"]').length,
    } : null

    // 7. 可聚焦元素总数（Tab 顺序规模）
    out.focusable = document.querySelectorAll('a[href],button:not([disabled]),input:not([disabled]),textarea,select,[tabindex]:not([tabindex="-1"])').length
    return out
  })

  if (res.namelessButtons.length) note(label, '无名按钮', `${res.namelessButtons.length} 个：${res.namelessButtons.slice(0, 4).join(' | ')}`)
  if (res.unlabeledInputs.length) note(label, '无标签输入', `${res.unlabeledInputs.length} 个：${res.unlabeledInputs.slice(0, 4).join(' | ')}`)
  if (res.headingSkips) note(label, '标题跳级', `${res.headingSkips} 处`)
  if (res.h1Count !== 1) note(label, 'h1 数量异常', `${res.h1Count} 个`)
  if (!res.landmarks.main) note(label, '缺少 main 地标', JSON.stringify(res.landmarks))
  if (!res.hasSkipLink) note(label, '缺少跳到主内容链接', '键盘用户要 Tab 穿过整个导航')
  if (res.positiveTabindex.length) note(label, '正 tabindex', `${res.positiveTabindex.length} 个`)
  console.log(`  · ${label}: 可聚焦 ${res.focusable} 个；地标 ${JSON.stringify(res.landmarks)}；分段 ${JSON.stringify(res.segAttrs)}`)

  if (openExtras) await openExtras()
}

/* ---------- 焦点可见性：Tab 走一遍看有没有元素"看不见焦点" ---------- */
async function auditFocusVisible(label, steps = 40) {
  const invisible = []
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.locator('body').click({ position: { x: 2, y: 2 } }).catch(() => {})
  for (let i = 0; i < steps; i++) {
    await page.keyboard.press('Tab')
    const info = await page.evaluate(() => {
      const el = document.activeElement
      if (!el || el === document.body) return null
      const cs = getComputedStyle(el)
      const hasOutline = cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0
      const hasShadow = cs.boxShadow && cs.boxShadow !== 'none'
      const hasBorder = cs.borderColor && cs.borderStyle !== 'none'
      return {
        tag: el.tagName.toLowerCase(),
        cls: (el.className || '').toString().slice(0, 40),
        text: (el.innerText || el.getAttribute('aria-label') || '').trim().slice(0, 18),
        visible: hasOutline || hasShadow || hasBorder,
      }
    })
    if (info && !info.visible) invisible.push(`${info.tag}.${info.cls}${info.text ? `(${info.text})` : ''}`)
  }
  if (invisible.length) note(label, '焦点不可见', `${invisible.length} 个：${invisible.slice(0, 5).join(' | ')}`)
  else console.log(`  · ${label}: Tab ${steps} 次，焦点样式都可见`)
}

/* ---------- 橡皮分段是否支持方向键（role=radio 应当支持） ---------- */
async function auditArrowKeys(label) {
  const sel = '.rubber-segment button.rubber-segment__item'
  const seg = page.locator('.rubber-segment').first()
  if ((await seg.count()) === 0) return
  const items = seg.locator('button.rubber-segment__item')
  const n = await items.count()
  if (n < 2) return

  /*
   * 必须把焦点放在**当前激活项**上再按方向键：
   * 组件用 roving tabindex（激活项 0、其余 -1），并按"激活下标"计算下一项。
   * 早期版本这里聚焦第一项，导致非激活项被聚焦时判定成"无反应"——那是用例的错，不是组件的错。
   */
  const activeIdx = await page.evaluate((s) => Array.from(document.querySelectorAll(s)).findIndex((b) => b.getAttribute('aria-checked') === 'true'), sel)
  if (activeIdx < 0) return
  await items.nth(activeIdx).focus()
  const key = (await page.evaluate(() => document.querySelector('.rubber-segment')?.getAttribute('data-vertical') !== null)) ? 'ArrowDown' : 'ArrowRight'
  const expected = Math.min(n - 1, activeIdx + 1)
  await page.keyboard.press(key)
  await page.waitForTimeout(320)
  const after = await page.evaluate((s) => Array.from(document.querySelectorAll(s)).findIndex((b) => b.getAttribute('aria-checked') === 'true'), sel)
  if (after !== expected) note(label, '分段方向键异常', `${key} 后激活下标 ${activeIdx} → ${after}（期望 ${expected}）`)
  else console.log(`  · ${label}: ${key} 可切换分段（${activeIdx} → ${after}）`)
}

console.log('=== 入口页 ===')
await page.goto(BASE, { waitUntil: 'networkidle' })
await page.waitForTimeout(2200)
await auditPage('入口页')
await auditFocusVisible('入口页', 30)

console.log('\n=== 登录页 ===')
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(1500)
await auditPage('登录页')
await auditArrowKeys('登录页')

console.log('\n=== 工作台 ===')
await login()
await page.goto(BASE + '/#/app/workbench', { waitUntil: 'networkidle' })
await page.waitForTimeout(1800)
await auditPage('工作台')
await auditFocusVisible('工作台', 40)

for (const [label, path] of [['知识库', '/#/app/kb'], ['知识库详情', '/#/app/kb/1'], ['对话', '/#/app/chat'], ['Agent', '/#/app/agents'], ['账号', '/#/app/account']]) {
  console.log(`\n=== ${label} ===`)
  await page.goto(BASE + path, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)
  await auditPage(label)
  await auditArrowKeys(label)
}

console.log(`\n共发现 ${findings.length} 类问题`)
const byKind = findings.reduce((m, f) => { m[f.kind] = (m[f.kind] || 0) + 1; return m }, {})
console.log('汇总:', JSON.stringify(byKind, null, 2))
await browser.close()