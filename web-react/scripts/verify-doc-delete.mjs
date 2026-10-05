/**
 * 文档删除验证（R11 补齐项）。
 *
 * 重点不是"按钮能点"，而是**危险操作的边界**：
 *   1. 取消后文档仍在（不误删）；
 *   2. 确认框写清后果（连带切片/向量/索引/磁盘原文）；
 *   3. 确认后**后端真的删了**（用接口核对，不能只看界面消失——
 *      界面靠本地 filter 也能"看起来删掉了"，但刷新就回来了）；
 *   4. 删除后列表刷新（本地 filter 与重新拉取的区别）。
 */
import { chromium } from 'playwright-core'

const base = process.env.R2_BASE ?? 'http://localhost:5174'
const apiBase = process.env.API_BASE ?? 'http://localhost:8088'
const USERNAME = process.env.DEMO_USER ?? 'demo'
const PASSWORD = process.env.DEMO_PASSWORD ?? 'Demo@12345'

let pass = 0
let fail = 0
const check = (ok, name, detail = '') => {
  if (ok) {
    pass++
    console.log(`PASS  ${name}${detail ? ' — ' + detail : ''}`)
  } else {
    fail++
    console.log(`FAIL  ${name}${detail ? ' — ' + detail : ''}`)
  }
}

const login = await fetch(apiBase + '/api/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: USERNAME, password: PASSWORD })
})
const token = (await login.json()).data.accessToken
const auth = { Authorization: 'Bearer ' + token }

const kbs = await (await fetch(apiBase + '/api/knowledge-bases?pageNum=1&pageSize=50', { headers: auth })).json()
const kb = kbs.data.list[0]

const docCountViaApi = async () => {
  const r = await fetch(`${apiBase}/api/knowledge-bases/${kb.id}/documents?pageNum=1&pageSize=100`, { headers: auth })
  return (await r.json()).data.list.length
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await (await browser.newContext({ viewport: { width: 1440, height: 980 } })).newPage()
const consoleErrors = []
page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text().slice(0, 120)))

