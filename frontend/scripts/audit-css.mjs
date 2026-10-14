// 只读审计：找出 CSS 里定义、但源码中没有任何引用的类选择器（死样式候选）
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

const ROOT = 'D:/develop/Prisma/frontend/src'

function walk(dir, out = []) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e)
    const s = statSync(p)
    if (s.isDirectory()) walk(p, out)
    else out.push(p)
  }
  return out
}

const files = walk(ROOT)
const cssFiles = files.filter((f) => f.endsWith('.css'))
const srcFiles = files.filter((f) => /\.(tsx?|jsx?|html)$/.test(f))
const srcText = srcFiles.map((f) => readFileSync(f, 'utf8')).join('\n')
const htmlText = readFileSync('D:/develop/Prisma/frontend/index.html', 'utf8')
const haystack = srcText + '\n' + htmlText

const report = []
for (const f of cssFiles) {
  const text = readFileSync(f, 'utf8')
  const classes = new Set()
  // 去掉注释再取选择器里的类名
  const noComment = text.replace(/\/\*[\s\S]*?\*\//g, '')
  for (const m of noComment.matchAll(/\.(-?[_a-zA-Z][_a-zA-Z0-9-]*)/g)) classes.add(m[1])
  const unused = [...classes].filter((c) => !haystack.includes(c))
  if (unused.length) report.push({ file: f.replace(ROOT + '/', ''), total: classes.size, unused: unused.sort() })
}

for (const r of report) {
  console.log(`\n### ${r.file}  (${r.unused.length}/${r.total} 未引用)`)
  console.log(r.unused.join(', '))
}
