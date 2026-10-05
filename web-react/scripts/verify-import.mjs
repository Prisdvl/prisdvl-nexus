/**
 * R7 导入资料验证。
 *
 * 覆盖：文件选择 / **取消选择不卡死**（任务书 §19）/ 逐文件状态（§20）/
 *       导入结果常驻（§22）/ 文档列表 / 状态中文 / 大小单位 / 创建-清理闭环。
 *
 * 会**真实上传一个临时 md 文件**并在结束时删掉它所在的测试知识库。
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
const ctx = await browser.newContext({ viewport: { width: 1440, height: 980 } })
const page = await ctx.newPage()
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text())
})
page.on('pageerror', (e) => pageErrors.push(e.message))

// 测试用知识库名
const KB = 'R7-verify-' + Date.now().toString(36)
let kbId = null

try {
  // ---- 登录 ----
  await page.goto(base + '/#/login', { waitUntil: 'networkidle' })
  await page.fill('[data-testid="login-username"]', USERNAME)
  await page.fill('[data-testid="login-password"]', PASSWORD)
  await page.click('[data-testid="login-submit"]')
  await page.waitForSelector('[data-testid="kb-list-page"]', { timeout: 20000 })

  // ---- 建一个测试库 ----
  console.log('① 准备测试知识库')
  await page.click('[data-testid="kb-create-open"]')
  await page.waitForSelector('[data-testid="kb-create-form"]', { timeout: 8000 })
  await page.fill('[data-testid="kb-create-name"]', KB)
  await page.click('[data-testid="kb-create-submit"]')
  await page.waitForSelector(`[data-testid="kb-card"]:has-text("${KB}")`, { timeout: 15000 })
  const token = await page.evaluate(() => localStorage.getItem('nexus.token'))
  const created = await fetch(`${apiBase}/api/knowledge-bases?pageNum=1&pageSize=100`, {
    headers: { Authorization: 'Bearer ' + token }
  }).then((r) => r.json())
  kbId = (created?.data?.list ?? []).find((k) => k.name === KB)?.id
  check(!!kbId, '测试知识库已创建', String(kbId))

  // ---- 进详情页 ----
  await page.click(`[data-testid="kb-card"]:has-text("${KB}") [data-testid="kb-card-link"]`)
  await page.waitForSelector('[data-testid="kb-detail-page"]', { timeout: 15000 })
  await page
    .waitForSelector('[data-testid="doc-list"], [data-testid="state-empty"]', { timeout: 20000 })
    .catch(() => {})
  check(true, '进入知识库详情页')

  console.log('② 空态（还没导入）')
  const emptyDocs = await page.locator('[data-testid="state-empty"]').count()
  check(emptyDocs === 1, '新库显示「还没有资料」空态', `${emptyDocs} 个`)

  console.log('③ 三个页签')
  for (const label of ['资料', '问答', '高级']) {
    const n = await page.locator(`[role="tab"]:has-text("${label}")`).count()
    check(n === 1, `页签「${label}」存在`)
  }

  console.log('④ 取消文件选择不卡死（任务书 §19）')
  // 直接给 input 派发一个空 change（模拟用户点了"取消"）
  await page.evaluate(() => {
    const input = document.querySelector('[data-testid="kb-file-picker"]')
    const dt = new DataTransfer()
    input.files = dt.files
    input.dispatchEvent(new Event('change', { bubbles: true }))
  })
  await page.waitForTimeout(1200)
  const noDialog = (await page.locator('[data-testid="kb-import-confirm"]').count()) === 0
  const noLoading = (await page.locator('[aria-busy="true"]').count()) === 0
  check(noDialog, '取消选择后没有弹出确认框')
  check(noLoading, '取消选择后没有卡在 loading')

  console.log('⑤ 真实上传一个 md 文件')
  // 用 setInputFiles 直接给 input 赋文件（等价于用户在文件对话框里选了）
  await page.setInputFiles('[data-testid="kb-file-picker"]', [
    {
      name: 'R7-测试笔记.md',
      mimeType: 'text/markdown',
      buffer: Buffer.from('# 测试\n\n这是 R7 验证用的文件，用于确认导入链路。\n', 'utf-8')
    }
  ])
  await page.waitForSelector('[data-testid="kb-import-confirm"]', { timeout: 8000 })
  const confirmText = await page.locator('[data-testid="kb-import-confirm"]').innerText()
  check(/1\s*个/.test(confirmText), '确认框显示选中文件数', confirmText.trim())

  await page.click('[data-testid="kb-import-confirm"]')
  // 轮询等导入完成（后端要切片+向量化，可能几秒）
  await page
    .waitForSelector('[data-testid="import-result"]', { timeout: 30000 })
    .catch(() => {})
  const hasResult = (await page.locator('[data-testid="import-result"]').count()) === 1
  check(hasResult, '导入结果显示在主界面（常驻，不是 toast）')

  // 等文档出现
  await page
    .waitForSelector('[data-testid="doc-item"]', { timeout: 40000 })
    .catch(() => {})
  const docItems = await page.locator('[data-testid="doc-item"]').count()
  check(docItems >= 1, '文档出现在资料列表', `${docItems} 篇`)

  console.log('⑥ 逐文件状态（任务书 §20）')
  const importItems = await page.locator('[data-testid="import-item"]').count()
  check(importItems >= 1, '导入结果里有逐文件明细', `${importItems} 条`)

  console.log('⑦ 文档状态是中文且不含英文枚举（任务书 §17）')
  const statusTexts = await page.locator('[data-testid="doc-status"]').allInnerTexts()
  const allText = statusTexts.join(' | ')
  check(
    statusTexts.every((t) => /处理中|已完成|失败|已跳过|等待处理/.test(t)),
    '文档状态是中文',
    allText
  )
  check(
    !/READY|PENDING|PARSING|FAILED/.test(allText),
    '状态里没有裸英文枚举',
    allText
  )

  console.log('⑧ 文件大小单位是 KB（后端字段是 fileSizeKb）')
  const docText = await page.locator('[data-testid="doc-item"]').first().innerText()
  const hasSize = /\d+(\.\d+)?\s*(B|KB|MB)/.test(docText)
  check(hasSize, '显示了带单位的文件大小', docText.replace(/\n/g, ' ').slice(0, 60))
  // 小于 1KB 的文件不应显示成 "0 B" 或明显偏大的值
  const sizeMatch = docText.match(/([\d.]+)\s*(B|KB|MB)/)
  if (sizeMatch) {
    const n = Number(sizeMatch[1])
    const unit = sizeMatch[2]
    const asBytes = unit === 'B' ? n : unit === 'KB' ? n * 1024 : n * 1024 * 1024
    check(asBytes < 200, '大小与实际文件相符（不是把 KB 当字节）', `${n}${unit} ≈ ${asBytes}B`)
  }

  console.log('⑨ 无 L4 技术词')
  const bodyText = await page.locator('[data-testid="kb-detail-page"]').innerText()
  const leaked = ['embeddingDim', 'chunkSize', 'pgvector', 'BM25', 'RRF'].filter((w) =>
    bodyText.includes(w)
  )
  check(leaked.length === 0, '资料页无 L4 技术词', leaked.join(','))

  console.log('⑩ 错误检查')
  const realErrors = consoleErrors.filter((t) => !/401|favicon/i.test(t))
  check(realErrors.length === 0, 'console error = 0', realErrors.slice(0, 2).join(' | '))
  check(pageErrors.length === 0, 'page error = 0', pageErrors.slice(0, 2).join(' | '))
} catch (e) {
  fail.push('探针中断：' + e.message)
  console.log('  FAIL  探针中断 — ' + e.message)
} finally {
  // 清理：删掉测试知识库（连带文档与向量）
  try {
    const t = await page.evaluate(() => localStorage.getItem('nexus.token'))
    if (t) {
      const r = await fetch(`${apiBase}/api/knowledge-bases?pageNum=1&pageSize=100`, {
        headers: { Authorization: 'Bearer ' + t }
      }).then((x) => x.json())
      for (const k of r?.data?.list ?? []) {
        if (String(k.name).startsWith('R7-verify-')) {
          await fetch(`${apiBase}/api/knowledge-bases/${k.id}`, {
            method: 'DELETE',
            headers: { Authorization: 'Bearer ' + t }
          })
          console.log('  （已清理 ' + k.name + '）')
        }
      }
    }
  } catch {
    // 清理失败只报告
  }
  await page.screenshot({ path: 'r7-import.png' }).catch(() => {})
  await browser.close()
}

console.log(`\n结论：PASS ${pass.length} / FAIL ${fail.length}`)
if (fail.length) {
  console.log('失败项：')
  for (const f of fail) console.log('  - ' + f)
  process.exit(1)
}
console.log('PASS R7 导入资料')
