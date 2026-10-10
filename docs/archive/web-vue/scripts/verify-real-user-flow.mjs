/**
 * 真实用户流验收（Real User Flow Acceptance）。
 *
 * 与 verify-full-product.mjs 的区别：那个是**接口冒烟**（直接打 REST，验证后端链路），
 * 这个是**从浏览器出发的真实操作**——注册、空态、取消文件选择、侧栏收缩、退出重登。
 * 两者互补：接口全绿不代表用户界面正确（UA-001/002/003 都是纯前端缺陷，接口侧完全正常）。
 *
 * 覆盖的验收点（对应 docs/user-acceptance-issues.md）：
 *   UA-001  取消文件选择后按钮必须回到可点状态（不再永久 loading）
 *   UA-002  登录提示不得出现 undefined，localStorage 不得写入 "undefined"
 *   UA-003  新用户欢迎区不得出现开发者 Demo 问题
 *   UA-004  知识库列表必须有「导入资料」通用入口
 *   UA-007  对话页会话侧栏可收缩且记忆选择
 *
 * 用法：
 *   node web/scripts/verify-real-user-flow.mjs
 *   WEB_BASE=http://localhost:8088 node web/scripts/verify-real-user-flow.mjs
 */
import { chromium } from 'playwright-core'
import { writeFileSync, unlinkSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
/** 截图统一放仓库根的 docs/screenshots（相对路径会跟着 cwd 跑偏，见 verify-responsive.mjs 的注释） */
const outDir = resolve(here, '../../docs/screenshots')

const base = process.env.WEB_BASE ?? 'http://localhost:8088'
const stamp = Date.now()
const username = `ua-${stamp}`
const password = 'UaVerify@2026'
const kbName = `验收库-${stamp}`
/** 独有事实：编得毫不像常识，模型不可能"本来就知道"它 */
const UNIQUE_FACT = `NEXUS-FACT-UAX-${String(stamp).slice(-4)}`

/** 禁止出现在欢迎区的开发者 Demo 文案（UA-003 的原始字符串） */
const DEMO_STRINGS = ['缓存击穿', '线程池的核心参数', 'G1 和 ZGC', '问点什么？', 'undefined']

const results = []
function record(name, pass, detail = '') {
  results.push({ name, pass, detail })
  console.log(`${pass ? '✓' : '✗'} ${name}${detail ? ` — ${detail}` : ''}`)
}

const docPath = join(tmpdir(), `ua-verify-${stamp}.md`)
writeFileSync(
  docPath,
  `# 验收专用文档\n\n本文件只用于用户验收，含唯一标记 ${UNIQUE_FACT}。\n` +
    `该标记的含义是：这是一份由自动化验收脚本导入的测试资料。\n`
)

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 980 } })

/** 收集页面级 JS 错误：白屏类问题往往先在这里露头 */
const pageErrors = []
page.on('pageerror', (error) => pageErrors.push(error.message))

