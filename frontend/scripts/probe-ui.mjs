// 只读诊断：把用户截图里描述的现象逐条在真实渲染上核实
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { chromium } from 'playwright-core'
import { setTheme, setPalette } from './_theme.mjs'
import { mkdirSync } from 'node:fs'

const BASE = 'http://localhost:5175'
const OUT = join(tmpdir(), 'prisma-recover-qa-probe')
mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
})

const shot = async (n) => { await page.screenshot({ path: `${OUT}/${n}.png` }); console.log('shot', n) }

/* 顶部"青绿色粗线"：找出所有贴着视口顶边、且颜色接近主色的元素 */
async function probeTopEdge(label) {
  const found = await page.evaluate(() => {
    const out = []
    const R = 60
    document.querySelectorAll('*').forEach((el) => {
      const r = el.getBoundingClientRect()
      if (r.width < 120 || r.height > 40 || r.height < 1) return
      if (r.top > 8 && r.top < 24) {
        const cs = getComputedStyle(el)
        const bg = cs.backgroundColor, bi = cs.backgroundImage
        out.push({
          sel: el.tagName.toLowerCase() + '.' + (el.className?.toString?.().slice(0, 60) ?? ''),
          top: +r.top.toFixed(1), h: +r.height.toFixed(1), w: +r.width.toFixed(1),
          bg, bi: bi === 'none' ? '' : bi.slice(0, 90),
          bt: cs.borderTopWidth + ' ' + cs.borderTopColor,
          bb: cs.borderBottomWidth + ' ' + cs.borderBottomColor,
        })
      }
    })
    void R
    return out
  })
  console.log(`\n== 顶部元素 (${label}) ==`)
  found.forEach((f) => console.log(JSON.stringify(f)))
}

async function probeBorders(sel, label) {
  const r = await page.evaluate((s) => {
    return [...document.querySelectorAll(s)].slice(0, 8).map((el) => {
      const cs = getComputedStyle(el)
      const b = el.getBoundingClientRect()
      return {
        cls: el.className?.toString?.().slice(0, 50),
        rect: `${b.width.toFixed(0)}x${b.height.toFixed(0)}`,
        border: `${cs.borderTopWidth} ${cs.borderRightWidth} ${cs.borderBottomWidth} ${cs.borderLeftWidth} / ${cs.borderTopColor}`,
        bg: cs.backgroundColor, shadow: cs.boxShadow.slice(0, 70),
      }
    })
  }, sel)
  console.log(`\n== ${label} (${sel}) ==`)
  r.forEach((x) => console.log(JSON.stringify(x)))
}

// ---------- 登录页 ----------
await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
await page.fill('#li-u', 'ui_probe')
await page.fill('#li-p', 'ui_probe_123')
await page.waitForTimeout(300)
const authErr = await page.evaluate(() => [...document.querySelectorAll('.field')].map((f) => ({
  cls: f.className, err: f.querySelector('.err')?.textContent, visible: getComputedStyle(f.querySelector('.err')).display,
})))
console.log('\n== 登录页填了值后的校验态 ==', JSON.stringify(authErr))
await shot('login-filled')

// ---------- 真实登录 ----------
await page.locator('button[type="submit"]').click()
await page.waitForTimeout(2500)
console.log('登录后 url =', page.url())

// ---------- 应用内 ----------
await page.goto(BASE + '/#/app/chat', { waitUntil: 'networkidle' })
await page.waitForTimeout(2500)
await shot('chat')
await probeTopEdge('chat-light-terracotta')
await probeBorders('.sidebar', '侧栏')
await probeBorders('.chat-main', '对话主面板')
await probeBorders('.shell-main', '内容区')

// 顶栏内联样式
await probeBorders('.chat-main header', '对话顶栏')

// 设置弹窗：重新查看新手引导按钮
await page.locator('.sb-row--settings').click()
await page.waitForTimeout(600)
await shot('settings')
const replay = await page.evaluate(() => {
  const el = document.querySelector('.settings-dialog__replay')
  if (!el) return { found: false }
  const cs = getComputedStyle(el)
  return { found: true, border: cs.border, background: cs.backgroundColor, appearance: cs.appearance, font: cs.fontFamily, radius: cs.borderRadius, pad: cs.padding, color: cs.color, height: el.getBoundingClientRect().height }
})
console.log('\n== 重新查看新手引导按钮 ==', JSON.stringify(replay))
const navItem = await page.evaluate(() => {
  const el = document.querySelector('.settings-dialog__item[aria-current="true"]')
  const cs = getComputedStyle(el)
  return { bg: cs.backgroundColor, shadow: cs.boxShadow, weight: cs.fontWeight, all: [...document.querySelectorAll('.settings-dialog__item[aria-current="true"]')].map((x) => x.textContent) }
})
console.log('== 设置导航选中 ==', JSON.stringify(navItem))
await page.keyboard.press('Escape')
await page.waitForTimeout(400)

// 右下角浮层 NULL
const floating = await page.evaluate(() => {
  const out = []
  document.querySelectorAll('body > *').forEach((el) => {
    const cs = getComputedStyle(el); const b = el.getBoundingClientRect()
    if (cs.position === 'fixed') out.push({ cls: el.className?.toString?.().slice(0, 50), pos: `${b.left.toFixed(0)},${b.top.toFixed(0)}`, size: `${b.width.toFixed(0)}x${b.height.toFixed(0)}`, text: (el.textContent ?? '').trim().slice(0, 60) })
  })
  return out
})
console.log('\n== body 下 fixed 浮层 ==', JSON.stringify(floating, null, 1))

await browser.close()