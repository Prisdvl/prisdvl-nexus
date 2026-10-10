/**
 * R3 认证链路验证（R3 任务书 §21）。
 *
 * 全部用 `data-testid` 定位，不使用中文 text selector。
 *
 * ## 三态结论，不要把 SKIP 混进 FAIL
 *
 * 后端（docker compose）未启动时，依赖真实登录的用例**无法执行**。
 * 这时把它们标成 `SKIP` 而不是 `FAIL`——"环境不具备条件"与"代码有问题"
 * 是两件事，混在一起会让真实回归被淹没。
 */
import { chromium } from 'playwright-core'

const base = process.env.R2_BASE ?? 'http://localhost:5174'
const apiBase = process.env.API_BASE ?? 'http://localhost:8088'
/**
 * 测试账号。
 *
 * 来自仓库的既有规定：`scripts/seed-demo.mjs` 种入 `demo` / `Demo@12345`，
 * 见 `docs/deployment.md`、`docs/decisions.md`、`docs/api.md`。
 * **不自行编造账号**（R3 任务书 §21）。可用环境变量覆盖。
 */
const USERNAME = process.env.DEMO_USER ?? 'demo'
const PASSWORD = process.env.DEMO_PASSWORD ?? 'Demo@12345'

const pass = []
const fail = []
const skip = []
const consoleErrors = []
const pageErrors = []

