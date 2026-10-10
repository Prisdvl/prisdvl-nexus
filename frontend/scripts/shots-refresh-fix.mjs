// 刷新修复证据：加载态仅骨架屏 / 修复后的知识库页（背景已调淡）
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'

const BASE = 'http://localhost:5175'
const OUT = 'D:/develop/Prisma/docs/prototypes/shots'
mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })

for (const theme of ['dark', 'light']) {
  const page = await browser.newPage({ viewport: { width: 1040, height: 620 }, deviceScaleFactor: 2 })
  // 引导只在真实首登时出现，避免污染用例/截图
  await page.addInitScript(() => {
    localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
    /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
    localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
  })
  await page.addInitScript((t) => {
    localStorage.setItem('nx-proto-theme', t)
    localStorage.setItem('nx-proto-auth', '1')
  }, theme)

  // 加载窗口内截图（骨架屏）
  await page.goto(BASE + '/#/app/kb', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(420)
  await page.screenshot({ path: `${OUT}/v-kb-loading-${theme}.png` })
  console.log(`ok  v-kb-loading-${theme}`)

  // 加载完成后
  await page.waitForTimeout(1600)
  await page.screenshot({ path: `${OUT}/v-kb-loaded-${theme}.png` })
  console.log(`ok  v-kb-loaded-${theme}`)
  await page.close()
}

await browser.close()
console.log('刷新修复证据截图完成')
