// 交互专项 QA：提示撤销 / StatusMark 状态位 / 骨架屏 / 空状态 / 弹窗关闭与焦点
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
await page.locator('#li-u').fill('qa-runner')
await page.locator('#li-p').fill('qa-runner-password')
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
await page.waitForSelector('.shell', { timeout: 8000 })
await page.waitForTimeout(1200)

/* ---------- 1. 骨架屏 → 内容（知识库列表） ---------- */
await nav('知识库').click()
await page.waitForTimeout(250)
const skWhileLoading = await page.locator('.sk').count()
check('加载时显示骨架屏', skWhileLoading > 0, `骨架元素 ${skWhileLoading} 个`)
await page.waitForTimeout(1200)
check('加载完成后替换为表格行', (await page.locator('.sk').count()) === 0 && (await page.locator('.kb-table tbody tr').count()) > 0)

/* ---------- 2. 空状态（搜索无结果） ---------- */
await page.locator('input[placeholder="搜索知识库"]').fill('zzz-不存在')
await page.waitForTimeout(400)
// 列表改成表格后，空状态由 .kb-filter-empty 承载（不再套 StateEmpty 的 .state-empty/.se-title）
check('搜索无结果显示空状态', (await page.locator('.kb-filter-empty').count()) === 1)
check('空状态带标题说明', (await page.locator('.kb-filter-empty').innerText()).includes('zzz-不存在'))
await page.locator('input[placeholder="搜索知识库"]').fill('')
await page.waitForTimeout(300)

