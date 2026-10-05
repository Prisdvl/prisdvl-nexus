/**
 * 多格式导入验收（§39）：TXT / MD / PDF / DOCX / ZIP 逐个真实走完
 * 上传 → 解析 → 分块 → 嵌入 → 索引 → **检索命中**。
 *
 * ## 为什么要单独一个脚本
 *
 * 仓库里已有 verify-doc-lifecycle.mjs（文档生命周期）与 verify-full-product.mjs（全链路），
 * 但它们导入的都是 **Markdown**。而 Markdown 走的是纯文本解析路径，
 * **完全证明不了 PDF / DOCX 这两条二进制路径**——那两条才是真正容易坏的地方
 * （PDFBox 版本、POI 样式抽取、扫描件无文字、.doc 改名成 .docx）。
 *
 * 本脚本对每种格式上传一份**含独有事实**的文件，最后用该事实去检索：
 * 只有"检索得到"，才说明这份格式真的被解析、切分、嵌入、索引了，
 * 而不是"接口返回了 201"。
 *
 * ## 无外部依赖
 *
 * PDF 与 DOCX/ZIP 都在脚本内**手工构造**（PDF 写最小对象表 + 正确 xref；
 * DOCX 写 store 模式 ZIP + OOXML 三件套），
 * 因此不需要往仓库里塞二进制测试样本，也不会因为样本文件丢失而静默跳过。
 *
 * 用法：
 *   node web/scripts/verify-import-formats.mjs                          （默认打网关 http://localhost:8088）
 *   API_BASE=http://localhost:8080 node web/scripts/verify-import-formats.mjs   （本地 vite 开发）
 *
 * 退出码：0 = 全部格式通过；1 = 有格式失败。
 */
const API = process.env.API_BASE ?? 'http://localhost:8088'

const stamp = Date.now()
const tag = String(stamp).slice(-5)
const username = `fmt-${stamp}`
const password = 'FmtVerify@2026'
const kbName = `格式验收-${stamp}`

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ---------- 手工构造 PDF ----------
/** 生成一个最小但结构合法的单页 PDF（ASCII 文本，Helvetica），带正确的 xref 偏移表 */
function buildPdf(text) {
  const objs = []
  objs[1] = '<< /Type /Catalog /Pages 2 0 R >>'
  objs[2] = '<< /Type /Pages /Kids [3 0 R] /Count 1 >>'
  objs[3] =
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] ' +
    '/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>'
  const stream = `BT\n/F1 12 Tf\n72 720 Td\n(${text}) Tj\nET\n`
  objs[4] = `<< /Length ${Buffer.byteLength(stream, 'latin1')} >>\nstream\n${stream}endstream`
  objs[5] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'

  let pdf = '%PDF-1.4\n'
  const offsets = []
  for (let i = 1; i <= 5; i++) {
    offsets[i] = Buffer.byteLength(pdf, 'latin1')
    pdf += `${i} 0 obj\n${objs[i]}\nendobj\n`
  }
  const xrefPos = Buffer.byteLength(pdf, 'latin1')
  pdf += `xref\n0 6\n0000000000 65535 f \n`
  for (let i = 1; i <= 5; i++) {
    pdf += `${String(offsets[i]).padStart(10, '0')} 00000 n \n`
  }
  pdf += `trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xrefPos}\n%%EOF\n`
  return Buffer.from(pdf, 'latin1')
}

// ---------- 手工构造 ZIP（store 模式）----------
const CRC_TABLE = (() => {
  const table = new Int32Array(256)
  for (let i = 0; i < 256; i++) {
    let c = i
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    table[i] = c
  }
  return table
})()

function crc32(buf) {
  let c = -1
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return (c ^ -1) >>> 0
}

/** 生成 ZIP（不压缩）。用于 .docx 与 .zip，避免引入第三方依赖。 */
function buildZip(entries) {
  const parts = []
  const centrals = []
  let offset = 0

  for (const { name, data } of entries) {
    const nameBuf = Buffer.from(name, 'utf8')
    const crc = crc32(data)

    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(0x0800, 6) // UTF-8
    local.writeUInt16LE(0, 8) // store
    local.writeUInt16LE(0x21, 12)
    local.writeUInt32LE(crc, 14)
    local.writeUInt32LE(data.length, 18)
    local.writeUInt32LE(data.length, 22)
    local.writeUInt16LE(nameBuf.length, 26)
    parts.push(local, nameBuf, data)

    const central = Buffer.alloc(46)
    central.writeUInt32LE(0x02014b50, 0)
    central.writeUInt16LE(20, 4)
    central.writeUInt16LE(20, 6)
    central.writeUInt16LE(0x0800, 8)
    central.writeUInt16LE(0x21, 14)
    central.writeUInt32LE(crc, 16)
    central.writeUInt32LE(data.length, 20)
    central.writeUInt32LE(data.length, 24)
    central.writeUInt16LE(nameBuf.length, 28)
    central.writeUInt32LE(offset, 42)
    centrals.push(central, nameBuf)

    offset += local.length + nameBuf.length + data.length
  }

  const centralBuf = Buffer.concat(centrals)
  const eocd = Buffer.alloc(22)
  eocd.writeUInt32LE(0x06054b50, 0)
  eocd.writeUInt16LE(entries.length, 8)
  eocd.writeUInt16LE(entries.length, 10)
  eocd.writeUInt32LE(centralBuf.length, 12)
  eocd.writeUInt32LE(offset, 16)
  return Buffer.concat([...parts, centralBuf, eocd])
}

