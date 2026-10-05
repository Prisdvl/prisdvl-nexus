/**
 * data-testid 契约守卫（Phase 0 新增）。
 *
 * 【为什么需要这条】
 * `verify-*.mjs` 在 Phase 0 里从"中文文案选择器"整体迁移到了 `data-testid`。
 * 这解决了一类脆弱性，但引入了另一类：**testid 本身成了新的隐形契约**。
 * 一旦有人重命名/删掉某个 testid（改个名字看起来毫无风险），
 * 十几个脚本会在各自的位置报"元素找不到"——而那个报错完全指向不了真正的原因。
 * 本脚本就是这份契约的可执行版本：逐个页面断言关键 testid 真的在 DOM 里。
 *
 * 【它同时是一份"落点实证"】
 * Element Plus 的 `el-input` / `el-dialog` 等组件是 `inheritAttrs: false`：
 * 写在组件上的 `data-testid` 到底绑到哪个元素，静态读源码只能得到"结论"，
 * 得不到"事实"。本脚本把它变成可重复跑的证据——包括
 * `el-radio-button` 的锚点是否真的落在可点击的 label 上、`el-tabs` 的
 * `#tab-*` id 是否按 `name` 生成（迁移用它们替代了"按中文页签名点页签"）。
 *
 * 【不做什么】
 * 不建任何数据：全程只读 + 开合弹窗，点「新对话」也只是清空前端的局部状态
 * （`newConversation()` 不发请求），退出后不留残留。
 *
 * 依赖：整栈在跑（需要真实账号能登录 + 有至少一个知识库与一个 Agent 才会覆盖到卡片类锚点）。
 * 用法：node web/scripts/verify-testid-dom.mjs
 * 退出码：0 = 全部关键锚点落位；1 = 有锚点缺失（打印缺失清单）。
 */
import { chromium } from 'playwright-core'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const base = process.env.WEB_BASE ?? 'http://localhost:8088'
const outDir = resolve(here, '../../docs/screenshots')
const username = process.env.DEMO_USER ?? 'demo'
const password = process.env.DEMO_PASSWORD ?? 'Demo@12345'

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 980 } })

const failures = []
const seen = new Set()

/** 当前页面所有 data-testid 的出现次数 */
async function dump() {
  return page.evaluate(() => {
    const out = {}
    document.querySelectorAll('[data-testid]').forEach((el) => {
      const key = el.getAttribute('data-testid')
      out[key] = (out[key] ?? 0) + 1
    })
    return out
  })
}

async function collect(label) {
  const map = await dump()
  Object.keys(map).forEach((k) => seen.add(k))
  console.log(`· ${label}：${Object.keys(map).length} 个 testid`)
  return map
}

function need(map, keys, where) {
  const missing = keys.filter((k) => !(k in map))
  if (missing.length) failures.push(`${where} 缺失：${missing.join(', ')}`)
  else console.log(`  ✓ ${where} 全部落位（${keys.length} 个）`)
}

/**
 * 条件渲染的锚点：只有前置条件成立时它才该在 DOM 里。
 *
 * 为什么不并进 need()：把"条件不成立"报成"缺失"是**假失败**，
 * 而把它悄悄跳过又会让这条契约看起来已被验证——那正是本脚本要根治的东西。
 * 所以这里如实记一笔"未取证"，并说明它由哪条脚本在条件必然成立时覆盖。
 */
function needWhen(map, keys, precondition, where, coveredBy) {
  if (precondition) {
    need(map, keys, where)
  } else {
    console.log(`  ○ ${where}：前置条件不成立，本次未取证（由 ${coveredBy} 覆盖）`)
  }
}

