// 文档预览与文档行专项 QA
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'

const BASE = 'http://localhost:5175'
const OUT = process.env.QA_SHOTS_OUT ?? join(tmpdir(), 'prisma-recover-qa-shots')
mkdirSync(OUT, { recursive: true })

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

await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done','1')   // 引导只在真实首登时出现，避免污染用例/截图
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))   // 高风险工具预授权：常规用例不被确认卡打断
  localStorage.setItem('nx-proto-theme', 'dark')
  localStorage.setItem('nx-proto-auth', '1')
})
await page.goto(BASE + '/#/app/kb/1', { waitUntil: 'networkidle' })
await page.waitForTimeout(1800)

/* ---------- 1. 状态列：靠右 + 带文字 + 所有行对齐 ---------- */
const statusInfo = await page.evaluate(() => {
  const rows = Array.from(document.querySelectorAll('.doc-row'))
  const rights = rows.map((r) => {
    const s = r.querySelector('.doc-status')
    return s ? Math.round(s.getBoundingClientRect().right) : null
  })
  const labels = Array.from(document.querySelectorAll('.doc-row .status-mark__label'))
    .map((el) => ({ text: el.textContent || '', display: getComputedStyle(el).display }))
  return { rights, labels }
})
const uniqRights = new Set(statusInfo.rights.filter(Boolean))
check('状态列靠右且所有行右边界一致', uniqRights.size === 1, `右边界：${[...uniqRights].join(', ')}`)
check('状态带可见文字标签', statusInfo.labels.some((l) => l.display !== 'none' && l.text.length > 0), JSON.stringify(statusInfo.labels.slice(0, 3)))
check('三种状态文字都出现', ['已索引', '解析中', '失败'].every((x) => statusInfo.labels.some((l) => l.text.includes(x))), statusInfo.labels.map((l) => l.text).join('/'))

/* ---------- 2. 图标中性化（不吃强调色） ---------- */
// 与 token 的"实际计算值"对比：新建探针元素取同一变量的 computed 值，避免比字符串字面量
const iconCmp = await page.evaluate(() => {
  const probe = document.createElement('span')
  const read = (varName) => {
    probe.style.backgroundColor = `var(${varName})`
    document.body.appendChild(probe)
    const v = getComputedStyle(probe).backgroundColor
    probe.remove()
    return v
  }
  const neutral = read('--surface-muted')
  const accent = read('--primary-container')
  const icon = getComputedStyle(document.querySelector('.doc-row .ic')).backgroundColor
  return { icon, neutral, accent }
})
check('文件图标底 = 中性 token（--surface-muted）', iconCmp.icon === iconCmp.neutral, `icon=${iconCmp.icon} / neutral=${iconCmp.neutral}`)
check('文件图标底 ≠ 强调容器（强调色已收敛）', iconCmp.icon !== iconCmp.accent, `icon=${iconCmp.icon} / accent=${iconCmp.accent}`)

/* ---------- 3. 点击 .md 文档 → 抽屉渲染正文 ---------- */
await page.locator('.doc-row').first().click()
await page.waitForTimeout(900)
check('预览抽屉打开', (await page.locator('.drawer').count()) === 1)
check('抽屉显示文档名', (await page.locator('.drawer-title').innerText()).includes('集合框架'))
check('Markdown 正文已渲染（有标题与代码块）', (await page.locator('.drawer .md h1, .drawer .md h2').count()) > 0 && (await page.locator('.drawer .md pre code').count()) > 0)
await page.waitForTimeout(2500)
check('抽屉里 Mermaid 渲染成 SVG', (await page.locator('.drawer .md-mermaid svg').count()) > 0)
check('抽屉显示切片列表', (await page.locator('.doc-chunk').count()) >= 3)
await page.screenshot({ path: `${OUT}/w-doc-preview-md.png` })
console.log('ok   w-doc-preview-md（截图）')

/* ---------- 4. Esc 关闭 + 焦点归还 ---------- */
await page.keyboard.press('Escape')
await page.waitForTimeout(500)
check('Esc 关闭抽屉', (await page.locator('.drawer').count()) === 0)
const focusBack = await page.evaluate(() => document.activeElement?.classList.contains('doc-row') || document.activeElement?.getAttribute('role') === 'button')
check('关闭后焦点回到触发元素', focusBack)

/* ---------- 5. PDF → 占位 + 下载 ---------- */
const pdfRow = page.locator('.doc-row', { hasText: 'MySQL 索引原理.pdf' }).first()
await pdfRow.click()
await page.waitForTimeout(700)
check('PDF 打开占位面板', (await page.locator('.doc-binary').count()) === 1)
check('PDF 原文不可下载时操作明确禁用', (await page.locator('.drawer-head button[disabled][title*="暂不支持下载"]').count()) === 1)
await page.screenshot({ path: `${OUT}/w-doc-preview-pdf.png` })
console.log('ok   w-doc-preview-pdf（截图）')
await page.keyboard.press('Escape')
await page.waitForTimeout(400)

/* ---------- 6. 删除按钮不误触预览 ---------- */
await page.locator('.doc-row').first().locator('.doc-actions button').click()
await page.waitForTimeout(500)
check('点删除不会打开预览抽屉', (await page.locator('.drawer').count()) === 0)
check('删除仍弹出撤销提示', (await page.locator('.toast-host button', { hasText: '撤销' }).count()) > 0)
await page.locator('.toast-host button', { hasText: '撤销' }).first().click()
await page.waitForTimeout(600)

/* ---------- 7. 滚动条：细窄 + 稳定槽位 ---------- */
const sb = await page.evaluate(() => {
  const html = getComputedStyle(document.documentElement)
  const body = getComputedStyle(document.body)
  return { width: html.scrollbarWidth || body.scrollbarWidth, gutter: html.scrollbarGutter || body.scrollbarGutter }
})
check('滚动条启用 thin', sb.width === 'thin', JSON.stringify(sb))
check('启用 scrollbar-gutter: stable（避免布局跳动）', (sb.gutter || '').includes('stable'), sb.gutter)

await browser.close()
if (issues.length) {
  console.log(`\n发现 ${issues.length} 个问题:`)
  issues.forEach((i) => console.log('  - ' + i))
  process.exit(1)
} else {
  console.log('\n文档预览专项 QA 全部通过')
}