// 原型 QA + 全页截图：入口页（3 版构图 / 5 背景画廊 / 组件展示）+ 应用内 19 视角
import { chromium } from 'playwright-core'
import { setTheme, setPalette } from './_theme.mjs'
import { mkdirSync } from 'node:fs'

const BASE = 'http://localhost:5175'
const OUT = 'D:/develop/Prisma/docs/prototypes/shots'
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
const kbtab = (label) => page.locator('.page .rubber-segment button.rubber-segment__item', { hasText: label }).first()

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
await shot('lp-agents-1')
// 切换到第二个 Agent，验证多 Agent 切换
await page.locator('.lp-agents-pick .rubber-segment__item', { hasText: '代码审查员' }).first().click()
await page.waitForTimeout(1200)
await shot('lp-agents-2')
await noOverflow('agents')

await page.evaluate(() => window.scrollTo(0, 0))
await page.waitForTimeout(900)
await shot('lp-full-light', { fullPage: true })

// ============ 登录 → 应用内 ============
await page.locator('.lp-pill button', { hasText: '登录' }).first().click()
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

await page.getByRole('button', { name: /一键使用演示账号/ }).click()
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

await page.locator('.kb-inner').first().click()
await page.waitForTimeout(1400)
await shot('p-kb-detail-docs-light')
await noOverflow('kb-detail')

await kbtab('问答').click()
await page.waitForTimeout(600)
const qaInput = page.locator('input[placeholder*="B+ 树为什么"]')
await qaInput.fill('B+ 树为什么适合做索引？')
await page.getByRole('button', { name: '提问', exact: true }).click()
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
const testIn = page.locator('input[placeholder*="帮我出一道"]')
await testIn.fill('出一道 JVM 调优题并讲解')
await page.getByRole('button', { name: '运行', exact: true }).click()
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