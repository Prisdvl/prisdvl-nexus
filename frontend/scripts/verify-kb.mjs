// 知识库列表 / 文档列表 / 分块管理 专项验证（mock 模式）
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { mkdirSync } from 'node:fs'
import { chromium } from 'playwright-core'

const BASE = 'http://localhost:5175'
// 截图落点与其余 mock 脚本一致：CI 会设 QA_SHOTS_OUT（ci.yml:97），未设时落到系统临时目录。
// 原来写死相对路径 → 落在 CWD，CI 失败时 diagnostics 里一张截图都没有。
const OUT = process.env.QA_SHOTS_OUT ?? join(tmpdir(), 'prisma-recover-qa-shots')
mkdirSync(OUT, { recursive: true })
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.addInitScript(() => localStorage.setItem('nx-proto-tour-done', '1'))
const issues = []
const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`)
  if (!ok) issues.push(name)
}
page.on('pageerror', (e) => issues.push(`pageerror: ${String(e.message).slice(0, 160)}`))
page.on('console', (m) => { if (m.type() === 'error') issues.push(`console.error: ${m.text().slice(0, 160)}`) })

/*
 * 数据到位前别用固定 sleep 再 count()：count() 自己不等待，慢 runner 上会读到"还没渲染"。
 * 这里的每个等待都只是「等到条件成立」，超时照常往下断言 —— 真的没渲染依然判 FAIL。
 */
const settle = (fn, arg = null, timeout = 10000) => page.waitForFunction(fn, arg, { timeout }).catch(() => {})
const rowCount = (min) => settle((n) => document.querySelectorAll('.kb-table tbody tr').length >= n, min)
const rowEquals = (n) => settle((want) => document.querySelectorAll('.kb-table tbody tr').length === want, n)

await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle' })
await page.waitForSelector('.auth-card')
await page.locator('#li-u').fill('qa-runner')
await page.locator('#li-p').fill('qa-runner-password')
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
await page.waitForSelector('.shell', { timeout: 10000 })

/* ---------- 知识库列表 ---------- */
await page.goto(`${BASE}/#/app/kb`, { waitUntil: 'networkidle' })
await rowCount(5)
check('知识库统计卡 3 个', (await page.locator('.kb-stats .stat-card').count()) === 3)
check('知识库筛选 3 项', (await page.locator('.kb-filters button.rubber-segment__item').count()) === 3)
check('知识库数据表渲染', (await page.locator('.kb-table tbody tr').count()) >= 5)
check('表格含 Embedding 模型列且不编造 Collection', (await page.locator('.kb-table .kb-code').count()) >= 5 && (await page.locator('.kb-table th', { hasText: 'Collection' }).count()) === 0)
await page.locator('.kb-actions__search input').fill('Redis')
await rowEquals(1)
check('搜索可过滤', (await page.locator('.kb-table tbody tr').count()) === 1)
await page.locator('.kb-actions__search input').fill('')
await rowCount(5)
await page.locator('.kb-table tbody tr').first().getByRole('button', { name: /重命名/ }).click()
await page.waitForSelector('#rename-kb')
await page.locator('#rename-kb').fill('重命名测试库')
await page.locator('.dialog .btn-primary').click()
await settle(() => (document.querySelector('.kb-table tbody')?.textContent ?? '').includes('重命名测试库'))
check('重命名生效', (await page.locator('.kb-table tbody').innerText()).includes('重命名测试库'))
await page.screenshot({ path: join(OUT, 'qa-kb-list.png') })

/* ---------- 文档列表 ---------- */
await page.locator('.kb-table tbody tr').first().click()
/*
 * 等文档区真的就绪再断言，而不是"睡 1200ms 后立刻 count()"。
 * count() 不等待：CI 的慢 runner 上曾读到"行还没渲染"，可同一批断言里紧接着的点
 * `.doc-row`、找分块表都成功——说明只是量得太早。超时后照常断言，真的没有行依然判 FAIL。
 */
