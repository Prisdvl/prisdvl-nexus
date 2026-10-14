/**
 * 会话历史导航：高亮按提问序号，即使消息 id 撞车也只亮一根。
 */
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'

const BASE = process.env.QA_BASE ?? 'http://127.0.0.1:5188'
const OUT = process.env.QA_SHOTS_OUT ?? 'D:/develop/Prisma/docs/prototypes/shots'
mkdirSync(OUT, { recursive: true })

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
  localStorage.setItem('nx-proto-auth', '1')
  localStorage.setItem('nx-proto-palette', 'turquoise')
})
await page.goto(BASE + '/#/app/chat', { waitUntil: 'networkidle' })
await page.waitForTimeout(700)
await page.locator('.session-open', { hasText: 'B+ 树' }).first().click()
await page.waitForTimeout(900)

const snapshot = async () => page.evaluate(() => {
  const root = document.querySelector('.thread')
  const items = Array.from(document.querySelectorAll('.msg-nav__item'))
  const activeRail = items.findIndex((el) => el.hasAttribute('data-active'))
  const activeRailCount = items.filter((el) => el.hasAttribute('data-active')).length
  const styles = items.map((el, i) => {
    const line = el.querySelector('.msg-nav__line')
    const cs = getComputedStyle(line)
    return {
      i: i + 1,
      active: el.hasAttribute('data-active'),
      w: Math.round(line.getBoundingClientRect().width),
      bg: cs.backgroundColor,
    }
  })
  const idleBg = styles.find((s) => !s.active)?.bg
  const primaryCount = styles.filter((s) => s.bg !== idleBg).length
  const pop = document.querySelector('.msg-preview')
  const activePop = pop
    ? Array.from(pop.querySelectorAll('.msg-preview__item')).findIndex((el) => el.hasAttribute('data-active'))
    : -1
  const activePopCount = pop ? pop.querySelectorAll('.msg-preview__item[data-active]').length : 0
  const qidxNodes = document.querySelectorAll('.thread .msg.user[data-qidx]').length
  return {
    activeRail,
    activeRailCount,
    activePop,
    activePopCount,
    styles,
    primaryCount,
    qidxNodes,
    scrollTop: root?.scrollTop ?? -1,
  }
})

const waitSettled = async () => page.waitForTimeout(750)

// 模拟 id 撞车：把第 2、第 3 条 user 消息的 data-mid 改成和第 1 条一样
await page.evaluate(() => {
  const users = Array.from(document.querySelectorAll('.thread .msg.user'))
  if (users.length < 3) return
  const firstId = users[0].getAttribute('data-mid')
  users[1].setAttribute('data-mid', firstId)
  users[2].setAttribute('data-mid', firstId)
})

await page.locator('.thread').evaluate((el) => { el.scrollTop = 0 })
await waitSettled()
let state = await snapshot()
check('有 data-qidx 锚点', state.qidxNodes >= 2, `${state.qidxNodes}`)
check('滚到顶部 → 只亮第 1 条', state.activeRail === 0 && state.activeRailCount === 1, JSON.stringify(state))
check('轨道只有一根主色', state.primaryCount === 1, JSON.stringify(state.styles))

// 即使 mid 撞车，悬停第 3 条时浮层仍只高亮阅读位第 1
await page.locator('.msg-nav__item').nth(2).hover()
await page.waitForTimeout(400)
state = await snapshot()
check('悬停后轨道仍只亮 1 根', state.activeRailCount === 1 && state.activeRail === 0, JSON.stringify(state))
check('浮层只高亮一条', state.activePopCount === 1, JSON.stringify(state))
check('浮层高亮 = 第 1 条', state.activePop === 0, JSON.stringify(state))
check('浮层无页眉页脚', await page.evaluate(() => !document.querySelector('.msg-preview__head, .msg-preview__hint')))

await page.screenshot({ path: `${OUT}/z19-msgnav-index-active.png` })

// 跳到第 3 条（按序号，不按撞车的 mid）
await page.locator('.msg-preview__item').nth(2).click()
await waitSettled()
state = await snapshot()
check('跳转后轨道只亮第 3', state.activeRail === 2 && state.activeRailCount === 1, JSON.stringify(state))
check('跳转后只有一根主色', state.primaryCount === 1, JSON.stringify(state.styles))

await page.screenshot({ path: `${OUT}/z19-msgnav-jump3-index.png` })

await browser.close()
if (issues.length) {
  console.error(`\n${issues.length} FAIL(s): ${issues.join('; ')}`)
  process.exit(1)
}
console.log('\nall passed')
