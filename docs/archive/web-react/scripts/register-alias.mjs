/**
 * 让 Node 认得 `@/` 路径别名（`@/lib/x` → `src/lib/x`）。
 *
 * ## 为什么需要它
 *
 * `verify-sse.mjs` 直接 import 源 `.ts` 文件（用 Node 的 `--experimental-strip-types`），
 * 这样验证脚本测的是**真实源码**，而不是构建产物——构建产物被压缩过，
 * 分片逻辑是否还在、行为是否一致都看不出来。
 *
 * 但 `@/` 是 **Vite 的 alias**，Node 不认。于是报
 * `Cannot find package '@/lib'`。
 *
 * ## 为什么不改成相对路径
 *
 * 源码里全用 `@/`（这是项目约定，R1 起就定了）。
 * 为了跑一个验证脚本而把源码改成相对 import，是**为了让工具适应工具**——
 * 方向反了。这里加钩子才是对的。
 */
import { register } from 'node:module'
import { pathToFileURL } from 'node:url'

register('./alias-hook.mjs', import.meta.url)

export { pathToFileURL }
