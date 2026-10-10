import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

/**
 * 后端地址。**默认可用**，不靠人记得改。
 *
 * ## 为什么默认是 8088 而不是 8080
 *
 * R0 最初写的是 `http://localhost:8080`——那是**本机 jar** 模式的端口。
 * 但实际开发用的是 docker compose：后端跑在容器里，只把**网关** 8088 映射到宿主机，
 * 宿主的 8080 上什么都没有。
 *
 * 这个错误在 R0 ~ R3 一直没被发现，因为那几轮都**没跑过真实 API 请求**
 * （R0~R2 页面全是占位；R3 的验证又因后端未启动而 SKIP）。
 * 第一次真正调登录才暴露：登录返回 **HTTP 404**——Vite 把请求转给了
 * 一个没人监听的端口，nginx 那边当然查不到 `/api/auth/login`。
 *
 * ## 两种模式
 * ```bash
 * # docker compose（默认，8088 网关）
 * npm run dev
 *
 * # 本机 jar（后端跑在 8080）：在 .env.local 写
 * #   VITE_API_TARGET=http://localhost:8080
 * ```
 *
 * 走网关还有一个好处：nginx 里有 **SSE 的免缓冲配置**
 * （`/api/chat/stream` 与 `/api/knowledge-bases/{id}/ask/stream` 有独立 location），
 * 而 R8 的流式回答依赖它。本机 jar 模式没有这一层，届时若出现
 * "流式不实时、一次性全出来"，**先怀疑这里**。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiTarget = env.VITE_API_TARGET || 'http://localhost:8088'

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      // 必须显式声明：tsconfig 的 paths 只影响类型检查，打包器不认。
      // 与 web/src/vite.config.ts 用同一条规则（`@` → src），这样 Phase R3 之后
      // 两套前端的 import 写法可以对齐，迁移时少一次机械改写。
      alias: {
        '@': path.resolve(import.meta.dirname, './src')
      }
    },
    server: {
      port: 5174,
      strictPort: false,
      // `/api` 与 `/mcp` 原样转发，**不写死后端地址**——换环境只改 env。
      proxy: {
        '/api': { target: apiTarget, changeOrigin: true },
        // MCP 端点：nginx.conf 里它是独立 location（/mcp），本地开发同样要能直连，
        // 否则将来调 MCP 工具会只在容器里能用、本地开发却 404。
        '/mcp': { target: apiTarget, changeOrigin: true }
      }
    }
  }
})
