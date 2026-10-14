/**
 * 实机 RAG 冒烟：登录 → 列知识库 → 检索 →（可选）ask
 * 用法：node frontend/scripts/smoke-rag.mjs
 */
const BASE = process.env.NEXUS_BASE || 'http://127.0.0.1:8080'

async function json(res) {
  const text = await res.text()
  try {
    return JSON.parse(text)
  } catch {
    throw new Error(`非 JSON 响应 ${res.status}: ${text.slice(0, 200)}`)
  }
}

async function api(path, { method = 'GET', token, body } = {}) {
  const headers = { Accept: 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  const payload = await json(res)
  if (!res.ok || payload.code !== 0) {
    throw new Error(`${method} ${path} → HTTP ${res.status} code=${payload.code} ${payload.message}`)
  }
  return payload.data
}

function ok(name, detail = '') {
  console.log(`ok   ${name}${detail ? ` — ${detail}` : ''}`)
}
function fail(name, err) {
  console.log(`FAIL ${name} — ${err}`)
}

const summary = { ok: 0, fail: 0 }

async function check(name, fn) {
  try {
    const detail = await fn()
    ok(name, detail ?? '')
    summary.ok++
  } catch (e) {
    fail(name, e.message || e)
    summary.fail++
  }
}

const login = await api('/api/auth/login', {
  method: 'POST',
  body: { username: 'demo', password: 'Demo@12345' },
})
const token = login.accessToken
ok('登录 demo', `expiresIn=${login.expiresIn}`)
summary.ok++

const kbPage = await api('/api/knowledge-bases?page=1&size=20', { token })
const kbs = kbPage?.list ?? kbPage?.records ?? (Array.isArray(kbPage) ? kbPage : [])
ok('列出知识库', `${kbs.length} 个`)
summary.ok++

if (!kbs.length) {
  console.log('SKIP 无知识库，无法继续检索/问答')
  process.exit(summary.fail ? 1 : 0)
}

const kb = kbs[0]
const kbId = kb.id
ok('选用知识库', `${kb.name || kbId} (${kbId})`)
summary.ok++

await check('hybrid 检索', async () => {
  const hits = await api(
    `/api/knowledge-bases/${kbId}/search?q=${encodeURIComponent('缓存')}&mode=hybrid&topK=5`,
    { token },
  )
  const list = hits?.list ?? hits?.hits ?? (Array.isArray(hits) ? hits : [])
  const n = Array.isArray(list) ? list.length : (hits?.total ?? Object.keys(hits || {}).length)
  return `命中≈${n}`
})

await check('bm25 检索', async () => {
  const hits = await api(
    `/api/knowledge-bases/${kbId}/search?q=${encodeURIComponent('索引')}&mode=bm25&topK=3`,
    { token },
  )
  const list = hits?.list ?? hits?.hits ?? (Array.isArray(hits) ? hits : [])
  return `命中≈${Array.isArray(list) ? list.length : '?'}`
})

await check('同步 RAG ask', async () => {
  const ans = await api(`/api/knowledge-bases/${kbId}/ask`, {
    method: 'POST',
    token,
    body: { question: '请用一句话概括这个知识库里和缓存或索引相关的要点' },
  })
  const text = ans?.answer ?? ans?.content ?? ''
  const cites = ans?.citations ?? ans?.refs ?? []
  return `answer=${text.length}字 citations=${Array.isArray(cites) ? cites.length : 0}`
})

await check('问答历史', async () => {
  const hist = await api(`/api/knowledge-bases/${kbId}/qa-history?page=1&size=5`, { token })
  const list = hist?.list ?? hist?.records ?? (Array.isArray(hist) ? hist : [])
  return `${Array.isArray(list) ? list.length : 0} 条`
})

console.log(`\n合计 ok=${summary.ok} fail=${summary.fail}`)
process.exit(summary.fail ? 1 : 0)
