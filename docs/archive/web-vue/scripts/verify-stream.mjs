/**
 * 流式问答的界面验证：真实驱动浏览器，确认"逐字出现"而不是等完整答案。
 *
 * 为什么必须用浏览器验证：流式的价值全在"时间维度"上——接口返回 200 只说明链路通了，
 * 只有观测到**文本在增长**才能证明它是流式而不是一次吐出。
 *
 * 检索条数必须在**第一个字之前**出现在界面上（"检索到 N 个片段"）。
 * 这条同样只有浏览器能证明：后端先发 retrieval 再发 delta，前端漏渲染的话接口测试全绿，
 * 用户却还是对着空白等生成。该标签只在生成期间可见，因此用轮询记录器抓它，
 * 避免"生成太快、断言采晚了"这种假阴性。
 *
 * ## 为什么这个脚本坚持用自己的账号和知识库
 *
 * 它原先借 demo 的令牌、在 demo 名下的知识库里提问。而 **demo 是受保护的演示账号**：
 * 一次问答就会在它名下写一条 `kb_qa_record`，而清理工具被保护规则挡在门外 ——
 * 也就是"每跑一轮 E2E，保护账号就多一条谁也不认识的数据，且没有任何通道能回收"。
 *
 * 现在脚本自己注册 `probe-stream-<ts>`（命中清理前缀白名单，可被 `cleanup-test-data`
 * 回收）、自建知识库、自己导入一份含**独有事实**的资料，收尾时把库删掉。
 * 它不再碰 demo 的任何数据 —— 顺带把断言也变强了：回答里必须出现那份独有事实，
 * 否则"检索 + 生成"这条链路就没真的跑通。
 */
import { chromium } from 'playwright-core'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { WEB_ROOT, apiUrl } from './lib/e2e-config.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const outDir = resolve(here, '../../docs/screenshots')
const base = WEB_ROOT

const stamp = Date.now().toString(36)
/** 前缀 `probe` 在 nexus.cleanup.username-prefixes 白名单里，因此账号可被回收 */
const username = `probe-stream-${stamp}`
const password = 'Probe@12345'
const kbName = `流式验收-${stamp}`
/** 独有事实：只有这份资料里有，回答里出现它才说明检索+生成真的用了这份资料 */
const FACT = `NEXUS-STREAM-FACT-${stamp}`

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const failures = []
let skipped = ''
let backendRefusal = ''
let kbId = null
let token = ''
let page = null

async function api(method, path, body, isForm = false) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body && !isForm) headers['Content-Type'] = 'application/json'
  const res = await fetch(apiUrl(path), {
    method,
    headers,
    body: isForm ? body : body ? JSON.stringify(body) : undefined
  })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    /* 非 JSON 响应保留原文用于报错 */
  }
  return { status: res.status, json, text }
}

