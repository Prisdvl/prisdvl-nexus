// 会话栏专项 QA：切换过渡 / 滚动位置记忆 / 紧凑密度 / 分组 / 悬停操作
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'

const BASE = process.env.NEXUS_BASE ?? 'http://localhost:5175'
const OUT = process.env.QA_SHOTS_OUT ?? 'D:/develop/Prisma/docs/prototypes/shots'
mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })
// 用矮视口让消息区可滚动——否则"滚动位置记忆"根本无从验证
const page = await browser.newPage({ viewport: { width: 1440, height: 430 } })
// 引导只在真实首登时出现，避免污染用例/截图
await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')
  /* 预置"高风险工具已授权"：常规流程不被确认卡打断；
     确认卡专项断言会先清掉它，再验证 awaiting 状态机 */
  localStorage.setItem('nx-proto-tool-policy', JSON.stringify(['js.sandbox']))
})
const issues = []
const consoleErrors = []
page.on('pageerror', (e) => issues.push(`pageerror: ${String(e.message).slice(0, 140)}`))
page.on('console', (m) => {
  if (m.type() !== 'error') return
  const text = m.text()
  if (/ERR_CONNECTION_CLOSED|ERR_CONNECTION_REFUSED|WebSocket|\[vite\]/i.test(text)) return
  consoleErrors.push(text.slice(0, 160))
})
page.on('console', (m) => { if (m.type() === 'error') issues.push(`console.error: ${m.text().slice(0, 140)}`) })

const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`)
  if (!ok) issues.push(name)
}

await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done','1')   // 引导只在真实首登时出现，避免污染用例/截图
  localStorage.setItem('nx-proto-theme', 'dark')
  localStorage.setItem('nx-proto-auth', '1')
})
await page.goto(BASE + '/#/app/chat', { waitUntil: 'networkidle' })
await page.waitForTimeout(1800)

/* ---------- 1. 紧凑密度 ---------- */
const rowH = await page.locator('.session-list .session-item').first().evaluate((el) => Math.round(el.getBoundingClientRect().height))
check('会话项是侧栏的紧凑行（34px）', rowH === 34, `${rowH}px`)
const noClip = await page.locator('.session-item').first().evaluate((el) => el.scrollHeight <= el.clientHeight + 1)
check('会话项内容不被裁切', noClip)

/* ---------- 2. 分组标签 ---------- */
const groups = await page.locator('.sb-section--toggle').allInnerTexts()
/* 置顶标题现在带计数（置顶（1）），用包含匹配而不是全等 */
/*
 * 分区顺序：置顶在上、最近在下（顺序错了会被这条拦住）。
 *
 * ⚠️ 这里**不再断言「项目」在最前**：项目栏目被老大拍板暂缓
 * （后端 chat_conversation 没有 project 字段，前端 projectId 已移除），
 * Sidebar.tsx:319 用 `{false && ...}` 硬编码关闭。
 * 断言一个"决定不显示"的栏目存在，等于把决策当回归——
 * 详见下方 7.58 段的说明。恢复项目功能时把这条断言加回来。
 */
check('分区顺序是「置顶 → 最近」', groups[0]?.includes('置顶') && groups[1]?.includes('最近'), groups.join(' / '))

/* ---------- 3. 操作是悬停显露的行内图标（不常驻、不挤占标题） ---------- */
check('会话行提供置顶/归档/删除图标操作', (await page.locator('.session-actions .session-action').count()) >= 9)
const barOpacity = await page.locator('.session-list .session-item').first()
  .evaluate((el) => getComputedStyle(el.querySelector('.session-actions')).opacity)
check('动作栏默认隐藏（不常驻挤占标题）', barOpacity === '0', `opacity=${barOpacity}`)

/* ---------- 4. 选中态底色渐变（非瞬时） ---------- */
const surfaceTransition = await page.locator('.session-list .session-item').first().evaluate((el) => getComputedStyle(el).transitionProperty + ' / ' + getComputedStyle(el).transitionDuration)
check('选中底色走过渡', /background/.test(surfaceTransition), surfaceTransition)

/* ---------- 5. 切换有淡入上移动画 ---------- */
check('消息区带切换动画', (await page.locator('.thread').evaluate((el) => getComputedStyle(el).animationName)) === 'chat-swap-in')
/*
 * 用 animationstart 事件捕获，而不是"点完等 120ms 再采样"：
 * 动画只有 200ms，机器繁忙时固定等待会错过窗口 → 假失败（实测在整套连跑时偶发）。
 */
await page.evaluate(() => {
  window.__chatSwap = false
  document.addEventListener('animationstart', (e) => {
    if (e.animationName === 'chat-swap-in') window.__chatSwap = true
  }, true)
})
await page.locator('.session-open').nth(1).click()
await page.waitForTimeout(600)
check('切到另一会话时触发了进入动画', await page.evaluate(() => window.__chatSwap === true))

/* ---------- 6. 滚动位置各自记忆 ---------- */
// 回到第一个会话，把它滚到顶部（非默认位置）
await page.locator('.session-open').first().click()
await page.waitForTimeout(700)
const scrollable = await page.locator('.thread').evaluate((el) => el.scrollHeight > el.clientHeight + 4)
check('矮视口下消息区可滚动（用例前提）', scrollable)
await page.locator('.thread').evaluate((el) => { el.scrollTop = 0 })
await page.waitForTimeout(200)
const savedTop = await page.locator('.thread').evaluate((el) => el.scrollTop)

// 切走再切回：应恢复 0，而不是跳到"最新一条"（即底部）
await page.locator('.session-open').nth(1).click()
await page.waitForTimeout(600)
await page.locator('.session-open').first().click()
await page.waitForTimeout(700)
const restored = await page.locator('.thread').evaluate((el) => el.scrollTop)
check('切回后恢复各自记录的位置', Math.abs(restored - savedTop) <= 2, `记录 ${savedTop} → 恢复 ${restored}`)

// 另一个会话应落在自己的位置（默认底部）
const otherBottom = await page.locator('.session-open').nth(1).click().then(async () => {
  await page.waitForTimeout(600)
  return page.locator('.thread').evaluate((el) => ({ top: el.scrollTop, max: el.scrollHeight - el.clientHeight }))
})
check('未记录过的会话落在最新消息（底部）', Math.abs(otherBottom.top - otherBottom.max) <= 4, `top=${otherBottom.top} max=${otherBottom.max}`)

/* ---------- 7. 截图 ---------- */
await page.setViewportSize({ width: 1440, height: 900 })
await page.waitForTimeout(800)
await page.locator('.session-item').first().hover()
await page.waitForTimeout(400)
check('常规视口：行高仍为 34px（悬停不改变行高）', (await page.locator('.session-list .session-item').first().evaluate((el) => Math.round(el.getBoundingClientRect().height))) === 34)
await page.screenshot({ path: `${OUT}/x2-chat-sessionlist-dark.png` })
console.log('ok   x2-chat-sessionlist-dark（截图）')

/* ---------- 7.5 消息导航：右侧横线 + Hover 预览（Portal 浮层） ---------- */
// 需要多轮问答：显式切到"B+ 树"会话
await page.locator('.session-open', { hasText: 'B+ 树' }).first().click()
await page.waitForTimeout(900)

const nav = await page.evaluate(() => ({
  lines: document.querySelectorAll('.msg-nav__item').length,
  questions: document.querySelectorAll('.thread .msg.user').length,
  msgs: document.querySelectorAll('.thread .msg').length,
  active: document.querySelectorAll('.msg-nav__item[data-active]').length,
  /* 量"非 active"的横线：第一条在初始状态就是 active（会更长），拿它当默认值会误判 */
  idleLineW: Math.round((document.querySelector('.msg-nav__item:not([data-active]) .msg-nav__line') ?? document.querySelector('.msg-nav__line')).getBoundingClientRect().width),
  activeLineW: Math.round(document.querySelector('.msg-nav__item[data-active] .msg-nav__line').getBoundingClientRect().width),
  navRight: Math.round(document.querySelector('.msg-nav').getBoundingClientRect().right),
  threadRight: Math.round(document.querySelector('.thread').getBoundingClientRect().right),
}))
check('右侧横线数量 = 我的提问数量（助手回答不占横线）', nav.lines === nav.questions && nav.lines >= 2, `${nav.lines} 横线 / ${nav.questions} 提问 / ${nav.msgs} 条消息`)
check('当前消息有且只有一条高亮', nav.active === 1, `active ${nav.active} 条`)
check('横线默认很短（低存在感）', nav.idleLineW > 0 && nav.idleLineW <= 28, `默认 ${nav.idleLineW}px`)
check('当前消息的横线更长', nav.activeLineW > nav.idleLineW, `${nav.idleLineW}px → ${nav.activeLineW}px`)
check('轨道贴在内容右侧', nav.navRight >= nav.threadRight - 12 && nav.navRight <= nav.threadRight + 2, `nav ${nav.navRight} / thread ${nav.threadRight}`)

/* 横线不能被祖先的 overflow:hidden 横向裁掉
   （历史 bug：轨道曾是 width:0 的浮层，内部 max-content 列表向右溢出到面板外被裁） */
const railGeom = await page.evaluate(() => {
  const row = document.querySelector('.thread-row').getBoundingClientRect()
  const items = Array.from(document.querySelectorAll('.msg-nav__item')).map((el) => el.getBoundingClientRect())
  const nav = document.querySelector('.msg-nav').getBoundingClientRect()
  return {
    overflowRight: items.filter((r) => r.right > row.right + 1).length,
    zeroWidth: items.filter((r) => r.width <= 0).length,
    insideNavVertically: items.filter((r) => r.top >= nav.top - 1 && r.bottom <= nav.bottom + 1).length,
    total: items.length,
    gap: getComputedStyle(document.querySelector('.msg-nav__list')).rowGap,
  }
})
check('横线未被横向裁切', railGeom.overflowRight === 0, `溢出 ${railGeom.overflowRight} 条`)
check('横线宽度均 > 0', railGeom.zeroWidth === 0)
check('横线全部落在轨道纵向范围内', railGeom.insideNavVertically === railGeom.total, `${railGeom.insideNavVertically}/${railGeom.total}`)
check('行距在合理区间（消息多时自动收紧）', parseFloat(railGeom.gap) >= 2 && parseFloat(railGeom.gap) <= 8, `gap=${railGeom.gap}`)

// Hover 中间一条 → 浮层必须是 Portal 到 body 的 fixed 元素，且不被裁剪
const items = page.locator('.msg-nav__item')
await items.nth(2).hover()
await page.waitForTimeout(450)
const pop = await page.evaluate(() => {
  const el = document.querySelector('.msg-preview')
  if (!el) return null
  const r = el.getBoundingClientRect()
  const items = Array.from(el.querySelectorAll('.msg-preview__item')).map((item) => item.innerText.trim())
  const list = el.querySelector('.msg-preview__list')
  return {
    inBody: el.parentElement === document.body,
    fixed: getComputedStyle(el).position === 'fixed',
    inside: r.left >= 12 && r.top >= 12 && r.right <= window.innerWidth - 12 && r.bottom <= window.innerHeight - 12,
    roleChip: !!el.querySelector('.msg-preview__role'),
    items,
    scrollable: list ? getComputedStyle(list).overflowY === 'auto' : false,
    hasHead: !!el.querySelector('.msg-preview__head'),
    hasHint: !!el.querySelector('.msg-preview__hint'),
    hasMd: items.some((text) => /[*#`|]/.test(text)),
  }
})
check('Hover 弹出预览浮层', pop !== null, JSON.stringify(pop))
check('浮层由 Portal 挂到 body（不受 overflow 裁剪）', pop?.inBody === true && pop?.fixed === true)
check('浮层完整落在视口内（12px 留白）', pop?.inside === true)
check('浮层列出多条提问并支持滚动', (pop?.items.length ?? 0) >= 3 && pop?.scrollable, `${pop?.items.length} 条 / 滚动 ${pop?.scrollable}`)
check('浮层不再有「我 / 助手」角色标签', pop?.roleChip === false)
check('浮层无页眉页脚', pop?.hasHead === false && pop?.hasHint === false)
check('消息区与浮层都没有「我」字样', await page.evaluate(() => !Array.from(document.querySelectorAll('.thread, .msg-preview')).some((el) => el.textContent.includes('我'))))
check('多条预览摘要是纯文本（无 Markdown 标记）', (pop?.items.length ?? 0) >= 3 && pop.hasMd === false, (pop?.items[0] ?? '').slice(0, 30))
/* 浮层列表高亮必须跟阅读位置（轨道 data-active）一致，不能跟悬停横线走 */
const popAlign = await page.evaluate(() => {
  const activeRail = Array.from(document.querySelectorAll('.msg-nav__item')).findIndex((el) => el.hasAttribute('data-active'))
  const activePop = Array.from(document.querySelectorAll('.msg-preview__item')).findIndex((el) => el.hasAttribute('data-active'))
  const activeCount = document.querySelectorAll('.msg-preview__item[data-active]').length
  return { activeRail, activePop, activeCount }
})
check('浮层只高亮一条提问', popAlign.activeCount === 1, JSON.stringify(popAlign))
check('浮层高亮 = 轨道阅读位置', popAlign.activePop === popAlign.activeRail, JSON.stringify(popAlign))
await page.screenshot({ path: `${OUT}/z17-msgnav-preview.png` })
console.log('ok   z17-msgnav-preview（截图）')

