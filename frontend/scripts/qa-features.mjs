// 功能级 QA：把「需求里点名的交互」逐条真点一遍（不做视觉判断，只验证行为）
import { chromium } from 'playwright-core'
import os from 'node:os'
import path from 'node:path'

const BASE = 'http://localhost:5175'
// 截图输出：显式 QA_SHOTS_OUT 优先；未设置时落到系统临时目录。
// 刻意**不**写进原型截图目录（已不再维护），也不写向只读备份仓库。
const SHOTS_OUT = process.env.QA_SHOTS_OUT ?? path.join(os.tmpdir(), 'prisma-recover-qa-shots')
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
await page.locator('.floating-nav button', { hasText: '登录' }).first().click()
await page.waitForSelector('.auth-card')
await page.locator('#li-u').fill('qa-runner')
await page.locator('#li-p').fill('qa-runner-password')
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
await page.waitForSelector('.shell')
await page.waitForTimeout(2000)

/* ---------- 1. 侧栏：竖向橡皮导航 ---------- */
const navItems = await page.locator('.sb-nav button.rubber-segment__item').count()
/* 工作台 / 知识库 / 对话 / Agent / 自动化 / 工作流 / 插件 / Agent 社区，共 8 项 */
check('侧栏有 8 个导航项', navItems === 8, `实际 ${navItems}`)
const isVerticalList = await page.locator('.sb-nav .rubber-segment[data-vertical]').count()
check('侧栏为极简竖向 RubberSegment', isVerticalList === 1)
await page.locator('.sb-nav button.rubber-segment__item', { hasText: '知识库' }).click()
await page.waitForTimeout(700)
check('点击侧栏可切页', (await page.locator('.page-title').first().innerText()).includes('知识库'))

/* ---------- 2. 工作台新增内容 ---------- */
await page.locator('.sb-nav button.rubber-segment__item', { hasText: '工作台' }).click()
await page.waitForTimeout(1600)
const heatCells = await page.locator('.wb-heat span').count()
check('活跃度图渲染 12 周热力格', heatCells === 84, `实际 ${heatCells}`)
check('用量趋势图已渲染', (await page.locator('.wb-trend svg path').count()) >= 2)
/* 工作台的「总览」现在是磁贴网格里的一块（原来的 .stat-grid/.stat-card 已不存在） */
const statCards = await page.locator('.wbw-stats .wbw-stat').count()
const glowCards = await page.locator('.wbw-stats .border-glow-card').count()
check('总览磁贴的四项统计使用低噪声表面（不含光条组件）', statCards === 4 && glowCards === 0, `卡片 ${statCards} / 光条 ${glowCards}`)
check('活动时间线 5 条', (await page.locator('.wb-timeline li').count()) === 5)

/* ---------- 3. 对话页：会话切换 / 管理 / 消息操作 / 绑定 ---------- */
await page.locator('.sb-nav button.rubber-segment__item', { hasText: '对话' }).click()
await page.waitForTimeout(900)
const title1 = await page.getByTestId('chat-title').innerText()
const firstMsg1 = await page.locator('.thread .bub').first().innerText()
await page.locator('.session-open').nth(1).click()
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

// 置顶：直接点会话行里的「置顶」操作
const firstBefore = await page.locator('.session-item .session-open').first().innerText()
const redisRow = page.locator('.session-list .session-item').filter({ hasText: 'Redis' }).first()
await redisRow.scrollIntoViewIfNeeded()   // 短视口下该行可能在可视区外，鼠标坐标会落到侧栏上
await page.waitForTimeout(400)
await redisRow.hover()
await page.waitForTimeout(250)
await redisRow.locator('.session-action').first().click({ force: true })
await page.waitForTimeout(600)
/* 置顶不再用行内 pin 图标（那会把文字起点顶偏、三层对不齐），
   改为验证它确实进入了「置顶」分组 */
const pinnedGroup = await page.evaluate(() => {
  const group = Array.from(document.querySelectorAll('.sb-group'))
    .find((g) => g.querySelector('.sb-section--toggle')?.textContent.includes('置顶'))
  return Array.from(group?.querySelectorAll('.sb-session') ?? []).map((e) => e.textContent?.trim() ?? '')
})
check('滑动置顶后该会话进入「置顶」分组', pinnedGroup.some((x) => x.includes('Redis')), pinnedGroup.join(' / '))

// 消息操作：先发一条（生成含表格/代码块/Mermaid 的回答），再从该回答**分支到新对话**
const msgsBeforeSend = await page.locator('.thread .msg').count()
await page.locator('.prompt-bar__input').fill('RDB 和 AOF 怎么选？')
await page.locator('.prompt-bar__input').press('Enter')
/* 等工具调用跑完并给出回答（3 条调用约 3.5s） */
await page.waitForTimeout(5200)
check('发送后生成回答', (await page.locator('.thread .msg').count()) > msgsBeforeSend)

