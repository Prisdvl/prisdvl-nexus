// 主题矩阵诊断：逐配色×明暗抓取关键页面，采样顶边像素与关键元素样式
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { chromium } from 'playwright-core'
import { setTheme, setPalette } from './_theme.mjs'
import { mkdirSync } from 'node:fs'

const BASE = 'http://localhost:5175'
const OUT = join(tmpdir(), 'prisma-recover-qa-matrix')
mkdirSync(OUT, { recursive: true })

const palettes = (process.env.PALETTES ?? 'turquoise,terracotta,lime').split(',')
const modes = (process.env.MODES ?? 'light,dark').split(',')
const routes = (process.env.ROUTES ?? 'app/workbench,app/chat,app/kb,app/agents,login').split(',').map((r) => '/' + r.replace(/^\//, ''))
const W = Number(process.env.W ?? 1440)
const H = Number(process.env.H ?? 900)

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: W, height: H } })
await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
})

async function login() {
  await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
  await page.waitForTimeout(800)
  if ((page.url().includes('login'))) {
    await page.fill('#li-u', 'demo')
    await page.fill('#li-p', 'demo1234')
    await page.locator('button[type="submit"]').click()
    await page.waitForTimeout(2000)
  }
}
await login()

for (const palette of palettes) {
  for (const mode of modes) {
    await setPalette(page, palette)
    await setTheme(page, mode)
    for (const route of routes) {
      await page.goto(BASE + '/#' + route, { waitUntil: 'domcontentloaded' })
      await page.waitForTimeout(1600)
      const tag = `${palette}-${mode}-${route.replace(/[/#]/g, '')}`
      await page.screenshot({ path: `${OUT}/${tag}.png` })
      const info = await page.evaluate(() => {
        const sm = document.querySelector('.shell-main')
        const sb = document.querySelector('.sidebar')
        const cs = sm ? getComputedStyle(sm) : null
        const canvas = document.querySelector('canvas')
        return {
          shellMainBg: cs ? cs.backgroundColor : null,
          sidebarBg: sb ? getComputedStyle(sb).backgroundColor : null,
          hasCanvas: !!canvas,
          overflowX: document.documentElement.scrollWidth > document.documentElement.clientWidth,
        }
      })
      console.log(tag, JSON.stringify(info))
    }
  }
}
await browser.close()
