// 统一性改造截图：橡皮导航 / 进度条 / 三个控件统一 / 路由过渡
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
const shot = async (n) => { await page.screenshot({ path: `${OUT}/${n}.png` }); console.log('ok  ' + n) }

// 1. 入口页：橡皮导航 + 阅读进度条（滚到中段）
await page.goto(BASE, { waitUntil: 'networkidle' })
await page.waitForTimeout(2400)
await page.evaluate(() => window.scrollTo({ top: document.documentElement.scrollHeight * 0.45 }))
await page.waitForTimeout(900)
await shot('z-landing-rubber-nav-progress')
await page.locator('.lp-pill .rubber-segment button.rubber-segment__item', { hasText: 'Agent' }).click()
await page.waitForTimeout(1400)
await shot('z-landing-nav-agent-active')

// 2. 登录页：橡皮 tab
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(1500)
await page.locator('.auth-card .rubber-segment button.rubber-segment__item', { hasText: '注册' }).click()
await page.waitForTimeout(700)
await shot('z-login-rubber-tab')

// 3. 预览工具条：两个橡皮分段（暗色）
await setTheme(page, 'dark')
await page.waitForTimeout(900)
await shot('z-previewbar-rubber-dark')

// 4. 对话页：Agent 横向橡皮选择
await page.locator('.auth-card .rubber-segment button.rubber-segment__item', { hasText: '登录' }).click()
await page.waitForTimeout(400)
await page.locator('#li-u').fill('qa-runner')
await page.locator('#li-p').fill('qa-runner-password')
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
await page.waitForSelector('.shell', { timeout: 8000 })
await page.waitForTimeout(1500)
await page.locator('.sb-nav button.rubber-segment__item', { hasText: '对话' }).click()
await page.waitForTimeout(1200)
await page.locator('.mode-seg button.rubber-segment__item', { hasText: 'Agent' }).click()
await page.waitForTimeout(400)
await page.locator('.agent-seg button.rubber-segment__item', { hasText: 'Java 代码审查员' }).click()
await page.waitForTimeout(800)
await shot('z-chat-agent-rubber')

await browser.close()
console.log('统一性截图完成')
