/**
 * 登录流程验证：真实填表单 → 提交 → 确认跳转到知识库列表。
 *
 * 为什么单独跑这一条：截图脚本为了稳定用的是"预置 token"，
 * 因此它并不能证明登录表单本身可用。而登录是用户打开项目后的第一道门，
 * 一旦这里坏了，后面所有页面都看不到。
 */
import { chromium } from 'playwright-core'

const base = process.env.WEB_BASE ?? 'http://localhost:8088'
// 默认账号必须与 scripts/seed-demo.mjs 保持一致：两个脚本描述的是同一个演示用户，
// 各写各的默认值会变成"种了数据却登不进去"，而且报错只是"登录超时"，很难看出原因
const username = process.env.DEMO_USER ?? 'demo'
const password = process.env.DEMO_PASSWORD ?? 'Demo@12345'

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 980 } })

const failures = []

try {
  await page.goto(base, { waitUntil: 'networkidle' })
  await page.waitForSelector('[data-testid="auth-username"]', { timeout: 15000 })
  console.log('① 打开首页 → 已被路由守卫重定向到登录页 ✓')

  await page.fill('[data-testid="auth-username"]', username)
  await page.fill('[data-testid="auth-password"]', password)
  await page.click('[data-testid="auth-submit"]')

  // 登录成功后应离开 #/login
  await page.waitForFunction(() => !location.hash.startsWith('#/login'), { timeout: 15000 })
  console.log('② 提交登录 → 已跳转，URL =', page.url())

  await page.waitForSelector('[data-testid="kb-card"], [data-testid="kb-empty"]', { timeout: 15000 })
  const cards = await page.locator('[data-testid="kb-card"]').count()
  console.log(`③ 知识库列表渲染 ✓（可见 ${cards} 个知识库）`)

  // 刷新后仍保持登录（token 存在 localStorage）
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForSelector('[data-testid="kb-card"], [data-testid="kb-empty"]', { timeout: 15000 })
  console.log('④ 刷新页面后仍处于登录态 ✓')
} catch (error) {
  failures.push(error.message)
  console.error('✗ 登录流程失败：', error.message)
  await page.screenshot({ path: 'login-flow-failure.png' })
} finally {
  await browser.close()
}

if (failures.length > 0) {
  process.exit(1)
}
console.log('\n登录流程验证通过。')
