import { chromium } from 'playwright-core'
const OUT = process.env.QA_SHOTS_OUT ?? 'D:/develop/Prisma/docs/prototypes/shots'
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
  // B：输入区 PromptBar（含 @ 菜单展开，一眼能看到效果）
  await page.locator('.prompt-bar__input').fill('@')
  await page.waitForTimeout(700)
  await page.screenshot({ path: `${OUT}/z13-promptbar-at-menu-${theme}.png` })
  console.log(`ok  z13-promptbar-at-menu-${theme}`)
  await page.keyboard.press('Escape')
  await page.locator('.prompt-bar__input').fill('Redis 的持久化怎么选？')
  await page.locator('.prompt-bar__input').press('Enter')
  await page.waitForTimeout(900)
  // C：工具调用运行中 + B：输入区
  await page.screenshot({ path: `${OUT}/z13-toolcalls-running-${theme}.png` })
  console.log(`ok  z13-toolcalls-running-${theme}`)
  await page.waitForTimeout(4200)
  await page.screenshot({ path: `${OUT}/z13-toolcalls-done-${theme}.png` })
  console.log(`ok  z13-toolcalls-done-${theme}`)
  await page.close()
}
await browser.close()
