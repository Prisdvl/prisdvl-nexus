import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'node:path'

/**
 * 原型应用：独立端口 5175，与 vue(5173)/react(5174) 分开
 *
 * dev proxy 的依据（见 docs/frontend-integration-decisions.md §6）：
 * 后端 `grep Cors` 零命中 —— **后端没有任何 CORS 配置**，
 * 而后端 8080、前端 5175 是不同源，浏览器一定会拦。
 * 所以开发期走 proxy，让浏览器只看到 5175 这一个源。
 *
 * ⚠️ 目标端口做成可配：`SERVER_PORT` 可能被宿主环境注入成别的值
 * （实测曾被注入成 62504 并撞端口），8080 只能算默认值。
 * 生产部署另需 nginx 反代或后端开 CORS，proxy 只解决开发期。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.VITE_BACKEND_ORIGIN || 'http://127.0.0.1:8080'
  return {
    plugins: [react()],
    resolve: {
      alias: { '@': path.resolve(import.meta.dirname, './src') },
    },
    server: {
      port: 5175,
      strictPort: false,
      host: true,
      // cloudflared / ngrok 等公网隧道会改 Host，默认会 403
      allowedHosts: true,
      proxy: {
        // 上下文是 api：baseURL 默认 '/api'，所以 /api/xxx → target + /api/xxx
        '/api': { target, changeOrigin: true },
        // 后端零 CORS，actuator 健康检查同样需要转发（探活/排查用）
        '/actuator': { target, changeOrigin: true },
      },
    },
  }
})