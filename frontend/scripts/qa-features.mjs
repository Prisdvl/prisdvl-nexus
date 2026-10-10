// 功能级 QA：把「需求里点名的交互」逐条真点一遍（不做视觉判断，只验证行为）
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

await page.goto(BASE, { waitUntil: 'networkidle' })
await page.waitForTimeout(2200)
await page.locator('.lp-pill button', { hasText: '登录' }).first().click()
await page.waitForSelector('.auth-card')
await page.getByRole('button', { name: /一键使用演示账号/ }).click()
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
await page.waitForSelector('.shell')
await page.waitForTimeout(2000)

/* ---------- 1. 侧栏：竖向橡皮导航 ---------- */
const navItems = await page.locator('.sb-nav button.rubber-segment__item').count()
/* 「账号」导航项已移除（设置收在底部「个人设置」弹层里），现在是 4 项 */
check('侧栏有 4 个导航项', navItems === 4, `实际 ${navItems}`)
const isVertical = await page.locator('.sb-nav .rubber-segment[data-vertical]').count()
check('侧栏为竖排橡皮导航', isVertical === 1)
await page.locator('.sb-nav button.rubber-segment__item', { hasText: '知识库' }).click()
await page.waitForTimeout(700)
check('点击侧栏可切页', (await page.locator('.page-title').first().innerText()).includes('知识库'))

/* ---------- 2. 工作台新增内容 ---------- */
await page.locator('.sb-nav button.rubber-segment__item', { hasText: '工作台' }).click()
await page.waitForTimeout(1600)
const heatCells = await page.locator('.wb-heat span').count()
check('活力图 12×7 = 84 格', heatCells === 84, `实际 ${heatCells}`)
check('用量趋势图已渲染', (await page.locator('.wb-trend svg path').count()) >= 2)
const glowCards = await page.locator('.stat-grid .border-glow-card').count()
check('四张统计卡统一 BorderGlow', glowCards === 4, `实际 ${glowCards}`)
check('活动时间线 5 条', (await page.locator('.wb-timeline li').count()) === 5)
check('轮播已渲染内容（功能速览）', (await page.locator('.lp-carousel .carousel-item-title').count()) > 0)
// Agent 快捷测试
await page.locator('.wb-quick input').fill('出一道 JVM 调优题')
await page.locator('.wb-quick button[type="submit"]').click()
await page.waitForTimeout(700)
check('快捷测试进入执行态', (await page.locator('.wb-quick .lattice-loader').count()) > 0)
await page.waitForTimeout(2200)
check('快捷测试产出结果', (await page.locator('.wb-quick-out').count()) > 0)

/* ---------- 3. 对话页：会话切换 / 管理 / 消息操作 / 绑定 ---------- */
await page.locator('.sb-nav button.rubber-segment__item', { hasText: '对话' }).click()
await page.waitForTimeout(900)
const title1 = await page.getByTestId('chat-title').innerText()
const firstMsg1 = await page.locator('.thread .bub').first().innerText()
await page.locator('.session-item').nth(1).click()
await page.waitForTimeout(500)
const title2 = await page.getByTestId('chat-title').innerText()
const firstMsg2 = await page.locator('.thread .bub').first().innerText()
check('切换会话会换标题', title1 !== title2, `${title1} → ${title2}`)
check('切换会话会换消息内容', firstMsg1 !== firstMsg2, `${firstMsg1.slice(0, 18)}… → ${firstMsg2.slice(0, 18)}…`)

// 搜索
await page.locator('.sb-top .sb-icon-btn[aria-label="搜索会话"]').click()
await page.waitForTimeout(300)
await page.locator('.chat-search input').fill('Redis')
await page.waitForTimeout(400)
check('会话搜索生效', (await page.locator('.session-item').count()) === 1)
await page.locator('.chat-search input').fill('')
await page.waitForTimeout(300)

