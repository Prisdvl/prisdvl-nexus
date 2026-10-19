// UI 回归截图：当前入口页与真实功能界面状态
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { chromium } from 'playwright-core'
import { setTheme, setPalette } from './_theme.mjs'
import { mkdirSync } from 'node:fs'

const BASE = 'http://localhost:5175'
const OUT = process.env.QA_SHOTS_OUT ?? join(tmpdir(), 'prisma-recover-qa-shots')
mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1.5 })
  // 引导只在真实首登时出现，避免污染用例/截图
  await page.addInitScript(() => {
    localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
    /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
    localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
  })
const issues = []
page.on('console', (m) => { if (m.type() === 'error') issues.push(`console.error: ${m.text().slice(0, 160)}`) })
page.on('pageerror', (e) => issues.push(`pageerror: ${String(e.message).slice(0, 160)}`))

const shot = async (name, opts) => { await page.screenshot({ path: `${OUT}/${name}.png`, ...opts }); console.log(`ok   ${name}`) }
const noOverflow = async (where) => {
  const o = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1)
  if (o) issues.push(`横向溢出: ${where}`)
}
const nav = (label) => page.locator('.sb-nav button.rubber-segment__item', { hasText: label }).first()
// 知识库详情页的根容器是 .kb-fixed，tab 在 .kb-top__tabs 里（不再有 .page 包裹）
const kbtab = (label) => page.locator('.kb-top__tabs button.rubber-segment__item', { hasText: label }).first()

// ============ 入口页 ============
await page.goto(BASE, { waitUntil: 'networkidle' })
await page.waitForTimeout(2600)
console.log('landing canvas:', await page.locator('.lp-hero canvas, .pattern-waves canvas').count())
await shot('lp-A-light')
await noOverflow('landing A')

await setTheme(page, 'dark')
await shot('lp-A-dark')
await setPalette(page, 'lime')
await shot('lp-A-dark-lime')
await setPalette(page, 'terracotta')
await setTheme(page, 'light')


await page.locator('#what').scrollIntoViewIfNeeded()
await page.waitForTimeout(900)
await shot('lp-what')
await noOverflow('what')

await page.locator('#flow').scrollIntoViewIfNeeded()
await page.waitForTimeout(900)
await shot('lp-flow')
await noOverflow('flow')

await page.locator('#agents').scrollIntoViewIfNeeded()
await page.waitForTimeout(900)
const modeCount = await page.locator('.lp-modes article').count()
console.log(`${modeCount === 3 ? 'ok' : 'FAIL'} 入口页展示三个真实会话模式说明（${modeCount}）`)
if (modeCount !== 3) process.exitCode = 1
await shot('lp-modes')
await noOverflow('modes')

await page.evaluate(() => window.scrollTo(0, 0))
await page.waitForTimeout(900)
await shot('lp-full-light', { fullPage: true })

// ============ 登录 → 应用内 ============
await page.locator('.floating-nav__action .btn-primary').first().click()
await page.waitForSelector('.auth-card')
await page.waitForTimeout(1500)
await shot('p-auth-light-terracotta')
await page.locator('.auth-card .rubber-segment button.rubber-segment__item', { hasText: '注册' }).click()
await page.waitForTimeout(400)
await shot('p-auth-register-light')
await page.locator('.auth-card .rubber-segment button.rubber-segment__item', { hasText: '登录' }).click()
await page.waitForTimeout(300)
await setTheme(page, 'dark')
await shot('p-auth-dark-terracotta')
await setPalette(page, 'lime')
await shot('p-auth-dark-lime')
await setPalette(page, 'terracotta')
await setTheme(page, 'light')

await page.locator('#li-u').fill('qa-runner')
await page.locator('#li-p').fill('qa-runner-password')
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
await page.waitForSelector('.shell', { timeout: 8000 })
await page.waitForTimeout(2400)
await shot('p-workbench-light')
await noOverflow('workbench')
await setTheme(page, 'dark')
await shot('p-workbench-dark')
await setPalette(page, 'lime')
await shot('p-workbench-dark-lime')
await setPalette(page, 'terracotta')
await setTheme(page, 'light')

await nav('知识库').click()
await page.waitForTimeout(1400)
await shot('p-kb-list-light')
await noOverflow('kb-list')

await page.locator('.kb-table tbody tr').first().click()
await page.waitForTimeout(1400)
await shot('p-kb-detail-docs-light')
await noOverflow('kb-detail')

await kbtab('问答').click()
await page.waitForTimeout(600)
// 问答空态现在是三枚「示例问题」按钮（placeholder 已换成通用文案），点第一枚即发起提问
const starter = page.locator('.kb-qa__starter').first()
await starter.click()
await page.waitForTimeout(1300)
await shot('p-kb-qa-thinking')
await page.waitForTimeout(2300)
await shot('p-kb-qa-answer')
await noOverflow('kb-qa')

await kbtab('高级检索').click()
await page.waitForTimeout(700)
await shot('p-kb-detail-adv-light')
await noOverflow('kb-adv')

await nav('对话').click()
await page.waitForTimeout(1200)
await shot('p-chat-light')
await noOverflow('chat')
const composer = page.locator('.prompt-bar__input')
await composer.fill('MySQL 索引失效的场景有哪些？')
await composer.press('Enter')
await page.waitForTimeout(1300)
await shot('p-chat-streaming')
await page.waitForTimeout(1700)
await setTheme(page, 'dark')
await shot('p-chat-dark')
await setTheme(page, 'light')

await nav('Agent').click()
await page.waitForTimeout(1600)
await shot('p-agents-light')
await noOverflow('agents')
// 测试台输入已从单行 input 换成 .bench-form 里的 textarea（aria-label 由文案提供）
const testIn = page.locator('.bench-form textarea')
await testIn.fill('出一道 JVM 调优题并讲解')
await page.locator('.bench-form button[type="submit"]').click()
await page.waitForTimeout(1200)
await shot('p-agents-testing')
await page.waitForTimeout(1500)

await page.goto('http://localhost:5175/#/app/account', { waitUntil: 'networkidle' })   // 「账号」导航项已移除，直接走路由
  await page.waitForTimeout(900)
await page.waitForTimeout(1000)
await shot('p-account-light')
await setTheme(page, 'dark')
await shot('p-account-dark')

await browser.close()
if (issues.length) {
  console.log('发现问题:')
  issues.forEach((i) => console.log('  - ' + i))
  process.exit(1)
} else {
  console.log('QA/截图全部通过（无 console error / 无横向溢出）')
}