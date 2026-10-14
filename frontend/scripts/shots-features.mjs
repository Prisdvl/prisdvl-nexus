// 本轮新特性专项截图：Markdown/Mermaid 回答、引用跳转高亮、两步注销、趋势动画
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'

const BASE = 'http://localhost:5175'
const OUT = process.env.QA_SHOTS_OUT ?? 'D:/develop/Prisma/docs/prototypes/shots'
mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1.5 })
  // 引导只在真实首登时出现，避免污染用例/截图
  await page.addInitScript(() => {
    localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
    /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
    localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
  })
const shot = async (n) => { await page.screenshot({ path: `${OUT}/${n}.png` }); console.log('ok  ' + n) }
const nav = (l) => page.locator('.sb-nav button.rubber-segment__item', { hasText: l }).first()

await page.goto(BASE, { waitUntil: 'networkidle' })
await page.waitForTimeout(2200)
await page.locator('.lp-pill button', { hasText: '登录' }).first().click()
await page.waitForSelector('.auth-card')
await page.locator('#li-u').fill('qa-runner')
await page.locator('#li-p').fill('qa-runner-password')
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
await page.waitForSelector('.shell')
await page.waitForTimeout(2000)

// 1. 对话：Markdown + Mermaid 回答（等 mermaid 渲染完）
await nav('对话').click()
await page.waitForTimeout(900)
await page.locator('.session-open').first().click()
await page.waitForTimeout(600)
await page.locator('.prompt-bar__input').fill('Redis 持久化怎么选？')
await page.locator('.prompt-bar__input').press('Enter')
await page.waitForTimeout(4200)
await shot('x-chat-markdown-mermaid')
await page.locator('.thread').evaluate((el) => { el.scrollTop = 0 })

// 2. 引用跳转 → 知识库详情高亮文档
await page.locator('.thread .cite-chip[data-clickable]').first().click()
await page.waitForTimeout(1600)
await shot('x-cite-jump-highlight')

// 3. 工作台：趋势动画 + 数字动画
await nav('工作台').click()
await page.waitForTimeout(900)
await shot('x-workbench-trend')

// 4. 账号：两步注销（第一步 → 第二步）
await page.goto('http://localhost:5175/#/app/account', { waitUntil: 'networkidle' })   // 「账号」导航项已移除，直接走路由
  await page.waitForTimeout(900)
await page.waitForTimeout(900)
await shot('x-account-danger-step1')
await page.locator('.danger-steps input:not([type="checkbox"])').fill('demo')
await page.locator('.danger-confirm-row input[type="checkbox"]').check()
await page.waitForTimeout(300)
await page.locator('.danger-steps .btn-danger').first().click()
await page.waitForTimeout(500)
await shot('x-account-danger-step2')

// 5. 登录后背景统一 Aurora（知识库页）
await nav('知识库').click()
await page.waitForTimeout(1500)
await shot('x-app-bg-aurora')

// 6. 暗色：知识库详情 tab（原黑框问题位置）
await page.goto(BASE + '/#/app/kb/1', { waitUntil: 'networkidle' })
await page.waitForTimeout(1800)
await page.locator('.preview-tools').getByRole('button', { name: '暗色' }).click()
await page.waitForTimeout(1400)
await shot('x-kb-tabs-dark')
await page.locator('.page .rubber-segment button.rubber-segment__item', { hasText: '问答' }).click()
await page.waitForTimeout(700)
await shot('x-kb-tabs-dark-qa')

// 7. 暗色工作台（Aurora 统一背景）
await page.locator('.sb-nav button.rubber-segment__item', { hasText: '工作台' }).click()
await page.waitForTimeout(1600)
await shot('x-workbench-dark')

await browser.close()
console.log('新特性截图完成')