// 置顶：改为滑动手势（左滑露出「置顶」，点它）
const firstBefore = await page.locator('.session-item .t').first().innerText()
const redisRow = page.locator('.session-list .swipe-row').filter({ hasText: 'Redis' }).first()
await redisRow.scrollIntoViewIfNeeded()   // 短视口下该行可能在可视区外，鼠标坐标会落到侧栏上
await page.waitForTimeout(400)
const rb = await redisRow.boundingBox()
await page.mouse.move(rb.x + rb.width - 40, rb.y + rb.height / 2)
await page.mouse.down()
await page.mouse.move(rb.x + rb.width - 150, rb.y + rb.height / 2, { steps: 12 })
await page.mouse.up()
await page.waitForTimeout(700)
// 只点主轨里的按钮：反方向轨的按钮虽然被裁掉，但 boundingBox 不计裁切，
// 直接 .first() 会命中它并算出落在侧栏上的点击点（导致"被侧栏拦截"的假失败）
await page.locator('.swipe-row[data-open-side="main"] .swipe-row__rail:not(.swipe-row__rail--start) .swipe-row__action').first().click()
await page.waitForTimeout(600)
/* 置顶不再用行内 pin 图标（那会把文字起点顶偏、三层对不齐），
   改为验证它确实进入了「置顶」分组 */
const pinnedGroup = await page.evaluate(() => {
  const group = Array.from(document.querySelectorAll('.sb-group'))
    .find((g) => g.querySelector('.sb-section--toggle')?.textContent.includes('置顶'))
  return Array.from(group?.querySelectorAll('.sb-session .t') ?? []).map((e) => e.textContent.trim())
})
check('滑动置顶后该会话进入「置顶」分组', pinnedGroup.some((x) => x.includes('Redis')), pinnedGroup.join(' / '))

// 消息操作：先发一条（生成含表格/代码块/Mermaid 的回答），再从该回答**分支到新对话**
const msgsBeforeSend = await page.locator('.thread .msg').count()
await page.locator('.prompt-bar__input').fill('RDB 和 AOF 怎么选？')
await page.locator('.prompt-bar__input').press('Enter')
/* 等工具调用跑完并给出回答（3 条调用约 3.5s） */
await page.waitForTimeout(5200)
check('发送后生成回答', (await page.locator('.thread .msg').count()) > msgsBeforeSend)

const sessionsBefore = await page.locator('.session-list .swipe-row').count()
const titleBefore = await page.locator('.session-item[aria-selected="true"] .t').innerText()
await page.locator('.thread .msg').last().hover()
await page.locator('.thread .msg').last().locator('.msg-ops button', { hasText: '在新对话中分支' }).click()
await page.waitForTimeout(1200)
const sessionsAfter = await page.locator('.session-list .swipe-row').count()
const titleAfter = await page.locator('.session-item[aria-selected="true"] .t').innerText()
check('分支后多出一个新会话', sessionsAfter === sessionsBefore + 1, `${sessionsBefore} → ${sessionsAfter}`)
check('新会话标题带「分支」', titleAfter.includes('分支') && titleAfter !== titleBefore, `${titleBefore} → ${titleAfter}`)
check('分支复制了原上下文', (await page.locator('.thread .msg').count()) > 0)
// 分支会话带着"到该条为止"的上下文，后续 Markdown 断言正是在它上面验证

// 消息操作：编辑重发（用户消息）
await page.locator('.thread .msg.user').last().hover()
await page.locator('.thread .msg.user').last().locator('.msg-ops button', { hasText: '编辑重发' }).click()
await page.waitForTimeout(300)
check('编辑重发进入编辑态', (await page.locator('.thread textarea').count()) > 0)
await page.locator('.thread button', { hasText: '取消' }).click()
await page.waitForTimeout(200)

// 绑定：知识库多选
const chipsBefore = await page.locator('.chat-layout header .btn-ghost', { hasText: '知识库' }).innerText()
await page.locator('.chat-layout header .btn-ghost', { hasText: '知识库' }).click()
await page.waitForTimeout(300)
check('知识库绑定弹层打开', (await page.locator('.chat-bind-pop').count()) === 1)
await page.locator('.chat-bind-pop .opt').nth(3).click()
await page.waitForTimeout(400)
const chipsAfter = await page.locator('.chat-layout header .btn-ghost', { hasText: '知识库' }).innerText()
check('知识库绑定数量变化', chipsBefore !== chipsAfter, `${chipsBefore.trim()} → ${chipsAfter.trim()}`)
await page.keyboard.press('Escape')

// Agent 切换
await page.locator('.agent-seg button.rubber-segment__item').nth(2).click()
await page.waitForTimeout(400)
check('Agent 切换生效', (await page.locator('.agent-seg .rubber-segment__item[aria-checked="true"]').innerText()).includes('代码') || (await page.locator('.agent-seg .rubber-segment__item[aria-checked="true"]').innerText()).includes('简历'))

