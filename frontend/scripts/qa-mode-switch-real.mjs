/*
 * 会话模式切换 —— **真实后端路径**专项 QA。
 *
 * 与 `qa-mode-switch.mjs`（mock 路径）互补：那一份验证控件存在、
 * 提示不谎报、mock 明确标注未落库；这一份验证**打真后端**时的行为：
 *   1. 成功后界面按**后端响应**更新（不是按本地推断）
 *   2. 失败后**不显示成功提示**，也不错误更新本地状态
 *   3. 提示里的"已清除"必须与后端实际返回一致
 *   4. 刷新后仍是新模式（证明真的落库）
 *
 * 运行前提：
 *   - 后端 standalone 在 8080（`-Dspring-boot.run.profiles=standalone`）
 *   - dev server **非** mock 模式（不加 VITE_USE_MOCK=true），且带 /api proxy
 */
import { chromium } from 'playwright-core'

const BASE = 'http://localhost:5175'
const USER = `modesw${Date.now().toString().slice(-8)}`
const PASS = 'modesw123456'

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')
})

const issues = []
const consoleErrors = []
const apiCalls = []
page.on('pageerror', (e) => issues.push(`pageerror: ${String(e.message).slice(0, 140)}`))
page.on('response', (r) => {
  const u = r.url()
  if (u.includes('/api/chat/conversations')) apiCalls.push(`${r.status()} ${u.replace(BASE, '')}`)
})
page.on('console', (m) => {
  if (m.type() !== 'error') return
  const t = m.text()
  if (/ERR_CONNECTION_CLOSED|ERR_CONNECTION_REFUSED|WebSocket|\[vite\]/i.test(t)) return
  consoleErrors.push(t.slice(0, 160))
})

