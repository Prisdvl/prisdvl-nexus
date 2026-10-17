// Agent 创建向导 + 测试台专项验证（mock 模式）
import { chromium } from 'playwright-core'

const BASE = 'http://localhost:5175'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.addInitScript(() => localStorage.setItem('nx-proto-tour-done', '1'))
const issues = []
const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`)
  if (!ok) issues.push(name)
}
page.on('pageerror', (e) => issues.push(`pageerror: ${String(e.message).slice(0, 160)}`))
page.on('console', (m) => { if (m.type() === 'error') issues.push(`console.error: ${m.text().slice(0, 160)}`) })

await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle' })
await page.waitForSelector('.auth-card')
await page.locator('#li-u').fill('qa-runner')
await page.locator('#li-p').fill('qa-runner-password')
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
await page.waitForSelector('.shell', { timeout: 10000 })
await page.goto(`${BASE}/#/app/agents`, { waitUntil: 'networkidle' })
await page.waitForTimeout(900)

await page.getByRole('button', { name: /新建 Agent/ }).first().click()
await page.waitForSelector('#agent-name')
check('第一步：名称 / 描述 / 10 个角色图标', (await page.locator('#agent-name, #agent-desc').count()) === 2 && (await page.locator('.agent-icon-opt').count()) === 10)
await page.locator('#agent-name').fill('QA 研究助手')
await page.locator('#agent-desc').fill('负责检索资料并给出结构化结论')
await page.locator('.agent-icon-opt').nth(1).click()
await page.getByRole('button', { name: /下一步/ }).click()
await page.waitForTimeout(300)

check('第二步：指令与创造性仪表', (await page.locator('#agent-prompt').count()) === 1 && (await page.locator('.agent-dial-row').count()) >= 1)
await page.locator('#agent-prompt').fill('先检索知识库，再用要点回答。')
await page.getByRole('button', { name: /下一步/ }).click()
await page.waitForTimeout(300)

check('第三步：测试与发布', (await page.locator('.wizard-test-row input, .wizard-summary').count()) === 2)
await page.locator('.wizard-test-row input').fill('总结一下 JVM 调优的三个方向')
await page.getByRole('button', { name: /运行测试/ }).click()
await page.waitForTimeout(1100)
check('向导内测试有结果', (await page.locator('.wizard-test-result .bench-output').count()) === 1)
check('摘要说明创建后为草稿', (await page.locator('.wizard-summary').innerText()).includes('草稿'))
await page.screenshot({ path: 'qa-agent-wizard.png' })
await page.getByRole('button', { name: /创建 Agent/ }).click()
await page.waitForTimeout(600)
check('创建后进入测试台', (await page.locator('.bench-examples').count()) === 1)
check('新建 Agent 默认为草稿', (await page.locator('.agent-grid .badge', { hasText: '草稿' }).count()) >= 1)

const draftFit = await page.locator('.agent-grid .pixel-card').first().evaluate((card) => {
  const cr = card.getBoundingClientRect()
  const acts = card.querySelector('.agent-inner .acts')?.getBoundingClientRect()
  return acts ? (acts.bottom <= cr.bottom + 1 && acts.right <= cr.right + 1) : false
})
check('草稿卡操作栏完整可见', draftFit)
await page.locator('.agent-grid .pixel-card').first().getByRole('button', { name: /发布/ }).click()
await page.waitForTimeout(400)
check('可从卡片发布 Agent', (await page.locator('.agent-grid .badge', { hasText: '已发布' }).count()) >= 1)

await page.locator('.bench-examples .chip').first().click()
await page.locator('.panel form button[type="submit"]').click()
await page.waitForTimeout(1100)
check('测试台显示工具轨迹', (await page.locator('.tool-trace .tool-trace__row').count()) >= 2)
check('测试台显示结果', (await page.locator('.bench-output').count()) >= 1)

await page.setViewportSize({ width: 390, height: 844 })
await page.waitForTimeout(600)
const overflow = await page.evaluate(() => {
  const bad = []
  document.querySelectorAll('*').forEach((el) => {
    const r = el.getBoundingClientRect()
    if (r.width > 0 && r.right > window.innerWidth + 1) bad.push(`${el.tagName}.${String(el.className).slice(0, 30)}:${Math.round(r.right)}`)
  })
  return { sw: document.documentElement.scrollWidth, iw: window.innerWidth, bad: bad.slice(0, 6) }
})
check('手机端 Agent 页面无横向溢出', overflow.sw <= overflow.iw + 1, JSON.stringify(overflow))

console.log(issues.length === 0 ? 'ALL OK' : `ISSUES: ${issues.join(' | ')}`)
await browser.close()
process.exit(issues.length ? 1 : 0)
