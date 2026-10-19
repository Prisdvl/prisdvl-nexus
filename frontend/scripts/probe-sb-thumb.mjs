import { chromium } from 'playwright-core'
import { join } from 'node:path'
import { tmpdir } from 'node:os'

const BASE = 'http://127.0.0.1:5175'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')
  localStorage.setItem('nx-proto-sb-collapsed', '1')
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
})

await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(600)
if (await page.locator('.auth-card').count()) {
  await page.locator('#li-u').fill('qa-runner')
  await page.locator('#li-p').fill('qa-runner-password')
  await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
  await page.waitForSelector('.shell', { timeout: 10000 })
}
await page.goto(BASE + '/#/app/workbench', { waitUntil: 'networkidle' })
await page.waitForTimeout(400)
await page.evaluate(() => localStorage.setItem('nx-proto-sb-collapsed', '1'))
await page.reload({ waitUntil: 'networkidle' })
await page.waitForSelector('.sidebar[data-collapsed]')
await page.waitForTimeout(400)

const align = await page.evaluate(() => {
  const cx = (el) => {
    const r = el.getBoundingClientRect()
    return +(r.x + r.width / 2).toFixed(1)
  }
  const brand = document.querySelector('.sb-brand-mini .mark, .sb-brand-mini img')
  const toggle = document.querySelector('.sb-icon-btn svg')
  const plus = document.querySelector('.sb-row--quick svg')
  const navIcons = [...document.querySelectorAll('.sb-nav .rubber-segment__item[role=radio] svg')]
  const gear = document.querySelector('.sb-row--settings svg')
  const centers = {
    brand: brand ? cx(brand) : null,
    toggle: toggle ? cx(toggle) : null,
    plus: plus ? cx(plus) : null,
    nav: navIcons.map(cx),
    gear: gear ? cx(gear) : null,
  }
  const vals = [centers.toggle, centers.plus, ...centers.nav, centers.gear].filter((n) => n != null)
  const mean = vals.reduce((a, b) => a + b, 0) / vals.length
  const maxDev = Math.max(...vals.map((v) => Math.abs(v - mean)))
  return { centers, mean: +mean.toFixed(1), maxDev: +maxDev.toFixed(1) }
})
console.log('align', JSON.stringify(align, null, 2))

/* 跨 3 项：工作台 → Agent，采样滑动中途 clip 是否离开起点 */
const readClip = () => page.evaluate(() => {
  const thumb = document.querySelector('.sb-nav .rubber-segment__thumb')
  const clip = getComputedStyle(thumb).clipPath
  const m = clip.match(/inset\(([-0-9.px\s]+)/)
  if (!m) return { clip, top: null }
  const parts = m[1].trim().split(/\s+/).map(parseFloat)
  return { clip, top: parts[0], bottom: parts[2] }
})
const start = await readClip()
const agentBtn = page.locator('.sb-nav .rubber-segment__item[role=radio]').nth(3)
await agentBtn.click({ force: true })
await page.waitForTimeout(80)
const mid = await readClip()
await page.waitForTimeout(500)
const end = await readClip()
const motion = {
  start,
  mid,
  end,
  moved: mid.top != null && start.top != null && Math.abs(mid.top - start.top) > 1,
  landed: end.top != null && end.top > 80,
}
console.log('motion', JSON.stringify(motion, null, 2))

await page.locator('.sidebar').screenshot({ path: join(tmpdir(), 'prisma-recover-qa-shots', 'qa-sb-rubber-collapsed.png') })
await browser.close()
