/**
 * 用户安全体系的真机 E2E（Phase 5.6）。
 *
 * 这一期后端补了四件事：改密码（+ 令牌吊销）、资料编辑、登录失败限制、限流/配额。
 * 每一条的单元与契约测试都在桩上跑——它们能证明"接口按预期应答"，
 * 但证明不了**用户在浏览器里能不能走通**：改完密码到底跳没跳登录页、
 * 侧栏的名字会不会跟着改、额度那一行长什么样。本脚本补的就是这一段。
 *
 * 八条腿（顺序是有意的，后一步依赖前一步的状态；编号与运行时打印的 ①~⑧ 一一对应）：
 *   ① 注册一个临时账号；② 用它登录（**不依赖 seed-demo**：改密码会把演示账号的密码也改掉，
 *      那样后面所有脚本都会登不进去，所以这条路必须用自己造的账号）；
 *   ③ 对话页能看到"今日 N/M 次 · x/y tokens"那一行（额度从"隐形旋钮"变成可见）；
 *   ④ 改资料：昵称保存后**侧栏立刻跟着变**（这是"改了就生效"的唯一证明）；
 *   ⑤ ★ 改密码时**原密码填错** → 必须是参数错误（1001）而**不是** 401：
 *      401 会触发全局拦截器清 token 跳登录页，把一个输入错误升级成会话中断。
 *      所以这里断言"弹窗还开着、人还登着"，这条断言守的是一处易回归的分支；
 *   ⑥ ★ 改密码成功后：当前会话被主动送到登录页（不是被 401 踹出去的）；
 *   ⑦ ★ **用改密前抓到的旧 token 直接打接口 → 401**——这是"旧令牌真的失效了"的唯一证据；
 *   ⑧ 用新密码登录成功 → 昵称还在 → 退出。
 *
 * 关于"自清"：知识库/Agent 那几支脚本能把自己造的东西删干净，本脚本不能——
 * **系统没有注销账号的接口**（本期也没做）。临时账号会留在库里，用户名带时间戳前缀
 * `sec-check-` 便于识别。这是如实记录的缺口，不是忘了清理。
 *
 * 退出码：0 = 通过；1 = 真实失败。（本脚本没有 2/跳过这一档：这里没有"本机缺模型就验不了"
 * 之类的可跳过的能力，跑不起来一律是真失败。）
 *
 * 需要：后端 + 前端都在跑。
 */
import { chromium } from 'playwright-core'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const base = process.env.WEB_BASE ?? 'http://localhost:8088'
const apiBase = process.env.API_BASE ?? 'http://localhost:8088'

/** 截图目录按**脚本自己的位置**解析，不按 cwd（否则从 `web/` 运行会写进 `web/docs/screenshots/`） */
const here = dirname(fileURLToPath(import.meta.url))
const outDir = resolve(here, '../../docs/screenshots')

/** 改密前用的密码 / 改密后的密码。两者不同才能证明"改成了新密码、而且旧的不管用了"。 */
const OLD_PASSWORD = 'SecChk@12345'
const NEW_PASSWORD = 'SecChk@67890'
const NICKNAME = '安全验证用户'

const username = `sec-check-${Date.now().toString(36)}`

const failures = []

/**
 * 弹窗有多个同时存在，只挑可见的那个（与 verify-agents.mjs 同一手法）。
 *
 * 判据是**弹窗内部的稳定锚点**（data-testid），不是它的标题文案：
 *  - 标题是产品文案。改一次字，脚本就报"找不到弹窗"——一个完全指向不了真实原因的失败；
 *  - 按整窗文本筛还会撞车（2026-10-02 实测）：资料弹窗的页脚有一个「修改密码」按钮，
 *    点它之后资料弹窗开始关闭动画、**在动画结束前仍然是 `:visible`**，于是
 *    `.el-dialog:visible` + 文本匹配会同时命中资料弹窗与改密弹窗，
 *    Playwright 以 strict mode violation 直接失败。
 * 锚点是结构：`profile-form` / `password-form` 各自只出现在自己的弹窗里，两个问题一起消掉。
 */
function dialogByTestId(anchor) {
  return page.locator('.el-dialog:visible').filter({
    has: page.locator(`[data-testid="${anchor}"]`)
  })
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 980 } })

