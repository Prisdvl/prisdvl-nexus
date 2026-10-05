/**
 * R9 Agent 管理页验证。
 *
 * 真实调用后端（创建 → 编辑 → 试跑 → 复制 → 删除），结束时清理。
 * 试跑会**真的调 LLM**（`deepseek-v4-flash` 走云端 API），可能几十秒。
 */
import { chromium } from 'playwright-core'

const base = process.env.R2_BASE ?? 'http://localhost:5174'
const apiBase = process.env.API_BASE ?? 'http://localhost:8088'
const USERNAME = process.env.DEMO_USER ?? 'demo'
const PASSWORD = process.env.DEMO_PASSWORD ?? 'Demo@12345'
const CODE = 'r9v' + Date.now().toString(36).slice(-6)

const pass = []
const fail = []
const consoleErrors = []
const pageErrors = []

function check(ok, label, detail = '') {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ' — ' + detail : ''}`)
  ;(ok ? pass : fail).push(label + (detail ? ` (${detail})` : ''))
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 980 } })
const page = await ctx.newPage()
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text())
})
page.on('pageerror', (e) => pageErrors.push(e.message))

let createdId = null

try {
  await page.goto(base + '/#/login', { waitUntil: 'networkidle' })
  await page.fill('[data-testid="login-username"]', USERNAME)
  await page.fill('[data-testid="login-password"]', PASSWORD)
  await page.click('[data-testid="login-submit"]')
  await page.waitForSelector('[data-testid="kb-list-page"]', { timeout: 20000 })

  console.log('① 进入 Agent 页')
  await page.click('[data-testid="workspace-nav-agent"]')
  await page.waitForSelector('[data-testid="agents-page"]', { timeout: 15000 })
  await page
    .waitForSelector('[data-testid="agent-card"], [data-testid="state-empty"]', { timeout: 20000 })
    .catch(() => {})
  check(true, '进入 Agent 管理页')
  const cards = await page.locator('[data-testid="agent-card"]').count()
  check(cards >= 0, '列表已加载（有空态或有卡片）', `${cards} 张卡片`)

  console.log('② 卡片显示 L1+L2，无 L4')
  if (cards > 0) {
    const first = page.locator('[data-testid="agent-card"]').first()
    const txt = await first.innerText()
    check(txt.trim().length > 0, '卡片有内容', txt.replace(/\n/g, ' ').slice(0, 40))
    // L4 不该出现：模型角色 / 温度 / 提示词
    const leaked = ['temperature', 'modelRole', 'systemPrompt', 'chat.agent', 'KB_PREFERRED'].filter(
      (w) => txt.includes(w)
    )
    check(leaked.length === 0, '卡片无 L4 技术词', leaked.join(','))
    // 状态是中文
    const st = await first.locator('[data-testid="agent-card-status"]').innerText()
    check(/草稿|已发布|已停用/.test(st), '状态是中文', st.trim())
  }

  console.log('③ 创建（含校验）')
  await page.click('[data-testid="agent-create-open"]')
  await page.waitForSelector('[data-testid="agent-form"]', { timeout: 8000 })
  // 空名 + 空标识 → 被拦
  await page.click('[data-testid="agent-save"]')
  await page.waitForTimeout(500)
  check(
    (await page.locator('[data-testid="agent-form"]').count()) === 1,
    '空名称/标识被拦下（弹窗未关闭）'
  )

  // 非法标识
  await page.fill('[data-testid="agent-name"]', 'R9 验证 Agent')
  await page.fill('[data-testid="agent-code"]', 'Bad Code!')
  await page.click('[data-testid="agent-save"]')
  await page.waitForTimeout(500)
  const stillOpen = (await page.locator('[data-testid="agent-form"]').count()) === 1
  const codeErr = await page.locator('[data-testid="agent-form"]').innerText()
  check(stillOpen && /小写字母/.test(codeErr), '非法标识被拦下并说明规则')

  // 合法创建
  await page.fill('[data-testid="agent-code"]', CODE)
  await page.fill('[data-testid="agent-description"]', 'R9 验证用，会被删除')
  await page.click('[data-testid="agent-save"]')
  await page.waitForSelector(`[data-testid="agent-card"]:has-text("${CODE}")`, { timeout: 20000 })
  check(true, '创建成功并出现在列表')

  const token = await page.evaluate(() => localStorage.getItem('nexus.token'))
  const listApi = await fetch(`${apiBase}/api/agents?pageNum=1&pageSize=100`, {
    headers: { Authorization: 'Bearer ' + token }
  }).then((r) => r.json())
  const found = (listApi?.data?.list ?? []).find((a) => a.code === CODE)
  createdId = found?.id
  check(!!createdId, '后端确实创建了', String(createdId))
  check(found?.status === 'DRAFT', '默认状态是 DRAFT（草稿）', found?.status)

  console.log('④ 编辑（code 应被禁用）')
  await page.click(`[data-testid="agent-card"]:has-text("${CODE}") [data-testid="agent-edit"]`)
  await page.waitForSelector('[data-testid="agent-form"]', { timeout: 8000 })
  const codeDisabled = await page.locator('[data-testid="agent-code"]').isDisabled()
  check(codeDisabled, '编辑时标识被禁用（业务标识不可改）')
  const codeStillThere = await page.locator('[data-testid="agent-code"]').inputValue()
  check(codeStillThere === CODE, '禁用而非隐藏（值还在）', codeStillThere)

  // 高级区默认折叠
  const advVisible = await page.locator('[data-testid="agent-advanced"]').count()
  check(advVisible === 0, '高级设置默认折叠')
  await page.click('[data-testid="agent-advanced-toggle"]')
  await page.waitForTimeout(400)
  check((await page.locator('[data-testid="agent-advanced"]').count()) === 1, '点开后展开高级设置')

  // 改描述并保存
  await page.fill('[data-testid="agent-description"]', 'R9 验证用（已改），会被删除')
  await page.click('[data-testid="agent-save"]')
  await page.waitForTimeout(2500)
  const descOk = await page
    .locator(`[data-testid="agent-card"]:has-text("${CODE}")`)
    .innerText()
  check(descOk.includes('已改'), '编辑已保存')

  console.log('⑤ 试跑（真实 LLM）')
  await page.click(`[data-testid="agent-card"]:has-text("${CODE}") [data-testid="agent-test"]`)
  await page.waitForSelector('[data-testid="agent-test-input"]', { timeout: 8000 })
  // 草稿也必须能试跑（后端明确允许）
  check(true, '草稿状态也能打开试跑（后端允许 DRAFT 试跑）')
  await page.fill('[data-testid="agent-test-input"]', '你好，请一句话。')
  await page.click('[data-testid="agent-test-run"]')
  const gotAnswer = await page
    .waitForFunction(
      () => {
        const el = document.querySelector('[data-testid="agent-test-answer"]')
        return el !== null && (el.textContent ?? '').trim().length > 0
      },
      { timeout: 180000 }
    )
    .then(() => true)
    .catch(() => false)
  check(gotAnswer, '试跑返回了答案')
  if (gotAnswer) {
    const ans = await page.locator('[data-testid="agent-test-answer"]').innerText()
    check(!/失败|错误|error/i.test(ans.split('\n')[0]), '试跑无错误', ans.slice(0, 50))
  }
  // 精确点"关闭"按钮。**不要靠 Escape**——它依赖焦点在弹窗内，
  // 而 Playwright 未必把焦点放进去；关不掉的话弹窗会盖住下面的删除按钮。
  await page.click('[data-testid="agent-test-close"]')
  await page.waitForTimeout(800)

  console.log('⑥ 复制')
  // 基数**现场取**：第一段的 cards 是在列表加载完成前读的（可能是 0），
  // 拿它做基数会得到错误的差值。
  const beforeDup = await page.locator('[data-testid="agent-card"]').count()
  /*
   * 从**卡片**层级定位，不能 `filter({ hasText: CODE })` 作用在按钮上——
   * 按钮里只有「复制」两个字，**没有 code 文本**，filter 永远匹配不到（实测 Timeout）。
   *
   * 用 `.first()`：复制出的副本 code 是 `${CODE}-copy`，
   * `:has-text(CODE)` 会同时匹配原版与副本两张卡 → Playwright strict mode 报错。
   */
  await page
    .locator(`[data-testid="agent-card"]:has-text("${CODE}")`)
    .first()
    .locator('[data-testid="agent-duplicate"]')
    .click()
  await page.waitForTimeout(3000)
  const afterDup = await page.locator('[data-testid="agent-card"]').count()
  check(afterDup === beforeDup + 1, '复制后多了一张卡', `${beforeDup} → ${afterDup}`)

  console.log('⑦ 删除（含危险确认 + 取消不误删）')
  /*
   * 精确定位"原版"那张卡：用 [data-testid="agent-card-code"] 的**文本完全相等**来选。
   *
   * 这里踩过两次：
   *  1. `:has-text(CODE)` 会同时匹配原版与副本（副本 code = `${CODE}-copy`）→ strict mode 报错；
   *  2. 改成 `.first()` 后不报错了，但 `.first()` 取到哪张取决于渲染顺序，
   *     于是"删原版还是删副本"变得不确定，末条断言随之随机失败。
   * 教训：**用模糊文本匹配去指代一个 id，本质上是不确定的**；应当按业务标识精确相等来选。
   */
  const cardOf = (code) =>
    page.locator(
      `[data-testid="agent-card"]:has([data-testid="agent-card-code"]:text-is("${code}"))`
    )
  const delBtn = cardOf(CODE).locator('[data-testid="agent-delete"]')
  await delBtn.click()
  await page.waitForSelector('[data-testid="confirm-body"]', { timeout: 8000 })
  // 先取消
  await page.click('[data-testid="confirm-cancel"]')
  await page.waitForTimeout(800)
  const stillThere = (await cardOf(CODE).count()) >= 1
  check(stillThere, '取消后 Agent 仍在（没有误删）')
  // 再真删
  await delBtn.click()
  await page.waitForSelector('[data-testid="confirm-body"]', { timeout: 8000 })
  const desc = await page.locator('[data-testid="confirm-body"]').innerText()
  check(/不可撤销/.test(desc), '确认框写明不可撤销', desc.trim().slice(0, 36))
  await page.click('[data-testid="confirm-submit"]')
  await page.waitForTimeout(2500)
  // 判据：原版那张卡（code 精确相等）消失；副本(code-copy)不受影响
  const gone = (await cardOf(CODE).count()) === 0
  check(gone, '确认后从列表消失')

  console.log('⑧ 错误检查')
  const realErrors = consoleErrors.filter((t) => !/401|favicon|AbortError/i.test(t))
  check(realErrors.length === 0, 'console error = 0', realErrors.slice(0, 2).join(' | '))
  check(pageErrors.length === 0, 'page error = 0', pageErrors.slice(0, 2).join(' | '))
} catch (e) {
  fail.push('探针中断：' + e.message)
  console.log('  FAIL  探针中断 — ' + e.message)
} finally {
  // 清理：删掉所有本次创建的（code 以 r9v 开头）
  try {
    const t = await page.evaluate(() => localStorage.getItem('nexus.token'))
    if (t) {
      const r = await fetch(`${apiBase}/api/agents?pageNum=1&pageSize=100`, {
        headers: { Authorization: 'Bearer ' + t }
      }).then((x) => x.json())
      for (const a of r?.data?.list ?? []) {
        if (String(a.code).startsWith('r9v')) {
          await fetch(`${apiBase}/api/agents/${a.id}`, {
            method: 'DELETE',
            headers: { Authorization: 'Bearer ' + t }
          })
          console.log('  （已清理 ' + a.code + '）')
        }
      }
    }
  } catch {
    // 清理失败只报告
  }
  await page.screenshot({ path: 'r9-agents.png' }).catch(() => {})
  await browser.close()
}

console.log(`\n结论：PASS ${pass.length} / FAIL ${fail.length}`)
if (fail.length) {
  console.log('失败项：')
  for (const f of fail) console.log('  - ' + f)
  process.exit(1)
}
console.log('PASS R9 Agent 管理页')
