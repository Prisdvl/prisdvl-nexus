import postcss from 'postcss'
import { readFileSync } from 'node:fs'
for (const f of ['global.css', 'chat-view.css', 'reference.css', 'sidebar.css', 'settings-dialog.css', 'msgnav.css', 'tokens.css', 'context.css']) {
  const p = 'src/styles/' + f
  try {
    postcss.parse(readFileSync(p, 'utf8'))
    console.log('OK  ', f)
  } catch (e) {
    console.log('FAIL', f, '→', e.message.split('\n')[0])
  }
}
