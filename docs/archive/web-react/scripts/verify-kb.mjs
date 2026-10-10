/**
 * R6 知识库列表页验证。
 *
 * 覆盖：加载 / 卡片字段 / **L4 信息不出现** / 空态与错误态分离 /
 *       创建（含校验）/ 删除（含危险确认）/ 清理测试数据。
 *
 * 真实后端（docker compose 起着），会**创建并删除**一个测试知识库。
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

// 测试数据名——结束时按它清理
const TEST_NAME = 'R6-verify-' + Date.now().toString(36)

try {
  // ---- 登录（hash 路由下必须用 reload，不能用 goto）----
  await page.goto(base + '/#/login', { waitUntil: 'networkidle' })
  await page.fill('[data-testid="login-username"]', USERNAME)
  await page.fill('[data-testid="login-password"]', PASSWORD)
  await page.click('[data-testid="login-submit"]')
  await page.waitForSelector('[data-testid="kb-list-page"]', { timeout: 20000 })
  /*
   * 等列表**加载完成**再断言，不能只看页面出现。
   * 第一版只 waitForSelector 页面容器就立刻检查，那时数据还在路上，
   * 于是"卡片 0 张 / 空态 0"——那不是空态，是**还在加载**。
   */
  await page
    .waitForSelector('[data-testid="kb-card"], [data-testid="state-empty"]', { timeout: 20000 })
    .catch(() => {})

  console.log('① 页面与列表加载')
  check(true, '登录后进入知识库列表页')
  const cards = await page.locator('[data-testid="kb-card"]').count()
  const empty = await page.locator('[data-testid="state-empty"]').count()
  const errBox = await page.locator('[data-testid="state-error"]').count()
  check(cards > 0 || empty === 1, '列表加载出结果', `卡片 ${cards} 张 / 空态 ${empty}`)
  check(errBox === 0, '没有错误态（说明确实成功了）')

  console.log('② 卡片字段（Phase B §15：L1+L2）')
  if (cards > 0) {
    const first = page.locator('[data-testid="kb-card"]').first()
    const hasName = (await first.locator('h2').innerText()).trim().length > 0
    check(hasName, '卡片显示名称')
    const statusText = await first.locator('[data-testid="kb-card-status"]').innerText()
    check(
      /启用中|已停用/.test(statusText),
      '卡片显示启用状态（ENABLED/DISABLED）',
      statusText.trim()
    )
    const docs = await first.locator('[data-testid="kb-card-docs"]').innerText()
    const chunks = await first.locator('[data-testid="kb-card-chunks"]').innerText()
    const tokens = await first.locator('[data-testid="kb-card-tokens"]').innerText()
    check(
      docs.trim() !== '' && chunks.trim() !== '' && tokens.trim() !== '',
      '卡片显示文档数 / 切片数 / Token',
      `${docs.trim()} / ${chunks.trim()} / ${tokens.trim()}`
    )
  }

  console.log('③ L4 开发信息**不应出现**（Phase B §12）')
  const bodyText = await page.locator('[data-testid="kb-list-page"]').innerText()
  const forbidden = [
    'embeddingDim',
    'Embedding',
    'chunkSize',
    'chunkOverlap',
    'pgvector',
    'BM25',
    'RRF',
    '向量维度',
    'ES',
    'Retriever'
  ]
  const leaked = forbidden.filter((w) => bodyText.includes(w))
  check(leaked.length === 0, '页面无 L4 技术词', leaked.length ? '泄漏: ' + leaked.join(',') : '')

  console.log('④ 创建知识库')
  await page.click('[data-testid="kb-create-open"]')
  await page.waitForSelector('[data-testid="kb-create-form"]', { timeout: 8000 })
  // 空名提交应被拦
  await page.click('[data-testid="kb-create-submit"]')
  await page.waitForTimeout(400)
  const stillOpen = (await page.locator('[data-testid="kb-create-form"]').count()) === 1
  check(stillOpen, '空名称被拦下（弹窗未关闭）')

  await page.fill('[data-testid="kb-create-name"]', TEST_NAME)
  await page.fill('[data-testid="kb-create-description"]', 'R6 验证用，会被删除')
  await page.click('[data-testid="kb-create-submit"]')
  await page.waitForTimeout(2500)
  const created = await page.locator(`[data-testid="kb-card"]:has-text("${TEST_NAME}")`).count()
  check(created === 1, '新知识库出现在列表里', `${created} 张`)

  // 后端也确认一下真的建了
  const token = await page.evaluate(() => localStorage.getItem('nexus.token'))
  const apiList = await fetch(`${apiBase}/api/knowledge-bases?pageNum=1&pageSize=100`, {
    headers: { Authorization: 'Bearer ' + token }
  }).then((r) => r.json())
  const inApi = (apiList?.data?.list ?? []).filter((k) => k.name === TEST_NAME)
  check(inApi.length === 1, '后端确实创建成功', `${inApi.length} 条`)
  const kbId = inApi[0]?.id

  console.log('⑤ 删除（危险确认）')
  if (kbId) {
    await page.click(`[data-testid="kb-delete-${kbId}"]`)
    await page.waitForSelector('[data-testid="confirm-body"]', { timeout: 8000 })
    const desc = await page.locator('[data-testid="confirm-body"]').innerText()
    check(desc.includes('不可撤销'), '确认框写明不可撤销', desc.trim().slice(0, 40))
    check(
      /\d+\s*篇文档/.test(desc) || desc.includes('文档'),
      '确认框写明会失去什么',
      desc.trim().slice(0, 50)
    )

    // 先取消一次：东西不该被删
    await page.click('[data-testid="confirm-cancel"]')
    await page.waitForTimeout(800)
    const stillThere = (await page.locator(`[data-testid="kb-card"]:has-text("${TEST_NAME}")`).count()) === 1
    check(stillThere, '取消后知识库仍在（没有误删）')

    // 再真删
    await page.click(`[data-testid="kb-delete-${kbId}"]`)
    await page.waitForSelector('[data-testid="confirm-body"]', { timeout: 8000 })
    await page.click('[data-testid="confirm-submit"]')
    await page.waitForTimeout(2500)
    const gone = (await page.locator(`[data-testid="kb-card"]:has-text("${TEST_NAME}")`).count()) === 0
    check(gone, '确认后知识库从列表消失')

    const after = await fetch(`${apiBase}/api/knowledge-bases?pageNum=1&pageSize=100`, {
      headers: { Authorization: 'Bearer ' + token }
    }).then((r) => r.json())
    const left = (after?.data?.list ?? []).filter((k) => k.name === TEST_NAME)
    check(left.length === 0, '后端也已删除（测试数据已清理）', `${left.length} 条残留`)
  }

  console.log('⑥ 错误检查')
  const realErrors = consoleErrors.filter((t) => !/401|favicon/i.test(t))
  check(realErrors.length === 0, 'console error = 0', realErrors.slice(0, 2).join(' | '))
  check(pageErrors.length === 0, 'page error = 0', pageErrors.slice(0, 2).join(' | '))
} catch (e) {
  fail.push('探针中断：' + e.message)
  console.log('  FAIL  探针中断 — ' + e.message)

  // 兜底：无论怎么失败，都别把测试数据留在后端
  try {
    const t = await page.evaluate(() => localStorage.getItem('nexus.token'))
    if (t) {
      const r = await fetch(`${apiBase}/api/knowledge-bases?pageNum=1&pageSize=100`, {
        headers: { Authorization: 'Bearer ' + t }
      }).then((x) => x.json())
      for (const k of r?.data?.list ?? []) {
        if (String(k.name).startsWith('R6-verify-')) {
          await fetch(`${apiBase}/api/knowledge-bases/${k.id}`, {
            method: 'DELETE',
            headers: { Authorization: 'Bearer ' + t }
          })
          console.log('  （已清理残留 ' + k.name + '）')
        }
      }
    }
  } catch {
    // 清理失败就只报告
  }
} finally {
  await page.screenshot({ path: 'r6-kb-list.png' }).catch(() => {})
  await browser.close()
}

console.log(`\n结论：PASS ${pass.length} / FAIL ${fail.length}`)
if (fail.length) {
  console.log('失败项：')
  for (const f of fail) console.log('  - ' + f)
  process.exit(1)
}
console.log('PASS R6 知识库列表页')
