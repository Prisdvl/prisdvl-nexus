# React 前端 API / 状态 / 鉴权报告（Phase R3）

> 阶段目标：建立 React 前端的**应用基础设施**——身份层 / 状态层 / 数据访问层 / 错误层 / 路由保护层。
> 未实现：KnowledgeBase、Chat、Agent、Evaluation 的任何业务页面。
> 上游：R0 骨架 → R1 Design System → R2 Layout → **本文件**。

---

## 0. 结论先行（含一条必须先说的验证限制）

| 项 | 状态 |
| --- | --- |
| `npm run build` | ✅ 通过 |
| API Client / 类型层 / storage / errors | ✅ **已实现** |
| Auth Store（��态）/ Knowledge Store | ✅ **已实现** |
| Protected Route / Login 基础 / Logout / 401 | ✅ **已实现** |
| `verify-auth.mjs` | ⚠️ **PASS 8 / FAIL 0 / SKIP 6** |
| `verify-layout.mjs` 回归 | ⚠️ **SKIP（退出码 2）** |

### ✅ 验证状态：后端已恢复，全链路实测通过

Docker 启动后补跑，**两组脚本全绿**：

```text
npm run build                ✅ 通过
scripts/verify-auth.mjs      ✅ PASS 25 / FAIL 0 / SKIP 0
scripts/verify-layout.mjs    ✅ PASS 20 / FAIL 0（退出码 0）——R2 未被破坏
```

### 补跑时抓到的三个真实 bug（只有真实调用能发现）

**① `vite.config.ts` 代理目标不可达**
R0 写的是 `http://localhost:8080`（本机 jar 的端口），但实际开发用 docker compose：
后端只把**网关 8088** 映射到宿主机，宿主 8080 上什么都没有 → 登录返回 **HTTP 404**。
**R0 ~ R3 一直没发现**，因为那几轮都没跑过真实 API 请求。
已改为默认 8088，并支持 `VITE_API_TARGET` 覆盖（本机 jar 模式）。

**② API 路径缺 `/api` 前缀（R3 的真 bug）**
Vue 版用 axios，实例上有 `baseURL: '/api'`，所以各 api 文件写 `/auth/login` 即可。
**本项目用原生 fetch，没有那个隐式前缀**，而我照抄了 Vue 的写法 →
`fetch('/auth/login')` 打到 SPA fallback，**同样表现为 404**。
已在 `client.ts` 加 `API_BASE = '/api'` 并由 `buildUrl` 统一补齐。

> **这两个 bug 的教训**：我第一次探测时用 `curl 5174/api/auth/login` 拿到 200，
> 就以为链路正常——**那个测法绕过了 client，等于没测**。
> 换 UI 里真点一次登录才暴露。**验证必须走真实调用路径。**

**③ 401 分支硬编码 message，掩盖后端原话（真实的 UX 缺陷）**
实测：错误密码返回 **HTTP 401** + `{"code":2006,"message":"用户名或密码错误"}`。
而 `client.ts` 的 401 分支原本硬编码「登录已过期，请重新登录」——
用户密码输错却被告知"登录过期"，会以为登录状态坏了。
已改为**401 一律优先用后端 message**，两种 401 由后端的话自然区分：

| 场景 | 后端 message | 最终显示 |
| --- | --- | --- |
| 登录失败 | 用户名或密码错误 | **用户名或密码错误** |
| 令牌失效 | 认证失败 / 无 | 登录已过期，请重新登录 |

`verify-auth` 已加断言锁住它，防止回归。

> 附带确认：**登录失败确实是 401**（不是业务码 200 + 2006），
> 所以 `api/auth.ts` 里 `skipAuthRedirect: true` 是**必需的**——
> 否则用户正在登录页，会被"跳回登录页"，看起来像什么都没发生。

## 1. 实际目录结构

