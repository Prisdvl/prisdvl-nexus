/**
 * 前端界面验证（standalone 模式，不依赖 Docker）。
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
  await page.click('.card .name')
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
  const linkSummary = (await page.locator('.links-head .num').innerText()).replace(/\s+/g, ' ').trim()
  const outlinks = await page.locator('.link-group li').count()
  console.log(`关联面板：${linkSummary}（条目 ${outlinks}）`)
  if (outlinks === 0) failures.push('关联面板没有条目')
  // "待创建"必须出现——它就是 [[SpringMVC]] 这种指向尚不存在笔记的链接
  if ((await page.locator('.link-group li.unresolved').count()) === 0) {
    failures.push('没有识别出"待创建"的引用')
  }
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
console.log('\n前端界面验证通过（standalone，无 Docker）。')
