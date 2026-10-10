# ============================================================
# 前端镜像（Phase 5.7）：构建阶段 Node，运行阶段只有 Nginx
#
#   浏览器 → Nginx ├── /            静态资源（Vue 构建产物）
#                  ├── /api/...     反代 → backend:8080
#                  └── /mcp         反代 → backend:8080
#
# 【为什么不用 Vite dev server 当运行形态】
# `vite` 是开发服务器：不做生产压缩、带 HMR 通道、文件按需编译、单线程。
# 需求里"frontend 不直接暴露开发服务器"说的就是它。dev server 的价值在
# 本地热更新（npm run dev），部署形态必须用构建产物。
#
# 【为什么构建在镜像里而不是拷宿主机的 web/dist】
# 与后端相反：后端 jar 的构建约定绑着"先杀 java"，前端没有这种约束，
# 而 `npm ci` 在 Linux 镜像里跑能保证产物的 Node 版本与平台一致
# （宿主是 Windows，宿主构建出的 esbuild 二进制与 Linux 镜像不兼容）。
# ============================================================
FROM node:22-alpine AS build

WORKDIR /app

# 先只拷依赖清单：依赖没变时这一层走缓存，改业务代码不必重装 node_modules
COPY package.json package-lock.json ./
# npm ci 而不是 npm install：严格按 lock 文件装，构建可复现
RUN npm ci

COPY . .
# 与本地同一条命令（vue-tsc --noEmit && vite build）。
# 类型错误会让镜像构建**失败**——这是有意的：把类型检查挡在交付之前，
# 而不是让它躺到运行期变成一次白屏。
RUN npm run build

# ------------------------------------------------------------
FROM nginx:1.29-alpine

# 覆盖默认站点配置（见同目录 nginx.conf：SSE、SPA fallback、上传体积）
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