```text
web-react/src/
├── api/
│   ├── client.ts              统一 HTTP（fetch + 401 集中 + 错误归一 + AbortSignal）
│   ├── auth.ts                login / register / fetchProfile
│   ├── knowledge.ts           listKnowledgeBases（R3 只读）
│   └── types/
│       ├── common.ts          Result / PageResult / SUCCESS_CODE
│       ├── auth.ts            LoginRequest / AuthTokenResponse / UserInfo
│       └── knowledge.ts       KnowledgeBase
├── stores/
│   ├── authStore.ts           token / currentUser / status（五态）/ login / logout / initialize
│   └── knowledgeStore.ts      list / loading / error / loaded / fetchList / reset
├── hooks/
│   ├── useAuth.ts             useAuthInitializer + useAuth（派生 authenticated / isGuest）
│   └── useLogout.ts           performLogout（跨 store 清理）
├── lib/
│   ├── storage.ts             localStorage 统一封装（读写全包 try/catch）
│   └── errors.ts              ApiError / isApiError / normalizeError / getErrorMessage
├── app/
│   ├── RequireAuth.tsx        守卫 + RedirectIfAuthenticated
│   └── router/index.tsx       Layout Route（含鉴权层）
├── components/layout/UserMenu.tsx   改为读真实 currentUser
└── pages/LoginPage.tsx        从占位升级为真实登录表单
```

---

## 2. API Client 设计

### 唯一出口

页面不允许直接 `fetch` / `axios`。所有请求经 `api.get/post/put/delete`，
保证 token 注入、401 处理、错误归一化**只做一次**。

### 为什么用 fetch 而不是 axios

1. 少一个依赖（当前没有别处需要 axios 特性）；
2. `fetch` **原生支持 `AbortSignal`**，而请求取消是 R3 硬需求（§16）；
3. 响应解析路径简单——本项目响应永远是 JSON。

需要超时/重试/去重时再换 axios 也迟，届时**只需改这一个文件**。

### 请求约定

| 项 | 做法 |
| --- | --- |
| token 注入 | 集中 `buildHeaders()`；**没有 token 时不设 `Authorization`**（而不是设空串） |
| query 拼装 | `undefined` 自动剔除，键值都 `encodeURIComponent` |
| 响应解析 | 204/205 无 body 时返回 `null`；非 JSON 时安全回退 |
| 业务错误 | `code !== 0` → 抛 `ApiError{kind:'business', code, message}`，**message 用后端原话** |

---

## 3. Error Model

`lib/errors.ts` 定义 `ApiError extends Error`，带 `kind` 分类：

| kind | 触发 | 页面该怎么处理 |
| --- | --- | --- |
| `network` | fetch 抛错（连接失败、DNS、CORS） | 提示"检查网络"，**不重试业务** |
| `auth` | HTTP 401 | **集中处理**（清 token + 跳登录），页面不处理 |
| `forbidden` | HTTP 403 | 提示无权限，**不跳登录** |
| `business` | 2xx 但 `code !== 0` | 显示后端 message |
| `http` | 其他非 2xx | 显示状态码 |
| `aborted` | `AbortController` 取消 | **静默忽略**，不弹提示 |
| `unexpected` | 响应不符合 `Result` 约定 | 兜底提示，不泄漏结构 |

**为什么要分这么细**：混成一个 `Error` 的后果很具体——401 与 403 都变成"登录已过期"，
用户明明有权限却被反复踢回登录页。

提供 `normalizeError(e)` 与 `getErrorMessage(e)`，页面不再写
`catch (e) { console.log(e); 猜错误 }`（任务书 §14 明确禁止）。

---

## 4. Auth Store 设计

### 五态而不是 `isLoggedIn: boolean`

```text
unknown        还没初始化（store 刚创建）
initializing   正在用 token 换用户信息
guest          已确认没登录
authed         已确认登录
failed         登录失败（≠ guest：UI 要显示错误，不是"请登录"）
```

**为什么必须五态**：两态方案会制造一个**必然发生的假跳转**。
应用刚启动时 `isLoggedIn` 还是 `false`（还没读 token），
而 Protected Route 一看 false 就跳登录页——**哪怕 token 其实有效**。
表现为"每次刷新先闪一下登录页再跳回来"。

