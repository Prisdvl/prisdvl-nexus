/**
 * 文档关联 / 相关推荐面板验证（R11 补齐项）。
 *
 * 后端早已提供 `/documents/{id}/links` 与 `/related`，前端此前完全没接。
 * 验证重点——三种链接状态**必须区分呈现**，混为一谈就是骗用户：
 *   已解析（内部） / 未解析（待创建的笔记） / 外链（不参与内部关联图）
 * 以及相关推荐**要给出理由**（只说"相关"没有说服力）。
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
const docs = await (
  await fetch(`${apiBase}/api/knowledge-bases/${kb.id}/documents?pageNum=1&pageSize=100`, { headers: auth })
).json()
console.log(`知识库「${kb.name}」文档 ${docs.data.list.length} 篇`)

/*
 * 挑选对照文档时不能只看"有反向链接"就 break —— 那样可能选到一篇**没有任何出链**的文档，
 * 于是"待创建/已解析"这两条断言在空数据上"通过"（假通过）。实测踩到：第一版选中的是
 * `java_jvm_jvm 笔记.md`（out=0/dangling=0），第 ⑤ 项等于什么都没验。
 * 正确做法是**按覆盖度打分**，优先选能同时覆盖 出链/待创建/反向链接 的那一篇。
 */
const scored = []
for (const d of docs.data.list) {
  const l = await (
    await fetch(`${apiBase}/api/knowledge-bases/${kb.id}/documents/${d.id}/links`, { headers: auth })
  ).json()
  const out = l.data.outlinks ?? []
  const back = l.data.backlinks ?? []
  const dangling = out.filter((x) => !x.external && !x.resolvedDocumentId).length
  const resolved = out.filter((x) => x.resolvedDocumentId).length
  // 覆盖度：待创建 + 已解析 + 反向链接 各算一分
  scored.push({
    doc: d,
    out,
    back,
    dangling,
    resolved,
    coverage: (dangling > 0 ? 1 : 0) + (resolved > 0 ? 1 : 0) + (back.length > 0 ? 1 : 0)
  })
}
scored.sort((a, b) => b.coverage - a.coverage)
const best = scored[0]
if (!best || best.coverage === 0) {
  console.log('（当前数据里没有任何文档具备关联关系，本脚本需要样本）')
  process.exit(1)
}
const target = best.doc
const expectedBack = best.back.length
const expectedDangling = best.dangling
const expectedOut = best.out.length
console.log(
  `对照文档：${target.sourcePath || target.fileName}` +
    `（后端：出链 ${expectedOut}（待创建 ${expectedDangling}）、反向链接 ${expectedBack}、覆盖度 ${best.coverage}/3）`
)
if (best.coverage < 3) {
  console.log('提示：当前数据未覆盖全部三种链接状态，相关断言会弱一些')
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await (await browser.newContext({ viewport: { width: 1440, height: 980 } })).newPage()
const consoleErrors = []
page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text().slice(0, 120)))

