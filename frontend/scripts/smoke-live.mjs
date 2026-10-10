// 运行态冒烟：真实打开 dev server，检查关键路由能渲染、无控制台报错
import { chromium } from 'playwright-core'
const BASE = 'http://localhost:5175'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  // 引导只在真实首登时出现，避免污染用例/截图
  await page.addInitScript(() => {
    localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
    /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
    localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
  })
const errs = []
page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 120)) })
page.on('pageerror', (e) => errs.push('pageerror: ' + String(e.message).slice(0, 120)))

await page.goto(BASE, { waitUntil: 'networkidle' })
await page.waitForTimeout(2200)
console.log('  入口页标题:', (await page.locator('.lp-h1').first().innerText()).slice(0, 30))
console.log('  入口页角落控件:', (await page.locator('.corner-tools .corner-btn').count()) === 1 ? 'ok' : 'FAIL')

await page.getByRole('button', { name: /一键使用演示账号/ }).click().catch(async () => {
  await page.locator('.lp-pill .btn').click()
  await page.waitForTimeout(1200)
  await page.getByRole('button', { name: /一键使用演示账号/ }).click()
})
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
await page.waitForSelector('.shell', { timeout: 8000 })
await page.waitForTimeout(1500)

for (const [label, path, sel] of [
  ['工作台', '/#/app/workbench', '.stat-grid'],
  ['知识库', '/#/app/kb', '.kb-grid'],
  ['知识库详情', '/#/app/kb/1', '.doc-row'],
  ['对话', '/#/app/chat', '.thread'],
  ['Agent', '/#/app/agents', '.agent-grid'],
  ['账号', '/#/app/account', '.appearance'],
]) {
  await page.goto(BASE + path, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1600)
  const n = await page.locator(sel).count()
  console.log(`  ${label.padEnd(6)} ${n > 0 ? 'ok' : 'FAIL'}（${sel} × ${n}）`)
}
console.log('  控制台错误:', errs.length === 0 ? '无' : errs.slice(0, 3))
await browser.close()
