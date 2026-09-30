/**
 * 检索卡片的**界面渲染契约**：用桩 SSE 帧喂给界面，确认"检索到 N 个片段"出现在回答之前。
 *
 * 为什么要有这一层：RAG 的检索卡片同时靠两件事——
 *   ① 后端在第一个 `delta` **之前**发出 `retrieval`：由 `RagStreamRetrievalEventTest` 在 HTTP 契约层
 *      证明（MockMvc 抓完整响应体，断言事件顺序）；
 *   ② 前端把这条事件渲染在回答正文之前：本脚本证明。
 *
 * 本地 `standalone` 没有 Elasticsearch，RAG 在检索处即返回 4005（见 verify-agent-chat.mjs 的跳过路径），
 * 所以②这一层用真后端根本跑不到。用桩帧把 UI 逻辑单独验掉，比"整个功能跳过"多一层保障。
 *
 * **它不能替代真机 E2E**：桩帧只证明"给定这套帧，界面是对的"，既不证明后端真的发这套帧
 * （契约测试的事），也不证明真机上检索真的能跑（full 栈的事）。三件事必须分开说。
 *
 * 桩帧形状与 docs/api.md §5.2 的表一致；会话创建、会话列表仍走真实后端。
 *
 * 退出码：0 = 通过；1 = 真实失败。
 */
import { chromium } from 'playwright-core'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const token = readFileSync(resolve(here, '../.demo-token'), 'utf8').trim()
const base = process.env.WEB_BASE ?? 'http://localhost:5173'
const outDir = resolve(here, '../../docs/screenshots')

const sleep = (ms) => new Promise((resolve_) => setTimeout(resolve_, ms))

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 980 } })
await context.addInitScript((value) => {
  localStorage.setItem('nexus.token', value)
  localStorage.setItem('nexus.username', 'demo')
}, token)

const page = await context.newPage()
const failures = []
/** 前端真正发出去的请求体（顺带证明"提问带的是 conversationId 与 content"） */
const sentBodies = []