try {
  console.log('① 登录并进入知识库详情')
  await page.goto(base + '/#/login', { waitUntil: 'domcontentloaded' })
  await page.fill('[data-testid="login-username"]', USERNAME)
  await page.fill('[data-testid="login-password"]', PASSWORD)
  await page.click('[data-testid="login-submit"]')
  await page.waitForSelector('[data-testid="kb-card"]', { timeout: 20000 })
  await page.locator('[data-testid="kb-card"]').first().click()
  await page.waitForSelector('[data-testid="kb-detail-page"]', { timeout: 15000 })

  console.log('② 切到高级区，找到关联面板')
  await page.click('text=高级')
  await page.waitForSelector('[data-testid="kb-links-panel"]', { timeout: 10000 })
  check(true, '关联面板已渲染（后端接口此前未被前端调用）')

  const select = page.locator('[data-testid="kb-links-doc-select"]')
  check((await select.count()) === 1, '有关联查看的文档选择器')

  console.log('③ 选中覆盖度最高的那篇，与后端数字对照')
  await page.selectOption('[data-testid="kb-links-doc-select"]', { label: target.sourcePath || target.fileName })
  await page.waitForTimeout(1800)

  const backCount = await page.locator('[data-testid="kb-link-back"]').count()
  check(
    backCount === expectedBack,
    '反向链接数量与后端一致',
    `界面 ${backCount} / 后端 ${expectedBack}`
  )

  // 相关推荐的数量现查（与面板里那次请求同源），避免用过期变量
  const relatedApi = await (
    await fetch(`${apiBase}/api/knowledge-bases/${kb.id}/documents/${target.id}/related`, { headers: auth })
  ).json()
  const expectedRelated = (relatedApi.data ?? []).length
  const relatedCount = await page.locator('[data-testid="kb-related-item"]').count()
  check(
    relatedCount === expectedRelated,
    '相关推荐数量与后端一致',
    `界面 ${relatedCount} / 后端 ${expectedRelated}`
  )

  console.log('④ 相关推荐必须给出理由（只说"相关"没有说服力）')
  const firstReason = await page
    .locator('[data-testid="kb-related-item"]')
    .first()
    .innerText()
    .catch(() => '')
  check(
    /互相链接|共同标签/.test(firstReason),
    '推荐项写明了理由',
    firstReason.replace(/\s+/g, ' ').slice(0, 50)
  )
  // 分数属于实现细节（L4），不该出现在界面上
  check(!/\b\d{2,}\b/.test(firstReason.replace(/共同标签[^·]*/g, '')), '推荐项不露排序分')

  console.log('⑤ 三种链接状态区分呈现')
  const outCount = await page.locator('[data-testid="kb-link-out"]').count()
  const danglingCount = await page.locator('[data-testid="kb-link-dangling"]').count()
  check(
    outCount === expectedOut,
    '出链数量与后端一致',
    `界面 ${outCount} / 后端 ${expectedOut}`
  )
  check(
    danglingCount === expectedDangling,
    '未解析的引用被标注为「待创建」（不与已解析的混在一起）',
    `界面 ${danglingCount} / 后端 ${expectedDangling}`
  )
  // 有已解析的出链时，必须存在"非待创建"的行（说明两种状态确实分开渲染了）
  if (best.resolved > 0) {
    check(
      outCount - danglingCount === best.resolved,
      '已解析的出链不带"待创建"标注',
      `已解析 ${outCount - danglingCount} / 后端 ${best.resolved}`
    )
  }

  console.log('⑥ 换个文档，面板应重新加载')
  const otherDoc = docs.data.list.find((d) => d.id !== target.id)
  if (otherDoc) {
    await select.selectOption({ label: otherDoc.sourcePath || otherDoc.fileName })
    await page.waitForTimeout(1500)
    const otherBack = await page.locator('[data-testid="kb-link-back"]').count()
    const otherApi = await (
      await fetch(`${apiBase}/api/knowledge-bases/${kb.id}/documents/${otherDoc.id}/links`, { headers: auth })
    ).json()
    check(
      otherBack === (otherApi.data.backlinks?.length ?? 0),
      '切换文档后面板重新加载且与后端一致',
      `${otherDoc.sourcePath}: 界面 ${otherBack} / 后端 ${otherApi.data.backlinks?.length ?? 0}`
    )
  }

  console.log('⑦ 错误检查')
  const real = consoleErrors.filter((t) => !/401|favicon|AbortError/i.test(t))
  check(real.length === 0, 'console error = 0', real.slice(0, 2).join(' | '))

  await page.screenshot({ path: 'D:/develop/Prisma/web-react/r11-links.png' })
  console.log('截图: web-react/r11-links.png')
} catch (e) {
  fail++
  console.log('FAIL  探针中断 — ' + e.message)
  await page.screenshot({ path: 'D:/develop/Prisma/web-react/r11-links-fail.png' }).catch(() => {})
} finally {
  await browser.close()
}

console.log(`\n结论：PASS ${pass} / FAIL ${fail}`)
process.exit(fail === 0 ? 0 : 1)
