/**
 * 消息摘要：给"导航预览"用的纯文本。
 *
 * 导航是**预览**不是完整消息，所以这里要把 Markdown 压平成一句话：
 * 去代码块、去图片/链接语法、去标题与标记符号、折叠空白，最后按 **Unicode 字符**
 * 安全截断（中文不会被切断成半个字）。
 */

const CODE_FENCE = /```[\s\S]*?```/g
const INLINE_CODE = /`([^`]*)`/g
const IMAGE = /!\[[^\]]*\]\([^)]*\)/g
const LINK = /\[([^\]]*)\]\([^)]*\)/g
const HEADING = /^\s{0,3}#{1,6}\s+/gm
const QUOTE = /^\s{0,3}>\s?/gm
const LIST_MARK = /^\s{0,3}(?:[-*+]|\d+\.)\s+/gm
const TABLE_ROW = /^\s*\|.*\|\s*$/gm
const RULE = /^\s*(?:-{3,}|\*{3,}|_{3,})\s*$/gm
const BOLD_ITALIC = /(\*\*|__|\*|_|~~)/g
const LATEX = /\$\$?[^$]*\$\$?/g
const MULTI_SPACE = /\s+/g

/** 把 Markdown 压成一行纯文本（保留可读句子） */
export function stripMarkdown(md: string): string {
  return md
    .replace(CODE_FENCE, ' ')
    .replace(INLINE_CODE, '$1')
    .replace(IMAGE, ' ')
    .replace(LINK, '$1')
    .replace(LATEX, ' ')
    .replace(TABLE_ROW, ' ')
    .replace(RULE, ' ')
    .replace(HEADING, '')
    .replace(QUOTE, '')
    .replace(LIST_MARK, '')
    .replace(BOLD_ITALIC, '')
    .replace(MULTI_SPACE, ' ')
    .trim()
}

/**
 * 截断到 `max` 个 Unicode 字符（中文/emoji 都安全），超出补省略号。
 * `[...str]` 按码点切分，比 `slice` 更安全（不会把 emoji 或组合字切坏）。
 */
export function clipChars(text: string, max: number): string {
  const chars = [...text]
  return chars.length <= max ? text : `${chars.slice(0, max).join('')}…`
}

/** 一步到位：消息正文 → 预览摘要 */
export function messageSummary(text: string, max = 140): string {
  const flat = stripMarkdown(text)
  if (!flat) return '（暂无文本内容）'
  return clipChars(flat, max)
}