// 宽限期：鼠标从横线移到浮层上不应消失
const box = await page.locator('.msg-preview').boundingBox()
await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
await page.waitForTimeout(320)
check('鼠标移到浮层上不会闪烁消失', (await page.locator('.msg-preview').count()) === 1)

// 点击浮层 → 平滑跳转
const beforeJump = await page.locator('.thread').evaluate((el) => el.scrollTop)
await page.locator('.msg-preview__item').nth(2).click()
await page.waitForTimeout(1100)
const afterJump = await page.locator('.thread').evaluate((el) => el.scrollTop)
check('点击后跳转到对应消息', afterJump !== beforeJump, `scrollTop ${beforeJump} → ${afterJump}`)
/* 浮层是 hover 触发的，且**有意**在指针仍停留时保持展开（只更新高亮）；
 * 指针离开后 160ms 宽限 + 180ms 收起动画才卸载，所以这里必须等够时间再断言 */
await page.mouse.move(20, 20)
await page.waitForTimeout(800)
check('指针离开后浮层收起', (await page.locator('.msg-preview').count()) === 0)

// 滚动 → active 跟随；滚到底应为最后一条
await page.locator('.thread').evaluate((el) => el.scrollTo({ top: 0 }))
await page.waitForTimeout(800)
const activeTop = await page.evaluate(() => Array.from(document.querySelectorAll('.msg-nav__item')).findIndex((el) => el.hasAttribute('data-active')))
await page.locator('.thread').evaluate((el) => el.scrollTo({ top: el.scrollHeight }))
await page.waitForTimeout(800)
const activeBottom = await page.evaluate(() => Array.from(document.querySelectorAll('.msg-nav__item')).findIndex((el) => el.hasAttribute('data-active')))
check('滚到顶部高亮第一条', activeTop === 0, `第一条 index=${activeTop}`)
check('滚到底部高亮最后一条', activeBottom === nav.lines - 1, `index=${activeBottom} / 共 ${nav.lines}`)

// 首条 / 末条 Hover 的边界收敛
for (const [label, idx] of [['第一条', 0], ['最后一条', nav.lines - 1]]) {
  await items.nth(idx).hover()
  await page.waitForTimeout(400)
  const ok = await page.evaluate(() => {
    const el = document.querySelector('.msg-preview')
    if (!el) return false
    const r = el.getBoundingClientRect()
    return r.left >= 12 && r.top >= 12 && r.right <= window.innerWidth - 12 && r.bottom <= window.innerHeight - 12
  })
  check(`${label} Hover 时浮层仍在视口内`, ok)
}