// 导出下载
const dl = page.waitForEvent('download', { timeout: 6000 }).catch(() => null)
await page.locator('.chat-layout header button[title="导出为 Markdown"]').click()
const download = await dl
check('导出对话触发下载', !!download, download ? await download.suggestedFilename() : '无下载事件')

/* ---------- 4. 新建会话向导 ---------- */
await page.locator('.sb-row--quick').click()   // 「新对话」已移到全局侧栏
await page.waitForTimeout(500)
check('新建会话向导打开', (await page.locator('.dialog .step-indicator').count()) === 3)
check('第一步可选 Agent', (await page.locator('.dialog .wizard-opts .chip').count()) >= 4)
await page.locator('.dialog button', { hasText: '下一步' }).click()
await page.waitForTimeout(500)
check('第二步可选知识库', (await page.locator('.dialog .wizard-opts .chip').count()) === 6)
await page.locator('.dialog .close').click()
await page.waitForTimeout(300)

/* ---------- 3.5 背景统一：登录前 waves / 登录后 aurora ---------- */
// （入口页与登录页在步骤 0 已包含 waves，这里只验证登录后）
const bgVariant = await page.locator('[data-bg]').first().getAttribute('data-bg')
check('登录后背景统一为 aurora', bgVariant === 'aurora', `data-bg=${bgVariant}`)

/* ---------- 3.6 AI 回答的 Markdown 渲染（含 Mermaid） ---------- */
await page.locator('.sb-nav button.rubber-segment__item', { hasText: '对话' }).click()
await page.waitForTimeout(900)
const mdCtx = await page.evaluate(() => ({
  title: document.querySelector('.session-item[aria-selected="true"] .t')?.textContent,
  msgs: document.querySelectorAll('.thread .msg').length,
  mdRoots: document.querySelectorAll('.thread .md').length,
  lastBubble: document.querySelector('.thread .msg.bot .bub')?.textContent?.slice(0, 40) ?? '(无)',
}))
console.log('  · Markdown 检查上下文:', JSON.stringify(mdCtx))
const mdTable = await page.locator('.thread .md table').count()
check('回答渲染出 Markdown 表格', mdTable > 0, `table ${mdTable} 个`)
const mdCode = await page.locator('.thread .md pre code').count()
check('回答渲染出代码块', mdCode > 0, `code ${mdCode} 个`)
await page.waitForTimeout(2500)
const mermaidSvg = await page.locator('.thread .md-mermaid svg').count()
check('Mermaid 真渲染为 SVG', mermaidSvg > 0, `svg ${mermaidSvg} 个`)
check('Mermaid 无渲染报错', (await page.locator('.md-err').count()) === 0)

/* ---------- 3.7 引用点击 → 跳知识库文档并高亮 ---------- */
// 显式切到引用里含 KB 文档名的会话，避免依赖列表顺序（置顶/归档会改变顺序）
await page.locator('.session-item', { hasText: 'B+ 树' }).first().click()
await page.waitForTimeout(900)
const cite = page.locator('.thread .cite-chip[data-clickable]').filter({ hasText: /\.(md|pdf|docx)$/i }).first()
await cite.click()
/* 点引用现在先开右侧面板（不跳页）；要跳知识库详情得点面板里的按钮 */
await page.waitForTimeout(800)
check('点引用先弹出右侧引用面板', (await page.locator('.ref-panel').count()) === 1)
await page.locator('.ref-panel button', { hasText: '在知识库中打开' }).click()
await page.waitForTimeout(1200)
const kbTitle = await page.locator('.page-title').first().innerText()
check('从面板跳转到知识库详情', kbTitle.includes('Java 笔记'), kbTitle)
const hlInfo = await page.evaluate(() => ({
  url: location.hash.slice(0, 70),
  hl: document.querySelectorAll('.doc-row[data-hl]').length,
  rows: document.querySelectorAll('.doc-row').length,
  tab: document.querySelector('.page .rubber-segment__item[aria-checked="true"]')?.textContent,
}))
check('目标文档被高亮定位', hlInfo.hl > 0, JSON.stringify(hlInfo))

/* ---------- 3.8 用量趋势动画与数字动画 ---------- */
await page.locator('.sb-nav button.rubber-segment__item', { hasText: '工作台' }).click()
await page.waitForTimeout(1500)
check('趋势折线带绘制动画类', (await page.locator('.wb-trend .trend-line').count()) === 1)
check('趋势面积带淡入类', (await page.locator('.wb-trend .trend-area').count()) === 1)
const legendCounters = await page.locator('.wb-trend-legend .counter-container').count()
check('图例数字使用滚动数字(Counter)', legendCounters === 3, `counter ${legendCounters} 个`)

