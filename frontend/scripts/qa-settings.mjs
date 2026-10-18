// 设置页（GitHub 式多级）专项 QA
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const BASE = process.env.QA_BASE ?? 'http://localhost:5175'
// 截图输出：显式 QA_SHOTS_OUT 优先；未设置时落到系统临时目录。
// 刻意**不**写进原型截图目录（已不再维护），也不写向任何其它 checkout 的绝对路径
// （只读备份仓库）。启动时打印实际目录，方便事后查看。
const OUT = process.env.QA_SHOTS_OUT ?? path.join(os.tmpdir(), 'prisma-recover-qa-shots')
mkdirSync(OUT, { recursive: true })
console.log(`QA 截图输出目录：${OUT}`)

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
// 引导只在真实首登时出现，避免污染用例/截图
await page.addInitScript(() => { localStorage.setItem('nx-proto-tour-done','1') })
const issues = []
page.on('pageerror', (e) => issues.push(`pageerror: ${String(e.message).slice(0, 120)}`))
page.on('console', (m) => {
  if (m.type() !== 'error') return
  const text = m.text()
  // 只忽略"开发服务器资源连接抖动"这一种（改代码时 Vite 重建会偶发），其余错误照旧计入
  if (/ERR_CONNECTION_CLOSED|ERR_CONNECTION_REFUSED|WebSocket|\[vite\]/i.test(text)) return
  issues.push(`console.error: ${text.slice(0, 120)}`)
})

