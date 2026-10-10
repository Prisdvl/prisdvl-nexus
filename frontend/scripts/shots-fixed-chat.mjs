import { chromium } from 'playwright-core'
const OUT = 'D:/develop/Prisma/docs/prototypes/shots'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
for (const v of [{ w: 1440, h: 900, n: 'desktop' }, { w: 390, h: 844, n: 'mobile' }]) {
  for (const theme of ['light', 'dark']) {
    const page = await browser.newPage({ viewport: { width: v.w, height: v.h }, deviceScaleFactor: v.n === 'mobile' ? 2 : 1.5 })
  // 引导只在真实首登时出现，避免污染用例/截图
  await page.addInitScript(() => {
    localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
    /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
    localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
  })
    await page.addInitScript((t) => { localStorage.setItem('nx-proto-auth','1'); localStorage.setItem('nx-proto-theme-mode', t) }, theme)
    await page.goto('http://localhost:5175/#/app/chat', { waitUntil: 'networkidle' })
    await page.waitForTimeout(2200)
    await page.screenshot({ path: `${OUT}/z16-chat-fixed-${v.n}-${theme}.png` })
    console.log(`ok  z16-chat-fixed-${v.n}-${theme}`)
    await page.close()
  }
}
await browser.close()
