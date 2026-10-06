/**
 * 前端现状速览：把当前所有页面截一遍，供人工查看。
 *
 * 与 verify-*.mjs 的区别：那些脚本**断言行为**（失败要退出码非 0），
 * 本脚本只**产出画面**——它的价值是"让人看到现在长什么样"，
 * 所以即使某页出错也继续截图，最后汇总哪些页有问题。
 */
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'

const targets = [
  { name: 'vue', base: process.env.VUE_BASE ?? 'http://localhost:5173', prefix: 'view-vue' },
  { name: 'react', base: process.env.R2_BASE ?? 'http://localhost:5174', prefix: 'view-react' }
]
const USERNAME = process.env.DEMO_USER ?? 'demo'
const PASSWORD = process.env.DEMO_PASSWORD ?? 'Demo@12345'
const apiBase = process.env.API_BASE ?? 'http://localhost:8088'
const outDir = 'D:/develop/Prisma/docs/screenshots/current'
mkdirSync(outDir, { recursive: true })

// 先拿一个知识库 id 与一个会话 id，供深层页面使用
const lr = await fetch(apiBase + '/api/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: USERNAME, password: PASSWORD })
})
const token = (await lr.json()).data.accessToken
const auth = { Authorization: 'Bearer ' + token }
const kbs = (await (await fetch(apiBase + '/api/knowledge-bases?pageNum=1&pageSize=50', { headers: auth })).json()).data.list
const kbId = kbs[0]?.id
console.log(`知识库：${kbs.length} 个，取「${kbs[0]?.name}」(${kbId})`)

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const summary = []

for (const t of targets) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 980 } })
  await ctx.addInitScript(
    (v) => {
      // 两套前端用的 storage key 不同，都写上，谁认哪个都行
      localStorage.setItem('nexus.token', v)
      localStorage.setItem('nexus.username', 'demo')
      localStorage.setItem('token', v)
    },
    token
  )
  const page = await ctx.newPage()
  const errors = []
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text().slice(0, 90)))

  const shot = async (label, url) => {
    const before = errors.length
    try {
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 25000 })
      await page.waitForTimeout(2500)
      const file = `${outDir}/${t.prefix}-${label}.png`
      await page.screenshot({ path: file })
      const text = (await page.locator('body').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 70)
      summary.push({ front: t.name, label, ok: true, errors: errors.length - before, text })
      console.log(`  ✓ ${t.name}/${label}  ${text}`)
    } catch (e) {
      summary.push({ front: t.name, label, ok: false, errors: 0, text: e.message.slice(0, 60) })
      console.log(`  ✗ ${t.name}/${label}  ${e.message.slice(0, 70)}`)
    }
  }

  // 路由差异：Vue 用 #/kb/:id，React 用 #/kb/:id；对话页 Vue 是 /agents 附近、React 是 /agent
  await shot('login', t.base + '/#/login')
  await shot('kb-list', t.base + '/#/')
  if (kbId) await shot('kb-detail', t.base + `/#/kb/${kbId}`)
  await shot('chat', t.base + '/#/agent')
  await shot('agents', t.base + '/#/agents')

  await ctx.close()
}

await browser.close()
console.log('\n=== 汇总 ===')
for (const s of summary) {
  console.log(`  ${s.ok ? '✓' : '✗'} ${s.front.padEnd(6)} ${s.label.padEnd(10)} console错误 ${s.errors}  ${s.text}`)
}
console.log(`\n截图目录：docs/screenshots/current/`)