const sessionsBefore = await page.locator('.session-list .session-item').count()
const titleBefore = await page.locator('.session-item[data-selected] .session-open').innerText()
await page.locator('.thread .msg').last().hover()
await page.locator('.thread .msg').last().getByRole('button', { name: '从此处创建分支' }).click()
await page.waitForTimeout(1200)
const sessionsAfter = await page.locator('.session-list .session-item').count()
const titleAfter = await page.locator('.session-item[data-selected] .session-open').innerText()
check('分支后多出一个新会话', sessionsAfter === sessionsBefore + 1, `${sessionsBefore} → ${sessionsAfter}`)
check('新分支使用连续命名「分支」', titleAfter === '分支' && titleAfter !== titleBefore, `${titleBefore} → ${titleAfter}`)
check('分支复制了原上下文', (await page.locator('.thread .msg').count()) > 0)
// 分支会话带着"到该条为止"的上下文，后续 Markdown 断言正是在它上面验证

// 消息操作：编辑重发（用户消息）
await page.locator('.thread .msg.user').last().hover()
await page.locator('.thread .msg.user').last().getByRole('button', { name: '编辑并重新发送' }).click()
await page.waitForTimeout(300)
check('编辑重发进入编辑态', (await page.locator('.thread textarea').count()) > 0)
await page.locator('.thread button', { hasText: '取消' }).click()
await page.waitForTimeout(200)

// 绑定：会话配置弹层里切换模式并绑定知识库
await page.locator('.session-config__summary').click()
await page.waitForTimeout(400)
check('会话配置弹层打开', (await page.locator('.session-config__pop').count()) === 1)
await page.locator('.session-config__pop .sc-opt', { hasText: '知识库问答' }).click()
await page.waitForTimeout(400)
await page.locator('.session-config__summary').click()
await page.waitForTimeout(250)
const chipsBefore = await page.locator('.session-config__summary').innerText()
await page.locator('.session-config__summary').click()
await page.waitForTimeout(300)
await page.locator('.session-config__pop .sc-opt', { hasText: '我的 Java' }).first().click()
await page.waitForTimeout(450)
const chipsAfter = await page.locator('.session-config__summary').innerText()
check('知识库绑定数量变化', chipsBefore !== chipsAfter, `${chipsBefore.trim()} → ${chipsAfter.trim()}`)

// Agent 只在 AGENT 模式可切换
await page.locator('.session-config__pop .sc-opt', { hasText: 'Agent' }).click()
await page.waitForTimeout(400)
await page.locator('.session-config__pop .sc-opt', { hasText: '代码审查员' }).click()
await page.waitForTimeout(450)
const agentSummary = await page.locator('.session-config__summary').innerText()
check('Agent 切换生效', agentSummary.includes('代码审查员'), agentSummary)
await page.locator('.session-config__summary').click()
await page.waitForTimeout(200)

// 导出下载
const dl = page.waitForEvent('download', { timeout: 6000 }).catch(() => null)
await page.locator('.chat-layout header button[title="导出为 Markdown"]').click()
const download = await dl
check('导出对话触发下载', !!download, download ? await download.suggestedFilename() : '无下载事件')

/* ---------- 4. 新建会话向导：模式驱动的两步表单 ---------- */
await page.locator('.sb-row--quick').click()
await page.waitForTimeout(500)
check('新建会话弹窗打开', (await page.locator('.dialog .step-indicator-inner').count()) === 2)
check('向导先提供三个真实模式', (await page.locator('.dialog button.rubber-segment__item').count()) === 3)
check('普通对话默认不显示虚构绑定项', (await page.locator('.dialog .wizard-chip').count()) === 0)
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
  title: document.querySelector('.session-item[data-selected] .session-open')?.textContent,
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

/* ---------- 3.7 引用预览：缺少服务端引用 ID 时不伪造正文或跳转 ---------- */
await page.locator('.session-open', { hasText: 'B+ 树' }).first().click()
await page.waitForTimeout(700)
await page.locator('.thread .cite-chip[data-clickable]').first().click()
await page.waitForTimeout(350)
check('点引用打开侧栏面板', (await page.locator('.ref-panel').count()) === 1)
check('缺少真实来源 ID 时预览明确提示且跳转禁用',
  (await page.locator('.ref-panel [role="alert"]').innerText()).includes('未包含知识库与文档 ID') && await page.locator('.ref-panel .ref-head button[disabled]').count() === 1)