### 初始化流程（§20）

```text
App 启动 → useAuthInitializer()
  → 读 localStorage token
  → 没有：直接 guest
  → 有：GET /users/me 换真实用户
        → 再 GET /knowledge-bases?pageSize=1 验令牌在业务侧也可用
        → 列表失败但非 401：**仍算 authed**（用户能进工作区，只是空的）
        → 401：清干净退 guest
```

**为什么要第二步**：profile 通了不代表知识库接口通了。只验 profile 的话，
用户会进到工作区才看到第一个错误。

### 明确**不**放进 authStore

知识库列表（属 `knowledgeStore`）· 当前知识库 · Chat 会话 · Agent · 侧栏折叠（纯 UI）。

---

## 5. Token lifecycle

| 环节 | 实现 |
| --- | --- |
| 存 | `lib/storage.ts` 的 `setToken()`，键 `nexus.token`（沿用 Vue 侧，保证两套前端并存时行为一致） |
| 读 | `getToken()`，返回 `string \| null`（**不是空串**——两者语义不同） |
| 清除 | `removeToken()` / `clearAuthStorage()`（token + lastUsername） |
| 异常 | 读写**全部包 try/catch**：Safari 无痕、禁用 cookie、配额满时 `localStorage` 会抛。读失败→`null`，写失败→静默 |

**不用 localStorage 的其它替代**：不引入 `sessionStorage`、不引入 JS 侧的刷新令牌机制——
后端 `AuthTokenResponse` 只有 `accessToken` + `expiresIn`，没有 `refreshToken`，
凭空造一个就是"根据记忆猜后端"（任务书 §3 禁止）。

`expiresIn` **不用于自动过期**：多标签页场景下，A 页的定时器会把 B 页也踢掉。

---

## 6. 401 lifecycle（§15）

```text
响应 401
  → client 立刻 removeToken()（本地先清，不等回调）
  → 若 handling401 为 false：置 true → 调注入的回调 → finally 复位 false
  → 抛 ApiError{kind:'auth'}，让调用方知道
```

**single-flight**：`handling401` 标志保证并发 401 **只触发一次**登出与跳转。
不做的话，页面初始化并发 5 个请求会跳 5 次登录页。

**回调注入而不是 client 直接 import store**：
依赖方向固定为 **store → client**，避免循环依赖。
`authStore.ts` 末尾用 `setUnauthorizedHandler()` 接上。

**登录接口设 `skipAuthRedirect: true`**：登录失败后端也返回 401
（用户名或密码不对），这时不能触发全局跳转——否则用户正在登录页，
被"跳到登录页"，看起来像什么都没发生。

---

## 7. Current User 数据流（§8）

```text
后端 UserInfoResponse(id, username, nickname, email, roles)
  → api/types/auth.ts 的 UserInfo
  → authStore.currentUser   （来源：GET /users/me，或登录响应的 user）
  → useAuth().currentUser
  → UserMenu（头像首字母 + 显示名 + 邮箱 + 角色）
```

**一个类型系统在开发中真的抓到的错**：`AuthTokenResponse` **顶层没有 `username`**
（嵌在 `user` 里），我在 `authStore.login()` 里写了 `res.username`，
`tsc` 当场报 `TS2339: Property 'username' does not exist on type 'AuthTokenResponse'`。
这正是 Vue 侧「欢迎回来 undefined」的根因——**类型如实反映后端形状，写错就编译期报错**，
比 `user?.name ?? '用户'` 那种 UI 掩盖强得多。

`UserMenu` 的显示名优先级：`nickname → username → 邮箱前缀 → '?'`。
后端确实可能返回空 nickname，那属于合法情况，fallback 合理。

**R2 的硬编码 `D` / `demo` 已删除**（任务书 §19）。

---

## 8. Protected Route 数据流（§9）

```text
status unknown/initializing → <StateLoading>「正在恢复登录状态」（**不跳**）
status guest/failed        → <Navigate to="/login" replace state={{from}}>
status authed              → <Outlet /> → WorkspaceLayout
```

