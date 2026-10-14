// 删除已确认无引用的死样式：只删「整条规则的所有选择器都命中未引用类名」的块
// 用法：node scripts/prune-css.mjs [--write]
import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const ROOT = 'D:/develop/Prisma/frontend/src'
const WRITE = process.argv.includes('--write')

function walk(dir, out = []) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e)
    if (statSync(p).isDirectory()) walk(p, out)
    else out.push(p)
  }
  return out
}
const files = walk(ROOT)
const cssFiles = files.filter((f) => f.endsWith('.css'))
const hay = files.filter((f) => /\.(tsx?|jsx?)$/.test(f)).map((f) => readFileSync(f, 'utf8')).join('\n')
  + readFileSync('D:/develop/Prisma/frontend/index.html', 'utf8')

/** 去掉注释后的文本（保留长度以便定位）——用空格替换注释内容 */
const blank = (s) => s.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))

/** 扫描出一层里的所有块：{ kind:'rule'|'at', pre, header, start, end, bodyStart, bodyEnd } */
function scan(text, from, to) {
  const blocks = []
  let i = from
  let headerStart = from
  while (i < to) {
    const ch = text[i]
    if (ch === '"' || ch === "'") { const q = ch; i++; while (i < to && text[i] !== q) i += text[i] === '\\' ? 2 : 1; i++; continue }
    if (ch === '{') {
      let depth = 1
      let j = i + 1
      while (j < to && depth) {
        const c = text[j]
        if (c === '"' || c === "'") { const q = c; j++; while (j < to && text[j] !== q) j += text[j] === '\\' ? 2 : 1; j++; continue }
        if (c === '{') depth++
        else if (c === '}') depth--
        j++
      }
      const header = text.slice(headerStart, i).trim()
      blocks.push({ header, headerStart, start: i, end: j, bodyStart: i + 1, bodyEnd: j - 1 })
      i = j
      headerStart = i
      continue
    }
    i++
  }
  return blocks
}

const isAt = (h) => h.startsWith('@')
const selDead = (sel, dead) => sel.split(/[\s>~+]+/).some((part) => {
  const m = part.match(/\.(-?[_a-zA-Z][_a-zA-Z0-9-]*)/g)
  return !!m && m.some((c) => dead.has(c.slice(1)))
})

let totalRemoved = 0
for (const file of cssFiles) {
  const raw = readFileSync(file, 'utf8')
  const text = blank(raw) // 与 raw 等长，注释被空白化，用于判断选择器
  const classes = new Set()
  for (const m of text.matchAll(/\.(-?[_a-zA-Z][_a-zA-Z0-9-]*)/g)) classes.add(m[1])
  const dead = new Set([...classes].filter((c) => !hay.includes(c)))
  if (!dead.size) continue

  // 自底向上删除，避免位移
  const drops = []
  const visit = (from, to) => {
    const blocks = scan(text, from, to)
    for (const b of blocks) {
      if (isAt(b.header)) {
        if (/^@(media|supports|layer|container)/.test(b.header)) {
          const inner = visit(b.bodyStart, b.bodyEnd)
          if (inner === 0) drops.push(b) // 里面全被删空 → 整个 at 块也删
          continue
        }
        continue // @keyframes / @font-face 等原样保留
      }
      const sels = b.header.split(',').map((s) => s.trim()).filter(Boolean)
      if (sels.length && sels.every((s) => selDead(s, dead))) drops.push(b)
    }
    const kept = blocks.length - drops.filter((d) => d.headerStart >= from && d.end <= to).length
    return blocks.length ? kept : 0
  }
  const remaining = visit(0, text.length)
  void remaining
  if (!drops.length) continue

  drops.sort((a, b) => b.headerStart - a.headerStart)
  let out = raw
  let removedLines = 0
  for (const d of drops) {
    // 连同选择器前导空白一起删；保留后面的换行结构
    let s = d.headerStart
    while (s > 0 && /[ \t]/.test(out[s - 1])) s--
    let e = d.end
    while (e < out.length && /[ \t]/.test(out[e])) e++
    if (out[e] === '\n') e++
    const chunk = out.slice(s, e)
    removedLines += chunk.split('\n').length - 1
    out = out.slice(0, s) + out.slice(e)
  }
  totalRemoved += removedLines
  console.log(`\n### ${file.replace(ROOT + '/', '')}  删除 ${drops.length} 条规则 / ${removedLines} 行`)
  for (const d of drops.sort((a, b) => a.headerStart - b.headerStart)) {
    const line = raw.slice(0, d.headerStart).split('\n').length
    console.log(`  L${line}: ${d.header.replace(/\s+/g, ' ').slice(0, 110)}`)
  }
  if (WRITE) writeFileSync(file, out)
}
console.log(`\n合计删除 ${totalRemoved} 行${WRITE ? '（已写入）' : '（预览，未写入）'}`)
