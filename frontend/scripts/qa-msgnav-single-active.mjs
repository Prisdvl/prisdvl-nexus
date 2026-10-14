/**
 * 回归：轨道永远只能有 1 根主色。
 * 复现旧 bug：点过第 1、第 2 条留下焦点，再悬停第 3、阅读在末条 → 曾经同时亮 3 根。
 */
import { chromium } from 'playwright-core'

const BASE = process.env.QA_BASE ?? 'http://127.0.0.1:5180'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const issues = []
const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`)
  if (!ok) issues.push(name)
}

await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')
  localStorage.setItem('nx-proto-theme', 'dark')
  localStorage.setItem('nx-proto-palette', 'indigo')
  localStorage.setItem('nx-proto-auth', '1')
})
await page.goto(BASE + '/#/app/chat', { waitUntil: 'networkidle' })
await page.waitForTimeout(700)
await page.locator('.session-open', { hasText: 'B+ 树' }).first().click()
await page.waitForTimeout(900)

const primaryCount = async () => page.evaluate(() => {
  const primary = getComputedStyle(document.documentElement).getPropertyValue('--primary').trim()
  // resolve --primary to rgb via a probe
  const probe = document.createElement('div')
  probe.style.background = primary
  document.body.appendChild(probe)
  const primaryBg = getComputedStyle(probe).backgroundColor
  probe.remove()

  const items = Array.from(document.querySelectorAll('.msg-nav__item'))
  const lines = items.map((el, i) => {
    const line = el.querySelector('.msg-nav__line')
    const cs = getComputedStyle(line)
    return {
      i: i + 1,
      isActive: el.classList.contains('is-active'),
      dataActive: el.hasAttribute('data-active'),
      bg: cs.backgroundColor,
      shadow: cs.boxShadow,
      outline: cs.outline,
      w: Math.round(line.getBoundingClientRect().width),
      primaryish: cs.backgroundColor === primaryBg,
    }
  })
  return {
    primaryBg,
    primaryish: lines.filter((l) => l.primaryish).length,
    activeClass: lines.filter((l) => l.isActive).length,
    lines,
  }
})

let state = await primaryCount()
check('初始只有 1 根主色', state.primaryish === 1 && state.activeClass === 1, JSON.stringify(state.lines))

// 依次点击 1、2，再点末条，再悬停第 1 条（旧 bug 现场）
const n = await page.locator('.msg-nav__item').count()
await page.locator('.msg-nav__item').nth(0).click()
await page.waitForTimeout(200)
await page.locator('.msg-nav__item').nth(1).click()
await page.waitForTimeout(200)
await page.locator('.msg-nav__item').nth(n - 1).click()
await page.waitForTimeout(800)
await page.locator('.msg-nav__item').nth(0).hover()
await page.waitForTimeout(300)

state = await primaryCount()
check('点过 + 悬停后仍只有 1 根主色', state.primaryish === 1, JSON.stringify(state.lines))
check('只有 1 个 is-active', state.activeClass === 1, JSON.stringify(state.lines))
check('悬停项不是主色（应是加长 muted）', state.lines[0].primaryish === false, JSON.stringify(state.lines[0]))
check('悬停项无主色描边', state.lines[0].shadow === 'none', JSON.stringify(state.lines[0]))
check('末条是唯一主色', state.lines[n - 1].primaryish === true && state.lines[n - 1].isActive, JSON.stringify(state.lines[n - 1]))

// 键盘焦点也不许上主色
await page.locator('.msg-nav__item').nth(0).focus({ force: true })
await page.waitForTimeout(200)
state = await primaryCount()
check('强制 focus 第 1 条后仍只有 1 根主色', state.primaryish === 1, JSON.stringify(state.lines))

await browser.close()
if (issues.length) {
  console.error(`\n${issues.length} FAIL(s): ${issues.join('; ')}`)
  process.exit(1)
}
console.log('\nall passed')
