/**
 * 触发一次浏览器下载。
 *
 * 抽成共享模块的原因：设置里的「数据导出」和回答里的「结果卡」都要用，
 * 各写一份必然会出现"一个格式对了另一个漏了"的情况。
 */
export function download(filename: string, text: string, mime = 'text/plain') {
  const blob = new Blob([text], { type: mime })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

/** 复制到剪贴板；失败时返回 false（调用方给提示，不要静默） */
export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}

/** 二维数组 → CSV（含表头），带引号转义 */
export function toCSV(head: string[], rows: (string | number)[][]): string {
  const cell = (v: string | number) => `"${String(v).replace(/"/g, '""')}"`
  return [head.map(cell).join(','), ...rows.map((r) => r.map(cell).join(','))].join('\n')
}
