import { chromium } from "playwright-core"
const OUT = "D:/develop/Prisma/docs/prototypes/shots"
const browser = await chromium.launch({ channel: "msedge", headless: true })
for (const theme of ["dark", "light"]) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
  await page.addInitScript((t) => {
    localStorage.setItem("nx-proto-auth","1"); localStorage.setItem("nx-proto-tour-done","1"); localStorage.setItem("nx-proto-theme-mode", t)
    localStorage.setItem("nx-proto-projects", JSON.stringify([{ id: 1, name: "求职准备", createdAt: "刚刚" }, { id: 2, name: "Nexus 平台", createdAt: "刚刚" }]))
  }, theme)
  await page.goto("http://localhost:5175/#/app/chat", { waitUntil: "networkidle" })
  await page.waitForSelector(".sb-session", { timeout: 20000 })
  await page.waitForTimeout(1200)
  const box = await page.locator(".sb-recent").boundingBox()
  const proj = await page.locator(".sb-projects").boundingBox()
  const top = Math.min(box.y, proj.y)
  await page.screenshot({ path: `${OUT}/z37-sidebar-align-${theme}.png`, clip: { x: 0, y: top - 6, width: 250, height: Math.min(520, box.y + box.height - top + 12) } })
  console.log(`ok  z37-sidebar-align-${theme}`)
  await page.close()
}
await browser.close()
