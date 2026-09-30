/**
 * 流式问答的界面验证：真实驱动浏览器，确认"逐字出现"而不是等完整答案。
 *
 * 为什么必须用浏览器验证：流式的价值全在"时间维度"上——接口返回 200 只说明链路通了，
 * 只有观测到**文本在增长**才能证明它是流式而不是一次吐出。
 *
 * Phase 3.4 增补：检索条数必须在**第一个字之前**出现在界面上（"检索到 N 个片段"）。
 * 这条同样只有浏览器能证明：后端先发 retrieval 再发 delta，前端漏渲染的话接口测试全绿，
 * 用户却还是对着空白等生成。该标签只在生成期间可见，因此用轮询记录器抓它，
 * 避免"生成太快、断言采晚了"这种假阴性。
 */
import { chromium } from 'playwright-core'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const token = readFileSync(resolve(here, '../.demo-token'), 'utf8').trim()
const base = process.env.WEB_BASE ?? 'http://localhost:5173'
const outDir = resolve(here, '../../docs/screenshots')

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 980 } })
await context.addInitScript((value) => {
  localStorage.setItem('nexus.token', value)
  localStorage.setItem('nexus.username', 'demo')
}, token)

const page = await context.newPage()
const failures = []
let skipped = ''

/**
 * standalone（无 ES/pgvector）下 RAG 问答在检索这一步就返回 4005"检索索引未启用"——
 * 这是**环境能力缺失**，不是代码回归，因此要报"跳过"而不是"失败"。
 * 判定依据取后端响应体而不是界面提示：ElMessage 默认 3 秒就消失，
 * 等断言超时（几十秒）再去读 DOM 必然什么都读不到，会把环境问题误报成产品缺陷。
 */
let backendRefusal = ''
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

try {
  await page.goto(base, { waitUntil: 'networkidle' })
  await page.waitForSelector('.card .name', { timeout: 15000 })
  await page.click('.card .name')
  await page.waitForSelector('.docs li', { timeout: 15000 })

  await page.click('.el-tabs__item:has-text("问答")')
  // 用 placeholder 精确定位问答输入框（.search-box 里还有"流式"开关的 checkbox，
  // 用类名定位会撞多个元素——strict mode 会直接报错）
  await page.getByPlaceholder('例如：缓存击穿有哪些解决方案？').fill('缓存击穿有哪些解决方案？')
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
  await page.locator('.search-box button:has-text("提问")').click()

  // ① 先等第一批文本出现。
  //    注意：流式开始推流之前有一段"前置阶段"（检索 + 重排 + 组装），实测 10~20 秒——
  //    第一版脚本没等就量文本，量到 0 字误判为"不是流式"。这是脚本时序错误，不是产品问题。
  await page.waitForSelector('.stream-text', { timeout: 60000 })
  await page.waitForFunction(
    () => (document.querySelector('.stream-text')?.textContent ?? '').trim().length > 0,
    undefined,
    { timeout: 90000 }
  )
  const first = await page.locator('.stream-text').innerText()
  console.log(`① 首批文本到达，${first.length} 字`)

  // ② 文本应当继续增长——这是"真的在流式"的硬证据。
  //    但短回答可能在采样间隔内就结束了（.stream-text 会被换成渲染后的 Markdown），
  //    因此这里要容忍"已完成"，否则会把"生成得快"误判成"不是流式"（第一版就踩了）。
  await page.waitForTimeout(2500)
  if ((await page.locator('.stream-text').count()) > 0) {
    const second = await page.locator('.stream-text').innerText()
    console.log(`② 2.5 秒后 ${second.length} 字（增长 ${second.length - first.length} 字）`)
    if (second.length <= first.length) {
      failures.push('文本没有增长，可能不是流式')
    }
  } else {
    console.log('② 采样间隔内已完成（本次生成较快），跳过增长对比')
  }

  // ③ 结束后应渲染成 Markdown 并出现引用列表
  await page.waitForSelector('.answer-block:not(:has(.stream-text)) .citations', { timeout: 180000 })
  const citations = await page.locator('.citations li').count()
  console.log(`③ 生成结束，引用 ${citations} 条`)
  if (citations === 0) {
    failures.push('没有渲染出引用')
  }

  // ④ 检索条数：在第一批文本到达之前就已显示（retrieval 事件早于 delta 的界面证据）
  const seen = await page.evaluate(() => {
    clearInterval(window.__retrievalTimer)
    return window.__retrievalSeen ?? []
  })
  console.log(`④ 生成期间出现过的检索标签：${seen.length ? seen.join(' / ') : '(一次都没有)'}`)
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
    await page.screenshot({ path: `${outDir}/kb-answer-failure.png` })
  }
} finally {
  await browser.close()
}

if (failures.length > 0) {
  process.exit(1)
}
if (skipped) {
  // 退出码 2 = 跳过（环境能力缺失），与 1（真实失败）区分，脚本调用方能分辨
  process.exit(2)
}
console.log('\n流式问答界面验证通过。')
