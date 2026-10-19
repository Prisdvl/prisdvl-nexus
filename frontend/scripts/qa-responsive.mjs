// 响应式 QA：桌面 / 平板 / 手机三档视口下的布局与交互
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'

const BASE = 'http://localhost:5175'
const OUT = process.env.QA_SHOTS_OUT ?? 'D:/develop/Prisma/docs/prototypes/shots'
mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const issues = []
const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`)
  if (!ok) issues.push(name)
}

const login = async (page) => {
  await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1400)
  await page.locator('#li-u').fill('qa-runner')
await page.locator('#li-p').fill('qa-runner-password')
  await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
  await page.waitForSelector('.shell', { timeout: 8000 })
  await page.waitForTimeout(1200)
}
/**
 * 量横向溢出前先等页面离开加载态。
 * 加载态里的 `.lattice-loader__text` 会比视口多出 1–3px（本机实测 391 vs 390），
 * 常见的红是"量得太早"而不是布局真的溢出；超时也照量，真有溢出依然判 FAIL。
 */
const settle = (page) => page.locator('.lattice-loader').first().waitFor({ state: 'detached', timeout: 15000 }).catch(() => {})
const overflow = async (page) => {
  await settle(page)
  return page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1)
}
const nav = (page, l) => page.locator('.sb-nav button.rubber-segment__item', { hasText: l }).first()

/* ================= 桌面 1440 ================= */
{
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1.25 })
  // 引导只在真实首登时出现，避免污染用例/截图
  await page.addInitScript(() => {
    localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
    /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
    localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
  })
  page.on('pageerror', (e) => issues.push(`desktop pageerror: ${String(e.message).slice(0, 120)}`))
  await page.goto(BASE, { waitUntil: 'networkidle' })
  await page.waitForTimeout(2000)
  check('桌面：顶栏隐藏', !(await page.locator('.mobile-bar').isVisible().catch(() => false)))
  await page.screenshot({ path: `${OUT}/r-desktop-landing.png` })
  await login(page)
  const sbWidth = await page.locator('.sidebar').evaluate((el) => el.getBoundingClientRect().width)
  check('桌面：侧栏完整宽度', sbWidth > 200, `${Math.round(sbWidth)}px`)
  check('桌面：导航文字可见', await page.locator('.sb-item span:last-child').first().isVisible())
  for (const [label, path] of [['工作台', '/#/app/workbench'], ['知识库', '/#/app/kb'], ['对话', '/#/app/chat'], ['Agent', '/#/app/agents'], ['账号', '/#/app/account']]) {
    await page.goto(BASE + path, { waitUntil: 'networkidle' })
    await page.waitForTimeout(1400)
    check(`桌面无横向溢出：${label}`, !(await overflow(page)))
  }
  await page.goto(BASE + '/#/app/workbench', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)
  await page.screenshot({ path: `${OUT}/r-desktop-workbench.png` })
  await page.close()
}

/* ================= 平板 820 ================= */
{
  const page = await browser.newPage({ viewport: { width: 820, height: 1180 }, deviceScaleFactor: 1.25 })
  // 引导只在真实首登时出现，避免污染用例/截图
  await page.addInitScript(() => {
    localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
    /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
    localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
  })
  page.on('pageerror', (e) => issues.push(`tablet pageerror: ${String(e.message).slice(0, 120)}`))
  await login(page)
  const sbWidth = await page.locator('.sidebar').evaluate((el) => el.getBoundingClientRect().width)
  check('平板：侧栏收成图标栏', sbWidth <= 80, `${Math.round(sbWidth)}px`)
  check('平板：导航文字隐藏', !(await page.locator('.sb-item span:last-child').first().isVisible()))
  check('平板：顶栏仍隐藏', !(await page.locator('.mobile-bar').isVisible().catch(() => false)))
  await page.screenshot({ path: `${OUT}/r-tablet-workbench.png` })
  for (const path of ['/#/app/kb', '/#/app/chat', '/#/app/account']) {
    await page.goto(BASE + path, { waitUntil: 'networkidle' })
    await page.waitForTimeout(1300)
    check(`平板无横向溢出：${path}`, !(await overflow(page)))
  }
  await page.goto(BASE + '/#/app/chat', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1400)
  await page.screenshot({ path: `${OUT}/r-tablet-chat.png` })
  await page.close()
}

/* ================= 手机 390 ================= */
{
  const page = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true })
  // 引导只在真实首登时出现，避免污染用例/截图
  await page.addInitScript(() => {
    localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
    /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
    localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
  })
  page.on('pageerror', (e) => issues.push(`mobile pageerror: ${String(e.message).slice(0, 120)}`))
  await page.goto(BASE, { waitUntil: 'networkidle' })
  await page.waitForTimeout(2000)
  check('手机：落地页无横向溢出', !(await overflow(page)))
  check('手机：顶部胶囊导航隐藏', !(await page.locator('.lp-pill-nav').isVisible().catch(() => false)))

/* ---------- 入口页导航：桌面胶囊 / 窄屏 CardNav（按屏幕分工） ---------- */
const navMode = await page.evaluate(() => {
  const disp = (sel) => { const el = document.querySelector(sel); return el ? getComputedStyle(el).display : null }
  return { pill: disp('.lp-pill'), card: disp('.lp-cardnav'), cardNav: disp('.card-nav') }
})
check('手机：桌面胶囊导航整个不存在', navMode.pill === null || navMode.pill === 'none', `display=${navMode.pill}`)
check('手机：换成 CardNav 导航', navMode.card !== 'none' && navMode.cardNav !== 'none', JSON.stringify(navMode))

// 汉堡展开三张卡片
await page.locator('.hamburger-menu').click()
await page.waitForTimeout(900)
const expanded = await page.evaluate(() => ({
  open: document.querySelector('.card-nav')?.classList.contains('open'),
  cards: Array.from(document.querySelectorAll('.nav-card')).filter((c) => c.getBoundingClientRect().height > 10).length,
  links: Array.from(document.querySelectorAll('.nav-card-link')).map((l) => l.textContent.trim()),
}))
check('汉堡展开出 3 张卡片', expanded.open === true && expanded.cards === 3, `${expanded.cards} 张`)
check('卡片里含章节锚点（导航未丢失）', ['首页', '产品', '提问', '智能体'].every((s) => expanded.links.includes(s)), expanded.links.slice(0, 4).join(' / '))

// 卡片链接必须走滚动，不能被 hash 路由吃掉
const hashBefore = await page.evaluate(() => location.hash)
await page.locator('.nav-card-link', { hasText: '提问' }).first().click()
await page.waitForTimeout(1300)
const afterJump = await page.evaluate(() => ({ y: Math.round(window.scrollY), hash: location.hash }))
check('点卡片里的章节会滚动过去', afterJump.y > 400, `scrollY=${afterJump.y}`)
check('走滚动而不是路由跳转', afterJump.hash === hashBefore, `${hashBefore} → ${afterJump.hash}`)

// 窄屏的登录入口不能丢（组件默认在 ≤768px 隐藏 CTA）
await page.locator('.card-nav-cta-button').click()
await page.waitForTimeout(1100)
check('窄屏 CardNav 的登录按钮可用', (await page.evaluate(() => location.hash)).includes('/login'))

// 回到入口页，切到桌面尺寸：胶囊回来、CardNav 收起
await page.goto(BASE + '/', { waitUntil: 'networkidle' })
await page.setViewportSize({ width: 1440, height: 900 })
await page.waitForTimeout(900)
const deskMode = await page.evaluate(() => {
  const disp = (sel) => { const el = document.querySelector(sel); return el ? getComputedStyle(el).display : null }
  return { pill: disp('.lp-pill'), floating: disp('.floating-nav'), card: disp('.lp-cardnav') }
})
check('桌面：浮动导航回来', deskMode.floating === 'flex', JSON.stringify(deskMode))
check('桌面：CardNav 隐藏', deskMode.card === 'none', `display=${deskMode.card}`)
await page.setViewportSize({ width: 390, height: 844 })
await page.waitForTimeout(600)
  await page.screenshot({ path: `${OUT}/r-mobile-landing.png`, fullPage: false })

  await login(page)
  check('手机：顶栏出现', await page.locator('.mobile-bar').isVisible())
  const offscreen = await page.locator('.sidebar').evaluate((el) => el.getBoundingClientRect().right <= 1)
  check('手机：侧栏默认离屏', offscreen)

  // 汉堡打开抽屉
  await page.locator('.mobile-bar .mb-btn').click()
  await page.waitForTimeout(500)
  check('手机：抽屉打开', (await page.locator('.sidebar[data-open]').count()) === 1)
  check('手机：出现遮罩', (await page.locator('.sb-scrim').count()) === 1)
  await page.screenshot({ path: `${OUT}/r-mobile-drawer.png` })

  // 点导航 → 收起抽屉并跳转
  await nav(page, '对话').click()
  await page.waitForTimeout(1000)
  check('手机：选导航后抽屉收起', (await page.locator('.sidebar[data-open]').count()) === 0)
  check('手机：导航生效', (await page.evaluate(() => location.hash)).includes('/app/chat'))

  // 遮罩点击关闭
  await page.locator('.mobile-bar .mb-btn').click()
  await page.waitForTimeout(400)
  await page.locator('.sb-scrim').click({ position: { x: 360, y: 700 } })
  await page.waitForTimeout(400)
  check('手机：点遮罩关闭抽屉', (await page.locator('.sidebar[data-open]').count()) === 0)

  // 各页无横向溢出 + 统计卡单列
  for (const path of ['/#/app/workbench', '/#/app/kb', '/#/app/kb/1', '/#/app/chat', '/#/app/agents', '/#/app/account']) {
    await page.goto(BASE + path, { waitUntil: 'networkidle' })
    await page.waitForTimeout(1500)
    check(`手机无横向溢出：${path}`, !(await overflow(page)))
  }
  await page.goto(BASE + '/#/app/workbench', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1800)
  const cols = await page.locator('.stat-grid').evaluate((el) => getComputedStyle(el).gridTemplateColumns.split(' ').length)
  check('手机：统计卡单列', cols === 1, `${cols} 列`)
  await page.screenshot({ path: `${OUT}/r-mobile-workbench.png`, fullPage: true })

  // 弹窗宽度不超出视口
  await page.goto(BASE + '/#/app/kb/1', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)
  await page.getByRole('button', { name: /导入/ }).first().click()
  await page.waitForTimeout(600)
  const fits = await page.locator('.dialog').evaluate((el) => {
    const r = el.getBoundingClientRect()
    return r.width <= window.innerWidth + 1 && r.left >= -1
  })
  check('手机：弹窗不超出视口', fits)
  await page.screenshot({ path: `${OUT}/r-mobile-dialog.png` })
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)

  // 验证码组件在小屏可用：
  // ⚠️ 已移除（2026-10-11）。账号页的「邮箱验证」在后端没有发送/校验接口，
  // 该入口已诚实地改为禁用态「暂不可用」（见 pages/settings/sections.tsx 的 EmailSection），
  // 因此"点『验证邮箱』→ 检查 .code-slots__row"这条流程已不存在，脚本不再断言它。
  await page.close()
}

// ===== 宽屏 + 暗色主题（2026-10-11 · 任务 §5 缺口补齐）=====
{
  const wide = await browser.newPage({ viewport: { width: 1920, height: 1080 } })
  await wide.addInitScript(() => {
    localStorage.setItem('nx-proto-tour-done', '1')
    localStorage.setItem('nx-proto-auth', '1')
    localStorage.setItem('nx-proto-theme', 'dark')
  })
  for (const path of ['/#/app/workbench', '/#/app/kb', '/#/app/chat', '/#/app/agents']) {
    await wide.goto(BASE + path, { waitUntil: 'networkidle' })
    await wide.waitForTimeout(900)
    const over = await wide.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
    check(`宽屏暗色无横向溢出：${path}`, over <= 1, `溢出 ${over}px`)
  }
  await wide.screenshot({ path: `${OUT}/r-wide-dark-workbench.png` })
  await wide.close()
}
await browser.close()
if (issues.length) {
  console.log(`\n发现 ${issues.length} 个问题:`)
  issues.forEach((i) => console.log('  - ' + i))
  process.exit(1)
} else {
  console.log('\n响应式 QA 全部通过')
}
