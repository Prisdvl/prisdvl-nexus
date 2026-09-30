/**
 * 对话页的界面验证：真实驱动浏览器，确认"过程可见 + 痕迹可回看"（两种模式各跑一遍）。
 *
 * 为什么必须用浏览器验证：这条链路的两次真实缺陷都只在这层暴露——
 *   ① 工具在流式链路上读不到用户 → 模型改用自身知识作答（接口 200、界面无报错、只是"不检索"）；
 *   ② 工具痕迹只存在于内存 → 刷新后消失（用户体验上等于"没检索"）。
 * 因此本脚本断言的是**界面证据**：工具卡片出现、结果摘要非占位、刷新后痕迹仍在。
 *
 * Phase 3.4 增补第二条腿（mode=RAG）：界面必须出现"检索到 N 个片段"，且排在回答之前。
 * 理由同上——后端发了 retrieval 事件、接口测试全绿，但前端没渲染的话，
 * 用户看到的仍然是"对着空白等十几秒"，而这层只有浏览器能证明。
 *
 * 退出码：0 = 全部通过；1 = 有真实失败；2 = 有跳过（环境缺少检索能力，见下）。
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
const ragQuestion = process.env.RAG_QUESTION ?? '缓存击穿怎么解决？'

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
const skipped = []

/**
 * standalone（无 ES/pgvector）下 RAG 会话在检索这一步会返回 4005"检索索引未启用"。
 * 那是**环境能力缺失**而不是代码回归，所以 RAG 腿要报"跳过"。
 *
 * 判定取后端响应体而不是界面提示：ElMessage 默认 3 秒就消失，
 * 等断言超时（几十秒）后再去读 DOM 什么也读不到，会把环境问题误报成产品缺陷。
 */
let backendRefusal = ''
page.on('response', async (response) => {
  const url = response.url()
  if (response.status() === 200) return
  if (!url.includes('/api/chat/stream') && !url.includes('/ask/stream')) return
  try {
    const text = await response.text()
    if (text.includes('4005')) {
      backendRefusal = text.split(/\s+/).join(' ').slice(0, 200)
    }
  } catch {
    // 读不到响应体就保持为空：宁可报失败，也不要凭空断言"环境不行"
  }
})

