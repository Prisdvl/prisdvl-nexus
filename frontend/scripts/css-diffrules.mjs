import postcss from 'postcss'
import { readFileSync } from 'node:fs'
import { execSync } from 'node:child_process'

const head = execSync('git show HEAD:frontend/src/styles/global.css', { cwd: 'D:/develop/Prisma', encoding: 'utf8', maxBuffer: 1 << 24 })
const cur = readFileSync('src/styles/global.css', 'utf8')

const collect = (css) => {
  const out = []
  const walk = (node, pre) => {
    for (const n of node.nodes ?? []) {
      if (n.type === 'rule') out.push({ sel: (pre + ' ' + n.selector).trim(), decls: Object.keys(Object.fromEntries(n.nodes?.filter((d) => d.type === 'decl').map((d) => [d.prop, d.value]) ?? [])).sort().join(';'), prop: n.raws.before?.includes('\n') ? '' : 'inline' })
      else if (n.type === 'atrule' && n.nodes) walk(n, (pre + ' ' + n.params).trim())
    }
  }
  walk(postcss.parse(css), '')
  return out
}

const H = collect(head), C = collect(cur)
const cKeys = new Set(C.map((r) => r.sel + ' || ' + r.decls))
const missing = H.filter((r) => !cKeys.has(r.sel + ' || ' + r.decls))
console.log('HEAD 规则数', H.length, ' 当前规则数', C.length, ' 内容有差异的规则', missing.length)
for (const m of missing) console.log('  -', m.sel.slice(0, 90))
