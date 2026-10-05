/**
 * R2 Layout 验证（任务书 §31）。
 *
 * 覆盖：导航高亮 / Sidebar 折叠 / localStorage 持久化 / 路由跳转 / 404 / 响应式。
 * 全部用 `data-testid` 定位——**不使用中文 text selector**（任务书 §25）。
 *
 * 刻意不覆盖业务：R2 没有 API，也不该有。
 */
import { chromium } from 'playwright-core'

const base = process.env.R2_BASE ?? 'http://localhost:5174'
const problems = []
const consoleErrors = []
const pageErrors = []
/** 4xx/5xx 响应的 URL —— console 文本里没有 URL，只能自己记。 */
const badResponses = []

const browser = await chromium.launch({ channel: 'msedge', headless: true })
// 每个分辨率都要新建 context：localStorage 隔离，否则折叠状态会跨分辨率泄漏
const wide = await browser.newContext({ viewport: { width: 1440, height: 980 } })
const page = await wide.newPage()
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text())
})
page.on('pageerror', (e) => pageErrors.push(e.message))
page.on('response', (r) => {
  if (r.status() >= 400) badResponses.push(r.status() + ' ' + r.url().replace(base, ''))
})

/**
 * 登录并把 token 注入 localStorage（R3 起必需）。
 *
 * ## 为什么不塞一个假 token
 *
 * 守卫会用 token 调 `GET /users/me` 验证，假 token 拿到 401 → 立刻被清掉并重定向，
 * 结果与"没登录"完全一样，却制造了一次假的 401 流程，反而掩盖真实问题。
 *
 * ## 为什么注入 storage 而不是走 UI 表单
 *
 * 本脚本要重复进工作区 10 次（每条路由一次）。每次走 UI 登录慢十倍，
 * 而且把"能否登录"与"Layout 对不对"两个关注点混在一起。这里只登录一次。
 */
