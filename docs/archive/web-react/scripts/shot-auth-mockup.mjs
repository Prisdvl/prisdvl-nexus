// 登录注册原型截图：亮/暗 × 背景 A/B（无残留依赖，独立运行）
// 用法：node scripts/shot-auth-mockup.mjs  （工作目录 web-react）
import { chromium } from 'playwright-core'

const FILE = 'file:///D:/develop/Prisma/docs/mockups/登录注册原型%20v1.html'
const OUT = 'D:/develop/Prisma/docs/mockups/shots'
import { mkdirSync } from 'node:fs'

mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1.5 })

const jobs = [
  { name: 'auth-light-aurora',  qs: '?theme=light&variant=aurora' },
  { name: 'auth-dark-aurora',   qs: '?theme=dark&variant=aurora' },
  { name: 'auth-light-particles', qs: '?theme=light&variant=particles' },
  { name: 'auth-dark-particles',  qs: '?theme=dark&variant=particles' },
  { name: 'auth-register-light',  qs: '?theme=light&variant=aurora&tab=register' },
]

let failed = 0
for (const job of jobs) {
  try {
    await page.goto(FILE + job.qs, { waitUntil: 'load' })
    // 等入场动画 + 粒子首帧
    await page.waitForTimeout(1400)
    await page.screenshot({ path: `${OUT}/${job.name}.png` })
    console.log(`ok  ${job.name}`)
  } catch (e) {
    failed++
    console.log(`FAIL ${job.name}: ${String(e).split('\n')[0]}`)
  }
}

await browser.close()
console.log(failed === 0 ? '全部成功' : `失败 ${failed} 张`)
process.exit(failed === 0 ? 0 : 1)