/* ---------- 7.55 视觉可辨识度：输入框、面板实底、侧栏行 ---------- */
const visibility = await page.evaluate(() => {
  const alpha = (c) => {
    const m = String(c).match(/\/\s*([\d.]+)\)/) || String(c).match(/rgba?\([^)]*,\s*([\d.]+)\s*\)/)
    return m ? parseFloat(m[1]) : 1
  }
  /* 边界现在落在 .prompt-bar 外壳（去掉了描边，改用 --surface + shadow-sm 与面板区分） */
  const field = document.querySelector('.composer-wrap .prompt-bar') ?? document.querySelector('.prompt-bar__field')
  const panel = document.querySelector('.chat-main')
  const rows = Array.from(document.querySelectorAll('.sb-session'))
  const sel = rows.find((row) => row.hasAttribute('data-selected'))
  const idle = rows.find((row) => !row.hasAttribute('data-selected'))
  /* 选中底色现在直接落在 .sb-session 整行上（旧版包了一层 .swipe-row__surface） */
  const surface = (row) => row ?? null
  const selSurface = surface(sel)
  const idleSurface = surface(idle)
  const cs = (el, p) => (el ? getComputedStyle(el)[p] : null)
  return {
    fieldBorder: parseFloat(cs(field, 'borderTopWidth')) || 0,
    fieldBg: cs(field, 'backgroundColor'),
    fieldBorderColor: cs(field, 'borderTopColor'),
    fieldShadow: cs(field, 'boxShadow'),
    panelBg: cs(panel, 'backgroundColor'),
    shellBg: cs(document.querySelector('.shell-main'), 'backgroundColor'),
    selBg: cs(selSurface, 'backgroundColor'),
    selBorderColor: cs(selSurface, 'borderTopColor'),
    selBorderWidth: parseFloat(cs(selSurface, 'borderTopWidth')) || 0,
    idleBg: cs(idleSurface, 'backgroundColor'),
    idleBorderColor: cs(idleSurface, 'borderTopColor'),
  }
})
// 暗色下 --surface 与面板同色，所以输入区必须靠描边 / 填充 / 阴影三者之一与面板分开
check('输入区与面板区分得开（边框或阴影）',
  visibility.fieldBorder >= 1 || (!!visibility.fieldShadow && visibility.fieldShadow !== 'none'),
  `border=${visibility.fieldBorder}px shadow=${visibility.fieldShadow}`)
// 面板半透明时背后的粒子花纹会透出来，看起来像"排版破了洞"
check('对话面板是实底（不透明）', visibility.panelBg !== null && !/\/\s*0?\.\d+\)/.test(String(visibility.panelBg)), String(visibility.panelBg))
check('对话页外壳也是实底', visibility.shellBg !== null && !/\/\s*0?\.\d+\)/.test(String(visibility.shellBg)), String(visibility.shellBg))
// 侧栏会话行：选中=淡灰底无边框；未选中=纯文字行（不能是容器色块 + 描边）
/* `border: 0` 的行 computed borderTopColor 会退回 currentColor（文字色），
 * 所以这里断言"没有边框宽度"，而不是断言颜色透明 */
check('侧栏选中行无可见边框', visibility.selBorderWidth === 0, `${visibility.selBorderWidth}px`)
check('侧栏未选中行是纯文字行（透明底）', String(visibility.idleBg).includes('rgba(0, 0, 0, 0)'), String(visibility.idleBg))

/* 打开某条对话必须能一眼看出是哪条：选中行的底色要和侧栏底色拉开足够亮度差
   （曾经用纯 --surface-muted，亮色下只差 0.016，等于没有高亮） */
const hl = await page.evaluate(() => {
  const rows = Array.from(document.querySelectorAll('.sb-session'))
  const sel = rows.find((r) => r.hasAttribute('data-selected'))
  const idle = rows.find((r) => !r.hasAttribute('data-selected'))
  const sidebarBg = getComputedStyle(document.querySelector('.sidebar')).backgroundColor
  const selBg = sel ? getComputedStyle(sel).backgroundColor : null
  /* 选中底色带 alpha（oklab(... / .16)）：必须先合成到侧栏底色上，再比相对亮度 */
  const ctx = document.createElement('canvas').getContext('2d')
  const resolve = (color, under) => {
    ctx.clearRect(0, 0, 1, 1)
    ctx.fillStyle = under
    ctx.fillRect(0, 0, 1, 1)
    ctx.fillStyle = color
    ctx.fillRect(0, 0, 1, 1)
    return Array.from(ctx.getImageData(0, 0, 1, 1).data)
  }
  /* OKLab 的 L 才是感知亮度：sRGB 相对亮度在暗色底上会把 0.04 的差压成 0.008 */
  const lum = (px) => {
    const f = (v) => { const x = v / 255; return x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4 }
    const r = f(px[0]); const g = f(px[1]); const b = f(px[2])
    const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    return 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
  }
  const bgPx = resolve(sidebarBg, '#000')
  const selPx = selBg ? resolve(selBg, sidebarBg) : null
  const idlePx = idle ? resolve(getComputedStyle(idle).backgroundColor, sidebarBg) : null
  return {
    selBg, sidebarBg,
    dL: selPx ? Math.abs(lum(selPx) - lum(bgPx)) : null,
    idleD: idlePx ? Math.abs(lum(idlePx) - lum(bgPx)) : null,
    weight: sel ? getComputedStyle(sel).fontWeight : null,
  }
})
check('选中会话所在行亮度差足够（ΔL ≥ 0.03）', hl.dL !== null && hl.dL >= 0.03,
  `选中 ${hl.selBg} vs 侧栏 ${hl.sidebarBg}（ΔL=${hl.dL !== null ? hl.dL.toFixed(3) : 'n/a'}）`)
check('未选中会话行不带底色（与侧栏同色）', hl.idleD !== null && hl.idleD < 0.005, `ΔL=${hl.idleD?.toFixed(4)}`)
check('选中会话文字加粗', hl.weight === '600', String(hl.weight))

/* ---------- 7.57 侧栏三层对齐：标题 8px / 标签 30px / 行高 34px ---------- */
const grid = await page.evaluate(() => {
  const x = (el) => Math.round(el.getBoundingClientRect().left)
  const titleX = Array.from(document.querySelectorAll('.sb-section--toggle')).map((e) => x(e.firstElementChild ?? e))
  const labelX = [
    ...Array.from(document.querySelectorAll('.sb-project')).map((e) => x(e.querySelector('span:not(.sb-project-count)') ?? e)),
    ...Array.from(document.querySelectorAll('.sb-session .session-open')).map((e) => x(e)),
  ]
  const heights = [
    ...Array.from(document.querySelectorAll('.sb-section--toggle')).map((e) => Math.round(e.getBoundingClientRect().height)),
    ...Array.from(document.querySelectorAll('.sb-project')).map((e) => Math.round(e.getBoundingClientRect().height)),
    ...Array.from(document.querySelectorAll('.sb-session')).map((e) => Math.round(e.getBoundingClientRect().height)),
  ]
  return { titleX, labelX, heights, dir: getComputedStyle(document.querySelector('.sb-session')).flexDirection }
})
check('分区标题全部对齐同一条基准线', new Set(grid.titleX).size === 1, `x=${grid.titleX.join(' / ')}`)
check('会话标签对齐同一条基准线', new Set(grid.labelX).size === 1, `x=${grid.labelX.join(' / ')}`)
/* 分区标题（30px）与会话行（34px）现在刻意不同高：标题是分组标签，行是可点区域；
   这里断言的是"都在紧凑区间里"，而不是"三档完全相等" */
check('行高都在紧凑区间（30–34px）', grid.heights.every((h) => h >= 30 && h <= 34), `h=${[...new Set(grid.heights)].join(' / ')}`)
// 曾经继承 .session-item 的 column+center，导致文字在行内居中、起点随内容漂移
check('会话行是横向排列（文字不再被居中）', grid.dir === 'row', grid.dir)

