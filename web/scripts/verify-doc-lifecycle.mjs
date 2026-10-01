/**
 * 文档生命周期 E2E（Phase 5.3 · P1-1 / P1-2 / P1-3）：上传单文件 → 看到结果 → 删除 → 列表里消失。
 *
 * 覆盖四件此前只能靠手点的事：
 *   ① 单文件上传（P1-1）：一个 .md 走完整条异步导入链路后出现在文档列表里；
 *   ② 失败原因可见（P1-3）：解析失败的文件在导入面板里逐条列出**路径 + 原因**，
 *      而不是只剩一个「失败 1」的数字；
 *   ③ 删除文档（P1-2）：行尾的删除按钮 → 二次确认 → 该行消失，且不再被选中；
 *   ④ 删除按钮不能破坏「点整行选中文档」——这是本次改动最容易踩坏的地方
 *      （按钮没 stop 的话，点删除会先切换文档，右侧正文闪一下再报加载失败）。
 *
 * **它不能替代真机验收**：向量库 / ES 里到底还有没有残留，界面看不出来，
 * 必须删完直接查 `SELECT count(*) FROM kb_vector WHERE document_id=?` 与 ES 文档数。
 * 本脚本证明的是「界面把后端的结果如实呈现出来」这一层。
 *
 * 需要：后端 + 前端都在跑（standalone 即可，本脚本不碰检索/问答）。
 * 退出码：0 = 通过；1 = 真实失败。
 */
import { chromium } from 'playwright-core'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const token = readFileSync(resolve(here, '../.demo-token'), 'utf8').trim()
const base = process.env.WEB_BASE ?? 'http://localhost:5173'
const outDir = resolve(here, '../../docs/screenshots')

// 待上传的文件写到系统临时目录：脚本自己造数据，不依赖演示 Vault 里恰好有什么
const workDir = mkdtempSync(join(tmpdir(), 'nexus-verify-'))
const goodName = '生命周期验证.md'
const goodPath = join(workDir, goodName)
writeFileSync(goodPath, '# 生命周期验证\n\n这篇文档由 verify-doc-lifecycle.mjs 上传，用完即删。\n', 'utf8')

// 扩展名合法（能过入队校验）、内容不是 PDF —— 失败只能发生在解析阶段，
// 正是「入队期校验挡不住、必须靠明细解释」的那一类
const badName = '坏文件.pdf'
const badPath = join(workDir, badName)
writeFileSync(badPath, '这不是一个 PDF 文件', 'utf8')

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 980 } })
await context.addInitScript((value) => {
  localStorage.setItem('nexus.token', value)
  localStorage.setItem('nexus.username', 'demo')
}, token)

const page = await context.newPage()
const failures = []
const api = { Authorization: `Bearer ${token}` }
/** 本脚本自建的知识库，收尾时整库删掉（不留"验证用"的垃圾库堆在列表里） */
let kbId = null

/** 走按钮弹出系统文件选择器的那条真实路径（而不是直接调接口），否则测不到 pickDocument 的接线。 */
async function uploadViaButton(buttonText, filePath) {
  const [chooser] = await Promise.all([
    page.waitForEvent('filechooser', { timeout: 15000 }),
    page.click(`.head-actions button:has-text("${buttonText}")`)
  ])
  await chooser.setFiles(filePath)
}

/** 等到导入面板报出一个终态（awaitImport 的轮询间隔 1.2s，最多 240 次）。 */
async function waitForImportDone() {
  await page.waitForFunction(
    () => {
      const tag = document.querySelector('.import-result .ir-head .el-tag')
      return tag && ['DONE', 'PARTIAL', 'FAILED'].includes(tag.textContent.trim())
    },
    undefined,
    { timeout: 90000 }
  )
}

