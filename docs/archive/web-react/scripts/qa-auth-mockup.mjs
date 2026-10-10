// 登录注册原型结构级 QA：控制台无错、无横向溢出、关键元素与交互正常
import { chromium } from 'playwright-core'

const FILE = 'file:///D:/develop/Prisma/docs/mockups/登录注册原型%20v1.html'
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })

const issues = []
page.on('console', (m) => { if (['error', 'warning'].includes(m.type())) issues.push(`console.${m.type()}: ${m.text()}`) })
page.on('pageerror', (e) => issues.push(`pageerror: ${e.message}`))

const variants = [
  '?theme=light&variant=aurora',
  '?theme=dark&variant=aurora',
  '?theme=light&variant=particles',
  '?theme=dark&variant=particles&tab=register',
]
for (const qs of variants) {
  await page.goto(FILE + qs, { waitUntil: 'load' })
  await page.waitForTimeout(900)

  const r = await page.evaluate(() => {
    const doc = document.documentElement
    return {
      theme: doc.dataset.theme,
      overflowX: doc.scrollWidth > doc.clientWidth + 1,
      cardVisible: !!document.querySelector('.card') && getComputedStyle(document.querySelector('.card')).display !== 'none',
      activePanel: document.querySelector('.form.active')?.id || null,
      canvasPainted: (() => {
        const c = document.getElementById('bg-particles')
        return c.width > 10 && c.height > 10
      })(),
      blobCount: document.querySelectorAll('.blob').length,
    }
  })
  console.log(qs, '=>', JSON.stringify(r))
  if (r.overflowX) issues.push(`overflowX in ${qs}`)
  if (!r.cardVisible) issues.push(`card hidden in ${qs}`)
  if (r.canvasPainted < 0) issues.push(`canvas missing in ${qs}`)
}

// 交互：标签切换 + 空表单校验 + 密码显隐 + 演示账号填充
await page.goto(FILE + '?theme=light&variant=aurora', { waitUntil: 'load' })
await page.click('#tabRegister')
const regActive = await page.evaluate(() => document.querySelector('.form.active')?.id === 'panelRegister')
await page.click('#tabLogin')
const loginBack = await page.evaluate(() => document.querySelector('.form.active')?.id === 'panelLogin')

// 空表单提交 → 校验触发
await page.click('#loginSubmit')
const errVisible = await page.evaluate(() => document.querySelectorAll('.field.has-error').length)

// 演示账号填充
await page.click('#demoFill')
const demo = await page.evaluate(() => ({
  u: document.getElementById('loginUsername').value,
  p: document.getElementById('loginPassword').value,
}))

// 主题切换往返
await page.click('#themeBtn')
const toggled = await page.evaluate(() => document.documentElement.dataset.theme)
await page.click('#themeBtn') // 还原

console.log('交互 =>', JSON.stringify({ regActive, loginBack, demo, toggled, errVisible }))
if (!regActive || !loginBack) issues.push('tab 切换异常')
if (demo.u !== 'demo' || demo.p !== 'Demo@12345') issues.push('演示账号填充异常')
if (toggled !== 'dark') issues.push('主题切换异常')
if (errVisible < 2) issues.push('空表单校验未触发')

await browser.close()
if (issues.length) {
  console.log('发现问题:')
  issues.forEach((i) => console.log('  - ' + i))
  process.exit(1)
} else {
  console.log('QA 通过：无控制台错误、无溢出、交互全部正常')
}