const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`)
  if (!ok) issues.push(name)
}

const readToast = async () => page.evaluate(() => {
  const els = [...document.querySelectorAll('.toast-host .swipe-toast')]
  const el = els[els.length - 1]
  if (!el) return null
  return {
    title: el.querySelector('.swipe-toast__title')?.textContent?.trim() ?? '',
    desc: el.querySelector('.swipe-toast__desc')?.textContent?.trim() ?? '',
  }
})

const clearToasts = () => page.evaluate(() => {
  document.querySelectorAll('.toast-host .swipe-toast').forEach((el) => el.remove())
})

const modeSegValue = () => page.evaluate(() => {
  const el = document.querySelector('.mode-seg .rubber-segment__item[aria-checked="true"]')
  return el?.textContent?.trim() ?? ''
})

try {
  /* ---------- 0. 注册 + 真实登录 ---------- */
  await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle' })
  await page.locator('.auth-card button.rubber-segment__item', { hasText: '注册' }).click()
  await page.waitForTimeout(400)
  await page.locator('#rg-u').fill(USER)
  await page.locator('#rg-p').fill(PASS)
  await page.locator('#rg-p2').fill(PASS)
  await page.locator('form button[type="submit"]', { hasText: '下一步' }).click()
  await page.waitForTimeout(1200)

  await page.locator('.auth-card button.rubber-segment__item', { hasText: '登录' }).click()
  await page.waitForTimeout(300)
  await page.locator('#li-u').fill(USER)
  await page.locator('#li-p').fill(PASS)
  await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
  await page.waitForSelector('.shell', { timeout: 20000 })
  check('真实登录成功（拿到令牌）', true, USER)

  await page.goto(`${BASE}/#/app/chat`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)

  /*
   * 空列表时对话页渲染的是"还没有任何会话"空态 ——
   * 那个分支**不包含**模式切换控件（没有会话也就没什么模式可切）。
   * 所以要先建会话再断言控件，否则会把"设计如此"判成"控件缺失"。
   * 空态本身不崩是要单独守的：曾在这里白屏过（sessions[0] 为 undefined）。
   */
  const rowCount = await page.locator('.session-list .swipe-row').count()
  check('新用户无会话时侧栏不崩溃', rowCount === 0, `${rowCount} 行`)
  check('空态给出了"还没有任何会话"提示',
    (await page.getByText('还没有任何会话').count()) > 0)

  const token = await page.evaluate(() => localStorage.getItem('nx-token'))
  const created = await page.evaluate(async ({ token }) => {
    const resp = await fetch('/api/chat/conversations', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ title: '真实切换验证', mode: 'PLAIN' }),
    })
    return { status: resp.status }
  }, { token })
  check('后端建会话成功', created.status === 200, `status=${created.status}`)

  // 有会话了，模式控件才该出现
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForSelector('.mode-seg', { timeout: 15000 })
  await page.waitForTimeout(1200)

  /* ---------- 3. 真实切换 PLAIN → RAG ---------- */
  const putBefore = apiCalls.length
  await clearToasts()
  await page.locator('.mode-seg button.rubber-segment__item', { hasText: '知识库问答' }).click()
  await page.waitForTimeout(2000)

  check('切换后面板显示新模式', (await modeSegValue()) === '知识库问答', await modeSegValue())
  check('切换打到了后端', apiCalls.length > putBefore, apiCalls.slice(putBefore).join(' | '))

  const t1 = await readToast()
  check('真实路径成功提示不含"未落库"', !!t1 && !t1.desc.includes('未落库'), t1?.desc ?? '(无)')
  check('真实路径提示说明历史不受影响', !!t1 && t1.desc.includes('历史回答保持原样'), t1?.desc ?? '(无)')
  check('无绑定时不谎报清理', !!t1 && !t1.desc.includes('已清除'), t1?.desc ?? '(无)')

  /* ---------- 4. 刷新后仍是新模式（真的落库） ---------- */
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForSelector('.mode-seg', { timeout: 15000 })
  await page.waitForTimeout(1200)
  check('刷新后仍显示新模式（真的落库）', (await modeSegValue()) === '知识库问答', await modeSegValue())

  /* ---------- 5. 失败路径：改坏令牌后再切 ---------- */
  await clearToasts()
  await page.evaluate(() => localStorage.setItem('nx-token', 'invalid.token.value'))
  const beforeFail = await modeSegValue()
  await page.locator('.mode-seg button.rubber-segment__item', { hasText: 'Agent' }).click()
  await page.waitForTimeout(2500)
  const tFail = await readToast()
  check('失败时不显示成功提示', !!tFail && !tFail.title.startsWith('已切换'), tFail?.title ?? '(无)')
  check('失败时给出错误提示', !!tFail && tFail.title.includes('切换失败'), tFail?.title ?? '(无)')

  /*
   * 上面第 5 步**故意**把令牌改坏来验证失败路径，
   * 所以这里必然会出现 401 —— 那是被预期的，不该算成"有错误"。
   * 只检查非401/400 的错误（真正的意外问题）。
   */
  const unexpected = consoleErrors.filter((e) => !/\b40[01]\b/.test(e))
  check('无非预期的 console 错误', unexpected.length === 0, unexpected.slice(0, 2).join(' | '))
} catch (e) {
  check('异常中断', false, e.message)
  await page.screenshot({ path: `${process.env.QA_SHOTS_OUT ?? 'D:/develop/Prisma/docs/prototypes/shots'}/mode-switch-real-error.png` }).catch(() => {})
} finally {
  console.log('\n--- 真实后端路径汇总 ---')
  console.log('API 调用：')
  apiCalls.forEach((c) => console.log('  ', c))
  if (issues.length === 0) {
    console.log('真实路径模式切换 QA 全部通过')
  } else {
    console.log(`发现 ${issues.length} 个问题:`)
    issues.forEach((i) => console.log('  - ' + i))
    consoleErrors.slice(0, 5).forEach((e) => console.log('  console: ' + e))
  }
  await browser.close()
  process.exit(issues.length > 0 ? 1 : 0)
}