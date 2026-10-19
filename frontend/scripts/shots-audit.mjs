// 审计修复证据：文档导入流程与轮播指示点可见
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { chromium } from 'playwright-core'
const OUT = process.env.QA_SHOTS_OUT ?? join(tmpdir(), 'prisma-recover-qa-shots')
const browser = await chromium.launch({ channel: 'msedge', headless: true })
for (const theme of ['light', 'dark']) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1.5 })
  // 引导只在真实首登时出现，避免污染用例/截图
  await page.addInitScript(() => {
    localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
    /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
    localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
  })
  await page.addInitScript((t) => {
    localStorage.setItem('nx-proto-theme-mode', t); localStorage.setItem('nx-proto-auth', '1')
    localStorage.setItem('nx-proto-palette', 'terracotta')
  }, theme)

  // 向导（Stepper 的下一步按钮）
  await page.goto('http://localhost:5175/#/app/kb/1', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1700)
  await page.getByRole('button', { name: /导入文档/ }).click()
  await page.waitForTimeout(800)
  await page.screenshot({ path: `${OUT}/z4-wizard-${theme}.png` })
  console.log(`ok  z4-wizard-${theme}`)
  await page.keyboard.press('Escape')
  await page.waitForTimeout(500)

  // 轮播指示点
  await page.goto('http://localhost:5175/#/app/workbench', { waitUntil: 'networkidle' })
  await page.waitForTimeout(2400)
  // 元素级截图：会自动滚动到可视区，避免 clip 落在视口外
  await page.locator('.lp-carousel').scrollIntoViewIfNeeded()
  await page.waitForTimeout(600)
  await page.locator('.lp-carousel').screenshot({ path: `${OUT}/z4-carousel-${theme}.png` })
  console.log(`ok  z4-carousel-${theme}`)
  await page.close()
}
await browser.close()
