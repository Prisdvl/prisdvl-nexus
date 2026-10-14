// 统一性 QA：入口导航橡皮化 / scroll-spy / 进度条 / 控件统一 / 动效 token / 路由过渡
import { chromium } from 'playwright-core'
import { setTheme, setPalette } from './_theme.mjs'

const BASE = 'http://localhost:5175'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  // 引导只在真实首登时出现，避免污染用例/截图
  await page.addInitScript(() => {
    localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
    /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
    localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
  })
const issues = []
page.on('pageerror', (e) => issues.push(`pageerror: ${String(e.message).slice(0, 140)}`))
page.on('console', (m) => { if (m.type() === 'error') issues.push(`console.error: ${m.text().slice(0, 140)}`) })

const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`)
  if (!ok) issues.push(name)
}
const nav = (l) => page.locator('.sb-nav button.rubber-segment__item', { hasText: l }).first()

/* ---------- 1. 入口页导航：橡皮分段 ---------- */
await page.goto(BASE, { waitUntil: 'networkidle' })
await page.waitForTimeout(2400)
check('入口导航是橡皮分段', (await page.locator('.lp-pill .rubber-segment').count()) === 1)
const navItems = await page.locator('.lp-pill .rubber-segment button.rubber-segment__item').count()
check('导航含 4 个区块', navItems === 4, `${navItems} 项`)
check('导航无 <a> 残留', (await page.locator('.lp-pill-nav a').count()) === 0)
check('默认高亮第一项', (await page.locator('.lp-pill .rubber-segment__item[aria-checked="true"]').innerText()).includes('能力'))

/* ---------- 2. 点击跳转 ---------- */
await page.locator('.lp-pill .rubber-segment button.rubber-segment__item', { hasText: '技术' }).click()
await page.waitForTimeout(1500)
const stackVisible = await page.evaluate(() => {
  const el = document.getElementById('stack')
  if (!el) return false
  const r = el.getBoundingClientRect()
  return r.top < window.innerHeight * 0.6 && r.bottom > 0
})
check('点击导航滚到对应区块', stackVisible)
check('高亮随之切换', (await page.locator('.lp-pill .rubber-segment__item[aria-checked="true"]').innerText()).includes('技术'))

/* ---------- 3. scroll-spy：滚回去高亮要跟着回来 ---------- */
await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }))
await page.waitForTimeout(1200)
const backLabel = await page.locator('.lp-pill .rubber-segment__item[aria-checked="true"]').innerText()
check('滚动回顶部后高亮回到前面的区块', !backLabel.includes('技术'), `当前高亮：${backLabel}`)

/* ---------- 4. 入口页：进度条已移除、版本切换器已移除（锁 A 版） ---------- */
check('顶部进度条已移除', (await page.locator('.lp-progress').count()) === 0)
check('版本切换器已移除（锁定 A 版）', (await page.locator('.lp-variant').count()) === 0)

/* ---------- 4.1 登录按钮与导航项对齐（胶囊 + 等高） ---------- */
const geo = await page.evaluate(() => {
  const btn = document.querySelector('.lp-pill .btn')
  // 基准是分段"外框"（track），不是内缩后的项：项比外框小 2×inset 是组件设计
  const track = document.querySelector('.lp-pill .rubber-segment')
  if (!btn || !track) return null
  return {
    btnH: Math.round(btn.getBoundingClientRect().height),
    trackH: Math.round(track.getBoundingClientRect().height),
    btnRadius: getComputedStyle(btn).borderTopLeftRadius,
  }
})
check('登录按钮与导航外框等高', geo && Math.abs(geo.btnH - geo.trackH) <= 2, geo ? `${geo.btnH}px vs 外框 ${geo.trackH}px` : 'n/a')
check('登录按钮为胶囊圆角', geo ? parseFloat(geo.btnRadius) >= 100 : false, geo ? geo.btnRadius : 'n/a')

/* ---------- 4.2 按钮尺寸体系统一（三档） ---------- */
await page.evaluate(() => window.scrollTo({ top: 0 }))
await page.waitForTimeout(400)
/* Hero 采用统一的普通按钮档位，不再使用 WebGL 高光层。 */
const heroHeights = await page.locator('.lp-hero .lp-cta-row .btn').evaluateAll((els) => els.map((el) => Math.round(el.getBoundingClientRect().height)))
check('Hero 主次按钮都走大号档（46px）', heroHeights.length === 2 && heroHeights.every((height) => height === 46), heroHeights.join(' / '))
check('入口页 CTA 不含装饰性 WebGL 光效', (await page.locator('.lp-hero .specular-button, .lp-hero canvas.specular-button__canvas').count()) === 0)

/* ---------- 4.3 eyebrow 与段落标题统一 ---------- */
const eyebrow = await page.locator('.lp-eyebrow').first().evaluate((el) => getComputedStyle(el).letterSpacing)
check('eyebrow 字距统一 0.08em', Math.abs(parseFloat(eyebrow) - 0.92) < 0.2, eyebrow)

/* ---------- 5. 登录页 tab 橡皮化 ---------- */
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(1600)
check('登录 tab 是橡皮分段', (await page.locator('.auth-card .rubber-segment').count()) === 1)
await page.locator('.auth-card .rubber-segment button.rubber-segment__item', { hasText: '注册' }).click()
await page.waitForTimeout(500)
check('橡皮 tab 可切换注册', (await page.locator('#rg-u').count()) === 1)
check('切换后高亮在注册', (await page.locator('.auth-card .rubber-segment__item[aria-checked="true"]').innerText()).includes('注册'))

/* ---------- 6. 登录前的外观入口在角落（悬浮条已移除；控件细节由 verify:theme 覆盖） ---------- */
check('登录页有角落外观控件', (await page.locator('.theme-control .theme-control__btn').count()) === 1)
check('全局悬浮工具条已移除', (await page.locator('.preview-tools').count()) === 0)
await setTheme(page, 'dark')
await page.waitForTimeout(500)
check('可通过外观控件切换到暗色', (await page.evaluate(() => document.documentElement.dataset.theme)) === 'dark')
await setTheme(page, 'light')
await page.waitForTimeout(400)

/* ---------- 6.1 全站分段：可视拇指必须恰好一格 ---------- */
const thumbCheck = async (label) => {
  /* 先等分段动画稳定：连读两次一致才测（否则会读到回弹中间态 → 假失败） */
  const read = () => page.evaluate(() => Array.from(document.querySelectorAll('.rubber-segment')).map((s) => getComputedStyle(s.querySelector('.rubber-segment__thumb')).clipPath).join('|'))
  let prev = await read()
  for (let i = 0; i < 12; i++) {
    await page.waitForTimeout(220)
    const now = await read()
    if (now === prev) break
    prev = now
  }
  const geo = await page.evaluate(() => Array.from(document.querySelectorAll('.rubber-segment')).map((seg) => {
    const thumb = seg.querySelector('.rubber-segment__thumb')
    const raw = (getComputedStyle(thumb).clipPath.match(/inset\(([^)]*)\)/)?.[1] || '').replace(/round.*/, '').trim().split(/\s+/).filter(Boolean).map(parseFloat)
  /* CSS inset() 简写会收敛成 1~4 个值，必须按规范展开，否则会误判（竖向分段就是 3 值形式） */
  const expand = (nums) => {
    if (nums.length === 4) return nums
    if (nums.length === 3) return [nums[0], nums[1], nums[2], nums[1]]
    if (nums.length === 2) return [nums[0], nums[1], nums[0], nums[1]]
    if (nums.length === 1) return [nums[0], nums[0], nums[0], nums[0]]
    return [0, 0, 0, 0]
  }
    const [top, right, bottom, left] = expand(raw)
    const r = seg.getBoundingClientRect()
    const vertical = seg.getAttribute('data-vertical') !== null
    const inner = (vertical ? r.height : r.width) - 6
    const n = seg.querySelectorAll('button.rubber-segment__item').length
    const visible = vertical ? inner - top - bottom : inner - left - right
    return { label: seg.getAttribute('aria-label') || '(无名)', ok: Math.abs(visible - inner / n) <= 3, visible: Math.round(visible), slot: Math.round(inner / n) }
  }))
  check(`${label}：每个分段只露出一格拇指`, geo.every((g) => g.ok), geo.map((g) => `${g.label}=${g.visible}/应${g.slot}`).join('  '))
}
await thumbCheck('登录页')
await page.locator('.auth-card .rubber-segment button.rubber-segment__item', { hasText: '注册' }).click()
await page.waitForTimeout(500)
await thumbCheck('注册页')

/* ---------- 7. 动效 token 生效 ---------- */
const btnDur = await page.locator('.auth-card .btn').first().evaluate((el) => getComputedStyle(el).transitionDuration)
check('按钮过渡走 token（0.13s 档）', btnDur.includes('0.13'), btnDur)

/* ---------- 8. 路由过渡 ---------- */
// 先切回登录 tab（上一步停在注册，演示账号按钮只存在于登录 tab）
await page.locator('.auth-card .rubber-segment button.rubber-segment__item', { hasText: '登录' }).click()
await page.waitForTimeout(500)
await page.locator('#li-u').fill('qa-runner')
await page.locator('#li-p').fill('qa-runner-password')
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
await page.waitForSelector('.shell', { timeout: 8000 })
await page.waitForTimeout(1200)
check('应用内有路由过渡容器', (await page.locator('.shell-main .route-fade').count()) === 1)
await nav('对话').click()
await page.waitForTimeout(800)
await page.locator('.mode-seg button.rubber-segment__item', { hasText: 'Agent' }).click()
await page.waitForTimeout(350)
check('切页后过渡容器仍在且内容已换', (await page.locator('.shell-main .route-fade .chat-layout').count()) === 1)

/* ---------- 9. AGENT 模式下的 Agent 选择：横向橡皮 ---------- */
check('Agent 选择是橡皮分段', (await page.locator('.agent-seg .rubber-segment').count()) === 1)
const agentItems = await page.locator('.agent-seg button.rubber-segment__item').count()
check('Agent 分段含 4 项', agentItems === 4, `${agentItems} 项`)
await page.locator('.agent-seg button.rubber-segment__item', { hasText: '简历润色师' }).click()
await page.waitForTimeout(600)
check('切换 Agent 生效', (await page.locator('.agent-seg .rubber-segment__item[aria-checked="true"]').innerText()).includes('简历润色师'))

/* ---------- 10. 动效降级仍有效 ---------- */
const reduced = await browser.newContext({ reducedMotion: 'reduce' })
const rp = await reduced.newPage()
// 引导只在真实首登时出现，避免污染用例/截图
await rp.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')   // 引导只在真实首登时出现
  /* 高风险工具预授权：常规用例不被确认卡打断（确认卡由 qa-chat 专项覆盖） */
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
})
await rp.goto(BASE, { waitUntil: 'networkidle' })
await rp.waitForTimeout(1800)
const fadeAnim = await rp.locator('.route-fade').evaluate((el) => getComputedStyle(el).animationName)
check('reduce 模式下路由过渡关闭', fadeAnim === 'none', fadeAnim)
await reduced.close()

/* ---------- 11. 应用内分段几何（放在最后，避免打断前面的流程） ---------- */
await page.locator('.sb-nav button.rubber-segment__item', { hasText: '知识库' }).first().click()
await page.waitForTimeout(900)
await thumbCheck('应用内（侧栏）')

await browser.close()
if (issues.length) {
  console.log(`\n发现 ${issues.length} 个问题:`)
  issues.forEach((i) => console.log('  - ' + i))
  process.exit(1)
} else {
  console.log('\n统一性 QA 全部通过')
}