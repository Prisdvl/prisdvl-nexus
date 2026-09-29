import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    // 关键：tsconfig.json 里的 paths 只影响类型检查，打包器不认。
    // Vite 必须在这里再声明一次别名，否则运行时会报 "Failed to resolve import @/..."。
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
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
