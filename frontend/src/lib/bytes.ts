/**
 * 字节数的展示格式。
 *
 * <p>只在「给用户看」的地方用（配额行、知识库占用）。判定阈值一律用字节比较，
 * 不要拿格式化后的字符串回比——`"1.0 GB" < "900 MB"` 按字典序是反的。
 *
 * <p>精确到 1 位小数、≥10 时取整：`12.3 MB` 有意义，`12.34 MB` 只是噪声；
 * 而 `1.0 GB` 与 `1.2 GB` 的差别又必须看得出来。
 */
export function fmtBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 MB'
  const gb = bytes / 1024 / 1024 / 1024
  if (gb >= 1) return `${gb.toFixed(gb >= 10 ? 0 : 1)} GB`
  const mb = bytes / 1024 / 1024
  return `${mb.toFixed(mb >= 10 ? 0 : 1)} MB`
}

/** 用量百分比（0-100 的整数），上限 <= 0 视为不限 → 返回 null（调用方不画条） */
export function usagePercent(used: number, limit: number): number | null {
  if (!Number.isFinite(limit) || limit <= 0) return null
  const ratio = (used / limit) * 100
  if (!Number.isFinite(ratio)) return null
  return Math.min(100, Math.max(0, Math.round(ratio)))
}
