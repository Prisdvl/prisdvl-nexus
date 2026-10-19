import { chromium } from "playwright-core"
import { join } from 'node:path'
import { tmpdir } from 'node:os'
const OUT = process.env.QA_SHOTS_OUT ?? join(tmpdir(), 'prisma-recover-qa-shots')
const browser = await chromium.launch({ channel: "msedge", headless: true })
for (const theme of ["dark", "light"]) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
  // 引导只在真实首登时出现，避免污染用例/截图
  await page.addInitScript(() => {
    localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
    /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
    localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
  })
  await page.addInitScript((t) => { localStorage.setItem("nx-proto-auth", "1"); localStorage.setItem("nx-proto-theme-mode", t) }, theme)
  await page.goto("http://localhost:5175/#/app/chat", { waitUntil: "networkidle" })
  await page.waitForSelector(".sb-session", { timeout: 20000 })
  await page.waitForTimeout(1000)
  await page.locator(".sb-session").nth(2).click()
  await page.waitForTimeout(1200)
  const box = await page.locator(".sb-recent").boundingBox()
  await page.screenshot({ path: `${OUT}/z33-active-row-${theme}.png`, clip: { x: box.x, y: box.y, width: box.width, height: box.height } })
  console.log(`ok  z33-active-row-${theme}  (${Math.round(box.width)}x${Math.round(box.height)})`)
  await page.close()
}
await browser.close()
