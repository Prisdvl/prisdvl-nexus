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
import { demoToken } from './lib/demo-session.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const token = await demoToken()
const base = process.env.WEB_BASE ?? 'http://localhost:8088'
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

/**
 * 桩帧四条腿：命中两段 → 一段都没命中 → **其中一库检索失败** → **全部库检索失败**。
 *
 * 后两条是 Phase 5.5.1 加的：多库并行之后，单个库失败是被**隔离**掉的，
 * 界面上"检索到 N 个片段"照样显示——所以"这次少搜了谁"必须由卡片自己说出来。
 * 这两条腿同时钉住那句措辞：空结果标签**随有没有库失败改口**
 * （"知识库里没有命中"是一句关于内容的断言，有库没搜成时说它就是假话）。
 * 桩帧是这个 UI 在没有 ES 时唯一能验到的地方。
 */
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
        // Phase 5.5 起负载是 knowledgeBaseIds（列表），且每条命中各带自己的出处。
        // 这里刻意给两个库：单库的桩证明不了"每段的来源能分开显示"这件事
        knowledgeBaseIds: ['5001', '5002'],
        count: 2,
        hits: [
          {
            chunkId: '9001', documentId: '100', knowledgeBaseId: '5001',
            documentTitle: '缓存击穿.md',
            headingPath: 'Redis > 缓存 > 缓存击穿', score: 0.83, source: 'hybrid'
          },
          {
            chunkId: '9002', documentId: '100', knowledgeBaseId: '5002',
            documentTitle: '缓存击穿.md',
            headingPath: 'Redis > 缓存 > 逻辑过期', score: 0.71, source: 'vector'
          }
        ],
        contextTokens: 1200,
        truncated: 0,
        reranked: true,
        empty: false,
        // Phase 5.5.1：正常时是空数组（不是缺省字段——前端不判空）
        failedKnowledgeBases: []
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
        knowledgeBaseIds: ['5001'],
        count: 0,
        hits: [],
        contextTokens: 0,
        truncated: 0,
        reranked: false,
        empty: true,
        // 一个库、成功检索、只是没命中：这里**没有**失败库，
        // 因此空结果标签必须说"知识库里没有命中"（与腿四对照）
        failedKnowledgeBases: []
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
  },
  {
    // Phase 5.5.1 的核心界面：绑了两个库，其中一个**没搜成**。
    // 卡片照样显示"检索到 1 个片段"——不把失败点出来，用户会以为答案涵盖了两个库。
    name: '其中一个库检索失败',
    conversationId: '',
    frames: [
      ['meta', {
        requestId: 'stub-req-3',
        modelRole: 'chat.default',
        mode: 'RAG',
        citations: []
      }],
      ['retrieval', {
        knowledgeBaseIds: ['5001', '5002'],
        count: 1,
        hits: [
          {
            chunkId: '9001', documentId: '100', knowledgeBaseId: '5001',
            documentTitle: '缓存击穿.md',
            headingPath: 'Redis > 缓存 > 缓存击穿', score: 0.83, source: 'hybrid'
          }
        ],
        contextTokens: 600,
        truncated: 0,
        reranked: true,
        empty: false,
        failedKnowledgeBases: [
          { knowledgeBaseId: '5002', knowledgeBaseName: '内部 FAQ', reason: 'ES 连接被拒绝' }
        ]
      }],
      ['delta', { text: '（资料不全）缓存击穿可以用互斥锁；内部 FAQ 这次没能检索，' }],
      ['delta', { text: '里面若也有相关内容，需要稍后重试。' }],
      ['done', { finishReason: 'stop' }]
    ],
    messages: [
      {
        id: '90005', role: 'USER', content: '缓存击穿怎么解决？', model: null,
        status: 'SUCCESS', errorMsg: null, createdAt: '2026-09-30T10:02:00', toolCalls: []
      },
      {
        id: '90006', role: 'ASSISTANT', content: '（资料不全）缓存击穿可以用互斥锁；内部 FAQ 这次没能检索，里面若也有相关内容，需要稍后重试。',
        model: 'chat.default', status: 'SUCCESS', errorMsg: null, createdAt: '2026-09-30T10:02:01', toolCalls: []
      }
    ]
  },
  {
    // 两个库全挂：空结果 + 有失败库 → 措辞必须与"检索到了、只是没命中"区分开。
    // 若这里仍显示"知识库里没有命中"，用户会读成"你的笔记里没有"——
    // 而真相是一个库都没搜成（服务端为此换掉了固定回答，界面必须跟上）。
    name: '全部库检索失败',
    conversationId: '',
    frames: [
      ['meta', {
        requestId: 'stub-req-4',
        modelRole: 'chat.default',
        mode: 'RAG',
        citations: []
      }],
      ['retrieval', {
        knowledgeBaseIds: ['5001', '5002'],
        count: 0,
        hits: [],
        contextTokens: 0,
        truncated: 0,
        reranked: false,
        empty: true,
        failedKnowledgeBases: [
          { knowledgeBaseId: '5001', knowledgeBaseName: '产品手册', reason: '超过 10 秒未返回（整体超时）' },
          { knowledgeBaseId: '5002', knowledgeBaseName: '内部 FAQ', reason: 'ES 连接被拒绝' }
        ]
      }],
      ['delta', { text: '有知识库暂时无法检索（可能正在重建索引或依赖服务不可用），本次没有拿到可用资料。请稍后重试，或先只绑定单个知识库确认。' }],
      ['done', { finishReason: 'empty' }]
    ],
    messages: [
      {
        id: '90007', role: 'USER', content: '两个库都挂了的问题', model: null,
        status: 'SUCCESS', errorMsg: null, createdAt: '2026-09-30T10:03:00', toolCalls: []
      },
      {
        id: '90008', role: 'ASSISTANT', content: '有知识库暂时无法检索（可能正在重建索引或依赖服务不可用），本次没有拿到可用资料。请稍后重试，或先只绑定单个知识库确认。',
        model: 'chat.default', status: 'SUCCESS', errorMsg: null, createdAt: '2026-09-30T10:03:01', toolCalls: []
      }
    ]
  },
  {
    // 两库、一个搜成了但没命中、另一个没搜成：三种空结果的措辞到此才算齐。
    // 这一条最容易被写成"知识库里没有命中"——那句只对"搜过且没命中"成立。
    name: '一个库没命中、一个库没搜成',
    conversationId: '',
    frames: [
      ['meta', {
        requestId: 'stub-req-5',
        modelRole: 'chat.default',
        mode: 'RAG',
        citations: []
      }],
      ['retrieval', {
        knowledgeBaseIds: ['5001', '5002'],
        count: 0,
        hits: [],
        contextTokens: 0,
        truncated: 0,
        reranked: false,
        empty: true,
        failedKnowledgeBases: [
          { knowledgeBaseId: '5002', knowledgeBaseName: '内部 FAQ', reason: '超过 10 秒未返回（整体超时）' }
        ]
      }],
      ['delta', { text: '有知识库暂时无法检索（可能正在重建索引或依赖服务不可用），本次没有拿到可用资料。请稍后重试，或先只绑定单个知识库确认。' }],
      ['done', { finishReason: 'empty' }]
    ],
    messages: [
      {
        id: '90009', role: 'USER', content: '一个库超时的问题', model: null,
        status: 'SUCCESS', errorMsg: null, createdAt: '2026-09-30T10:04:00', toolCalls: []
      },
      {
        id: '90010', role: 'ASSISTANT', content: '有知识库暂时无法检索（可能正在重建索引或依赖服务不可用），本次没有拿到可用资料。请稍后重试，或先只绑定单个知识库确认。',
        model: 'chat.default', status: 'SUCCESS', errorMsg: null, createdAt: '2026-09-30T10:04:01', toolCalls: []
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
  await page.click('[data-testid="chat-new"]')
  await page.waitForFunction(
    () => document.querySelectorAll('[data-testid="chat-message"]').length === 0,
    undefined,
    { timeout: 10000 }
  )
  await page.click('[data-testid="chat-draft-mode-rag"]')
  await page.waitForSelector('[data-testid="chat-draft-kb-select"]', { timeout: 15000 })
}

/** 检索卡片与回答都渲染完（生成结束、服务端校正还没落地的那一刻）。 */
async function waitForCard() {
  await page.waitForSelector('[data-testid="chat-message"][data-role="ASSISTANT"] .retrieval-count', { timeout: 30000 })
  await page.waitForSelector('[data-testid="chat-message"][data-role="ASSISTANT"] .markdown:not(.stream-text)', { timeout: 30000 })
}

const answerText = () =>
  page.locator('[data-testid="chat-message"][data-role="ASSISTANT"] .markdown:not(.stream-text)').last().innerText()

try {
  await page.goto(base, { waitUntil: 'networkidle' })
  await page.click('[data-testid="nav-chat"]')
  await page.waitForSelector('[data-testid="chat-input"]', { timeout: 15000 })

  // ---------- 腿一：命中两段 ----------
  await newRagConversation()
  await page.fill('[data-testid="chat-input"]', '缓存击穿怎么解决？')
  holdReload = true
  await page.click('[data-testid="chat-send"]')
  await waitForCard()

  const countText = (await page.locator('.retrieval-count').last().innerText()).trim()
  console.log(`① 检索卡片：${countText}`)
  if (countText !== '检索到 2 个片段') {
    failures.push(`检索卡片文案异常：${countText}`)
  }

  const hits = await page.locator('[data-testid="chat-message"][data-role="ASSISTANT"] .retrieval-hits li').allInnerTexts()
  console.log(`② 命中列表：${hits.map((text) => text.trim()).join(' | ') || '(空)'}`)
  if (hits.length !== 2) {
    failures.push(`命中列表条数应为 2，实际 ${hits.length}`)
  }
  if (!(hits[0] ?? '').includes('缓存击穿.md') || !(hits[0] ?? '').includes('Redis > 缓存 > 缓存击穿')) {
    failures.push(`命中列表没有带出文档名与标题路径：${hits[0]}`)
  }
  if ((await page.locator('[data-testid="chat-retrieval-reranked"]').count()) !== 1) {
    failures.push('reranked=true 时没有显示"已重排"标签')
  }

  // 顺序：卡片必须排在回答正文之前——retrieval 事件早于 delta 的界面表现。
  // 只断言"卡片存在"是不够的：晚一拍出现就是"回答已经蹦字，检索条数才冒出来"
  const ordered = await page.evaluate(() => {
    const bubbles = Array.from(document.querySelectorAll('[data-testid="chat-message"][data-role="ASSISTANT"] .bubble'))
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
    () => document.querySelectorAll('[data-testid="chat-message"][data-role="ASSISTANT"] .retrieval').length === 0,
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
  await page.fill('[data-testid="chat-input"]', '库里没有的问题')
  holdReload = true
  await page.click('[data-testid="chat-send"]')
  await waitForCard()
  holdReload = false

  const emptyCount = (await page.locator('.retrieval-count').last().innerText()).trim()
  console.log(`⑥ 零命中卡片：${emptyCount}`)
  if (emptyCount !== '检索到 0 个片段') {
    failures.push(`零命中卡片文案异常：${emptyCount}`)
  }
  if ((await page.locator('[data-testid="chat-retrieval-empty"][data-empty-variant="none"]').count()) !== 1) {
    failures.push('empty=true 时没有显示"知识库里没有命中"标签')
  }
  const emptyAnswer = (await answerText()).trim()
  console.log(`⑦ 零命中回答：${emptyAnswer}`)
  if (!emptyAnswer.includes('资料中没有相关内容')) {
    failures.push(`零命中回答不明确：${emptyAnswer}`)
  }
  if ((await page.locator('[data-testid="chat-retrieval-failed"]').count()) !== 0) {
    failures.push('没有失败库时不该出现"检索失败"标签（有它就是凭空报故障）')
  }

  // ---------- 腿三：其中一个库检索失败（Phase 5.5.1 的核心界面） ----------
  await newRagConversation()
  await page.fill('[data-testid="chat-input"]', '缓存击穿怎么解决？')
  holdReload = true
  await page.click('[data-testid="chat-send"]')
  await waitForCard()
  // 注意：这一步**先别放行**服务端校正。校正一落地检索卡片就消失（命中片段未落库，已知缺口），
  // 断言做到一半元素被换掉就成了"时有时无"的假阴性——首轮实测就是这么挂在 hover 上的。

  const partialTag = page.locator('[data-testid="chat-retrieval-failed"]')
  const partialTagText = (await partialTag.last().innerText()).trim()
  console.log(`⑧ 部分失败卡片：${partialTagText}`)
  if (partialTagText !== '其中 1 个知识库检索失败') {
    failures.push(`部分失败标签文案异常：${partialTagText}`)
  }
  if ((await page.locator('[data-testid="chat-retrieval-empty"][data-empty-variant="none"]').count()) !== 0) {
    failures.push('有命中时不该出现"知识库里没有命中"')
  }
  // 只说"1 个库失败"不够：用户还得知道是**哪个**、**为什么**，否则无从判断能不能重试。
  // 这里**不用 hover 而用派发事件**：首轮实测 hover 会被 el-loading-mask 挡住指针事件
  // （会话列表刷新时的遮罩），重试 30 秒永远等不到；派发事件不走命中测试，验的还是同一件事——
  // Vue 监听到 mouseenter → el-tooltip 渲染出内容。
  try {
    await partialTag.last().dispatchEvent('mouseenter')
    // 只等"浮层出现"，不等它含某个库名：库名是**本脚本自建的夹具**，
    // 拿它当选择器等于让断言依赖夹具（改个夹具名就静默失效）。
    // "有没有指出是哪个库、有没有给原因"在下面显式断言——那才是这条腿要验的东西。
    await page.waitForSelector('.el-popper[role="tooltip"]:visible', { timeout: 8000 })
    const tip = (await page.locator('.el-popper[role="tooltip"]:visible').last().innerText()).trim()
    console.log(`⑨ 失败明细悬浮提示：${tip}`)
    if (!tip.includes('内部 FAQ')) {
      failures.push(`悬浮提示没有指出是哪个库失败：${tip}`)
    }
    if (!tip.includes('ES 连接被拒绝')) {
      failures.push(`悬浮提示没有给出失败原因：${tip}`)
    }
  } catch {
    failures.push('悬浮"检索失败"标签没有弹出库名与原因')
  }
  // 等入场动画落定再截图：首轮截在 zoom-in 的中途，标签在图上是一团糊的，
  // 作为证据它什么也证明不了（截图的价值就是"人能看懂"）
  await sleep(700)
  await page.screenshot({ path: `${outDir}/agent-chat-rag-retrieval-failed-kb.png` })
  console.log('✓ 已截图 docs/screenshots/agent-chat-rag-retrieval-failed-kb.png')
  holdReload = false

  // ---------- 腿四：全部库检索失败（空结果的措辞必须改口） ----------
  await newRagConversation()
  await page.fill('[data-testid="chat-input"]', '两个库都挂了的问题')
  holdReload = true
  await page.click('[data-testid="chat-send"]')
  await waitForCard()

  const allFailCount = (await page.locator('.retrieval-count').last().innerText()).trim()
  const allFailTags = (await page.locator('[data-testid="chat-retrieval"] .el-tag').allInnerTexts())
    .map((text) => text.trim())
  console.log(`⑩ 全部失败卡片：${allFailCount} / 标签 ${JSON.stringify(allFailTags)}`)
  if (allFailCount !== '检索到 0 个片段') {
    failures.push(`全部失败卡片文案异常：${allFailCount}`)
  }
  // 断言用的是"哪个标签在"，不是"第几个标签说什么"——顺序一变就误报的断言没有价值
  if ((await page.locator('[data-testid="chat-retrieval-empty"][data-empty-variant="all-failed"]').count()) !== 1) {
    failures.push(`有失败库时空结果标签必须改口，实际标签：${JSON.stringify(allFailTags)}`)
  }
  if ((await page.locator('[data-testid="chat-retrieval-empty"][data-empty-variant="none"]').count()) !== 0) {
    failures.push('库都没搜成时说了"知识库里没有命中"——与腿二对照，这句措辞必须是条件式的')
  }
  if ((await page.locator('[data-testid="chat-retrieval-failed"]').count()) !== 1) {
    failures.push('两个库全失败时没有显示"其中 2 个知识库检索失败"')
  }
  const allFailAnswer = (await answerText()).trim()
  console.log(`⑪ 全部失败回答：${allFailAnswer}`)
  if (!allFailAnswer.includes('有知识库暂时无法检索')) {
    failures.push(`全部失败时的回答不该说"资料中没有相关内容"：${allFailAnswer}`)
  }
  holdReload = false

  // ---------- 腿五：一个库搜成了但没命中、另一个没搜成 ----------
  await newRagConversation()
  await page.fill('[data-testid="chat-input"]', '一个库超时的问题')
  holdReload = true
  await page.click('[data-testid="chat-send"]')
  await waitForCard()

  const mixedTags = (await page.locator('[data-testid="chat-retrieval"] .el-tag').allInnerTexts()).map((t) => t.trim())
  console.log(`⑫ 部分失败且零命中：${JSON.stringify(mixedTags)}`)
  if ((await page.locator('[data-testid="chat-retrieval-empty"][data-empty-variant="partial"]').count()) !== 1) {
    failures.push(`还有一个库正常搜过时应说"其余知识库没有命中"，实际：${JSON.stringify(mixedTags)}`)
  }
  if ((await page.locator('[data-testid="chat-retrieval-empty"][data-empty-variant="none"]').count()) !== 0) {
    failures.push('有库没搜成时不能断言"知识库里没有命中"')
  }
  holdReload = false
} catch (error) {
  failures.push(error.message)
  console.error('✗ 验证失败：', error.message)
  // 失败现场截图：**临时的**，只给当场排查用，不要提交。
  // （上一版把它提交进了仓库，于是它长期躺在 docs/screenshots 里冒充"当前状态"——
  //  它记的是某次失败，不是界面该有的样子。失败的证据由退出码和这行日志给。）
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