const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`)
  if (!ok) issues.push(name)
}

await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done','1')   // 引导只在真实首登时出现，避免污染用例/截图
  localStorage.setItem('nx-proto-auth', '1')
  localStorage.setItem('nx-proto-theme-mode', 'light')
  localStorage.setItem('nx-proto-palette', 'terracotta')
})
await page.goto(BASE + '/#/app/account', { waitUntil: 'networkidle' })
await page.waitForTimeout(1800)

/* ---------- 1. 多级结构 ---------- */
check('设置页使用左右分栏布局', (await page.locator('.settings-layout').count()) === 1)
const groups = await page.locator('.settings-group').allInnerTexts()
check('左导航含 5 个分组', groups.length === 5, groups.join(' / '))
check('分组标题正确', ['个人', '访问与安全', '通知', '数据与用量', '危险区'].every((g) => groups.includes(g)), groups.join('/'))
const items = await page.locator('.settings-item').count()
check('共 12 个设置分区', items === 12, `${items} 个`)
check('默认停在「公开资料」', (await page.locator('.settings-item[aria-current="true"]').innerText()).includes('公开资料'))
check('有面包屑（分组 / 分区）', (await page.locator('.settings-crumbs').innerText()).includes('个人'))

/* ---------- 2. 逐个分区都能渲染（不残留旧内容） ---------- */
const LABELS = ['公开资料', '模型 Key', '嵌入模型', '账号偏好', '外观', '密码与登录', '邮箱验证', '会话与设备', '通知偏好', '用量配额', '数据导出', '注销账号']
await page.evaluate(() => {
  window.__swapped = 0
  document.addEventListener('animationstart', (e) => { if (e.animationName === 'route-in') window.__swapped++ }, true)
})
let allRendered = true
const rendered = []
for (const label of LABELS) {
  await page.locator('.settings-item', { hasText: label }).first().click()
  await page.waitForTimeout(420)
  const title = await page.locator('.settings-head h2').first().innerText().catch(() => '')
  const panels = await page.locator('.settings-body .panel').count()
  rendered.push(`${label}→${title}`)
  if (!title || panels !== 1) allRendered = false
}
check('12 个分区都能切换到对应内容', allRendered, rendered.join(' | '))
check('每次切换都播放进入过渡', (await page.evaluate(() => window.__swapped)) >= LABELS.length - 1, `${await page.evaluate(() => window.__swapped)} 次`)

/* ---------- 2.5 嵌入模型（BYOK 路线 A）：白名单下拉 + 固定维度 ---------- */
await page.locator('.settings-item', { hasText: '嵌入模型' }).first().click()
await page.waitForTimeout(420)
check('嵌入模型：供应商下拉来自白名单', (await page.locator('#ek-provider option').allInnerTexts()).join() === 'bailian', (await page.locator('#ek-provider option').allInnerTexts()).join('/'))
check('嵌入模型：模型下拉来自白名单', (await page.locator('#ek-model option').allInnerTexts()).join() === 'text-embedding-v4', (await page.locator('#ek-model option').allInnerTexts()).join('/'))
check('嵌入模型：维度只读且固定 1024', (await page.locator('#ek-dim').isDisabled()) && (await page.locator('#ek-dim').inputValue()) === '1024')
check('嵌入模型：Base URL / Key / 启用开关 / 保存按钮齐备',
  (await page.locator('#ek-base').count()) === 1 && (await page.locator('#ek-key').count()) === 1
  && (await page.locator('.settings-body [role=switch]').count()) === 1
  && (await page.locator('.settings-body button[type=submit]').count()) === 1)
check('嵌入模型：明确说明旧库不会自动迁移', (await page.locator('.settings-body').innerText()).includes('冻结'))

/* ---------- 3. 各分区关键控件在位 ---------- */
const byItem = async (label) => { await page.locator('.settings-item', { hasText: label }).first().click(); await page.waitForTimeout(420) }
await byItem('外观')
const appearanceRubbers = await page.locator('.settings-body .appearance .rubber-segment').count()
const prisdvlNote = await page.locator('.settings-body .appearance .appearance-note').count()
const swatches = await page.locator('.settings-body .accent-swatches .accent-swatch').count()
check('外观分区有主题分段或品牌主题说明与配色色板', (appearanceRubbers === 1 || prisdvlNote === 1) && swatches >= 4, `rubber=${appearanceRubbers} prisdvl=${prisdvlNote} swatches=${swatches}`)

/* 橡皮分段：可视拇指宽度必须恰好一格
   （历史 bug：clip 停在 inset(0) 四边为 0 = 整个轨道露出，看起来"所有选项同时被选中"） */
const thumbGeometry = await page.evaluate(() => Array.from(document.querySelectorAll('.settings-body .rubber-segment')).map((seg) => {
  const thumb = seg.querySelector('.rubber-segment__thumb')
  const clip = getComputedStyle(thumb).clipPath
  const raw = (clip.match(/inset\(([^)]*)\)/)?.[1] || '').replace(/round.*/, '').trim().split(/\s+/).filter(Boolean).map(parseFloat)
  /* CSS inset() 简写会收敛成 1~4 个值，必须按规范展开，否则会误判（竖向分段就是 3 值形式） */
  const expand = (nums) => {
    if (nums.length === 4) return nums
    if (nums.length === 3) return [nums[0], nums[1], nums[2], nums[1]]
    if (nums.length === 2) return [nums[0], nums[1], nums[0], nums[1]]
    if (nums.length === 1) return [nums[0], nums[0], nums[0], nums[0]]
    return [0, 0, 0, 0]
  }
  const [top, right, bottom, left] = expand(raw)
  const track = seg.getBoundingClientRect()
  const inner = track.width - 6
  const items = seg.querySelectorAll('button.rubber-segment__item').length
  return { label: seg.getAttribute('aria-label'), visible: Math.round(inner - left - right), slot: Math.round(inner / items) }
}))
check(
  '每个分段只露出一格拇指（不是全部选中）',
  thumbGeometry.every((g) => Math.abs(g.visible - g.slot) <= 3),
  thumbGeometry.map((g) => `${g.label}=${g.visible}/应${g.slot}`).join('  ')
)
await byItem('通知偏好')
check('通知偏好有 3 个开关', (await page.locator('.settings-body .squish-switch, .settings-body [role="switch"]').count()) >= 3, `${await page.locator('.settings-body [role="switch"]').count()} 个`)
await byItem('数据导出')
check('数据导出有格式分段', (await page.locator('.settings-body .rubber-segment').count()) === 1)

/* 反馈数据必须能导出（否则"改进闭环"无从分析） */
check('数据导出含回答反馈卡', (await page.locator('.settings-body').innerText()).includes('回答反馈'))
check('反馈卡提供 JSON / CSV 导出', (await page.locator("button[aria-label='导出反馈 JSON']").count()) === 1 && (await page.locator("button[aria-label='导出反馈 CSV']").count()) === 1)
check('反馈卡可清空', (await page.locator("button[aria-label='清空反馈数据']").count()) === 1)

/* 工具授权：被"始终允许"的高风险工具必须能收回（授权只增不减 = 信任漏洞） */
await page.evaluate(() => localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox'])))
await page.reload({ waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
await page.locator('.settings-item', { hasText: '密码与登录' }).first().click()
await page.waitForTimeout(600)
check('设置里能看到已授权的工具', (await page.locator('.settings-body').innerText()).includes('js.sandbox'))
await page.locator("button[aria-label='撤销 js.sandbox 的授权']").click()
await page.waitForTimeout(600)
check('撤销后策略被清空', (await page.evaluate(() => localStorage.getItem('nx-proto-tool-policy'))) === '[]')
await byItem('密码与登录')
check('密码与登录有修改密码按钮', (await page.locator('.settings-body button', { hasText: '修改密码' }).count()) === 1)
await byItem('会话与设备')
check('会话管理显示当前浏览器会话', (await page.locator('.settings-body .list-row').count()) === 1 && (await page.locator('.settings-body').innerText()).includes('当前浏览器'))
await byItem('注销账号')
check('注销账号无法在后端不支持时执行', (await page.locator('.settings-body .btn-danger[disabled]').count()) === 1)
check('注销账号面板有危险色边框', (await page.locator('.settings-body .panel-danger').count()) === 1)

check('注销账号不显示假流程：服务端无注销接口时按钮必须禁用', (await page.locator('.settings-body .btn-danger[disabled]').count()) === 1)
check('注销账号明确说明后端暂不支持', (await page.locator('.settings-body').innerText()).includes('服务端目前没有账号注销接口'))
/* ---------- 4. 设置搜索 ---------- */
await page.locator('.settings-search input').fill('主题')
await page.waitForTimeout(420)
check('搜「主题」命中「外观」', (await page.locator('.settings-item').count()) === 1 && (await page.locator('.settings-item').innerText()).includes('外观'))
await page.locator('.settings-search input').fill('密码')
await page.waitForTimeout(420)
const pwdHits = await page.locator('.settings-item').allInnerTexts()
check('搜「密码」命中密码与邮箱相关项', pwdHits.some((t) => t.includes('密码与登录')) && pwdHits.some((t) => t.includes('邮箱验证')), pwdHits.join('/'))
await page.locator('.settings-search input').fill('向量')
await page.waitForTimeout(420)
const vecHits = await page.locator('.settings-item').allInnerTexts()
check('搜「向量」命中嵌入模型', vecHits.some((t) => t.includes('嵌入模型')), vecHits.join('/'))
await page.locator('.settings-search input').fill('zzzz')
await page.waitForTimeout(420)
check('无匹配时显示空状态', (await page.locator('.settings-empty').count()) === 1 && (await page.locator('.settings-item').count()) === 0)
await page.locator('.settings-search input').fill('')
await page.waitForTimeout(420)
check('清空搜索后恢复 12 项', (await page.locator('.settings-item').count()) === 12)

/* ---------- 5. 桌面截图 ---------- */
await page.locator('.settings-item', { hasText: '外观' }).first().click()
await page.waitForTimeout(600)
await page.screenshot({ path: `${OUT}/z7-settings-desktop-light.png` })
console.log('ok   z7-settings-desktop-light（截图）')
await page.evaluate(() => window.__nxTheme.setMode('dark'))
await page.waitForTimeout(700)
await page.screenshot({ path: `${OUT}/z7-settings-desktop-dark.png` })
console.log('ok   z7-settings-desktop-dark（截图）')
await page.evaluate(() => window.__nxTheme.setMode('light'))
await page.waitForTimeout(400)

/* ---------- 6. 手机端：横向分类 tab ---------- */
await page.setViewportSize({ width: 390, height: 844 })
await page.waitForTimeout(900)
const mobile = await page.evaluate(() => {
  const nav = document.querySelector('.settings-nav')
  const item = document.querySelector('.settings-item')
  const group = document.querySelector('.settings-group')
  const cs = getComputedStyle(nav)
  return {
    dir: cs.flexDirection,
    scrollable: nav.scrollWidth > nav.clientWidth + 2,
    groupHidden: getComputedStyle(group).display === 'none',
    itemRadius: parseFloat(getComputedStyle(item).borderTopLeftRadius),
    columns: getComputedStyle(document.querySelector('.settings-layout')).gridTemplateColumns.split(' ').length,
  }
})
check('手机：导航变横向', mobile.dir === 'row', mobile.dir)
check('手机：可横向滑动', mobile.scrollable)
check('手机：分组标题隐藏', mobile.groupHidden)
check('手机：分区项是胶囊形', mobile.itemRadius >= 100, String(mobile.itemRadius))
check('手机：单列布局', mobile.columns === 1, String(mobile.columns))
await page.screenshot({ path: `${OUT}/z7-settings-mobile.png` })
console.log('ok   z7-settings-mobile（截图）')

await browser.close()
if (issues.length) {
  console.log(`\n发现 ${issues.length} 个问题:`)
  issues.forEach((i) => console.log('  - ' + i))
  process.exit(1)
} else {
  console.log('\n设置页专项 QA 全部通过')
}