await page.locator('.ref-panel button[aria-label="关闭引用面板"]').click()

/* ---------- 3.8 用量趋势动画与数字动画 ---------- */
await page.locator('.sb-nav button.rubber-segment__item', { hasText: '工作台' }).click()
await page.waitForTimeout(1500)
check('趋势折线带绘制动画类', (await page.locator('.wb-trend .trend-line').count()) === 1)
check('趋势面积带淡入类', (await page.locator('.wb-trend .trend-area').count()) === 1)
const legendCounters = await page.locator('.wb-trend-legend .counter-container').count()
check('图例数字使用滚动数字(Counter)', legendCounters === 3, `counter ${legendCounters} 个`)

/* ---------- 3.9 注销账号：后端无注销接口时不显示虚构确认流程 ---------- */
await page.goto(BASE + '/#/app/account', { waitUntil: 'networkidle' })
await page.waitForTimeout(900)
await page.locator('.settings-item', { hasText: '注销账号' }).first().click()
await page.waitForTimeout(400)
check('无账号注销 API 时危险操作保持禁用', (await page.locator('.settings-body .btn-danger[disabled]').count()) === 1)
check('明确说明服务端暂不支持注销', (await page.locator('.settings-body').innerText()).includes('服务端目前没有账号注销接口'))

/* ---------- 4.4 新会话：真实模式/知识库选择，创建后不插入虚构欢迎消息 ---------- */
await page.locator('.sb-row--quick').click()
await page.waitForTimeout(500)
await page.locator('.dialog button.rubber-segment__item', { hasText: '知识库问答' }).click()
await page.waitForTimeout(400)
const kbChips = page.locator('.dialog .wizard-chip')
check('知识库问答模式列出当前测试知识库', await kbChips.count() >= 2, `实际 ${await kbChips.count()} 个`)
await kbChips.nth(0).click()
await kbChips.nth(1).click()
await page.getByRole('button', { name: '继续' }).click()
await page.waitForTimeout(300)
check('确认页反映选择的两个知识库', (await page.locator('.dialog').innerText()).includes('绑定 2 个知识库'))
await page.getByRole('button', { name: /创建会话|Complete/ }).click()
await page.waitForTimeout(900)
const createdSession = await page.evaluate(() => ({
  dialog: document.querySelectorAll('.dialog').length,
  title: document.querySelector('[data-testid=chat-title]')?.textContent,
  kbButton: document.querySelector('.session-config__summary')?.textContent,
  messages: document.querySelectorAll('.thread .msg').length,
}))
check('完成向导后创建会话并绑定资料', createdSession.dialog === 0 && createdSession.title === '新会话' && (createdSession.kbButton ?? '').includes('2'), JSON.stringify(createdSession))
check('新会话不插入虚构欢迎消息', createdSession.messages === 0, `消息数 ${createdSession.messages}`)

/* ---------- 4.5 退出登录收进头像菜单 ---------- */
await page.locator('.sb-row--settings').click()   // 账号入口改为底部「个人设置」弹层
await page.waitForTimeout(700)
await page.waitForTimeout(400)
check('个人设置弹层已打开', (await page.locator('.settings-dialog').count()) === 1)
const menuItems = await page.locator('.settings-dialog__item').count()
check('设置弹层列出全部分区', menuItems === 12, `实际 ${menuItems} 项`)
check('设置弹层含退出登录', (await page.locator('.settings-dialog__logout').count()) === 1)
check('设置弹层含搜索', (await page.locator('.settings-dialog__search input').count()) === 1)
check('侧栏不再常驻账号信息（无头像/无升级）', (await page.locator('.sb-avatar, .sb-upgrade, .sb-user').count()) === 0)
await page.keyboard.press('Escape')
await page.waitForTimeout(300)

/* ---------- 5. Agent 页卡片不再裁切 ---------- */
await page.locator('.sb-nav button.rubber-segment__item', { hasText: /^Agent$/ }).click()
/*
 * 等真节点出现再量，而不是靠固定等待：Agent 列表来自一次数据请求，runner 上比本机慢得多，
 * 1.2s 之后卡片可能还没挂上去（CI 上就是这样把"还没渲染"读成"未找到节点"的）。
 * 超时也照量——真的没有节点，下面的 check 依然判 FAIL。
 */
await page.locator('.agent-grid .pixel-card').first().waitFor({ state: 'visible', timeout: 15000 }).catch(() => {})
await page.locator('.agent-inner .acts').first().waitFor({ state: 'attached', timeout: 15000 }).catch(() => {})
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
  await crashPage.screenshot({ path: `${SHOTS_OUT}/z39-error-boundary.png` })
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
