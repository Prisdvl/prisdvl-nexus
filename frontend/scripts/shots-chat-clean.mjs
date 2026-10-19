import { chromium } from "playwright-core"
import { join } from 'node:path'
import { tmpdir } from 'node:os'
const OUT = process.env.QA_SHOTS_OUT ?? join(tmpdir(), 'prisma-recover-qa-shots')
const browser = await chromium.launch({ channel: "msedge", headless: true })
for (const vp of [{ w: 1440, h: 900, n: "std" }, { w: 1045, h: 547, n: "narrow" }]) {
  for (const theme of ["dark", "light"]) {
    const page = await browser.newPage({ viewport: { width: vp.w, height: vp.h }, deviceScaleFactor: 1.5 })
  // 引导只在真实首登时出现，避免污染用例/截图
  await page.addInitScript(() => {
    localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
    /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
    localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
  })
    await page.addInitScript((t) => { localStorage.setItem("nx-proto-auth", "1"); localStorage.setItem("nx-proto-theme-mode", t) }, theme)
    await page.goto("http://localhost:5175/#/app/chat", { waitUntil: "networkidle" })
    await page.waitForTimeout(2400)
    await page.screenshot({ path: `${OUT}/z29-chat-${vp.n}-${theme}.png` })
    console.log(`ok  z29-chat-${vp.n}-${theme}`)
    await page.close()
  }
}
await browser.close()
