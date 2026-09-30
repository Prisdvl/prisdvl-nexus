/**
 * Agent 对话的界面验证：真实驱动浏览器，确认"过程可见 + 痕迹可回看"。
 *
 * 为什么必须用浏览器验证：这条链路的两次真实缺陷都只在这层暴露——
 *   ① 工具在流式链路上读不到用户 → 模型改用自身知识作答（接口 200、界面无报错、只是"不检索"）；
 *   ② 工具痕迹只存在于内存 → 刷新后消失（用户体验上等于"没检索"）。
 * 因此本脚本断言的是**界面证据**：工具卡片出现、结果摘要非占位、刷新后痕迹仍在。
 */
import { chromium } from 'playwright-core'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const token = readFileSync(resolve(here, '../.demo-token'), 'utf8').trim()
const base = process.env.WEB_BASE ?? 'http://localhost:5173'
const outDir = resolve(here, '../../docs/screenshots')
const question = process.env.AGENT_QUESTION ?? '我的笔记里缓存击穿怎么解决？'

/** 与 AgentView.vue 里的 PENDING 常量一致：界面用它标记"检索中" */
const PENDING = '检索中…'

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 980 } })
await context.addInitScript((value) => {
  localStorage.setItem('nexus.token', value)
  localStorage.setItem('nexus.username', 'demo')
}, token)

const page = await context.newPage()
const failures = []

try {
  await page.goto(base, { waitUntil: 'networkidle' })
  // ① 从侧边栏进 Agent 对话页
  await page.click('.nav-item:has-text("Agent 对话")')
  await page.waitForSelector('.chat', { timeout: 15000 })

  // ② 新建会话后提问（不依赖已有会话，保证可重复运行）
  await page.click('.rail .new-chat')
  await page.waitForSelector('.composer textarea', { timeout: 15000 })
  await page.fill('.composer textarea', question)
  await page.click('.composer .send')

  // ③ 工具卡片必须出现——这是"模型真的检索了知识库"的界面证据。
  //    等待给足：检索本身要几秒，工具事件在回答之前到达。
  await page.waitForSelector('.message.from-agent .trace li', { timeout: 120000 })
  const toolName = (await page.locator('.trace-tool').first().innerText()).trim()
  const toolInput = (await page.locator('.trace-input').first().innerText()).trim()
  console.log(`① 工具卡片出现：${toolName}（输入「${toolInput}」）`)
  if (!toolName.includes('检索')) {
    failures.push(`工具卡片文案异常：${toolName}`)
  }

  // ④ 结果摘要必须从"检索中…"变成真实结果
  await page.waitForFunction(
    (pending) => {
      const node = document.querySelector('.trace-result')
      return node !== null && node.textContent.trim() !== pending
    },
    PENDING,
    { timeout: 180000 }
  )
  const resultSummary = (await page.locator('.trace-result').first().innerText()).trim()
  console.log(`② 检索完成：${resultSummary}`)

  // ⑤ 回答逐字出现，最后渲染成 Markdown（.stream-text 被替换掉）
  await page.waitForSelector('.message.from-agent .markdown:not(.stream-text)', { timeout: 180000 })
  const answer = (await page.locator('.message.from-agent .markdown:not(.stream-text)').last().innerText()).trim()
  console.log(`③ 回答渲染完成，${answer.length} 字`)
  if (answer.length < 10) {
    failures.push('回答内容过少')
  }
  // 兜底判断：模型若因检索失败改用自身知识，会在回答里如实说明"检索不可用"
  if (answer.includes('检索不可用')) {
    failures.push('回答里出现"检索不可用"——这次的检索链路没跑通')
  }

  await page.screenshot({ path: `${outDir}/agent-chat.png` })
  console.log('✓ 已截图 docs/screenshots/agent-chat.png')

  // ⑥ 刷新后痕迹仍在（工具痕迹随消息落库，而不是只活在这次会话的内存里）
  const beforeReload = await page.locator('.message.from-agent .trace li').count()
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForSelector('.message.from-agent .markdown', { timeout: 30000 })
  const afterReload = await page.locator('.message.from-agent .trace li').count()
  console.log(`④ 刷新前痕迹 ${beforeReload} 条，刷新后 ${afterReload} 条`)
  if (afterReload < beforeReload) {
    failures.push('刷新后工具痕迹丢失（未落库）')
  }
  const reloadedSummary = (await page.locator('.trace-result').first().innerText()).trim()
  if (reloadedSummary === PENDING) {
    failures.push('刷新后结果摘要仍是占位文案"检索中…"')
  }
} catch (error) {
  failures.push(error.message)
  console.error('✗ 验证失败：', error.message)
  await page.screenshot({ path: `${outDir}/agent-chat-failure.png` })
} finally {
  await browser.close()
}

if (failures.length > 0) {
  console.error('失败项：' + failures.join('; '))
  process.exit(1)
}
console.log('\nAgent 对话界面验证通过（standalone：检索走关键词降级，无 ES/pgvector）。')
