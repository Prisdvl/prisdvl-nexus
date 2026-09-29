/**
 * 诊断：为什么点击文档列表项不切换文档。
 *
 * 这次不再猜：直接问浏览器三个问题——
 * 1. 指针落在 SpringBoot 那一行时，elementFromPoint 命中的是哪个元素（若被遮罩挡住，会命中遮罩）；
 * 2. 点击后正文标题变成什么；
 * 3. console / pageerror 里有没有 Vue 报错。
 */
import { chromium } from 'playwright-core'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const token = readFileSync(resolve(here, '../.demo-token'), 'utf8').trim()
const base = process.env.WEB_BASE ?? 'http://localhost:5173'

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 980 } })
await context.addInitScript((value) => {
  localStorage.setItem('nexus.token', value)
  localStorage.setItem('nexus.username', 'demo')
}, token)

const page = await context.newPage()
const logs = []
page.on('console', (msg) => logs.push(`[${msg.type()}] ${msg.text().slice(0, 180)}`))
page.on('pageerror', (err) => logs.push(`[pageerror] ${err.message.slice(0, 180)}`))

try {
  await page.goto(base, { waitUntil: 'networkidle' })
  await page.waitForSelector('.card .name', { timeout: 15000 })
  await page.click('.card .name')
  await page.waitForSelector('.docs li', { timeout: 15000 })
  await page.waitForSelector('.markdown', { timeout: 15000 })
  await page.waitForTimeout(600)

  console.log('① 初始正文标题:', await page.locator('.markdown h1').first().innerText().catch(() => '(无 h1)'))

  // ② 指针落点
  const probe = await page.evaluate(() => {
    const lis = [...document.querySelectorAll('.docs li')]
    const target = lis.find((li) => li.textContent.includes('SpringBoot'))
    if (!target) return { found: false }
    const r = target.getBoundingClientRect()
    const hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2)
    return {
      found: true,
      liText: target.textContent.replace(/\s+/g, ' ').slice(0, 40),
      hitTag: hit?.tagName ?? null,
      hitClass: (hit?.className ?? '').toString().slice(0, 80),
      y: Math.round(r.y + r.height / 2)
    }
  })
  console.log('② elementFromPoint 命中:', JSON.stringify(probe))

  // ③ 带 force 点击（绕过可能的遮罩），看正文是否切换
  await page.locator('.docs li:has-text("SpringBoot")').first().click({ force: true })
  await page.waitForTimeout(1600)
  const title = await page.locator('.markdown h1').first().innerText().catch(() => '(无 h1)')
  console.log('③ force 点击后正文标题:', title)
  console.log('④ 顶部标题栏:',
    await page.locator('.detail-title, .doc-title, h1.detail').first().innerText().catch(() => '(无)'))

  console.log('⑤ 控制台日志（含 Vue 错误）:')
  logs.slice(0, 8).forEach((line) => console.log('   ' + line))
  if (!logs.length) console.log('   （无）')
} catch (error) {
  console.error('✗ 脚本失败:', error.message)
} finally {
  await browser.close()
}