/*
 * ---------- 7.58 项目栏目：创建 / 过滤 / 全部会话 / 删除 ----------
 *
 * ⚠️ **本段已停用（2026-10-09，老大拍板）**
 *
 * 项目栏目被**故意隐藏**（Sidebar.tsx:319 用 `{false && ...}` 关闭）：
 * 后端 `chat_conversation` 表没有 project 字段（`V9__workspace` 是另一套模型），
 * 前端 `SessionItem.projectId` 已移除。与其在前端维护一个"刷新就丢"的假归属，
 * 不如先不显示这个栏目。
 *
 * 停用的不只是下面两条断言，而是**整段 12 条**：它们是一条连续流程
 * （创建 → 过滤 → 切回全部 → 删除），中途依赖 `.sb-project--new` 等选择器，
 * 元素不存在会让脚本在 30s 超时处**抛异常中断**，后面的断言一条都跑不到。
 * 之前的表现就是「49 通过 / 3 失败」——那3 条只是最先撞上的，
 * 后面 5 条根本没执行（不是通过，是没跑到）。
 *
 * **恢复条件**（后端支持项目归属后）：
 *   1. Sidebar.tsx:319 去掉 `{false &&`
 *   2. SessionItem 加回 projectId，同步 store 的 Persisted 类型
 *   3. 把本段与上方「分区顺序」断言一起取消注释/恢复
 */
/* ---------- 7.6 侧栏：「最近」与「置顶」可收起 ---------- */
/*
 * ⚠️ 按**文本**找分区，不按下标。
 * 原来写的是 `[1]`=置顶、`[2]`=最近，那依赖"项目栏目在index 0"；
 * 项目栏目隐藏后下标整体前移一位，`[1]` 变成「最近」——
 * 断言会拿"最近"的展开态当"置顶"来验，**假通过**（测的不是想测的东西）。
 */
const sbState = () => page.evaluate(() => {
  const toggles = [...document.querySelectorAll('.sb-section--toggle')]
  const byText = (kw) => toggles.find((e) => e.textContent.includes(kw))
  return {
    pinned: byText('置顶')?.getAttribute('aria-expanded'),
    recent: byText('最近')?.getAttribute('aria-expanded'),
    visible: Array.from(document.querySelectorAll('.sb-session')).filter((e) => e.offsetParent !== null).length,
    total: document.querySelectorAll('.sb-session').length,
  }
})
let sb = await sbState()
const sbTotal = sb.total
check('侧栏「最近」默认展开', sb.recent === 'true')
check('侧栏「置顶」默认展开', sb.pinned === 'true')

/* 点「置顶」的折叠按钮（按文本定位，理由见上方 sbState 的注释） */
await page.locator('.sb-section--toggle', { hasText: '置顶' }).first().click()
await page.waitForTimeout(450)
sb = await sbState()
check('收起「置顶」后置顶项被隐藏', sb.pinned === 'false' && sb.visible === sbTotal - 1, `可见 ${sb.visible}/${sbTotal}`)

/* 同样按文本定位「最近」，理由见上方 sbState 的注释 */
await page.locator('.sb-section--toggle', { hasText: '最近' }).first().click()
await page.waitForTimeout(450)
sb = await sbState()
check('收起「最近」后整块隐藏', sb.recent === 'false' && sb.visible === 0, `可见 ${sb.visible}`)

await page.locator('.sb-section--toggle', { hasText: '最近' }).first().click()
await page.locator('.sb-section--toggle', { hasText: '置顶' }).first().click()
await page.waitForTimeout(450)
sb = await sbState()
check('再展开后恢复原样', sb.recent === 'true' && sb.pinned === 'true' && sb.visible === sbTotal, `可见 ${sb.visible}/${sbTotal}`)

/* ---------- 7.65 新建会话弹窗：Stepper 必须按"弹窗内容"排版 ---------- */
await page.locator('.sb-row--quick').click()
await page.waitForTimeout(900)
const wiz = await page.evaluate(() => {
  const outer = document.querySelector('.dialog .outer-container')
  const circle = document.querySelector('.dialog .step-circle-container')
  const next = document.querySelector('.dialog .next-button')
  const nr = next?.getBoundingClientRect()
  return {
    open: !!document.querySelector('.dialog'),
    aspect: outer ? getComputedStyle(outer).aspectRatio : null,
    circleBorder: circle ? getComputedStyle(circle).borderTopWidth : null,
    steps: document.querySelectorAll('.dialog .step-indicator-inner').length,
    nextInside: nr ? nr.bottom <= window.innerHeight + 1 && nr.width > 0 : null,
  }
})
check('新建会话弹窗打开', wiz.open === true)
// 组件自带的 aspect-ratio（4/3、2/1）会把弹窗撑歪 —— 必须被覆盖成 auto
check('弹窗内的 Stepper 不再受长宽比限制', wiz.aspect === 'auto', String(wiz.aspect))
check('弹窗内不再嵌套一层容器边框', wiz.circleBorder === '0px', String(wiz.circleBorder))
check('步骤指示器完整（2 步）', wiz.steps === 2, `${wiz.steps} 个`)
check('「下一步」按钮在视口内', wiz.nextInside === true)
await page.screenshot({ path: `${OUT}/z23-wizard-fixed.png` })
console.log('ok   z23-wizard-fixed（截图）')
await page.keyboard.press('Escape')
await page.waitForTimeout(500)

/* ---------- 7.7 消息不用头像，靠气泡底色区分说话人 ---------- */
const msgStyle = await page.evaluate(() => {
  const u = document.querySelector('.thread .msg.user .bub')
  const b = document.querySelector('.thread .msg.bot .bub')
  const userRow = document.querySelector('.thread .msg.user')
  const rows = Array.from(document.querySelectorAll('.thread .msg'))
  const maxRight = Math.max(...rows.map((m) => m.getBoundingClientRect().right))
  return {
    avatars: document.querySelectorAll('.thread .ava').length,
    userBg: u ? getComputedStyle(u).backgroundColor : null,
    botBg: b ? getComputedStyle(b).backgroundColor : null,
    userRight: userRow ? Math.abs(userRow.getBoundingClientRect().right - maxRight) < 1 : null,
  }
})
check('消息区没有头像', msgStyle.avatars === 0, `找到 ${msgStyle.avatars} 个 .ava`)
check('「我」与 AI 的气泡底色不同（靠颜色区分）', !!msgStyle.userBg && !!msgStyle.botBg && msgStyle.userBg !== msgStyle.botBg, `${msgStyle.userBg} vs ${msgStyle.botBg}`)
check('「我」的消息右对齐', msgStyle.userRight === true)

/* ---------- 7.8 输入区（PromptBar）：@ 数据源 / 命令 / 模型 / 档位 / 附件 / 发送 ---------- */
const pb = page.locator('.prompt-bar')
check('输入区已换成 PromptBar', (await pb.count()) === 1)

// 数据源只在 RAG 且确有绑定时显示；聊天附件上传尚无服务端契约，不伪造上传项。
/* 模式切换从顶部分段控件搬进了会话配置浮层（.session-config__pop → .sc-opt） */
await page.locator('.session-config__btn').click()
await page.waitForTimeout(300)
await page.locator('.session-config__pop .sc-opt', { hasText: '知识库问答' }).click()
await page.waitForTimeout(350)
await page.locator('.prompt-bar__input').fill('@')
await page.waitForTimeout(500)
const atRows = await page.locator('.prompt-bar__row-name').allInnerTexts()
check('RAG 的 @ 菜单只列出真实绑定知识库', (await page.locator('.prompt-bar__menu[data-kind="at"]').count()) === 1 && atRows.some((row) => row.includes('我的 Java 笔记')) && !atRows.some((row) => row.includes('上传')), atRows.join(' / '))
check('聊天附件没有被伪装成可上传项目', (await page.locator('.prompt-bar__tool').count()) === 1 && (await page.locator('.prompt-bar__row-name', { hasText: '上传' }).count()) === 0)
check('打开菜单不会把页面打崩', (await page.locator('.prompt-bar__input').count()) === 1 && (await page.locator('.chat-layout').count()) === 1)

