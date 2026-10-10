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
  // 对话页整体布局（滚动条贴右 + 时间轴浮层）
  await page.goto('http://localhost:5175/#/app/chat', { waitUntil: 'networkidle' })
  await page.waitForTimeout(2200)
  await page.screenshot({ path: `${OUT}/z10-chat-${theme}.png` })
  console.log(`ok  z10-chat-${theme}`)
  // Agent 卡片粒子（应与知识库一致）
  await page.goto('http://localhost:5175/#/app/agents', { waitUntil: 'networkidle' })
  await page.waitForTimeout(2000)
  const card = page.locator('.agent-grid .pixel-card').first()
  await card.scrollIntoViewIfNeeded()
  await page.mouse.move(4, 4); await page.waitForTimeout(150)
  await card.hover(); await page.waitForTimeout(1200)
  await page.locator('.agent-grid').screenshot({ path: `${OUT}/z10-agent-particles-${theme}.png` })
  console.log(`ok  z10-agent-particles-${theme}`)
  await page.close()
}
await browser.close()
