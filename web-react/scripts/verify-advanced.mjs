/**
 * R10 高级区验证（只读为主，不做危险操作）。
 *
 * 覆盖：高级页签 / 索引状态卡 / 检索试验（三种模式）/ 切片列表含向量与索引状态 /
 *       L4 分层（高级区允许分数，但资料页不允许）。
 *
 * **不点「重建索引」**——它会重写全部向量，耗时且影响真实数据。
 */
import { chromium } from 'playwright-core'

const base = process.env.R2_BASE ?? 'http://localhost:5174'
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

try {
  await page.goto(base + '/#/login', { waitUntil: 'networkidle' })
  await page.fill('[data-testid="login-username"]', USERNAME)
  await page.fill('[data-testid="login-password"]', PASSWORD)
  await page.click('[data-testid="login-submit"]')
  await page.waitForSelector('[data-testid="kb-list-page"]', { timeout: 20000 })

  // 找一个**有文档**的知识库（否则高级区是空的，测不到切片）
  const token = await page.evaluate(() => localStorage.getItem('nexus.token'))
  const list = await page
    .evaluate(async () => {
      const t = localStorage.getItem('nexus.token')
      const r = await fetch('/api/knowledge-bases?pageNum=1&pageSize=100', {
        headers: { Authorization: 'Bearer ' + t }
      })
      return r.json()
    })
    .catch(() => null)
  void token
  const withDocs = (list?.data?.list ?? []).find((k) => (k.docCount ?? 0) > 0)
  check(!!withDocs, '找到一个有文档的知识库用于测试', withDocs?.name ?? String(list?.data?.list?.length))

  if (!withDocs) {
    throw new Error('没有含文档的知识库，无法验证高级区')
  }

  await page.goto(`${base}/#/kb/${withDocs.id}`, { waitUntil: 'networkidle' })
  await page.waitForSelector('[data-testid="kb-detail-page"]', { timeout: 15000 })

  console.log('① 高级页签')
  await page.click('[role="tab"]:has-text("高级")')
  await page.waitForSelector('[data-testid="kb-advanced"]', { timeout: 15000 })
  check(true, '高级区已渲染')
  check((await page.locator('[data-testid="kb-advanced-index"]').count()) === 1, '索引状态卡存在')
  check((await page.locator('[data-testid="kb-advanced-search"]').count()) === 1, '检索试验卡存在')
  check((await page.locator('[data-testid="kb-advanced-chunks"]').count()) === 1, '切片卡存在')

  console.log('② 检索模式切换（排查召回的关键开关）')
  for (const [m, label] of [
    ['vector', '仅向量'],
    ['es', '仅关键词'],
    ['hybrid', '混合']
  ]) {
    await page.click(`[data-testid="kb-mode-${m}"]`)
    await page.waitForTimeout(300)
    const shown = await page.locator('[data-testid="kb-search-mode"]').innerText()
    check(shown.trim() === m, `切到「${label}」模式`, shown.trim())
  }

  console.log('③ 检索试验（真实调检索接口）')
  await page.click('[data-testid="kb-mode-vector"]')
  await page.fill('[data-testid="kb-search-input"]', '缓存')
  await page.click('[data-testid="kb-search-run"]')
  const gotResult = await page
    .waitForSelector('[data-testid="kb-search-hits"], [data-testid="kb-search-empty"]', {
      timeout: 60000
    })
    .then(() => true)
    .catch(() => false)
  check(gotResult, '检索返回了结果（命中列表或明确的空结果）')
  if (gotResult) {
    const hits = await page.locator('[data-testid="kb-search-hit"]').count()
    const empty = await page.locator('[data-testid="kb-search-empty"]').count()
    check(hits > 0 || empty === 1, '结果明确（命中或空）', `${hits} 命中 / 空标记 ${empty}`)
    if (hits > 0) {
      const src = await page.locator('[data-testid="kb-hit-source"]').first().innerText()
      check(src.trim().length > 0, '命中带来源标记（vector/es/hybrid）', src.trim())
      const hitText = await page.locator('[data-testid="kb-search-hit"]').first().innerText()
      check(!/undefined|NaN/.test(hitText), '命中内容无 undefined / NaN')
    }
  }

  console.log('④ 切片列表（含向量 / 索引状态）')
  const hasChunks = (await page.locator('[data-testid="kb-chunk"]').count()) > 0
  check(hasChunks, '切片列表有内容', `${await page.locator('[data-testid="kb-chunk"]').count()} 个`)
  if (hasChunks) {
    const first = await page.locator('[data-testid="kb-chunk"]').first().innerText()
    check(!/undefined|NaN/.test(first), '切片内容无 undefined / NaN')
    // 状态徽标：非 null 才显示（正常时应为 0 个）
    const vBadges = await page.locator('[data-testid="kb-chunk-vector-status"]').count()
    const eBadges = await page.locator('[data-testid="kb-chunk-es-status"]').count()
    check(
      vBadges === 0 && eBadges === 0,
      '已导入的切片向量/索引均正常（无失败徽标）',
      `向量徽标 ${vBadges} / 索引徽标 ${eBadges}`
    )
  }

  console.log('⑤ 危险操作存在但本轮不执行')
  check((await page.locator('[data-testid="kb-recreate-index"]').count()) === 1, '重建索引按钮存在')

  console.log('⑥ 资料页不含 L4（高级区才有分数）')
  await page.click('[role="tab"]:has-text("资料")')
  await page.waitForTimeout(800)
  const docsText = await page.locator('[data-testid="kb-detail-page"]').innerText()
  check(!/similarity|score|命中来源|混合检索/.test(docsText), '资料页无检索调试信息')

  console.log('⑦ 错误检查')
  const realErrors = consoleErrors.filter((t) => !/401|favicon|AbortError/i.test(t))
  check(realErrors.length === 0, 'console error = 0', realErrors.slice(0, 2).join(' | '))
  check(pageErrors.length === 0, 'page error = 0', pageErrors.slice(0, 2).join(' | '))
} catch (e) {
  fail.push('探针中断：' + e.message)
  console.log('  FAIL  探针中断 — ' + e.message)
} finally {
  await page.screenshot({ path: 'r10-advanced.png' }).catch(() => {})
  await browser.close()
}

console.log(`\n结论：PASS ${pass.length} / FAIL ${fail.length}`)
if (fail.length) {
  console.log('失败项：')
  for (const f of fail) console.log('  - ' + f)
  process.exit(1)
}
console.log('PASS R10 高级区')