// 输入 / 打开命令菜单
await page.locator('.prompt-bar__input').fill('/')
await page.waitForTimeout(600)
check('输入 / 弹出命令菜单', (await page.locator('.prompt-bar__menu[data-kind="slash"]').count()) === 1)
await page.keyboard.press('Escape')
await page.locator('.prompt-bar__input').fill('')
await page.waitForTimeout(300)

// 模型 / 档位
await page.locator('.prompt-bar__pick').first().click()
await page.waitForTimeout(400)
check('模型菜单可打开', (await page.locator('.prompt-bar__row-name').count()) >= 2)
await page.keyboard.press('Escape')
await page.waitForTimeout(300)
const picks = await page.locator('.prompt-bar__pick').all()
await picks[picks.length - 1].click()
await page.waitForTimeout(400)
check('档位面板可打开（含滑杆）', (await page.locator('.prompt-bar__effort-track').count()) === 1)
await page.keyboard.press('Escape')
await page.waitForTimeout(300)

// 发送：Enter 送出且草稿清空
const msgsBeforeSend = await page.locator('.thread .msg').count()
await page.locator('.prompt-bar__input').fill('时间轴刻度怎么对齐？')
await page.locator('.prompt-bar__input').press('Enter')
await page.waitForTimeout(700)
check('Enter 发送后出现我的消息', (await page.locator('.thread .msg').count()) > msgsBeforeSend)
check('发送后草稿已清空', (await page.locator('.prompt-bar__input').inputValue()) === '')

await page.screenshot({ path: `${OUT}/z11-promptbar.png` })
console.log('ok   z11-promptbar（截图）')

/* ---------- 7.9 工具调用链（AgentThoughtChain）：running → done/error + 重试 ---------- */
/* 聊天里的链路是极简行内清单（`.thought-chain` / `.thought-call`），工具名放在 `data-tool` 上 */
const callEvents = async () => page.evaluate(() => ({
  groups: document.querySelectorAll('.thought-chain').length,
  chips: Array.from(document.querySelectorAll('.thought-chain .thought-call')).map((c) => ({
    name: c.getAttribute('data-tool'),
    status: c.getAttribute('data-status'),
  })),
  retry: document.querySelectorAll('.thought-call__retry').length,
}))
let ce = await callEvents()
check('种子消息里已展示工具调用轨迹', ce.groups >= 1 && ce.chips.length >= 2, JSON.stringify(ce.chips))

// 发一条：观察状态推进（search → read → sandbox 失败并出现重试）
/* 上一步刚发过消息：PromptBar 在 busy（流式）期间会忽略发送，先等它结束 */
await page.waitForTimeout(4500)
await page.waitForFunction(() => !document.querySelector('.prompt-bar[data-busy]'), null, { timeout: 8000 }).catch(() => {})
const navLinesBefore = await page.locator('.msg-nav__item').count()
await page.locator('.prompt-bar__input').fill('B+ 树的高度怎么算？')
await page.locator('.prompt-bar__input').press('Enter')
await page.waitForTimeout(500)
const navLinesAfter = await page.locator('.msg-nav__item').count()
check('新消息出现后导航横线自动增加', navLinesAfter > navLinesBefore, `${navLinesBefore} → ${navLinesAfter}`)
ce = await callEvents()
check('发送后出现运行中的工具调用', ce.chips.some((c) => c.status === 'running' || c.status === 'idle'), JSON.stringify(ce.chips.slice(-3)))
let sawRunning = false
for (let i = 0; i < 14 && !sawRunning; i++) {
  await page.waitForTimeout(300)
  const s = await callEvents()
  if (s.chips.some((c) => c.status === 'running')) sawRunning = true
}
check('过程里能看到 running 态（进度在走）', sawRunning)
let sawError = false
for (let i = 0; i < 16 && !sawError; i++) {
  await page.waitForTimeout(300)
  const s = await callEvents()
  if (s.chips.some((c) => c.status === 'error')) sawError = true
}
ce = await callEvents()
check('失败的调用进入 error 态', sawError && ce.retry >= 1, `retry=${ce.retry}`)
await page.screenshot({ path: `${OUT}/z12-tool-calls.png` })
console.log('ok   z12-tool-calls（截图）')

// 重试：error / timeout → running → done，重试按钮消失
/* 作用域限定在"最后一条"调用链：屏幕上可能同时有多条（每次发送各一条），
   否则会点到别的组的重试、却断言当前组的状态。
   注意：链路行有入场动画，Playwright 的"稳定性"检查可能在动画期间不通过，
   所以必须 click({ force: true })；也正因为如此，不要断言"能采样到 running"（时序敏感→假失败），
   改为断言确定性终态：重试按钮消失且全部 done。 */
const lastGroup = page.locator('.thought-chain').last()
/* 先等这一轮跑完（末尾两条会落成 error / timeout）——否则重试按钮还不存在，
   断言会采到"运行中"的中间态 */
await page.waitForFunction(() => {
  const groups = document.querySelectorAll('.thought-chain')
  const last = groups[groups.length - 1]
  return !!last && last.querySelectorAll('.thought-call[data-status="running"]').length === 0
}, null, { timeout: 15000 }).catch(() => {})
for (let i = 0; i < 6; i += 1) {
  const btn = lastGroup.locator('.thought-call__retry').first()
  if ((await btn.count()) === 0) break
  /* 直接派发 DOM click：行内条目带入场动画，Playwright 的命中测试/稳定性等待不可靠 */
  await btn.evaluate((el) => el.click())
  await page.waitForTimeout(1800)
}
const finalCalls = await lastGroup.locator('.thought-call').evaluateAll((els) =>
  els.map((e) => `${e.getAttribute('data-tool')}:${e.getAttribute('data-status')}`))
check('重试后不再有失败/超时态（全部 done）', finalCalls.length > 0 && finalCalls.every((s) => s.endsWith(':done')), finalCalls.join(' '))
check('重试成功后弹出提示', (await page.locator('.toast-host .swipe-toast').count()) > 0)

/* ---------- 8. 滑动手势：左滑置顶 / 右滑归档+删除 ---------- */
await page.evaluate(() => window.__nxTheme.setMode('light'))
await page.waitForTimeout(500)
check('会话行图标操作与左滑手势同时保留', (await page.locator('.session-actions .session-action').count()) >= 9)

const ROW = '.session-list .session-item'
/* 动作栏是绝对定位 + opacity:0，悬停才淡入：先 hover 再点，否则命中测试会失败 */
const barOpacityOf = () => page.locator(ROW).first().evaluate((el) => getComputedStyle(el.querySelector('.session-actions')).opacity)

const pinRow = page.locator(ROW).first()
await pinRow.hover()
await page.waitForTimeout(320)
check('悬停后动作栏淡入', parseFloat(await barOpacityOf()) > 0.9, `opacity=${await barOpacityOf()}`)
const pinnedBefore = await page.locator(ROW).count()
await pinRow.locator('.session-action[aria-label^="置顶"], .session-action[aria-label^="取消置顶"]').click()
await page.waitForTimeout(800)
const pinOk = (await page.locator('.toast-host .swipe-toast').count()) > 0
  || (await page.locator(ROW).count()) !== pinnedBefore
  || (await page.locator(`${ROW} .session-action[aria-label^="取消置顶"]`).count()) === 1
check('点「置顶」有反馈（提示或分组变化）', pinOk)
await page.waitForTimeout(400)

