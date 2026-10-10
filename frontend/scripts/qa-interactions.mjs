// 交互专项 QA：提示撤销 / CodeSlots 验证码 / StatusMark 状态位 / 骨架屏 / 空状态 / 弹窗关闭与焦点
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
const issues = []
page.on('pageerror', (e) => issues.push(`pageerror: ${String(e.message).slice(0, 140)}`))
page.on('console', (m) => { if (m.type() === 'error') issues.push(`console.error: ${m.text().slice(0, 140)}`) })

const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`)
  if (!ok) issues.push(name)
}
const nav = (l) => page.locator('.sb-nav button.rubber-segment__item', { hasText: l }).first()

/* 登录 */
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(1500)
await page.getByRole('button', { name: /一键使用演示账号/ }).click()
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
await page.waitForSelector('.shell', { timeout: 8000 })
await page.waitForTimeout(1200)

/* ---------- 1. 骨架屏 → 内容（知识库列表） ---------- */
await nav('知识库').click()
await page.waitForTimeout(250)
const skWhileLoading = await page.locator('.sk').count()
check('加载时显示骨架屏', skWhileLoading > 0, `骨架元素 ${skWhileLoading} 个`)
await page.waitForTimeout(1200)
check('加载完成后替换为卡片', (await page.locator('.sk').count()) === 0 && (await page.locator('.kb-inner').count()) > 0)

/* ---------- 2. 空状态（搜索无结果） ---------- */
await page.locator('input[placeholder="搜索知识库"]').fill('zzz-不存在')
await page.waitForTimeout(400)
check('搜索无结果显示空状态', (await page.locator('.state-empty').count()) === 1)
check('空状态带标题说明', (await page.locator('.state-empty .se-title').innerText()).includes('zzz-不存在'))
await page.locator('input[placeholder="搜索知识库"]').fill('')
await page.waitForTimeout(300)

/* ---------- 2.1 刷新竞态：骨架屏与真实列表不得同时可见 ---------- */
await page.reload({ waitUntil: 'domcontentloaded' })
let bothVisible = false
let sawSkeleton = false
for (let i = 0; i < 24; i++) {
  const s = await page.evaluate(() => ({
    sk: document.querySelectorAll('.sk').length,
    // 判据必须是"真实卡片内容"（.kb-inner）；骨架屏自己也包在 .kb-grid 里，用它当判据会误报
    real: document.querySelectorAll('.kb-inner').length,
  }))
  if (s.sk > 0) sawSkeleton = true
  if (s.sk > 0 && s.real > 0) { bothVisible = true; break }
  await page.waitForTimeout(50)
}
check('刷新时骨架屏与真实卡片不同时显示', !bothVisible)
check('加载态确实出现过（用例有效）', sawSkeleton)
await page.waitForTimeout(1200)

/* ---------- 2.2 [hidden] 兜底必须真生效（历史 bug：被 display:grid 覆盖） ---------- */
const hiddenDisplay = await page.evaluate(() => {
  const g = document.querySelector('.kb-grid')
  if (!g) return 'no-grid'
  g.setAttribute('hidden', '')
  const d = getComputedStyle(g).display
  g.removeAttribute('hidden')
  return d
})
check('[hidden] 属性真正生效（display:none）', hiddenDisplay === 'none', String(hiddenDisplay))


/* ---------- 2.3 卡片悬停不得出现近黑遮罩（历史 bug：PixelCard 默认活性色 #09090b） ---------- */
const hoverProbe = async () => page.evaluate(() => {
  const c = document.querySelector('.kb-grid .pixel-card')
  const before = getComputedStyle(c, '::before')
  const alpha = (before.backgroundImage.match(/\/\s*([\d.]+)\)/) || [])[1]
  return {
    active: getComputedStyle(c).getPropertyValue('--pixel-card-active-color').trim(),
    bg: before.backgroundImage,
    alpha: alpha ? Number(alpha) : null,
    border: getComputedStyle(c).borderColor,
    opacity: before.opacity,
  }
})
const idle = await hoverProbe()
await page.locator('.kb-grid .pixel-card').first().hover()
await page.waitForTimeout(900)
const hovered = await hoverProbe()
check('卡片活性色已配置（不是默认近黑）', hovered.active.length > 0 && !hovered.active.includes('#09090b'), hovered.active || '(空)')
check('悬停遮罩不含近黑 rgb(9, 9, 11)', !hovered.bg.includes('rgb(9, 9, 11)'), hovered.bg.slice(0, 90))
check('悬停遮罩透明度很低（≤0.3，不挡文字）', hovered.alpha !== null && hovered.alpha <= 0.3, `alpha=${hovered.alpha}`)
check('悬停有边框反馈', hovered.border !== idle.border, `${idle.border} → ${hovered.border}`)

/* ---------- 3. StatusMark：文档状态位 ---------- */
await page.locator('.kb-inner').first().click()
await page.waitForTimeout(1500)
check('URL 进入知识库详情', (await page.evaluate(() => location.hash)).includes('/app/kb/1'))
const docMarks = await page.locator('.doc-row .status-mark').count()
check('文档行使用 StatusMark 状态位', docMarks >= 6, `状态位 ${docMarks} 个`)
check('存在"解析中"运行态', (await page.locator('.doc-row .status-mark[data-status="running"]').count()) > 0)
check('存在"失败"态', (await page.locator('.doc-row .status-mark[data-status="failed"]').count()) > 0)

/* ---------- 4. 删除文档 → 提示带撤销 → 撤销恢复 ---------- */
const docsBefore = await page.locator('.doc-row').count()
await page.locator('.doc-row').first().locator('.doc-actions button').click()
await page.waitForTimeout(600)
const toastCount = await page.locator('.toast-host .swipe-toast').count()
check('删除后弹出提示', toastCount > 0, `提示 ${toastCount} 条`)
check('提示里带「撤销」按钮', (await page.locator('.toast-host button', { hasText: '撤销' }).count()) > 0)
check('文档已从列表移除', (await page.locator('.doc-row').count()) === docsBefore - 1)
await page.locator('.toast-host button', { hasText: '撤销' }).first().click()
await page.waitForTimeout(700)
check('点撤销后文档恢复', (await page.locator('.doc-row').count()) === docsBefore)

/* ---------- 5. 导入弹窗：Esc 关闭 + 焦点陷阱 ---------- */
await page.getByRole('button', { name: /导入文档/ }).click()
await page.waitForTimeout(500)
check('导入弹窗打开（Modal）', (await page.locator('.dialog[role="dialog"]').count()) === 1)
const focusInside = await page.evaluate(() => !!document.querySelector('.dialog[role="dialog"]')?.contains(document.activeElement))
check('打开后焦点在弹窗内', focusInside)
await page.keyboard.press('Escape')
await page.waitForTimeout(400)
check('Esc 可关闭弹窗', (await page.locator('.dialog[role="dialog"]').count()) === 0)

/* ---------- 6. 工作台：导入任务卡（StatusMark + 进度 + LatticeLoader） ---------- */
await nav('工作台').click()
await page.waitForTimeout(1600)
check('导入任务卡存在', (await page.locator('.wb-card-title', { hasText: '导入任务' }).count()) === 1)
check('任务卡里有 StatusMark 状态位', (await page.locator('.wb-card .status-mark').count()) >= 4)
check('任务进度条存在', (await page.locator('.task-progress i').count()) >= 1)
check('任务卡里有 LatticeLoader', (await page.locator('.wb-card .lattice-loader').count()) >= 1)

/* ---------- 7. Agent 测试：StatusMark 运行 → 完成 ---------- */
await nav('Agent').click()
await page.waitForTimeout(1400)
const testInput = page.locator('input[placeholder*="帮我出一道"]')
await testInput.fill('出一道 JVM 调优题')
await page.getByRole('button', { name: '运行', exact: true }).click()
await page.waitForTimeout(600)
check('测试中显示运行态 StatusMark', (await page.locator('.status-mark[data-status="running"]').count()) > 0)
await page.waitForTimeout(2200)
check('测试完成显示完成态', (await page.locator('.status-mark[data-status="done"]').count()) > 0)

/* ---------- 8. 删除 Agent → 撤销恢复 ---------- */
const agentsBefore = await page.locator('.agent-grid .pixel-card').count()
await page.locator('.agent-grid .pixel-card').first().locator('button[aria-label="删除"]').click()
await page.waitForTimeout(600)
check('删除 Agent 后数量减少', (await page.locator('.agent-grid .pixel-card').count()) === agentsBefore - 1)
await page.locator('.toast-host button', { hasText: '撤销' }).first().click()
await page.waitForTimeout(700)
check('撤销后 Agent 恢复', (await page.locator('.agent-grid .pixel-card').count()) === agentsBefore)

/* ---------- 9. 账号页：CodeSlots 验证邮箱（错误 → 正确） ---------- */
await page.goto('http://localhost:5175/#/app/account', { waitUntil: 'networkidle' })   // 「账号」导航项已移除，直接走路由
  await page.waitForTimeout(900)
await page.waitForTimeout(1200)
await page.locator('.settings-item', { hasText: '邮箱验证' }).first().click()
await page.waitForTimeout(500)
check('邮箱验证分区有验证入口', (await page.locator('button', { hasText: '验证邮箱' }).count()) > 0)
await page.locator('button', { hasText: '验证邮箱' }).first().click()
await page.waitForTimeout(500)
check('验证码弹窗打开', (await page.locator('.dialog .code-slots').count()) === 1)
check('弹窗标注原型演示码', (await page.locator('.dialog').innerText()).includes('123456'))

// 错误码 → error 态
await page.locator('.code-slots__input').click()
await page.keyboard.type('000000')
// 轮询等待 error 态（校验有 700ms 延迟 + 失败态窗口有限，死等会与动画赛跑）
let sawError = false
for (let i = 0; i < 20; i++) {
  if ((await page.locator('.code-slots__row[data-status="error"]').count()) === 1) { sawError = true; break }
  await page.waitForTimeout(120)
}
check('错误码进入 error 态', sawError)
let backToIdle = false
for (let i = 0; i < 25; i++) {
  if ((await page.locator('.code-slots__row[data-status="idle"]').count()) === 1) { backToIdle = true; break }
  await page.waitForTimeout(120)
}
check('错误码回抽清空（回到 idle）', backToIdle)

// 正确码 → success 态
await page.locator('.code-slots__input').click()
await page.keyboard.type('123456')
let successOrClosed = false
for (let i = 0; i < 25; i++) {
  if ((await page.locator('.code-slots__row[data-status="success"]').count()) === 1 || (await page.locator('.dialog').count()) === 0) { successOrClosed = true; break }
  await page.waitForTimeout(120)
}
check('正确码进入 success 态或直接关闭', successOrClosed)
await page.waitForTimeout(900)
check('验证邮箱后状态位变为已完成', (await page.locator('.status-mark[data-status="done"]').count()) > 0)

/* ---------- 10. 保存资料 → 成功提示（「保存」在「公开资料」分区里） ---------- */
await page.locator('.settings-item', { hasText: '公开资料' }).first().click()
await page.waitForTimeout(500)
await page.getByRole('button', { name: /^保存$/ }).click()
await page.waitForTimeout(500)
check('保存后弹出成功提示', (await page.locator('.toast-host .swipe-toast').count()) > 0)

/* ---------- 11. 注册：两步 + 验证码 ---------- */
await page.locator('.sb-row--settings').click()   // 账号入口改为底部「个人设置」弹层
  await page.waitForTimeout(600)
await page.waitForTimeout(300)
await page.locator('.settings-dialog__logout').click()
await page.waitForTimeout(1000)
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
await page.locator('.auth-card .rubber-segment button.rubber-segment__item', { hasText: '注册' }).click()
await page.waitForTimeout(400)
await page.locator('#rg-u').fill('newbie_user')
await page.locator('#rg-u').blur()
await page.locator('#rg-p').fill('Password123')
await page.locator('#rg-p2').fill('Password123')
await page.locator('#rg-e').fill('newbie@example.com')
await page.locator('#rg-e').blur()
await page.locator('form button[type="submit"]', { hasText: '下一步' }).click()
await page.waitForTimeout(600)
check('注册进入第二步：验证码', (await page.locator('.auth-card .code-slots').count()) === 1)
await page.locator('.code-slots__input').click()
await page.keyboard.type('123456')
await page.waitForTimeout(1400)
check('注册验证码通过并提示', (await page.locator('.toast-host .swipe-toast').count()) > 0)

/* ---------- 12. 校验时机：未失焦不报错，失焦才报 ---------- */
await page.locator('.auth-card .rubber-segment button.rubber-segment__item', { hasText: '注册' }).click()
await page.waitForTimeout(300)
await page.locator('#rg-u').fill('x')
const errBeforeBlur = await page.locator('#rg-u').evaluate((el) => {
  const f = el.closest('.field')
  const e = f && f.querySelector('.err')
  return e ? getComputedStyle(e).display : 'missing'
})
await page.locator('#rg-u').blur()
await page.waitForTimeout(300)
const errAfterBlur = await page.locator('#rg-u').evaluate((el) => {
  const f = el.closest('.field')
  const e = f && f.querySelector('.err')
  return e ? getComputedStyle(e).display : 'missing'
})
check('输入中不显示错误（未失焦）', errBeforeBlur === 'none', `display=${errBeforeBlur}`)
check('失焦后显示错误', errAfterBlur !== 'none', `display=${errAfterBlur}`)

await browser.close()
if (issues.length) {
  console.log(`\n发现 ${issues.length} 个问题:`)
  issues.forEach((i) => console.log('  - ' + i))
  process.exit(1)
} else {
  console.log('\n交互专项 QA 全部通过')
}