- 守卫是**布局路由**，4 个工作区页面被一起保护，**逐页包一定会漏**。
- `replace` 而非 `push`：登录页不该留在历史里（否则登录后点后退又回来）。
- `state.from` 记住来源，登录成功后跳回去。
- **`RedirectIfAuthenticated`**：已登录用户访问 `/login` 会被弹回工作区，
  否则"登录成功 → 手动后退"会回到登录页，看起来像"登出失败"。
- **404 放在 `RequireAuth` 之外**：未登录时访问乱路径应该看到 404 而不是登录页——
  否则"地址打错了"被报成"你没登录"。

---

## 9. Logout lifecycle（§11）

```text
performLogout()
  ① clearAuthStorage()        清 token + lastUsername
  ② authStore.handleUnauthorized()   清内存态（幂等）
  ③ knowledgeStore.reset()    清与用户强绑定的一切
  ④ 跳转 /login               由调用方 navigate
```

**为什么第 ③ 步不能省**：漏掉它的话，用户 A 退出、用户 B 登录，
界面会显示 A 的知识库列表——因为 `loaded` 还是 true，
再拉取会被 `if (loaded && !force) return` 挡掉。**这是数据串号，比任何样式 bug 都严重。**

**为什么 ④ 不在 store 里**：store 层不该知道路由。
`performLogout` 写成普通函数（不是 hook），
因为 401 被拦截时也要做同样的清理，而那条路径上**没有组件**。

---

## 10. Workspace Store

### 命名偏差（与任务书 §4/§12 不符，已按 §3 修正）

任务书要求管理"workspace 列表 / 当前 workspace / 切换"。**后端没有 `WorkspaceController`**：

- `WorkspacePO`（表 `workspace`）是**内部归属概念**，
  每个用户由 `workspaceService.requirePersonalWorkspace(userId)` 自动解析出唯一的"个人 workspace"；
- 前端既拿不到它的列表，也没有切换它的接口。

按 §3「API 必须以真实 Controller/DTO 为准」，**不虚构 workspace API**。
本项目对应关系：

```text
任务书的 workspace  ≡  用户看得见的 KnowledgeBase 列表
```

字段名沿用后端 DTO（`api/types/knowledge.ts` 逐字段抄自 `KnowledgeBaseResponse.java`），
避免造出第二套定义（§17 禁止）。

### 与 UI 状态严格分离

`sidebarCollapsed` **没有**进 `knowledgeStore`——它由 `AppSidebar` 组件自己管 + localStorage。
放进业务 store 就等于把"UI 偏好"与"业务数据"混在一起。

R3 只做**列表**（创建/删除属 R6）：

```ts
fetchList({ force?: boolean, signal?: AbortSignal })   // force=true 用于退出后
reset()                                                  // 退出/换账号时必须调
```

---

## 11. AbortSignal 支持（§16）

`RequestOptions.signal` 已贯通到 `fetch`。
`fetch` 抛出的 `AbortError` 被归一为 `ApiError{kind:'aborted'}`，
`knowledgeStore.fetchList` 遇它**静默返回**（不显示错误）。

**不要用 `isMounted` 布尔代替**：那只是"忽略结果"，
请求仍会跑完、仍占连接与后端资源；abort 是真的停掉。

本阶段没有业务页面用它，但能力已就绪（搜索 / Chat / 文件上传会需要）。

---

## 12. 测试结果

### `scripts/verify-auth.mjs`（25 项全过）

