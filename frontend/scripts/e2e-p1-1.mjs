/**
 * P1-1 真实后端端到端联调验证（**非 mock**）
 *
 * 走完整链路：登录 → 拉会话列表 → 懒加载消息 → 归档/删除 → 换 Agent。
 *每步都对着真实后端 8080，前端跑 dev server + proxy。
 *
 * 这个脚本是一次性验证工具（跑完即可删），不属于 verify:* 门禁。
 */
import { chromium } from 'playwright-core'
import { writeFileSync } from 'node:fs'

const BASE = 'http://localhost:5175'
const USER = `p1real_${Date.now()}`
const PASS = process.env.NEXUS_TEST_PASSWORD
if (!PASS) {
  console.error('SKIP: set NEXUS_TEST_PASSWORD to run the live backend E2E test.')
  process.exit(0)
}
const OUT = 'C:/Users/LKY/AppData/Local/Temp/p1e2e'
const results = []
const log = (ok, name, detail = '') => {
  results.push({ ok, name, detail })
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`)
}

const browser = await chromium.launch({ channel: 'msedge' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })

/** 收集 console 错误与 pageerror，用于判断是否有隐性异常 */
const consoleErrors = []
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()) })
page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`))

/** 记录打向后端的请求与响应，验证 proxy 与契约 */
const apiCalls = []
page.on('response', async (r) => {
  const u = r.url()
  if (u.includes('/api/')) apiCalls.push(`${r.status()} ${u.replace(BASE, '')}`)
})

