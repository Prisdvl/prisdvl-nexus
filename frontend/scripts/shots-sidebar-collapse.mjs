import { chromium } from "playwright-core"
const OUT = "D:/develop/Prisma/docs/prototypes/shots"
const browser = await chromium.launch({ channel: "msedge", headless: true })
for (const theme of ["light", "dark"]) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1.5 })
  // 引导只在真实首登时出现，避免污染用例/截图
  await page.addInitScript(() => {
    localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
    /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
    localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
  })
  await page.addInitScript((t) => { localStorage.setItem("nx-proto-auth", "1"); localStorage.setItem("nx-proto-theme-mode", t) }, theme)
  await page.goto("http://localhost:5175/#/app/chat", { waitUntil: "networkidle" })
  await page.waitForTimeout(2200)
  const sb = await page.locator(".sidebar").boundingBox()
  const clip = { x: sb.x, y: sb.y, width: sb.width + 12, height: Math.min(sb.height, 900) }
  await page.screenshot({ path: `${OUT}/z24-sidebar-open-${theme}.png`, clip })
  console.log(`ok  z24-sidebar-open-${theme}`)
  // 收起两个分组
  await page.locator(".sb-section--toggle").first().click()
  await page.locator(".session-group.sb-section--toggle").click().catch(() => {})
  await page.waitForTimeout(600)
  await page.screenshot({ path: `${OUT}/z24-sidebar-collapsed-groups-${theme}.png`, clip })
  console.log(`ok  z24-sidebar-collapsed-groups-${theme}`)
  // 恢复 + 折叠侧栏
  await page.locator(".sb-section--toggle").first().click()
  await page.locator(".session-group.sb-section--toggle").click().catch(() => {})
  await page.waitForTimeout(400)
  await page.locator(".sb-top .sb-icon-btn").nth(1).click()
  await page.waitForTimeout(700)
  const sb2 = await page.locator(".sidebar").boundingBox()
  await page.screenshot({ path: `${OUT}/z24-sidebar-rail-${theme}.png`, clip: { x: 0, y: 0, width: sb2.width + 12, height: 900 } })
  console.log(`ok  z24-sidebar-rail-${theme}`)
  await page.close()
}
await browser.close()
