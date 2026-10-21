// 嵌入模型 BYOK（路线 A）设置页验收：真后端（standalone / H2）+ 真前端
//
// 与 mock 版脚本的区别：这个脚本**不**用 VITE_USE_MOCK，而是对着
//   java -jar nexus-app/target/nexus-app-0.0.1-SNAPSHOT.jar --spring.profiles.active=standalone
// 起来的前端 + 后端跑，所以"保存 → 掩码回显 → 刷新后仍在 → 开关关闭 → 建库回落全局"
// 全部是真实往返，而不是桩数据。
//
// 用法：
//   $env:QA_BASE='http://localhost:5175'; node scripts/qa-embedding-byok.mjs
//
// 白名单：后端用 --ai.byok.embedding.allowed-models=bailian:text-embedding-v4,bailian:text-embedding-v3
// 起（生产默认只有 v4）。给两项是为了让"用户模型 vs 全局模型"在建库冻结值上**可区分**——
// 只有一项时两者相同，回退与否看不出来。
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'

const BASE = process.env.QA_BASE ?? 'http://localhost:5175'
const OUT = process.env.QA_SHOTS_OUT ?? '.'
mkdirSync(OUT, { recursive: true })

const stamp = Date.now().toString().slice(-8)
const USER = `byok-e2e-${stamp}`
const PASSWORD = 'Passw0rd!23'
const FAKE_KEY = 'sk-e2e-byok-abcdefghijklmnop123456'   // 刻意用假 Key：保存路径不调模型
const MASKED = 'sk-…56'                                 // 服务端掩码：前 3 + … + 后 2
const BYOK_BASE_URL = 'https://byok-e2e.example.com/compatible-mode/v1'
const BYOK_MODEL = 'text-embedding-v4'
const GLOBAL_MODEL = 'text-embedding-v4'

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const issues = []
const check = (name, ok, extra = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`)
  if (!ok) issues.push(name)
}
page.on('pageerror', (e) => issues.push(`pageerror: ${String(e.message).slice(0, 160)}`))
page.on('console', (m) => {
  if (m.type() !== 'error') return
  const text = m.text()
  if (/ERR_CONNECTION_CLOSED|ERR_CONNECTION_REFUSED|WebSocket|\[vite\]/i.test(text)) return
  issues.push(`console.error: ${text.slice(0, 160)}`)
})

/* ---------- 准备：注册账号（走真接口） ---------- */
const register = await page.request.post(`${BASE}/api/auth/register`, {
  data: { username: USER, password: PASSWORD, email: `${USER}@example.com` },
})
check('注册测试账号', [200, 409].includes(register.status()), `HTTP ${register.status()}`)

/* ---------- 登录（走真界面） ---------- */
await page.addInitScript(() => {
  localStorage.setItem('nx-proto-tour-done', '1')
  localStorage.setItem('nx-proto-theme-mode', 'light')
})
await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle' })
await page.waitForSelector('.auth-card')
await page.locator('#li-u').fill(USER)
await page.locator('#li-p').fill(PASSWORD)
await page.locator('.auth-card .btn-primary', { hasText: '登录' }).click()
await page.waitForSelector('.shell', { timeout: 15000 })
check('真后端登录成功', true)

/* ---------- 设置页：分区数与新分区渲染 ---------- */
await page.goto(`${BASE}/#/app/account`, { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
const items = await page.locator('.settings-item').count()
check('设置页共 12 个分区', items === 12, `${items} 个`)
await page.locator('.settings-item', { hasText: '嵌入模型' }).first().click()
await page.waitForTimeout(700)

const providerOpts = await page.locator('#ek-provider option').allInnerTexts()
const modelOpts = await page.locator('#ek-model option').allInnerTexts()
// 白名单供应商 + 可选的「自定义端点」入口（后端 ai.byok.embedding.allow-custom-endpoint）
check('供应商下拉来自后端白名单', providerOpts.includes('bailian'), providerOpts.join('/'))
check('模型下拉来自后端白名单', modelOpts.includes('text-embedding-v4'), modelOpts.join('/'))
/* 维度是平台决定的，界面上不再有可编辑宿主——只断言"没有假输入框 + 文案里写明 1024"。
   原来这里断言 #ek-dim 是 disabled input，那段假输入框已在纯交互批里删掉。 */
check(
  '维度是只读文案且写明 1024（没有假输入框）',
  (await page.locator('#ek-dim').count()) === 0
    && /1024/.test((await page.locator('#ek-dim-text').innerText()) ?? ''),
)
check('未配置时显示未设置状态', ((await page.locator('#ek-key').getAttribute('placeholder')) ?? '').includes('sk-…'))

/* ---------- 保存 → 掩码回显 ---------- */
await page.locator('#ek-model').selectOption(BYOK_MODEL)
await page.locator('#ek-base').fill(BYOK_BASE_URL)
await page.locator('#ek-key').fill(FAKE_KEY)
await page.locator('.settings-body [role=switch]').click()
await page.locator('.settings-body button[type=submit]').click()
await page.waitForTimeout(1500)
const placeholderAfterSave = (await page.locator('#ek-key').getAttribute('placeholder')) ?? ''
check('保存后回显掩码（不回明文）', placeholderAfterSave.includes(MASKED), placeholderAfterSave)
check('保存后 Key 输入框被清空', (await page.locator('#ek-key').inputValue()) === '')
check('保存后开关处于启用', (await page.locator('.settings-body [role=switch]').getAttribute('aria-checked')) === 'true')

/* ---------- 刷新后仍在（真落库 + 真回显） ---------- */
await page.reload({ waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
await page.locator('.settings-item', { hasText: '嵌入模型' }).first().click()
await page.waitForTimeout(700)
const placeholderAfterReload = (await page.locator('#ek-key').getAttribute('placeholder')) ?? ''
check('刷新后配置与掩码仍在', placeholderAfterReload.includes(MASKED) && (await page.locator('#ek-base').inputValue()) === BYOK_BASE_URL, placeholderAfterReload)
check('刷新后模型选择仍是白名单那项', (await page.locator('#ek-model').inputValue()) === BYOK_MODEL)

/* ---------- 启用时建库：冻结用户自己的模型 ---------- */
const login = await page.request.post(`${BASE}/api/auth/login`, { data: { username: USER, password: PASSWORD } })
const token = (await login.json()).data.accessToken
const auth = { Authorization: `Bearer ${token}` }
const kbEnabled = await (await page.request.post(`${BASE}/api/knowledge-bases`, {
  headers: auth, data: { name: `启用BYOK建的库-${stamp}` },
})).json()
check('启用 BYOK 时建库冻结用户模型', kbEnabled?.data?.embeddingModel === BYOK_MODEL, JSON.stringify({ model: kbEnabled?.data?.embeddingModel, dim: kbEnabled?.data?.embeddingDim }))

/* ---------- 关闭开关 → 建库回落全局模型 ---------- */
await page.locator('.settings-body [role=switch]').click()
await page.locator('.settings-body button[type=submit]').click()
await page.waitForTimeout(1500)
check('关闭后开关为禁用', (await page.locator('.settings-body [role=switch]').getAttribute('aria-checked')) === 'false')
const kbDisabled = await (await page.request.post(`${BASE}/api/knowledge-bases`, {
  headers: auth, data: { name: `禁用BYOK建的库-${stamp}` },
})).json()
check('禁用后建库回落全局模型', kbDisabled?.data?.embeddingModel === GLOBAL_MODEL, JSON.stringify({ model: kbDisabled?.data?.embeddingModel, dim: kbDisabled?.data?.embeddingDim }))
check('禁用不清空已存的 Key（掩码仍在）', ((await page.locator('#ek-key').getAttribute('placeholder')) ?? '').includes(MASKED))

await page.screenshot({ path: `${OUT}/byok-embedding-settings.png` })
console.log(issues.length === 0 ? 'ALL OK' : `ISSUES: ${issues.join(' | ')}`)
await browser.close()
process.exit(issues.length ? 1 : 0)