try {
  console.log('① 登录并进入知识库')
  await page.goto(base + '/#/login', { waitUntil: 'domcontentloaded' })
  await page.fill('[data-testid="login-username"]', USERNAME)
  await page.fill('[data-testid="login-password"]', PASSWORD)
  await page.click('[data-testid="login-submit"]')
  await page.waitForSelector('[data-testid="kb-card"]', { timeout: 20000 })

  // 为保证销毁性验证不影响真实数据，先通过 UI 上传一个临时文件
  console.log('② 通过 UI 导入一个临时文件（作为删除对象）')
  await page.locator('[data-testid="kb-card"]').first().click()
  await page.waitForSelector('[data-testid="kb-detail-page"]', { timeout: 15000 })
  const before = await docCountViaApi()

  const marker = 'delcheck' + Math.random().toString(36).slice(2, 7)
  const fileInput = page.locator('input[type="file"]').first()
  /*
   * 导入是**两步**：选文件 → 确认框 → 提交。
   * 第一版只 setInputFiles 就等结果，于是"文档数 +1"失败——
   * 不是导入坏了，是我漏了确认这一步（verify-import.mjs 里有 kb-import-confirm）。
   */
  await page.setInputFiles('[data-testid="kb-file-picker"]', [
    {
      name: `${marker}.md`,
      mimeType: 'text/markdown',
      buffer: Buffer.from(`# ${marker}\n\n这是删除验证用的临时内容。\n`)
    }
  ])
  await page.waitForSelector('[data-testid="kb-import-confirm"]', { timeout: 10000 })
  await page.click('[data-testid="kb-import-confirm"]')
  await page.waitForTimeout(6000)
  const afterUpload = await docCountViaApi()
  check(afterUpload === before + 1, '临时文件导入成功（后端文档数 +1）', `${before} → ${afterUpload}`)

  console.log('③ 删除：先取消，确认不误删')
  const targetRow = page.locator('[data-testid="doc-item"]').filter({ hasText: marker }).first()
  check((await targetRow.count()) === 1, '列表里找到了刚导入的那一篇')
  await targetRow.locator('[data-testid="kb-doc-delete"]').click()
  await page.waitForSelector('[data-testid="kb-doc-delete-confirm"]', { timeout: 8000 })
  const bodyText = await page.locator('[data-testid="kb-doc-delete-target"]').innerText().catch(() => '')
  check(bodyText.includes(marker), '确认框显示删除目标（避免删错）', bodyText.slice(0, 40))
  const dialogText = await page.locator('body').innerText()
  check(/不可撤销/.test(dialogText), '确认框写明了不可撤销')

  await page.click('[data-testid="kb-doc-delete-cancel"]')
  await page.waitForTimeout(1200)
  const afterCancel = await docCountViaApi()
  check(afterCancel === afterUpload, '取消后文档仍在（后端数未变）', `${afterCancel}`)

  console.log('④ 删除：确认，验证后端真删了')
  await targetRow.locator('[data-testid="kb-doc-delete"]').click()
  await page.waitForSelector('[data-testid="kb-doc-delete-confirm"]', { timeout: 8000 })
  await page.click('[data-testid="kb-doc-delete-confirm"]')
  await page.waitForTimeout(3000)

  const afterDelete = await docCountViaApi()
  check(afterDelete === afterUpload - 1, '后端确实删除了该文档', `${afterUpload} → ${afterDelete}`)

  const stillInUi = await page.locator('[data-testid="doc-item"]').filter({ hasText: marker }).count()
  check(stillInUi === 0, '列表里已消失')

  // 刷新后再看：本地 filter 的假象会在这里暴露
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-testid="kb-detail-page"]', { timeout: 15000 })
  await page.waitForTimeout(1500)
  const afterReload = await page.locator('[data-testid="doc-item"]').filter({ hasText: marker }).count()
  check(afterReload === 0, '刷新后依然不在（不是本地 filter 的假象）')

  console.log('⑤ 错误检查')
  const real = consoleErrors.filter((t) => !/401|favicon|AbortError/i.test(t))
  check(real.length === 0, 'console error = 0', real.slice(0, 2).join(' | '))

  await page.screenshot({ path: 'D:/develop/Prisma/web-react/r11-doc-delete.png' })
  console.log('截图: web-react/r11-doc-delete.png')
} catch (e) {
  fail++
  console.log('FAIL  探针中断 — ' + e.message)
  await page.screenshot({ path: 'D:/develop/Prisma/web-react/r11-doc-delete-fail.png' }).catch(() => {})
} finally {
  /*
   * 兜底清理：本脚本会**导入**一个临时文件，如果中途失败（断言挂了、页面卡住），
   * 那个文件就会留在库里——而它没有向量/索引，会导致 **verify:advanced** 的
   * "切片向量/索引均正常"断言失败（实测踩到：残留 delchecks10zg.md 让 advanced 从 19/0 变 18/1）。
   * 这就是"测试之间的数据污染"：一个脚本的残留把另一个脚本判红，而产品本身没问题。
   * 收尾时按 marker 前缀扫一遍删掉，无论本轮成功与否。
   */
  try {
    const list = await (
      await fetch(`${apiBase}/api/knowledge-bases/${kb.id}/documents?pageNum=1&pageSize=100`, { headers: auth })
    ).json()
    const leftovers = (list.data?.list ?? []).filter((d) =>
      String(d.sourcePath || d.fileName || '').includes('delcheck')
    )
    for (const d of leftovers) {
      await fetch(`${apiBase}/api/knowledge-bases/${kb.id}/documents/${d.id}`, {
        method: 'DELETE',
        headers: auth
      })
      console.log(`（兜底清理）删除了残留临时文件 ${d.sourcePath || d.fileName}`)
    }
  } catch {
    // 清理失败不该改变脚本结论
  }
  await browser.close()
}

console.log(`\n结论：PASS ${pass} / FAIL ${fail}`)
process.exit(fail === 0 ? 0 : 1)
