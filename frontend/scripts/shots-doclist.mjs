// 文档列表（状态列靠右对齐 + 图标中性化）截图
import { chromium } from 'playwright-core'
const BASE = 'http://localhost:5175'
const OUT = process.env.QA_SHOTS_OUT ?? 'D:/develop/Prisma/docs/prototypes/shots'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
for (const theme of ['dark', 'light']) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1.5 })
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
  await page.goto(BASE + '/#/app/kb/1', { waitUntil: 'networkidle' })
  await page.waitForTimeout(2000)
  await page.screenshot({ path: `${OUT}/w-doclist-${theme}.png` })
  console.log(`ok  w-doclist-${theme}`)
  await page.close()
}
await browser.close()
