import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    // 关键：tsconfig.json 里的 paths 只影响类型检查，打包器不认。
    // Vite 必须在这里再声明一次别名，否则运行时会报 "Failed to resolve import @/..."。
    // 用 '/src' 这种"相对项目根"的写法，避免为 node:url 引入 @types/node。
    alias: {
      '@': '/src'
    }
  },
  server: {
    port: 5173,
    // 把 /api 代理到后端，前端因此与后端同源，不需要处理 CORS，
    // 也不需要在代码里写死后端地址（换个环境只改这里）
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
