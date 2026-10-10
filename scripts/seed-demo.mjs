/**
 * 演示数据播种（standalone / local 都可用）。
 *
 * ⚠️ **状态：退役（Vue 时代遗留，2026-10-09）**。它服务的界面验证脚本已随 `web/` 归档到
 * `docs/archive/web-vue/scripts/`；当前 React 前端（`frontend/`）的 QA 走 mock（`VITE_USE_MOCK=true`）
 * 或真实后端，**不读 `.demo-token`、也不依赖本脚本**（已核实无 CI / 脚本调用方）。
 * 保留它只为手动造演示数据与历史参考。
 *
 * 用途：`standalone` 用的是 H2 内存库，**每次重启数据全清**，
 * 而 web/scripts 下的界面验证脚本（现已归档到 docs/archive/web-vue/scripts/）都要求"有一个装着笔记的知识库"，
 * 否则第一次跑必然失败（失败原因还是"没有数据"这种与代码无关的事）。
 * 本脚本把这一步固定下来：注册/登录 demo 用户 → 建知识库 → 导入 scripts/demo-vault.zip
 * → 把 token 写到 docs/archive/web-vue/.demo-token（供已归档的界面脚本读取；当前 React 前端的 QA 走 mock，不读它）。
 *
 * 用法（先启动后端）：
 *   node scripts/seed-demo.mjs
 *
 * 幂等：用户已存在就登录，知识库已存在就复用（重复导入 vault 只会更新同名文档）。
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const base = process.env.NEXUS_BASE ?? 'http://localhost:8080'
const username = process.env.DEMO_USER ?? 'demo'
const password = process.env.DEMO_PASSWORD ?? 'Demo@12345'
const kbName = '我的笔记'

async function api(path, { method = 'GET', token, body } = {}) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  let payload = body
  if (body && !(body instanceof FormData)) {
    headers['Content-Type'] = 'application/json; charset=utf-8'
    payload = JSON.stringify(body)
  }
  const response = await fetch(`${base}${path}`, { method, headers, body: payload })
  const text = await response.text()
  if (!response.ok) {
    throw new Error(`${method} ${path} → HTTP ${response.status}：${text.slice(0, 300)}`)
  }
  const json = JSON.parse(text)
  if (json.code !== 0) {
    throw new Error(`${method} ${path} → code ${json.code}：${json.message}`)
  }
  return json.data
}

// ① 注册（已存在就忽略，注册接口对重复用户名会返回业务错误）
try {
  await api('/api/auth/register', { method: 'POST', body: { username, password } })
  console.log(`✓ 已注册用户 ${username}`)
} catch (error) {
  if (!error.message.includes('已存在') && !error.message.includes('exists')) {
    console.warn(`· 注册跳过：${error.message}`)
  }
}

// ② 登录并写 token——界面验证脚本靠它免登录
const { accessToken } = await api('/api/auth/login', { method: 'POST', body: { username, password } })
writeFileSync(resolve(here, '../docs/archive/web-vue/.demo-token'), accessToken, 'utf8')
console.log('✓ 已写入 docs/archive/web-vue/.demo-token')

// ③ 知识库：同名复用，避免反复运行堆出一排空库。
// 检索参数显式写在这里（Phase 3.2 起检索按知识库配置走）：
// 问答链路不再由服务端写死条数，而是用知识库的 retrieval_top_k；
// 阈值 0.25 是放宽的噪声下限（详见 application.yml 里 nexus.retrieval 的说明）。
const kbRetrieval = { retrievalTopK: 12, similarityThreshold: 0.25 }
const existing = (await api('/api/knowledge-bases', { token: accessToken })).list
let knowledgeBase = existing.find((item) => item.name === kbName)
if (knowledgeBase) {
  console.log(`· 复用知识库「${kbName}」(${knowledgeBase.id})`)
  // 复用也要对齐参数：老库可能是"建库默认值"那版数据，参数不一致会让演示结果莫名其妙
  knowledgeBase = await api(`/api/knowledge-bases/${knowledgeBase.id}`, {
    method: 'PUT',
    token: accessToken,
    body: kbRetrieval
  })
  console.log('· 已对齐演示检索参数：topK 12 / threshold 0.25')
} else {
  knowledgeBase = await api('/api/knowledge-bases', {
    method: 'POST',
    token: accessToken,
    body: { name: kbName, description: '演示：Redis / JVM / Spring 笔记', ...kbRetrieval }
  })
  console.log(`✓ 已建知识库「${kbName}」(${knowledgeBase.id})，检索参数 topK 12 / threshold 0.25`)
}

// ④ 导入演示笔记（ZIP：Obsidian vault 结构）
const vault = readFileSync(resolve(here, 'demo-vault.zip'))
const form = new FormData()
form.append('file', new Blob([vault], { type: 'application/zip' }), 'demo-vault.zip')
const task = await api(`/api/knowledge-bases/${knowledgeBase.id}/vault`, {
  method: 'POST',
  token: accessToken,
  body: form
})
console.log(`✓ 导入完成：新建 ${task.createdCount} / 更新 ${task.updatedCount} / 删除 ${task.deletedCount} 篇，切片 ${task.totalChunks}`)
console.log('\n演示数据就绪（standalone 重启后需要重新执行本脚本）。')
