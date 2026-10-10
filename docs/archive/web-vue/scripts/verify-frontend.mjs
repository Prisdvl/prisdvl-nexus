/**
 * 前端界面验证（不需要 Docker；standalone 后端也能跑完本脚本）。
 *
 * 覆盖：知识库列表 → 正文（Markdown + 大纲 + **关联面板**）→ 切片。
 * 检索/问答两个页签需要 ES/pgvector/Ollama，standalone 下不可用，因此不在本脚本范围内。
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
// 本脚本断言的是带双链的那篇（java_springboot.md → [[JVM 笔记]] / [[SpringMVC]]），
// 它只在「我的笔记」里（库名相近的那个「我的 Java 笔记」一条关联边都没有）。
// **不能靠"点第一张卡片"**：列表按创建时间倒序，演示数据后来又加了一个库，
// 第一张就变成别的了——那样的失败说明的是数据漂移，不是代码坏了
const demoKb = process.env.DEMO_KB ?? '我的笔记'
// 目标文档的 sourcePath（与 seed-demo.mjs 保持一致）。
// 写死整条路径而不是片段：库里还有一个sourcePath 为 `Java/SpringBoot.md` 的同名库，
// 片段匹配分不清"哪个库的哪一篇"，而这条脚本要的正是「我的笔记」里那篇。
const DOC_PATH = process.env.DEMO_DOC ?? 'java_springboot.md'

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 980 } })
await context.addInitScript((value) => {
  localStorage.setItem('nexus.token', value)
  localStorage.setItem('nexus.username', 'demo')
}, token)

const page = await context.newPage()
const failures = []
const shoot = async (name) => {
  await page.screenshot({ path: `${outDir}/${name}.png` })
  console.log(`✓ ${name}.png`)
}

try {
  // ① 知识库列表
  await page.goto(base, { waitUntil: 'networkidle' })
  await page.waitForSelector('[data-testid="kb-card"]', { timeout: 15000 })
  await shoot('kb-list')

  // ② 正文：Markdown + 大纲 + 关联面板
  await page.click(`[data-testid="kb-card"][data-kb-name="${demoKb}"] [data-testid="kb-open"]`)
  await page.waitForSelector('[data-testid="kb-doc-item"]', { timeout: 15000 })
  await page.waitForSelector('.markdown', { timeout: 15000 })
  await page.waitForTimeout(800)

  const outlineCount = await page.locator('.outline li').count()
  console.log(`大纲 ${outlineCount} 项`)
  if (outlineCount === 0) failures.push('大纲为空')

  // 选中带链接的那篇（java_springboot.md 出链到 [[JVM 笔记]] 与 [[SpringMVC]]）
  //
  // 用 sourcePath 精确匹配，**不用 `[data-doc-path*="SpringBoot"]`**：
  // CSS `*=` 是大小写敏感的，而 Playwright 的 `:has-text()` 是大小写不敏感的子串匹配——
  // 原来的 `li:has-text("SpringBoot")` 命中的其实是小写的 `java_springboot.md`。
  // 照搬成 `*="SpringBoot"` 就会**一个都匹配不到**，而报错是"元素找不到"，
  // 指向不了"大小写"这个真因。所以这里老老实实用完整路径。
  await page.locator(`[data-testid="kb-doc-item"][data-doc-path="${DOC_PATH}"]`).click()
  // 等待顶部标题栏与"待创建"引用真正出现——第一版脚本在这里有竞态：
  // 切换还没完成就去读关联面板，读到的是上一篇文档的旧值，误判成"没有切换"
  await page.waitForSelector(
    `[data-testid="kb-doc-item"][data-doc-path="${DOC_PATH}"].active`,
    { timeout: 15000 }
  )
  await page.waitForSelector('.link-group li.unresolved', { timeout: 15000 })
  // 关联面板的 testid 契约：面板本体是条件渲染（文档没链接就不渲染），
  // 但**这里的前提必然成立**（SpringBoot.md 确有出链），所以可以硬断言。
  // 这同时证明"两个同构的 .links 面板靠 kb-links / kb-related 区分"确实有效——
  // 原先这里只能靠小节标题文案来区分。
  if ((await page.locator('[data-testid="kb-links"]').count()) !== 1) {
    failures.push('关联面板锚点 kb-links 未落位（或同时出现了两个）')
  }
  const linkSummary = (await page.locator('.links-head .num').first().innerText()).replace(/\s+/g, ' ').trim()
  const outlinks = await page.locator('.link-group li').count()
  console.log(`关联面板：${linkSummary}（条目 ${outlinks}）`)
  if (outlinks === 0) failures.push('关联面板没有条目')
  // "待创建"必须出现——它就是 [[SpringMVC]] 这种指向尚不存在笔记的链接
  if ((await page.locator('.link-group li.unresolved').count()) === 0) {
    failures.push('没有识别出"待创建"的引用')
  }
  // 相关推荐面板（Phase 4.2）：与关联面板**同构**（都用 .links 样式），
  // 靠各自的 testid 区分——原先靠小节标题文案，改个字就会选错面板
  const relatedSection = page.locator('[data-testid="kb-related"]')
  const relatedItems = await relatedSection.locator('.link-group li').count()
  console.log(`相关推荐：${relatedItems} 篇`)
  if (relatedItems === 0) failures.push('相关推荐面板为空')
  await shoot('kb-doc')

  // ③ 切片
  await page.click('#tab-chunks')
  await page.waitForTimeout(500)
  await shoot('kb-chunks')
} catch (error) {
  failures.push(error.message)
  console.error('✗ 验证失败：', error.message)
  await page.screenshot({ path: `${outDir}/frontend-failure.png` })
} finally {
  await browser.close()
}

if (failures.length > 0) {
  console.error('失败项：' + failures.join('; '))
  process.exit(1)
}
console.log('\n前端界面验证通过（依赖已启动的后端与演示数据；本脚本只覆盖不涉及检索/问答的页面）。')