/** 组成一段 SSE 响应体：帧之间用空行分隔（与后端一致）。 */
function sse(frames) {
  return frames.map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`).join('')
}

/** 两条腿的桩帧：先"命中两段"，再"一段都没命中"。 */
const legs = [
  {
    name: '命中两段',
    conversationId: '',
    frames: [
      ['meta', {
        requestId: 'stub-req-1',
        modelRole: 'chat.default',
        mode: 'RAG',
        citations: []
      }],
      ['retrieval', {
        knowledgeBaseId: '5001',
        count: 2,
        hits: [
          {
            chunkId: '9001', documentId: '100', documentTitle: '缓存击穿.md',
            headingPath: 'Redis > 缓存 > 缓存击穿', score: 0.83, source: 'hybrid'
          },
          {
            chunkId: '9002', documentId: '100', documentTitle: '缓存击穿.md',
            headingPath: 'Redis > 缓存 > 逻辑过期', score: 0.71, source: 'vector'
          }
        ],
        contextTokens: 1200,
        truncated: 0,
        reranked: true,
        empty: false
      }],
      ['delta', { text: '缓存击穿可以用**互斥锁**，' }],
      ['delta', { text: '也可以用逻辑过期。' }],
      ['usage', { promptTokens: 1200, completionTokens: 20, totalTokens: 1220, latencyMs: 900, firstTokenMs: 300 }],
      ['done', { finishReason: 'stop' }]
    ],
    /** 桩流"本该落库"的消息：生成结束后前端会拉一次它来校正界面（见 holdReload 注释） */
    messages: [
      {
        id: '90001', role: 'USER', content: '缓存击穿怎么解决？', model: null,
        status: 'SUCCESS', errorMsg: null, createdAt: '2026-09-30T10:00:00', toolCalls: []
      },
      {
        id: '90002', role: 'ASSISTANT', content: '缓存击穿可以用**互斥锁**，也可以用逻辑过期。', model: 'chat.default',
        status: 'SUCCESS', errorMsg: null, createdAt: '2026-09-30T10:00:01', toolCalls: []
      }
    ]
  },
  {
    name: '一段都没命中',
    conversationId: '',
    frames: [
      ['meta', {
        requestId: 'stub-req-2',
        modelRole: 'chat.default',
        mode: 'RAG',
        citations: []
      }],
      ['retrieval', {
        knowledgeBaseId: '5001',
        count: 0,
        hits: [],
        contextTokens: 0,
        truncated: 0,
        reranked: false,
        empty: true
      }],
      ['delta', { text: '资料中没有相关内容（知识库未导入或未建立索引）。' }],
      ['done', { finishReason: 'empty' }]
    ],
    messages: [
      {
        id: '90003', role: 'USER', content: '库里没有的问题', model: null,
        status: 'SUCCESS', errorMsg: null, createdAt: '2026-09-30T10:01:00', toolCalls: []
      },
      {
        id: '90004', role: 'ASSISTANT', content: '资料中没有相关内容（知识库未导入或未建立索引）。', model: 'chat.default',
        status: 'SUCCESS', errorMsg: null, createdAt: '2026-09-30T10:01:01', toolCalls: []
      }
    ]
  }
]

let legIndex = 0
await page.route('**/api/chat/stream', async (route) => {
  const leg = legs[Math.min(legIndex, legs.length - 1)]
  legIndex += 1
  let body = {}
  try {
    body = JSON.parse(route.request().postData() ?? '{}')
  } catch {
    // 请求体读不出来就是协议问题，sentBodies 里留空对象，断言会报出来
  }
  sentBodies.push(body)
  leg.conversationId = String(body.conversationId ?? '')
  await route.fulfill({
    status: 200,
    headers: { 'Content-Type': 'text/event-stream; charset=utf-8' },
    body: sse(leg.frames)
  })
})

/**
 * 生成结束后前端会用 `GET /api/chat/messages/{id}` 校正一次界面（拿真实 id 与工具痕迹）。
 * 命中片段<b>没有</b>随消息落库，这次校正会把检索卡片抹掉——那是已知缺口（见 AgentView.toItems 注释），
 * 不是渲染缺陷。若不放慢这一步，卡片只在 DOM 里活几十毫秒，断言必然"时有时无"的假阴性。
 * 所以发送后的第一次校正挂住 8 秒：断言在稳定窗口里跑，窗口一过再放行，
 * 并顺手把"校正后卡片消失、答案还在"这个缺口也断言出来。
 */
const RELOAD_HOLD_MS = 8000
let holdReload = false
await page.route('**/api/chat/messages/**', async (route) => {
  const conversationId = (route.request().url().split('/chat/messages/')[1] ?? '').split('?')[0]
  const leg = legs.find((item) => item.conversationId && item.conversationId === conversationId)
  if (holdReload && leg) {
    await sleep(RELOAD_HOLD_MS)
  }
  const list = leg ? leg.messages : []
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 0,
      message: 'ok',
      data: { list, total: list.length, pageNum: 1, pageSize: 100 }
    })
  })
})

/** 新建会话并选到 RAG 模式（真接口：顺带验证"建会话时带上了知识库"）。 */
async function newRagConversation() {
  await page.click('.rail .new-chat')
  await page.waitForFunction(
    () => document.querySelectorAll('.message').length === 0,
    undefined,
    { timeout: 10000 }
  )
  await page.click('.composer-options .el-radio-button:has-text("知识库问答")')
  await page.waitForSelector('.composer-options .kb-select', { timeout: 15000 })
}

/** 检索卡片与回答都渲染完（生成结束、服务端校正还没落地的那一刻）。 */
async function waitForCard() {
  await page.waitForSelector('.message.from-agent .retrieval-count', { timeout: 30000 })
  await page.waitForSelector('.message.from-agent .markdown:not(.stream-text)', { timeout: 30000 })
}

const answerText = () =>
  page.locator('.message.from-agent .markdown:not(.stream-text)').last().innerText()

try {
  await page.goto(base, { waitUntil: 'networkidle' })
  await page.click('.nav-item:has-text("对话")')
  await page.waitForSelector('.composer textarea', { timeout: 15000 })

  // ---------- 腿一：命中两段 ----------
  await newRagConversation()
  await page.fill('.composer textarea', '缓存击穿怎么解决？')
  holdReload = true
  await page.click('.composer .send')
  await waitForCard()

  const countText = (await page.locator('.retrieval-count').last().innerText()).trim()
  console.log(`① 检索卡片：${countText}`)
  if (countText !== '检索到 2 个片段') {
    failures.push(`检索卡片文案异常：${countText}`)
  }

  const hits = await page.locator('.message.from-agent .retrieval-hits li').allInnerTexts()
  console.log(`② 命中列表：${hits.map((text) => text.trim()).join(' | ') || '(空)'}`)
  if (hits.length !== 2) {
    failures.push(`命中列表条数应为 2，实际 ${hits.length}`)
  }
  if (!(hits[0] ?? '').includes('缓存击穿.md') || !(hits[0] ?? '').includes('Redis > 缓存 > 缓存击穿')) {
    failures.push(`命中列表没有带出文档名与标题路径：${hits[0]}`)
  }
  if ((await page.locator('.retrieval .el-tag:has-text("已重排")').count()) !== 1) {
    failures.push('reranked=true 时没有显示"已重排"标签')
  }

  // 顺序：卡片必须排在回答正文之前——retrieval 事件早于 delta 的界面表现。
  // 只断言"卡片存在"是不够的：晚一拍出现就是"回答已经蹦字，检索条数才冒出来"
  const ordered = await page.evaluate(() => {
    const bubbles = Array.from(document.querySelectorAll('.message.from-agent .bubble'))
    const last = bubbles[bubbles.length - 1]
    const card = last?.querySelector('.retrieval')
    const answer = last?.querySelector('.markdown')
    if (!card || !answer) return false
    return (card.compareDocumentPosition(answer) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0
  })
  if (!ordered) {
    failures.push('检索卡片没有排在回答之前（retrieval 事件晚于 delta？）')
  }

  const answer = (await answerText()).trim()
  console.log(`③ 回答：${answer}`)
  if (!answer.includes('互斥锁') || !answer.includes('逻辑过期')) {
    failures.push(`回答内容与桩帧不符：${answer}`)
  }
  if (!sentBodies[0]?.conversationId || !sentBodies[0]?.content) {
    failures.push(`流式请求体异常：${JSON.stringify(sentBodies[0])}`)
  } else {
    console.log(`④ 流式请求体：conversationId=${sentBodies[0].conversationId} content=「${sentBodies[0].content}」`)
  }
  await page.screenshot({ path: `${outDir}/agent-chat-rag-retrieval.png` })
  console.log('✓ 已截图 docs/screenshots/agent-chat-rag-retrieval.png')

  // ⑤ 服务端校正落地后：答案还在（来自桩的落库消息），检索卡片消失——已知缺口，不是 bug
  await page.waitForFunction(
    () => document.querySelectorAll('.message.from-agent .retrieval').length === 0,
    undefined,
    { timeout: 20000 }
  )
  holdReload = false
  const afterReload = (await answerText()).trim()
  console.log(`⑤ 服务端校正后：检索卡片消失（已知缺口：命中片段未落库），答案仍在（${afterReload.length} 字）`)
  if (!afterReload.includes('互斥锁')) {
    failures.push('服务端校正后回答丢失')
  }

  // ---------- 腿二：一段都没命中（不调模型，但卡片与回答都要有） ----------
  await newRagConversation()
  await page.fill('.composer textarea', '库里没有的问题')
  holdReload = true
  await page.click('.composer .send')
  await waitForCard()
  holdReload = false

  const emptyCount = (await page.locator('.retrieval-count').last().innerText()).trim()
  console.log(`⑥ 零命中卡片：${emptyCount}`)
  if (emptyCount !== '检索到 0 个片段') {
    failures.push(`零命中卡片文案异常：${emptyCount}`)
  }
  if ((await page.locator('.retrieval .el-tag:has-text("知识库里没有命中")').count()) !== 1) {
    failures.push('empty=true 时没有显示"知识库里没有命中"标签')
  }
  const emptyAnswer = (await answerText()).trim()
  console.log(`⑦ 零命中回答：${emptyAnswer}`)
  if (!emptyAnswer.includes('资料中没有相关内容')) {
    failures.push(`零命中回答不明确：${emptyAnswer}`)
  }
} catch (error) {
  failures.push(error.message)
  console.error('✗ 验证失败：', error.message)
  await page.screenshot({ path: `${outDir}/agent-chat-rag-ui-failure.png` })
} finally {
  // 收尾：删掉本轮建的两个会话。不删也不影响结论（H2 是内存库，重启即清），
  // 但反复跑会把演示数据堆成一列"新对话"，看起来像是新建会话坏了。
  for (const body of sentBodies) {
    if (!body.conversationId) continue
    try {
      const response = await context.request.delete(
        `${base}/api/chat/conversations/${body.conversationId}`,
        { headers: { Authorization: `Bearer ${token}` } }
      )
      console.log(`· 已清理本轮会话 ${body.conversationId}（HTTP ${response.status()}）`)
    } catch {
      // 清理失败只是脚本卫生问题，不该影响验证结论
    }
  }
  await browser.close()
}

if (failures.length > 0) {
  console.error('失败项：' + failures.join('; '))
  process.exit(1)
}
console.log('\n检索卡片界面渲染契约通过（桩 SSE 帧；真机 E2E 仍需 full 栈）。')