/* ---------- 3.9 注销账号：两步确认 ---------- */
/* 「账号」导航项已移除（设置收在底部「个人设置」弹层），这里直接走路由 */
  await page.goto(BASE + '/#/app/account', { waitUntil: 'networkidle' })
await page.waitForTimeout(900)
// 设置页改版为多级：危险操作在「注销账号」分区里，先切过去
await page.locator('.settings-item', { hasText: '注销账号' }).first().click()
await page.waitForTimeout(500)
const step1Btn = page.locator('.danger-steps .btn-danger').first()
check('未确认时注销不可点', await step1Btn.isDisabled())
await page.locator('.danger-steps input:not([type="checkbox"])').fill('demo')
// 确认勾选已换成 SpringCheck（不再是原生 checkbox）
await page.locator('.danger-steps .spring-check').click()
await page.waitForTimeout(300)
check('输入用户名并勾选后可继续', !(await step1Btn.isDisabled()))
await step1Btn.click()
await page.waitForTimeout(500)
check('第二步展示将删除的数据清单', (await page.locator('.danger-list li').count()) === 4, `li ${await page.locator('.danger-list li').count()} 条`)
check('第二步有最终红色按钮', (await page.locator('.danger-final + div .btn-danger').count()) === 1)
await page.locator('.danger-final + div .btn-ghost').click()
await page.waitForTimeout(400)
check('取消后回到第一步', (await page.locator('.danger-steps .spring-check').count()) === 1)

/* ---------- 4.4 新建会话向导：Agent 单选 / 知识库多选必须真的能选 ---------- */
await page.locator('.sb-row--quick').click()
await page.waitForTimeout(900)
const wizAgent = await page.evaluate(() => {
  const chips = Array.from(document.querySelectorAll('.wizard-chip'))
  return { n: chips.length, on: chips.map((c) => c.hasAttribute('data-on')) }
})
check('向导第 1 步列出 Agent', wizAgent.n >= 3, `${wizAgent.n} 个`)
check('向导默认选中第一个 Agent', wizAgent.on.filter(Boolean).length === 1, JSON.stringify(wizAgent.on))

await page.locator('.wizard-chip').nth(2).click()
await page.waitForTimeout(350)
const picked = await page.evaluate(() => {
  const chips = Array.from(document.querySelectorAll('.wizard-chip'))
  const on = chips.filter((c) => c.hasAttribute('data-on'))
  return { count: on.length, name: on[0]?.textContent?.trim(), pressed: on[0]?.getAttribute('aria-pressed') }
})
check('点 Agent 能切换选择（单选）', picked.count === 1 && picked.pressed === 'true', `${picked.name}`)

/* 步骤圆应是"轮廓式"，不是实心色块 */
const stepStyle = await page.evaluate(() => {
  const el = document.querySelector('.dialog .step-indicator-inner')
  const cs = el ? getComputedStyle(el) : null
  return cs ? { bg: cs.backgroundColor, border: cs.borderTopWidth } : null
})
check('步骤圆用轮廓而非实心', stepStyle !== null && String(stepStyle.bg).includes('rgba(0, 0, 0, 0)') && parseFloat(stepStyle.border) >= 1,
  `${stepStyle?.bg} / ${stepStyle?.border}`)

await page.locator('.next-button').click()
await page.waitForTimeout(800)
const kbBefore = await page.evaluate(() => Array.from(document.querySelectorAll('.wizard-chip')).filter((c) => c.hasAttribute('data-on')).length)
await page.locator('.wizard-chip').nth(2).click()
await page.locator('.wizard-chip').nth(4).click()
await page.waitForTimeout(400)
const kbAfter = await page.evaluate(() => ({
  on: Array.from(document.querySelectorAll('.wizard-chip')).filter((c) => c.hasAttribute('data-on')).length,
  names: Array.from(document.querySelectorAll('.wizard-chip')).filter((c) => c.hasAttribute('data-on')).map((c) => c.textContent.trim()),
}))
check('知识库可多选（数量变化）', kbBefore !== kbAfter.on, `${kbBefore} → ${kbAfter.on}`)
check('多选后选中项可读', kbAfter.names.length === kbAfter.on && kbAfter.names.every(Boolean), kbAfter.names.join(' / '))