/* 归档：从主列表移除 → 「已归档」入口可找回 */
const rowsBefore = await page.locator(ROW).count()
const archRow = page.locator(ROW).last()
await archRow.hover()
await page.waitForTimeout(250)
await archRow.locator('.session-action[aria-label^="归档"]').click()
await page.waitForTimeout(800)
check('归档后从主列表移除', (await page.locator(ROW).count()) === rowsBefore - 1, `${rowsBefore} → ${await page.locator(ROW).count()}`)
check('出现「已归档」入口', (await page.locator('.session-arch-toggle').count()) === 1 && (await page.locator('.session-arch-toggle').innerText()).includes('已归档（1）'))
await page.locator('.session-arch-toggle').click()
await page.waitForTimeout(500)
check('展开后能看到归档会话', (await page.locator('.session-arch-row').count()) === 1)
await page.screenshot({ path: `${OUT}/z8-session-archived.png` })
console.log('ok   z8-session-archived（截图）')
await page.locator('.session-arch-row button', { hasText: '恢复' }).click()
await page.waitForTimeout(800)
check('恢复后回到主列表', (await page.locator('.session-arch-toggle').count()) === 0 && (await page.locator(ROW).count()) === rowsBefore)

/*
 * 删除：**二次确认，无撤销**。
 *
 * ⚠️ 断言要跟实现对齐，而不是跟旧设计对齐。
 * 旧断言期望「删除后弹 toast + 撤销按钮，点撤销会话回来」——
 * 那套逻辑在真实后端下是**假的**（Sidebar.tsx:120-126 的注释已说明）：
 * 后端 `delete` 会**物理删除消息**并 `chatMemory.clear()`，
 * 所谓"撤销"只是把本地行放回去，后端消息已被删光。
 * 因此实现已改为**应用内二次确认弹窗** + 指向「归档」这个可逆选项。
 *
 * 另一个坑：入口不再是滑动手势，而是悬停显露的行内图标（`.session-action`），
 * 必须先 hover 出动作栏再点，否则绝对定位 + opacity:0 会挡住命中测试。
 */
const rowsBeforeDel = await page.locator(ROW).count()
const delRow = page.locator(ROW).first()
await delRow.hover()
await page.waitForTimeout(250)
await delRow.locator('.session-action[aria-label^="删除"]').click()
await page.waitForTimeout(600)
const delDialog = page.locator('.dialog')
check('删除前有二次确认（应用内弹窗，不是原生 confirm）', (await delDialog.count()) === 1)
const delText = (await delDialog.count()) ? (await delDialog.innerText()).replace(/\s+/g, ' ') : ''
check('确认文案说清不可恢复并指向归档', delText.includes('无法恢复') && delText.includes('归档'), delText.slice(0, 80))
await delDialog.locator('.btn-danger').click()
await page.waitForTimeout(1000)
check('删除后会话消失', (await page.locator(ROW).count()) === rowsBeforeDel - 1, `${rowsBeforeDel} → ${await page.locator(ROW).count()}`)
check(
  '删除后给的是「已删除」提示而非可撤销',
  (await page.locator('.toast-host .swipe-toast', { hasText: '会话已删除' }).count()) > 0,
)
check('删除后不提供撤销入口（消息已被物理删除，撤销是假的）',
  (await page.locator('.toast-host button', { hasText: '撤销' }).count()) === 0)

/*
 * ⚠️ **必须复原快照**，否则污染后面的用例。
 *
 * 本用例删掉的是 `seed_s1`（B+ 树会话），而它恰好是**整个种子里唯一带表格结果卡**的
 * （`data.ts` 里 table/json/chart 三种卡分别落在 seed_s1/s2/s3）。
 * 后面的「表格结果卡渲染出表头与多行」要遍历会话找卡——种子被删后自然找不到，
 * 表现为那条断言莫名失败，而根因在这里。
 *
 * 这个坑的本质：**用例会改共享状态（localStorage 快照），后面的用例读的是同一份**。
 * 之前没暴露是因为脚本在这里就崩了（等撤销按钮超时），从没跑到后面。
 */
await page.evaluate(() => localStorage.removeItem('nx-proto-sessions'))
await page.reload({ waitUntil: 'networkidle' })
await page.waitForSelector('.session-list .session-item', { timeout: 15000 })
await page.waitForTimeout(1200)
check('删除用例已复原种子会话（不污染后续用例）',
  (await page.locator('.sb-session').count()) >= 4,
  `${await page.locator('.sb-session').count()} 个会话`)

/* 双击标题 → 居中弹窗重命名（不再在侧栏行内改名） */
const titleEl = page.locator('.sb-session .session-open').first()
const oldTitle = (await titleEl.innerText()).trim()
await titleEl.dblclick()
await page.waitForTimeout(500)
const renameDialog = page.locator('.dialog[aria-label="重命名聊天"]')
check('双击标题出现重命名弹窗', (await renameDialog.count()) === 1)
await renameDialog.locator('input.input').fill('重命名后的会话')
await renameDialog.locator('.btn-primary', { hasText: '保存' }).click()
await page.waitForTimeout(600)
const newTitle = (await page.locator('.sb-session .session-open').first().innerText()).trim()
check('保存后标题已更新', newTitle === '重命名后的会话' && newTitle !== oldTitle, `${oldTitle} → ${newTitle}`)

/* ---------- 12. 对话页固定为视口高度：整页不滚 + 滚动条隐藏 ---------- */
const chatFixed = async (label) => {
  const d = await page.evaluate(() => {
    const de = document.documentElement
    const th = document.querySelector('.thread')
    const composer = document.querySelector('.composer-wrap')
    const r = composer?.getBoundingClientRect()
    return {
      pageScrollable: de.scrollHeight > de.clientHeight + 1,
      scrollbar: th ? getComputedStyle(th).scrollbarWidth : null,
      threadScrollable: th ? th.scrollHeight > th.clientHeight : null,
      /* 移动端顶栏（56px）与有限视口高度下，要求“输入区顶端可见”即可：用户可滚动消息区把其余部分带入视口 */
      composerInside: r ? r.top <= document.documentElement.clientHeight : null,
    }
  })
  check(`${label}：整页不滚动（固定一屏）`, d.pageScrollable === false, JSON.stringify(d))
  check(`${label}：消息区滚动条已隐藏`, d.scrollbar === 'none', `scrollbar-width=${d.scrollbar}`)
  check(`${label}：消息区仍可滚动`, d.threadScrollable === true)
  check(`${label}：输入区在视口内`, d.composerInside === true)
}
await chatFixed('桌面')
await page.setViewportSize({ width: 390, height: 844 })
await page.waitForTimeout(900)
await chatFixed('手机')
await page.setViewportSize({ width: 1440, height: 900 })
await page.waitForTimeout(600)

/* ---------- 11.2 P1-2：上下文胶囊（说明"拿到了什么"，与工具轨迹互补） ---------- */
/* ---------- 11.15 P1-3：结构化结果卡（表格 / JSON / 图表 + 复制导出） ---------- */
/* ---------- 11.16 P1-4：busy 排队（能看见 / 能删 / 自动接着发） ---------- */
const qState = () => page.evaluate(() => ({
  queue: Array.from(document.querySelectorAll('.queue__item .queue__text')).map((e) => e.textContent.trim()),
  users: document.querySelectorAll('.thread .msg.user').length,
  busy: !!document.querySelector('.thought-chain[data-working]'),
}))
/** 这一轮是否仍在跑：`.thought-chain[data-working]` 就是 ChatPage 里 streaming 的 DOM 投影。 */
const workingNow = () => page.evaluate(() => !!document.querySelector('.thought-chain[data-working]'))
await page.locator('.sb-session').first().click()
await page.waitForTimeout(700)
await page.locator('.prompt-bar__input').fill('排队：第一条')
await page.locator('.prompt-bar__input').press('Enter')
/*
 * 不等固定延时，等"确实在跑"这个信号出现再断言。
 * mock 流一轮约 4.8s（ChatPage step()：800+800+1400+1400+400），固定 1000ms 再加几次往返，
 * 在 CI 的慢 runner 上会把窗口耗掉——上一版就是这么红的（第二条直接发出去了）。
 */
await page.waitForFunction(() => !!document.querySelector('.thought-chain[data-working]'), null, { timeout: 5000 }).catch(() => {})
const q0 = await qState()
check('忙时第一条正常发出', q0.busy === true && q0.queue.length === 0)

