/**
 * Agent 管理页的界面验证（Phase 4.2）：真实驱动浏览器，走一遍 Agent 的生命周期。
 *
 * 为什么这层不能省：后端契约测试（AgentResourceTest）证明的是"接口的准入规则对不对"，
 * 而这一页要回答的是**用户能不能把流程走通**——例如"草稿为什么不能选用"在界面上有没有说清楚、
 * "不绑知识库 = 全部知识库"这句反直觉的语义有没有写在用户看得见的地方、
 * 试跑失败时用户能不能看到失败原因而不是一个转圈的按钮。这些只有浏览器能证明。
 *
 * 覆盖的生命周期：新建（草稿）→ 配置（提示词 + 绑知识库）→ 发布 → 在对话页可选用 → 停用
 *                 → 对话页不再列出它 → 删除。
 *
 * 退出码：0 = 全部通过；1 = 有真实失败；2 = 有跳过（本机没有可用模型，试跑跑不了，见下）。
 */
import { chromium } from 'playwright-core'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const token = readFileSync(resolve(here, '../.demo-token'), 'utf8').trim()
const base = process.env.WEB_BASE ?? 'http://localhost:5173'
const outDir = resolve(here, '../../docs/screenshots')
/** 演示知识库名（scripts/seed-demo.mjs 建的那个） */
const demoKb = process.env.DEMO_KB ?? '我的笔记'

// 每次跑用新的标识：跑完会删掉，但万一中途失败也不会与上一轮的残留撞名（code 唯一）
const stamp = Date.now().toString(36)
const agentCode = `ui-check-${stamp}`
const agentName = `界面验证 Agent ${stamp}`
const copyCode = `${agentCode}-copy`
const prompt = '只依据检索到的资料回答；资料里没有的内容就直说没有，不要用你自己的知识补充。'

const failures = []
const skipped = []

/** 该 Agent 的卡片（按 code 精确定位：`x` 与 `x-copy` 不能互相匹配） */
function cardByCode(code) {
  return page.locator('.grid .card').filter({
    has: page.locator('.code').getByText(code, { exact: true })
  })
}

/** 当前可见的弹窗（页面上同时存在多个 el-dialog，必须只挑可见的那个） */
function dialog(title) {
  return page.locator('.el-dialog:visible').filter({ hasText: title })
}

/** 点确认框里的主按钮（Element Plus 的 MessageBox 是独立挂载的） */
async function confirmBox() {
  await page.click('.el-message-box__btns .el-button--primary')
}

async function deleteAgentCard(code) {
  await cardByCode(code).locator('.danger').click()
  await confirmBox()
  await cardByCode(code).waitFor({ state: 'detached', timeout: 15000 })
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 980 } })
await context.addInitScript((value) => {
  localStorage.setItem('nexus.token', value)
  localStorage.setItem('nexus.username', 'demo')
}, token)

const page = await context.newPage()

