import { useState } from 'react'
import { BarChart3, Braces, Check, Copy, Download, Table2 } from 'lucide-react'
import type { ResultPayload } from '@/data'
import { copyText, download, toCSV } from '@/lib/download'
import '@/styles/result.css'

interface Props {
  result: ResultPayload
}

/**
 * 结果卡（P1-3）。
 *
 * 解决的问题：回答里的表格/JSON/图表现在只能靠"从 Markdown 里手抄"取走，
 * 复制出来还是带竖线的文本，粘到 Excel 就散了。
 * 这里把结构化结果**当成一等公民**渲染，并提供"复制成 CSV/JSON"与"下载文件"。
 *
 * 取舍：不引图表库（只为几根柱子加一个几百 KB 的依赖不值当），用 CSS 画条形图。
 */
export default function ResultCard({ result }: Props) {
  const [copied, setCopied] = useState(false)

  const kindMeta = {
    table: { icon: <Table2 size={13} />, label: '表格' },
    json: { icon: <Braces size={13} />, label: 'JSON' },
    chart: { icon: <BarChart3 size={13} />, label: '图表' },
  }[result.kind]

  /* 三种结果都能"变成文本"：表格→CSV、图表→CSV、JSON→JSON */
  const asText = () => {
    if (result.kind === 'json') return JSON.stringify(result.json, null, 2)
    if (result.kind === 'table') return toCSV(result.columns ?? [], result.rows ?? [])
    return toCSV(['标签', '数值'], result.series.map((s) => [s.label, s.value]))
  }
  const ext = result.kind === 'json' ? 'json' : 'csv'
  const fileName = `${result.title.replace(/[\s/\\]+/g, '-')}.${ext}`

  const onCopy = async () => {
    const ok = await copyText(asText())
    setCopied(ok)
    setTimeout(() => setCopied(false), 1600)
  }

  const max = Math.max(...(result.kind === 'chart' ? result.series.map((s) => s.value) : [1]), 1)

  return (
    <figure className="result" aria-label={`结果：${result.title}（${kindMeta.label}）`}>
      <figcaption className="result__head">
        <span className="result__kind">{kindMeta.icon} {kindMeta.label}</span>
        <span className="result__title">{result.title}</span>
        <button className="result__btn" type="button" aria-label="复制结果" onClick={onCopy}>
          {copied ? <><Check size={12} /> 已复制</> : <><Copy size={12} /> 复制</>}
        </button>
        <button className="result__btn" type="button" aria-label="下载结果" onClick={() => download(fileName, asText(), result.kind === 'json' ? 'application/json' : 'text/csv')}>
          <Download size={12} /> 下载
        </button>
      </figcaption>

      {result.kind === 'table' && (
        <div className="result__scroll">
          <table className="result__table">
            <thead>
              <tr>{(result.columns ?? []).map((c) => <th key={c}>{c}</th>)}</tr>
            </thead>
            <tbody>
              {(result.rows ?? []).map((row, i) => (
                <tr key={i}>
                  {row.map((cell, j) => (
                    /* 数字右对齐：便于逐行比大小（表格的第一列通常是名称，保持左对齐） */
                    <td key={j} data-num={typeof cell === 'number' ? '' : undefined}>{cell}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {result.kind === 'json' && (
        <pre className="result__json">{JSON.stringify(result.json, null, 2)}</pre>
      )}

      {result.kind === 'chart' && (
        <div className="result__chart" role="img" aria-label={result.title}>
          {result.series.map((s) => (
            <div className="result__bar" key={s.label}>
              <span className="result__bar-label">{s.label}</span>
              <span className="result__bar-track">
                <span className="result__bar-fill" style={{ width: `${Math.round((s.value / max) * 100)}%` }} />
              </span>
              <span className="result__bar-value">{s.value}{result.unit ?? ''}</span>
            </div>
          ))}
        </div>
      )}
    </figure>
  )
}