/* ---------- 2.1 刷新竞态：骨架屏与真实列表不得同时可见 ---------- */
await page.reload({ waitUntil: 'domcontentloaded' })
let bothVisible = false
let sawSkeleton = false
for (let i = 0; i < 24; i++) {
  const s = await page.evaluate(() => ({
    sk: document.querySelectorAll('.sk').length,
    // 判据必须是"真实内容行"（tbody tr）；骨架屏自己也包在列表容器里，用它当判据会误报
    real: document.querySelectorAll('.kb-table tbody tr').length,
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
  const g = document.querySelector('.kb-table-wrap')
  if (!g) return 'no-grid'
  g.setAttribute('hidden', '')
  const d = getComputedStyle(g).display
  g.removeAttribute('hidden')
  return d
})
check('[hidden] 属性真正生效（display:none）', hiddenDisplay === 'none', String(hiddenDisplay))


/* ---------- 2.3 PixelCard 悬停检查已随界面迁移：知识库列表改为表格，卡片只存在于 Agent 网格 ---------- */
/* 该组断言见第 7.1 节（Agent 页的 .agent-grid .pixel-card）。 */

/* ---------- 3. StatusMark：文档状态位 ---------- */
await page.locator('.kb-table tbody tr').first().click()
await page.waitForTimeout(1500)
check('URL 进入知识库详情', (await page.evaluate(() => location.hash)).includes('/app/kb/1'))
const docMarks = await page.locator('.doc-row .status-mark').count()
check('文档行使用 StatusMark 状态位', docMarks >= 6, `状态位 ${docMarks} 个`)
check('存在"解析中"运行态', (await page.locator('.doc-row .status-mark[data-status="running"]').count()) > 0)
check('存在"失败"态', (await page.locator('.doc-row .status-mark[data-status="failed"]').count()) > 0)

/* ---------- 4. 删除文档 → 提示带撤销 → 撤销恢复 ---------- */
const docsBefore = await page.locator('.doc-row').count()
// 行内动作有两个按钮（分块管理 / 删除），按可访问名点删除
await page.locator('.doc-row').first().locator('.doc-actions button[aria-label^="删除 "]').click()
await page.waitForTimeout(600)
const toastCount = await page.locator('.toast-host .swipe-toast').count()
check('删除后弹出提示', toastCount > 0, `提示 ${toastCount} 条`)
check('提示里带「撤销」按钮', (await page.locator('.toast-host button', { hasText: '撤销' }).count()) > 0)
check('文档已从列表移除', (await page.locator('.doc-row').count()) === docsBefore - 1)
await page.locator('.toast-host button', { hasText: '撤销' }).first().click()
await page.waitForTimeout(700)
check('点撤销后文档恢复', (await page.locator('.doc-row').count()) === docsBefore)

/* ---------- 5. 导入弹窗：Esc 关闭 + 焦点陷阱 ---------- */
await page.getByRole('button', { name: /^导入$/ }).click()
await page.waitForTimeout(500)
check('导入弹窗打开（Modal）', (await page.locator('.dialog[role="dialog"]').count()) === 1)
const focusInside = await page.evaluate(() => !!document.querySelector('.dialog[role="dialog"]')?.contains(document.activeElement))
check('打开后焦点在弹窗内', focusInside)
await page.keyboard.press('Escape')
await page.waitForTimeout(400)
check('Esc 可关闭弹窗', (await page.locator('.dialog[role="dialog"]').count()) === 0)

/* ---------- 6. 工作台：文档处理卡（StatusMark + 任务行 + LatticeLoader） ---------- */
await nav('工作台').click()
await page.waitForTimeout(1600)
// 卡片标题改成了「最近文档处理」，按标题文案找会绑死语言 → 改用结构判据
check('工作台有文档处理卡（含状态位）', (await page.locator('.wb-card .status-mark').count()) >= 4)
check('任务行存在（每行一个状态位）', (await page.locator('.wb-card .task-row').count()) >= 2)
check('任务卡里有 LatticeLoader', (await page.locator('.wb-card .lattice-loader').count()) >= 1)

/* ---------- 7. Agent 测试：StatusMark 运行 → 完成 ---------- */
await nav('Agent').click()
await page.waitForTimeout(1400)
// 测试台输入已从单行 input 换成 .bench-form 里的 textarea
const testInput = page.locator('.bench-form textarea')
await testInput.fill('出一道 JVM 调优题')
await page.locator('.bench-form button[type="submit"]').click()
await page.waitForTimeout(600)
check('测试中显示运行态 StatusMark', (await page.locator('.status-mark[data-status="running"]').count()) > 0)
await page.waitForTimeout(2200)
check('测试完成显示完成态', (await page.locator('.status-mark[data-status="done"]').count()) > 0)

/* ---------- 7.1 PixelCard 悬停不得出现近黑遮罩（历史 bug：默认活性色 #09090b） ---------- */
const hoverProbe = async () => page.evaluate(() => {
  const c = document.querySelector('.agent-grid .pixel-card')
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
await page.locator('.agent-grid .pixel-card').first().hover()
await page.waitForTimeout(900)
const hovered = await hoverProbe()
check('卡片活性色已配置（不是默认近黑）', hovered.active.length > 0 && !hovered.active.includes('#09090b'), hovered.active || '(空)')
check('悬停遮罩不含近黑 rgb(9, 9, 11)', !hovered.bg.includes('rgb(9, 9, 11)'), hovered.bg.slice(0, 90))
check('悬停遮罩透明度很低（≤0.3，不挡文字）', hovered.alpha !== null && hovered.alpha <= 0.3, `alpha=${hovered.alpha}`)
check('悬停有边框反馈', hovered.border !== idle.border, `${idle.border} → ${hovered.border}`)

/* ---------- 8. 删除 Agent → 撤销恢复 ---------- */
const agentsBefore = await page.locator('.agent-grid .pixel-card').count()
await page.locator('.agent-grid .pixel-card').first().locator('button[aria-label="删除"]').click()
await page.waitForTimeout(600)
check('删除 Agent 后数量减少', (await page.locator('.agent-grid .pixel-card').count()) === agentsBefore - 1)
await page.locator('.toast-host button', { hasText: '撤销' }).first().click()
await page.waitForTimeout(700)
check('撤销后 Agent 恢复', (await page.locator('.agent-grid .pixel-card').count()) === agentsBefore)

/* ---------- 9. 邮箱验证：后端无验证码 API 时明确不可用 ---------- */
await page.goto('http://localhost:5175/#/app/account', { waitUntil: 'networkidle' })
await page.waitForTimeout(1500)
await page.locator('.settings-item', { hasText: '邮箱验证' }).first().click()
await page.waitForTimeout(500)
check('无验证码 API 时邮箱验证按钮禁用', await page.locator('.settings-body button[disabled]', { hasText: '暂不可用' }).count() === 1)
check('邮箱验证明确说明服务端能力限制', (await page.locator('.settings-body').innerText()).includes('没有发送或校验邮箱验证码的接口'))

/* ---------- 10. 保存资料 → 成功提示（「保存」在「公开资料」分区里） ---------- */
await page.locator('.settings-item', { hasText: '公开资料' }).first().click()
await page.waitForTimeout(500)
await page.getByRole('button', { name: /^保存$/ }).click()
await page.waitForTimeout(500)
check('保存后弹出成功提示', (await page.locator('.toast-host .swipe-toast').count()) > 0)

/* ---------- 11. 注册：不能以离线 mock 假报成功 ---------- */
await page.locator('.sb-row--settings').click()
await page.waitForTimeout(600)
await page.locator('.settings-dialog__logout').click()
await page.waitForTimeout(1000)
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(800)
await page.locator('.auth-card .rubber-segment button.rubber-segment__item', { hasText: '注册' }).click()
await page.waitForTimeout(300)
await page.locator('#rg-u').fill('qa_user')
await page.locator('#rg-p').fill('Password123')
await page.locator('#rg-p2').fill('Password123')
await page.locator('#rg-e').fill('qa@example.com')
await page.locator('form button[type="submit"]', { hasText: '创建账号' }).click()
await page.waitForTimeout(300)
check('离线测试模式注册不假报成功', (await page.locator('.auth-card [role="alert"]').innerText()).includes('注册需要真实后端'))

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