await page.locator('.next-button').click()
await page.waitForTimeout(700)
await page.locator('.next-button').click()
await page.waitForTimeout(1200)
const createdSession = await page.evaluate(() => ({
  dialog: document.querySelectorAll('.dialog').length,
  title: document.querySelector('[data-testid=chat-title]')?.textContent,
  welcome: document.querySelector('.thread .msg.bot .bub')?.textContent ?? '',
}))
check('走完向导会真的创建会话', createdSession.dialog === 0 && createdSession.title === '新会话', String(createdSession.title))
check('创建的会话带上所选的 Agent', createdSession.welcome.includes(picked.name ?? '\u0000'), createdSession.welcome.slice(0, 30))

await page.locator('.thread .msg.user, .thread .msg.bot').first().waitFor({ timeout: 4000 }).catch(() => {})

/* ---------- 4.5 退出登录收进头像菜单 ---------- */
await page.locator('.sb-row--settings').click()   // 账号入口改为底部「个人设置」弹层
await page.waitForTimeout(700)
await page.waitForTimeout(400)
check('个人设置弹层已打开', (await page.locator('.settings-dialog').count()) === 1)
const menuItems = await page.locator('.settings-dialog__item').count()
check('设置弹层列出全部分区', menuItems === 10, `实际 ${menuItems} 项`)
check('设置弹层含退出登录', (await page.locator('.settings-dialog__logout').count()) === 1)
check('设置弹层含搜索', (await page.locator('.settings-dialog__search input').count()) === 1)
check('侧栏不再常驻账号信息（无头像/无升级）', (await page.locator('.sb-avatar, .sb-upgrade, .sb-user').count()) === 0)
await page.keyboard.press('Escape')
await page.waitForTimeout(300)

/* ---------- 5. Agent 页卡片不再裁切 ---------- */
await page.locator('.sb-nav button.rubber-segment__item', { hasText: 'Agent' }).click()
await page.waitForTimeout(1200)
const clipped = await page.evaluate(() => {
  const acts = document.querySelector('.agent-inner .acts')
  const card = document.querySelector('.agent-grid .pixel-card')
  if (!acts || !card) return null
  return acts.getBoundingClientRect().bottom > card.getBoundingClientRect().bottom + 1
})
check('Agent 卡片底部按钮未被裁切', clipped === false, clipped === null ? '未找到节点' : String(clipped))

/* ---------- 8. 错误边界：渲染期异常要落到我们自己的恢复页 ---------- */
{
  const crashPage = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  const crashLogs = []
  crashPage.on('pageerror', (e) => crashLogs.push(String(e.message)))
  await crashPage.addInitScript(() => {
    localStorage.setItem('nx-proto-auth', '1')
    localStorage.setItem('nx-proto-tour-done', '1')
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))   // 高风险工具预授权：常规用例不被确认卡打断
    /* 验收钩子（仅 DEV）：让 AppShell 渲染时抛错 */
    window.__nxCrash = true
  })
  await crashPage.goto(BASE + '/#/app/chat', { waitUntil: 'networkidle' })
  await crashPage.waitForTimeout(2600)
  const eb = await crashPage.evaluate(() => ({
    ours: document.body.innerText.includes('这个页面出错了'),
    routerDefault: document.body.innerText.includes('Unexpected Application Error'),
    reloadBtn: !!Array.from(document.querySelectorAll('button')).find((b) => b.textContent.includes('重新加载')),
    homeLink: !!Array.from(document.querySelectorAll('a,button')).find((b) => b.textContent.includes('回到工作台')),
    keepsData: document.body.innerText.includes('已保存在本地'),
    details: !!document.querySelector('details'),
  }))
  check('渲染异常落到我们自己的错误页', eb.ours === true && eb.routerDefault === false, JSON.stringify(eb))
  check('错误页提供恢复动作（重新加载 / 回工作台）', eb.reloadBtn && eb.homeLink)
  check('错误页说明会话数据不会丢 + 可展开技术细节', eb.keepsData && eb.details)
  await crashPage.screenshot({ path: 'D:/develop/Prisma/docs/prototypes/shots/z39-error-boundary.png' })
  console.log('ok   z39-error-boundary（截图）')
  await crashPage.close()
}

await browser.close()
if (issues.length) {
  console.log(`\n发现 ${issues.length} 个问题:`)
  issues.forEach((i) => console.log('  - ' + i))
  process.exit(1)
} else {
  console.log('\n功能级 QA 全部通过')
}