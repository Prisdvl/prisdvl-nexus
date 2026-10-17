/**
 * 审批链路定向验证（工具等待确认 → 前端分发 → 确认卡 → 决定提交 → 继续执行 / 取消）。
 *
 * ⚠️ 这条用例**不是**后端闸门的端到端验证，读结论时不要混淆：
 *   - 真实的部分：真前端（USE_MOCK=false）、真后端、真 SSE 传输与解析、真 ConfirmCard、
 *     真的 `POST /api/agent/runs/{runId}/approval` 请求体（本脚本的代理会记录并断言）。
 *   - 注入的部分：`approval_required` 那一帧由本脚本的内置代理插入。原因见下。
 *   - 为什么必须注入：审批只由**数据库里的** `ai_tool_registry.permission`（运行时权威，
 *     见 V22 文件头 / ToolRegistrationService：同步不覆盖库中原值）为 WRITE/DANGEROUS 的工具触发，
 *     而内置工具 knowledge_search / calculator 都是 READ；现有接口 `PUT /api/tools/{code}`
 *     只能改 enabled、改不了 permission（改动它属于新增权限管理功能，超出本用例范围）。
 *     所以真机跑这条用例前需要运维改一行库（不需要改代码）：
 *       UPDATE ai_tool_registry SET permission='WRITE' WHERE code='calculator';
 *     不注入的版本在那种环境下同样可用（把 INJECT=0 走真闸门即可，帧序完全一致）。
 *
 * 代理按闸门的真实语义"挂起"：注入审批帧后扣住后续帧，直到前端提交决定才放行；
 * 且**拒绝时丢弃该工具的 `tool_call` 帧**——被拒的工具后端根本不执行
 * （ExecutingToolCallback.passGate → GateResult.denied → 只 record 不 call），
 * 否则测到的是代理的行为而不是产品的行为。
 *
 * 前置：
 *   1. 后端在 8080（standalone 即可：`java -jar nexus-app/target/*.jar --spring.profiles.active=standalone`）；
 *   2. 前端 dev server 的 API 代理指向本脚本（否则用例立刻报"未收到任何流量"）：
 *        cd frontend && VITE_BACKEND_ORIGIN=http://127.0.0.1:8099 npx vite --port 5176 --strictPort
 *      然后 `NEXUS_BASE=http://localhost:5176 node scripts/verify-approval-chain.mjs`
 *
 * 不依赖任何新包（playwright-core 已是 devDependency）。
 */
import http from 'node:http'
import { chromium } from 'playwright-core'

const API = process.env.NEXUS_API ?? 'http://127.0.0.1:8080/api'
const BASE = process.env.NEXUS_BASE ?? 'http://localhost:5176'
const PROXY_PORT = Number(process.env.INJECT_PORT ?? 8099)
const TOOL = process.env.INJECT_TOOL ?? 'calculator'
const INJECT = process.env.INJECT !== '0'
const UPSTREAM = process.env.NEXUS_UPSTREAM ?? 'http://127.0.0.1:8080'

/* ---------- 内置注入代理 ---------- */
let proxyHits = 0
let decision = null
let flush = null
const proxy = http.createServer(async (req, res) => {
  proxyHits += 1
  const chunks = []
  for await (const c of req) chunks.push(c)
  const body = Buffer.concat(chunks)

  if (/^\/api\/agent\/runs\/[^/]+\/approval$/.test(req.url)) {
    let approved = true
    try { approved = JSON.parse(body.toString())?.approved !== false } catch { /* 保持默认 */ }
    decision = { url: req.url, body: body.toString(), approved }
    if (flush) { flush(); flush = null }
    res.writeHead(200, { 'Content-Type': 'application/json' })
    return res.end(JSON.stringify({ code: 0, message: 'ok', data: { runId: 'injected', status: 'RUNNING' } }))
  }

  const upstream = await fetch(UPSTREAM + req.url, {
    method: req.method,
    headers: { ...req.headers, host: '127.0.0.1:8080' },
    body: ['GET', 'HEAD'].includes(req.method) ? undefined : body,
    redirect: 'manual',
  })
  const headers = Object.fromEntries(upstream.headers)
  for (const h of ['content-length', 'transfer-encoding', 'connection', 'content-encoding']) delete headers[h]
  const isStream = (headers['content-type'] ?? '').includes('text/event-stream')
  res.writeHead(upstream.status, headers)
  if (!isStream) { res.end(Buffer.from(await upstream.arrayBuffer())); return }

  const reader = upstream.body.getReader()
  const dec = new TextDecoder()
  let buf = ''
  let gating = false
  let injected = false
  const gateBuf = []
  flush = () => {
    if (!gating) return
    gating = false
    const frames = gateBuf.splice(0)
    const kept = decision && decision.approved === false
      ? frames.filter((f) => !(/event:\s*tool_call/.test(f) && f.includes(`"${TOOL}"`)))
      : frames
    for (const f of kept) res.write(f)
  }
  setTimeout(() => { if (gating && flush) flush() }, 12000)

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += dec.decode(value, { stream: true })
    let i
    while ((i = buf.indexOf('\n\n')) >= 0) {
      const frame = buf.slice(0, i) + '\n\n'
      buf = buf.slice(i + 2)
      if (INJECT && !injected && /event:\s*tool_call/.test(frame) && frame.includes(`"${TOOL}"`)) {
        injected = true
        gating = true
        res.write('event:approval_required\ndata:' + JSON.stringify({
          runId: 'injected-run', approvalId: 'injected-approval', toolCode: TOOL,
          arguments: '{"expression":"1234*5678"}',
          reason: '该工具会修改数据（WRITE），执行前需要你确认。',
        }) + '\n\n')
      }
      if (gating) gateBuf.push(frame)
      else res.write(frame)
    }
  }
  flush = null
  res.end()
})