/** 构造最小合法 DOCX（含一个 Heading1 段落 + 正文段落） */
function buildDocx(text) {
  const xml = (body) =>
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>${body}`
  return buildZip([
    {
      name: '[Content_Types].xml',
      data: Buffer.from(
        xml(
          '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
            '<Default Extension="xml" ContentType="application/xml"/>' +
            '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>' +
            '</Types>'
        ),
        'utf8'
      )
    },
    {
      name: '_rels/.rels',
      data: Buffer.from(
        xml(
          '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>' +
            '</Relationships>'
        ),
        'utf8'
      )
    },
    {
      name: 'word/document.xml',
      data: Buffer.from(
        xml(
          '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>' +
            '<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>格式验收标题</w:t></w:r></w:p>' +
            `<w:p><w:r><w:t>${text}</w:t></w:r></w:p>` +
            '</w:body></w:document>'
        ),
        'utf8'
      )
    }
  ])
}

// ---------- HTTP ----------
let token = ''

async function api(method, path, body, isForm = false) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body && !isForm) headers['Content-Type'] = 'application/json'
  const res = await fetch(`${API}/api${path}`, {
    method,
    headers,
    body: isForm ? body : body ? JSON.stringify(body) : undefined
  })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    /* 非 JSON 响应保留原文用于报错 */
  }
  return { status: res.status, json, text }
}

async function upload(kbId, fileName, buf) {
  const form = new FormData()
  form.append('file', new Blob([buf]), fileName)
  return api('POST', `/knowledge-bases/${kbId}/documents`, form, true)
}

/** 轮询导入任务到终态 */
async function waitTask(taskId, label) {
  for (let i = 0; i < 150; i++) {
    await sleep(1500)
    const { json } = await api('GET', `/import-tasks/${taskId}`)
    const task = json?.data
    if (!task) continue
    if (['DONE', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(task.status)) {
      return task
    }
  }
  return { status: 'TIMEOUT', failedCount: -1, totalChunks: 0, _label: label }
}

// ---------- 主流程 ----------
const results = []
function record(format, pass, detail) {
  results.push({ format, pass, detail })
  console.log(`${pass ? '✓' : '✗'} ${format.padEnd(6)} ${detail}`)
}

const FACT = {
  txt: `NEXUS-TXT-FACT-${tag}`,
  md: `NEXUS-MD-FACT-${tag}`,
  pdf: `NEXUS-PDF-FACT-${tag}`,
  docx: `NEXUS-DOCX-FACT-${tag}`,
  zip: `NEXUS-ZIP-FACT-${tag}`
}

const files = [
  { format: 'txt', name: `验收-${tag}.txt`, buf: Buffer.from(`纯文本资料。\n唯一标记：${FACT.txt}\n`, 'utf8') },
  { format: 'md', name: `验收-${tag}.md`, buf: Buffer.from(`# 标题\n\n唯一标记：${FACT.md}\n`, 'utf8') },
  { format: 'pdf', name: `验收-${tag}.pdf`, buf: buildPdf(`Unique marker ${FACT.pdf}`) },
  { format: 'docx', name: `验收-${tag}.docx`, buf: buildDocx(`唯一标记：${FACT.docx}`) }
]

/**
 * 两个知识库，不是一个。
 *
 * 原因（实测踩出来的）：**Vault（ZIP）导入是全量同步语义 —— 清单里没有的文档会被删除**。
 * 把 ZIP 和单文件放进同一个库里测，最后上传的 ZIP 会把前面四个文件全删掉，
 * 于是出现"四个格式导入全部 DONE、索引也全部 SUCCESS，检索却只剩 1 条命中"。
 * 那是脚本的错，不是产品的错（产品行为符合设计）——
 * 但顺带证明了一件事：这个语义**在界面上没有任何警示**，见 docs/user-acceptance-issues.md UA-015。
 */
let kbId = null
let vaultKbId = null

