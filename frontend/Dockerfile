# ============================================================
# 前端镜像（React 版）：构建阶段 Node，运行阶段只有 Nginx
#
#   浏览器 → Nginx ├─ /            静态资源（vite 构建产物）
#                  ├─ /api/...     反代 → backend:8080
#                  └─ /mcp         反代 → backend:8080
#
# 【为什么不用 Vite dev server 当运行形态】
# `vite` 是开发服务器：不做生产压缩、带 HMR 通道、按需编译。本地热更新（npm run dev）用它，
# 部署形态必须用构建产物。
#
# 【为什么构建在镜像里而不是拷贝宿主机的 dist】
# 宿主机是 Windows，产物里的 esbuild 二进制与平台相关；在 Linux 镜像里跑 `npm ci` 才能保证
# 产物与运行平台一致（这一点与原 Vue 版镜像的取舍相同）。
# ============================================================
FROM node:22-alpine AS build

WORKDIR /app

# 先只拷依赖清单：依赖没变时这一层走缓存，改业务代码不必重装 node_modules
COPY package.json package-lock.json ./
# npm ci 而不是 npm install：严格按 lock 文件装，构建可复现
RUN npm ci

COPY . .
# 与本地同一条命令（tsc --noEmit && vite build）：
# 类型错误会让镜像构建**失败** —— 这是有意的，把类型检查挡在构建期，而不是让它到运行期变成白屏。
RUN npm run build

# ------------------------------------------------------------
FROM nginx:1.29-alpine

# 覆盖默认站点配置（见同目录 nginx.conf：SSE 不缓冲、/mcp 反代、上传体积、SPA fallback）
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