try {
  // ---------- ① 打开系统 → 注册新用户（UA-002 的前置） ----------
  await page.goto(base, { waitUntil: 'networkidle' })
  await page.waitForSelector('[data-testid="auth-username"]', { timeout: 20000 })
  await page.click('[data-testid="auth-switch-mode"]')
  await page.waitForSelector('[data-testid="auth-confirm-password"]', { timeout: 10000 })
  const pwdInputs = await page.locator('[data-testid="auth-password"], [data-testid="auth-confirm-password"]').count()
  record('注册模式出现"确认密码"输入框（UA-006）', pwdInputs >= 2, `password 输入框数 = ${pwdInputs}`)

  await page.fill('[data-testid="auth-username"]', username)
  await page.locator('[data-testid="auth-password"]').fill(password)
  await page.locator('[data-testid="auth-confirm-password"]').fill(password)

  /*
   * 累积所有浮层提示（UA-002）。
   *
   * 不能只抓"第一条 .el-message"：路由守卫的"请先登录"可能先于"欢迎回来"出现，
   * 抓到它就会把这条断言误判为失败。这里用 MutationObserver 把跳转前后出现的
   * 所有提示都收下来，再对整体做"不得含 undefined"的断言——那才是这条 bug 的真判据。
   */
  await page.evaluate(() => {
    window.__toasts = []
    new MutationObserver(() => {
      document.querySelectorAll('.el-message').forEach((el) => {
        const text = (el.innerText || '').trim()
        if (text && !window.__toasts.includes(text)) window.__toasts.push(text)
      })
    }).observe(document.body, { childList: true, subtree: true })
  })

  await page.click('[data-testid="auth-submit"]')
  await page.waitForFunction(() => !location.hash.startsWith('#/login'), { timeout: 20000 })
  await page.waitForTimeout(1200) // 给浮层一点时间出现并被收集
  const toasts = await page.evaluate(() => window.__toasts ?? [])
  record('注册后自动登录并跳转', true, `URL = ${page.url()}`)

  const dirtyToast = toasts.find((text) => text.includes('undefined'))
  record('登录提示不含 undefined（UA-002）', !dirtyToast, `捕获到的提示 = [${toasts.join(' / ')}]`)

  const storedName = await page.evaluate(() => localStorage.getItem('nexus.username'))
  record(
    'localStorage 未被写入 "undefined"（UA-002）',
    storedName !== 'undefined' && storedName !== null,
    `nexus.username = "${storedName}"`
  )

  // ---------- ② 新用户空态：知识库列表（UA-004） ----------
  await page.waitForSelector('[data-testid="kb-empty"], [data-testid="kb-card"]', { timeout: 20000 })
  const kbPageText = await page.locator('body').innerText()
  record('新用户知识库页为空态', kbPageText.includes('还没有知识库'))
  // 空态锚点必须真的在 DOM 里。上面那条断言读的是整页文本，而"处于空态"这件事
  // 在 Phase 0 之后有了自己的定位契约（kb-empty 与 kb-card 互斥）。
  // 本账号刚注册、还没有任何库，所以这里是这条契约**条件必然成立**的取证点
  // （verify-testid-dom.mjs 用有数据的 demo 账号，只能把它列为条件锚点）。
  const emptyAnchors = await page
    .locator('[data-testid="kb-empty"], [data-testid="kb-card"]')
    .evaluateAll((els) => els.map((el) => el.getAttribute('data-testid')))
  record(
    '空态锚点落位（kb-empty 在、kb-card 不在）',
    emptyAnchors.length === 1 && emptyAnchors[0] === 'kb-empty',
    `实际渲染 = ${JSON.stringify(emptyAnchors)}`
  )
  // 注意：空态下**不该**有「导入资料」按钮——资料必须归属某个知识库，
  // 所以断言放在创建完库之后再查（见下）

  // ---------- ③ 对话页新用户空态（UA-003，核心回归） ----------
  await page.click('[data-testid="nav-chat"]')
  await page.waitForSelector('.welcome, [data-testid="kb-empty"]', { timeout: 20000 })
  await page.waitForTimeout(800) // 等知识库/会话两个请求都回来再断言
  const chatText = await page.locator('body').innerText()
  const leaked = DEMO_STRINGS.filter((s) => chatText.includes(s))
  record(
    '新用户对话页无开发者 Demo 文案（UA-003 + UA-009）',
    leaked.length === 0,
    leaked.length ? `泄漏了：${leaked.join(' / ')}` : '干净'
  )
  record('新用户对话页显示"还没有知识库"引导', chatText.includes('还没有知识库'))

  // ---------- ④ 创建知识库 ----------
  await page.click('[data-testid="nav-knowledge"]')
  await page.waitForSelector('[data-testid="kb-empty"], [data-testid="kb-card"]', { timeout: 20000 })
  await page.click('[data-testid="kb-create-open"]')
  await page.waitForSelector('[data-testid="kb-create-name"]', { timeout: 10000 })
  await page.fill('[data-testid="kb-create-name"]', kbName)
  await page.click('[data-testid="kb-create-submit"]')
  await page.waitForSelector(`[data-testid="kb-card"][data-kb-name="${kbName}"]`, { timeout: 20000 })
  record('创建知识库', true, kbName)
  record(
    '知识库卡片提供通用「导入资料」入口（UA-004）',
    (await page.locator(`[data-testid="kb-card"][data-kb-name="${kbName}"] [data-testid="kb-import-files"]`).count()) > 0,
    '不再是只有「上传 Vault」'
  )

  // ---------- ⑤ 取消文件选择不得卡住（UA-001，本脚本最关键的一条） ----------
  let chooserSeen = false
  const cancelHandler = () => {
    chooserSeen = true
    // 刻意不调用 setFiles()：这就是"用户在系统弹窗里点了取消"
  }
  page.once('filechooser', cancelHandler)
  const importBtn = page.locator('[data-testid="kb-card"][data-kb-name="' + kbName + '"] [data-testid="kb-import-files"]')
  await importBtn.click()
  await page.waitForTimeout(2500)

  const stillLoading = await importBtn.evaluate((el) => el.classList.contains('is-loading'))
  record('取消文件选择后按钮未卡在 loading（UA-001）', chooserSeen && !stillLoading, `选择器弹出=${chooserSeen}，仍 loading=${stillLoading}`)

  // 取消后必须**还能继续用**：再点一次并真的上传，证明状态机复位了
  await page.waitForTimeout(500)

  // ---------- ⑥ 真实上传 Markdown（UA-004 的另一半：格式确实能吃） ----------
  page.once('filechooser', (chooser) => chooser.setFiles(docPath))
  await importBtn.click()
  await page.waitForTimeout(1500)
  let uploadOk = false
  try {
    await page.waitForFunction(
      (name) => {
        const cards = document.querySelectorAll('[data-testid="kb-card"]')
        for (const card of cards) {
          if (card.innerText.includes(name) && card.innerText.includes('已完成')) return true
        }
        return false
      },
      kbName,
      { timeout: 180000 }
    )
    uploadOk = true
  } catch {
    uploadOk = false
  }
  record('上传 Markdown 并处理完成', uploadOk, uploadOk ? '状态=已完成' : '等待 180s 未到"已完成"')

  // ---------- ⑥b 导入完成后必须**可检索**（UA-014 的核心回归点） ----------
  //
  // 这是本轮修复里最要紧的一条：此前"导入 DONE"只代表解析+分块完成，
  // 向量化与建索引是另一个没人告诉用户的步骤，于是文件显示"已完成"、检索却 0 命中。
  // 现在列表页会在导入终态后自动串接 workflow（向量化 + 建索引）。
  // 所以这里不能只断言"界面显示已完成"——必须断言**文件里的独有事实真的搜得到**。
  // 索引是异步的，故轮询到命中为止（最多 ~2 分钟）。
  const searchOnce = () =>
    page.evaluate(
      async ({ fact, name }) => {
        const token = localStorage.getItem('nexus.token')
        const headers = { Authorization: `Bearer ${token}` }
        const kbs = await (await fetch('/api/knowledge-bases?pageNum=1&pageSize=50', { headers })).json()
        const kb = (kbs?.data?.list ?? []).find((item) => item.name === name)
        if (!kb) return { error: '知识库未找到' }
        const res = await fetch(
          `/api/knowledge-bases/${kb.id}/search?q=${encodeURIComponent(fact)}&topK=5&mode=hybrid`,
          { headers }
        )
        const body = await res.json()
        const hits = body?.data ?? []
        return { count: hits.length, matched: JSON.stringify(hits).includes(fact) }
      },
      { fact: UNIQUE_FACT, name: kbName }
    )

  // 先回到知识库页，让列表页的自动索引流程有机会跑起来
  await page.click('[data-testid="nav-knowledge"]')
  await page.waitForTimeout(1500)

  let searchResult = await searchOnce()
  for (let i = 0; i < 60 && !searchResult.matched; i++) {
    await page.waitForTimeout(2000)
    searchResult = await searchOnce()
  }
  record(
    '导入后独有事实可检索（UA-014 回归）',
    searchResult.matched === true,
    searchResult.error ? searchResult.error : `命中 ${searchResult.count} 条，标记 ${UNIQUE_FACT}`
  )

  // ---------- ⑦ 侧栏收缩（UA-007） ----------
  await page.click('[data-testid="nav-chat"]')
  await page.waitForTimeout(1200)
  const rail = page.locator('[data-testid="chat-rail"]')
  const railVisibleBefore = await rail.isVisible().catch(() => false)
  await page.locator('[data-testid="chat-rail-toggle"]').click()
  await page.waitForTimeout(600)
  const railVisibleAfter = await rail.isVisible().catch(() => false)
  const stored = await page.evaluate(() => localStorage.getItem('nexus.chatRailCollapsed'))
  record(
    '对话页侧栏可收缩且记忆选择（UA-007）',
    railVisibleBefore && !railVisibleAfter && stored === '1',
    `收缩前可见=${railVisibleBefore}，收缩后可见=${railVisibleAfter}，记忆=${stored}`
  )
  // 恢复展开，避免影响后续人工查看
  await page.locator('[data-testid="chat-rail-toggle"]').click()
  await page.waitForTimeout(400)

  // ---------- ⑧ 退出 → 重新登录 ----------
  await page.click('[data-testid="logout"]')
  await page.waitForSelector('.nexus-confirm-logout', { timeout: 10000 })
  await page.click('.nexus-confirm-logout [data-testid="confirm-submit"]')
  await page.waitForSelector('[data-testid="auth-username"]', { timeout: 20000 })
  record('退出登录回到登录页', true)

  await page.fill('[data-testid="auth-username"]', username)
  await page.fill('input[type="password"]', password)
  await page.click('[data-testid="auth-submit"]')
  await page.waitForFunction(() => !location.hash.startsWith('#/login'), { timeout: 20000 })
  await page.waitForSelector('[data-testid="kb-card"]', { timeout: 20000 })
  record('重新登录并恢复数据', true, `URL = ${page.url()}`)

  // ---------- ⑨ 清理：删掉本次创建的测试知识库（不污染真实数据） ----------
  // 只按**本次生成的唯一库名**精确匹配删除，不做任何范围扩展。
  // 账号本身没有删除接口，靠唯一用户名（ua-<时间戳>）隔离，不会与既有账号冲突。
  const removed = await page.evaluate(async (name) => {
    const token = localStorage.getItem('nexus.token')
    const headers = { Authorization: `Bearer ${token}` }
    const res = await fetch('/api/knowledge-bases?pageNum=1&pageSize=50', { headers })
    const body = await res.json()
    const list = body?.data?.list ?? []
    let count = 0
    for (const kb of list) {
      if (kb.name === name) {
        await fetch(`/api/knowledge-bases/${kb.id}`, { method: 'DELETE', headers })
        count += 1
      }
    }
    return count
  }, kbName)
  record('清理测试知识库', removed > 0, `已删除 ${removed} 个`)

  record('全程无未捕获的前端异常', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '))
} catch (error) {
  record('脚本执行中断', false, error.message)
  await page.screenshot({ path: `${outDir}/real-user-flow-failure-${stamp}.png` }).catch(() => {})
} finally {
  await browser.close()
  try {
    unlinkSync(docPath)
  } catch {
    /* 临时文件清理失败不影响结论 */
  }
}

// ---------- 汇总 ----------
const failed = results.filter((r) => !r.pass)
console.log('\n' + '='.repeat(60))
console.log(`真实用户流验收：${results.length - failed.length} / ${results.length} 通过`)
if (failed.length) {
  console.log('未通过：')
  for (const item of failed) console.log(`  ✗ ${item.name}${item.detail ? ` — ${item.detail}` : ''}`)
}
console.log('='.repeat(60))
console.log(`\n测试账号 ${username} 与知识库「${kbName}」已创建（用户名唯一，不污染既有数据）。`)
console.log('如需清理，请在确认作用域后删除该账号名下的知识库。')

process.exit(failed.length ? 1 : 0)
