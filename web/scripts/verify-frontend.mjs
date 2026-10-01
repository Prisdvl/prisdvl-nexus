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

const here = dirname(fileURLToPath(import.meta.url))
const token = readFileSync(resolve(here, '../.demo-token'), 'utf8').trim()
const base = process.env.WEB_BASE ?? 'http://localhost:5173'
const outDir = resolve(here, '../../docs/screenshots')
// 本脚本断言的是带双链的那篇（java_springboot.md → [[JVM 笔记]] / [[SpringMVC]]），
// 它只在「我的笔记」里（库名相近的那个「我的 Java 笔记」一条关联边都没有）。
// **不能靠"点第一张卡片"**：列表按创建时间倒序，演示数据后来又加了一个库，
// 第一张就变成别的了——那样的失败说明的是数据漂移，不是代码坏了
const demoKb = process.env.DEMO_KB ?? '我的笔记'

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
  await page.waitForSelector('.card .name', { timeout: 15000 })
  await shoot('kb-list')

  // ② 正文：Markdown + 大纲 + 关联面板
  await page.click(`.card:has-text("${demoKb}") .name`)
  await page.waitForSelector('.docs li', { timeout: 15000 })
  await page.waitForSelector('.markdown', { timeout: 15000 })
  await page.waitForTimeout(800)

  const outlineCount = await page.locator('.outline li').count()
  console.log(`大纲 ${outlineCount} 项`)
  if (outlineCount === 0) failures.push('大纲为空')

  // 选中带链接的那篇（SpringBoot.md 出链到 [[JVM 笔记]] 与 [[SpringMVC]]）
  await page.locator('.docs li:has-text("SpringBoot")').click()
  // 等待顶部标题栏与"待创建"引用真正出现——第一版脚本在这里有竞态：
  // 切换还没完成就去读关联面板，读到的是上一篇文档的旧值，误判成"没有切换"
  await page.waitForSelector('.doc-title:has-text("Spring Boot")', { timeout: 15000 })
  await page.waitForSelector('.link-group li.unresolved', { timeout: 15000 })
  const linkSummary = (await page.locator('.links-head .num').first().innerText()).replace(/\s+/g, ' ').trim()
  const outlinks = await page.locator('.link-group li').count()
  console.log(`关联面板：${linkSummary}（条目 ${outlinks}）`)
  if (outlinks === 0) failures.push('关联面板没有条目')
  // "待创建"必须出现——它就是 [[SpringMVC]] 这种指向尚不存在笔记的链接
  if ((await page.locator('.link-group li.unresolved').count()) === 0) {
    failures.push('没有识别出"待创建"的引用')
  }
  // 相关推荐面板（Phase 4.2）：与关联面板共用样式，用标题文本区分
  const relatedSection = page.locator('.links', { has: page.locator('.links-head:has-text("相关推荐")') })
  const relatedItems = await relatedSection.locator('.link-group li').count()
  console.log(`相关推荐：${relatedItems} 篇`)
  if (relatedItems === 0) failures.push('相关推荐面板为空')
  await shoot('kb-doc')

  // ③ 切片
  await page.click('.el-tabs__item:has-text("切片")')
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
