/**
 * resolve 钩子：让 Node 认得 Vite 的两种解析规则。
 *
 * 由 `register-alias.mjs` 通过 `module.register()` 注册。
 * 用法：
 * ```bash
 * node --experimental-strip-types --import ./scripts/register-alias.mjs scripts/verify-sse.mjs
 * ```
 *
 * ## 补两件事
 *
 * 1. **`@/x` → `src/x`**：这是 Vite 的 alias，Node 不认。
 * 2. **相对路径补扩展名**：源码写 `./types/common`（无 `.ts`）——
 *    Vite 会补，Node 的 ESM 解析器**不会**（它要求完整路径）。
 *    只做 1 不做 2 会报 `Cannot find module .../src/api/types/common`。
 *
 * ## 为什么值得写这个钩子
 *
 * 有了它，验证脚本可以**直接 import 源 `.ts`**，测的是真实源码而不是构建产物。
 * 构建产物被压缩/tree-shake 过，"分片逻辑还在不在、行为变没变"都看不出来——
 * 而分片恰恰是 SSE 解析最容易错的地方。
 *
 * 替代方案（把源码改成相对 import，或让验证脚本 import `dist/`）都会
 * **为了工具去改产品代码**，方向反了。
 */
import { existsSync, statSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'

const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'src')

/** 依次尝试候选路径，返回第一个真实存在的文件。 */
function resolveReal(base) {
  const candidates = [
    base, // 原样（可能已带扩展名，如 .css）
    base + '.ts',
    base + '.tsx',
    base + '.mjs',
    base + '.js',
    path.join(base, 'index.ts'),
    path.join(base, 'index.tsx')
  ]
  for (const c of candidates) {
    try {
      if (existsSync(c) && statSync(c).isFile()) return c
    } catch {
      // 路径非法（含不可读字符）——继续试下一个
    }
  }
  return null
}

export function resolve(specifier, context, next) {
  // ① 路径别名
  if (specifier.startsWith('@/')) {
    const abs = resolveReal(path.join(SRC, specifier.slice(2)))
    if (abs) return next(pathToFileURL(abs).href, context)
    return next(specifier, context)
  }

  // ② 相对路径：补扩展名
  if ((specifier.startsWith('./') || specifier.startsWith('../')) && context.parentURL) {
    const parentDir = path.dirname(fileURLToPath(context.parentURL))
    const abs = resolveReal(path.resolve(parentDir, specifier))
    if (abs) return next(pathToFileURL(abs).href, context)
    return next(specifier, context)
  }

  return next(specifier, context)
}
