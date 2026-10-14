// 本轮新增交互的截图：提示撤销 / 验证码 / 状态位 / 骨架屏 / 空状态
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

await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(1500)
await page.locator('#li-u').fill('qa-runner')
await page.locator('#li-p').fill('qa-runner-password')
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
await page.waitForSelector('.shell', { timeout: 8000 })
await page.waitForTimeout(1000)

// 1. 骨架屏（知识库列表加载中）
await nav('知识库').click()
await page.waitForTimeout(260)
await shot('y-kb-skeleton')

// 2. 空状态（搜索无结果）
await page.waitForTimeout(1100)
await page.locator('input[placeholder="搜索知识库"]').fill('zzz')
await page.waitForTimeout(400)
await shot('y-kb-empty')
await page.locator('input[placeholder="搜索知识库"]').fill('')
await page.waitForTimeout(300)

// 3. 文档状态位（StatusMark）+ 删除撤销提示
await page.locator('.kb-inner').first().click()
await page.waitForTimeout(1500)
await shot('y-doc-statusmarks')
await page.locator('.doc-row').first().locator('.doc-actions button').click()
await page.waitForTimeout(700)
await shot('y-toast-undo')

// 4. 工作台导入任务卡（状态位 + 进度 + LatticeLoader）
await nav('工作台').click()
await page.waitForTimeout(1800)
await page.locator('.wb-card-title', { hasText: '导入任务' }).scrollIntoViewIfNeeded()
await page.waitForTimeout(500)
await shot('y-import-tasks')

// 5. Agent 测试状态位（运行中）
await nav('Agent').click()
await page.waitForTimeout(1400)
await page.locator('input[placeholder*="帮我出一道"]').fill('出一道 JVM 调优题')
await page.getByRole('button', { name: '运行', exact: true }).click()
await page.waitForTimeout(700)
await shot('y-agent-running')
await page.waitForTimeout(2200)
await shot('y-agent-done-toast')

// 6. 验证邮箱（CodeSlots）—— 失败态
await page.goto('http://localhost:5175/#/app/account', { waitUntil: 'networkidle' })   // 「账号」导航项已移除，直接走路由
  await page.waitForTimeout(900)
await page.waitForTimeout(1200)
await page.locator('button', { hasText: '验证邮箱' }).first().click()
await page.waitForTimeout(500)
await page.locator('.code-slots__input').click()
await page.keyboard.type('000000')
await page.waitForTimeout(950)
await shot('y-codeslots-error')

// 7. 验证邮编成功态
await page.waitForTimeout(1600)
await page.locator('.code-slots__input').click()
await page.keyboard.type('123456')
await page.waitForTimeout(850)
await shot('y-codeslots-success')
await page.waitForTimeout(1200)
await shot('y-account-verified')

// 8. 注册第二步（验证码）
await page.locator('.sb-row--settings').click()   // 账号入口改为底部「个人设置」弹层
  await page.waitForTimeout(600)
await page.waitForTimeout(300)
await page.locator('.settings-dialog__logout').click()
await page.waitForTimeout(1000)
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(1300)
await page.locator('.auth-card .rubber-segment button.rubber-segment__item', { hasText: '注册' }).click()
await page.waitForTimeout(400)
await page.locator('#rg-u').fill('newbie_user')
await page.locator('#rg-p').fill('Password123')
await page.locator('#rg-p2').fill('Password123')
await page.locator('#rg-e').fill('newbie@example.com')
await page.locator('form button[type="submit"]', { hasText: '下一步' }).click()
await page.waitForTimeout(700)
await shot('y-register-code')

await browser.close()
console.log('新交互截图完成')