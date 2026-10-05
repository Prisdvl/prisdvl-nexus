/**
 * R8 对话页验证。
 *
 * **真实调用 LLM**（本地 Ollama），因此：
 * - 断言围绕"链路是否通"而不是"回答的具体内容"；
 * - 允许回答为空（模型可能只回一句话），但**必须有 done 帧**；
 * - 结束时会话会被删掉。
 */
import { chromium } from 'playwright-core'

const base = process.env.R2_BASE ?? 'http://localhost:5174'
const apiBase = process.env.API_BASE ?? 'http://localhost:8088'
const USERNAME = process.env.DEMO_USER ?? 'demo'
const PASSWORD = process.env.DEMO_PASSWORD ?? 'Demo@12345'

const pass = []
const fail = []
const consoleErrors = []
const pageErrors = []

function check(ok, label, detail = '') {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ' — ' + detail : ''}`)
  ;(ok ? pass : fail).push(label + (detail ? ` (${detail})` : ''))
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const ctx = await browser.newContext({ viewport: { width: 1600, height: 980 } })
const page = await ctx.newPage()
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text())
})
page.on('pageerror', (e) => pageErrors.push(e.message))

let convoId = null

try {
  await page.goto(base + '/#/login', { waitUntil: 'networkidle' })
  await page.fill('[data-testid="login-username"]', USERNAME)
  await page.fill('[data-testid="login-password"]', PASSWORD)
  await page.click('[data-testid="login-submit"]')
  await page.waitForSelector('[data-testid="kb-list-page"]', { timeout: 20000 })

  console.log('① 进入对话页')
  await page.click('[data-testid="workspace-nav-chat"]')
  await page.waitForSelector('[data-testid="chat-page"]', { timeout: 15000 })
  check(true, '导航到对话页')
  check((await page.locator('[data-testid="chat-conversations"]').count()) === 1, '会话列表存在')
  check((await page.locator('[data-testid="chat-input"]').count()) === 1, '输入框存在')

  console.log('② 空态')
  const emptyVisible = (await page.locator('[data-testid="state-empty"]').count()) >= 1
  check(emptyVisible, '没有会话时显示空态引导')

  console.log('③ 发送前先自动建会话')
  // 后端 conversationId 是 @NotNull，所以"没有会话就发"必须先建一个
  await page.fill('[data-testid="chat-input"]', '你好，请简短回一句话。')
  await page.click('[data-testid="chat-send"]')
  // 会话列表里应出现新会话
  await page
    .waitForSelector('[data-testid="chat-conversation-item"]', { timeout: 20000 })
    .catch(() => {})
  const convoCount = await page.locator('[data-testid="chat-conversation-item"]').count()
  check(convoCount >= 1, '自动创建了会话', `${convoCount} 个`)

  const token = await page.evaluate(() => localStorage.getItem('nexus.token'))
  const convos = await fetch(`${apiBase}/api/chat/conversations?pageNum=1&pageSize=50`, {
    headers: { Authorization: 'Bearer ' + token }
  }).then((r) => r.json())
  convoId = convos?.data?.list?.[0]?.id
  check(!!convoId, '后端确实建了会话', String(convoId))

  console.log('④ 流式回答（真实 LLM，可能要几十秒）')
  // 用户消息应立即上屏（乐观插入）
  await page.waitForSelector('[data-testid="chat-message"][data-role="USER"]', { timeout: 30000 }).catch(() => {})
  const userMsg = await page.locator('[data-testid="chat-message"][data-role="USER"]').count()
  check(userMsg >= 1, '用户消息已上屏')

  /*
   * 等**非空文本**出现，而不是等元素出现。
   *
   * `waitForSelector('[data-role="ASSISTANT"]')` 会在**流式占位气泡**出现时就返回
   * （占位在 streaming 一开始就渲染），那时内容还是空的——于是后面的
   * "回答有内容"必然判 0 字。实测：等元素 → 0 字；轮询到有字 → 正常。
   *
   * 所以这里直接等"至少有一条 trim 后非空的助手消息"。
   */
  const gotAnswer = await page
    .waitForFunction(
      () => {
        const els = document.querySelectorAll('[data-testid="chat-message"][data-role="ASSISTANT"]')
        for (const el of els) if ((el.textContent ?? '').trim().length > 0) return true
        return false
      },
      { timeout: 180000 }
    )
    .then(() => true)
    .catch(() => false)
  check(gotAnswer, '助手回答出现（且有内容）')

  if (gotAnswer) {
    /*
     * 取**第一条非空**的助手消息，而不是 `.last()`。
     *
     * `.last()` 会选中流式占位气泡（它排在历史消息之后，而流式结束后
     * `streaming` 为 false 但 `streamingText` 仍非空，占位依然渲染）——
     * 那一行的 text 可能是空的，于是"回答有内容"被判成 0 字。
     * **这是探针的错，不是页面的错。**
     */
    const texts = await page
      .locator('[data-testid="chat-message"][data-role="ASSISTANT"]')
      .allInnerTexts()
    const withText = texts.map((t) => t.trim()).filter((t) => t.length > 0)
    const text = withText[0] ?? ''
    check(text.length > 0, '回答有内容', `${text.length} 字`)
    check(
      withText.length === 0 || !/^（空）$/.test(withText[withText.length - 1]),
      '没有（空）占位',
      withText.length ? '' : '（全部为空）'
    )
    // **delta 是累积替换，不是追加** —— 若实现错了会出现重复文本
    const first20 = text.trim().slice(0, 20)
    const occurrences = text.split(first20).length - 1
    check(occurrences <= 1, '文本没有重复累积（delta 是替换不是追加）', `前 20 字出现 ${occurrences} 次`)
  }

  console.log('⑤ 引用显示')
  const citeCount = await page.locator('[data-testid="chat-citation"]').count()
  const citePanel = await page.locator('[data-testid="chat-citations"]').count()
  // 这个问题可能检索到也可能检索不到，所以两种都算通过，但要说清
  check(
    citeCount > 0 ? citePanel === 1 : true,
    citeCount > 0 ? '有引用时右侧面板出现' : '无引用时不显示引用面板（正常）',
    `${citeCount} 条引用`
  )
  if (citeCount > 0) {
    const firstCite = await page.locator('[data-testid="chat-citation"]').first().innerText()
    check(firstCite.length > 0, '引用卡片有内容', firstCite.replace(/\n/g, ' ').slice(0, 40))
    // L4 信息不该出现
    check(!/score|0\.\d{2,}/.test(firstCite), '引用里没有相似度分数（L4）')
  }

  console.log('⑥ 0 命中提示（任务书 §28）')
  // 问一个知识库里绝对没有的东西，验证不硬答且给提示
  await page.fill('[data-testid="chat-input"]', '请只回答：香蕉和股票的关系是什么？')
  await page.click('[data-testid="chat-send"]')
  await page.waitForTimeout(3000)
  const streaming = await page.locator('[data-testid="chat-stop"]').count()
  check(streaming === 1, '生成中显示「停止」按钮')
  /*
   * 0 命中提示**不依赖 chat 模型**——`retrieval` 帧在调 LLM **之前**就发出来了。
   * 所以这里只等它出现（最多 60s），不等生成结束。
   *
   * 第一版把这一项放在"等助手消息"之后，于是本地没装 chat 模型时
   * 脚本卡在前一步，这项永远测不到——**测试之间的依赖会放大环境问题的遮盖范围**。
   */
  await page
    .waitForSelector('[data-testid="chat-zero-hits"]', { timeout: 60000 })
    .catch(() => {})
  const zeroHits = await page.locator('[data-testid="chat-zero-hits"]').count()
  check(zeroHits === 1, '0 命中时显示"通用回答"提示（不依赖 chat 模型）', `${zeroHits} 条`)

  console.log('⑦ 历史消息加载')
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForTimeout(3000)
  const afterReload = await page.locator('[data-testid="chat-message"]').count()
  check(afterReload >= 2, '刷新后历史消息还在', `${afterReload} 条`)

  console.log('⑧ 删除会话（含危险确认）')
  await page.locator('[data-testid="chat-conversation-item"]').first().hover()
  await page.locator('[data-testid="chat-conversation-delete"]').first().click()
  await page.waitForSelector('[data-testid="confirm-body"]', { timeout: 8000 })
  await page.click('[data-testid="confirm-submit"]')
  await page.waitForTimeout(2000)
  const left = await page.locator('[data-testid="chat-conversation-item"]').count()
  check(left === 0, '会话已删除', `剩 ${left} 个`)

  console.log('⑨ 错误检查')
  const realErrors = consoleErrors.filter((t) => !/401|favicon|AbortError/i.test(t))
  check(realErrors.length === 0, 'console error = 0', realErrors.slice(0, 2).join(' | '))
  check(pageErrors.length === 0, 'page error = 0', pageErrors.slice(0, 2).join(' | '))
} catch (e) {
  fail.push('探针中断：' + e.message)
  console.log('  FAIL  探针中断 — ' + e.message)
} finally {
  // 清理：把本次建的会话删掉
  try {
    const t = await page.evaluate(() => localStorage.getItem('nexus.token'))
    if (t) {
      const r = await fetch(`${apiBase}/api/chat/conversations?pageNum=1&pageSize=100`, {
        headers: { Authorization: 'Bearer ' + t }
      }).then((x) => x.json())
      for (const c of r?.data?.list ?? []) {
        await fetch(`${apiBase}/api/chat/conversations/${c.id}`, {
          method: 'DELETE',
          headers: { Authorization: 'Bearer ' + t }
        })
      }
      console.log('  （已清理本次会话）')
    }
  } catch {
    // 清理失败只报告
  }
  await page.screenshot({ path: 'r8-chat.png' }).catch(() => {})
  await browser.close()
}

console.log(`\n结论：PASS ${pass.length} / FAIL ${fail.length}`)
if (fail.length) {
  console.log('失败项：')
  for (const f of fail) console.log('  - ' + f)
  process.exit(1)
}
console.log('PASS R8 对话页')