async function ensureAuthenticated() {
  const apiBase = process.env.API_BASE ?? 'http://localhost:8088'
  const username = process.env.DEMO_USER ?? 'demo'
  const password = process.env.DEMO_PASSWORD ?? 'Demo@12345'
  let res
  try {
    res = await fetch(`${apiBase}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    })
  } catch {
    return { ok: false, why: '后端不可达' }
  }
  if (!res.ok) return { ok: false, why: `登录接口返回 HTTP ${res.status}` }
  const body = await res.json()
  const token = body?.data?.accessToken
  if (!token) return { ok: false, why: '登录响应里没有 accessToken' }

  await page.context().addInitScript((value) => {
    localStorage.setItem('nexus.token', value)
    localStorage.setItem('nexus.username', 'demo')
  }, token)
  await page.goto(`${base}/#/`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(2000)
  if ((await page.locator('[data-testid="workspace-shell"]').count()) === 0) {
    return { ok: false, why: '注入 token 后仍未进入工作区' }
  }
  return { ok: true }
}

const count = async (sel) => page.locator(sel).count()
async function must(sel, label) {
  const n = await count(sel)
  console.log(`  ${label.padEnd(40)} ${n > 0 ? 'OK' : 'FAIL'}（${n}）`)
  if (n === 0) problems.push(`${label}：${sel} 未渲染`)
}
const go = async (hash) => {
  await page.goto(`${base}/#${hash}`, { waitUntil: 'networkidle' })
}

/** 某导航项是否处于选中态。用 class 判定——React Router 的 NavLink 会加 aria-current。 */
async function isActive(testId) {
  return page.locator(`[data-testid="${testId}"][aria-current="page"]`).count()
}

try {
  // ---------- ⓪ 认证准备（R3 新增） ----------
  // R3 给工作区加了 RequireAuth，未登录访问会被重定向到 /login。
  // 不先登录的话，本脚本的 20 项会**全部失败**——那不是回归，是它没跟上结构变化。
  // 后端不可用时整份脚本标 SKIP（退出码 2），绝不把"环境不具备条件"报成 FAIL。
  console.log('⓪ 认证准备')
  const auth = await ensureAuthenticated()
  if (!auth.ok) {
    console.log(`  SKIP  全部用例 — ${auth.why}。`)
    console.log('        R3 为工作区加了登录守卫，未登录会被重定向到 /login；')
    console.log('        本脚本需要真实登录态才能测 Layout。')
    console.log('        起后端后重跑：cd deploy && docker compose up -d')
    await browser.close()
    process.exit(2)
  }
  console.log('  PASS  已登录并进入工作区')

  // ---------- ① 结构 ----------
  console.log('① 外壳结构')
  await go('/')
  await must('[data-testid="workspace-shell"]', 'workspace-shell')
  await must('[data-testid="app-header"]', 'app-header')
  await must('[data-testid="workspace-sidebar"]', 'workspace-sidebar')
  await must('[data-testid="workspace-main"]', 'workspace-main')
  await must('[data-testid="workspace-sidebar-toggle"]', 'workspace-sidebar-toggle')
  await must('[data-testid="user-menu"]', 'user-menu')

  // ---------- ② 三条导航（不靠文案定位）----------
  console.log('② 一级导航（知识库 / 对话 / Agent）')
  for (const id of ['workspace-nav-knowledge', 'workspace-nav-chat', 'workspace-nav-agent']) {
    await must(`[data-testid="${id}"]`, id)
  }

  // ---------- ③ 导航高亮跟随路由（任务书 §8）----------
  console.log('③ 导航高亮')
  const cases = [
    ['/', 'workspace-nav-knowledge'],
    ['/agent', 'workspace-nav-chat'],
    ['/agents', 'workspace-nav-agent']
  ]
  for (const [hash, expectId] of cases) {
    await go(hash)
    const ok = (await isActive(expectId)) === 1
    // 顺带确认只有它高亮
    const others = cases.filter(([, id]) => id !== expectId).map(([, id]) => id)
    const othersActive = []
    for (const o of others) if ((await isActive(o)) > 0) othersActive.push(o)
    console.log(`  ${hash.padEnd(9)} → ${expectId} ${ok ? 'OK' : 'FAIL'}${othersActive.length ? ' | 误高亮: ' + othersActive.join(',') : ''}`)
    if (!ok) problems.push(`${hash} 未高亮 ${expectId}`)
    if (othersActive.length) problems.push(`${hash} 误高亮 ${othersActive.join(',')}`)
  }

  // 详情页：/kb/:id 时"知识库"仍应高亮（它是父级）
  await go('/kb/12345')
  const kbActive = (await isActive('workspace-nav-knowledge')) === 1
  console.log(`  /kb/12345 → 知识库高亮 ${kbActive ? 'OK' : 'FAIL'}`)
  if (!kbActive) problems.push('/kb/:id 下知识库未高亮')

  // ---------- ④ Sidebar 折叠 + localStorage（任务书 §6、§28）----------
  console.log('④ Sidebar 折叠与持久化')
  const sidebar = page.locator('[data-testid="workspace-sidebar"]')
  const initialCollapsed = await sidebar.getAttribute('data-collapsed')
  const initialWidth = (await sidebar.boundingBox()).width
  console.log(`  初始 data-collapsed=${initialCollapsed} 宽度=${initialWidth.toFixed(0)}px`)

  // 宽度读数要真的变小，且 Toggle 的 aria-label 要跟着翻转
  const toggle = page.locator('[data-testid="workspace-sidebar-toggle"]')
  const labelBefore = await toggle.getAttribute('aria-label')
  await toggle.click()
  await page.waitForTimeout(450) // 等 width 过渡完成
  const afterCollapsed = await sidebar.getAttribute('data-collapsed')
  const afterWidth = (await sidebar.boundingBox()).width
  const labelAfter = await toggle.getAttribute('aria-label')
  console.log(`  折叠后 data-collapsed=${afterCollapsed} 宽度=${afterWidth.toFixed(0)}px aria-label: "${labelBefore}" → "${labelAfter}"`)
  if (initialWidth - afterWidth < 100) problems.push(`折叠后宽度只减少 ${(initialWidth - afterWidth).toFixed(0)}px，视觉上等于没折叠`)
  if (labelBefore === labelAfter) problems.push('折叠后 toggle 的 aria-label 没有翻转（读屏用户听不出按下会发生什么）')

  const stored = await page.evaluate(() => localStorage.getItem('nexus.sidebar.collapsed'))
  console.log(`  localStorage nexus.sidebar.collapsed = ${stored}`)
  if (stored !== 'true') problems.push(`localStorage 未写入 true（实际 ${stored}）`)

  // 刷新后保持
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForTimeout(400)
  const reloaded = await page.locator('[data-testid="workspace-sidebar"]').getAttribute('data-collapsed')
  const reloadWidth = (await page.locator('[data-testid="workspace-sidebar"]').boundingBox()).width
  console.log(`  刷新后 data-collapsed=${reloaded} 宽度=${reloadWidth.toFixed(0)}px`)
  if (reloaded !== 'true') problems.push('刷新后折叠状态丢失')
  if (Math.abs(reloadWidth - afterWidth) > 2) problems.push('刷新后宽度与刷新前不一致')

  // 重新进入（全新导航）仍保持
  await go('/agents')
  const reNav = await page.locator('[data-testid="workspace-sidebar"]').getAttribute('data-collapsed')
  console.log(`  切换路由后 data-collapsed=${reNav}`)
  if (reNav !== 'true') problems.push('切换路由后折叠状态丢失')

  // 收起后 Tooltip 是否可提示（图标不能"无解释"）
  const navCount = await count('[data-testid="workspace-nav-knowledge"]')
  const hasSrOnly = await page.evaluate(() => {
    const el = document.querySelector('[data-testid="workspace-nav-knowledge"]')
    return !!el?.querySelector('.sr-only')
  })
  console.log(`  收起态导航项 ${navCount} 个，含 sr-only 文本：${hasSrOnly}`)
  if (!hasSrOnly) problems.push('收起态导航图标没有可读文本（Tooltip 之外还需 sr-only 兜底）')

  // 恢复展开，留给后面的用例
  await page.locator('[data-testid="workspace-sidebar-toggle"]').click()
  await page.waitForTimeout(400)
  await page.evaluate(() => localStorage.removeItem('nexus.sidebar.collapsed'))

  // ---------- ⑤ Header 标题随路由变（任务书 §9）----------
  console.log('⑤ Header 标题')
  for (const [hash, expect] of [['/', '知识库'], ['/agent', '对话'], ['/agents', 'Agent']]) {
    await go(hash)
    const t = (await page.locator('[data-testid="app-header-title"]').innerText()).trim()
    console.log(`  ${hash.padEnd(9)} → "${t}" ${t === expect ? 'OK' : 'FAIL(期望 ' + expect + ')'}`)
    if (t !== expect) problems.push(`${hash} 标题为 "${t}"，期望 "${expect}"`)
  }

  // ---------- ⑥ 404（任务书 §19）----------
  console.log('⑥ 404')
  await go('/this-route-does-not-exist')
  await must('[data-testid="not-found"]', 'not-found')
  await must('[data-testid="not-found-home-link"]', '返回链接')
  const homeHref = await page.locator('[data-testid="not-found-home-link"]').getAttribute('href')
  console.log(`  返回链接 href = ${homeHref}`)
  if (!homeHref || !homeHref.includes('/')) problems.push('404 的返回链接没有指向知识库')

  // ---------- ⑦ 键盘可达（任务书 §26、§27）----------
  console.log('⑦ 键盘')
  await go('/')
  await page.keyboard.press('Tab')
  const firstFocus = await page.evaluate(() => document.activeElement?.getAttribute('data-testid') ?? '(none)')
  console.log(`  第一次 Tab 落在 ${firstFocus}`)
  // 逐个 Tab 直到能到导航项与 toggle，最多 20 次
  let reachedNav = false
  let reachedToggle = false
  for (let i = 0; i < 20; i++) {
    await page.keyboard.press('Tab')
    const id = await page.evaluate(() => document.activeElement?.getAttribute('data-testid') ?? '')
    if (id === 'workspace-nav-knowledge') reachedNav = true
    if (id === 'workspace-sidebar-toggle') reachedToggle = true
    if (reachedNav && reachedToggle) break
  }
  console.log(`  Tab 能到达导航=${reachedNav} 能到达折叠开关=${reachedToggle}`)
  if (!reachedNav) problems.push('键盘 Tab 到不了导航项')
  if (!reachedToggle) problems.push('键盘 Tab 到不了折叠开关')

  // Enter 能激活导航
  await page.locator('[data-testid="workspace-nav-chat"]').focus()
  await page.keyboard.press('Enter')
  await page.waitForTimeout(300)
  const afterEnter = page.url().includes('#/agent')
  console.log(`  Enter 激活「对话」→ ${afterEnter ? 'OK' : 'FAIL'}`)
  if (!afterEnter) problems.push('Enter 不能激活导航项')

  // ---------- ⑧ 用户菜单（placeholder，任务书 §10）----------
  console.log('⑧ 用户菜单')
  await page.locator('[data-testid="user-menu"]').click()
  await page.waitForTimeout(400)
  await must('[data-testid="user-menu-profile"]', '个人资料项')
  await must('[data-testid="user-menu-password"]', '修改密码项')
  await must('[data-testid="user-menu-logout"]', '退出按钮')
  const inDialog = await page.evaluate(() => {
    const el = document.querySelector('[data-testid="user-menu-profile"]')
    return !!el?.closest('[role="dialog"]')
  })
  console.log(`  菜单在 dialog 内（含焦点陷阱）= ${inDialog}`)
  if (!inDialog) problems.push('用户菜单没有放在 role=dialog 里，焦点会跑到背景')
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)
  const closed = (await count('[data-testid="user-menu-profile"]')) === 0
  console.log(`  ESC 关闭 = ${closed ? 'OK' : 'FAIL'}`)
  if (!closed) problems.push('ESC 关不掉用户菜单')

  // ---------- ⑨ 响应式（任务书 §14、§30）----------
  console.log('⑨ 响应式')
  for (const [w, h] of [[1920, 1080], [1366, 768], [1024, 768]]) {
    await page.setViewportSize({ width: w, height: h })
    await go('/')
    await page.waitForTimeout(350)
    const m = await page.evaluate(() => ({
      overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      hasSidebar: !!document.querySelector('[data-testid="workspace-sidebar"]'),
      hasMenuBtn: !!document.querySelector('[data-testid="app-header-menu"]'),
      menuVisible: (() => {
        const b = document.querySelector('[data-testid="app-header-menu"]')
        return b ? getComputedStyle(b).display !== 'none' : false
      })(),
      mainW: document.querySelector('[data-testid="workspace-main"]')?.getBoundingClientRect().width ?? 0
    }))
    console.log(
      `  ${w}×${h} 溢出 ${m.overflow}px | 侧栏 ${m.hasSidebar ? '有' : '无(抽屉)'} | 菜单键可见 ${m.menuVisible} | 主区 ${m.mainW.toFixed(0)}px`
    )
    if (m.overflow > 2) problems.push(`${w}×${h} 横向溢出 ${m.overflow}px`)
    if (!m.mainW) problems.push(`${w}×${h} 主区宽度为 0`)
    // 1024 及以下应切到抽屉
    // 阈值 900：1024 是 Tablet，走完整侧栏（任务书 §14「先解决 Desktop / Tablet」）
    if (w < 900 && m.hasSidebar) problems.push(`${w}×${h} 移动端宽度仍在渲染桌面侧栏`)
    if (w >= 1024 && !m.hasSidebar) problems.push(`${w}×${h} Tablet/桌面宽度却没有侧栏`)
    if (w < 900 && !m.menuVisible) problems.push(`${w}×${h} 移动端但菜单按钮不可见`)
  }
  await page.setViewportSize({ width: 1440, height: 980 })

  // ---------- ⑩ 无错误 ----------
  console.log('⑩ 错误检查')
  /*
   * 排除 `/kb/12345` 带来的**预期** 404。
   *
   * 本脚本用**不存在的** id 测「详情路由可达」，所以
   * `GET /api/knowledge-bases/12345/documents` 必然 404 —— 那是**测试数据**造成的，
   * 不是缺陷（R7 之后详情页会真的去拉数据）。
   *
   * **必须用 response 事件按 URL 过滤，不能过滤 console 文本** ——
   * Chrome 的 console message 形如
   * `"Failed to load resource: the server responded with a status of 404 (Not Found)"`，
   * **里面没有 URL**。第一版按文本匹配 `/documents/` 因此完全失效。
   *
   * 精确匹配这一条（URL 含 `knowledge-bases/12345/documents`），
   * 其余 4xx/5xx 一律算失败 —— 宽泛过滤会把真问题一起藏起来。
   */
  const EXPECTED_404 = /knowledge-bases\/12345\/documents/
  const realFailed = badResponses.filter((r) => !EXPECTED_404.test(r))
  const realConsoleErrors = consoleErrors.filter(
    (t) => !/Failed to load resource/.test(t)
  )
  console.log(`  失败请求 = ${realFailed.length} | console error = ${realConsoleErrors.length} | page error = ${pageErrors.length}`)
  if (pageErrors.length) problems.push('页面 JS 错误：' + pageErrors[0])
  if (realConsoleErrors.length) problems.push('console error：' + realConsoleErrors[0])
  if (realFailed.length) problems.push('失败请求：' + realFailed.slice(0, 3).join(' | '))
} catch (e) {
  problems.push('探针中断：' + e.message)
} finally {
  await page.screenshot({ path: 'r2-layout.png' }).catch(() => {})
  await wide.close()
  await browser.close()
}

if (problems.length) {
  console.log('\nFAIL')
  for (const p of problems) console.log('  - ' + p)
  process.exit(1)
}
console.log('\nPASS R2：外壳结构 / 导航高亮 / Sidebar 折叠与持久化 / Header 标题 / 404 / 键盘 / 响应式 全部正常')