const results = []
const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`)
  if (!ok) results.push(name)
}
const api = async (m, p, b, t) => {
  const r = await fetch(API + p, { method: m, headers: { 'Content-Type': 'application/json', ...(t ? { Authorization: 'Bearer ' + t } : {}) }, ...(b === undefined ? {} : { body: JSON.stringify(b) }) })
  const txt = await r.text(); let j = null; try { j = JSON.parse(txt) } catch { /* 非 JSON */ }
  return { status: r.status, body: j }
}

await new Promise((r) => proxy.listen(PROXY_PORT, '127.0.0.1', r))
const user = 'qa_ap_' + (Date.now() % 1000000)
const pass = 'Qa-ap-pass-1234'
await api('POST', '/auth/register', { username: user, password: pass, email: `${user}@example.com` })
const login = await api('POST', '/auth/login', { username: user, password: pass })
const token = login.body?.data?.accessToken
if (!token) {
  console.log(`FAIL 无法登录后端 ${API}（先启动后端）`)
  process.exit(1)
}
await api('POST', '/chat/conversations', { title: 'warmup', mode: 'AGENT' }, token)

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } })
const errs = []
page.on('pageerror', (e) => errs.push(String(e.message).slice(0, 120)))
page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 120)) })
await page.addInitScript((tk) => {
  localStorage.setItem('nx-token', tk)
  localStorage.setItem('nx-token-expires-at', String(Date.now() + 1800000))
  localStorage.setItem('nx-proto-tour-done', '1')
}, token)
await page.goto(`${BASE}/#/app/chat`, { waitUntil: 'networkidle' })
await page.waitForSelector('.prompt-bar__input', { timeout: 20000 })

const snapshot = () => page.evaluate(() => {
  const chains = document.querySelectorAll('.thought-chain')
  const el = chains[chains.length - 1]
  const rows = el ? Array.from(el.querySelectorAll('.thought-call')) : []
  return {
    rows: rows.map((r) => `${r.getAttribute('data-tool')}:${r.getAttribute('data-status')}${r.hasAttribute('data-warn') ? '(warn)' : ''}`),
    card: document.querySelectorAll('.confirm-card').length,
    cardText: (document.querySelector('.confirm-card')?.textContent ?? '').replace(/\s+/g, ' ').trim(),
    summary: el?.querySelector('.thought-chain__time')?.textContent ?? '',
    working: el?.hasAttribute('data-working') ?? false,
  }
})

async function scenario(label, button, expectScope) {
  decision = null
  await api('POST', '/chat/conversations', { title: label, mode: 'AGENT' }, token)
  await page.reload({ waitUntil: 'networkidle' })
  const row = page.locator('.sb-session', { hasText: label }).first()
  if (await row.count()) await row.click()
  await page.waitForTimeout(300)
  await page.locator('.prompt-bar__input').fill('TWO 请依次使用工具')
  await page.locator('.prompt-bar__input').press('Enter')

  let s = null
  for (let i = 0; i < 100; i++) {
    s = await snapshot()
    if (s.card === 1) break
    await page.waitForTimeout(100)
  }
  check(`${label}：确认卡出现`, s?.card === 1, JSON.stringify(s?.rows))
  check(`${label}：出现等待确认条目（data-status=awaiting + data-warn）`, /awaiting\(warn\)/.test((s?.rows ?? []).join(' ')), JSON.stringify(s?.rows))
  check(`${label}：卡片带工具名与理由`, (s?.cardText ?? '').includes(TOOL) && (s?.cardText ?? '').length > 10, (s?.cardText ?? '').slice(0, 80))
  if (s?.card !== 1) return s

  const before = s.rows.length
  await page.locator('.confirm-card button', { hasText: button }).click()
  for (let i = 0; i < 120; i++) {
    s = await snapshot()
    if (!s.working && s.summary.includes('用时')) break
    await page.waitForTimeout(100)
  }
  check(`${label}：条目数不随状态变化增长（${before} → ${s.rows.length}）`, s.rows.length === before, JSON.stringify(s.rows))
  const body = decision?.body ?? ''
  check(`${label}：提交了协议要求的请求体（${expectScope}）`, body.includes(`"scope":"${expectScope}"`), body || '(未捕获到审批请求)')
  return s
}

const allow = await scenario('允许一次', '允许一次', 'once')
if (allow?.rows) check('允许一次：该工具收尾为 done 且摘要完成', /calculator:done/.test(allow.rows.join(' ')) && allow.summary.includes('用时'), JSON.stringify(allow.rows))

const deny = await scenario('拒绝', '拒绝', 'deny')
if (deny?.rows) {
  check('拒绝：该工具为 cancelled（未被 tool_result 改写成 done）', /calculator:cancelled/.test(deny.rows.join(' ')), JSON.stringify(deny.rows))
  check('拒绝：链路继续（同轮其它工具仍 done，摘要完成）', /done/.test(deny.rows.join(' ')) && deny.summary.includes('用时'), `${JSON.stringify(deny.rows)} | ${deny.summary}`)
}

const always = await scenario('始终允许', '始终允许', 'always')
if (always?.rows) check('始终允许：该工具收尾为 done', /calculator:done/.test(always.rows.join(' ')), JSON.stringify(always.rows))

check('全程无控制台错误', errs.length === 0, errs.slice(0, 3).join(' | '))
check('前端确经本脚本代理（否则用例无意义）', proxyHits > 0, `proxyHits=${proxyHits}`)

await browser.close()
proxy.close()
console.log(results.length === 0 ? '\n审批链路定向验证全部通过' : `\nISSUES: ${results.join(' | ')}`)
process.exit(results.length ? 1 : 0)