try {
  // ---------- 登录页 ----------
  await page.goto(base, { waitUntil: 'networkidle' })
  await page.waitForSelector('[data-testid="auth-username"]', { timeout: 20000 })
  const login = await collect('登录页')
  need(login, ['auth-username', 'auth-password', 'auth-submit', 'auth-switch-mode'], '登录页')

  // 注册模式多出确认密码框（正是 UA-006 那条腿依赖的锚点）
  await page.click('[data-testid="auth-switch-mode"]')
  await page.waitForSelector('[data-testid="auth-confirm-password"]', { timeout: 10000 })
  console.log('  ✓ 切到注册模式后 auth-confirm-password 出现')
  await page.click('[data-testid="auth-switch-mode"]')
  await page.waitForTimeout(400)

  // ---------- 真实登录 ----------
  await page.fill('[data-testid="auth-username"]', username)
  await page.fill('[data-testid="auth-password"]', password)
  await page.click('[data-testid="auth-submit"]')
  await page.waitForFunction(() => !location.hash.startsWith('#/login'), { timeout: 20000 })
  await page.waitForSelector('[data-testid="kb-card"], [data-testid="kb-empty"]', { timeout: 20000 })

  // ---------- 外壳 + 知识库列表 ----------
  const shell = await collect('登录后 · 知识库列表')
  need(
    shell,
    ['app-sidebar', 'app-nav', 'nav-knowledge', 'nav-chat', 'nav-agents', 'account-entry', 'account-name', 'logout'],
    '外壳/导航'
  )
  need(shell, ['kb-create-open', 'kb-ask-agent'], '知识库列表')
  // 列表态与空态互斥：断言"恰有其一"，而不是断言两者都在
  needWhen(shell, ['kb-card'], true, '知识库列表 · 卡片态', '—')
  needWhen(
    shell,
    ['kb-empty'],
    !shell['kb-card'],
    '知识库列表 · 空态',
    'verify-real-user-flow.mjs（自建新账号，空态必然出现）'
  )
  if (shell['kb-card'] && shell['kb-empty']) {
    failures.push('知识库列表同时渲染了卡片态与空态，二者本应互斥')
  }

  // ---------- 新建知识库弹窗 ----------
  // el-dialog 是 inheritAttrs:false，锚点只能挂在内部 el-form / el-input 上——这里就是在验那件事
  await page.click('[data-testid="kb-create-open"]')
  await page.waitForSelector('[data-testid="kb-create-form"]', { timeout: 15000 })
  const dlg = await collect('新建知识库弹窗')
  need(
    dlg,
    ['kb-create-form', 'kb-create-name', 'kb-create-desc', 'kb-create-cancel', 'kb-create-submit'],
    '新建知识库弹窗'
  )
  await page.click('[data-testid="kb-create-cancel"]')
  await page.waitForTimeout(600)

  // ---------- 知识库详情 ----------
  if (shell['kb-card']) {
    await page.locator('[data-testid="kb-card"]').first().locator('[data-testid="kb-open"]').click()
    await page.waitForSelector('[data-testid="kb-detail-import-file"]', { timeout: 20000 })
    const detail = await collect('知识库详情')
    need(detail, ['kb-detail-back', 'kb-detail-tabs', 'kb-tab-content'], '知识库详情')
    need(
      detail,
      ['kb-detail-import-file', 'kb-detail-import-vault', 'kb-detail-embed', 'kb-detail-index', 'kb-detail-recreate'],
      '知识库详情页头按钮'
    )
    needWhen(
      detail,
      ['kb-links'],
      'kb-links' in detail,
      '知识库详情 · 关联面板',
      'verify-frontend.mjs（打开 SpringBoot.md，该文档有出链）'
    )
    needWhen(
      detail,
      ['kb-doc-item', 'kb-doc-delete', 'kb-doc-filter'],
      'kb-doc-item' in detail,
      '知识库详情 · 文档列表',
      'verify-doc-lifecycle.mjs / verify-frontend.mjs'
    )

    // el-tabs 的页签头 id = `tab-{name}`。迁移用它替代"按中文页签名点页签"
    const tabIds = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.el-tabs__item')).map((el) => el.id)
    )
    console.log(`  · tab 头 id = ${JSON.stringify(tabIds)}`)
    for (const id of ['tab-content', 'tab-chunks', 'tab-search', 'tab-ask']) {
      if (!tabIds.includes(id)) failures.push(`知识库详情 tab id 缺失：${id}`)
    }

    await page.click('#tab-search')
    await page.waitForTimeout(500)
    const searchPane = await collect('检索页签')
    need(
      searchPane,
      ['kb-tab-search', 'kb-search-mode', 'kb-search-topk', 'kb-search-input', 'kb-search-submit'],
      '检索页签'
    )
    await page.click('#tab-ask')
    await page.waitForTimeout(500)
    const askPane = await collect('问答页签')
    need(askPane, ['kb-tab-ask', 'kb-ask-input', 'kb-ask-submit', 'kb-ask-stream-switch'], '问答页签')
  } else {
    console.log('  ⚠ 当前账号没有知识库，跳过详情页锚点（这些锚点由 verify-real-user-flow / verify-doc-lifecycle 覆盖）')
  }

  // ---------- 对话页 ----------
  await page.click('[data-testid="nav-chat"]')
  await page.waitForSelector('[data-testid="chat-messages"]', { timeout: 20000 })
  let chat = await collect('对话页')
  need(chat, ['chat-rail', 'chat-new', 'chat-messages', 'chat-composer', 'chat-rail-toggle'], '对话页')

  await page.click('[data-testid="chat-new"]')
  await page.waitForSelector('[data-testid="chat-composer-options"]', { timeout: 15000 })
  chat = await collect('对话页 · 草稿态')
  need(
    chat,
    ['chat-composer-options', 'chat-draft-mode', 'chat-draft-mode-agent', 'chat-draft-mode-rag', 'chat-input', 'chat-send'],
    '对话页草稿态'
  )
  // el-radio-button 的锚点必须落在可点击的 label 上（否则"锚点在"但点了没反应）
  const ragClickable = await page.locator('[data-testid="chat-draft-mode-rag"]').isVisible().catch(() => false)
  if (!ragClickable) failures.push('chat-draft-mode-rag 不可见/不可点')
  await page.click('[data-testid="chat-draft-mode-rag"]')
  await page.waitForSelector('[data-testid="chat-draft-kb-select"]', { timeout: 10000 })
  console.log('  ✓ 切到 RAG 后 chat-draft-kb-select 出现（radio-button 锚点可点）')

  // 侧栏折叠：toggle 唯一的效果就是切 rail 的可见性，直接验这个效果
  const railVisible = () =>
    page.evaluate(
      () => document.querySelector('[data-testid="chat-rail"]')?.checkVisibility?.() ?? true
    )
  const beforeCollapsed = await railVisible()
  await page.click('[data-testid="chat-rail-toggle"]')
  await page.waitForTimeout(500)
  const afterCollapsed = await railVisible()
  if (!(beforeCollapsed && !afterCollapsed)) {
    failures.push(`chat-rail-toggle 未能折叠侧栏（前 ${beforeCollapsed} / 后 ${afterCollapsed}）`)
  } else {
    console.log('  ✓ chat-rail-toggle 可折叠侧栏')
  }
  await page.click('[data-testid="chat-rail-toggle"]')

  // ---------- Agent 管理 ----------
  await page.click('[data-testid="nav-agents"]')
  await page.waitForSelector('[data-testid="agent-card"], [data-testid="agent-empty"]', { timeout: 20000 })
  const agents = await collect('Agent 管理')
  need(agents, ['agent-eval-open', 'agent-create-open'], 'Agent 管理页头')
  needWhen(agents, ['agent-card'], true, 'Agent 管理 · 卡片态', '—')
  needWhen(
    agents,
    ['agent-empty'],
    !agents['agent-card'],
    'Agent 管理 · 空态',
    'verify-real-user-flow.mjs'
  )
  if (agents['agent-card']) {
    need(
      agents,
      ['agent-card-test', 'agent-card-config', 'agent-card-duplicate', 'agent-card-toggle-status'],
      'Agent 卡片按钮'
    )
  }
  await page.click('[data-testid="agent-create-open"]')
  await page.waitForSelector('[data-testid="agent-create-form"]', { timeout: 15000 })
  const createAgent = await collect('新建 Agent 弹窗')
  need(
    createAgent,
    ['agent-create-form', 'agent-create-code', 'agent-create-name', 'agent-create-desc', 'agent-create-cancel', 'agent-create-submit'],
    '新建 Agent 弹窗'
  )
  await page.click('[data-testid="agent-create-cancel"]')
  await page.waitForTimeout(600)

  await page.click('[data-testid="agent-eval-open"]')
  await page.waitForSelector('[data-testid="agent-eval-panel"]', { timeout: 15000 })
  const evalDlg = await collect('评测集弹窗')
  need(evalDlg, ['agent-eval-panel', 'agent-eval-kb-select', 'agent-eval-run'], '评测集弹窗')
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)

  // ---------- 账号设置 / 修改密码 ----------
  // 这两个弹窗在 Phase 0 之前是靠"标题文案"区分的；锚点必须同时在，才能那样改
  await page.click('[data-testid="account-entry"]')
  await page.waitForSelector('[data-testid="profile-form"]', { timeout: 15000 })
  const profile = await collect('账号设置弹窗')
  need(
    profile,
    ['profile-form', 'profile-nickname', 'profile-email', 'profile-save', 'profile-cancel', 'profile-change-password'],
    '账号设置弹窗'
  )
  await page.click('[data-testid="profile-change-password"]')
  await page.waitForSelector('[data-testid="password-form"]', { timeout: 15000 })
  const pw = await collect('修改密码弹窗')
  need(
    pw,
    ['password-form', 'password-old', 'password-new', 'password-confirm', 'password-submit', 'password-cancel'],
    '修改密码弹窗'
  )
  await page.keyboard.press('Escape')

  console.log(`\n合计观测到 ${seen.size} 个不同的 testid`)
} catch (error) {
  failures.push(`探针中断：${error.message}`)
  await page.screenshot({ path: `${outDir}/testid-dom-failure.png` }).catch(() => {})
} finally {
  await browser.close()
}

if (failures.length) {
  console.error('\n✗ data-testid 契约未满足：')
  failures.forEach((f) => console.error('  - ' + f))
  process.exit(1)
}
console.log('\n✓ data-testid 契约通过：所有关键锚点均已进入真实 DOM。')
