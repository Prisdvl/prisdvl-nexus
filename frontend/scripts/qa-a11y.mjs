// 无障碍常驻回归：跳转链接 / 地标 / 标题层级 / 可访问名 / 焦点可见
import { chromium } from 'playwright-core'

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
page.on('pageerror', (e) => issues.push(`pageerror: ${String(e.message).slice(0, 120)}`))

const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`)
  if (!ok) issues.push(name)
}

const login = async () => {
  await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1400)
  await page.locator('.auth-card .rubber-segment button.rubber-segment__item', { hasText: '登录' }).first().click()
  await page.locator('#li-u').fill('qa-runner')
  await page.locator('#li-p').fill('qa-runner-password')
  await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
  await page.waitForSelector('.shell', { timeout: 8000 })
  await page.waitForTimeout(1200)
}

/** 页面级结构审计 */
async function audit(label) {
  const r = await page.evaluate(() => {
    const visible = (el) => el.offsetParent !== null || getComputedStyle(el).position === 'fixed'

    // 可访问名：label[for] / aria-label / aria-labelledby / 包裹 label / 相邻 label 文本
    const accName = (el) => {
      const byFor = el.id ? document.querySelector(`label[for="${el.id}"]`) : null
      const wrapping = el.closest('label')
      const aria = el.getAttribute('aria-label') || (el.getAttribute('aria-labelledby') && document.getElementById(el.getAttribute('aria-labelledby'))?.textContent)
      return (byFor?.textContent || wrapping?.textContent || aria || '').trim()
    }

    const segLabel = (el) => (el.innerText || '').trim() || el.getAttribute('aria-label') || el.getAttribute('title') || ''

    return {
      skip: document.querySelectorAll('.skip-link').length,
      main: document.querySelectorAll('main#main-content').length,
      h1: Array.from(document.querySelectorAll('h1')).length,
      headingSkips: (() => {
        const lv = Array.from(document.querySelectorAll('h1,h2,h3,h4,h5,h6')).map((h) => Number(h.tagName[1]))
        return lv.filter((v, i) => i > 0 && v - lv[i - 1] > 1).length
      })(),
      namelessButtons: Array.from(document.querySelectorAll('button')).filter((b) => visible(b) && !segLabel(b)).map((b) => (b.className || '').toString().slice(0, 40)),
      unlabeled: Array.from(document.querySelectorAll('input,textarea,select')).filter((el) => {
        if (el.type === 'hidden') return false
        return !accName(el)
      }).map((el) => `${el.tagName.toLowerCase()}[${el.type || ''}]${el.id ? '#' + el.id : ''}${el.placeholder ? ' ph=' + el.placeholder.slice(0, 12) : ''}`),
      positiveTabindex: Array.from(document.querySelectorAll('[tabindex]')).filter((el) => Number(el.getAttribute('tabindex')) > 0).length,
    }
  })

  check(`${label}：有跳到主内容按钮`, r.skip === 1, `找到 ${r.skip} 个`)
  check(`${label}：有 main 地标`, r.main === 1, `找到 ${r.main} 个`)
  check(`${label}：恰好一个 h1`, r.h1 === 1, `找到 ${r.h1} 个`)
  check(`${label}：标题不跳级`, r.headingSkips === 0, `跳级 ${r.headingSkips} 处`)
  check(`${label}：无无名按钮`, r.namelessButtons.length === 0, r.namelessButtons.slice(0, 3).join(' | '))
  check(`${label}：表单控件都有可访问名`, r.unlabeled.length === 0, r.unlabeled.slice(0, 4).join(' | '))
  check(`${label}：无正 tabindex`, r.positiveTabindex === 0)
}

/** 跳到主内容：聚焦时可见 + 点击后焦点落到 main */
async function auditSkip(label) {
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.keyboard.press('Tab') // 第一个可聚焦元素应当就是它
  await page.waitForTimeout(400)   // 等浮出过渡跑完（否则读到的是 translateY 中间值）
  const info = await page.evaluate(() => {
    const el = document.activeElement
    if (!el || !el.classList.contains('skip-link')) return { focused: false }
    const cs = getComputedStyle(el)
    return { focused: true, transform: cs.transform, visible: el.getBoundingClientRect().top > -5 }
  })
  check(`${label}：Tab 首个焦点是跳转按钮`, info.focused, JSON.stringify(info))
  if (info.focused) check(`${label}：聚焦后跳转按钮可见`, info.visible === true)

  await page.evaluate(() => document.activeElement?.click())
  await page.waitForTimeout(400)
  const focusedMain = await page.evaluate(() => document.activeElement?.id === 'main-content')
  check(`${label}：跳转后焦点落到主内容`, focusedMain)
}

/** 焦点可见性：Tab N 次，逐个检查有可见焦点样式 */
async function auditFocus(label, steps = 25) {
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.locator('body').click({ position: { x: 2, y: 2 } }).catch(() => {})
  const bad = []
  for (let i = 0; i < steps; i++) {
    await page.keyboard.press('Tab')
    const badOne = await page.evaluate(() => {
      const el = document.activeElement
      if (!el || el === document.body || el.classList.contains('skip-link')) return null
      const cs = getComputedStyle(el)
      const ok = (cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0) || (cs.boxShadow && cs.boxShadow !== 'none')
      return ok ? null : `${el.tagName.toLowerCase()}.${(el.className || '').toString().slice(0, 30)}`
    })
    if (badOne) bad.push(badOne)
  }
  check(`${label}：焦点样式可见（Tab ${steps} 次）`, bad.length === 0, bad.slice(0, 4).join(' | '))
}

console.log('=== 入口页 ===')
await page.goto(BASE, { waitUntil: 'networkidle' })
await page.waitForTimeout(2200)
await audit('入口页')
await auditSkip('入口页')
await auditFocus('入口页', 20)

/* 落地页 CTA 不得破坏 hash 路由（曾用 href="#flow"，被路由吃掉且不滚动） */
const beforeHash = await page.evaluate(() => location.hash)
await page.locator('button.btn-ghost', { hasText: '了解流程' }).click()
await page.waitForTimeout(1200)
const afterHash = await page.evaluate(() => location.hash)
const scrolled = await page.evaluate(() => {
  const el = document.getElementById('flow')
  if (!el) return false
  const r = el.getBoundingClientRect()
  return r.top < window.innerHeight * 0.7 && r.bottom > 0
})
check('入口页 CTA 不改变路由', afterHash === beforeHash, `${beforeHash} → ${afterHash}`)
check('入口页 CTA 真的滚动到目标区块', scrolled)

console.log('\n=== 登录页 ===')
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(1500)
await audit('登录页')

console.log('\n=== 应用内 ===')
await login()
for (const [label, path] of [
  ['工作台', '/#/app/workbench'],
  ['知识库', '/#/app/kb'],
  ['知识库详情', '/#/app/kb/1'],
  ['对话', '/#/app/chat'],
  ['Agent', '/#/app/agents'],
  ['自动化', '/#/app/automations'],
  ['工作流', '/#/app/workflows'],
  ['插件', '/#/app/plugins'],
  ['Agent 社区', '/#/app/community'],
  ['账号', '/#/app/account'],
]) {
  console.log(`\n=== ${label} ===`)
  await page.goto(BASE + path, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1600)
  await audit(label)
  if (label === '工作台') { await auditSkip(label); await auditFocus(label, 30) }
}

await browser.close()
if (issues.length) {
  console.log(`\n发现 ${issues.length} 个问题:`)
  issues.forEach((i) => console.log('  - ' + i))
  process.exit(1)
} else {
  console.log('\n无障碍 QA 全部通过')
}
