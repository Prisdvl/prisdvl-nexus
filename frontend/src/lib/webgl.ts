/**
 * WebGL2 能力探测（单例缓存）。
 *
 * 为什么不能只靠「new Renderer 时让它自己失败」：
 *   ogl 的 Renderer 在 canvas.getContext() 返回 null（远程桌面/无 GPU/驱动黑名单/
 *   同页 context 数达上限）时会先 console.warn 再对 null 赋值并抛 TypeError，
 *   这个错误发生在 useEffect 里、会被路由错误边界接住，表现为整页白屏。
 *   挂载前先探测，不支持就根本不创建 WebGL 背景（退化为纯色/渐变底）。
 *
 * 探测拿到的 context 立即主动释放：浏览器对同一进程的 WebGL context 数量有上限
 * （Chrome 约 16 个），探测 context 不释放会挤占真正的背景组件。
 */
let cached: boolean | null = null

export function isWebGL2Available(): boolean {
  if (cached !== null) return cached
  try {
    const canvas = document.createElement('canvas')
    const gl = canvas.getContext('webgl2')
    cached = gl !== null
    gl?.getExtension('WEBGL_lose_context')?.loseContext()
  } catch {
    cached = false
  }
  return cached
}