/** 后端是否可用。不可用时把需要真实登录的用例标 SKIP。 */
async function backendAlive() {
  try {
    const r = await fetch(`${apiBase}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: '__probe__', password: '__probe__' })
    })
    // 能拿到任意 HTTP 响应就说明服务在监听（400/401 都算活着）
    return r.status > 0
  } catch {
    return false
  }
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 980 } })
const page = await ctx.newPage()
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text())
})
page.on('pageerror', (e) => pageErrors.push(e.message))

const count = async (sel) => page.locator(sel).count()
const check = (ok, label, detail = '') => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ' — ' + detail : ''}`)
  ;(ok ? pass : fail).push(label + (detail ? ` (${detail})` : ''))
}
const skipCase = (label, why) => {
  console.log(`  SKIP  ${label} — ${why}`)
  skip.push(label)
}

const go = async (hash) => {
  await page.goto(`${base}/#${hash}`, { waitUntil: 'networkidle' })
}
const login = async (u, p) => {
  await page.fill('[data-testid="login-username"]', u)
  await page.fill('[data-testid="login-password"]', p)
  await page.click('[data-testid="login-submit"]')
}

const backendUp = await backendAlive()
console.log(`后端 ${apiBase}：${backendUp ? '可用' : '不可用（将相关用例标 SKIP）'}\n`)

try {
  // ---------- ① 未登录访问受保护路由 → 自动跳 login ----------
  console.log('① 未登录访问受保护路由')
  await go('/')
  await page.waitForTimeout(600)
  const onLogin = page.url().includes('#/login')
  check(onLogin, '未登录访问 / 自动跳 /login', page.url().split('#')[1] ?? '')

  // ---------- ② 初始化期间显示占位，而不是空白或误跳 ----------
  const sawInitializing = await page.evaluate(() => {
    // 事后无法回看，只能看当前是否已稳定在登录页——这里改为检查"没有裸空白"
    return document.body.innerText.trim().length > 0
  })
  check(sawInitializing, '跳转后页面非空白（不是 undefined/裸加载）')

  // ---------- ③ 登录表单出现 ----------
  console.log('② 登录表单')
  const form = await count('[data-testid="login-page"]')
  check(form === 1, 'login-page 渲染')
  const hasUser = (await count('[data-testid="login-username"]')) === 1
  const hasPwd = (await count('[data-testid="login-password"]')) === 1
  const hasSubmit = (await count('[data-testid="login-submit"]')) === 1
  check(hasUser && hasPwd && hasSubmit, '用户名 / 密码 / 提交按钮齐全')

  // ---------- ④ 空表单提交被前端校验拦下 ----------
  await page.click('[data-testid="login-submit"]')
  await page.waitForTimeout(300)
  const stillOnLogin = page.url().includes('#/login')
  check(stillOnLogin, '空表单提交被拦下，仍在登录页')

  // ---------- ⑤ 错误密码不进入工作区 ----------
  if (!backendUp) {
    skipCase('错误密码被拒', '后端不可用')
    skipCase('正确密码登录成功', '后端不可用')
    skipCase('Header 显示真实用户名', '后端不可用')
    skipCase('刷新后登录态恢复', '后端不可用')
    skipCase('logout 后回到 login', '后端不可用')
    skipCase('logout 后 refresh 仍是 login', '后端不可用')
  } else {
    console.log('③ 错误密码')
    await login(USERNAME, 'definitely-wrong-password')
    await page.waitForTimeout(1200)
    const rejected = (await count('[data-testid="login-error"]')) === 1
    const notInWorkspace = (await count('[data-testid="workspace-shell"]')) === 0
    const errText = rejected ? (await page.locator('[data-testid="login-error"]').innerText()).trim() : ''
    check(rejected && notInWorkspace, '错误密码被拒且未进入工作区', errText.slice(0, 40))
    check(!/undefined|null|NaN/i.test(errText), '错误提示里没有 undefined / null / NaN', errText.slice(0, 40))
    // 关键断言：**必须显示后端原话「用户名或密码错误」**。
    // 后端对错误密码返回 HTTP 401 + {code:2006, message:"用户名或密码错误"}；
    // 如果 client 的 401 分支硬编码成「登录已过期」，用户会以为登录状态坏了
    // 而不是"密码错了"——那是提示掩盖真实原因。这条断言就是防它回归。
    check(
      /用户名或密码错误/.test(errText),
      '错误提示显示后端原话（不是硬编码的"登录已过期"）',
      `"${errText}"`
    )
    check(
      !/登录已过期/.test(errText),
      '错误提示没有把"密码错"说成"登录过期"',
      `"${errText}"`
    )

    console.log('④ 正确密码')
    await login(USERNAME, PASSWORD)
    await page.waitForTimeout(2500)
    const inWorkspace = (await count('[data-testid="workspace-shell"]')) === 1
    check(inWorkspace, '登录成功进入工作区', page.url().split('#')[1] ?? '')

    // ---------- ⑥ Header 显示真实用户名 ----------
    const shownName = (await count('[data-testid="user-menu-name"]'))
      ? (await page.locator('[data-testid="user-menu-name"]').innerText()).trim()
      : ''
    const shownInitial = (await count('[data-testid="user-menu-initial"]'))
      ? (await page.locator('[data-testid="user-menu-initial"]').innerText()).trim()
      : ''
    check(shownName.length > 0, 'Header 显示用户名', `"${shownName}"`)
    // 判据是"**与登录输入一致**"，不是"不等于 demo"。
    // 第一版写的是 `!/undefined|demo$/i.test(shownName)` —— 而测试账号真名就叫 demo，
    // 于是把"正确的显示"判成了失败。**排除具体字符串是脆弱的**：换个账号就失效。
    check(
      shownName === USERNAME,
      'Header 用户名与登录输入一致（证明来自后端而非硬编码）',
      `"${shownName}" vs 登录 "${USERNAME}"`
    )
    check(!/undefined|null|NaN/i.test(shownName), '用户名不是 undefined / null / NaN', `"${shownName}"`)
    check(
      shownInitial === shownName.slice(0, 1).toUpperCase(),
      '头像首字母由真实用户名派生',
      `"${shownInitial}"`
    )

    // ---------- ⑦ 刷新后登录态恢复（不闪回登录页） ----------
    console.log('⑤ 刷新恢复')
    const tokenBefore = await page.evaluate(() => localStorage.getItem('nexus.token'))
    check(!!tokenBefore, 'token 已落 localStorage')
    await page.reload({ waitUntil: 'networkidle' })
    // 关键：刷新后**最终**要在工作区，且过程中不应长时间停在登录页
    await page.waitForTimeout(2500)
    const afterReload = page.url()
    check(
      afterReload.includes('#/') && !afterReload.includes('#/login'),
      '刷新后停留在工作区（未被踢回登录页）',
      afterReload.split('#')[1] ?? ''
    )

    // ---------- ⑧ logout ----------
    console.log('⑥ 退出登录')
    await page.click('[data-testid="user-menu"]')
    await page.waitForTimeout(500)
    await page.click('[data-testid="user-menu-logout"]')
    await page.waitForTimeout(1200)
    const afterLogout = page.url()
    check(afterLogout.includes('#/login'), 'logout 后回到 /login', afterLogout.split('#')[1] ?? '')
    const tokenAfter = await page.evaluate(() => localStorage.getItem('nexus.token'))
    check(!tokenAfter, 'logout 后 token 已从 localStorage 清除')

    // ---------- ⑨ logout 后 refresh 仍是 login（不能"又登录回来"）----------
    await page.reload({ waitUntil: 'networkidle' })
    await page.waitForTimeout(2000)
    const afterLogoutReload = page.url()
    check(
      afterLogoutReload.includes('#/login'),
      'logout 后 refresh 仍然是 /login（没有自动恢复登录）',
      afterLogoutReload.split('#')[1] ?? ''
    )

    // ---------- ⑩ 401 不产生无限 redirect ----------
    console.log('⑦ 401 生命周期')
    // 塞一个**无效** token：真实请求会 401，client 应清 token 并单次跳登录
    await page.evaluate(() => localStorage.setItem('nexus.token', 'invalid-token-for-test'))
    // 必须用 reload 而不是 goto —— **hash 路由下 goto 只改 hash，不重新加载页面**。
    // 第一版用 go('/')，页面根本没重载，`initialize()` 也就没重新执行，
    // 于是 token 无人清理、断言失败。那是探针的错，不是代码的错。
    await page.reload({ waitUntil: 'networkidle' })
    await page.waitForTimeout(3500)
    const badTokenUrl = page.url()
    const badTokenLeft = await page.evaluate(() => localStorage.getItem('nexus.token'))
    check(badTokenLeft === null, '无效 token 被清掉')
    // 关键：URL 里不能出现 /login → /login 的抖动；React Router replace 会消掉历史
    const historyLen = await page.evaluate(() => window.history.length)
    check(historyLen < 30, '没有出现 redirect 死循环（历史长度未暴涨）', `history=${historyLen}`)
    check(
      badTokenUrl.includes('#/login'),
      '无效 token 最终停在 /login',
      badTokenUrl.split('#')[1] ?? ''
    )
  }

  // ---------- ⑪ 404 仍在（回归） ----------
  console.log('⑧ 404 与守卫边界')
  await go('/no-such-route')
  await page.waitForTimeout(500)
  const nf = (await count('[data-testid="not-found"]')) === 1
  check(nf, '非法路径落到 404（不被守卫吞掉）')

  // ---------- ⑫ 无错误 ----------
  console.log('⑨ 错误检查')
  // 过滤两类**预期内**的噪声，否则断言会永远失败：
  //  a) 后端不可达导致的资源错误——环境问题，不是代码问题
  //  b) 401 —— 第 ⑦ 组用例**故意**塞无效 token 触发它。浏览器把任何 4xx
  //     都记成 console error，那是预期行为，不是缺陷。
  const realConsoleErrors = consoleErrors.filter(
    (t) => !/Failed to load resource|502|fetch/i.test(t) && !/401/.test(t)
  )
  check(realConsoleErrors.length === 0, 'console error = 0', realConsoleErrors.slice(0, 2).join(' | '))
  check(pageErrors.length === 0, 'page error = 0', pageErrors.slice(0, 2).join(' | '))
} catch (e) {
  fail.push('探针中断：' + e.message)
  console.log('  FAIL  探针中断 — ' + e.message)
} finally {
  await page.screenshot({ path: 'r3-verify.png' }).catch(() => {})
  await browser.close()
}

console.log(`\n结论：PASS ${pass.length} / FAIL ${fail.length} / SKIP ${skip.length}`)
if (skip.length) console.log(`SKIP 项（后端不可用）：${skip.join('、')}`)
if (fail.length) {
  console.log('失败项：')
  for (const f of fail) console.log('  - ' + f)
  process.exit(1)
}
console.log('PASS R3 认证链路')