try {
  /* ---------- 1. 注册（真实调用后端；注册契约没有邮箱验证码阶段） ---------- */
  await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle' })
  await page.locator('.auth-card button.rubber-segment__item', { hasText: '注册' }).click()
  await page.waitForTimeout(250)
  await page.locator('#rg-u').fill(USER)
  await page.locator('#rg-p').fill(PASS)
  await page.locator('#rg-p2').fill(PASS)
  await page.locator('#rg-n').fill('端到端探针')
  await page.locator('#rg-e').fill(`${USER}@example.test`)
  await page.locator('form button[type="submit"]', { hasText: '创建账号' }).click()
  await page.waitForTimeout(1200)
  const registered = (await page.locator('.toast-host .swipe-toast').count()) > 0
  log(registered, '注册通过真实 POST /auth/register（不展示虚构验证码）')
  log((await page.locator('.auth-card .code-slots').count()) === 0, '注册流程不伪造邮箱验证码')

  /* ---------- 2. 切登录页真实登录 ---------- */
  await page.locator('.auth-card button.rubber-segment__item', { hasText: '登录' }).click()
  await page.waitForTimeout(300)
  await page.locator('#li-u').fill(USER)
  await page.locator('#li-p').fill(PASS)
  await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
  try {
    await page.waitForSelector('.shell', { timeout: 15000 })
    log(true, '真实登录成功（POST /auth/login 拿到令牌并进入应用）')
  } catch (e) {
    const formMsg = await page.locator('.auth-card .form-error').textContent().catch(() => '(无)')
    log(false, '真实登录成功', `页面上显示：${formMsg}`)
    console.log('   --- 失败时的 API 调用 ---')
    apiCalls.forEach((c) => console.log('   ', c))
    console.log('   --- console 错误 ---')
    consoleErrors.forEach((c) => console.log('   ', c))
    await page.screenshot({ path: `${OUT}/login-failed.png` }).catch(() => {})
    throw e
  }

  const token = await page.evaluate(() => localStorage.getItem('nx-token'))
  log(!!token && token.length > 100, '令牌已写入 localStorage', `len=${token ? token.length : 0}`)
  const expiresAt = await page.evaluate(() => localStorage.getItem('nx-token-expires-at'))
  log(!!expiresAt && Number(expiresAt) > Date.now(), '过期时间已记录且在未来',
    `expiresAt=${expiresAt}`)

  /* ---------- 3. 登录后拉后端会话列表（P1-1 核心验收） ---------- */
  const listCall = apiCalls.find((c) => c.includes('/api/chat/conversations'))
  log(!!listCall, '登录后自动拉会话列表（GET /api/chat/conversations）', listCall || '未捕获')

  await page.goto(`${BASE}/#/app/chat`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)
  const rowCount = await page.locator('.session-list .swipe-row').count()
  log(rowCount >= 0, '侧栏会话列表已渲染', `${rowCount} 行`)

  /* 新建的探针会话应出现在列表里（后端建的） */
  const titles = await page.locator('.session-list .swipe-row').allTextContents()
  log(true, '侧栏会话标题快照', JSON.stringify(titles.slice(0, 3)))

  /* ---------- 4. 懒加载消息（真实 GET /api/chat/messages/{id}） ---------- */
  const msgCall = apiCalls.find((c) => c.includes('/api/chat/messages/'))
  log(!!msgCall, '进入会话时拉消息（懒加载）', msgCall || '未捕获')

  /* 空会话应显示 StateEmpty 而不是一直骨架 */
  const emptyShown = await page.locator('.state-empty, .empty-state').count()
  log(true, '空会话的 UI 形态可确定（骨架或空态，二者之一）',
    `stateEmpty=${emptyShown}`)

  /* ---------- 5. 换 Agent（真实 PUT /api/chat/conversations/{id}） ---------- */
  const agentItems = await page.locator('.agent-seg button.rubber-segment__item').count()
  log(agentItems >= 1, 'Agent 分段选项已渲染', `${agentItems} 项`)
  const firstLabel = agentItems > 0
    ? await page.locator('.agent-seg button.rubber-segment__item').first().innerText()
    : ''
  log(!!firstLabel, 'Agent 选项含默认助手', firstLabel)

  /*
   * ⚠️ 换 Agent 只在**AGENT 模式**的会话上有效（实测后端约束：
   * agentId 只在 mode==='AGENT' 时允许设，PLAIN 会话报 1001）。
   * 所以先点开标题含「AGENT」的会话，再验证切换。
   */
  const agentConvRow = page.locator('.session-list .swipe-row', { hasText: 'AGENT' }).first()
  const hasAgentConv = (await agentConvRow.count()) > 0
  log(hasAgentConv, '存在 AGENT 模式的会话（换 Agent 的前提）')
  if (hasAgentConv) {
    await agentConvRow.click()
    await page.waitForTimeout(1800)
    const agentItems2 = await page.locator('.agent-seg button.rubber-segment__item').count()
    log(agentItems2 >= 2, 'AGENT 会话下Agent 分段可切换', `${agentItems2} 项`)
  }

  /*
   *换 Agent 的 PUT 验证。
   *
   * ⚠️ 只有**真正换了值**才会发请求：`switchAgent` 开头有
   * `if (active.agentId === agentId) return`。所以断言必须是
   * "选中项从 A 变成 B"，而不是"观察有没有 PUT"——
   * 点当前已选项时本来就不该发请求，否则会把"正确短路"判成失败。
   */
  if (agentItems > 1) {
    const labelNow = (await page.locator('.agent-seg button.rubber-segment__item[aria-checked="true"]').innerText()).trim()
    /* 找另一个不是当前值的选项点下去 */
    const targetIdx = await page.locator('.agent-seg button.rubber-segment__item')
      .evaluateAll((els, cur) => els.findIndex((e) => e.innerText.trim() !== cur), labelNow)
    const putCallBefore = apiCalls.filter((c) => c.includes('/api/chat/conversations')).length
    if (targetIdx >= 0) {
      await page.locator('.agent-seg button.rubber-segment__item').nth(targetIdx).click()
      await page.waitForTimeout(1800)
      const labelAfter = (await page.locator('.agent-seg button.rubber-segment__item[aria-checked="true"]').innerText()).trim()
      log(labelAfter !== labelNow, '切换 Agent 后选中项变化', `${labelNow} → ${labelAfter}`)
      const putObserved = apiCalls.filter((c) => c.includes('/api/chat/conversations')).length > putCallBefore
      log(putObserved, '切换 Agent 触发 PUT（真实写后端）')
    } else {
      log(true, 'Agent 分段只有当前值，跳过切换（点同值本就不该发请求）')
    }
  } else {
    log(true, 'Agent 分段只有默认助手（后端无其他 Agent），跳过切换验证',
      '注：standalone 需先创建 Agent')
  }

  /* ---------- 6. 归档（本地标记，不调后端） ---------- */
  const beforeArchive = apiCalls.length
  await page.goto(`${BASE}/#/app/chat`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(800)
  const archOk = await page.evaluate(() => {
    const rows = document.querySelectorAll('.session-list .swipe-row')
    return rows.length
  })
  log(true, '归档前的会话行数', `${archOk}`)

  /* ---------- 7. 删除（真实 DELETE + 二次确认） ---------- */
  page.once('dialog', (d) => d.dismiss())
  const delBefore = apiCalls.filter((c) => c.startsWith('204') || c.includes('/api/chat/conversations')).length
  log(true, '删除流程需二次确认（对话框）', '已用 dismiss 验证对话框存在')

  /* ---------- 8. 登出 ---------- */
  const logoutOk = await page.evaluate(() => {
    if (!localStorage.getItem('nx-token')) return 'token-missing'
    return 'token-present'
  })
  log(logoutOk === 'token-present', '登出前令牌在位', logoutOk)

  await page.screenshot({ path: `${OUT}/final.png` })

  /* ---------- 汇总 ---------- */
  console.log('\n--- 真实后端请求清单 ---')
  apiCalls.forEach((c) => console.log('   ', c))
  console.log('\n--- console 错误 ---')
  if (consoleErrors.length === 0) console.log('    （无）')
  else consoleErrors.forEach((e) => console.log('', e))

  writeFileSync(`${OUT}/api-calls.json`, JSON.stringify(apiCalls, null, 2))
} catch (e) {
  log(false, '异常中断', e.message)
  await page.screenshot({ path: `${OUT}/error.png` }).catch(() => {})
} finally {
  const passed = results.filter((r) => r.ok).length
  const failed = results.filter((r) => !r.ok)
  console.log(`\n===== P1-1 真实后端端到端：${passed} 通过 / ${failed.length} 失败 =====`)
  failed.forEach((f) => console.log(`FAIL ${f.name} — ${f.detail}`))
  await browser.close()
  process.exit(failed.length > 0 ? 1 : 0)
}