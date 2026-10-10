// 入口页改版截图：锁 A 版 / 无进度条 / 胶囊登录按钮 / 统一按钮与页脚
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
const shot = async (n, o) => { await page.screenshot({ path: `${OUT}/${n}.png`, ...o }); console.log('ok  ' + n) }

await page.goto(BASE, { waitUntil: 'networkidle' })
await page.waitForTimeout(2600)
await shot('z2-landing-hero-light')

// 暗色
await setTheme(page, 'dark')
await page.waitForTimeout(1600)
await shot('z2-landing-hero-dark')
await setTheme(page, 'light')
await page.waitForTimeout(1200)

// 底部 CTA + 页脚
await page.evaluate(() => window.scrollTo({ top: document.documentElement.scrollHeight }))
await page.waitForTimeout(1200)
await shot('z2-landing-cta-footer')

// 导航滚动高亮（滚到 Agent 段）
await page.locator('.lp-pill .rubber-segment button.rubber-segment__item', { hasText: 'Agent' }).click()
await page.waitForTimeout(1500)
await shot('z2-landing-nav-active-agent')

await browser.close()
console.log('入口页改版截图完成')