try {
  // ---------- ① 造账号（走接口，不经过界面：注册本身不是本脚本要验的东西） ----------
  const registered = await fetch(`${apiBase}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: OLD_PASSWORD })
  })
  if (!registered.ok) {
    throw new Error(`注册临时账号失败：HTTP ${registered.status} ${await registered.text()}`)
  }
  console.log(`① 临时账号已注册：${username}`)

  await page.goto(base, { waitUntil: 'networkidle' })
  await page.waitForSelector('[data-testid="auth-username"]', { timeout: 15000 })
  await page.fill('[data-testid="auth-username"]', username)
  await page.fill('[data-testid="auth-password"]', OLD_PASSWORD)
  await page.click('[data-testid="auth-submit"]')
  await page.waitForFunction(() => !location.hash.startsWith('#/login'), { timeout: 15000 })
  console.log('② 登录成功，已进入控制台')

  const tokenBeforeChange = await page.evaluate(() => localStorage.getItem('nexus.token'))
  if (!tokenBeforeChange) {
    throw new Error('登录后 localStorage 里没有 nexus.token')
  }

  // ---------- ② 额度那一行 ----------
  await page.click('[data-testid="nav-chat"]')
  await page.waitForSelector('.quota', { timeout: 15000 })
  const quotaText = (await page.locator('.quota').innerText()).replace(/\s+/g, ' ').trim()
  // 不断死具体数字：新账号是 0，但"上限是多少"随 profile 变化（测试 profile 与 standalone 就不同）
  if (!/^今日 \d+\/\d+ 次 · \d+(\.\d+)?k?\/\d+(\.\d+)?k? tokens$/.test(quotaText)) {
    throw new Error(`额度那一行格式不对：「${quotaText}」`)
  }
  console.log(`③ 对话页可见当日额度：${quotaText}`)

  // ---------- ③ 改资料 ----------
  await page.click('[data-testid="account-entry"]')
  const profileDialog = dialogByTestId('profile-form')
  await profileDialog.waitFor({ timeout: 15000 })
  await profileDialog.locator('[data-testid="profile-nickname"]').fill(NICKNAME)
  await profileDialog.locator('[data-testid="profile-save"]').click()
  await profileDialog.waitFor({ state: 'hidden', timeout: 15000 })

  const sidebarName = (await page.locator('[data-testid="account-name"]').innerText()).trim()
  if (sidebarName !== NICKNAME) {
    throw new Error(`侧栏没有跟着改成昵称：期望「${NICKNAME}」，实际「${sidebarName}」`)
  }
  console.log(`④ 昵称已保存，侧栏立即显示「${sidebarName}」`)

  // ---------- ④ 原密码填错：必须是参数错误，不能把人踢下线 ----------
  await page.click('[data-testid="account-entry"]')
  await profileDialog.waitFor({ timeout: 15000 })
  await profileDialog.locator('[data-testid="profile-change-password"]').click()
  // 等资料弹窗真的关掉再操作改密弹窗：它的遮罩在关闭动画期间仍盖在页面上，
  // 与其和动画抢时间，不如等它消失（这也让上面那条"两个弹窗都命中"的歧义彻底消失）
  await profileDialog.waitFor({ state: 'hidden', timeout: 15000 })
  const pwdDialog = dialogByTestId('password-form')
  await pwdDialog.waitFor({ timeout: 15000 })

  // 三个框各挂 data-testid。原先按 DOM 顺序取 nth(0/1/2)：标签里"新密码"是"确认新密码"的
  // 子串、按标签选会同时命中两个，于是退而求其次用了顺序——但一旦插入或重排字段，
  // 顺序定位会**静默错位**（把新密码填进确认框，却仍然"看起来填了三格"）。
  await pwdDialog.locator('[data-testid="password-old"]').fill('WrongOldPwd!1')
  await pwdDialog.locator('[data-testid="password-new"]').fill(NEW_PASSWORD)
  await pwdDialog.locator('[data-testid="password-confirm"]').fill(NEW_PASSWORD)

  // 先等上一步"资料已保存"那条 toast 自己消失，否则下面会读到它（而不是这次的报错），
  // 断言就变成了在验上一句提示语——一条看起来通过、其实什么都没验的断言
  await page.waitForFunction(() => document.querySelectorAll('.el-message').length === 0, {
    timeout: 15000
  })

  await pwdDialog.locator('[data-testid="password-submit"]').click()

  // 后端原话会以 toast 出现；这里要断言的是**没有发生的事**：既没跳登录页，弹窗也还开着
  await page.waitForSelector('.el-message', { timeout: 15000 })
  const wrongOldMessage = (await page.locator('.el-message').first().innerText()).trim()
  if (page.url().includes('#/login')) {
    throw new Error(`原密码填错竟被踢到登录页（应返回参数错误而不是 401）——toast：「${wrongOldMessage}」`)
  }
  if (!(await pwdDialog.isVisible())) {
    throw new Error(`原密码填错时弹窗被关掉了，用户得从头再填一遍——toast：「${wrongOldMessage}」`)
  }
  console.log(`⑤ 原密码填错 → 参数错误、留在原地（提示：「${wrongOldMessage}」）`)

  // ---------- ⑤ 改密成功：主动下线 + 旧 token 真的失效 ----------
  // 同样按锚点填，不按 DOM 顺序：第二次填错框（把旧密码填进确认框）会得到一个
  // 语义完全错误的结果——"改密成功"但新密码没生效
  await pwdDialog.locator('[data-testid="password-old"]').fill(OLD_PASSWORD)
  await pwdDialog.locator('[data-testid="password-new"]').fill(NEW_PASSWORD)
  await pwdDialog.locator('[data-testid="password-confirm"]').fill(NEW_PASSWORD)
  await pwdDialog.locator('[data-testid="password-submit"]').click()

  await page.waitForFunction(() => location.hash.startsWith('#/login'), { timeout: 15000 })
  console.log('⑥ 改密成功 → 已回到登录页（提示语说明"请用新密码重新登录"）')

  // 旧令牌必须已经失效。这一步**必须直接打接口**：界面上"回到登录页"是前端主动跳的，
  // 它证明不了服务端也认旧 token —— 而"改密码后旧 token 还能用"正是要防的那件事
  const staleResponse = await fetch(`${apiBase}/api/users/me`, {
    headers: { Authorization: `Bearer ${tokenBeforeChange}` }
  })
  if (staleResponse.status !== 401) {
    throw new Error(`改密后旧 token 仍然可用：HTTP ${staleResponse.status}（期望 401）`)
  }
  console.log('⑦ 用改密前的旧 token 打接口 → 401（令牌吊销真的生效了）')

  // ---------- ⑥ 新密码登录 ----------
  await page.fill('[data-testid="auth-username"]', username)
  await page.fill('[data-testid="auth-password"]', NEW_PASSWORD)
  await page.click('[data-testid="auth-submit"]')
  await page.waitForFunction(() => !location.hash.startsWith('#/login'), { timeout: 15000 })

  // 昵称是服务端的资料，换个会话也应当还在（它不是本地缓存）
  await page.waitForSelector('[data-testid="account-name"]', { timeout: 15000 })
  const nameAfterRelogin = (await page.locator('[data-testid="account-name"]').innerText()).trim()
  if (nameAfterRelogin !== NICKNAME) {
    throw new Error(`重新登录后昵称丢了：期望「${NICKNAME}」，实际「${nameAfterRelogin}」`)
  }
  console.log(`⑧ 用新密码登录成功，昵称仍在（${nameAfterRelogin}）`)
} catch (error) {
  failures.push(error.message)
  console.error('✗ 验证失败：', error.message)
  // 失败现场存进 docs/screenshots/，与其余 verify-* 脚本同一约定（也避免"从哪个目录运行
  // 就落在哪里"这种随 CWD 漂移的路径）
  await page.screenshot({ path: `${outDir}/security-flow-failure.png` }).catch(() => {})
} finally {
  await browser.close()
}

if (failures.length > 0) {
  console.error('失败项：' + failures.join('; '))
  process.exit(1)
}
console.log('\n用户安全体系 E2E 通过：资料编辑 → 改密码（错原密码不踢人 / 旧令牌立即失效）→ 新密码登录。')