try {
  // 注册 + 登录
  await api('POST', '/auth/register', { username, password })
  const login = await api('POST', '/auth/login', { username, password })
  token = login.json?.data?.accessToken
  if (!token) throw new Error(`登录失败：${login.status} ${login.text.slice(0, 200)}`)
  console.log(`测试账号 ${username} 登录成功\n`)

  const kb = await api('POST', '/knowledge-bases', { name: kbName, description: '多格式单文件导入验收' })
  kbId = kb.json?.data?.id
  if (!kbId) throw new Error(`建库失败：${kb.status} ${kb.text.slice(0, 200)}`)

  const vaultKb = await api('POST', '/knowledge-bases', {
    name: `${kbName}-Vault`,
    description: 'Vault 全量同步验收（独立库，避免删掉单文件导入的文档）'
  })
  vaultKbId = vaultKb.json?.data?.id
  if (!vaultKbId) throw new Error(`建 Vault 库失败：${vaultKb.status} ${vaultKb.text.slice(0, 200)}`)

  // ---------- 逐格式：上传 → 等终态 ----------
  for (const file of files) {
    const res = await upload(kbId, file.name, file.buf)
    const taskId = res.json?.data?.id
    if (!taskId) {
      record(file.format, false, `上传被拒（HTTP ${res.status}）：${(res.json?.message ?? res.text).slice(0, 120)}`)
      continue
    }
    const task = await waitTask(taskId, file.format)
    const ok = task.status === 'DONE' && (task.totalChunks ?? 0) > 0
    record(
      file.format,
      ok,
      ok
        ? `导入 DONE，切片 ${task.totalChunks}，失败 ${task.failedCount ?? 0}`
        : `状态=${task.status}，切片=${task.totalChunks ?? 0}，失败=${task.failedCount ?? '-'}`
    )
  }

  // ---------- ZIP（Vault）：内含一个 Markdown，全量同步语义 ----------
  const vaultZip = buildZip([
    {
      name: 'Vault/笔记.md',
      data: Buffer.from(`# Vault 内笔记\n\n唯一标记：${FACT.zip}\n`, 'utf8')
    }
  ])
  const vaultRes = await api(
    'POST',
    `/knowledge-bases/${vaultKbId}/vault`,
    (() => {
      const form = new FormData()
      form.append('file', new Blob([vaultZip]), `vault-${tag}.zip`)
      return form
    })(),
    true
  )
  const vaultTaskId = vaultRes.json?.data?.id
  if (!vaultTaskId) {
    record('zip', false, `上传被拒（HTTP ${vaultRes.status}）：${(vaultRes.json?.message ?? vaultRes.text).slice(0, 120)}`)
  } else {
    const task = await waitTask(vaultTaskId, 'zip')
    const ok = task.status === 'DONE' && (task.createdCount ?? 0) > 0
    record('zip', ok, ok ? `Vault 导入 DONE，新增 ${task.createdCount} 篇` : `状态=${task.status}，新增=${task.createdCount ?? '-'}`)
  }

  // ---------- 关键：导入只完成"解析 + 分块"，向量化与建索引是**独立步骤** ----------
  //
  // 这条是实测踩出来的：五个文件全部 import 成功、状态 DONE、切片数正常，
  // 但直接检索 **0 命中**。回代码确认 ImportTaskExecutor 里没有任何 embedding / index 调用——
  // 它只做解析与分块。可检索性由 /workflow/run/{kbId}（统计→向量化→建索引）负责。
  // 因此"导入完成"≠"可以问答"，这是用户最容易踩的坑。
  // 两个库都要跑：workflow 是按**知识库**维度的
  const wfSummary = []
  let wfOk = true
  for (const [label, id] of [['单文件库', kbId], ['Vault库', vaultKbId]]) {
    const wf = await api('GET', `/workflow/run/${id}`)
    const steps = wf.json?.data ?? []
    const ok = steps.length > 0 && steps.every((s) => s.status === 'SUCCESS')
    if (!ok) wfOk = false
    wfSummary.push(
      `${label}[${steps.length ? steps.map((s) => `${s.name}=${s.status}`).join(' → ') : `HTTP ${wf.status}`}]`
    )
  }
  record('索引', wfOk, wfSummary.join('  '))

  // ---------- 用**独有事实**检索，验证真的进了索引 ----------
  // 这一步才是 §40 要求的"证明检索命中了我上传的文档"，而不是"接口返回了成功"
  await sleep(2000) // ES refresh_interval=1s，给索引一点时间可见
  console.log('')
  for (const [format, fact] of Object.entries(FACT)) {
    const targetKb = format === 'zip' ? vaultKbId : kbId
    const res = await api('GET', `/knowledge-bases/${targetKb}/search?q=${encodeURIComponent(fact)}&topK=5&mode=hybrid`)
    const hits = res.json?.data ?? []
    const hit = JSON.stringify(hits).includes(fact)
    record(`${format}-检索`, hit, hit ? `命中 ${hits.length} 条，独有事实可检索` : `未命中（返回 ${hits.length} 条）`)
  }
} catch (error) {
  record('流程', false, error.message)
} finally {
  // 清理：只删本次创建的知识库（走 API 级联，不做裸表删除）
  for (const id of [kbId, vaultKbId]) {
    if (id) {
      const del = await api('DELETE', `/knowledge-bases/${id}`)
      console.log(`\n清理：删除测试知识库 ${id} → HTTP ${del.status}`)
    }
  }
}

const failed = results.filter((r) => !r.pass)
console.log('\n' + '='.repeat(60))
console.log(`多格式导入验收：${results.length - failed.length} / ${results.length} 通过`)
if (failed.length) {
  console.log('未通过：')
  for (const item of failed) console.log(`  ✗ ${item.format} — ${item.detail}`)
}
console.log('='.repeat(60))

process.exit(failed.length ? 1 : 0)
