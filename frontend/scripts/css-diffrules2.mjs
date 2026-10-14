import postcss from 'postcss'
import { readFileSync } from 'node:fs'
import { execSync } from 'node:child_process'

const head = execSync('git show HEAD:frontend/src/styles/global.css', { cwd: 'D:/develop/Prisma', encoding: 'utf8', maxBuffer: 1 << 24 })
const cur = readFileSync('src/styles/global.css', 'utf8')

const collect = (css) => {
  const out = new Map()
  const walk = (node, pre) => {
    for (const n of node.nodes ?? []) {
      if (n.type === 'rule') {
        const key = (pre + ' ' + n.selector).trim().replace(/\s+/g, ' ')
        const decls = (n.nodes?.filter((d) => d.type === 'decl').map((d) => d.prop + ':' + d.value) ?? []).sort()
        const prev = out.get(key)
        out.set(key, prev ? [...prev, ...decls] : decls)
      } else if (n.type === 'atrule' && n.nodes) walk(n, pre + ' ' + n.params)
    }
  }
  walk(postcss.parse(css), '')
  return out
}

const H = collect(head), C = collect(cur)
const EDITED = ['.page', '.settings-item:hover', '.settings-item[aria-current=\'true\']', '.settings-item', '.shell-main', '.lp-about-orb', '.overlay', '.sb-scrim', '.drawer-scrim', '.composer-hint', '.foot-note', 'button', '.chip', '.eyebrow, .lp-eyebrow', '.btn', '.panel', '.chat-layout', '.msg .bub', '.msg.user .bub', '.stat-card', '.session-item', '.session-item:hover', '.session-item[aria-selected=\'true\']', '.session-item .t', '.session-item .m', '.thread', '.msg', '.msg > div', '.msg.user', '.chat-main', '.sidebar', '.sb-brand', '.sb-nav', '.sb-nav .rubber-segment', '.sb-item', '.sb-item svg', '.sb-foot', '.sb-user', '.sb-user:hover', '.sb-avatar', '.sb-name', '.sb-sub', '.sb-out', '.field.has-error input', '.field.has-error textarea', '.field.has-error input:focus-visible', '.field.has-error textarea:focus-visible', '.field .err', '.cite-chip', '.citation', '.composer', '.composer:focus-within', '.composer textarea', '.composer .bar', '.composer .bar .hint', '.composer .send', '.composer .send:hover', '.composer .send.stop', '.kb-grid .pixel-card:hover, .agent-grid .pixel-card:hover, .kb-grid .pixel-card:focus-within, .agent-grid .pixel-card:focus-within', '.dialog', '.settings-item[aria-current=\'true\']']

let shown = 0
for (const [sel, hdecls] of H) {
  const cdecls = C.get(sel)
  if (!cdecls) continue
  const lost = hdecls.filter((d) => !cdecls.includes(d))
  if (lost.length && !EDITED.includes(sel)) {
    console.log(`!! ${sel}\n   丢失: ${lost.join(' | ').slice(0, 160)}`)
    shown++
  }
}
console.log(shown ? `\n${shown} 条规则有未解释的声明丢失` : '\n所有仍存在的规则均未丢失声明（除白名单内的有意修改）')