// 忙的时候连发两条 → 进队列，且**不产生新消息**
// 先证"这一轮还在跑"再发：窗口只有几秒，把前提单独报出来，红了也能直接看出红在哪。
check('发送第二条前第一条仍在运行', await workingNow())
await page.locator('.prompt-bar__input').fill('排队：第二条')
await page.locator('.prompt-bar__input').press('Enter')
await page.waitForTimeout(250)
await page.locator('.prompt-bar__input').fill('排队：第三条')
await page.locator('.prompt-bar__input').press('Enter')
await page.waitForTimeout(500)
const q1 = await qState()
check('busy 时消息进入待发送队列', q1.queue.length === 2, JSON.stringify(q1.queue))
check('排队期间不新增消息（不会插队打断当前回答）', q1.users === q0.users, `${q0.users} → ${q1.users}`)

// 移除队首
await page.locator('.queue__del').first().click()
await page.waitForTimeout(400)
const q2 = await qState()
check('可以移除排队中的某一条', q2.queue.length === 1 && q2.queue[0].includes('第三条'), JSON.stringify(q2.queue))

// 当前这轮结束后，队首自动发出
await page.waitForFunction(() => !document.querySelector('.thought-chain[data-working]'), null, { timeout: 25000 }).catch(() => {})
await page.waitForTimeout(1600)
const q3 = await qState()
check('上一轮结束后队首自动发出', q3.users === q1.users + 1 && q3.queue.length === 0, JSON.stringify(q3))

// 停止应清空队列（否则停止后会立刻自动发下一条，反直觉）
await page.locator('.prompt-bar__input').fill('排队：会被停止清掉')
await page.locator('.prompt-bar__input').press('Enter')
await page.waitForTimeout(900)
await page.locator('.prompt-bar__input').fill('排队：停止后应被清掉')
await page.locator('.prompt-bar__input').press('Enter')
await page.waitForTimeout(400)
await page.locator('.prompt-bar button[aria-label*="Stop"], .prompt-bar button[aria-label*="停止"]').first().click()
await page.waitForTimeout(900)
const q4 = await qState()
check('停止会清空待发送队列', q4.queue.length === 0, JSON.stringify(q4.queue))

await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
/* 不依赖会话标题：遍历会话，把三种结果卡都找出来 */
const seen = { 表格: false, JSON: false, 图表: false }
let chartWidths = []
let tableShape = null
const sessionCount = await page.locator('.sb-session').count()
for (let i = 0; i < Math.min(sessionCount, 6); i += 1) {
  await page.locator('.sb-session').nth(i).click()
  await page.waitForTimeout(650)
  const info = await page.evaluate(() => ({
    kinds: Array.from(document.querySelectorAll('.result__kind')).map((e) => e.textContent.trim()),
    rows: document.querySelectorAll('.result__table tbody tr').length,
    cols: document.querySelectorAll('.result__table thead th').length,
    bars: Array.from(document.querySelectorAll('.result__bar-fill')).map((b) => b.style.width),
  }))
  info.kinds.forEach((k) => { if (k in seen) seen[k] = true })
  if (info.rows > 0 && !tableShape) tableShape = { rows: info.rows, cols: info.cols }
  if (info.bars.length > 0) chartWidths = info.bars
}
check('表格结果卡渲染出表头与多行', seen.表格 === true && !!tableShape && tableShape.rows >= 3 && tableShape.cols >= 2, JSON.stringify(tableShape))
check('JSON 结果卡渲染出代码块', seen.JSON === true)
check('图表结果卡渲染出多根条形（按比例，最宽为 100%）', seen.图表 === true && chartWidths.length >= 3 &&
  chartWidths.every((w) => /%$/.test(w)) && Math.max(...chartWidths.map((w) => parseInt(w, 10))) === 100,
  chartWidths.join(' / '))

/* 复制：必须是能直接用的 CSV/JSON（而不是带竖线的 Markdown 文本） */
let csvOk = false
let downloadName = ''
for (let i = 0; i < Math.min(sessionCount, 6); i += 1) {
  await page.locator('.sb-session').nth(i).click()
  await page.waitForTimeout(600)
  const copyBtn = page.locator('.result__btn', { hasText: '复制' }).first()
  if ((await copyBtn.count()) === 0) continue
  await copyBtn.click()
  await page.waitForTimeout(500)
  const kind = (await page.locator('.result__kind').first().textContent()) ?? ''
  const clip = await page.evaluate(() => navigator.clipboard.readText())
  csvOk = kind.includes('JSON') ? clip.trim().startsWith('{') : (clip.includes(',') && !clip.includes('|'))
  const dl = page.waitForEvent('download', { timeout: 5000 }).catch(() => null)
  await page.locator('.result__btn', { hasText: '下载' }).first().click()
  const f = await dl
  downloadName = f ? f.suggestedFilename() : ''
  break
}
check('复制出来的是 CSV/JSON（不是 Markdown 文本）', csvOk === true)
check('结果卡可下载且扩展名正确', /\.(csv|json)$/.test(downloadName), downloadName)

const ctxInfo = async () => page.evaluate(() => {
  const groups = document.querySelectorAll('.ctx')
  const g = groups[groups.length - 1]
  if (!g) return null
  return Array.from(g.querySelectorAll('.ctx__chip')).map((c) => ({ t: c.textContent.trim(), warn: c.classList.contains('ctx__chip--warn') }))
})
/* 前面的用例改过标题/归档，这里不依赖具体标题：直接取第一个会话 */
await page.locator('.sb-session').first().click()
await page.waitForTimeout(900)
let chips = await ctxInfo()
check('回答上方有上下文胶囊', Array.isArray(chips) && chips.length >= 4, JSON.stringify(chips))
check('含检索侧信息（命中切片 / 引用篇数）', chips.some((c) => c.t.includes('命中')) && chips.some((c) => c.t.includes('引用')), (chips ?? []).map((c) => c.t).join(' | '))
check('含历史轮数', chips.some((c) => c.t.includes('历史')), (chips ?? []).map((c) => c.t).join(' | '))

// 点"引用 N 篇"应打开右侧引用面板（与 P0 的引用面板打通）
await page.locator('.ctx__chip--btn').first().click()
await page.waitForTimeout(800)
check('点「引用 N 篇」打开引用面板', (await page.locator('.ref-panel').count()) === 1)
await page.locator('.ref-panel .close').click().catch(() => {})
await page.waitForTimeout(400)

/* 长会话：历史被截断必须有提醒（否则会被误判成模型变笨）。
   不知道哪个会话是长会话，就逐个点过去找带提醒胶囊的那个。 */
let truncated = null
const sessions = await page.locator('.sb-session').count()
for (let i = 0; i < Math.min(sessions, 6); i += 1) {
  await page.locator('.sb-session').nth(i).click()
  await page.waitForTimeout(700)
  const c = await ctxInfo()
  if (c?.some((x) => x.t.includes('已省略'))) { truncated = { i, c }; break }
}
check('历史被截断时给出提醒胶囊（且标记为 warn）', !!truncated && truncated.c.some((x) => x.t.includes('已省略') && x.warn === true),
  truncated ? `会话 #${truncated.i}: ${truncated.c.map((x) => x.t).join(' | ')}` : `遍历 ${sessions} 个会话都没找到截断提醒`)

/* ---------- 11.3 P1：高风险工具确认卡（awaiting_confirm 状态机） ---------- */
/* 授权在内存 + localStorage 两处，必须走 DEV 钩子复位（只删 localStorage 不生效） */
await page.evaluate(() => window.__nxToolPolicy?.revokeAll())
await page.waitForTimeout(300)
const lastCalls = () => page.evaluate(() => {
  const g = document.querySelectorAll('.thought-chain')
  const l = g[g.length - 1]
  return l ? Array.from(l.querySelectorAll('.thought-call')).map((c) => `${c.getAttribute('data-tool')}:${c.getAttribute('data-status')}`) : []
})
await page.locator('.prompt-bar__input').fill('确认卡检查')
await page.locator('.prompt-bar__input').press('Enter')
await page.waitForTimeout(3000)
const awaiting = await lastCalls()
check('高风险工具进入 awaiting（链路挂起）', awaiting.some((s) => s.endsWith(':awaiting')), awaiting.join(' '))
check('确认卡出现且为内联卡（非弹窗）', (await page.locator('.confirm-card').count()) === 1 && (await page.locator('.dialog').count()) === 0)
const paused = JSON.stringify(await lastCalls())
await page.waitForTimeout(2000)
check('等待确认期间链路不推进', JSON.stringify(await lastCalls()) === paused, paused)

