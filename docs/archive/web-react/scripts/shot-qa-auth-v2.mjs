// v2 四配色原型：QA + 全视角截图（4 配色 × 亮/暗 = 8 张）
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'

const FILE = 'file:///D:/develop/Prisma/docs/mockups/登录注册原型%20v2.html'
const OUT = 'D:/develop/Prisma/docs/mockups/shots'
mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1.5 })
const issues = []
page.on('console', (m) => { if (['error', 'warning'].includes(m.type())) issues.push(`console.${m.type()}: ${m.text()}`) })
page.on('pageerror', (e) => issues.push(`pageerror: ${e.message}`))

const palettes = ['terracotta', 'turquoise', 'plum', 'lime']
const themes = ['light', 'dark']

for (const p of palettes) {
  for (const t of themes) {
    const qs = `?p=${p}&theme=${t}`
    await page.goto(FILE + qs, { waitUntil: 'load' })
    await page.waitForTimeout(1200)
    const r = await page.evaluate(() => {
      const doc = document.documentElement
      return {
        palette: doc.dataset.palette,
        theme: doc.dataset.theme,
        overflowX: doc.scrollWidth > doc.clientWidth + 1,
        cardVisible: getComputedStyle(document.querySelector('.card')).display !== 'none',
        paletteName: document.getElementById('paletteName').textContent,
        particles: (() => { const c = document.getElementById('bg-particles'); return c.width > 50 && c.height > 50 })(),
      }
    })
    const ok = r.palette === p && r.theme === t && !r.overflowX && r.cardVisible && r.particles
    console.log(`${ok ? 'ok ' : 'FAIL'} ${p}/${t} -> ${JSON.stringify(r)}`)
    if (!ok) issues.push(`combo ${p}/${t}: ${JSON.stringify(r)}`)
    await page.screenshot({ path: `${OUT}/v2-${p}-${t}.png` })
  }
}

// 交互抽查
await page.goto(FILE + '?p=terracotta&theme=light', { waitUntil: 'load' })
await page.click('#tabRegister')
const reg = await page.evaluate(() => document.querySelector('.form.active')?.id === 'panelRegister')
await page.click('#tabLogin')
await page.click('#loginSubmit')
const errs = await page.evaluate(() => document.querySelectorAll('.field.has-error').length)
await page.click('#demoFill')
const demo = await page.evaluate(() => [document.getElementById('loginUsername').value, document.getElementById('loginPassword').value])
await page.click('#paletteGroup [data-palette="lime"]')
const switched = await page.evaluate(() => document.documentElement.dataset.palette)
console.log('交互 =>', JSON.stringify({ reg, errs, demo, switched }))
if (!reg || errs < 2 || demo.join() !== 'demo,Demo@12345' || switched !== 'lime') issues.push('交互异常')

await browser.close()
if (issues.length) { console.log('发现问题:'); issues.forEach((i) => console.log('  - ' + i)); process.exit(1) }
console.log('QA 通过：4 配色 × 2 主题无错误，交互正常，8 张截图已保存')