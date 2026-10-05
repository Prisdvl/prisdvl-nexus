/**
 * UI 截图脚本（复用系统已安装的 Edge/Chrome，不下载浏览器）。
 *
 * 用途：每次改完前端跑一次，确认页面"真的渲染出来了"而不是只返回了 HTML。
 * 用法：node scripts/screenshot.mjs [tokenFile]
 *
 * 为什么用 playwright-core 而不是默认的 playwright：前者不含浏览器二进制，
 * 用 channel: 'msedge' / 'chrome' 直接驱动系统浏览器，装起来只有几百 KB。
 */
import { chromium } from 'playwright-core'
import { readFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const outDir = resolve(here, '../../docs/screenshots')
mkdirSync(outDir, { recursive: true })

const tokenFile = process.argv[2] ?? resolve(here, '../.demo-token')
const token = readFileSync(tokenFile, 'utf8').trim()
const base = process.env.WEB_BASE ?? 'http://localhost:8088'

/** 依次尝试系统浏览器；任一可用即可。 */
async function launch() {
  const errors = []
  for (const channel of ['msedge', 'chrome']) {
    try {
      return await chromium.launch({ channel, headless: true })
    } catch (error) {
      errors.push(`${channel}: ${error.message}`)
    }
  }
  throw new Error('未找到可用的系统浏览器\n' + errors.join('\n'))
}

const browser = await launch()
const context = await browser.newContext({ viewport: { width: 1440, height: 980 } })

// 登录态：在页面脚本执行前写入 localStorage（这是最干净的"预置会话"方式，
// 不需要给应用加任何"开发专用后门"）
await context.addInitScript((value) => {
  localStorage.setItem('nexus.token', value)
  localStorage.setItem('nexus.username', 'demo')
}, token)

const page = await context.newPage()
const shots = []

async function shoot(name) {
  const path = `${outDir}/${name}.png`
  await page.screenshot({ path, fullPage: false })
  shots.push(path)
  console.log(`✓ ${name}.png`)
}

// ① 知识库列表
await page.goto(base, { waitUntil: 'networkidle' })
await page.waitForSelector('.card, .el-empty', { timeout: 15000 })
await shoot('kb-list')

// ② 进入知识库详情（点卡片标题导航）
const kbLink = page.locator('.card .name').first()
if (await kbLink.count()) {
  await kbLink.click()
  // 详情页：左侧文档列表 + 右侧正文（Markdown 渲染 + 大纲）
  await page.waitForSelector('.docs li', { timeout: 15000 })
  await page.waitForSelector('.markdown, .empty', { timeout: 15000 })
  await page.waitForTimeout(900)   // 等代码高亮与大纲渲染完成
  await shoot('kb-doc')

  // ③ 切片视图
  await page.click('.el-tabs__item:has-text("切片")')
  await page.waitForTimeout(500)
  await shoot('kb-chunks')

  // ④ 检索（融合模式）：命中项带来源与 RRF 分数
  await page.click('.el-tabs__item:has-text("检索")')
  await page.locator('.search-box input').fill('缓存击穿怎么解决')
  await page.locator('.search-box button').click()
  await page.waitForTimeout(3000)
  await shoot('kb-search')
} else {
  console.log('（没有知识库可进入，跳过详情页截图）')
}

console.log('\n输出目录: ' + outDir)
await browser.close()
