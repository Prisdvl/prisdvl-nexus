import { chromium } from 'playwright-core'
const OUT = 'D:/develop/Prisma/docs/prototypes/shots'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
for (const theme of ['light', 'dark']) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1.5 })
  // 引导只在真实首登时出现，避免污染用例/截图
  await page.addInitScript(() => {
    localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
    /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
    localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
  })
  await page.addInitScript((t) => { localStorage.setItem('nx-proto-auth','1'); localStorage.setItem('nx-proto-theme-mode', t) }, theme)
  await page.goto('http://localhost:5175/#/app/chat', { waitUntil: 'networkidle' })
  await page.waitForTimeout(2200)
  for (let i = 0; i < 3; i++) {
    await page.locator('.prompt-bar__input').fill(`第 ${i + 1} 个问题：并发容器怎么选`)
    await page.locator('.prompt-bar__input').press('Enter')
    await page.waitForTimeout(4600)
  }
  // 悬停中间一条，展示浮层与完整横线
  await page.locator('.msg-nav__item').nth(Math.floor((await page.locator('.msg-nav__item').count()) / 2)).hover()
  await page.waitForTimeout(600)
  await page.screenshot({ path: `${OUT}/z18-rail-full-${theme}.png` })
  console.log(`ok  z18-rail-full-${theme}`)
  await page.close()
}
await browser.close()
