import { useEffect, useMemo, useRef, useState } from 'react'
import { marked } from 'marked'
import type { ThemeMode } from '@/lib/types'
import { appCopy } from '@/lib/appCopy'
import { useLocale } from '@/lib/locale'

interface MarkdownProps {
  text: string
  theme: ThemeMode
  /** 保留原始 HTML（原型内容可信；生产需接 sanitizer） */
  className?: string
}

let mermaidReady: Promise<typeof import('mermaid').default> | null = null
const loadMermaid = () => {
  if (!mermaidReady) mermaidReady = import('mermaid').then((m) => m.default)
  return mermaidReady
}

/** Markdown 正文渲染：标题 / 列表 / 表格 / 行内代码 / 代码块 / Mermaid 图 */
export default function Markdown({ text, theme, className = '' }: MarkdownProps) {
  const mermaidFail = appCopy(useLocale().locale).shell.mermaidFail
  const html = useMemo(() => marked.parse(text, { async: false, gfm: true, breaks: true }) as string, [text])
  const ref = useRef<HTMLDivElement>(null)
  const [mermaidErr, setMermaidErr] = useState<string | null>(null)

  useEffect(() => {
    const root = ref.current
    if (!root) return
    const blocks = Array.from(root.querySelectorAll<HTMLElement>('code.language-mermaid'))
    if (blocks.length === 0) return

    let cancelled = false
    loadMermaid().then(async (mermaid) => {
      if (cancelled) return
      mermaid.initialize({
        startOnLoad: false,
        securityLevel: 'loose',
        theme: theme === 'dark' ? 'dark' : 'default',
        fontFamily: 'inherit',
      } as Parameters<typeof mermaid.initialize>[0])
      for (let i = 0; i < blocks.length; i++) {
        const code = blocks[i]
        const holder = code.closest('pre') ?? code
        const src = code.textContent ?? ''
        try {
          const { svg } = await mermaid.render(`mmd-${Date.now()}-${i}`, src)
          if (cancelled) return
          const box = document.createElement('div')
          box.className = 'md-mermaid'
          box.innerHTML = svg
          holder.replaceWith(box)
        } catch (e) {
          setMermaidErr(String((e as Error).message ?? e).slice(0, 160))
          holder.classList.add('md-mermaid-error')
        }
      }
    })
    return () => { cancelled = true }
  }, [html, theme])

  return (
    <>
      <div ref={ref} className={`md ${className}`.trim()} dangerouslySetInnerHTML={{ __html: html }} />
      {mermaidErr && <p className="md-err">{mermaidFail}{mermaidErr}</p>}
    </>
  )
}