import { marked } from 'marked'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js'

/**
 * Markdown 渲染。
 *
 * 三件事必须一起做，缺一不可：
 * 1. **渲染**（marked）——支持 GFM（表格、任务列表、删除线），笔记里很常见；
 * 2. **净化**（DOMPurify）——Markdown 里可以内嵌原始 HTML，笔记内容虽然是自己的，
 *    但导入的 Vault 可能来自任何地方；把净化放在渲染之后是最后一道防线；
 * 3. **代码高亮**（highlight.js）——技术笔记里代码块占比很高，
 *    不高亮的话正文可读性会差一大截。高亮在"插入 DOM 之后"做，
 *    而不是在 HTML 字符串阶段做（后者拿不到真实节点，也不好处理增量更新）。
 */
marked.setOptions({
  gfm: true,
  // 不把单个换行当 <br>：中文笔记里换行很随意，按 CommonMark 语义更符合预期
  breaks: false
})

export function renderMarkdown(source: string | null | undefined): string {
  if (!source) {
    return ''
  }
  const html = marked.parse(source) as string
  return DOMPurify.sanitize(html, {
    // 允许 id：标题锚点要靠它做目录跳转（DOMPurify 默认会保留 id，这里显式声明意图）
    ADD_ATTR: ['id', 'target']
  })
}

/**
 * 给渲染后的标题按顺序赋 id（h-0、h-1…），与大纲的序号一一对应。
 *
 * 为什么用序号而不是标题文本：同名的标题（"总结""示例"）在同一篇笔记里很常见，
 * 按文本生成 id 会互相覆盖，点击大纲跳到错误的位置。
 */
export function applyHeadingAnchors(root: HTMLElement | null): void {
  if (!root) {
    return
  }
  root.querySelectorAll('h1, h2, h3, h4, h5, h6').forEach((element, index) => {
    element.id = headingAnchor(index)
  })
}

export function headingAnchor(index: number): string {
  return `h-${index}`
}

/** 对容器内的代码块做语法高亮（渲染入 DOM 之后调用）。 */
export function highlightWithin(root: HTMLElement | null): void {
  if (!root) {
    return
  }
  root.querySelectorAll('pre code').forEach((element) => {
    hljs.highlightElement(element as HTMLElement)
  })
}
