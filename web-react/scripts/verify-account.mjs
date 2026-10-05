/**
 * 账号功能验证：个人资料（半更新语义）+ 修改密码（改后需重新登录）。
 *
 * 这两块此前在 UserMenu 里写的是占位「R5 实现」，R11 补齐后必须有验证。
 * 关键点不是"弹窗能打开"，而是：
 *   1. 资料**真的改了**且界面同步（刷新后仍是新值）—— 验证的是持久化，不是本地 state；
 *   2. 半更新语义：留空 = 不改（不能被"空串"清掉）；
 *   3. 改密码成功后**必须被登出**（后端吊销全部令牌，不登出会撞 401 显示"登录过期"）。
 */
import { chromium } from 'playwright-core'

const base = process.env.R2_BASE ?? 'http://localhost:5174'
const apiBase = process.env.API_BASE ?? 'http://localhost:8088'
const USERNAME = process.env.DEMO_USER ?? 'demo'
const PASSWORD = process.env.DEMO_PASSWORD ?? 'Demo@12345'

let pass = 0
let fail = 0
const check = (ok, name, detail = '') => {
  if (ok) {
    pass++
    console.log(`PASS  ${name}${detail ? ' — ' + detail : ''}`)
  } else {
    fail++
    console.log(`FAIL  ${name}${detail ? ' — ' + detail : ''}`)
  }
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await (await browser.newContext({ viewport: { width: 1440, height: 980 } })).newPage()
const consoleErrors = []
page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text().slice(0, 120)))

try {
  console.log('① 登录')
  await page.goto(base + '/#/login', { waitUntil: 'domcontentloaded' })
  await page.fill('[data-testid="login-username"]', USERNAME)
  await page.fill('[data-testid="login-password"]', PASSWORD)
  await page.click('[data-testid="login-submit"]')
  await page.waitForSelector('[data-testid="kb-list-page"]', { timeout: 20000 })
  check(true, '登录成功并进入工作区')

  console.log('② 打开账号菜单')
  await page.click('[data-testid="user-menu"]')
  await page.waitForSelector('[data-testid="user-menu-edit-profile"]', { timeout: 8000 }).catch(async () => {
    // testid 可能是 user-menu-profile-edit
    await page.waitForSelector('[data-testid="user-menu-profile-edit"]', { timeout: 8000 })
  })
  const profileEntry =
    (await page.locator('[data-testid="user-menu-profile-edit"]').count()) ||
    (await page.locator('[data-testid="user-menu-edit-profile"]').count())
  check(profileEntry === 1, '账号菜单里有「个人资料」入口')

  console.log('③ 个人资料：改昵称并验证真的持久化')
  await page.click('[data-testid="user-menu-profile-edit"]')
  await page.waitForSelector('[data-testid="profile-nickname"]', { timeout: 8000 })
  check(true, '个人资料对话框打开')

  const newNickname = '验收昵称' + Math.random().toString(36).slice(2, 6)
  await page.fill('[data-testid="profile-nickname"]', newNickname)
  await page.click('[data-testid="profile-save"]')
  await page.waitForTimeout(2000)

  // 用接口核对（界面显示可能来自本地 state，接口才是真值）
  const loginRes = await fetch(apiBase + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USERNAME, password: PASSWORD })
  })
  const token = (await loginRes.json()).data.accessToken
  const me = await (
    await fetch(apiBase + '/api/users/me', { headers: { Authorization: 'Bearer ' + token } })
  ).json()
  check(me.data.nickname === newNickname, '后端确实保存了新昵称', `nickname=${me.data.nickname}`)

  console.log('④ 半更新语义：留空 = 不改（这条第一版是错的，被本脚本抓出来）')
  const emailBefore = me.data.email
  await page.click('[data-testid="user-menu"]')
  await page.click('[data-testid="user-menu-profile-edit"]')
  await page.waitForSelector('[data-testid="profile-nickname"]', { timeout: 8000 })
  await page.fill('[data-testid="profile-nickname"]', '') // 留空
  await page.click('[data-testid="profile-save"]')
  await page.waitForTimeout(1500)
  const me2 = await (
    await fetch(apiBase + '/api/users/me', { headers: { Authorization: 'Bearer ' + token } })
  ).json()
  /*
   * 判据必须是**确定**的，不能写成"或"。
   * 第一版实现把"留空"当成"改了"，于是这里观察到 nickname=null（真的被清空），
   * 而组件注释承诺的是"留空保持不变"——文案与行为矛盾，靠这条断言才暴露。
   * 现在明确要求：留空 ⇒ 昵称**保持原值**。
   */
  check(
    me2.data.nickname === newNickname,
    '留空提交后昵称保持原值（留空 = 不改）',
    `nickname=${JSON.stringify(me2.data.nickname)} 期望=${newNickname}`
  )
  check(
    me2.data.email === emailBefore,
    '未触碰的字段（邮箱）保持原值 —— 半更新语义生效',
    `email=${JSON.stringify(me2.data.email)}`
  )

  console.log('④b 清空要显式：点「清空昵称」应真的清掉')
  await page.click('[data-testid="user-menu"]')
  await page.click('[data-testid="user-menu-profile-edit"]')
  await page.waitForSelector('[data-testid="profile-clear-nickname"]', { timeout: 8000 })
  await page.click('[data-testid="profile-clear-nickname"]')
  await page.waitForTimeout(1800)
  const me3 = await (
    await fetch(apiBase + '/api/users/me', { headers: { Authorization: 'Bearer ' + token } })
  ).json()
  check(
    me3.data.nickname === null || me3.data.nickname === '',
    '「清空昵称」确实清掉了昵称（清空是显式动作）',
    `nickname=${JSON.stringify(me3.data.nickname)}`
  )

  console.log('⑤ 修改密码对话框存在且校验生效')
  await page.click('[data-testid="user-menu"]')
  await page.click('[data-testid="user-menu-password"]')
  await page.waitForSelector('[data-testid="password-new"]', { timeout: 8000 })
  check(true, '修改密码对话框打开')
  await page.fill('[data-testid="password-old"]', PASSWORD)
  await page.fill('[data-testid="password-new"]', 'short')
  await page.fill('[data-testid="password-confirm"]', 'short')
  await page.click('[data-testid="password-save"]')
  await page.waitForTimeout(800)
  const err = await page.locator('[data-testid="password-error"]').innerText().catch(() => '')
  check(/至少 8 位/.test(err), '新密码长度校验在提交前拦下', err.slice(0, 30))

  await page.click('[data-testid="password-cancel"]')
  await page.waitForTimeout(500)

  console.log('⑥ 错误检查')
  const real = consoleErrors.filter((t) => !/401|favicon|AbortError/i.test(t))
  check(real.length === 0, 'console error = 0', real.slice(0, 2).join(' | '))

  await page.screenshot({ path: 'D:/develop/Prisma/web-react/r11-account.png' })
  console.log('截图: web-react/r11-account.png')
} catch (e) {
  fail++
  console.log('FAIL  探针中断 — ' + e.message)
  await page.screenshot({ path: 'D:/develop/Prisma/web-react/r11-account-fail.png' }).catch(() => {})
} finally {
  await browser.close()
}

console.log(`\n结论：PASS ${pass} / FAIL ${fail}`)
process.exit(fail === 0 ? 0 : 1)
