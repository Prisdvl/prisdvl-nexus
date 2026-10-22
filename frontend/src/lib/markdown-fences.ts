/**
 * 代码围栏规整：把模型输出里「没写在独立行」的围栏拆出来，避免吞噬后续正文。
 *
 * <p><b>为什么需要</b>：CommonMark / GFM 规定收尾围栏必须独占一行（`^ {0,3}(`{3,}|~{3,})\s*$`）。
 * 模型经常写成 `Class.forName("java.lang.String"); // 全限定名加载```——围栏粘在代码行尾，
 * 于是围栏永远闭合不了，后面所有正文（列表、粗体、表格）都被当成代码块原样显示。
 * 这不是渲染器的 bug，但用户看到的是「回答后半段变成灰框」。
 *
 * <p><b>策略</b>（只在围栏内部生效，围栏外的 Markdown 一个字节都不动）：
 * <ol>
 *   <li>收尾围栏粘在有内容的行尾 → 拆成「内容行 + 独立围栏行」；</li>
 *   <li>到文档结束仍在围栏内 → 补一个同字符、同长度的收尾围栏。</li>
 * </ol>
 * 代价是围栏内「以三连反引号结尾的代码行」会被当成围栏结束——在聊天回答里这几乎只可能
 * 是模型的围栏错误引用，而不是真的想展示三连反引号（要展示得用四个反引号包起来）。
 */

const FENCE_OPEN = /^(\s*)(`{3,}|~{3,})/
const FENCE_CLOSE_ONLY = /^(`{3,}|~{3,})\s*$/
/** 围栏后面可能跟着的标点：模型常把 ```` ``` ```` 写进句子或加引号收尾 */
const TRAILING_NOISE = /["'”’）)\s]*$/

interface Fence {
  char: '`' | '~'
  len: number
}

const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** 围栏内某行是否是「内容 + 粘在行尾的收尾围栏」。返回切分位置或 null。 */
function splitGluedCloser(line: string, fence: Fence): { body: string } | null {
  const marker = fence.char.repeat(fence.len)
  const re = new RegExp(`^(.*\\S)\\s*(${escapeRe(marker)})${TRAILING_NOISE.source}`)
  const m = line.match(re)
  if (!m) return null
  /* 只认「围栏前面确有代码/文字」的情况；整行只有围栏时上面的 close-only 分支已经处理 */
  const body = m[1].replace(/\s+$/, '')
  if (!body) return null
  return { body }
}

/**
 * 规整围栏。幂等：已经合法的 Markdown 原样返回。
 *
 * @param src 模型输出的 Markdown 原文
 */
export function normalizeFences(src: string): string {
  /* 没有围栏字符就是纯正文，别白跑一遍正则 */
  if (!src.includes('```') && !src.includes('~~~')) return src

  const lines = src.replace(/\r\n?/g, '\n').split('\n')
  const out: string[] = []
  let fence: Fence | null = null

  for (const line of lines) {
    if (!fence) {
      const open = FENCE_OPEN.exec(line)
      if (open) {
        const token = open[2]
        fence = { char: token[0] as '`' | '~', len: token.length }
      }
      out.push(line)
      continue
    }

    if (FENCE_CLOSE_ONLY.test(line.trim())) {
      fence = null
      out.push(line)
      continue
    }

    const glued = splitGluedCloser(line, fence)
    if (glued) {
      out.push(glued.body)
      out.push(fence.char.repeat(fence.len))
      fence = null
      continue
    }

    out.push(line)
  }

  if (fence) out.push(fence.char.repeat(fence.len))
  return out.join('\n')
}
