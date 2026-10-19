// 首次登录分步引导（Onboarding Tour）专项 QA
import { join } from 'node:path'
import { tmpdir } from 'node:os'
//
// 注意：本套件**刻意不植入** nx-proto-tour-done 标记 —— 引导只在"首登且未看过"时出现，
// 其他套件都植入了标记以免污染用例与截图，所以这里必须用干净的上下文来验证。
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'

const BASE = 'http://localhost:5175'
const OUT = process.env.QA_SHOTS_OUT ?? join(tmpdir(), 'prisma-recover-qa-shots')
mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })
/* 同一个 context 里开页：localStorage 共享，才能验证"跳过后刷新不再出现" */
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
let page = await ctx.newPage()
const issues = []
page.on('pageerror', (e) => issues.push(`pageerror: ${String(e.message).slice(0, 140)}`))
page.on('console', (m) => { if (m.type() === 'error') issues.push(`console.error: ${m.text().slice(0, 140)}`) })

const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`)
  if (!ok) issues.push(name)
}

const tourState = () => page.evaluate(() => {
  const card = document.querySelector('.tour__card')
  const spot = document.querySelector('.tour__spotlight')
  const r = card?.getBoundingClientRect()
  const sr = spot?.getBoundingClientRect()
  const root = document.querySelector('.tour')
  return {
    open: !!root,
    inBody: root?.parentElement === document.body,
    fixed: root ? getComputedStyle(root).position : null,
    badge: card?.querySelector('.tour__badge')?.textContent?.trim(),
    title: card?.querySelector('.tour__title')?.textContent?.trim(),
    cardInside: r ? r.left >= 0 && r.top >= 0 && r.right <= window.innerWidth + 1 && r.bottom <= window.innerHeight + 1 : null,
    spot: sr ? { l: Math.round(sr.left), t: Math.round(sr.top), w: Math.round(sr.width), h: Math.round(sr.height) } : null,
    /* 遮罩必须不吃点击，否则引导会挡住正常操作 */
    overlayPointer: root ? getComputedStyle(root).pointerEvents : null,
    cardPointer: card ? getComputedStyle(card).pointerEvents : null,
    done: localStorage.getItem('nx-proto-tour-done'),
  }
})

/* ---------- 1. 首登出现引导 ---------- */
/* 只清第一页的标记；后续新开页不再清，用于验证持久化 */
await page.addInitScript(() => { localStorage.removeItem('nx-proto-tour-done') })
/* 登录流程与其它套件一致：落地页 → 登录页 → 演示账号 → 登录 */
await page.goto(BASE, { waitUntil: 'networkidle' })
await page.waitForTimeout(2200)
await page.locator('.lp-pill button', { hasText: '登录' }).first().click()
await page.waitForSelector('.auth-card')
await page.locator('#li-u').fill('qa-runner')
await page.locator('#li-p').fill('qa-runner-password')
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
await page.waitForSelector('.shell')
/* 引导延后 700ms 才出现，这里多等一点 */
await page.waitForTimeout(2600)

let s = await tourState()
check('首登出现分步引导', s.open === true)
check('引导由 Portal 挂到 body 且 fixed', s.inBody === true && s.fixed === 'fixed', `position=${s.fixed}`)
check('第一步指向「新对话」', s.badge === '1 / 5' && /新对话/.test(s.title ?? ''), `${s.badge} ${s.title}`)
check('说明卡片完整落在视口内', s.cardInside === true)
check('高亮框尺寸为正', !!s.spot && s.spot.w > 0 && s.spot.h > 0, JSON.stringify(s.spot))
check('遮罩不吃点击（引导不阻断操作）', s.overlayPointer === 'none' && s.cardPointer === 'auto', `${s.overlayPointer} / ${s.cardPointer}`)

/* 高亮框应套住「新对话」这一行 */
const wrapOk = await page.evaluate(() => {
  const target = document.querySelector('.sb-row--quick')?.getBoundingClientRect()
  const spot = document.querySelector('.tour__spotlight')?.getBoundingClientRect()
  if (!target || !spot) return false
  return spot.left <= target.left + 1 && spot.right >= target.right - 1 && spot.top <= target.top + 1 && spot.bottom >= target.bottom - 1
})
check('高亮框套住目标元素', wrapOk === true)

/* 引导打开时仍能正常操作侧栏（遮罩穿透） */
await page.locator('.sb-nav button.rubber-segment__item', { hasText: '知识库' }).click()
await page.waitForTimeout(900)
check('引导打开时侧栏仍可点击', (await page.evaluate(() => location.hash)).includes('/app/kb'))
await page.goto(BASE + '/#/app/chat', { waitUntil: 'networkidle' })
await page.waitForTimeout(1500)

/* ---------- 2. 上一步 / 下一步 ---------- */
await page.locator('.tour__card .btn-primary').click()
await page.waitForTimeout(700)
s = await tourState()
check('下一步切到第 2 步', s.badge === '2 / 5', `${s.badge} ${s.title}`)
const spot2 = s.spot
await page.locator('.tour__card .btn', { hasText: '上一步' }).click()
await page.waitForTimeout(700)
s = await tourState()
check('上一步回到第 1 步', s.badge === '1 / 5', `${s.badge}`)
check('高亮框随步骤移动', JSON.stringify(s.spot) !== JSON.stringify(spot2), `${JSON.stringify(spot2)} → ${JSON.stringify(s.spot)}`)

/* ---------- 3. 跳过并持久化 ---------- */
await page.locator('.tour__card .btn', { hasText: '跳过' }).click()
await page.waitForTimeout(600)
s = await tourState()
check('跳过后引导关闭', s.open === false)
check('跳过后写入已完成标记', s.done === '1', String(s.done))
await page.close()
page = await ctx.newPage()
await page.addInitScript(() => { localStorage.setItem('nx-proto-auth','1') })
await page.goto(BASE + '/#/app/chat', { waitUntil: 'networkidle' })
await page.waitForTimeout(2200)
check('跳过后重新打开不再出现引导', (await page.locator('.tour').count()) === 0)
check('已完成标记在会话间保留', (await page.evaluate(() => localStorage.getItem('nx-proto-tour-done'))) === '1')

/* ---------- 4. 从「个人设置」重播 ---------- */
await page.locator('.sb-row--settings').click()
await page.waitForTimeout(800)
await page.locator('.settings-dialog button', { hasText: '重新查看新手引导' }).click()
await page.waitForTimeout(1400)
s = await tourState()
check('可从设置弹层重播引导', s.open === true && s.badge === '1 / 5', `${s.badge}`)
await page.screenshot({ path: `${OUT}/z34-tour-step1.png` })
console.log('ok   z34-tour-step1（截图）')
await page.keyboard.press('Escape')
await page.waitForTimeout(500)
check('Esc 可跳过引导', (await page.locator('.tour').count()) === 0)

/* ---------- 5. 关键页面全程无控制台错误 ---------- */
check('引导流程无控制台错误', issues.filter((i) => i.includes('error')).length === 0, issues.slice(0, 2).join(' | '))

await browser.close()
if (issues.length) {
  console.log(`\n发现 ${issues.length} 个问题`)
  issues.forEach((i) => console.log('  - ' + i))
  process.exit(1)
} else {
  console.log('\n新手引导专项 QA 全部通过')
}
