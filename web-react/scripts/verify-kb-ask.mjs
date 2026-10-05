/**
 * 知识库问答页签验证（R11 补齐项）。
 *
 * 这一屏此前是 `Placeholder text="…R8 实现"`，所以验证重点不是"能打开"，而是：
 *   1. 流式真的在流（文本随时间增长，不是一次性蹦出来）；
 *   2. 回答**基于知识库**（不是模型的通用知识）；
 *   3. 引用面板出现，且**区分"被引用 / 未被引用"**——这是"可核对"的核心；
 *   4. 检索事实如实展示（命中数、是否重排降级）；
 *   5. 问答留档能读回。
 */
import { chromium } from 'playwright-core'

const base = process.env.R2_BASE ?? 'http://localhost:5174'
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

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await (await browser.newContext({ viewport: { width: 1440, height: 980 } })).newPage()
const consoleErrors = []
page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text().slice(0, 120)))
page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message.slice(0, 120)))

try {
  console.log('① 登录并进入第一个知识库')
  await page.goto(base + '/#/login', { waitUntil: 'domcontentloaded' })
  await page.fill('[data-testid="login-username"]', USERNAME)
  await page.fill('[data-testid="login-password"]', PASSWORD)
  await page.click('[data-testid="login-submit"]')
  await page.waitForSelector('[data-testid="kb-card"]', { timeout: 20000 })
  await page.locator('[data-testid="kb-card"]').first().click()
  await page.waitForSelector('[data-testid="kb-detail-page"]', { timeout: 15000 })
  check(true, '进入知识库详情')

  console.log('② 切换到「问答」页签')
  await page.click('text=问答')
  await page.waitForSelector('[data-testid="kb-ask-input"]', { timeout: 10000 })
  check(true, '问答页签渲染出输入框（不再是占位文案）')

  console.log('③ 提问并观察流式增长')
  await page.fill('[data-testid="kb-ask-input"]', '缓存击穿怎么解决？')
  await page.click('[data-testid="kb-ask-submit"]')

  // 等第一批文本
  await page
    .waitForFunction(
      () => (document.querySelector('[data-testid="kb-ask-answer"]')?.textContent ?? '').trim().length > 0,
      { timeout: 90000 }
    )
    .catch(() => {})
  const first = (await page.locator('[data-testid="kb-ask-answer"]').innerText().catch(() => '')).length
  check(first > 0, '收到第一批文本', `${first} 字`)

  // 采样两次看是否增长（短回答可能已结束，允许"已完成"）
  await page.waitForTimeout(2500)
  const secondLen = (await page.locator('[data-testid="kb-ask-answer"]').innerText().catch(() => '')).length
  const stillStreaming = await page.locator('[data-testid="kb-ask-stop"]').count()
  check(
    secondLen > first || stillStreaming === 0,
    '流式行为可解释（文本增长，或本轮已生成完）',
    `${first} → ${secondLen}`
  )

  console.log('④ 等生成结束，检查检索事实与引用')
  await page.waitForSelector('[data-testid="kb-ask-stop"]', { state: 'detached', timeout: 120000 }).catch(() => {})
  await page.waitForTimeout(1200)

  const retrievalText = await page.locator('[data-testid="kb-ask-retrieval"]').innerText().catch(() => '')
  check(/检索到\s*\d+\s*个片段/.test(retrievalText), '检索事实如实展示', retrievalText.trim())

  const rerankedText = await page.locator('[data-testid="kb-ask-reranked"]').innerText().catch(() => '')
  check(
    /已重排|未重排/.test(rerankedText),
    '重排状态如实展示（降级时不能假装重排过）',
    rerankedText.trim()
  )

  const answer = await page.locator('[data-testid="kb-ask-answer"]').innerText()
  // 检查是否基于知识库：demo 库里那篇笔记是 SETNX 互斥锁方案
  check(
    /SETNX|互斥锁|逻辑过期|缓存/i.test(answer),
    '回答内容与本知识库资料相关（不是通用闲聊）',
    answer.replace(/\s+/g, ' ').slice(0, 60)
  )

  const citations = await page.locator('[data-testid="kb-ask-citation"]').count()
  check(citations > 0, '引用列表出现', `${citations} 条`)
  if (citations > 0) {
    const referenced = await page.locator('[data-testid="kb-ask-citation"][data-referenced="true"]').count()
    const unreferenced = await page.locator('[data-testid="kb-ask-citation"][data-referenced="false"]').count()
    check(
      referenced + unreferenced === citations,
      '每条引用都标明是否被回答真正引用（可核对）',
      `被引用 ${referenced} / 未引用 ${unreferenced}`
    )
  }

  console.log('⑤ 问答留档')
  const history = await page.locator('[data-testid="kb-ask-history-item"]').count()
  check(history > 0, '问答留档能读回', `${history} 条`)

  console.log('⑥ 错误检查')
  const real = consoleErrors.filter((t) => !/401|favicon|AbortError/i.test(t))
  check(real.length === 0, 'console error = 0', real.slice(0, 2).join(' | '))

  await page.screenshot({ path: 'D:/develop/Prisma/web-react/r11-kb-ask.png' })
  console.log('截图: web-react/r11-kb-ask.png')
} catch (e) {
  fail++
  console.log('FAIL  探针中断 — ' + e.message)
  await page.screenshot({ path: 'D:/develop/Prisma/web-react/r11-kb-ask-fail.png' }).catch(() => {})
} finally {
  await browser.close()
}

console.log(`\n结论：PASS ${pass} / FAIL ${fail}`)
process.exit(fail === 0 ? 0 : 1)
