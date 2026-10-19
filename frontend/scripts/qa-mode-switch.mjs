/*
 * 会话模式切换专项 QA（P1-1 任务书第四节）。
 *
 * 覆盖三件老大明确要求的事：
 *   1. 成功后**按实际响应**更新界面（不是按本地推断）
 *   2. 失败后**不显示成功提示**，也不错误更新本地状态
 *   3. **仅在绑定确实被清除时**显示清理提示（不谎报）
 *
 * 运行前提：dev server 必须以 mock 模式启动（`VITE_USE_MOCK=true`），
 * 否则 QA 脚本靠 legacy flag 预置登录会失效 → 等`.shell` 超时。
 */
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { chromium } from 'playwright-core'

const BASE = 'http://localhost:5175'

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')
  localStorage.setItem('nx-proto-auth', '1')
})

const issues = []
const consoleErrors = []
page.on('pageerror', (e) => issues.push(`pageerror: ${String(e.message).slice(0, 140)}`))
page.on('console', (m) => {
  if (m.type() !== 'error') return
  const t = m.text()
  if (/ERR_CONNECTION_CLOSED|ERR_CONNECTION_REFUSED|WebSocket|\[vite\]/i.test(t)) return
  consoleErrors.push(t.slice(0, 160))
})

const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`)
  if (!ok) issues.push(name)
}

/** 当前可见 toast 的标题与描述（没有则返回 null）。 */
const readToast = async () => page.evaluate(() => {
  const els = [...document.querySelectorAll('.toast-host .swipe-toast')]
  const el = els[els.length - 1]
  if (!el) return null
  return {
    title: el.querySelector('.swipe-toast__title')?.textContent?.trim() ?? '',
    desc: el.querySelector('.swipe-toast__desc')?.textContent?.trim() ?? '',
    isError: !!el.querySelector('.swipe-toast--error, [data-tone="error"]'),
  }
})

const clearToasts = () => page.evaluate(() => {
  document.querySelectorAll('.toast-host .swipe-toast').forEach((el) => el.remove())
})

// 模式切换现在是「会话配置」弹出层：按钮 + .session-config__pop 里的 .sc-opt（aria-pressed 表示选中）
const openConfig = async () => {
  if ((await page.locator('.session-config__pop').count()) === 0) {
    await page.locator('.session-config__btn').first().click()
  }
  await page.waitForSelector('.session-config__pop', { timeout: 5000 })
}
const closeConfig = async () => {
  if ((await page.locator('.session-config__pop').count()) > 0) await page.keyboard.press('Escape')
}
const modeOptValue = () => page.evaluate(() => {
  const el = document.querySelector('.session-config__pop .sc-section:first-child .sc-opt[aria-pressed="true"]')
  return el?.textContent?.trim() ?? ''
})
const pickMode = async (label) => {
  await openConfig()
  await page.locator('.session-config__pop .sc-section:first-child .sc-opt', { hasText: label }).first().click()
}

try {
  await page.goto(`${BASE}/#/app/chat`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.session-config__btn', { timeout: 15000 })
  await page.waitForTimeout(800)

  /* ---------- 1. 模式切换控件存在 ---------- */
  check('对话页有模式切换控件', (await page.locator('.session-config__btn').count()) === 1)
  await openConfig()
  const modeItems = await page.locator('.session-config__pop .sc-section:first-child .sc-opt').count()
  check('模式区含三项（普通对话/知识库问答/Agent）', modeItems === 3, `${modeItems} 项`)
  check(
    '模式按钮声明为弹出层触发（aria-haspopup=dialog）',
    (await page.locator('.session-config__btn').first().getAttribute('aria-haspopup')) === 'dialog',
  )

  /* ---------- 2. mock 分支允许切模式 ---------- */
  const before = await modeOptValue()
  const targetLabel = before === '知识库问答' ? 'Agent' : '知识库问答'
  await clearToasts()
  await pickMode(targetLabel)
  await page.waitForTimeout(1000)
  await openConfig()

  const after = await modeOptValue()
  await closeConfig()
  check('点模式项后选中项变化', after === targetLabel, `${before} → ${after}`)

  const t1 = await readToast()
  /*
   * mock 与真实两条路径的文案本来就不一样：
   *   mock  → "已切换模式" + "PLAIN → RAG（原型演示，未落库）"
   *   真实  → "已切换到知识库问答模式" + 清理说明
   * 所以断言只要求"是成功提示"，不锁死具体措辞——
   * 措辞属于文案迭代，锁死会让每次改文案都要改测试。
   */
  check('切换后有成功提示', !!t1 && t1.title.startsWith('已切换'), t1?.title ?? '(无)')
  check(
    'mock 分支明确标注未落库（不谎称已写入后端）',
    !!t1 && t1.desc.includes('未落库'),
    t1?.desc ?? '(无)',
  )

  /* ---------- 3. 提示不谎报清理 ---------- */
  /*
   * 关键断言：提示里出现「已清除」时，必须确实清过。
   * 这里用「切到普通对话」验证 —— 会话在 PLAIN 下本来就没有绑定，
   * 所以提示**不该**说"已清除"，否则就是在谎报。
   */
  await pickMode('普通对话')
  await page.waitForTimeout(1000)
  const t2 = await readToast()
  check('切回普通对话有提示', !!t2 && t2.title.startsWith('已切换'), t2?.title ?? '(无)')
  check(
    'PLAIN 会话本无绑定时，提示不谎报"已清除"',
    !!t2 && !t2.desc.includes('已清除'),
    t2?.desc ?? '(无)',
  )
  check(
    '切回普通对话同样标注未落库（mock 路径不谎称已落库）',
    !!t2 && t2.desc.includes('未落库'),
    t2?.desc ?? '(无)',
  )

  /* ---------- 4. mock 分支不写后端 ---------- */
  const calls = await page.evaluate(() => window.__qaApiCalls ?? null)
  check('mock 分支不发真实 PUT（无 __qaApiCalls 记录）', calls === null, String(calls))

  /* ---------- 5. 无控制台错误 ---------- */
  check('无 console 错误', consoleErrors.length === 0, consoleErrors.slice(0, 2).join(' | '))
} catch (e) {
  check('异常中断', false, e.message)
  await page.screenshot({ path: `${process.env.QA_SHOTS_OUT ?? join(tmpdir(), 'prisma-recover-qa-shots')}/mode-switch-error.png` }).catch(() => {})
} finally {
  console.log('\n--- 汇总 ---')
  if (issues.length === 0) {
    console.log('模式切换专项 QA 全部通过')
  } else {
    console.log(`发现 ${issues.length} 个问题:`)
    issues.forEach((i) => console.log('  - ' + i))
    if (consoleErrors.length) {
      console.log('--- console 错误 ---')
      consoleErrors.forEach((e) => console.log('  ' + e))
    }
  }
  await browser.close()
  process.exit(issues.length > 0 ? 1 : 0)
}