// 拒绝：该工具落 cancelled，但链路继续（不是整轮失败）
await page.locator('.confirm-card button', { hasText: '拒绝' }).click()
await page.waitForTimeout(2000)
const denied = await lastCalls()
check('拒绝后该工具为 cancelled', denied.some((s) => s.endsWith(':cancelled')), denied.join(' '))
await page.waitForTimeout(4500)
check('拒绝不会中断整轮（后续工具仍会跑）', (await lastCalls()).some((s) => !s.includes('idle')), (await lastCalls()).join(' '))

// 始终允许：写入策略，下次不再询问
await page.waitForFunction(() => !document.querySelector('.thought-chain[data-working]'), null, { timeout: 20000 }).catch(() => {})
await page.waitForTimeout(600)
await page.locator('.prompt-bar__input').fill('始终允许检查')
await page.locator('.prompt-bar__input').press('Enter')
await page.waitForTimeout(3000)
await page.locator('.confirm-card button', { hasText: '始终允许' }).click()
await page.waitForTimeout(800)
check('「始终允许」写入工具策略', (await page.evaluate(() => localStorage.getItem('nx-proto-tool-policy')))?.includes('js.sandbox') === true)
await page.waitForFunction(() => !document.querySelector('.thought-chain[data-working]'), null, { timeout: 20000 }).catch(() => {})
await page.waitForTimeout(600)
await page.locator('.prompt-bar__input').fill('第二次应免确认')
await page.locator('.prompt-bar__input').press('Enter')
await page.waitForTimeout(3200)
check('已授权的工具不再询问', (await page.locator('.confirm-card').count()) === 0)
await page.waitForFunction(() => !document.querySelector('.thought-chain[data-working]'), null, { timeout: 20000 }).catch(() => {})

/* ---------- 11.4 P0-3：回答反馈条（一次点击落库 + 可归因 + 可撤销） ---------- */
await page.evaluate(() => localStorage.removeItem('nx-proto-feedback'))
await page.waitForTimeout(300)
check('回答上出现反馈条', (await page.locator('.msg-ops .fb').count()) >= 1, `${await page.locator('.msg-ops .fb').count()} 条`)

const lastFb = page.locator('.msg-ops .fb').last()
await lastFb.locator('.msg-op').first().click() // 第一个 msg-op 永远是 👍 有帮助
await page.waitForTimeout(500)
const upRec = await page.evaluate(() => JSON.parse(localStorage.getItem('nx-proto-feedback') || '[]'))
check('👍 一次点击即落库', upRec.length === 1 && upRec[0].vote === 'up', JSON.stringify(upRec[0] ?? null).slice(0, 80))
/* 归因字段必须齐全，否则收上来也分析不了 */
const needKeys = ['msgId', 'sessionId', 'agent', 'kbCount', 'citedCount', 'tools', 'createdAt']
check('反馈带齐归因字段（agent/引用数/工具/耗时）', needKeys.every((k) => k in upRec[0]) && 'totalMs' in upRec[0],
  Object.keys(upRec[0]).join(','))

// 撤销
// 上一次点击已把条目切成“已记录”态：等待 done 态出现后再点撤销
await page.waitForSelector('.msg-ops .fb.fb--done', { timeout: 8000 })
await page.locator('.msg-ops .fb.fb--done').last().locator('.fb__undo').click()
await page.waitForTimeout(400)
check('撤销后记录被删除', (await page.evaluate(() => JSON.parse(localStorage.getItem('nx-proto-feedback') || '[]').length)) === 0)

// 👎 + 原因（同一条回答改主意应覆盖而不是新增）
await page.locator('.msg-ops .fb').last().locator('.msg-op').last().click() // 第二个 msg-op 永远是 👎 没答好
await page.waitForTimeout(400)
await page.locator('.fb--done .fb__reason', { hasText: '引用不对' }).click()
await page.waitForTimeout(500)
const downRec = await page.evaluate(() => JSON.parse(localStorage.getItem('nx-proto-feedback') || '[]'))
check('👎 可补原因且不重复计数', downRec.length === 1 && downRec[0].vote === 'down' && downRec[0].reason === '引用不对',
  JSON.stringify(downRec[0]?.reason))

/* ---------- 11.5 P0 回归：停止的收尾语义 + 会话持久化 ---------- */
await page.locator('.prompt-bar__input').fill('停止语义检查')
await page.locator('.prompt-bar__input').press('Enter')
await page.waitForTimeout(1200)
check('运行时确有工具在跑', (await page.locator('.thought-call[data-status="running"]').count()) >= 1)
await page.locator('.prompt-bar button[aria-label*="Stop"], .prompt-bar button[aria-label*="停止"]').first().click()
await page.waitForTimeout(1000)
const stopInfo = await page.evaluate(() => ({
  running: document.querySelectorAll('.thought-call[data-status="running"]').length,
  cancelled: document.querySelectorAll('.thought-call[data-status="cancelled"]').length,
  /* 「继续生成」是图标按钮，无文本，只能按 aria-label 定位 */
  canContinue: !!document.querySelector('.msg-ops button[aria-label="继续生成"]'),
}))
check('停止后没有工具卡在 running', stopInfo.running === 0, `cancelled=${stopInfo.cancelled}`)
check('停止后给出「继续生成」入口', stopInfo.canContinue === true)
await page.waitForTimeout(3000)
check('停止后没有后台继续推进', (await page.locator('.thought-call[data-status="running"]').count()) === 0)
await page.locator('.msg-ops button[aria-label="继续生成"]').first().click()
await page.waitForTimeout(2200)
const contInfo = await page.evaluate(() => {
  /* 只看最后一条：确认卡专项测试会留下 cancelled 的条目 */
  const groups = document.querySelectorAll('.thought-chain')
  const last = groups[groups.length - 1]
  return {
    cancelled: last ? last.querySelectorAll('.thought-call[data-status="cancelled"]').length : 0,
    len: document.querySelector('.thread .msg.bot .bub')?.textContent?.length ?? 0,
  }
})
check('继续生成后工具收尾、回答补完', contInfo.cancelled === 0 && contInfo.len > 50, JSON.stringify(contInfo))

const persist = await page.evaluate(() => {
  const raw = localStorage.getItem('nx-proto-sessions')
  const data = raw ? JSON.parse(raw) : null
  return { stored: !!raw, version: data?.version, sessions: data?.sessions?.length ?? 0 }
})
/*
 * ⚠️ 这里断言 version === 2 而不是写死"等于STORE_VERSION"：
 * 脚本跑在浏览器里，拿不到源码常量，只能硬编码；
 * 而 STORE_VERSION 已经因为「旧快照里 id 是数字」升过一次（sessions.ts:40），
 * 断言还写 1 就会一直红——那是测试滞后于实现，不是回归。
 *
 * 真正的契约是「快照带版本号，且版本与代码期望的一致」，
 * 版本号本身是实现细节。升版本时记得同步这里（注释已标注）。
 */
check('会话写入本地快照（带版本号）', persist.stored === true && persist.version === 2 && persist.sessions > 0, JSON.stringify(persist))

/* ---------- 12. 全程不得有 React 警告/运行时报错 ----------
   （曾出现：同一毫秒内两条消息都用 Date.now() 当 id → 重复 key 警告） */
check('会话页全程无控制台错误', consoleErrors.length === 0, consoleErrors.slice(0, 2).join(' | '))

await browser.close()
if (issues.length) {
  console.log(`\n发现 ${issues.length} 个问题:`)
  issues.forEach((i) => console.log('  - ' + i))
  process.exit(1)
} else {
  console.log('\n会话栏专项 QA 全部通过')
}