try {
  // ---------- 准备：建一个干净的知识库，从列表页点进去（顺带验证新按钮没破坏列表跳转） ----------
  const kbName = `生命周期 ${Date.now()}`
  const created = await context.request.post(`${base}/api/knowledge-bases`, {
    headers: api,
    data: { name: kbName }
  })
  if (!created.ok()) throw new Error(`建库失败：HTTP ${created.status()} ${await created.text()}`)
  kbId = (await created.json()).data.id
  console.log(`· 已建知识库 ${kbId}（${kbName}）`)

  await page.goto(base, { waitUntil: 'networkidle' })
  await page.waitForSelector('.card .name', { timeout: 15000 })
  await page.click(`.card:has-text("${kbName}") .name`)
  // 等工具栏而不是 `.docs`：新建的库一篇文档都没有，`ul.docs` 是空的、**不可见**，
  // 等它等于等一个永远不出现的东西（第一版就卡在这里）
  await page.waitForSelector('.head-actions button:has-text("上传文件")', { timeout: 20000 })
  await page.waitForSelector('.pane.doc-list', { timeout: 20000 })

  // ---------- ① 单文件上传（P1-1） ----------
  await uploadViaButton('上传文件', goodPath)
  await waitForImportDone()
  await page.waitForSelector(`.docs li:has-text("${goodName}")`, { timeout: 20000 })
  console.log(`① 上传成功：文档列表里出现了「${goodName}」`)

  const docRow = page.locator(`.docs li:has-text("${goodName}")`)
  const meta = (await docRow.locator('.doc-meta').innerText()).trim()
  console.log(`   行内元信息：${meta}`)
  if (!/切片/.test(meta)) failures.push(`文档行没有显示切片数：${meta}`)
  // 好消息那一条也要有交代：这次导入新增了 1 篇、没有失败
  const goodSummary = (await page.locator('.ir-head .num').innerText()).replace(/\s+/g, ' ')
  console.log(`   导入概览：${goodSummary}`)
  if (!/新增\s*1/.test(goodSummary) || !/失败\s*0/.test(goodSummary)) {
    failures.push(`导入概览没有如实反映"新增 1 / 失败 0"：${goodSummary}`)
  }

  // 点整行应当选中它（删除按钮是后来才加的，这条保证行点击没被破坏）
  await docRow.click()
  await page.waitForSelector(`.doc-title:has-text("生命周期验证")`, { timeout: 15000 })
  if ((await docRow.getAttribute('class') ?? '').includes('active')) {
    console.log('   点整行可以选中文档（行点击未被删除按钮破坏）')
  } else {
    failures.push('点整行没有选中文档')
  }

  // ---------- ② 失败原因可见（P1-3） ----------
  await uploadViaButton('上传文件', badPath)
  await waitForImportDone()
  await page.waitForSelector('.import-result .ir-group', { timeout: 20000 })

  const failedItems = await page.locator('.ir-group li').allInnerTexts()
  console.log(`② 导入明细：${failedItems.map((text) => text.replace(/\s+/g, ' ').trim()).join(' | ') || '(空)'}`)
  const badLine = failedItems.find((text) => text.includes(badName))
  if (!badLine) {
    failures.push(`失败明细里没有「${badName}」：${JSON.stringify(failedItems)}`)
  } else if (badLine.replace(badName, '').trim().length < 4) {
    // 只列路径不给原因，等于把「失败 1」换了个地方显示
    failures.push(`失败明细只有路径、没有原因：${badLine}`)
  }
  // 面板展示的是**最近一次**导入（这里已换成失败那一次），概览要把失败数如实报出来
  const summary = (await page.locator('.ir-head .num').innerText()).replace(/\s+/g, ' ')
  if (!/失败\s*1/.test(summary)) {
    failures.push(`导入概览没有如实反映"失败 1"：${summary}`)
  }
  await page.screenshot({ path: `${outDir}/kb-import-items.png` })
  console.log('✓ 已截图 docs/screenshots/kb-import-items.png')

  // ---------- ③ 删除文档（P1-2） ----------
  const beforeCount = await page.locator('.docs li').count()
  await docRow.locator('.doc-del').click()
  await page.waitForSelector('.el-message-box', { timeout: 10000 })
  const confirmText = (await page.locator('.el-message-box__message').innerText()).replace(/\s+/g, ' ').trim()
  console.log(`③ 二次确认：${confirmText}`)
  // 确认文案必须点明不可逆的部分，否则用户不知道自己在删什么
  if (!/不可撤销/.test(confirmText) || !/切片/.test(confirmText)) {
    failures.push(`确认文案没说清后果：${confirmText}`)
  }
  await page.screenshot({ path: `${outDir}/kb-doc-delete-confirm.png` })
  console.log('✓ 已截图 docs/screenshots/kb-doc-delete-confirm.png')

  await page.click('.el-message-box__btns button:has-text("删除")')
  await page.waitForFunction(
    (name) => !Array.from(document.querySelectorAll('.docs li'))
      .some((li) => li.textContent.includes(name)),
    goodName,
    { timeout: 20000 }
  )
  const afterCount = await page.locator('.docs li').count()
  console.log(`   文档数 ${beforeCount} → ${afterCount}`)
  if (afterCount !== beforeCount - 1) {
    failures.push(`删除后文档数应为 ${beforeCount - 1}，实际 ${afterCount}`)
  }
  // 删的正好是打开着的那篇 → 右侧阅读区必须被清空，而不是继续显示已经不存在的内容
  if (await page.locator('.markdown').count() > 0) {
    failures.push('删掉当前打开的文档后，右侧正文没有清空')
  }

  // 后端也要认这件事（界面自己把行抹掉不等于真的删了）
  const gone = await context.request.get(`${base}/api/knowledge-bases/${kbId}/documents`, { headers: api })
  const remaining = (await gone.json()).data.list.map((doc) => doc.sourcePath)
  console.log(`   后端剩余文档：${JSON.stringify(remaining)}`)
  if (remaining.includes(goodName)) {
    failures.push('界面上没了，但后端还能查到——删除请求没有真正生效')
  }
  await page.screenshot({ path: `${outDir}/kb-doc-deleted.png` })
  console.log('✓ 已截图 docs/screenshots/kb-doc-deleted.png')
} catch (error) {
  failures.push(error.message)
  console.error('✗ 验证失败：', error.message)
  await page.screenshot({ path: `${outDir}/doc-lifecycle-failure.png` })
} finally {
  // 整库删掉：这个库以及里面那个"坏文件"的失败记录都是脚本产物
  if (kbId) {
    try {
      const response = await context.request.delete(`${base}/api/knowledge-bases/${kbId}`, { headers: api })
      console.log(`· 已清理脚本自建的知识库 ${kbId}（HTTP ${response.status()}）`)
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
console.log('\n文档生命周期 E2E 通过（上传 / 失败原因 / 删除三条界面路径）。')
console.log('提醒：向量与 ES 的"无残留"仍需真机直接查库确认——界面看不出来。')
