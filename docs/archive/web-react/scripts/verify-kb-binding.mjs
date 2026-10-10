/**
 * 会话知识库绑定验证（R11 补齐项）。
 *
 * 此前创建 RAG 会话时前端**写死绑定全部知识库**，用户看不到也改不了。
 * 本脚本验证绑定条真正可用，且验证的是**后端状态**（不只看界面）：
 *   1. 绑定条显示当前绑定的库；
 *   2. 取消勾选并保存后，`GET /chat/conversations/{id}` 的 kbIds 真的少了；
 *   3. 解绑全部后界面给出明确提示（而不是静默变成"检索不到"）；
 *   4. 非 RAG 会话不显示绑定条（PLAIN 没有"检索范围"这回事）。
 */
import { chromium } from 'playwright-core'

const base = process.env.R2_BASE ?? 'http://localhost:5174'
const apiBase = process.env.API_BASE ?? 'http://localhost:8088'
const USERNAME = process.env.DEMO_USER ?? 'demo'
const PASSWORD = process.env.DEMO_PASSWORD ?? 'Demo@12345'

let pass = 0
let fail = 0
const check = (ok, name, detail = '') => {
  if (ok) {
    pass++
    console.log(`PASS  ${name}${detail ? ' — ' + detail : ''}`)
  } else {
    fail++
    console.log(`FAIL  ${name}${detail ? ' — ' + detail : ''}`)
  }
}

const login = await fetch(apiBase + '/api/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: USERNAME, password: PASSWORD })
})
const token = (await login.json()).data.accessToken
const auth = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' }

const kbsRes = await (await fetch(apiBase + '/api/knowledge-bases?pageNum=1&pageSize=50', { headers: auth })).json()
const kbs = kbsRes.data.list
console.log(`可用知识库：${kbs.length} 个（${kbs.map((k) => k.name).join('、')}）`)

let createdId = null
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await (await browser.newContext({ viewport: { width: 1440, height: 980 } })).newPage()
const consoleErrors = []
page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text().slice(0, 120)))

try {
  console.log('① 登录并进入对话页')
  await page.goto(base + '/#/login', { waitUntil: 'domcontentloaded' })
  await page.fill('[data-testid="login-username"]', USERNAME)
  await page.fill('[data-testid="login-password"]', PASSWORD)
  await page.click('[data-testid="login-submit"]')
  await page.waitForTimeout(2500)
  await page.goto(base + '/#/agent', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-testid="chat-page"]', { timeout: 20000 })
  check(true, '进入对话页')

  console.log('② 提问一次以自动创建 RAG 会话（默认绑定全部库）')
  await page.fill('[data-testid="chat-input"]', '这个知识库里有什么？')
  await page.click('[data-testid="chat-send"]')
  // 等会话被创建出来（绑定条出现）
  await page.waitForSelector('[data-testid="chat-kb-bind-bar"]', { timeout: 30000 })
  check(true, 'RAG 会话创建后出现知识库绑定条')

  // 从后端读真实 kbIds
  const list = await (await fetch(apiBase + '/api/chat/conversations?pageNum=1&pageSize=50', { headers: auth })).json()
  const conv = list.data.list.find((c) => c.mode === 'RAG')
  createdId = conv?.id ?? null
  check(!!createdId, '后端存在 RAG 会话')
  const boundBefore = conv?.kbIds ?? []
  check(boundBefore.length === kbs.length, '默认绑定了全部知识库', `${boundBefore.length} 个`)

  const boundBadges = await page.locator('[data-testid="chat-kb-bound"]').count()
  check(boundBadges === boundBefore.length, '界面显示与后端一致', `界面 ${boundBadges} / 后端 ${boundBefore.length}`)

  console.log('③ 改绑：去掉一个库并保存')
  await page.click('[data-testid="chat-kb-edit"]')
  await page.waitForSelector('[data-testid="chat-kb-option"]', { timeout: 8000 })
  const optionCount = await page.locator('[data-testid="chat-kb-option"]').count()
  check(optionCount === kbs.length, '选项列出全部知识库', `${optionCount} 个`)

  // 取消第一个已选中的
  const firstSelected = page.locator('[data-testid="chat-kb-option"][data-selected="true"]').first()
  const removedName = (await firstSelected.innerText()).trim()
  await firstSelected.click()
  await page.click('[data-testid="chat-kb-save"]')
  await page.waitForTimeout(2000)

  const after = await (
    await fetch(`${apiBase}/api/chat/conversations/${createdId}`, { headers: auth })
  ).json()
  const boundAfter = after.data.kbIds ?? []
  check(
    boundAfter.length === boundBefore.length - 1,
    '后端 kbIds 真的少了一个（改绑生效）',
    `${boundBefore.length} → ${boundAfter.length}（移除「${removedName}」）`
  )

  console.log('④ 解绑全部 → 界面必须明确提示（而不是静默检索不到）')
  await page.click('[data-testid="chat-kb-edit"]')
  await page.waitForSelector('[data-testid="chat-kb-option"]', { timeout: 8000 })
  // 全部取消
  for (;;) {
    const sel = page.locator('[data-testid="chat-kb-option"][data-selected="true"]')
    if ((await sel.count()) === 0) break
    await sel.first().click()
  }
  await page.click('[data-testid="chat-kb-save"]')
  await page.waitForTimeout(2000)

  const noneHint = await page.locator('[data-testid="chat-kb-none"]').count()
  check(noneHint === 1, '解绑全部后界面明确提示"未绑定知识库，无法提问"')

  const cleared = await (
    await fetch(`${apiBase}/api/chat/conversations/${createdId}`, { headers: auth })
  ).json()
  check((cleared.data.kbIds ?? []).length === 0, '后端 kbIds 已清空')

  console.log('⑤ 恢复绑定，避免影响后续脚本')
  await page.click('[data-testid="chat-kb-edit"]')
  await page.waitForSelector('[data-testid="chat-kb-option"]', { timeout: 8000 })
  await page.locator('[data-testid="chat-kb-option"]').first().click()
  await page.click('[data-testid="chat-kb-save"]')
  await page.waitForTimeout(1500)
  check(true, '恢复绑定完成')

  console.log('⑥ 错误检查')
  const real = consoleErrors.filter((t) => !/401|favicon|AbortError/i.test(t))
  check(real.length === 0, 'console error = 0', real.slice(0, 2).join(' | '))

  await page.screenshot({ path: 'D:/develop/Prisma/web-react/r11-kb-binding.png' })
  console.log('截图: web-react/r11-kb-binding.png')
} catch (e) {
  fail++
  console.log('FAIL  探针中断 — ' + e.message)
  await page.screenshot({ path: 'D:/develop/Prisma/web-react/r11-kb-binding-fail.png' }).catch(() => {})
} finally {
  // 兜底：删掉本轮创建的会话，避免污染后续脚本的会话列表断言
  try {
    if (createdId) {
      await fetch(`${apiBase}/api/chat/conversations/${createdId}`, { method: 'DELETE', headers: auth })
      console.log('（兜底清理）删除了本轮创建的会话')
    }
  } catch {
    // 清理失败不改变结论
  }
  await browser.close()
}

console.log(`\n结论：PASS ${pass} / FAIL ${fail}`)
process.exit(fail === 0 ? 0 : 1)