/** 轮询导入任务到终态 */
async function waitTask(taskId) {
  for (let i = 0; i < 150; i++) {
    await sleep(1500)
    const { json } = await api('GET', `/import-tasks/${taskId}`)
    const task = json?.data
    if (task && ['DONE', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(task.status)) {
      return task
    }
  }
  return { status: 'TIMEOUT', totalChunks: 0, failedCount: -1 }
}

let browser = null
try {
  // ---------- ① 自建账号 → 自建知识库 → 导入含独有事实的资料 ----------
  const registered = await api('POST', '/auth/register', { username, password })
  if (registered.json?.code !== 0) {
    throw new Error(`注册失败：HTTP ${registered.status} ${registered.text.slice(0, 160)}`)
  }
  const login = await api('POST', '/auth/login', { username, password })
  token = login.json?.data?.accessToken
  if (!token) throw new Error(`登录失败：HTTP ${login.status} ${login.text.slice(0, 160)}`)
  console.log(`① 已注册并登录 ${username}`)

  const kb = await api('POST', '/knowledge-bases', {
    name: kbName,
    description: '流式与检索渲染验收（脚本自有，收尾即删）'
  })
  kbId = kb.json?.data?.id
  if (!kbId) throw new Error(`建库失败：HTTP ${kb.status} ${kb.text.slice(0, 160)}`)

  const content = [
    '流式验收资料。',
    '',
    `唯一标记：${FACT}`,
    '',
    '本资料只用于验证两件事：回答是逐字出现的，且检索标签先于正文出现。',
    `请记住上面的唯一标记 ${FACT}。`,
    ''
  ].join('\n')
  const form = new FormData()
  form.append('file', new Blob([Buffer.from(content, 'utf8')]), `流式验收-${stamp}.txt`)
  const uploaded = await api('POST', `/knowledge-bases/${kbId}/documents`, form, true)
  const taskId = uploaded.json?.data?.id
  if (!taskId) throw new Error(`上传失败：HTTP ${uploaded.status} ${uploaded.text.slice(0, 160)}`)
  const task = await waitTask(taskId)
  if (task.status !== 'DONE') throw new Error(`导入未完成：${task.status}`)
  console.log(`   资料已导入（切片 ${task.totalChunks}，失败 ${task.failedCount ?? 0}）`)

  // 导入只做"解析 + 分块"；可检索性由 workflow 负责（UA-014 的教训）
  const wf = await api('GET', `/workflow/run/${kbId}`)
  const steps = wf.json?.data ?? []
  const wfOk = steps.length > 0 && steps.every((s) => s.status === 'SUCCESS')
  console.log(
    `   索引流水线：${steps.length ? steps.map((s) => `${s.name}=${s.status}`).join(' → ') : `HTTP ${wf.status}`}`
  )
  if (!wfOk) failures.push('索引流水线没有全部 SUCCESS，后面的检索大概率命中不到')
  await sleep(2000) // ES refresh_interval=1s，给索引一点可见时间

  // 前置条件：刚导入的独有事实必须可检索。不成立就该先修这个，
  // 而不是继续往下测"流式"——那只会把"检索坏了"记成"流式坏了"。
  const probe = await api(
    'GET',
    `/knowledge-bases/${kbId}/search?q=${encodeURIComponent(FACT)}&topK=5&mode=hybrid`
  )
  const probeHits = probe.json?.data ?? []
  if (!JSON.stringify(probeHits).includes(FACT)) {
    failures.push(`前置条件不成立：刚导入的独有事实检索不到（返回 ${probeHits.length} 条）`)
  } else {
    console.log(`   前置检查：独有事实可检索（${probeHits.length} 条命中）`)
  }

  // ---------- ② 真实浏览器：打开这个知识库的「问答」页 ----------
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const context = await browser.newContext({ viewport: { width: 1440, height: 980 } })
  /*
   * addInitScript 的签名是 (script, arg) —— **只接受一个参数**。
   * 写成 (fn, token, username) 会抛 "Too many arguments..."（第一轮 E2E 就栽在这里，
   * 而它发生在浏览器启动后、断言之前，看起来像产品问题，其实是脚本自己的 API 误用）。
   * 要传多值就包成一个对象 —— 这也是本仓库其余 11 个脚本的既有写法。
   */
  await context.addInitScript(
    (value) => {
      localStorage.setItem('nexus.token', value.token)
      localStorage.setItem('nexus.username', value.username)
    },
    { token, username }
  )
  page = await context.newPage()

  /**
   * standalone（无 ES/pgvector）下 RAG 问答在检索这一步就返回 4005"检索索引未启用"——
   * 这是**环境能力缺失**，不是代码回归，因此要报"跳过"而不是"失败"。
   * 判定依据取后端响应体而不是界面提示：ElMessage 默认 3 秒就消失，
   * 等断言超时（几十秒）再去读 DOM 必然什么都读不到，会把环境问题误报成产品缺陷。
   */
  page.on('response', async (response) => {
    if (!response.url().includes('/ask/stream') || response.status() === 200) return
    try {
      const text = await response.text()
      if (text.includes('4005')) {
        backendRefusal = text.split(/\s+/).join(' ').slice(0, 200)
      }
    } catch {
      // 响应体读不到就保持空：宁可报失败，也不要凭空说"环境不行"
    }
  })

  await page.goto(base, { waitUntil: 'networkidle' })
  await page.waitForSelector('[data-testid="kb-card"]', { timeout: 15000 })
  await page.locator('[data-testid="kb-card"]').first().locator('[data-testid="kb-open"]').click()
  await page.waitForSelector('[data-testid="kb-doc-item"]', { timeout: 15000 })

  await page.click('#tab-ask')
  /*
   * 问答输入框按 data-testid 定位。这段历史值得留：它被"文案依赖"坑过两次。
   *
   * 第一次（2026-10-03 之前）：按 `getByPlaceholder('例如：缓存击穿有哪些解决方案？')` 定位，
   * 而 UA-003「去 Demo 化」改了那个占位符，于是这一行**静默失效**——直到把全部脚本
   * 复跑一遍才暴露（此前它从没被复跑过）。
   * 第二次（当时的修补）：改成"按含『提问』按钮的 .search-box 定位"——脆弱点其实没消掉，
   * 只是从"占位符文案"挪到了"按钮文案"，而且要用 getByRole('textbox') 绕开同框里的
   * 流式开关 checkbox，绕开的理由本身就说明这个选择器不干净。
   *
   * 现在：输入框 `kb-ask-input`、按钮 `kb-ask-submit`，检索页签里那个 .search-box
   * 靠 testid 天然区分，不必再"靠文案认出按钮"。
   */
  await page.fill('[data-testid="kb-ask-input"]', '资料里的唯一标记是什么？只回答标记本身。')
  // 检索标签只在生成期间渲染，而生成可能很快结束；在点击之前挂一个轮询记录器，
  // 记录它出现过的每一次文案（用户看得到的东西，脚本也必须"看到"）
  await page.evaluate(() => {
    window.__retrievalSeen = []
    window.__retrievalTimer = setInterval(() => {
      const node = document.querySelector('.retrieval-tag')
      const text = node ? node.textContent.trim() : ''
      if (text && !window.__retrievalSeen.includes(text)) {
        window.__retrievalSeen.push(text)
      }
    }, 50)
  })
  await page.click('[data-testid="kb-ask-submit"]')

  // ③ 先等第一批文本出现。
  //    注意：流式开始推流之前有一段"前置阶段"（检索 + 重排 + 组装），实测 10~20 秒——
  //    第一版脚本没等就量文本，量到 0 字误判为"不是流式"。这是脚本时序错误，不是产品问题。
  await page.waitForSelector('.stream-text', { timeout: 60000 })
  await page.waitForFunction(
    () => (document.querySelector('.stream-text')?.textContent ?? '').trim().length > 0,
    undefined,
    { timeout: 90000 }
  )
  const first = await page.locator('.stream-text').innerText()
  console.log(`③ 首批文本到达，${first.length} 字`)

  // ④ 文本应当继续增长——这是"真的在流式"的硬证据。
  //    但短回答可能在采样间隔内就结束了（.stream-text 会被换成渲染后的 Markdown），
  //    因此这里要容忍"已完成"，否则会把"生成得快"误判成"不是流式"（第一版就踩了）。
  await page.waitForTimeout(2500)
  if ((await page.locator('.stream-text').count()) > 0) {
    const second = await page.locator('.stream-text').innerText()
    console.log(`④ 2.5 秒后 ${second.length} 字（增长 ${second.length - first.length} 字）`)
    if (second.length <= first.length) {
      failures.push('文本没有增长，可能不是流式')
    }
  } else {
    console.log('④ 采样间隔内已完成（本次生成较快），跳过增长对比')
  }

  // ⑤ 结束后应渲染成 Markdown 并出现引用列表，回答里必须带上那份独有事实
  const answerBlock = page.locator('.answer-block:not(:has(.stream-text))')
  await page.waitForSelector('.answer-block:not(:has(.stream-text)) .citations', { timeout: 180000 })
  const citations = await page.locator('.citations li').count()
  const answer = (await answerBlock.last().innerText()).trim()
  console.log(`⑤ 生成结束，引用 ${citations} 条`)
  if (citations === 0) {
    failures.push('没有渲染出引用')
  }
  if (!answer.includes(FACT)) {
    failures.push(`回答里没有出现资料独有事实 ${FACT}（实际回答：${answer.slice(0, 120)}）`)
  } else {
    console.log(`   回答里出现了独有事实 ${FACT}`)
  }

  // ⑥ 检索条数：在第一批文本到达之前就已显示（retrieval 事件早于 delta 的界面证据）
  const seen = await page.evaluate(() => {
    clearInterval(window.__retrievalTimer)
    return window.__retrievalSeen ?? []
  })
  console.log(`⑥ 生成期间出现过的检索标签：${seen.length ? seen.join(' / ') : '(一次都没有)'}`)
  if (!seen.some((text) => /检索到 [0-9]+ 个片段/.test(text))) {
    failures.push('界面上没有出现"检索到 N 个片段"（retrieval 事件没渲染出来？）')
  }

  await page.screenshot({ path: `${outDir}/kb-answer.png` })
  console.log('✓ 已截图 docs/screenshots/kb-answer.png')
} catch (error) {
  if (backendRefusal) {
    skipped = `后端在检索这一步就拒绝了：${backendRefusal}`
    console.log(`⚠ SKIPPED：${skipped}`)
    console.log('  standalone 没有 Elasticsearch，RAG 问答在检索处即返回 4005，')
    console.log('  所以"流式 + 检索条数"两项在本地无法验证；需 full 栈（docker compose）重跑本脚本。')
  } else {
    failures.push(error.message)
    console.error('✗ 验证失败：', error.message)
    if (page) await page.screenshot({ path: `${outDir}/kb-answer-failure.png` })
  }
} finally {
  if (browser) await browser.close()
  // 收尾：删掉本轮自建的知识库（走 API 级联，不做裸表删除）。
  // 这个脚本不再用 demo 令牌，因此不需要"会话快照 + 差集"那套保护逻辑。
  if (kbId) {
    const del = await api('DELETE', `/knowledge-bases/${kbId}`)
    console.log(`· 清理：删除本轮知识库 ${kbId} → HTTP ${del.status}`)
  }
  if (token) {
    console.log(`· 本轮账号 ${username} 可由 cleanup-test-data 回收（前缀 probe 在白名单内）`)
  }
}

if (failures.length > 0) {
  process.exit(1)
}
if (skipped) {
  // 退出码 2 = 跳过（环境能力缺失），与 1（真实失败）区分，脚本调用方能分辨
  process.exit(2)
}
console.log('\n流式问答界面验证通过。')