```text
① 未登录访问受保护路由 → 自动跳 /login            PASS
② 登录表单（用户名/密码/提交）                     PASS
   空表单提交被前端校验拦下                        PASS
③ 错误密码
   被拒且未进入工作区                              PASS
   提示里没有 undefined / null / NaN               PASS
   提示显示后端原话「用户名或密码错误」             PASS   ← 锁住 bug ③
   没有把"密码错"说成"登录过期"                    PASS
④ 正确密码
   登录成功进入工作区                              PASS
   Header 显示用户名「demo」                       PASS
   用户名与登录输入一致（证明来自后端）             PASS
   头像首字母由真实用户名派生（D）                  PASS
⑤ 刷新恢复
   token 已落 localStorage                         PASS
   刷新后停留在工作区（未被踢回登录页）             PASS
⑥ 退出登录
   回到 /login                                     PASS
   token 已从 localStorage 清除                     PASS
   refresh 之后仍然是 /login（没有自动恢复）        PASS
⑦ 401 生命周期
   无效 token 被清掉                               PASS
   没有 redirect 死循环（history=2）                PASS
   最终停在 /login                                 PASS
⑧ 404 与守卫边界（非法路径落到 404）               PASS
⑨ console error = 0 / page error = 0              PASS
结论：PASS 25 / FAIL 0 / SKIP 0
```

### `scripts/verify-layout.mjs`（R2 回归，退出码 0）

```text
⓪ 认证准备（真实登录拿 token 并注入）             PASS
① 外壳结构 / ② 三条导航 / ③ 导航高亮（4 条路径）   PASS
④ Sidebar 折叠 240→64px + aria-label 翻转
   + localStorage + 刷新保持 + 切换路由保持        PASS
⑤ Header 标题（4 条路径）                          PASS
⑥ 404 + 返回链接                                   PASS
⑦ 键盘（Tab 可达 / Enter 激活）                     PASS
⑧ 用户菜单（dialog 内 / ESC 关）                    PASS
⑨ 响应式 1920/1366/1024 横向溢出均 0px             PASS
⑩ console error = 0 / page error = 0              PASS
结论：PASS 20 / FAIL 0，退出码 0
```

**R3 的鉴权层没有破坏 R2 的任何布局行为。**

### 探针自身的三处错误（记录下来，别重复犯）

1. **用户名断言写成"不等于 demo"** —— 而测试账号真名就叫 `demo`，
   于是把正确显示判成失败。**排除具体字符串是脆弱的**；改为"与登录输入一致"。
2. **401 用例用 `page.goto` 重新进 `/`** —— **hash 路由下 goto 只改 hash，
   不重新加载页面**，`initialize()` 根本没重跑，token 无人清理。
   改用 `page.reload()` 才对。**hash 路由的测试里这是最容易踩的坑。**
3. **console error 断言没排除故意触发的 401** —— 第 ⑦ 组用例就是靠它触发 401 的，
   而浏览器把任何 4xx 都记成 console error。断言要区分"预期内的错误"。

### `npm run build`

✅ 通过，**未使用** `any` / `@ts-ignore` / 关类型检查等任何绕过手段。

## 13. 实际修改文件列表

| 类型 | 文件 |
| --- | --- |
| **新增（唯一文档产物）** | `docs/react-auth-report.md` |
| 新增源码 | `api/client.ts`、`api/auth.ts`、`api/knowledge.ts`、`api/types/{common,auth,knowledge}.ts`、`stores/{authStore,knowledgeStore}.ts`、`hooks/{useAuth,useLogout}.ts`、`lib/{storage,errors}.ts`、`app/RequireAuth.tsx`、`pages/LoginPage.tsx` |
| 重写 | `app/router/index.tsx`（Layout Route + 鉴权层）、`components/layout/UserMenu.tsx`（真实用户）、`main.tsx`（挂初始化器） |
| 修改 | `scripts/verify-layout.mjs`（加认证准备 + 三态） |
| 新增脚本 | `scripts/verify-auth.mjs` |

**未动**：`web/**`（Vue 前端）、`nexus-*/**`（后端）、`web/scripts/**`（旧 E2E）、
`deploy/**`、`docs/update.txt`。**未执行任何 git 写操作。**

---

## 14. 未完成事项

