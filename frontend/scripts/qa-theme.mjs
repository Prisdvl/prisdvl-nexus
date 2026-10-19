// 外观专项 QA：角落聚合控件 / 设置页外观区 / 跟随系统 / 持久化 / 悬浮条已移除
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'

const BASE = 'http://localhost:5175'
const OUT = process.env.QA_SHOTS_OUT ?? join(tmpdir(), 'prisma-recover-qa-shots')
mkdirSync(OUT, { recursive: true })

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
const appearance = () => page.evaluate(() => ({
  mode: document.documentElement.dataset.themeMode,
  theme: document.documentElement.dataset.theme,
  palette: document.documentElement.dataset.palette,
  stored: localStorage.getItem('nx-proto-theme-mode'),
}))
const nav = (l) => page.locator('.sb-nav button.rubber-segment__item', { hasText: l }).first()

/* ---------- 1. 入口页：悬浮条已移除 + 扩散主题控件 ---------- */
await page.goto(BASE, { waitUntil: 'networkidle' })
await page.waitForTimeout(2400)
check('入口页已无全局悬浮工具条', (await page.locator('.preview-tools').count()) === 0)
check('入口页有扩散主题控件', (await page.locator('.theme-control .theme-control__btn').count()) === 1)
const swatchCount = await page.locator('.theme-control__swatch').count()
check('配色圆点已挂载', swatchCount >= 4, `${swatchCount}`)
await page.locator('.theme-control__btn').click()
await page.waitForTimeout(400)
check('点击展开配色环', (await page.locator('.theme-control[data-picker-open]').count()) === 1)
await page.screenshot({ path: `${OUT}/y2-corner-panel-light.png` })
console.log('ok   y2-corner-panel-light（截图）')

/* ---------- 2. 切配色 / 主题生效并持久化 ---------- */
await page.locator('.theme-control__swatch[aria-label="松石青"]').click()
await page.waitForTimeout(600)
let st = await appearance()
check('切配色生效', st.palette === 'turquoise', st.palette)
check('选定后配色环收起', (await page.locator('.theme-control[data-picker-open]').count()) === 0)

await page.locator('.theme-control__btn').click()
await page.waitForTimeout(300)
await page.locator('.theme-control__btn').click()
await page.waitForTimeout(600)
st = await appearance()
check('展开后点中心可切浅/深色', st.theme === 'light' || st.theme === 'dark', JSON.stringify(st))
check('模式已持久化', st.stored === 'light' || st.stored === 'dark', st.stored || 'null')

/* 固定到暗色便于后续断言 */
await page.evaluate(() => window.__nxTheme.setMode('dark'))
await page.waitForTimeout(300)
st = await appearance()
check('切暗色生效', st.theme === 'dark', JSON.stringify(st))

/* ---------- 3. 离开悬停后控件仍在 ---------- */
await page.mouse.move(200, 200)
await page.waitForTimeout(300)
check('离开后主题按钮仍在', (await page.locator('.theme-control__btn').count()) === 1)

/* ---------- 4. 刷新后保持 ---------- */
await page.reload({ waitUntil: 'networkidle' })
await page.waitForTimeout(1500)
st = await appearance()
check('刷新后仍是暗色 + 松石青', st.theme === 'dark' && st.palette === 'turquoise', JSON.stringify(st))

/* ---------- 5. 跟随系统 ---------- */
await page.emulateMedia({ colorScheme: 'light' })
await page.evaluate(() => window.__nxTheme.setMode('system'))
await page.waitForTimeout(400)
check('system + 系统亮色 → 亮色', (await appearance()).theme === 'light')
await page.emulateMedia({ colorScheme: 'dark' })
await page.waitForTimeout(600)
check('system + 系统暗色 → 暗色', (await appearance()).theme === 'dark', (await appearance()).theme)
await page.evaluate(() => window.__nxTheme.setMode('light'))
await page.waitForTimeout(300)
await page.evaluate(() => window.__nxTheme.setPalette('terracotta'))
await page.waitForTimeout(300)

/* ---------- 6. 登录页也有角落控件 ---------- */
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(1600)
check('登录页有角落控件', (await page.locator('.theme-control .theme-control__btn').count()) === 1)
check('登录页无悬浮条', (await page.locator('.preview-tools').count()) === 0)

/* ---------- 7. 应用内：角落控件不出现，改到设置页 ---------- */
await page.locator('#li-u').fill('qa-runner')
await page.locator('#li-p').fill('qa-runner-password')
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
await page.waitForSelector('.shell', { timeout: 8000 })
await page.waitForTimeout(1500)
check('应用内不再有角落控件（收敛到设置页）', (await page.locator('.theme-control').count()) === 0)

await page.goto('http://localhost:5175/#/app/account', { waitUntil: 'networkidle' })   // 「账号」导航项已移除，直接走路由
  await page.waitForTimeout(900)
await page.waitForTimeout(1200)
// 设置页已是多级界面：先进「外观」分区再看控件
await page.locator('.settings-item', { hasText: '外观' }).first().click()
await page.waitForTimeout(600)
check('设置页有「外观」分区', (await page.locator('.settings-head h2', { hasText: '外观' }).count()) === 1)
// 外观区两个控件组：主题（prisdvl 配色下是 .theme-control 拟物开关，其它配色是分段控件）+ 配色（.accent-swatches）
const themeRow = page.locator('.appearance .appearance-row').first()
const themeSwitch = await themeRow.locator('.theme-control').count()
const themeSegment = await themeRow.locator('.rubber-segment').count()
const accentGroups = await page.locator('.appearance .accent-swatches').count()
check(
  '外观区含主题与配色两组控件',
  (await page.locator('.appearance .appearance-row').count()) === 2 && themeSwitch + themeSegment === 1 && accentGroups === 1,
  `主题开关 ${themeSwitch} / 主题分段 ${themeSegment} / 配色 ${accentGroups}`,
)
await page.screenshot({ path: `${OUT}/y2-settings-appearance-light.png` })
console.log('ok   y2-settings-appearance-light（截图）')

// 切到暗色：拟物开关是一枚按钮（亮→暗），分段控件则点「暗色」项
if (themeSwitch > 0) {
  await themeRow.locator('.theme-control__btn').click()
} else {
  await themeRow.locator('button.rubber-segment__item', { hasText: '暗色' }).click()
}
await page.waitForTimeout(500)
check('在设置页切暗色生效', (await appearance()).theme === 'dark')
await page.screenshot({ path: `${OUT}/y2-settings-appearance-dark.png` })
console.log('ok   y2-settings-appearance-dark（截图）')

await browser.close()
if (issues.length) {
  console.log(`\n发现 ${issues.length} 个问题:`)
  issues.forEach((i) => console.log('  - ' + i))
  process.exit(1)
} else {
  console.log('\n外观专项 QA 全部通过')
}