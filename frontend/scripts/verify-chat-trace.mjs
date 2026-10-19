// 对话「思考与工具调用链」专项验证（mock 模式）
// 设计目标：跟着正文走的极简清单 —— 一处发丝线、无卡片、无背景、无胶囊标签。
import { chromium } from 'playwright-core'

const BASE = process.env.NEXUS_BASE ?? 'http://localhost:5175'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')
  // 预先授权高风险工具，链路才不会停在确认卡上（确认卡另有 qa-chat 覆盖）
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
})
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
await page.goto(`${BASE}/#/app/chat`, { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)

await page.locator('.prompt-bar__input').fill('解释一下 B+ 树索引，并给一个例子')
await page.locator('.prompt-bar__input').press('Enter')

// 思考态：复用工作台的 LatticeLoader sweep
// 注意：会话里已有历史消息的工具链，所以一律只看**最后一条**链路（本轮正在跑的那条）
await page.waitForSelector('.thought-chain[data-working]', { timeout: 5000 })
const chain = page.locator('.thought-chain').last()
const think = await chain.evaluate((el) => {
  const loader = el.querySelector('.lattice-loader')
  const cs = getComputedStyle(el)
  return {
    working: el.hasAttribute('data-working'),
    loader: !!loader,
    status: loader?.getAttribute('data-status') ?? '',
    text: el.querySelector('.thought-chain__time')?.textContent ?? '',
    bg: cs.backgroundColor,
    border: cs.borderTopWidth,
    radius: cs.borderRadius,
  }
})
check('流式期间出现思考链', think.working, JSON.stringify(think))
check('思考链复用 LatticeLoader', think.loader && think.status === 'working', JSON.stringify(think))
check('思考链不再是卡片', think.bg === 'rgba(0, 0, 0, 0)' && parseFloat(think.border) === 0 && think.radius === '0px', JSON.stringify(think))
await chain.screenshot({ path: 'qa-chat-thought-running.png' })

// 条目：一行一句话，可展开
await chain.locator('.thought-call').first().waitFor({ timeout: 8000 })
const row = await chain.locator('.thought-call .thought-call__row').first().evaluate((el) => {
  const argEl = el?.querySelector('.thought-call__arg')
  const cs = el ? getComputedStyle(el) : null
  return {
    tag: el?.tagName.toLowerCase() ?? '',
    display: cs?.display ?? '',
    height: el ? Math.round(el.getBoundingClientRect().height) : 0,
    bg: cs?.backgroundColor ?? '',
    radius: cs?.borderRadius ?? '',
    verb: el?.querySelector('.thought-call__verb')?.textContent ?? '',
    argBg: argEl ? getComputedStyle(argEl).backgroundColor : 'none',
  }
})
const head = await chain.locator('.thought-chain__head').evaluate((el) => ({
  border: getComputedStyle(el).borderBottomWidth,
  align: getComputedStyle(el).textAlign,
}))
check('条目是行内按钮', row.tag === 'button' && row.display === 'flex', JSON.stringify(row))
check('行内条目接近终端排版', row.height <= 26 && row.bg === 'rgba(0, 0, 0, 0)' && row.radius === '0px', JSON.stringify(row))
check('文案是人话动作而非函数名', /执行了|检索了|读取了|写入了|访问了|调用了/.test(row.verb), row.verb)
check('参数为纯文本（无胶囊底色）', row.argBg === 'rgba(0, 0, 0, 0)' || row.argBg === 'none', row.argBg)
check('只有标题下一处发丝线', parseFloat(head.border) === 1, head.border)
check('标题左对齐', head.align === 'left' || head.align === 'start', head.align)

// 展开 / 收起单条
const codeSel = chain.locator('.thought-call__code')
check('默认不展开输出', (await codeSel.count()) === 0)
await chain.locator('.thought-call__row').first().click()
check('点击条目展开输出', (await codeSel.count()) >= 1)
await chain.locator('.thought-call__row').first().click()
check('再次点击收起输出', (await codeSel.count()) === 0)

// 结束态：全部收尾，头部换成用时
await page.waitForFunction(() => {
  const chains = document.querySelectorAll('.thought-chain')
  const rows = chains[chains.length - 1]?.querySelectorAll('.thought-call') ?? []
  if (!rows.length) return false
  return Array.from(rows).every((r) => {
    const s = r.getAttribute('data-status')
    return s !== 'running' && s !== 'idle' && s !== 'awaiting'
  })
}, null, { timeout: 20000 })
// 收尾有 400ms 的落文案/总耗时延迟：等头部真的换成「用时」，而不是等 data-working 消失
await page.waitForFunction(() => {
  const chains = document.querySelectorAll('.thought-chain')
  const el = chains[chains.length - 1]
  return /用时/.test(el?.querySelector('.thought-chain__time')?.textContent ?? '')
}, null, { timeout: 20000 })
const settled = await page.evaluate(() => {
  const chains = document.querySelectorAll('.thought-chain')
  const el = chains[chains.length - 1]
  const msg = el?.closest('.msg.bot')
  return {
    running: document.querySelectorAll('.thought-call[data-status="running"]').length,
    rows: el?.querySelectorAll('.thought-call').length ?? 0,
    time: el?.querySelector('.thought-chain__time')?.textContent ?? '',
    state: el?.querySelector('.thought-chain__state')?.textContent ?? '',
    dir: msg ? getComputedStyle(msg).flexDirection : '',
  }
})
check('结束后没有运行中的条目', settled.running === 0, JSON.stringify(settled))
check('结束后条目保留为记录', settled.rows >= 3, JSON.stringify(settled))
check('头部显示用时', /用时/.test(settled.time), settled.time)
check('正文首行提示已完成分析', /已完成分析/.test(settled.state), settled.state)
check('bot 消息为纵向步骤流', settled.dir === 'column', settled.dir)
await chain.screenshot({ path: 'qa-chat-thought-chain.png' })

// 整条链路可折叠
await chain.locator('.thought-chain__head').click()
check('点击头部收起整条链路', (await chain.locator('.thought-chain__body').count()) === 0)
await chain.locator('.thought-chain__head').click()
check('再次点击展开整条链路', (await chain.locator('.thought-chain__body').count()) >= 1)

// 会话内「轨迹」视图
await page.locator('.chat-view-tabs button.rubber-segment__item', { hasText: /轨迹|Trace/ }).click()
await page.waitForSelector('.trace-layout')
const trace = await page.evaluate(() => ({
  layout: !!document.querySelector('.trace-layout'),
  stripRows: document.querySelectorAll('.trace-strip__row').length,
  entries: document.querySelectorAll('.trace-rail .trace-entry').length,
  toolEntries: document.querySelectorAll('.trace-entry[data-kind="tool"]').length,
  turns: document.querySelectorAll('.trace-entry--turn').length,
  overview: !!document.querySelector('.trace-side'),
}))
check('轨迹视图渲染', trace.layout && trace.stripRows === 3, JSON.stringify(trace))
check('轨迹时间线含轮次与工具', trace.entries >= 4 && trace.toolEntries >= 1 && trace.turns >= 1, JSON.stringify(trace))
await page.locator('.trace-head .market-search input').fill('kb.search')
await page.waitForTimeout(250)
check('轨迹搜索可用', (await page.locator('.trace-rail .trace-entry').count()) >= 1)
await page.screenshot({ path: 'qa-chat-trace-view.png' })

console.log(issues.length === 0 ? 'ALL OK' : `ISSUES: ${issues.join(' | ')}`)
await browser.close()
process.exit(issues.length ? 1 : 0)