| # | 事项 | 归属 |
| --- | --- | --- |
| 1 | ~~6 项登录链路未验证~~ **已验证**（verify-auth PASS 25 / SKIP 0） | ✅ |
| 2 | ~~R2 Layout 回归未确认~~ **已验证**（verify-layout 退出码 0） | ✅ |
| 3 | ~~`initialize()` 并发~~ **已解决**：加了 single-flight | ✅ |
| 4 | 注册流程只有 API（`register()` 后需再 `login()`），UI 未做 | R5 |
| 5 | 个人资料 / 修改密码是占位行 | R5 |
| 6 | `api/types/sse.ts` 目录与约定已确立，**未实现** | R3 起后续阶段 |
| 7 | 知识库只有列表 API；创建/删除/导入/文档/检索全未做 | R6 |
| 8 | 无 `refreshToken`（后端没有） | — |
| 9 | shadcn CLI 仍不可用（把同级 `web/` 当 workspace 成员） | R1 已手写绕过，R2 未解 |

---

## 14.1 补跑步骤（本阶段唯一被阻塞的事）

Docker Desktop 未能自动启动（`Start-Process` 被沙箱阻止），所以下面这组验证必须手动执行：

```bash
# 1) 起整栈
cd deploy && docker compose up -d
# 等到 8 个容器都是 healthy（首次含 ES 镜像构建，可能几分钟）
docker ps

# 2) 起前端 dev server（另开一个终端）
cd web-react && npm run dev

# 3) 补跑两个验证脚本
cd web-react
node scripts/verify-auth.mjs     # 预期：PASS 14 / FAIL 0 / SKIP 0
node scripts/verify-layout.mjs   # 预期：PASS 20 / FAIL 0 / SKIP 0（退出码 0）
```

**通过标准**：`verify-auth` 的 SKIP 归零（6 项登录链路真的跑过），
且 `verify-layout` 退出码为 **0**（不是 2）——那才证明 R3 的守卫**没有破坏 R2 的 Layout**。

若 `verify-auth` 报错，最可能的三个原因：

| 现象 | 原因 |
| --- | --- |
| 登录成功但进不了工作区 | `demo` 账号未种入 → `node scripts/seed-demo.mjs` |
| 404 页面出现但没进工作区 | 后端仍在启动，`/users/me` 连不上被当成网络错误 |
| `SKIP 后端不可达` | `API_BASE` 不对（默认 `http://localhost:8088`） |

## 14.2 已补上的并发保护

`initialize()` 原先在 StrictMode 下会**并发发两次 `GET /users/me`**。现在：

```ts
let inflight: Promise<void> | null = null   // 模块级，不进 store

async initialize() {
  const token = getToken()
  if (!token) { /* guest */ return }
  if (inflight) return inflight                              // 正在跑 → 复用
  if (get().status === 'authed' && get().token === token) return  // 令牌没变且已完成
  const run = (async () => { /* 原逻辑 */ })()
  inflight = run
  try { await run } finally { inflight = null }               // 失败也释放，允许重试
}
```

三个设计点：

1. **`inflight` 在模块级，不在 store state 里** —— 它不是业务状态（不进 devtools、
   不持久化、不该被组件订阅），只是一把"正在跑"的锁。
2. **短路判据是 `token` 不是 `status`** —— `logout()` 会把 status 置为 `guest`，
   但那之后若再调 `initialize()`（例如登录成功后），仍应能正常重新初始化。
3. **`finally` 里释放** —— 网络故障后要允许重试（用户刷新即可恢复）。

## 15. 下一阶段建议

**Phase R4（Zustand + Hooks 深化）**，但顺序上我建议先做两件小事：

1. **起后端补跑验证**（10 分钟的事，却能把上面 #1 #2 从"未验证"变成"已验证"）。
2. **决定 `initialize()` 的并发保护**（#3）。它现在只是浪费一次请求，但一旦
   后端给 `/users/me` 加了副作用（记活跃时间之类），就会变成真 bug。

R4 的重点应是：把 `useAsyncState` 抽成通用 hook、给知识库/Agent 页面准备数据流，
而不是继续加 store —— Phase B §14.2 已经把"跨页面共享且单一真相 → Store"的判据定死了，
现在三个 store 已经覆盖已知需求，**再加就是过度 Store 化**（任务书 §13 的警告）。