/** RAG 腿（mode=RAG）："检索到 N 个片段"必须出现，且排在回答之前。 */
async function verifyRagLeg() {
  await page.click('.rail .new-chat')
  await page.waitForFunction(
    () => document.querySelectorAll('.message').length === 0,
    undefined,
    { timeout: 10000 }
  )
  await page.waitForSelector('.composer-options', { timeout: 15000 })
  await page.click('.composer-options .el-radio-button:has-text("知识库问答")')
  // 知识库下拉默认选中第一个库（AgentView.loadKnowledgeBases），没库说明 seed-demo 没跑
  await page.waitForSelector('.composer-options .kb-select', { timeout: 15000 })
  // el-select 的选中项画在 .el-select__selected-item 里，内部 input 是搜索框（恒为空），
  // 所以这里读整个下拉的可见文本；等于 placeholder 就说明一个库都没带上
  const kbPicked = (await page.locator('.composer-options .kb-select').innerText()).trim()
  console.log(`② RAG 会话绑定知识库：${kbPicked || '(空)'}`)
  if (!kbPicked || kbPicked === '选择知识库') {
    failures.push('RAG 模式没有选中任何知识库（演示数据缺失？）')
  }

  await page.fill('.composer textarea', ragQuestion)
  await page.click('.composer .send')

  // 检索卡片是"后端发了 retrieval 事件、前端也渲染了"的界面证据
  await page.waitForSelector('.message.from-agent .retrieval-count', { timeout: 180000 })
  const retrievalText = (await page.locator('.retrieval-count').last().innerText()).trim()
  console.log(`③ 检索卡片：${retrievalText}`)
  if (!/检索到 [0-9]+ 个片段/.test(retrievalText)) {
    failures.push(`检索卡片文案异常：${retrievalText}`)
  }

  // 顺序断言：卡片排在回答正文之前（即 retrieval 事件早于 delta）。
  // 只断言"卡片存在"不够——晚一拍出现就是"回答已经蹦字，检索条数才冒出来"
  const orderedBeforeAnswer = await page.evaluate(() => {
    const bubbles = Array.from(document.querySelectorAll('.message.from-agent .bubble'))
    const last = bubbles[bubbles.length - 1]
    if (!last) return false
    const card = last.querySelector('.retrieval')
    const answer = last.querySelector('.markdown')
    if (!card || !answer) return false
    return (card.compareDocumentPosition(answer) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0
  })
  if (!orderedBeforeAnswer) {
    failures.push('检索卡片没有排在回答之前（retrieval 事件晚于 delta？）')
  }

  await page.waitForSelector('.message.from-agent .markdown:not(.stream-text)', { timeout: 180000 })
  const ragAnswer = (await page.locator('.message.from-agent .markdown:not(.stream-text)').last().innerText()).trim()
  console.log(`④ RAG 回答渲染完成，${ragAnswer.length} 字`)
  if (ragAnswer.length < 5) {
    failures.push('RAG 回答内容过少')
  }
  await page.screenshot({ path: `${outDir}/agent-chat-rag.png` })
  console.log('✓ 已截图 docs/screenshots/agent-chat-rag.png')
}

try {
  await page.goto(base, { waitUntil: 'networkidle' })
  // ① 从侧边栏进对话页
  await page.click('.nav-item:has-text("对话")')
  await page.waitForSelector('.chat', { timeout: 15000 })

  // ② 新建会话后提问（不依赖已有会话，保证可重复运行）。
  //    默认模式必须是 Agent：默认值若被改成 RAG，这条腿就在悄悄验证另一条链路了
  await page.click('.rail .new-chat')
  await page.waitForSelector('.composer textarea', { timeout: 15000 })
  // 新对话必须是空白的。这一条不是形式主义：页面挂载时"自动选中最近会话"要跨两个 RPC，
  // 若它回来后无条件覆盖，就会把用户（或本脚本）刚点的"新对话"抢回去——
  // 屏幕上留着上一条会话的消息，后面的断言全被旧 DOM 满足，跑出一个假的通过。
  await page.waitForFunction(
    () => document.querySelectorAll('.message').length === 0,
    undefined,
    { timeout: 10000 }
  )
  const defaultMode = (await page.locator('.composer-options .is-active').first().innerText()).trim()
  console.log(`① 新建会话的默认模式：${defaultMode}`)
  if (!defaultMode.startsWith('Agent')) {
    failures.push(`新建会话的默认模式不是 Agent：${defaultMode}`)
  }
  await page.fill('.composer textarea', question)
  await page.click('.composer .send')

  // ③ 工具卡片必须出现——这是"模型真的检索了知识库"的界面证据。
  //    等待给足：检索本身要几秒，工具事件在回答之前到达。
  await page.waitForSelector('.message.from-agent .trace li', { timeout: 120000 })
  const toolName = (await page.locator('.trace-tool').first().innerText()).trim()
  const toolInput = (await page.locator('.trace-input').first().innerText()).trim()
  console.log(`② 工具卡片出现：${toolName}（输入「${toolInput}」）`)
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
  console.log(`③ 检索完成：${resultSummary}`)

  // ⑤ 回答逐字出现，最后渲染成 Markdown（.stream-text 被替换掉）
  await page.waitForSelector('.message.from-agent .markdown:not(.stream-text)', { timeout: 180000 })
  const answer = (await page.locator('.message.from-agent .markdown:not(.stream-text)').last().innerText()).trim()
  console.log(`④ 回答渲染完成，${answer.length} 字`)
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
  console.log(`⑤ 刷新前痕迹 ${beforeReload} 条，刷新后 ${afterReload} 条`)
  if (afterReload < beforeReload) {
    failures.push('刷新后工具痕迹丢失（未落库）')
  }
  const reloadedSummary = (await page.locator('.trace-result').first().innerText()).trim()
  if (reloadedSummary === PENDING) {
    failures.push('刷新后结果摘要仍是占位文案"检索中…"')
  }

  // ⑦ 第二条腿：知识库问答（mode=RAG）。环境缺少检索能力时如实跳过，混进失败项只会误导
  try {
    await verifyRagLeg()
  } catch (error) {
    if (backendRefusal) {
      skipped.push(`RAG 腿：${backendRefusal}`)
      console.log(`⚠ SKIPPED（RAG 腿）：${backendRefusal}`)
      console.log('  standalone 没有 Elasticsearch，RAG 在检索处即返回 4005；需 full 栈重跑本脚本。')
    } else {
      failures.push(error.message)
    }
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
if (skipped.length > 0) {
  // 退出码 2 = 部分跳过（环境能力缺失），与 1（真实失败）区分开，脚本调用方能分辨
  console.error('跳过项：' + skipped.join('; '))
  process.exit(2)
}
console.log('\n对话页界面验证通过：Agent（工具痕迹可回看）+ RAG（检索到 N 个片段且先于回答）。')
