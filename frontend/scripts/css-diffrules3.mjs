import postcss from 'postcss'
import { join } from 'node:path'
import { readFileSync } from 'node:fs'
import { execSync } from 'node:child_process'

const head = execSync('git show HEAD:frontend/src/styles/global.css', { cwd: join(import.meta.dirname, '..', '..'), encoding: 'utf8', maxBuffer: 1 << 24 })
const cur = readFileSync('src/styles/global.css', 'utf8')

const collect = (css) => {
  const out = new Map()
  const walk = (node, pre) => {
    for (const n of node.nodes ?? []) {
      if (n.type === 'rule') {
        const key = (pre + ' ' + n.selector).trim().replace(/\s+/g, ' ')
        const decls = (n.nodes?.filter((d) => d.type === 'decl').map((d) => d.prop) ?? [])
        const prev = out.get(key)
        out.set(key, prev ? [...prev, ...decls] : decls)
      } else if (n.type === 'atrule' && n.nodes) walk(n, pre + ' ' + n.params)
    }
  }
  walk(postcss.parse(css), '')
  return out
}

const H = collect(head), C = collect(cur)
for (const [sel, hProps] of H) {
  const cProps = C.get(sel)
  if (!cProps) continue
  const lost = [...new Set(hProps)].filter((p) => !cProps.includes(p))
  if (lost.length) console.log(`!! ${sel}  缺属性: ${lost.join(', ')}`)
}
console.log('done')