await page.locator('.kb-docs__toolbar').waitFor({ state: 'visible', timeout: 10000 }).catch(() => {})
await page.locator('.doc-row').first().waitFor({ state: 'visible', timeout: 10000 }).catch(() => {})
check('文档筛选含状态与刷新', (await page.locator('.kb-docs__toolbar select, .kb-docs__toolbar button').count()) >= 2)
check('文档行含启用开关', (await page.locator('.doc-row .doc-switch').count()) >= 1)
check('文档行含分块数', (await page.locator('.doc-row .kb-num').count()) >= 1)
await page.locator('.doc-row').first().getByRole('button', { name: /分块管理/ }).click()
await page.waitForSelector('.chunk-view')

/* ---------- 分块管理 ---------- */
check('分块表渲染', (await page.locator('.chunk-table tbody tr').count()) >= 5)
check('分块含字符与 Token 列', (await page.locator('.chunk-table th', { hasText: 'Token' }).count()) === 1)
check('分块批量操作齐全', (await page.locator('.chunk-batch button').count()) === 4)
const onBefore = await page.locator('.chunk-table .doc-switch').first().getAttribute('aria-checked')
await page.locator('.chunk-table .doc-switch').first().click()
await settle((before) => document.querySelector('.chunk-table .doc-switch')?.getAttribute('aria-checked') !== before, onBefore)
const onAfter = await page.locator('.chunk-table .doc-switch').first().getAttribute('aria-checked')
check('分块可单独启停', onBefore !== onAfter, `${onBefore} → ${onAfter}`)
await page.locator('.chunk-table tbody tr').first().getByRole('button', { name: /选择分块/ }).click()
await page.locator('.chunk-batch button', { hasText: '批量禁用' }).click()
await settle(() => document.querySelectorAll('.chunk-table .doc-switch[aria-checked="false"]').length >= 1)
check('批量操作可用', (await page.locator('.chunk-table .doc-switch[aria-checked="false"]').count()) >= 1)
await page.screenshot({ path: join(OUT, 'qa-kb-chunks.png') })

/* ---------- 未建索引徽标与 0 命中解释（emptyReason） ----------
 * 演示模式的库是「有切片、没建索引」：徽标要出现，检索要说清是没建索引，
 * 点徽标之后徽标消失、检索真能出结果——这条因果链是静默失败的根因所在。
 */
/* 分块管理是弹层：先确认它关掉了，否则后面对标签页的点击会被遮罩吃掉 */
await page.keyboard.press('Escape')
await settle(() => document.querySelector('.chunk-view') === null)
check('分块管理弹层可关闭', (await page.locator('.chunk-view').count()) === 0)
check('未建索引徽标可见', (await page.locator('.kb-index-badge').count()) === 1)
await page.locator('.kb-top__tabs button.rubber-segment__item', { hasText: '高级检索' }).click()
await page.locator('.kb-adv__search input').waitFor({ state: 'visible', timeout: 8000 }).catch(() => {})
await page.locator('.kb-adv__search input').fill('Redis')
await page.locator('.kb-adv__search button[type="submit"]').click()
/* 初始空态（「输入关键词开始检索」）本来就带 .state-empty，所以要等文案真的换成未建索引那条 */
await settle(() => (document.querySelector('.kb-adv__results .se-title')?.textContent ?? '').includes('还没建索引'))
const noIndexText = (await page.locator('.kb-adv__results .state-empty').count()) === 1
  ? await page.locator('.kb-adv__results .state-empty').innerText()
  : ''
check('未建索引时检索说明原因', noIndexText.includes('还没建索引'), noIndexText.replace(/\s+/g, ' ').slice(0, 60))
await page.screenshot({ path: join(OUT, 'qa-kb-no-index.png') })
await page.locator('.kb-index-badge').click()
await settle(() => document.querySelector('.kb-index-badge') === null)
check('点徽标建索引后徽标消失', (await page.locator('.kb-index-badge').count()) === 0)
await page.locator('.kb-adv__search button[type="submit"]').click()
await settle(() => document.querySelectorAll('.kb-adv__hit').length > 0)
check('建索引后检索能出结果', (await page.locator('.kb-adv__hit').count()) > 0)

await page.setViewportSize({ width: 390, height: 844 })
await settle(() => document.documentElement.scrollWidth <= window.innerWidth + 1)
check('手机端知识库页无横向溢出', await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1))

console.log(issues.length === 0 ? 'ALL OK' : `ISSUES: ${issues.join(' | ')}`)
await browser.close()
process.exit(issues.length ? 1 : 0)