try {
  // ---------- ① 管理页：内置默认 Agent 必须看得见 ----------
  await page.goto(base, { waitUntil: 'networkidle' })
  await page.click('.nav-item:has-text("Agent 管理")')
  await page.waitForSelector('.grid .card', { timeout: 15000 })
  const builtinCard = page.locator('.grid .card').filter({ hasText: '内置' }).first()
  const builtinText = await builtinCard.innerText()
  console.log(`① 管理页打开，内置项：${builtinText.split('\n').filter(Boolean).slice(0, 3).join(' / ')}`)
  if (!builtinText.includes('内置默认 Agent')) {
    failures.push('管理页没有显示内置默认 Agent（新用户列表为空？）')
  }
  if (!builtinText.includes('全部知识库')) {
    // 这条不是文案洁癖：kbIds 为空 = 不限制，与 RAG 会话"必须绑一个库"正好相反，
    // 界面上不写出来，用户会以为"没绑库所以搜不到"
    failures.push('内置项的检索范围没有显示"全部知识库"（反直觉语义必须写出来）')
  }

  // ---------- ② 新建：落在草稿 ----------
  await page.click('.page-head .el-button--primary')
  const createDialog = dialog('新建 Agent')
  await createDialog.locator('input[placeholder="例如 note-helper"]').fill(agentCode)
  await createDialog.locator('input[placeholder="例如 笔记助手"]').fill(agentName)
  await createDialog.locator('.el-dialog__footer .el-button--primary').click()

  const card = cardByCode(agentCode)
  await card.waitFor({ timeout: 15000 })
  const createdText = await card.innerText()
  console.log(`② 已创建「${agentName}」`)
  if (!createdText.includes('草稿')) {
    failures.push('新建的 Agent 没有落在草稿态（"先测试再发布"的第一步就错了）')
  }
  // 草稿的操作按钮必须是"发布"，不能是"停用"：草稿本来就没启用，
  // 给它一个停用按钮说明状态机的按钮映射错了（用户会以为它已经生效）
  const publishable = await card.locator('.el-button:has-text("发布")').count()
  const stoppable = await card.locator('.el-button:has-text("停用")').count()
  if (publishable !== 1 || stoppable !== 0) {
    failures.push(`草稿的按钮映射不正确（发布 ×${publishable}、停用 ×${stoppable}）`)
  }
  await page.screenshot({ path: `${outDir}/agents-list.png` })
  console.log('✓ 已截图 docs/screenshots/agents-list.png')

  // ---------- ③ 配置：提示词 + 绑定知识库 ----------
  await card.locator('.el-button:has-text("配置")').click()
  const editDialog = dialog('配置「')
  await editDialog.locator('.el-textarea__inner').first().fill(prompt)
  // 绑一个库：多选下拉的选项挂在 body 上的 popper 里，不在弹窗 DOM 内
  await editDialog.locator('.kb-multi').click()
  const option = page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter({ hasText: demoKb }).first()
  await option.waitFor({ timeout: 15000 })
  await option.click()
  await page.keyboard.press('Escape')
  await editDialog.locator('.el-dialog__footer .el-button--primary').click()
  await page.waitForSelector('.el-dialog:visible', { state: 'detached', timeout: 15000 })

  const configuredText = await cardByCode(agentCode).innerText()
  console.log(`③ 已配置提示词与知识库，卡片范围：${configuredText.includes(demoKb) ? demoKb : '(未生效)'}`)
  if (!configuredText.includes(demoKb)) {
    failures.push(`绑定的知识库没有显示出来（期望出现「${demoKb}」）`)
  }
  if (configuredText.includes('全部知识库')) {
    failures.push('绑定知识库后仍显示"全部知识库"（界面没跟着更新？）')
  }
  if (!configuredText.includes('只依据检索到的资料回答')) {
    failures.push('提示词没有回显在卡片上')
  }

  // ---------- ④ 复制：副本必须是草稿 ----------
  await cardByCode(agentCode).locator('.el-button:has-text("复制")').click()
  await cardByCode(copyCode).waitFor({ timeout: 15000 })
  const copyText = await cardByCode(copyCode).innerText()
  console.log('④ 已复制出副本')
  if (!copyText.includes('草稿')) {
    failures.push('复制的 Agent 不是草稿（没测过就生效）')
  }

  // ---------- ⑤ 发布：状态可变，且"未发布的不能选用"要在对话页说清楚 ----------
  await cardByCode(agentCode).locator('.el-button:has-text("发布")').click()
  await page.waitForFunction(
    (code) => {
      const cards = Array.from(document.querySelectorAll('.grid .card'))
      const target = cards.find((c) => c.querySelector('.code')?.textContent.trim() === code)
      return target?.textContent.includes('已发布') ?? false
    },
    agentCode,
    { timeout: 15000 }
  )
  console.log('⑤ 已发布')

  // 对话页的选择器：已发布的出现、草稿的不出现（并给出解释）
  await page.click('.nav-item:has-text("对话")')
  await page.click('.rail .new-chat')
  await page.waitForSelector('.composer-options', { timeout: 15000 })
  await page.click('.composer-options .kb-select')
  const options = page.locator('.el-select-dropdown:visible .el-select-dropdown__item')
  await options.first().waitFor({ timeout: 15000 })
  const optionTexts = (await options.allInnerTexts()).map((text) => text.trim())
  console.log(`⑥ 对话页可选 Agent：${optionTexts.join(' / ')}`)
  if (!optionTexts.includes(agentName)) {
    failures.push('已发布的 Agent 没有出现在对话页的 Agent 选择器里')
  }
  if (!optionTexts.some((text) => text.includes('内置默认'))) {
    failures.push('Agent 选择器里没有"内置默认 Agent"这一项（没选 Agent 的会话就没法解释）')
  }
  await page.screenshot({ path: `${outDir}/agent-picker.png` })
  console.log('✓ 已截图 docs/screenshots/agent-picker.png')
  await page.keyboard.press('Escape')

  const hint = await page.locator('.composer-hint').innerText().catch(() => '')
  console.log(`⑦ 不可选用提示：${hint.replace(/\s+/g, ' ').slice(0, 80)}`)
  if (!hint.includes('草稿需先发布')) {
    // 草稿被静默隐藏时用户只会觉得"我建的 Agent 不见了"
    failures.push('草稿 Agent 没有出现在"不能选用"的提示里（被静默隐藏了？）')
  }

  // ---------- ⑥ 试跑：草稿/已发布都能跑，失败要看得见原因 ----------
  await page.click('.nav-item:has-text("Agent 管理")')
  await cardByCode(agentCode).locator('.el-button:has-text("试跑")').click()
  const testDialog = dialog('试跑「')
  await testDialog.locator('.el-button:has-text("用示例问题")').click()
  await testDialog.locator('.el-button:has-text("开始试跑")').click()
  await page.waitForSelector('.test-result, .test-error', { timeout: 180000 })
  if (await page.locator('.test-error').count()) {
    // 只取第一行（后端原话）：下面那段"怎样解读这个错误"的提示不必进日志
    const errorText = (await page.locator('.test-error p').first().innerText()).replace(/\s+/g, ' ').trim()
    console.log(`⑧ 试跑未完成：${errorText.slice(0, 120)}`)
    // 后端原话必须出现在弹窗里，而不是 axios 那句 "Request failed with status code 502"：
    // 后者对用户毫无用处（client.ts 的那个坑，已修）
    if (/Request failed with status code/.test(errorText)) {
      failures.push('试跑错误只显示 HTTP 状态描述，没有透出后端原因')
    }
    // 没有配置模型（或模型未注册）属环境能力缺失，与 Agent 配置是否正确无关：
    // 报"跳过"而不是"失败"，否则每台没配 key 的机器都会看到一条假失败
    if (/模型|4001|4004|4006|429|超时/.test(errorText)) {
      skipped.push(`试跑：${errorText.slice(0, 120)}`)
    } else {
      failures.push(`试跑失败且原因不像环境问题：${errorText.slice(0, 120)}`)
    }
  } else {
    const result = await page.locator('.test-result').innerText()
    console.log(`⑧ 试跑完成：${result.split('\n').filter(Boolean).slice(0, 2).join(' / ')}`)
    if (result.includes('未使用工具')) {
      // 试跑带知识库范围，模型若一次都没检索，"试跑通过"这个结论就是假的
      failures.push('试跑没有使用任何工具（检索链路没跑通，别急着发布）')
    }
    await page.screenshot({ path: `${outDir}/agents-test.png` })
    console.log('✓ 已截图 docs/screenshots/agents-test.png')
  }
  await testDialog.locator('.el-dialog__footer .el-button, .el-dialog__headerbtn').first().click().catch(() => {})
  await page.keyboard.press('Escape')

  // ---------- ⑦ 停用：使用它的会话必须明确失败，而不是悄悄换回默认 ----------
  await cardByCode(agentCode).locator('.el-button:has-text("停用")').click()
  await confirmBox()
  await page.waitForFunction(
    (code) => {
      const cards = Array.from(document.querySelectorAll('.grid .card'))
      const target = cards.find((c) => c.querySelector('.code')?.textContent.trim() === code)
      return target?.textContent.includes('已停用') ?? false
    },
    agentCode,
    { timeout: 15000 }
  )
  console.log('⑨ 已停用')

  // 停用后对话页不能再列出它（列出来就是在骗用户"能选"）
  await page.click('.nav-item:has-text("对话")')
  // 必须像第 ⑥ 段那样先点"新对话"：页面挂载后会"自动选中最近会话"，
  // 一旦选中，`.composer-options` 就被头部的选择器取代——这条腿曾因此假失败
  // （点开的下拉随 DOM 被换掉而消失，报的是"下拉项没出现"，看着像产品缺陷）。
  // 点"新对话"同时把 pickedByUser 置位，自动选中不再覆盖：用的是应用自己的既定机制。
  await page.click('.rail .new-chat')
  await page.waitForSelector('.composer-options', { timeout: 15000 })
  await page.click('.composer-options .kb-select')
  await options.first().waitFor({ timeout: 15000 })
  const afterDisable = (await options.allInnerTexts()).map((text) => text.trim())
  if (afterDisable.includes(agentName)) {
    failures.push('已停用的 Agent 仍然出现在对话页的选择器里')
  } else {
    console.log('⑩ 已停用的 Agent 不再出现在选择器里')
  }
  await page.keyboard.press('Escape')

  // ---------- ⑧ 清理：删掉本轮造的 Agent（界面删除本身也是一条断言） ----------
  await page.click('.nav-item:has-text("Agent 管理")')
  await deleteAgentCard(copyCode)
  await deleteAgentCard(agentCode)
  console.log('⑪ 已清理本轮创建的 Agent（删除后卡片消失）')
} catch (error) {
  failures.push(error.message)
  console.error('✗ 验证失败：', error.message)
  await page.screenshot({ path: `${outDir}/agents-failure.png` }).catch(() => {})
} finally {
  await browser.close()
}

if (failures.length > 0) {
  console.error('失败项：' + failures.join('; '))
  process.exit(1)
}
if (skipped.length > 0) {
  console.error('跳过项：' + skipped.join('; '))
  process.exit(2)
}
console.log('\nAgent 管理页界面验证通过：新建 → 配置 → 发布 → 可选用 → 停用 → 不可选用 → 删除。')
