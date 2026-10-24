# API 设计（Prisdvl-Nexus）

> STEP 1 设计稿。路径遵循需求文档给定的 `/api/...` 风格；版本演进通过 `Api-Version` 请求头（可选）而不是改路径，避免早期过度设计。

---

## 1. 通用约定

### 1.1 统一返回体

```json
{ "code": 0, "message": "success", "data": {} }
```

| 字段 | 说明 |
| --- | --- |
| `code` | 业务码，`0` 表示成功，非 0 表示业务失败（见 1.3） |
| `message` | 人类可读信息，直接可展示；i18n 时按 `code` 映射 |
| `data` | 业务数据；失败时为 `null` |

**Request ID 不放 body，放响应头**：`X-Request-Id`（由过滤器生成，贯穿日志与 `obs_ai_request`），这样"观测字段"不会污染业务契约。

### 1.2 HTTP 状态码与业务码的分工

| 层 | 表达方式 |
| --- | --- |
| 传输/协议层 | 用 HTTP 状态码：`400` 参数格式错、`401` 未认证、`403` 无权限、`404` 资源不存在、`409` 冲突、`413` 文件过大、`429` 限流、`500` 服务内部错误、`503` 依赖不可用 |
| 业务层 | 用 body 里的 `code`，例如"知识库名称已存在"= `3002`，HTTP 仍是 `200`/`409` 按语义选择 |

统一由 `GlobalExceptionHandler` 转换，业务代码只抛 `BusinessException(ErrorCode, message)`。

#### 失败归因表（Phase 5.2）

"模型调用失败"曾经是所有生成期故障的唯一说法——撞到工具轮数上限、密钥过期、网关超时，
用户看到的都是同一句话。归错不会让程序崩，只会让所有人沿着错误方向排查，因此判定收敛到
**一处**：`FailureClassifier`（`nexus-ai`），下列入口共用它，所以同一个故障在所有入口
报的是同一个码、同一句话：

| 判定顺序 | 异常 | 码 / HTTP | retryable | 给用户的话 |
| --- | --- | --- | --- | --- |
| 1 | `ToolCallLimitExceededException`（分单工具 / 合计两种） | `5002` / `500` | ✗ | 哪个工具、上限几次（确定性上限，重试必然再撞） |
| 2 | `BusinessException`（任何域） | 它自己的码 | 按域 | 它自己的文案（已判定过的事实，不被下面的猜测覆盖） |
| 3 | 401/403 类（`*Unauthorized*`、`*Authentication*`…） | `4007` / `502` | ✗ | 密钥无效或已过期，请联系管理员 |
| 4 | 超时（`*Timeout*`、`SocketTimeoutException`） | `4002` / `504` | ✓ | 模型响应超时，请稍后重试 |
| 5 | 其余未知故障 | `4001` / `503` | ✓ | 模型调用失败，请稍后重试 |

- 判定沿 cause 链上溯（Spring AI 会包 `RetryExhausted → WebClientRequest → ConnectException`），
  只看最外层等于永远落到第 5 行。
- 第 3 行刻意不匹配 `AccessDenied`：在 Spring Security 里那是"**我们的**接口被越权访问"，
  与"上游拒绝我们的密钥"是两件相反的事。
- **同一个故障在各入口的口径**（2026-10-01 真机实测，无效 Key）：`/api/agent/ask`、
  `/api/chat/conversations/{id}/messages` 均返回 `502 / 4007`；SSE 的 `error` 帧
  `{"code":4007,"retryable":false,"message":"…"}`。修复前这三处分别是
  `500 / 9999 系统内部错误`、`502 / 4001` + **上游原文**、`4001 / retryable=true`。
- 生成失败**不再是"一种回答"**：同步 RAG（`POST /api/knowledge-bases/{id}/ask`）此前会把
  `"生成回答失败：" + ex.getMessage()` 当成 200 的答案返回，并落进 `kb_qa_record`；
  现在抛业务异常，且不落档（留档只记真实发生过的问答）。
- `chat_message.error_msg` 存的是**给用户的那句话**（不是上游原文），它会经
  `GET /api/chat/messages/{id}` 返回给前端，因此原文只留在日志里、靠 `requestId` 关联。

### 1.3 错误码规划

| 段 | 域 | 示例 |
| --- | --- | --- |
| `0` | 成功 | `0 success` |
| `1xxx` | 通用/参数 | `1001 参数校验失败`、`1002 请求体格式错误`、`1003 不支持的排序字段` |
| `2xxx` | 认证授权 | `2001 未认证`、`2002 Token 已过期`（**当前无抛出点**：`RestAuthenticationEntryPoint` 不区分过期与非法）、`2003 无权限访问该资源`、`2004 账号被禁用`、`2005 用户名已被占用`、`2006 用户名或密码错误`、`2007 邮箱已被占用` |
| `3xxx` | 知识库/文档 | `3001 知识库不存在`、`3002 知识库名称重复`、`3003 文档不存在`、`3004 DocumentParseFailed 文档解析失败`、`3005 文件类型不支持`、`3006 文件超出大小限制`、`3007 ZIP 结构非法`、`3008 导入任务不存在` |
| `4xxx` | AI 模型/嵌入/向量 | `4001 ModelUnavailable 模型不可用`、`4002 ModelTimeout 模型超时`、`4003 TokenLimitExceeded 超出上下文限制`、`4004 EmbeddingFailed 嵌入失败`、`4005 VectorStoreUnavailable 向量库不可用`、`4006 未配置对应逻辑角色的模型`、`4007 ModelAuthFailed 模型服务鉴权失败`（密钥被上游拒绝；**不可重试**，与 4001 的区别见 1.2 的错误归因表）、`4009 EmbeddingFrozenMismatch 知识库冻结的嵌入模型与当前生效配置不一致`（**409**，可修复的资源冲突，与 4006 的区别见 §4 的检索预检说明） |
| `5xxx` | 工具/Agent/MCP | `5001 ToolExecutionFailed 工具执行失败`、`5002 AgentMaxIterationsExceeded 超出最大步数`、`5003 人工确认被拒绝`、`5004 工具无权限`、`5005 MCP 服务不可用` |
| `6xxx` | 工作流/评测 | `6001 工作流定义非法`、`6002 工作流节点执行失败`、`6003 评测集为空`、`6004 评测裁判模型不可用` |
| `7xxx` | 限流/系统 | `7001 请求过于频繁`、`7002 配额已用尽`、`9999 系统内部错误` |

> 与需求文档示例的差异：需求里写 `5001 AI model unavailable`。这里把 AI 模型类错误统一放在 `4xxx`（AI 域），`5xxx` 留给工具/Agent，理由是错误码分段要能一眼看出"哪个域炸了"，评测与观测才好归类。若你更希望严格照抄示例，这一步可以改。

### 1.4 分页与排序

请求：`?pageNum=1&pageSize=20&sort=createdAt,desc`
响应：`{ "list": [], "total": 137, "pageNum": 1, "pageSize": 20, "pages": 7 }`

`pageSize` 上限 100（超出直接截断为 100，不报错）。

### 1.5 幂等与上传

| 场景 | 约定 |
| --- | --- |
| 创建类接口 | 支持 `Idempotency-Key` 头；同 key 10 分钟内返回首次结果（Redis 存结果摘要） |
| 文件上传 | `multipart/form-data`；单文件 ≤ 50 MB，ZIP ≤ 200 MB（可配）；一次最多 200 个文件。超过 `spring.servlet.multipart.max-file-size` 时返回 `413` + code `3006`，文案里带**配置的实际值**（形如"单文件上限 200MB，单次请求上限 220MB"），不写死数字 |
| 长任务 | 一律返回 `taskId`，用 `GET /api/import-tasks/{id}` 或 SSE 获取进度 |
| 流式接口 | `POST` + `Accept: text/event-stream`（不用 `EventSource`，因为它无法带 `Authorization` 头） |

### 1.6 序列化契约：`Long` 一律以 JSON **字符串**返回

**这是全项目统一的约定，不是单接口特例。**

雪花ID（如 `2104750903666438145`）是 19 位十进制数，**超出 JavaScript `Number.MAX_SAFE_INTEGER`（2^53−1 = 9007199254740991）**。若按数字返回，前端解析后会变成 `2104750903666438144` —— 表现为"拿到的 id 查不到数据"，**且不报错**，是最难排查的一类 bug。

**实现**：`nexus-app/.../infra/jackson/LongToStringJacksonComponent.java`
（Boot 4 的 Jackson 3 写法：`@JacksonComponent` + `ValueSerializer<Long>`）。

| Java 类型 | JSON 形态 | 前端类型 |
| --- | --- | --- |
| `Long`（包装类型） | `"2104750903666438145"` | `string` |
| `int` / `long`（基本类型） | `123` | `number` |
| `Integer` / `Double` | `123` / `0.93` | `number` |

⚠️ **DTO 编写约定（务必遵守）**：既然 `Long` 会变成字符串，
**计数类字段用 `int` / `long` 基本类型**（保持数字形态），**只有 ID 类字段才用包装类型 `Long`**。
否则计数字段会意外变成字符串。

> ⚠️ 已知缺口：`LongToStringJacksonComponent` 的 javadoc 提到有一个
> `LongToStringSerializationTest` 断言这条约定，但**该测试在当前仓库里不存在**（已核实）。
> 即这条约定目前**没有自动化测试守护**，改DTO 时需人工留意。
>
> 迁移自已删除的 `docs/project-tutorial.md`（2026-10-10）。

---

## 2. 认证与账号（Phase 0；账号安全 2026-10-02；Refresh / 会话 Phase 4）

### 2.1 已实现

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/register` | 注册（用户名 + 密码），密码 BCrypt 存储；按**来源 IP** 限流（10 次/小时，超限 `429/7001`） |
| POST | `/api/auth/login` | 登录 → `accessToken`（默认 30m）+ `refreshToken`（默认 7d，opaque）+ `expiresIn` + `user`；连续失败冻结 → `429/7001` |
| POST | `/api/auth/refresh` | 用 refresh 换新的 access + refresh（**rotation**：旧 refresh 立即失效）；公开端点 |
| POST | `/api/auth/logout` | 撤销当前 refresh 会话（body：`refreshToken`）；access 不立刻删，靠 `exp` / `token_version`；公开端点 |
| GET | `/api/users/me` | 当前用户信息与角色 |
| PUT | `/api/users/me` | 修改资料（昵称 / 邮箱） |
| PUT | `/api/users/me/password` | 修改密码（校验原密码；成功后 `token_version+1` **且**撤销全部 refresh 会话） |
| GET | `/api/users/me/sessions` | 当前用户未撤销且未过期的设备会话列表（可选头 `X-Refresh-Token` 标记 `current`；**不回传 token/hash**） |
| DELETE | `/api/users/me/sessions/{id}` | 退出指定设备（只能删自己的；否则 `404/2010`） |
| POST | `/api/users/me/sessions/revoke-all` | 退出全部设备（批量 revoke） |
| GET | `/api/users/me/quota` | 当前用户当日额度（只读，用于界面上"今日 N/M 次 · x/y tokens"那一行） |
| GET | `/api/users/me/storage` | 当前用户存储占用（只读）：`usedBytes` / `documentCount` / `knowledgeBaseCount` / `totalLimitBytes` / `perKnowledgeBaseLimitBytes`；上限 **≤ 0 表示不限**（前端据此不画进度条）。口径见 `security.md` §6.4 |
| GET | `/api/users/me/stats/daily-tokens` | 当前用户逐日 Token 消耗（只读，用量页热力图数据源）：`{ from, to, days: [{ day, tokens }] }`，`days` 是**连续**日轴（无消息的日子 `tokens=0`），`tokens = chat_message.total_tokens` 按自然日求和（流式中断未写回用量的消息计 0）。查询参数 `days`（默认 **365**，只接受 **1–365**，越界 `1001`）；用户维度取自令牌，不接受前端传 userId |
| GET | `/api/stats/summary` | 工作台汇总（只读，首页一次拿四块）：`trend` 近 14 天 / `heat` 近 30 天的每日**消息条数**、`tokenHeat` 同 30 天窗口的每日 **Token 合计**、`activity` 最近 8 条（新建会话 / 导入任务）。缺失日期已补 0；用户维度取自令牌，不接受前端传 userId。详见下文 |
| GET | `/api/users/me/totp` | 两步验证状态：`{ enabled, recoveryCodesLeft, confirmedAt }`（只读，不返回密钥） |
| POST | `/api/users/me/totp/setup` | 生成候选密钥（**此时还没开启**）：`{ secret, otpauthUri, issuer, account, digits, periodSeconds }`；密钥仅本响应可见一次 |
| POST | `/api/users/me/totp/enable` | 用 `{ "code": "123456" }` 校验候选密钥，通过才置 `totp_enabled=1` 并返回 **`{ recoveryCodes: [...] }` 10 枚一次性恢复码**（只此一次） |
| POST | `/api/users/me/totp/disable` | 用 `{ "password": "******" }` 关闭两步验证并清空密钥/恢复码（换绑设备 = 先关再开） |
| GET | `/api/users/me/notify-channels` | 通知通道列表（服务端保存，跨设备同步）。每项含 `name` / `type`（`WEBHOOK`\|`EMAIL`）/ `target` / **`hasSecret`（永不返回密钥本身）** / `events` / `enabled` / 最近一次投递的 `lastStatus`·`lastError`·`lastAt` |
| POST | `/api/users/me/notify-channels` | 新建通道：`{ name, type, target, secret?, events, enabled? }`；`events` **至少一项**（`import.done` / `import.failed`，未知码静默丢弃），否则 `1001`；上限 `nexus.notify.max-channels`（默认 10），超限 `1001`；Webhook 目标保存时就过一遍协议与内网校验 |
| PUT | `/api/users/me/notify-channels/{id}` | 修改通道。`secret` 三态：**`null`/省略 = 不动已存密钥，`""` = 清除，非空 = 覆盖**；别人的 id 与不存在同样 `404/3010` |
| DELETE | `/api/users/me/notify-channels/{id}` | 删除通道（别人的 id 同样 `404/3010`） |
| POST | `/api/users/me/notify-channels/{id}/test` | 发一条测试消息，返回**更新后的通道对象**（`lastStatus` = `OK`\|`FAILED`，失败时 `lastError` 是可读原因）；投递失败本身不报错，仍回 `200` |
| GET | `/api/users/me/llm-config` | 当前用户自定义对话模型凭据（`baseUrl` / `modelName` / `enabled` / `hasApiKey` / **`apiKeyMasked`**；**永不返回明文**） |
| PUT | `/api/users/me/llm-config` | 保存同一份凭据。`apiKey` **留空 = 保留旧值**，填了才覆盖（上限 **320**，超限 `1001`）；`enabled=true` 时三项必须齐全，否则 `1001`。落库形态是 AES-GCM 密文（`v1:` 前缀），见 `security.md` 未实现清单 §11 |
| GET | `/api/users/me/embedding-config` | 当前用户自定义嵌入模型凭据（BYOK 路线 A）：`provider` / `modelName` / `baseUrl` / `dimensions` / `enabled` / `hasApiKey` / **`apiKeyMasked`**，另带平台约束 **`allowedModels`**、**`requiredDimensions`** 与 **`customEndpointAllowed`**（未配置时也返回，供前端渲染合法选项） |
| PUT | `/api/users/me/embedding-config` | 保存同一份凭据。`apiKey` 留空 = 保留旧值；`provider:model` 必须在 `allowedModels` 内，`dimensions` 省略即取 `requiredDimensions`、传了就必须相等——两者任一不合规都是 `1001`；`enabled=true` 时 provider / 模型名 / Base URL / Key 四项必须齐全。**`customEndpointAllowed=true` 时**，白名单之外的自定义 OpenAI 兼容端点是允许的：保存时用 `apiKey` 真调一次 `POST {baseUrl}/embeddings` 探针，**返回维度必须等于 `requiredDimensions`**（1024），否则 `1001` 并附实际维度；探针表达式调用失败（HTTP 4xx/5xx、超时、不可达）同样是 `1001`，拒绝的是"存下去以后才炸"。内网/本机地址默认拒绝（SSRF 防护），只有开发/测试 profile 打开 `allow-private-endpoints` 才放行。**它只改"以后用什么"，不迁移已建知识库**（见下） |

```http
POST /api/auth/login
{ "username": "lky", "password": "******", "totpCode": "123456" }

200 OK
{ "code": 0, "message": "success", "data": {
    "accessToken": "eyJ...",
    "refreshToken": "<opaque>",
    "tokenType": "Bearer",
    "expiresIn": 1800,
    "user": { "id": "...", "username": "lky", "nickname": "...", "email": null,
              "emailVerified": false, "roles": ["USER"] } } }
```

`totpCode` 只在**该账号已开启两步验证**时必填；未开启的账号传了也不校验（忽略）。
开启后缺码或码错一律 `401 / 2011`，前端据此把登录表单切到"输验证码"第二屏（见下），
**不要把 2011 当普通登录失败处理**：密码此时对了，只是第二因子没给对。

```http
POST /api/auth/refresh
{ "refreshToken": "<opaque>" }

200 OK
{ "code": 0, "data": {
    "accessToken": "eyJ...", "refreshToken": "<new-opaque>",
    "tokenType": "Bearer", "expiresIn": 1800 } }

# 已用过 / 已撤销 / 已过期 → 401 / 2001
```

**`/users/**` 全部没有 userId 参数**：身份只从 JWT 的 `sub` 取（`CurrentUser.requireId()`），
从请求参数或请求体接收 userId 是水平越权的典型来源（ADR-018 第 8 条）。

#### 修改资料 `PUT /api/users/me`

```http
PUT /api/users/me
{ "nickname": "老孔", "email": "lky@example.com" }
```

**字段语义是"半更新"，两条规则要一起看**：

- **不传某个字段（`null`）= 不修改**；
- **传空串 = 清空**（邮箱清空后落 `NULL`；昵称清空后界面回退显示用户名）。

一个值不能同时表示"不改"和"清空"，所以这两种意图在 JSON 里必须是不同取值。
若把"缺失"当成"清空"，前端少渲染一个输入框就会静默删掉用户数据。

错误：邮箱与其他账号重复 → `409 / 2007`（与 `2005` 用户名重复对称）。同一预检也加在注册入口上——
在此之前，用重复邮箱注册会撞唯一键 → `DataIntegrityViolationException` → `500/9999`，用户看到的是"服务器错误"。

#### 修改密码 `PUT /api/users/me/password`

```http
PUT /api/users/me/password
{ "oldPassword": "******", "newPassword": "******" }

// 原密码错误：400（不是 401）
{ "code": 1001, "message": "原密码不正确" }
```

**为什么原密码错误返回 `1001 / 400` 而不是 `2006 / 401`**：调用方已经用自己的令牌通过了认证，
此时回 401 会让前端全局拦截器（`frontend/src/lib/api.ts` 的响应拦截器）**立刻清 token 并跳登录页**——
把一个"打错一个字"的输入错误升级成会话中断。（此处也不构成账号枚举：攻击者必须已持有该账号的令牌。）
代价是登录失败（`2006`）与改密失败（`1001`）口径不同，这是**刻意的区分**：
登录 = 未认证；改密 = 已认证的参数错误。（原 Vue 版 E2E）`docs/archive/web-vue/scripts/verify-security.mjs` 有一条断言专门守这个分支，该脚本已随 `web/` 归档。

**改密成功后本次会话也会失效**：`sys_user.token_version` 自增（access JWT，见 ADR-020），
并批量撤销该用户全部 refresh 会话（`sys_user_session`）。这是"改密码 = 所有设备下线"的正确语义，
前端必须明说「密码已修改，请用新密码重新登录」并主动跳登录页，而不是等下一个请求撞 401。
忘记密码确认成功同样：`token_version+1` + 撤销全部 refresh 会话。

#### 设备会话

列表项形状：`{ id, device, ip, lastUsedAt, createdAt, current }`。  
前端设置页「会话与设备」调用上述三个 sessions 接口；`api.ts` 在 access 401/2001 时用单飞 Promise 调 `/auth/refresh` 并重放原请求。

#### 两步验证（TOTP）

```http
GET  /api/users/me/totp
200 OK
{ "code": 0, "data": { "enabled": true, "recoveryCodesLeft": 7,
                       "confirmedAt": "2026-10-21T09:12:00" } }

POST /api/users/me/totp/setup
200 OK
{ "code": 0, "data": {
    "secret": "JBSWY3DPEHPK3PXP",
    "otpauthUri": "otpauth://totp/Prisdvl%20Nexus:lky?secret=JBSWY3DPEHPK3PXP&issuer=Prisdvl%20Nexus&digits=6&period=30",
    "issuer": "Prisdvl Nexus", "account": "lky", "digits": 6, "periodSeconds": 30 } }

POST /api/users/me/totp/enable
{ "code": "123456" }
200 OK
{ "code": 0, "data": { "recoveryCodes": ["3f9a-k2","...十枚..."] } }

POST /api/users/me/totp/disable
{ "password": "******" }
200 OK
```

- 码错、已过期、恢复码已用过或不属于该账号 → 一律 `401 / 2012`（**不区分**是哪种：分开报等于告诉攻击者"这段看起来像恢复码"）。
- 未启用就想关闭（或读状态时本就没开）→ `409 / 2013`；已启用还去 `setup`/`enable` → `409 / 2014`（换密钥必须先关）。
- `.env`/环境变量必须提供 `NEXUS_SECURITY_CRYPTO_KEK`：TOTP 密钥用同一把 KEK 做 AES-GCM，缺失时凭证加解密 fail-closed（与 BYOK 同口径）。
- 登录时**缺码**（`totpCode` 为空）不记登录失败次数（用户只是还没输第二步）；**码错**计入 §4 的失败冻结。前端据此把 2011 当"切第二屏"信号，2012 当"这一格填错了"。
- `secret` 落库前用与 BYOK 同一把 KEK 做 AES-GCM（`CredentialCipher`，`v1:` 前缀），**任何接口都不回读密钥**；
  恢复码只存 `SHA-256`，用一次即写 `used_at`（`UPDATE … WHERE used_at IS NULL`，并发下也只会成功一次）。
- 校验窗口 `±1` 个时间步（30 s/步，见 `nexus.auth.totp.window`），容忍客户端时钟偏差；重放保护靠"候选密钥一次性 + 恢复码一次性"，
  不额外记已用码（同窗口内复用同一码不构成提权：攻击者仍需先已知密码）。
- 登录侧见上文 `POST /api/auth/login` 的 `totpCode` 与 `2011`。

#### 通知通道 `notify-channels`

```http
GET /api/users/me/notify-channels
200 OK
{ "code": 0, "data": [{
    "id": "2112808352084967426", "name": "团队群机器人", "type": "WEBHOOK",
    "target": "https://example.com/hook", "hasSecret": true,
    "events": ["import.done", "import.failed"], "enabled": true,
    "lastStatus": "OK", "lastError": null, "lastAt": "2026-10-21T15:35:14" }] }

POST /api/users/me/notify-channels/{id}/test
200 OK
{ "code": 0, "data": { "id": "211…", "lastStatus": "FAILED",
                       "lastError": "Webhook 请求失败：ConnectException", "…": "…" } }
```

- **投递是有真实触发源的**：导入任务结束（`import.done`，含 `PARTIAL` 之外的成功）与导入未完全成功（`import.failed`，含 `PARTIAL`）会各投一次；`enabled=false` 或事件不在 `events` 里的通道不投。
- **投递失败不改业务结果**：`dispatch` 里 catch 一切异常并写 `last_*`，导入任务不会因为用户填了个挂掉的 Webhook 而变成失败。
- **密钥只进不出**：`secret` 用 `CredentialCipher`（与 TOTP/BYOK 同一把 `NEXUS_SECURITY_CRYPTO_KEK`，AES-GCM `v1:` 前缀）落库；设置了密钥的 Webhook 请求带 `X-Nexus-Signature: sha256=<HMAC-SHA256(secret, body)>`，接收方可校验来源。口径见 `security.md` §6.6。
- Webhook 目标在**保存时**就校验协议（只 http/https）与目标地址；生产默认拒绝环回/私网/链路本地（SSRF），开发/测试 profile 由 `nexus.notify.webhook.allow-private-endpoints=true` 放行（本机调试 Webhook 需要它）。
- 邮件通道复用既有 `EmailSender` 的 `sendNotice`：SMTP 未配置时实现是 Console/未配置桩，`lastStatus` 会如实写成 `FAILED`，不会假装成功。

#### 当日额度 `GET /api/users/me/quota`

```http
GET /api/users/me/quota

200 OK
{ "code": 0, "data": {
    "requestUsed": 12, "requestLimit": 200,
    "tokenUsed": 3421, "tokenLimit": 200000,
    "resetAt": "2026-10-03T00:00:00" } }
```

`requestUsed` 可能**大于** `requestLimit`：被拒的请求也占一次计数（否则连点没有成本），
界面展示时用 `min(已用, 上限)` 收敛。

#### 存储占用 `GET /api/users/me/storage`

```http
GET /api/users/me/storage

200 OK
{ "code": 0, "data": {
    "usedBytes": 1610612736, "documentCount": 12,
    "knowledgeBaseCount": 3,
    "totalLimitBytes": 5368709120,
    "perKnowledgeBaseLimitBytes": 1073741824 } }
```

- 口径是 `kb_document.file_size` 之和（`deleted = 0`）：**原始文件字节**，不是正文、不是向量体积。删文档即刻释放。
- `totalLimitBytes` / `perKnowledgeBaseLimitBytes` **≤ 0 表示该维度不限**，前端此时不画进度条（而不是画一条满格）。
- 只读快照，不占用每日请求配额；上限由 `nexus.storage.*` 配置，见 `security.md` §6.4。
- 单库占用随 `GET /api/knowledge-bases`（列表）与 `GET /api/knowledge-bases/{id}`（详情）的 `usedBytes` 一起下发，前端列表的占用条用的就是它。

#### 工作台汇总 `GET /api/stats/summary`

```http
GET /api/stats/summary

200 OK
{ "code": 0, "data": {
    "trend":     [ { "day": "2026-10-08", "count": 0 }, … 14 项 ],
    "heat":      [ { "day": "2026-09-22", "count": 3 }, … 30 项 ],
    "tokenHeat": [ { "day": "2026-09-22", "count": 1420 }, … 30 项 ],
    "activity":  [ { "type": "CHAT", "refId": "1842", "label": "面试复盘",
                     "at": "2026-10-21T09:14:02" }, … 8 条 ] } }
```

- `trend` / `heat` / `tokenHeat` **都是连续日轴**：日期升序、末项是今天，没有数据的那天 `count = 0`。SQL 只返回有行的日期，补零在服务端做——不补的话前端折线会把"那天没消息"画成"那天不存在"，视觉上等于把稀疏数据拉平。
- `trend` 与 `heat` **口径相同**（当天 `chat_message` 条数），差别只在窗口（14 / 30 天）；`tokenHeat` 是同一 30 天窗口里每天的 `total_tokens` 合计（流式中断未写回用量的消息按 0 计）。
- `activity` 是两路合并后按 `at` 倒序取 **8 条**：`type=CHAT` 来自 `chat_conversation`（`deleted = 0`，`label` = 会话标题），`type=IMPORT` 来自 `kb_import_task`（`label` = 源文件名）；`refId` 按 §1.6 以**字符串**返回；`at` 直接取 `LocalDateTime.toString()`（无时区，秒为 0 时会省略秒段，别按定长解析）。
- 只读快照，不占每日请求配额；用户维度由服务端从令牌解析（`CurrentUser.requireId()`），**不接受前端传 `userId`**。
- 需要整年序列时用 `GET /api/users/me/stats/daily-tokens?days=365`（独立接口）：把 365 天塞进 summary，等于让每个打开工作台的人都多读 11 个月的消息行。
- 索引：本接口与 `daily-tokens` 共用同一查询形状（`user_id` + `created_at` 范围），由 `V28__idx_chat_message_user_created.sql` 的 `idx_msg_user_created (user_id, created_at)` 支撑。

### 2.2 规划中（尚未实现）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/users` | 管理员：用户列表（`ADMIN` 角色） |

> Phase 4 已落地：opaque refresh + rotation + `sys_user_session`（Flyway V21）+
> `/auth/refresh` / `/auth/logout` / `/users/me/sessions*`。
> Access 仍是 HS256 JWT（`sub` + `ver`）；**不做** jti 黑名单、不做 Redis 存 refresh。
> Token 仍在前端 `localStorage`（XSS 窗口见 ADR-021）；HttpOnly Cookie 迁移仍属后续。

---

## 3. 知识库与文档（Phase 2）

### 3.1 知识库

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/knowledge-bases` | 创建（name、description、icon、chunkStrategy、retrievalTopK、similarityThreshold） |
| GET | `/api/knowledge-bases` | 我的知识库列表（含 doc/chunk 统计、最近同步时间、`usedBytes` 原始文件占用） |
| GET | `/api/knowledge-bases/{id}` | 详情（含 embedding 模型、维度、切分参数、`usedBytes` 原始文件占用） |
| PUT | `/api/knowledge-bases/{id}` | 改名/描述/图标/enabled 状态/检索参数（**不允许**改 embedding 模型，见 ADR-008） |
| DELETE | `/api/knowledge-bases/{id}` | 删除知识库（级联删除文档、Chunk、向量、ES 文档） |
| GET | `/api/knowledge-bases/{id}/tree` | 目录树（供左侧栏：Java / Spring / Redis…） |
| GET | `/api/knowledge-bases/{id}/tags` | 标签云与计数 |
| GET | `/api/knowledge-bases/{id}/stats` | 文档数/Chunk 数/Token 数/向量数/失败数 |

平台化字段（Phase 2，均可选，缺省见下）：

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `workspaceId` | Long | 个人工作区 | 所属工作区，服务端解析后写入，**请求里不需要传** |
| `icon` | String(≤512) | null | 图标（URL 或 emoji） |
| `status` | String | `ENABLED` | `ENABLED` / `DISABLED` |
| `retrievalTopK` | Integer(1–50) | 5 | 该库的检索 TopK |
| `similarityThreshold` | Double(0–1) | 0.25 | 相似度阈值 |

> 上表默认值即 `nexus.retrieval.*`（Phase 3.2 起为唯一来源）。检索时的优先级是
> **请求参数 > 知识库配置 > 全局默认**；问答/检索接口的 `topK` **不传**即表示"用该知识库的 `retrievalTopK`"。


### 3.2 文档

文档一律挂在知识库下（嵌套路由），不存在跨库的 `/api/documents`——归属校验因此只有一处（路径上的 `{kbId}`），
下面所有 `{id}` 都是 `{documentId}`：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/knowledge-bases/{kbId}/documents` | **单文件上传（Phase 5.3 · P1-1）**：md/txt/pdf/docx → `taskId` |
| POST | `/api/knowledge-bases/{kbId}/vault` | 上传 Obsidian Vault ZIP → `taskId` |
| GET | `/api/knowledge-bases/{kbId}/documents` | 文档分页列表（按 folder/tag/status/关键词过滤） |
| GET | `/api/knowledge-bases/{kbId}/documents/{id}` | 文档详情（frontmatter、标签、标题树、图片、关联文档、Chunk/向量状态） |
| GET | `/api/knowledge-bases/{kbId}/documents/{id}/chunks` | Chunk 列表（含 heading_path、token 数、向量状态） |
| GET | `/api/knowledge-bases/{kbId}/documents/{id}/links` | 关联文档（WikiLink / Markdown Link，含未解析目标） |
| GET | `/api/knowledge-bases/{kbId}/documents/{id}/related` | 相关推荐（按关联度排序） |
| DELETE | `/api/knowledge-bases/{kbId}/documents/{id}` | **删除单篇文档（Phase 5.3 · P1-2）**，见下 |

#### 单文件上传（Phase 5.3 · P1-1）

```http
POST /api/knowledge-bases/1/documents   (multipart: file=笔记.md)
200 OK
{ "code": 0, "data": { "id": 9001, "taskType": "UPLOAD",
                       "status": "QUEUED", "totalFiles": 1, ... } }
```

- **复用整条异步导入链路**，没有第二条流水线：同一张 `kb_import_task`、同一个执行器、
  同一套状态机与重试端点。区别只有两处——
  - 任务 `taskType = UPLOAD`，文件名记在 `kb_import_task.source_name`（V15 新列；
    ZIP 导入是 `VAULT_ZIP` / 压缩包名）。该列**不随响应返回**，执行器内部用它确定导入目标；
  - **不做删除检测**：单文件上传的"清单"只有这一个文件，按它去比对会把库里其余文档全删掉
    （`VaultSyncPlanner` 的 `detectDeletions=false`，这个口子本来就是为它留的）。
- **扩展名校验在服务层**（`ParserRegistry.requireParser`），不是只在 Controller：
  这样别的调用方（未来的 MCP 工具）绕过 HTTP 也会拿到 `3005 文件类型不支持` +
  "当前支持哪些格式"，而不是一个含糊的"导入失败"。
- **不适用压缩包里那套 20MB 单条目上限**：直接上传是用户显式点名的文件，
  `VaultScanOptions.DEFAULT_MAX_FILE_BYTES` 是给 ZIP 里来路不明的条目设的防线，
  多一道 20MB 的闸只会让合法的 25MB PDF 被拒。服务层改用**压缩包整体上限**
  `DEFAULT_MAX_TOTAL_BYTES`（200MB）判定，与 Spring 的 `max-file-size` 对齐——
  校验放在服务里而不只靠 multipart，换一个调用方（MCP、批量导入）也成立。
  超限 → `3006`；Spring 层先拦下的则是 `413 / 3006`，见 1.2。
- 落库的文档 `sourceType = UPLOAD`（此前该枚举值不可达，详见 `docs/decisions.md` B.44）。
- **重复上传同名同内容**不会报错：增量同步判定 `SKIPPED`，并在 `items` 里给出一条
  "文件未变化，已跳过（未重新解析，也未重新嵌入向量）"——这正是 P1-3 要解决的问题。

#### 删除单篇文档（Phase 5.3 · P1-2）

```http
DELETE /api/knowledge-bases/1/documents/7
200 OK  { "code": 0, "data": null }
```

删除会连着清六处数据（少清哪一处都不会报错，只会留下看不见的残留）：

| 清理对象 | 语义 |
| --- | --- |
| 向量库（pgvector） | 按 `document_id` 清 |
| 检索索引（ES） | 按 `document_id` 清 |
| `kb_chunk` / `kb_document_tag` | **物理删除**（量大且无审计价值） |
| `kb_document_link` 的**出链** | 删文本档指向别人的边；**保留入链**——别人指向它的边会退化成"待创建的引用"，这是 Obsidian 语义 |
| 磁盘上的原始文件 | 不留垃圾 |
| `kb_knowledge_base` 的统计 | 重算 `docCount` / `chunkCount` / `totalTokens` |

- **外部清理在前，且失败即整体失败（fail-closed）**：向量/ES 不在 MySQL 事务里，
  无法回滚，所以顺序只能是"先外部、后 MySQL"。外部失败 → 事务回滚 → 文档原封不动，
  用户重试即可；反过来会在向量库里留下**检索命中却取不出正文**的孤儿，且没有任何入口能修。
- **同名路径的墓碑会被先清掉**：`uk_doc_kb_path (kb_id, source_path, deleted)` 是逻辑删除陷阱——
  同一路径第二次逻辑删除会写出第二行 `deleted=1` 而撞唯一键（传 → 删 → 再传 → 再删即可复现）。
- 错误：库不属于该用户 → `3001`；文档不在该库里（或不存在）→ `3003`。两者都是 404，不泄露资源存在性。

### 3.3 导入任务

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/import-tasks/{taskId}` | 任务状态与进度（总数/新增/更新/跳过/删除/失败/百分比） |
| GET | `/api/import-tasks/{taskId}/items` | **逐文件明细（Phase 5.3 · P1-3）**，见下 |
| POST | `/api/import-tasks/{taskId}/retry` | 只重试失败项（复用暂存包） |

> `/stream`（SSE 实时进度）与 `/cancel` 在 API 设计文档里规划过，但**没有实现**——
> 前端目前靠 1.2s 轮询 `GET /api/import-tasks/{taskId}` 拿终态。规划与实际不符的以本节为准。

#### 任务状态机（`ImportTaskStatus`）

```
QUEUED → RUNNING → ┬→ DONE       全部成功
                   ├→ PARTIAL    部分成功（有失败项）
                   ├→ FAILED     全部失败
                   └→ CANCELLED  被取消
```

**终态是 4 个**：`DONE` / `PARTIAL` / `FAILED` / `CANCELLED`。
轮询判断"是否已结束"时**必须把这 4 个都算作终态** —— 只认 `DONE` / `FAILED`，
会让"3 成功 2 失败"这类任务**永远转圈**。

⚠️ `PARTIAL` 是**刻意保留**的（见 `nexus-knowledge/.../model/ImportTaskStatus.java` 源码注释）：
批量导入时"部分成功"远比"全失败"常见，用独立状态把它与"全部成功"区分开，
用户才知道是否需要处理失败项。**不要在解析时把它归并进 `DONE` 或 `FAILED`。**

另：`PARTIAL` 与 `FAILED` 都可被抢占重试（见 `ImportTaskExecutor`）。

> 迁移自已删除的 `docs/project-tutorial.md`（2026-10-10）。

#### 逐文件明细（Phase 5.3 · P1-3 / P1-4）

```http
GET /api/import-tasks/9001/items?action=FAILED&limit=100
200 OK
{ "code": 0, "data": [
    { "id": 55012, "sourcePath": "坏文件.pdf", "action": "FAILED",
      "documentId": null, "chunkCount": 0, "durationMs": 12,
      "message": "解析失败：不是有效的 PDF（missing %PDF header）",
      "createdAt": "2026-10-01T19:20:31" } ] }
```

- **这张表此前只有写入方、没有任何读接口**：于是"失败 3 个"是一个没有下文的数字，
  用户只能去翻服务端日志——而 `kb_import_task_item` 存在的全部意义就是回答"为什么这篇没进来"。
- `action` 可选过滤，大小写不敏感（`FAILED` / `failed` / `SKIPPED` / `skipped`）；
  不传则返回全部。`limit` 默认 100，**收敛到 1–500**（0 抬到 1，超大值压到 500）——
  一个 Vault 会有上千行明细，不能让一次查询全拖回来。
- **`SKIPPED` 现在真的会被写入了**（此前是死枚举）：扫描期被跳过的条目
  （`.pptx`、隐藏目录、空文件…）以前只进计数、不落明细，
  于是用户最常问的"我的 `.pptx` 为什么没进来"恰恰是明细答不了的那一类。现在每条都带上原因。
- **一处刻意的不对称**：整包同步时"内容未变化"的文件**只计数、不逐条落明细**——
  否则一次全量重导会插入上千行同样的记录（`VaultIncrementalSyncTest` 守着这条）。
  单文件上传只有一行，因此会如实给出"文件未变化，已跳过"。
  界面必须解释这个差异（`task.skippedCount` 可能大于明细条数），不能让用户以为明细丢了。
- 归属校验与 `GET /api/import-tasks/{taskId}` 同一道：别人的任务一律 `3008`，
  不泄露文件清单。

```http
POST /api/knowledge-bases/1/vault   (multipart: file=MyVault.zip)

200 OK
{ "code": 0, "message": "success", "data": { "id": 9001, "taskType": "VAULT_ZIP",
                                             "status": "QUEUED", "totalFiles": 380 } }

GET /api/import-tasks/9001
{ "code": 0, "data": {
    "id": 9001, "status": "RUNNING", "progress": 42,
    "totalFiles": 380, "createdCount": 120, "updatedCount": 15, "skippedCount": 240,
    "deletedCount": 5, "failedCount": 0,
    "totalChunks": 5120, "errorMsg": null, "startedAt": "2026-09-24T02:11:00Z" } }
```

---

## 4. 检索与调试（Phase 3）

### 4.1 已实现：知识库内检索（带元数据过滤）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/knowledge-bases/{id}/index` | 为待索引切片建索引（幂等，`batchLimit` 默认 500） |
| POST | `/api/knowledge-bases/{id}/index/recreate` | 重建索引：新建物理索引 → 原子切别名 |
| GET | `/api/knowledge-bases/{id}/search` | 检索：`q`（必填）、`mode`、`topK`、`tags`、`folder`、`documentId`。返回 `{ hits, emptyReason }`，0 命中时 `emptyReason` 说明原因 |

查询参数语义（Phase 3.3）：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `q` | ✅ | 查询串 |
| `mode` | — | `bm25` / `vector` / `hybrid`（默认 hybrid）。不认识的值 → **400 + 1001**，不静默退回 hybrid |
| `topK` | — | **不传 = 用该知识库的 `retrievalTopK`**（优先级：请求 > 知识库配置 > `nexus.retrieval`）。越界夹到 1–100 |
| `tags` | — | 标签过滤，多个标签之间是 **OR**（与 Obsidian `tag:#a OR tag:#b` 一致）。支持 `?tags=a,b` 与 `?tags=a&tags=b`，两者可混用 |
| `folder` | — | 目录过滤，**包含子目录**（`算法` 匹配 `算法` 及 `算法/…`；不匹配 `算法入门`） |
| `documentId` | — | 限定文档，`?documentId=7&documentId=8` 或 `?documentId=7,8`；非数字 → 400 + 1001 |

> 多个条件同时给是 **AND**（逐个求交集）；标签内部是 OR；空值/空白项都表示"该条件不参与"。
> ⚠️ 与"条件给了、但在库内一篇都没命中"是两回事：后者**必须返回空结果**，
> 绝不能退化成"不过滤"（否则过滤会静默消失）。实现见 `RetrievalFilterResolver`。

```http
GET /api/knowledge-bases/1/search?q=缓存击穿&mode=hybrid&tags=Redis,缓存&folder=Redis

200 OK
{ "code": 0, "data": {
  "hits": [
    { "chunkId": 20001, "documentId": 100, "documentTitle": "缓存击穿.md",
      "headingPath": "Redis > 缓存 > 缓存击穿", "content": "……互斥锁方案……",
      "score": 0.0324, "source": "hybrid", "matchedBy": ["BM25 #2", "向量 #1"] } ],
  "emptyReason": null } }
```

`emptyReason`（**只在 `hits` 为空时有值**，有命中一律 `null`）：

| 值 | 含义 | 用户该做什么 |
| --- | --- | --- |
| `NO_INDEX` | 库里有切片，但**没有一片进了检索索引**（导入文档 ≠ 建索引） | 去知识库详情页点「建索引」，在此之前任何关键词都不可能命中 |
| `NO_RESULTS` | 索引存在，但这次查询确实没命中 | 换关键词 / 放宽 `tags`、`folder`、`documentId` |
| `NO_KB` | 会话或 Agent 没绑定可检索的知识库 | 给会话/Agent 选一个知识库 |

> 这三种事实对用户的下一步是**相反**的指令，所以不能都压成一个空数组。
> 覆盖率的判定成本只在 0 命中时付（多几条 `count`），有命中时不查。

```http
GET /api/knowledge-bases/1/search?q=不存在的词

200 OK
{ "code": 0, "data": { "hits": [], "emptyReason": "NO_INDEX" } }
```

### 4.2 跨库统一检索（已实现）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/search` | 跨库检索：`q` + 可选 `kbIds[]`，`mode` ∈ `VECTOR` / `BM25` / `HYBRID`（大小写不敏感），`topK`，`rerank`，`filters{tags,folder,documentId}` |
| POST | `/api/search/explain` | 同入参；返回两路原始结果、RRF 融合名次与重排分数（只读，不落库，用于调参与演示） |

> ✅ **本表均已实现**。入口是 `SearchController`，检索本身复用 `ChunkSearchService` /
> `MultiKnowledgeRetriever`——没有第二套召回逻辑，§4.1 的库内入口同时保留（库在路径里，
> 一次只搜一个库）。`requestId` / `request_id` 不参与检索，这里的"跨库"是**同一用户**的多个库。

```http
POST /api/search
{ "q": "Redis 缓存击穿", "kbIds": ["1800000000000000001"], "mode": "HYBRID", "topK": 10,
  "rerank": true, "filters": { "tags": ["Redis"], "folder": "Redis" } }

200 OK
{ "code": 0, "data": {
  "hits": [
    { "kbId": "1800000000000000001", "kbName": "Redis 源码阅读", "chunkId": "20001",
      "documentId": "100", "documentTitle": "缓存击穿.md",
      "headingPath": "Redis > 缓存 > 缓存击穿", "snippet": "……互斥锁方案……",
      "score": 0.0325, "source": "hybrid", "matchedBy": ["BM25 #1", "向量 #2"] } ],
  "emptyReason": null,
  "tookMs": 78,
  "degradedKbs": [ { "kbId": "1800000000000000002", "kbName": "面试八股合集", "reason": "索引不可用" } ] } }
```

```http
POST /api/search/explain
{ "q": "Redis 缓存击穿", "topK": 10, "rerank": true }

200 OK
{ "code": 0, "data": {
  "vector": [ { "kbId": "1800000000000000001", "chunkId": "20001", "documentId": "100",
                "documentTitle": "缓存击穿.md", "headingPath": "Redis > 缓存 > 缓存击穿",
                "snippet": "……互斥锁方案……", "score": 0.83, "rank": 2 } ],
  "bm25":   [ { "kbId": "1800000000000000001", "chunkId": "20001", "documentId": "100",
                "documentTitle": "缓存击穿.md", "headingPath": "Redis > 缓存 > 缓存击穿",
                "snippet": "……互斥锁方案……", "score": 12.4, "rank": 1 } ],
  "fused":  [ { "kbId": "1800000000000000001", "chunkId": "20001", "documentId": "100",
                "documentTitle": "缓存击穿.md", "headingPath": "Redis > 缓存 > 缓存击穿",
                "snippet": "……互斥锁方案……", "rank": 1, "fusedScore": 0.0325,
                "bm25Rank": 1, "vectorRank": 2, "matchedBy": ["BM25 #1", "向量 #2"] } ],
  "rerank": [ { "kbId": "1800000000000000001", "chunkId": "20001", "rank": 1, "rerankScore": 8.7 } ],
  "tookMs": 78,
  "degradedKbs": [] } }
```

**五条容易搞错的契约事实**

1. **`kbIds` 省略或空数组 = 当前用户的全部可检索库**（`status = ENABLED` 的库），上限 20 个。
   显式传了不属于自己的库不会"悄悄剔除"，而是整批 `404 / 3001`；显式传了自己已禁用的库
   不参与检索，但会出现在 `degradedKbs` 里说明原因。
2. **单个库崩了不会整体失败**：该库进 `degradedKbs`，其余库的命中照常返回，HTTP 仍是 200。
   所以调用方必须看 `degradedKbs`，不能只 `catch`——`catch` 到的东西只有"全挂"这一种。
   全部库都失败时才由底层抛出（`4005` / `503`）。
3. `emptyReason` 仍是三态：`NO_KB`（一个可检索库都没有）/ `NO_INDEX`（有切片但没建索引）/
   `NO_RESULTS`（索引正常、确实没有相关内容）；**有命中时一定是 `null`**。命中为空且部分库
   降级时 `emptyReason` 照给——它说的是"能搜的库确实没有"，"还有库没搜到"由 `degradedKbs` 说。
4. `topK` 只决定**归并后保留多少条**，每个库仍按自己的 `retrieval_top_k` 召回；`rerank: true`
   只对归并后的**前 8 条**调模型，重排失败按原序返回（不丢结果），重排成功的条目
   `source = "rerank"`、`score` 换成模型分、`matchedBy` 追加 `重排 #n`。
5. `/explain` 只读、不落库；**两路原始结果不裁剪**（召回多少条回多少条），`fusedScore`
   就是 RRF：`1/(60+bm25Rank) + 1/(60+vectorRank)`（k=60），`rank` 从 1 连续；
   `rerank[]` 只含参与重排的前 8 条且不带正文（避免响应翻倍）。

| 业务码 | HTTP | 触发条件 |
| --- | --- | --- |
| 1001 | 400 | `q` 为空、`mode` 非法、`topK` 越界、`filters.documentId` 非数字、`kbIds` 超过 20 个 |
| 3001 | 404 | `kbIds` 里有不存在或不属于当前用户的库 |
| 4006 | 400 | 有库的嵌入模型未配置（整批失败，不降级——降级会让"配置错误"伪装成"这个库没内容"） |
| 4005 | 503 | 向量库不可用导致**全部**库失败 |

### 4.3 RAG 问答（已实现）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/knowledge-bases/{id}/ask` | 检索 → 重排 → 组装 → 生成，返回**带引用**的回答（同步） |
| POST | `/api/knowledge-bases/{id}/ask/stream` | 同上，SSE 流式（`text/event-stream`） |
| GET | `/api/knowledge-bases/{id}/qa-history` | 该库的问答留档（B.27） |

> `topK` 同样是"不传 = 用该知识库的 `retrievalTopK`"。
> 两者的关系与分工见 decisions.md B.31 第 7 条：**会话流式（`/api/chat/stream`）是主链路**，
> 这里的知识库问答接口是"单次问答/调试"入口，保留但不再叠加会话与记忆。

---

## 5. AI 会话与流式输出（Phase 1 / Phase 3）

### 5.1 会话与消息

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/chat/conversations` | 新建会话（title 可空，首轮后自动生成标题；可指定 mode/kbIds/agentId） |
| — | — | `mode` ∈ `PLAIN` / `RAG` / `AGENT`；`kbIds` 只在 `RAG` 下有意义，**可以绑多个**（Phase 5.5，上限 10 个，超限 1001）；重复 id 会去重并保持顺序 |
| — | — | `agentId`（Phase 4.1）只在 `AGENT` 下有意义，且必须是**自己已发布**的 Agent（草稿 5008、停用 5009、别人的 5006→404）；不传 = 用内置默认 Agent |
| GET | `/api/chat/conversations` | 会话列表（分页、置顶优先、按最后消息时间倒序） |
| GET | `/api/chat/conversations/{id}` | 会话详情（含配置：mode、kbIds、agentId、模型角色、prompt 版本） |
| PUT | `/api/chat/conversations/{id}` | 改标题 / 置顶 / 绑定知识库 / 换 Agent（`agentId` 为 null = 不改；`resetAgent:true` = 回到内置默认，见下）。⚠️ 校验用**目标模式**，见下 |
| DELETE | `/api/chat/conversations/{id}` | 删除会话。⚠️ **不可恢复，且消息被物理删除**，见下方说明 |
| GET | `/api/chat/messages/{conversationId}` | 历史消息（分页，含引用、Token、耗时） |
| POST | `/api/chat/messages` | 非流式发送（用于简单场景与自动化测试） |
| POST | `/api/chat/stream` | **SSE 流式发送**（见 5.2） |
| POST | `/api/chat/messages/{messageId}/regenerate` | 重新生成（删除该 assistant 消息重跑，保留原 prompt 版本） |
| POST | `/api/chat/stream/{requestId}/stop` | 停止生成（写 Redis 取消标记；服务端落库 `status=STOPPED`，保留已生成内容） |
| POST | `/api/chat/messages/{messageId}/feedback` | 点赞/点踩 + 备注（回流评测集） |

#### ⚠️ `DELETE /api/chat/conversations/{id}` 的三重副作用（不可恢复）

实现见 `ConversationServiceImpl.delete`，它**同时做三件事**：

| # | 动作 | 后果 |
| --- | --- | --- |
| 1 | 会话**逻辑删除**（`@TableLogic` → `UPDATE deleted=1`） | 会话行仍在库里，但列表查询不再返回 |
| 2 | 该会话的**消息被物理删除** | ⚠️ **不是软删，消息真的没了** |
| 3 | `chatMemory.clear("conv:{id}")` | 该会话的对话记忆窗口清空 |

因此：

- ❌ **不能把本端点当"归档"用**。"归档"是**可恢复**的语义，与本端点的**不可恢复**是两种不同产品语义。
  若用 DELETE 实现归档，用户点"恢复"后只会得到一个**空会话**。
- ✅ **要"可恢复的收起"，前端应走本地归档**（从主列表隐藏、入口可找回），**不调用本端点**。
- 为什么必须清记忆（不只是"多留几行无主数据"）：记忆是"这次发给模型多少历史"的唯一来源，
  一旦该 id 被重新读到（重建会话、同 id 复用），新会话会**凭空记得旧会话说过的话**。
- 为什么不软删消息：不删会随会话删除不断堆积无主数据。

> 迁移自已删除的 `docs/frontend-integration-decisions.md`（2026-10-10），已按当前实现核实。

#### 模式切换时，Agent / 知识库校验用**目标模式**（`targetMode`）

`update` 先算出 `targetMode = request.mode() == null ? 库里当前模式 : 解析(request.mode())`，
**后续所有校验都用 `targetMode`，不是库里的旧模式**。因此"先切模式再绑定"是**合法的一次请求**：

| 会话当前 | 请求 | 结果 |
| --- | --- | --- |
| `AGENT` | 只带 `resetAgent:true` | ✅ 清空 `agentId`（`targetMode` 仍是 AGENT） |
| `PLAIN` | 同时带 `mode:"AGENT"` + `resetAgent:true` | ✅ 合法：先切模式再重置 |
| `PLAIN` | 只带 `resetAgent:true`（不传 mode） | ❌ `PARAM_INVALID`（1001）："只有 AGENT 会话可以清空 Agent" |
| `PLAIN` | 带 `mode:"RAG"` + `kbIds` | ✅ 合法：切到 RAG 后立刻绑库 |
| `PLAIN` | 只带 `kbIds`（不切模式） | ❌ 被 `resolveKbIds` 挡下（1001） |

⚠️ **静默跳过比报错更坏**：`resetAgent` 的校验不能省。
早期实现写成"是 AGENT 才校验，否则静默跳过"，结果给 PLAIN 会话发 `resetAgent` 返回 `200`，
而"选用 Agent"路径却要求 `1001` —— 同一件事两种口径；
更糟的是"返回成功但什么都没变"，属于**假成功**（用户以为回到默认助手，实际 Agent 还挂着）。

实现见 `ConversationServiceImpl.update`（`targetMode` 约在 194 行）与 `requireAgentMode`。

> 迁移自已删除的 `docs/backend-findings-agent-constraints.md`（2026-10-10）。
> 该文档记录的两个具体缺陷均已修复，这里只保留当前真实语义。

**消息上的产出来源 `agentId`（5.4 · P1-14）**：`GET /api/chat/messages/{conversationId}`
返回的每条消息多一个 `agentId`。

- **只有助手消息有值，用户消息恒为 `null`**（用户消息不是任何 Agent 产出的；给它也写 id 会让
  "按 agent_id 聚合"把每轮对话重复计两次）；
- 值是**解析后**的具体 id：会话没选 Agent 时，落的是**内置默认那一行的真实 id**，不是 `null`——
  否则"这条是谁答的"永远没有答案；
- 它与 `Conversation.agentId` **不是一个东西**：那个是**活指针**（会话现在用哪个 Agent，可随时改），
  这个是**历史事实**（这条回答当时是哪个 Agent 跑的，插入即定）。会话换绑后旧回答仍指回原来那个；
- 5.4 之前的存量消息为 `null`（当时确实没记，不回填）。

**非流式入口的模式约束（5.4）**：`POST /api/chat/messages` 此前**完全不看会话模式**，
于是同一个会话走不同入口会得到两种答案，而且都不报错。现在：

| 会话模式 | 非流式 `POST /api/chat/messages` 的行为 |
| --- | --- |
| `PLAIN` | 正常回答（与之前一致） |
| `AGENT` | **正常回答**，且用的是该 Agent 的提示词 / 模型 / 工具（与流式链路同一份装配）；出处照记 |
| `RAG` | **报 `8003`**（HTTP 409），message 指向 `POST /api/chat/stream`；**在任何副作用之前拒绝**（不落用户消息、不调模型） |

`8003` 是"这个模式没走这个入口"，与 `1001`（参数填错）分开：前者换接口，后者改参数。
合并成一个码，用户会一直在参数上找问题。RAG 之所以是报错而不是顺手补上检索：
那需要把流式那条链路的"检索 → 无结果短路 → 提示词组装"在非流式下再实现一遍，
而多库 RAG（P1-6）本身还没定——现在接上来等于把两个未决问题焊在一起。

### 5.2 SSE 协议（本项目自定义事件）

```http
POST /api/chat/stream
Authorization: Bearer <accessToken>
Accept: text/event-stream
Content-Type: application/json

{ "conversationId": 5001, "content": "解释一下 Redis 缓存击穿，并结合我的笔记回答" }
```

> 上面是**当前实现**的请求体；设计稿里的 `mode` / `kbIds` / `options` 已收敛：
> `mode` 属于会话（建会话时定），检索范围由会话/知识库配置决定（见 5.2 末的"当前实现的事件子集"）。

响应 `Content-Type: text/event-stream`，事件序列：

| 事件 | 何时发送 | data 示例 |
| --- | --- | --- |
| `meta` | 立即（**已实现**） | `{"requestId":"...","conversationId":5001,"userMessageId":91,"assistantMessageId":92,"modelRole":"chat.default","mode":"RAG"}` |
| `retrieval` | 检索完成、生成前（**已实现**，Phase 3.4；多库形状见 5.5；失败明细见 5.5.1） | `{"knowledgeBaseIds":["5001","5002"],"count":2,"hits":[{"chunkId":"9001","documentId":"100","knowledgeBaseId":"5001","documentTitle":"缓存击穿.md","headingPath":"Redis > 缓存 > 缓存击穿","score":0.83,"source":"hybrid"}],"contextTokens":1200,"truncated":0,"reranked":true,"empty":false,"failedKnowledgeBases":[{"knowledgeBaseId":"5002","knowledgeBaseName":"内部 FAQ","reason":"ES 连接被拒绝"}]}` |
| `delta` | 每个 token 片段（**已实现**） | `{"text":"缓存击穿是指"}` |
| `tool_call` | Agent/工具场景（**已实现**） | `{"tool":"knowledge_search","input":"缓存击穿怎么解决"}` |
| `tool_result` | 工具返回（**已实现**） | `{"tool":"knowledge_search","input":"缓存击穿怎么解决","resultSummary":"3 条结果"}` |
| `approval_required` | 工具需要人工确认（**已实现**，P0：只对 `WRITE`/`DANGEROUS` 工具且用户没有 `always` 授权时发送） | `{"runId":"9","approvalId":"11","toolCode":"test_write_tool","arguments":"{\"payload\":\"…\"}","reason":"该工具会修改数据（WRITE），执行前需要你确认。"}` |
| `citation` | 生成结束后（Phase 3 预留，**仍未作为独立事件实现**） | 引用随 `done` 的 `citations` 字段给出，并同时落库（见下） |
| `usage` | 结束前（**已实现**） | `{"promptTokens":1820,"completionTokens":240,"totalTokens":2060,"latencyMs":2900,"firstTokenMs":380}` |
| `done` | 正常结束（**已实现**） | `{"finishReason":"stop"}`；用户停止时 `{"finishReason":"stopped","stopped":true}` |
| `error` | 异常结束（**已实现**） | `{"code":4001,"message":"模型调用失败，请稍后重试","retryable":true}`；`code`/`message`/`retryable` 由 `FailureClassifier` 判定，可能是 `4007`（密钥被拒，不可重试）、`4002`（超时）、`5002`（工具轮数超限）等，见 1.2 的失败归因表 |

⚠️ **两条 SSE 链路的帧结构不同，不要假设可以共用一个解析类型。**

上面这张表描述的是 `/api/chat/stream`（实现见 `ChatStreamServiceImpl`）。
知识库问答的 `/api/knowledge-bases/{id}/ask/stream`（实现见 `RagStreamServiceImpl`）字段名**不一样**：

| | `/api/chat/stream` | `/api/knowledge-bases/{id}/ask/stream` |
| --- | --- | --- |
| `done` 的 `finishReason` | `"stop"`；用户中止时 `"stopped"` 且带 `stopped: true` | `"empty"`（无命中）；出错时 `"error"` |
| 错误字段名 | `code` / `message` / `retryable` | **`errorCode` / `error`** / `retryable` |
| 引用 | 由 `usage` 帧或消息落库给出 | `done` 帧里带 `citations` |

即：**错误文案字段一个是 `message`、一个是 `error`**，
所以写不出一个兼容两者的解析器 —— 前端必须按链路分别处理。

> 迁移自已删除的 `docs/project-tutorial.md`（2026-10-10），字段名已按当前源码逐一核实。

**实现约束**

1. 用 `SseEmitter`（Spring MVC）而不是 WebFlux：全栈保持 MVC，避免为流式引入两套编程模型；`SseEmitter` 的超时设 5 分钟，每 15 秒发注释行心跳防代理断连。
2. 事件顺序有语义：`retrieval` 必须在第一个 `delta` 之前（前端可先展示"检索到 6 个片段"）。
3. 停止生成：`delta` 循环每片检查一次 `nexus:stream:cancel:{requestId}`，命中即终止并落库部分内容。
4. 断线重连：给每个事件带 `id:`，客户端可用 `Last-Event-ID` 续传（第一版只保证"不重复插入消息"，不做严格续传）。
5. 前端用 `fetch` + `ReadableStream` 解析 SSE（因为要带 JWT 头），不使用 `EventSource`。

**当前实现的事件子集**（Phase 3.4 实测口径）：

| 配置 | 事件序列 |
| --- | --- |
| `mode=PLAIN` | `meta` → `delta…` → `usage` → `done`（异常时 `error`） |
| `mode=RAG` | `meta` → **`retrieval`** → `delta…` → `usage` → `done`；知识库无命中时 `retrieval(count=0)` → 一句固定回答（**有库检索失败时措辞不同**，见下）→ `done{finishReason:"empty"}`（**不调模型**） |
| `mode=AGENT` | `meta` → (`tool_call` → `tool_result`)* → `delta…` → `usage` → `done` |

- 工具事件的实际字段是 `{tool, input, resultSummary}`（`tool` 为工具名，如 `knowledge_search`），
  **不是**设计稿早期的 `toolCode/arguments/step` 形状；工具痕迹同时随消息落库
  （`chat_message.metadata.toolCalls`），刷新后仍可回看。
- `retrieval` 的 `hits` 是**切片级**（与回答里的 `[n]` 编号一一对应），不是设计稿当初写的
  `documents` 文档级列表；`hits[]` 只带展示用的字段（不含正文片段），`meta` 里另有一份更精简的
  `citationPreview`。两条流式链路（`/api/chat/stream` 的 RAG 模式与
  `/api/knowledge-bases/{id}/ask/stream`）共用同一个构造器，因此事件形状完全一致。
- **`retrieval` 的知识库维度是列表**（Phase 5.5）：`knowledgeBaseIds` 是本次检索覆盖的库，
  `hits[].knowledgeBaseId` 是**这一片**出自哪个库。会话可以绑多个库，所以"检索到 N 个片段"
  不再能回答"资料从哪来"——出处必须逐条给。知识库问答流式那条链路固定单库
  （路径里的 `{id}`），因此 `knowledgeBaseIds` 恒为一个元素、`hits[].knowledgeBaseId` 恒等于它。
- `citation` 事件仍未实现（引用随 `done` 的 `citations` 字段给出）；
  `approval_required` **已实现**（P0）：事件来自工具执行壳的真实拦截点，
  发送前 `ai_tool_approval` 与运行状态已落库，`runId`/`approvalId` 就是提交审批要用的两个 id。
  客户端断开不影响持久化状态——刷新后用 `GET /api/agent/runs/{runId}` 仍能看到待审批，
  但**断开不等于批准**，工具不会执行。引用随 `done` 的 `citations` 字段给出，
  并**同时写进 `chat_message.metadata.citations`（Phase 5.9 五）**——刷新聊天页面后引用仍在。
- **RAG 流式生成失败**（`/api/knowledge-bases/{id}/ask/stream`，Phase 5.2）不再发 `error` 帧，
  而是把失败放进**最后一个 `done` 帧**：`{"finishReason":"error","error":"<给用户的话>","errorCode":4001,
  "retryable":true,"citations":[]}`。理由是这条链路此时已经产出过 `retrieval` 与部分 `delta`，
  收尾仍以 `done` 结束，前端只需要处理"一个终止帧"；`errorCode` 的取值同 1.2 的失败归因表。
  若失败前一个字都没产出，**不写问答留档**（`kb_qa_record` 只记真实发生过的问答）。
- **引用随消息落库（Phase 5.9 五）**：回答结束时把 `Citations.build` / `Citations.merge` 的结果
  （即 `done` 里那份 `citations`，含 `knowledgeBaseId` / `chunkId` / `documentId` / `documentTitle` /
  `headingPath` / `snippet` / `score` / `referenced`）写进 `chat_message.metadata.citations`，
  与工具痕迹**同一列**。刷新聊天页面后引用仍在，多库下还标得出"这一片出自哪个库"。
  用的是与同步问答同一个 `RagCitation`，**没有第二套引用系统、没有新表**。
  `referenced` 是"模型正文里有没有真的出现 `[n]`"——未引用也返回，用于核对模型有没有漏引。
- 仍然**不落库**的是 `retrieval` 事件里的命中列表本身：刷新后"检索到 N 个片段"那张卡片不再显示
  （正文与引用仍在）。引用列表回答的是"这条回答照着什么说的"，检索事件回答的是"这次搜到了什么"，
  两者不是同一件事；前者已留档，后者仍然只活在这一次连接里。
- `mode` 在**建会话时**指定（`POST /api/chat/conversations` 的 `mode` 字段），
  不是每次发送时指定；`/api/chat/stream` 的请求体只有 `conversationId` 与 `content`。
- **RAG 会话没绑知识库不等于"资料里没有"**：没绑就提问时**照常开流**（HTTP 200，SSE），
  `retrieval` 事件里 `count = 0`、`emptyReason = "NO_KB"`、`knowledgeBaseIds = []`，
  并落一条固定回答「这个会话还没有绑定知识库，所以没有可检索的资料。请在右上角「会话配置」里…」；
  该原因同时写进助手消息 `metadata.retrieval.emptyReason`，刷新后提示仍在。
  早先是"开流之前返回 HTTP 400 / code 1001"，改成现在这样是因为 400 只留一条报错、没有落库消息，
  界面既没有落点也给不出"去绑定"的入口。绑定用 `PUT /api/chat/conversations/{id}` 的 `kbIds`：
  传 `[]` = 解绑，不传该字段 = 不改。
- **多库检索的口径（Phase 5.5）**：绑了多个库时**逐库检索后跨库归并**——
  每个库按**自己的** `retrievalTopK` / `similarityThreshold` 召回（不是共用调用方的 `topK`），
  然后按分数降序归并、再按全局 `topK` 截断（请求值，没传用 `nexus.retrieval.top-k` = 5）。
  因此**多绑库不会让上下文变长、也不会让回答变慢**。
  任意一个库不存在或不属于本人 → 整批 404 / `3001`，文案带出是哪个 id，不做部分成功。
- **逐库并行 + 失败隔离（Phase 5.5.1）**：上面那条管线不变，但**逐库那一段是并行跑的**
  （虚拟线程，整体 deadline 由 `nexus.retrieval.fanout-timeout` 控制，默认 10s），
  结果按**输入顺序**装配。单个库失败不再让整次检索报错，而是：
  - 失败的库在 `failedKnowledgeBases` 里点名（`{knowledgeBaseId, knowledgeBaseName, reason}`），
    `hits` 里不会有它的一条——所以 `count = 5, failedKnowledgeBases = [内部 FAQ]` 读作
    "这 5 段只来自其余库"，**不是**"内部 FAQ 里没有相关内容"；
  - 全部库都失败 → 整批发 `4005`（不把"全挂"降级成 `count = 0`）；
  - 空结果且**有**失败库时，固定回答换成"有知识库暂时无法检索…请稍后重试"，
    `done.finishReason` 仍为 `empty`——此时**不能**说"资料中没有相关内容"，那是假话
    （同上，模型也被明令不许这么说，见 `RagAnswerServiceImpl.userMessage`）。
  两种失败**不被隔离**，仍在扇出之前整批失败：归属校验（404/`3001`）与嵌入模型不一致（`4009`/**409**，
  不是 `4006`/502——这是"这个库按另一个模型建的"这类**可修复的资源冲突**，不是上游故障）。
- **换 Agent / 回到内置默认**（Phase 4.2，仅 `AGENT` 会话）：

  ```http
  PUT /api/chat/conversations/5001
  { "agentId": "12" }      // 换成某个已发布的 Agent
  PUT /api/chat/conversations/5001
  { "resetAgent": true }   // 回到内置默认 Agent（清空选用）
  ```

  `resetAgent` 必须是**独立信号**：本接口是局部更新，`agentId: null` 已经表示"这个字段不改"，
  一个值不能同时是"不改"和"清空"。两个字段同时传属自相矛盾的请求 → HTTP 400 / code 1001
  （不猜用户意图）；非 `AGENT` 会话传 `resetAgent` 同样 1001。


---

## 6. Agent 与工具（Phase 5–6）

### 6.0 已实现：Agent 资源（平台化 Phase 4.1）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/agents` | 我的 Agent 列表（内置默认 Agent 置顶；`pageNum` / `pageSize`） |
| POST | `/api/agents` | 创建 Agent：`{code, name, description?, systemPrompt?, modelRole?, answerPolicy?, temperature?, kbIds?, toolCodes?}` → 落 `DRAFT` |
| GET | `/api/agents/{agentId}` | 详情（含绑定知识库 `kbIds`、工具白名单 `toolCodes`、`answerPolicy` 与 `temperature`） |
| PUT | `/api/agents/{agentId}` | 局部更新（null = 不改）：`{name?, description?, systemPrompt?, modelRole?, answerPolicy?, temperature?, status?, kbIds?, toolCodes?}`；`kbIds: []` / `toolCodes: []` = 回到"不限制" |
| POST | `/api/agents/{agentId}/duplicate` | 复制（服务端生成 `-copy` 标识，副本回到 `DRAFT`） |
| DELETE | `/api/agents/{agentId}` | 删除（内置默认 Agent → 5010） |
| POST | `/api/agents/{agentId}/test-chat` | 试跑：`{question}` → 与 `/api/agent/ask` 同形的答案 + 工具痕迹。**不受发布状态限制**（草稿正是要被测试的） |
| POST | `/api/agent/ask` | 用**内置默认 Agent** 跑一次（旧接口，路径保持单数） |
| POST | `/api/chat/stream` | AGENT 模式会话按所选 Agent 的提示词/模型/检索范围执行 |

要点：

- `code` 是**稳定标识**（`[a-z0-9][a-z0-9._-]{1,63}`，同一个用户下唯一），显示名可随时改；
- 状态：`DRAFT`（新建默认，**不能被会话选用** → 5008）/ `PUBLISHED`（可用）/ `DISABLED`（明确报错 5009，不静默降级）；
- 内置默认 Agent（`code=builtin.default`）不可删除、不可停用（5010），但可改名、改提示词——
  AGENT 模式因此永远有可用的兜底；`agentId` 为空的 AGENT 会话在执行时解析到它；
- `kbIds` 为空 = **不限制**（检索该用户全部知识库，注意与 RAG 会话的 kbIds 语义相反）；
- `toolCodes`（5.4 · P1-9，界面在 5.4 才接上，后端 4.3 起支持）= 工具白名单，**空 = 不限制**
  （装该用户全部**启用中**的工具，与 `kbIds` 同一套语义）。装配时取"启用中的 ∩ 白名单"，
  所以白名单里留着一个已停用的 code 不会让它复活——界面上给这类工具标"（已停用）"。可选值来自
  `GET /api/tools`；
- `temperature`（5.4 · P1-8，可空字段）：采样温度 **0–2**，越界 → `1001`（错误信息带范围与当前值）。
  **`null` = 跟随全局 `ai.defaults.temperature`，与 `0` 不是一回事**（0 = 显式要求确定性）。
  响应里原样返回（含 `null`），服务端**不**把全局默认值回填进字段——回填了用户就分不清
  "我配过"和"系统给的"。生效范围只限**由 Agent 驱动的调用**（AGENT 模式会话与试跑）；
  PLAIN / RAG 会话与 `/api/knowledge/**/ask` 仍用全局默认。
  ⚠️ **没有"改回跟随默认"的传法**：`PUT` 里 `null` 已被"不改"占用（与 `kbIds` 不同，那两个把"空"
  复用成了"不限制"）。想清空只能新建 Agent；
- `answerPolicy`（4.4，可空字段；**不传 = 不改**，新建时不传落服务端默认）：
  `KB_PREFERRED`（默认）没有可引用资料时**允许**用模型自身知识回答，但必须注明"不是来自你的笔记"；
  `KB_ONLY` 只依据笔记，没有就直说。两者都**禁止**把自身知识标注成"来源：笔记"，
  区别只在"没有资料时答不答"。非法值 → `1001`。响应里该字段**保证是合法值**
  （服务端把库里的 NULL 收敛成默认），前端不必再兜底；
  注意它与 RAG 会话的旧契约**曾有意相反**：RAG 没有可引用资料时拒绝提问（见 B.41 第 9 条）；
  现在 RAG 改成"照常回答 + `retrieval.emptyReason` 说明原因"，本条策略的含义不变；
- 错误码：`5006` 不存在/非本人（HTTP 404，不泄露存在性）、`5007` 标识重复、`5008` 未发布、
  `5009` 已停用、`5010` 内置项只读（后四个 HTTP 409，语义是"请求格式没问题、资源状态不允许"）。

```http
PUT /api/agents/12
{ "status": "PUBLISHED", "kbIds": [3, 7], "toolCodes": ["knowledge_search"], "temperature": 0.2 }
```

### 6.0.2 已实现：工具目录（平台化 Phase 4.3，界面 5.4 接入）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/tools` | 当前用户的工具目录：`{code, name, description, source, category, permission, enabled, builtin}`；`permission` 取自数据库（运行时权威），不是代码里的默认值 |
| PUT | `/api/tools/{code}` | 启停一个工具（`{enabled}`） |

要点：

- **工具是平台内置的能力，不是用户创建的资源**：`tool` 表里的行只是**用户态覆盖**（启用/停用），
  真正的实现与它的身份（`code`，即 `@Tool(name=…)`）在服务端代码里。所以这里**只有读与启停**，
  没有新建/删除——用户能选的是"这个 Agent 允许用哪些"，不是"我能有哪些工具"；
- 没有覆盖行 = `enabled=true`（默认开着）。**描述等元数据不落库**：唯一真源是代码里的 `@Tool`，
  库里再存一份就是永远不被读的死数据（见 `database.md` 6.0.1）；
- **停用的效果是"任何 Agent 都不再装它"**——模型连这个工具的存在都看不到，因此不会再被调用
  （而不是"调用后被拒"）。而**需要审批的语义是"模型看得见、但执行前要用户点头"**，
  已在 P0 落地（见 6.0）；
- 界面上配白名单的入口在 **Agent 编辑弹窗**（5.4 · P1-9），字段是 `toolCodes`，
  不是 `PUT /api/agents/{id}/tools` ——那个端点**不存在**，也不会做（白名单就是 Agent 的一个字段）。

### 6.0 已实现：工具授权与人工审批闭环（P0，迁移 V22）

权限语义：**`READ` 直接执行；`WRITE` / `DANGEROUS` 必须人工确认**，
除非该用户对该工具有 `always` 授权。权限与授权都在数据库里（`ai_tool_registry.permission` 是运行时权威），
执行拦截读的是库里的值——管理员把一个只读工具提级为 `DANGEROUS` 后，下一次调用立即开始要求审批
（`ToolApprovalFlowTest.registrySyncDoesNotOverwriteAdminPermission` 钉住了这条）。

#### 运行状态机

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/agent/runs` | 发起一次运行 → `{"runId":"9"}`（异步；不阻塞 HTTP 线程） |
| POST | `/api/agent/runs/stream` | 发起一次运行并推 SSE（meta → status → step* → approval? → done），见下 |
| GET | `/api/agent/runs/{runId}` | 运行详情：状态 + `pendingApproval`（等待审批时非空，刷新后可据此恢复现场） |
| GET | `/api/agent/runs/{runId}/steps` | 运行轨迹，按 `seq` 升序 |
| POST | `/api/agent/runs/{runId}/approval` | 提交审批决策 → 返回决策后的运行详情 |
| POST | `/api/agent/runs/{runId}/cancel` | 取消运行（幂等，等待审批时同样可取消）→ `{"runId":"9","cancelled":true}` |

```http
POST /api/agent/runs
{ "agentId": "12", "conversationId": "5001", "question": "把这份纪要写进我的学习计划" }
200 OK  { "code": 0, "data": { "runId": "9" } }

GET /api/agent/runs/9
200 OK  { "code": 0, "data": {
    "id": "9", "status": "WAITING_APPROVAL",
    "pendingApproval": { "approvalId": "11", "toolCode": "test_write_tool",
                         "arguments": "{\"payload\":\"…\"}",
                         "reason": "该工具会修改数据（WRITE），执行前需要你确认。",
                         "status": "PENDING", "createdAt": "2026-10-15T19:33:23.742" } } }

POST /api/agent/runs/9/approval
{ "approved": true, "scope": "always", "reason": "信任这个工具" }
200 OK  { "code": 0, "data": { "id": "9", "status": "RUNNING", "pendingApproval": null } }
```

状态取值：`RUNNING` / `WAITING_APPROVAL` / `DONE` / `FAILED` / `MAX_ITERATIONS` / `CANCELLED`。
状态转换一律是**条件更新**（`UPDATE … WHERE status=?`），因此并发审批、审批与取消同时到达时只有一次能生效；
取消之后再提交审批返回 `5013`（HTTP 409），工具不会执行。

#### 审批决策

| 字段 | 取值 | 语义 |
| --- | --- | --- |
| `approved` | `true` / `false` | 是否放行**本次**调用 |
| `scope` | `once` | 只放行这一次，不写长期授权（缺省值就是它） |
| `scope` | `always` | 放行本次，并写入 `ai_tool_grant`（后续同工具不再询问） |
| `scope` | `deny` | 拒绝本次；**不写授权**，并把"用户拒绝"作为 observation 回灌模型 |
| `reason` | 字符串（≤500） | 可空；拒绝时会出现在回灌给模型的那句话里 |

约束：

- `approved=false` 一律按拒绝处理（`scope` 被收敛成 `deny`），**绝不会**写长期授权；
- `approved=true` + `scope=deny` 是矛盾组合 → `1001`；
- 重复审批、审批已过期、运行没有待决策审批 → `5014`（HTTP 409）；
- **拒绝不是失败**：运行回到 `RUNNING` 让模型换方案，运行不会因为一次拒绝被标成 `FAILED`。

#### 用户工具授权

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/users/me/tool-grants` | 当前用户的长期授权列表 |
| PUT | `/api/users/me/tool-grants` | 写入/更新 `always` 授权：`{"toolCode":"test_write_tool"}`（幂等） |
| DELETE | `/api/users/me/tool-grants/{toolCode}` | 撤销单个授权（幂等：本来就没有也返回成功） |
| DELETE | `/api/users/me/tool-grants` | 撤销全部授权（幂等） |

约束：只接受 `scope=always`（`once`/`deny` → `1001`）；不能给未注册或已禁用的工具授权（`5011`）；
授权只对当前用户生效（唯一键 `(user_id, tool_code)`）；撤销后下一次调用重新走审批。

#### 审计

每次进入执行壳的工具调用都会写一行 `ai_tool_invocation`（含被拒、被取消、等待超时的），
运行轨迹写 `ai_agent_run_step`，审批写 `ai_tool_approval`。
入参与结果都会**脱敏 + 截断**后才落库（`AgentTraceGuards`：`apiKey`/`token`/`password` 等键值、
`Bearer …`、`sk-…` 一律替换为 `***`）。
`THINK` 只是阶段标记（"开始运行""等待人工确认"），**不落库也不外发模型的隐藏推理原文**。

#### 工具调用审计查询（2026-10-19 已实现）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/tool-invocations` | 当前用户的工具调用审计流水：分页（`pageNum` / `pageSize`），可按 `toolCode` / `status`（`SUCCEEDED` / `FAILED` / `DENIED` / `CANCELLED` / `PENDING_APPROVAL`）/ `runId` 过滤，新到旧 |

> **没有 userId 参数**：一律按登录用户收口（审计行里带着工具名、参数摘要与运行 id）。
> 被拒绝、被取消、等待超时的调用同样在流水里——只记成功的审计等于把最需要复查的那部分抹掉。
> 返回的 `argumentsDigest` / `resultDigest` 入库时已是脱敏摘要，接口不还原明文。

### 6.1 规划中（尚未实现）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/tools/{code}` | 工具详情（入参 JSON Schema、最近调用记录） |

> ~~`POST /api/agent/runs` 的**流式**形态（把整个运行挂到 `POST /api/chat/stream` 上并复用同一条 SSE）~~
> **已实现（2026-10-20）**：`POST /api/agent/runs/stream` 有了自己的流式形态，不再需要借聊天链路的工具拦截点。
>
> 事件（内容型，各帧 name 即下面这五个）：
>
> | 事件 | 载荷 | 何时发 |
> |------|------|--------|
> | `meta` | `{"runId":"9"}` | 连上先告诉客户端在看哪次运行 |
> | `status` | `{"runId":"9","status":"RUNNING"}` | 状态机换值时发一次（同一状态不重复刷） |
> | `step` | 与 `GET /api/agent/runs/{runId}/steps` 同一形态 | 每有新落库的 step 行就推一条 |
> | `approval` | `pendingApproval` 对象 | 状态进入 `WAITING_APPROVAL` 时发，决策仍走 `/approval` |
> | `done` | `{"runId","status","answer","errorCode","errorMsg","finishedAt"}` | 终态（DONE/FAILED/CANCELLED）发一次并结束 |
>
> 边界：流只承载真实落库的事实（状态值 + step 行），没有估算进度；断连只停推送，
> 运行照旧跑完并落库，重连用 `GET` 补现场。
> 仍未实现：本轮审批事件是通过对话流的工具拦截点真实发出的（`approval_required` 已可用），
> 但运行状态机自身的接口是查询式的；审计数据由 `GET /api/tool-invocations` 提供（见上）。

---

## 7. MCP（Phase 7）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/mcp/servers` | 已注册的 MCP Server（名称、URL、状态、工具数） |
| POST | `/api/mcp/servers/{name}/refresh` | 重新拉取工具清单（MCP 工具可动态变化） |
| GET | `/api/mcp/servers/{name}/tools` | 该 Server 暴露的工具与 Schema |

> ⚠️ 上面这张表（`/api/mcp/servers`，带斜杠）是**目标态、尚未实现**。
> 2026-10-24 起真正可用的外部 server 管理接口是 §7.2 的 `/api/mcp-servers`（复数 + 连字符），
> 两者路径不同、语义也不同，不要照这张表写客户端。

MCP Server 进程自身暴露（Streamable HTTP）：

| 能力 | 名称 | 权限 |
| --- | --- | --- |
| Tool | `searchKnowledge` | READ |
| Tool | `getDocument` | READ |
| Tool | `searchByTag` | READ |
| Tool | `listKnowledgeBase` | READ |

> 上面这张表是**目标态**（把一个独立 MCP Server 进程里的知识库能力暴露成标准工具）。
> 当前**已实现并可调用**的是下面这一节——同一个 Spring Boot 进程内的最小 MCP Server，
> 没有独立进程。两者不是同一个东西，别把这张表当成"已经在跑的接口清单"。

### 7.1 已实现的 MCP 端点：`POST /mcp`

JSON-RPC 2.0 over HTTP（MCP Streamable HTTP 的最小子集）。

**必须携带 `Authorization: Bearer <JWT>`**（2026-10-01 起，与其余所有端点同一套身份；
此前它免认证且用 `X-Nexus-User` 头自证身份——那条路径可读任意用户的笔记，属 P0 安全漏洞，
见 `docs/decisions.md` B.42）。**用户身份只取自令牌里的 `sub`**，任何请求头都不再影响它。

| method | 说明 |
| --- | --- |
| `initialize` | 协议握手（返回 `protocolVersion` / `capabilities` / `serverInfo`） |
| `ping` | 存活探测 |
| `tools/list` | 工具清单：**只列该用户启用中的工具**（`@Tool` 方法由注册表反射而来，名称/描述/schema 单一来源），另有非 `@Tool` 的便利入口 `qa_ask` |
| `tools/call` | 执行工具；失败时 `result.isError = true`，未知工具 `error.code = -32601`，**工具被该用户停用** `error.code = -32003`（见下） |

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"Demo@12345"}' | jq -r .data.accessToken)

curl -s -X POST localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
# tools/call: {"jsonrpc":"2.0","id":2,"method":"tools/call",
#              "params":{"name":"knowledge_search","arguments":{"query":"缓存击穿"}}}
```

**未认证时**返回的是本项目的统一失败体（HTTP 401 / `code 2001`），不是 JSON-RPC 的 `error` 对象：
认证发生在协议层之前。**兜底错误不回传异常原文**，只给 `-32603` + `requestId`。

**工具权限（Phase 5.9 八）**：`tools/list` 与 `tools/call` 遵守**同一份**用户工具权限
（`ToolRegistry.enabledFor`，与 Agent 装配是同一个判定）。用户停用 `knowledge_search` 后：

- `tools/list` **不再返回**它（客户端不该拿到一个"看起来能用"的名字）；
- `tools/call` 拒绝，回 JSON-RPC `error`：

  ```json
  {"jsonrpc":"2.0","id":1,
   "error":{"code":-32003,
            "message":"工具调用无权限：knowledge_search 已被停用（业务码 5004）",
            "data":{"businessCode":5004,"tool":"knowledge_search"}}}
  ```

为什么是 `error` 而不是 `result.isError=true`：本端点里 `isError` 的含义是"工具真的跑了、但失败了"，
而这里工具**一次都没跑**——混在一起，调用方就再也分不清是用户的开关生效了，还是检索真的出错了。
为什么是 `-32003` 而不是标准码：JSON-RPC 2.0 只规定了"方法不存在（`-32601`）""参数不合法（`-32602`）"，
没有"无权限"这一档。**`-32601`（名字写错了）与 `-32003`（你的开关生效了）刻意不合并**，
它们各自指向完全不同的下一步动作。

`qa_ask` 不受这个开关影响（它不是 `@Tool`，而是"跑一次默认 Agent"的入口）：
它内部的检索同样按用户启停过滤，因此停用检索工具后它仍会回答、只是不再检索
（这一点由 `AGENT_TOOLS_EMPTY` 计数与 WARN 留痕，不静默）。

### 7.2 已实现：接入外部 MCP Server（迁移 V32）

§7.1 是 Nexus **作为** MCP Server；这一节是反方向：让 Nexus **作为客户端**去连别人家的
MCP server，把它的工具挂进 Agent 工具箱。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/mcp-servers` | 我接的 server 列表（含上次探测到的工具、上次成功/失败时间） |
| POST | `/api/mcp-servers` | 新建（`name` 必填；`transport` 为 `http` 或 `stdio`） |
| PUT | `/api/mcp-servers/{id}` | 局部更新（每个字段都可省略 = 不改那一项） |
| DELETE | `/api/mcp-servers/{id}` | 删除（它的工具同时从工具箱移除） |
| POST | `/api/mcp-servers/{id}/probe` | 真连一次 `tools/list`，成功则落库并注册工具 |

> ✅ **本表均已实现**（`McpServerController` + `McpServerService`，迁移 `V32__plugin_and_mcp_server.sql`）。
> 上一节（§7.1）的进程内 Server 与这里的外部客户端是两套东西，互不影响。

请求体（POST）：

```json
{ "name": "Fake Echo", "transport": "http", "url": "http://127.0.0.1:9000/mcp",
  "authHeader": "Bearer xxx", "enabled": true }
```

`stdio` 时把 `url` 换成 `command`，例如
`"command": "npx -y @modelcontextprotocol/server-filesystem /data"`。

响应（GET / POST / PUT 同一个形状）：

```json
{ "id": "1", "name": "Fake Echo", "slug": "fake-echo", "transport": "http",
  "url": "http://127.0.0.1:9000/mcp", "command": null,
  "hasAuthHeader": true, "enabled": true,
  "lastOkAt": "2026-10-24T23:10:00", "lastError": null,
  "tools": [ { "name": "echo", "description": "回声", "inputSchema": "{\"type\":\"object\"}" } ] }
```

`probe` 的响应：

```json
{ "ok": false, "error": "MCP server 返回 HTTP 500：…", "tools": [], "tookMs": 12, "lastOkAt": null }
```

五条容易搞错的契约事实：

1. **`probe` 失败也是 HTTP 200**，成没成看 `ok`。探测失败是**业务结果**（上游 500、连不上、
   stdio 被服务端关掉），不是 Nexus 自己的故障；用 5xx 表示只会让前端弹一个没有信息的
   "请求失败"。`error` 里的原因**必须显示**，不能静默当成"这个服务本来就没什么工具"。
2. **`authHeader` 只进不出**。写入时经 `CredentialCipher`（AES-GCM，`v1:` 前缀）加密存进
   `auth_header_cipher`，读接口只回 `hasAuthHeader`，永不回显明文。PUT 时 `authHeader`
   缺省 = 不改，空串 = 清除。
3. **工具 code 是 `mcp__<slug>__<tool>`**。`slug` 由 `name` 生成且**全库唯一**（第二个用户
   也叫 github 会拿到 `github-2`）——因为 `ai_tool_registry` 没有 user_id 维度。改名**不会**
   改 slug：它已经刻在工具 code 里了。
4. **stdio 默认关闭**（`nexus.mcp.stdio-enabled=false`）。打开它意味着网页上填的"命令"会被
   服务器**真的执行**——这是 stdio 传输固有的语义，只能由部署者决定要不要给这个能力；
   关闭时 probe 明确回报「本机 stdio 传输已关闭」，而不是一个看不懂的连接失败。
5. **probe 成功才注册工具；失败只写 `last_error`，并保留旧的 `tools_json`**——一次网络抖动
   不该把已经能用的工具全撤掉。上游真的删掉的工具会被置为停用，但**用户手动停用的工具
   不会被"复活"**。

运行时注入：`AgentToolFactory.assemble` 在内置 `@Tool` 之后追加
`McpToolInjector.toolsFor(userId, definition)`，因此 MCP 工具与内置工具走**同一套**授权闸门
（`ToolAuthorizationService`）、审批与留痕（`ExecutingToolCallback`）；0 个工具时仍然不调用
`.tools()`。真正的调用走 JSON-RPC `tools/call`，Streamable HTTP 与 stdio 两种传输由
`McpClientFactory` 按 `transport` 选择。

错误码：`9201`（server 不存在；越权同样回 404，不暴露存在性）、`9203`（不支持的传输方式，400）、
`9204`（stdio 已被服务端关闭，400）。

### 7.3 已实现：插件开关与配置（迁移 V32）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/plugins` | 我显式改过的插件（没动过的不出现在列表里） |
| GET | `/api/plugins/{code}` | 单个插件状态；**没有记录时返回默认值，不是 404** |
| PUT | `/api/plugins/{code}` | 启停 + 配置（即 upsert，没有 POST） |

```json
{ "code": "web-fetch", "enabled": false, "config": { "endpoint": "https://example.com" } }
```

三条契约事实：

1. 插件目录在前端（`PLUGIN_CATALOG`），服务端只记"这个用户对这个 code 做了什么"。
   所以 `GET /{code}` 在一条记录都没有时返回 `{"enabled": true, "config": {}}`，**不会 404**；
   `PUT` 就是 upsert，安装/卸载等价于 `enabled` 的 true/false。
2. `config` 传 `null`/缺省 = 不改，传 `{}` = 清空。两者混为一谈，用户就永远清不掉自己的配置。
3. `code` 必须匹配 `^[a-z0-9][a-z0-9._-]{0,63}$`，否则 400 / `1001`——它同时是 URL 注入的边界。

---

## 8. 工作流（Phase 8）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/workflows` | 创建/导入工作流定义（nodes + edges） |
| GET | `/api/workflows` | 工作流列表 |
| GET | `/api/workflows/{id}` | 定义详情（含图结构与节点配置） |
| PUT | `/api/workflows/{id}` | 改元信息（名称 / 描述，局部更新；**不产生新版本**） |
| POST | `/api/workflows/{id}/runs` | 触发执行 → `runId` |
| GET | `/api/workflow-runs/{runId}` | 运行状态与当前节点 |
| GET | `/api/workflow-runs/{runId}/nodes` | 各节点执行明细（输入/输出/耗时/重试次数） |
| GET | `/api/workflow-runs?workflowId=` | 某工作流的运行历史（按 id 倒序分页；失败运行靠它找回 runId） |
| POST | `/api/workflow-runs/{runId}/approval` | 人工确认节点放行 |
| POST | `/api/workflow-runs/{runId}/retry` | 从失败节点重试 |

> ✅ **本表（连同下面的预置模板）均已实现**（`architecture.md` §0 的规矩仍适用：与代码冲突时以代码为准）。
> 已实现：`POST/GET /api/workflows`、`GET /api/workflows/{id}`、`PUT /api/workflows/{id}`（仅元信息）、
> `PUT /api/workflows/{id}/draft`（草稿）、`POST .../validate`、`POST .../publish`、`GET .../versions`、
> `POST .../debug`（跑草稿）、`POST .../runs`（跑已发布）、`DELETE /api/workflows/{id}`、
> `GET /api/workflows/presets` + `POST /api/workflows/presets/{code}`（预置模板），
> `/api/workflow-runs` 的列表 / 详情 / 节点轨迹 / 取消，以及 **`/approval`（人工审批推进）** 与
> **`/retry`（从失败节点续跑）**。
>
> 三条容易搞错的契约事实：
> ① `/approval` 的 `decision` 只接受 `APPROVE` / `REJECT`（大小写不敏感），其他值 → `1001`；
>    运行不在等待审批状态 → `6007` / 409（重复审批、已取消都在此列）。
> ② `/retry` 只接受 `FAILED` / `CANCELLED` 的运行，否则 `6008` / 409；它沿用源运行的版本与入参，
>    把已成功节点的输出复制进新运行的轨迹，只重跑未成功的节点。新运行的响应字段 `retryOfRunId`
>    （V24 起，`wf_run.retry_of_run_id`）指向被续跑的**源运行 id**，非重试运行恒为 `null`——
>    `triggerType=RETRY` 只是「这是重试」的标记，溯源要靠这一列。
> ③ `/presets/{code}` 的 `code` 不存在 → `1001`；实例化只落**草稿**不自动发布，
>    且检索节点的 `kbIds` 刻意留空，所以要用户先选知识库才能发布。

**预置工作流**（`GET /api/workflows/presets` 列出，`POST /api/workflows/presets/{code}` 实例化）：
八个模板、四类用途，列表项是 `{ code, name, category, description, tags: string[], nodeCount }`（`category` 与 `tags` 都是给前端卡片用的 code/短语，展示名由前端字典翻译）：

- `qa` 知识问答：`knowledge-gap-analysis`（知识缺口分析）、`kb-qa-bot`（知识库问答机器人，检索为空时走兜底话术分支）
- `content` 内容生产：`study-plan-7d`（7 天学习计划，带人工审批闸门）、`knowledge-report`（知识报告生成）、`weekly-report`（周报生成）、`meeting-notes`（会议纪要结构化）
- `ops` 分流与运营：`support-triage`（工单分流，按分类走不同回复分支）
- `review` 审核与合规：`release-review`（对外文案审核，人工审批后才出稿）

带检索节点的四个模板（`knowledge-gap-analysis` / `kb-qa-bot` / `study-plan-7d` / `knowledge-report`）的 `kbIds` 刻意留空，
所以实例化后校验会提示先选知识库才能发布；纯生成型的四个模板实例化后校验直接通过。
⚠️ 它们是**用现有 8 种节点写出的顺序版**，不是 `architecture.md` §12 设计稿里那张带 `PARALLEL` 扇出的图——
那套枚举没有实现，所以没有并行分析，也没有工具节点。

---

## 9. 评测（Phase 9）

> ⚠️ **已移除（2026-10-08）**：评测接口（`EvalController` / `EvalService`）已从后端删除 —— 原型没有对应界面，
> 也没有外部调用方。下表是**当初的设计规格**，注意它与实现有差距：实际只落了 1 个端点。
> 保留此表仅作设计记录；决策见 `decisions.md` D.11。

| 方法 | 路径 | 说明 | <!-- 已移除（2026-10-08）：仅存设计记录 -->
| --- | --- | --- |
| POST | `/api/eval/datasets` | 创建评测集 |
| POST | `/api/eval/datasets/{id}/import` | 导入 JSON/YAML 测试问题（question + expectedSource 等） |
| GET | `/api/eval/datasets/{id}/cases` | 用例列表 |
| POST | `/api/eval/runs` | 批量执行（可指定 prompt 版本、chunk 参数、topK、是否 rerank）→ `runId` |
| GET | `/api/eval/runs` | 历史运行列表（用于跨版本对比） |
| GET | `/api/eval/runs/{runId}` | 汇总指标（平均 HitRate/MRR/Faithfulness/Citation 准确率） |
| GET | `/api/eval/runs/{runId}/cases` | 逐用例结果（含检索结果、回答、引用、裁判理由） |
| GET | `/api/eval/runs/compare?base=1&target=2` | 两个 run 的指标差异对比 |

```http
POST /api/eval/datasets/1/import
[ { "question": "Spring Boot 自动配置是什么？", "expectedSource": "Spring/SpringBoot.md" },
  { "question": "Redis 缓存击穿怎么解决？", "expectedSource": "Redis/缓存.md" } ]
```

---

## 10. 观测（Phase 10）

> ⚠️ **部分已移除（2026-10-08）**：`/api/observability/summary` 与 `/api/observability/agents` 两个只读接口已删除
> （原型无对应界面）。底层的 `ObservabilityRegistry`（`nexus-common`）与 Micrometer / Actuator 指标**保留**。
> 本节曾有"规划中"的后半部分（`overview` / `trend` / `models` / `requests` …），现已实现，见 **10.1**
> （明细表 `observability_call`，迁移 `V31`）。10.0 保留原样作为历史记录：它描述的是**进程内**计数，
> 与 10.1 的**落库**明细是两套数据源，数字对不上是正常的。

### 10.0 已实现（两个端点，**数据来源不同，不要混着看**）

| 方法 | 路径 | 说明 | <!-- 部分已移除（2026-10-08）：summary/agents 已删 -->
| --- | --- | --- |
| GET | `/api/observability/summary` | 进程内计数快照：降级/不可用/失败次数。**自本次进程启动以来的累计，重启即归零**，且不含任何归属维度（全局） |
| GET | `/api/observability/agents` | 按 Agent 的用量（5.4 · P1-14）：`[{agentId, code, name, deleted, messages, failed, lastUsedAt}]`，**按消息数倒序** |

`/agents` 要点：

- 数据来自数据库里的 `chat_message`（跨重启的既成事实），**按当前登录用户隔离**；
  归属取自 JWT、**不接受任何入参**——没有可以填错的参数，也就没有越权查询的入口；
- 它回答"我配的那些 Agent，到底哪个在被真的用"：加这一列之前只能靠会话上那个**活的**
  `agentId` 去猜，而它随时会被改、且为 NULL 时连"内置默认在跑"都看不出来（见 `database.md` V17）；
- 只统计**助手消息**（只有它们有 `agent_id`）；`failed` 单独给出来，因为"用了 100 次"和
  "用了 100 次、其中 80 次失败"是两句完全不同的话；
- Agent 被删除（逻辑删除，或行被物理清掉）后，它历史上跑过的消息**仍出现在报表里**，
  由 `deleted=true` 标注——把那一行抹掉的话，用户会看到总数对不上却找不到少掉的是谁；
- 没有任何消息时返回**空列表**（不是错误）。

> 同一个数字在 `/summary` 与 `/agents` 上对不上是正常的：一个是"这次进程见过多少"，一个是"库里有多少"。

**运行指标：`/actuator/metrics`（Phase 5.9 六）**

上面两个端点回答"哪些降级发生了多少次、哪个 Agent 在被用"；运行指标回答"系统忙不忙、慢不慢"。
两者**共用同一个上报口**（`ObservabilityRegistry`），只是读法不同——刻意**不**写第二套采集逻辑：
同一个事实有两处记录，一旦对不上就没人知道该信哪个。

| 指标名（`/actuator/metrics/{name}`） | 类型 | 含义 |
| --- | --- | --- |
| `nexus.llm.calls` | counter | 模型调用次数（含 Agent 工具循环的每一轮、以及重排这种系统调用） |
| `nexus.llm.tokens` | summary | 单次调用的 token 总量（prompt + completion） |
| `nexus.llm.latency` | timer | 单次调用耗时（从**订阅**那一刻起算，不含排队等待） |
| `nexus.chat.requests` | counter | 对话请求数（流式与非流式各算一次） |
| `nexus.chat.latency` | timer | 一次对话的端到端耗时（入口 → 落库/返回） |
| `nexus.retrieval.requests` | counter | 真正发起检索的次数（"没有库可搜"不计） |
| `nexus.agent.executions` | counter | Agent 执行次数 |
| `nexus.errors` | counter | 以错误结束的请求数（全局异常处理器与流式失败各记一次） |

降级类计数（`agent.search.degraded`、`retrieval.kb.failed` 等，见 `/summary`）同样以
`nexus.` 前缀暴露在 `/actuator/metrics` 上，名字就是 `nexus.` + `/summary` 里的键。

两点如实说明：①**没有引入任何指标后端**（无 Prometheus / Grafana），
暴露的就是 Micrometer 的进程内注册表 + Actuator 的 JSON——这正是本阶段要的粒度；
②token 与耗时**只在真的发生了调用/时长 ≥ 0 时记录**。供应商不回传 usage（流式下常见）时
token 记不到，但"调了几次、花了多久"仍然记——把它一起丢掉会让指标整片为空，
看起来像"没人在用"。

### 10.1 已实现（五个只读端点，迁移 V31）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/observability/overview` | 窗口总览：调用次数、Token 消耗、平均延迟、首 Token 延迟、RAG 次数、工具次数、失败数、错误率、估算成本 |
| GET | `/api/observability/trend` | 时间趋势（按小时 / 天分桶，空桶补零，可按 scene / model 过滤） |
| GET | `/api/observability/models` | 模型维度统计（调用数、Token、延迟、失败数、成本、出现过的场景），按调用数倒序 |
| GET | `/api/observability/requests` | 请求列表（**一行一个 `requestId`**，分页） |
| GET | `/api/observability/requests/{requestId}` | 单次请求全链路（逐轮调用、检索文档、工具调用、Token、延迟、错误） |

> ✅ **本表均已实现**（迁移 `V31__observability_call.sql`）。全部需要登录、且只返回**当前用户**的数据：
> 归属取自 JWT，**不接受任何 `userId` 入参**——没有可以填错的参数，也就没有越权查询的入口。

**公共参数**（`overview` / `trend` / `models` / `requests`）

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `hours` | `24` | 回看窗口，单位小时；`< 1` 夹到 `1`，`> 720` 夹到 `720`（30 天） |
| `scene` | — | 可选，场景过滤：`chat` / `chat.stream` / `rag` / `rag.stream` / `agent` / `agent.stream` / `rerank` |
| `model` | — | 可选，模型名过滤（仅 `trend`） |
| `by` | `hour` | `trend` 的桶粒度；`day` 以外的任何值都按 `hour` |
| `pageNum` | `1` | `requests` 分页，越界自动归一 |
| `pageSize` | `20` | `requests` 每页条数，上限 `100` |
| `success` | — | `requests` 可选，`true` / `false` |

**`GET /api/observability/overview`**

```json
{
  "windowHours": 24,
  "from": "2026-02-11T09:00:00",
  "to": "2026-02-12T09:00:00",
  "calls": 128,
  "tokensIn": 48120,
  "tokensOut": 9310,
  "tokens": 57430,
  "avgLatencyMs": 642,
  "firstTokenMs": 186,
  "ragCalls": 44,
  "toolCalls": 17,
  "failures": 3,
  "errorRate": 0.0234,
  "estimatedCost": 0.171660,
  "costComplete": true,
  "unpricedModels": []
}
```

**`GET /api/observability/requests`**

```json
{
  "list": [
    {
      "requestId": "a1b2c3d4",
      "scene": "agent",
      "model": "deepseek-chat",
      "startedAt": "2026-02-12T08:41:02",
      "endedAt": "2026-02-12T08:41:09",
      "calls": 3,
      "tokens": 2140,
      "latencyMs": 6900,
      "firstTokenMs": 240,
      "success": false,
      "errorCode": 4002,
      "scenes": ["agent"]
    }
  ],
  "total": 37,
  "pageNum": 1,
  "pageSize": 20,
  "pages": 2
}
```

**`GET /api/observability/requests/{requestId}`** 在上面那些汇总字段之外，多了三段：

```json
{
  "requestId": "a1b2c3d4",
  "callCount": 3,
  "calls": [
    {"id": "9001", "scene": "agent", "model": "deepseek-chat", "tokensIn": 1200, "tokensOut": 210,
     "latencyMs": 2400, "firstTokenMs": 240, "success": true, "errorCode": null,
     "createdAt": "2026-02-12T08:41:02"}
  ],
  "retrieval": {
    "found": true,
    "knowledgeBaseIds": [9],
    "count": 2,
    "contextTokens": 1234,
    "truncated": false,
    "reranked": true,
    "empty": false,
    "hits": [
      {"knowledgeBaseId": 9, "documentName": "部署笔记.md", "headingPath": "# 部署",
       "score": 0.91, "snippet": "用 docker compose 起…"}
    ]
  },
  "toolCalls": [
    {"tool": "kb.search", "input": "{\"query\":\"部署\"}", "resultSummary": "3 条命中"}
  ]
}
```

（为省篇幅示例里省略了 `scene` / `model` / `startedAt` / `endedAt` / `tokensIn` / `tokensOut` / `tokens` /
`latencyMs` / `firstTokenMs` / `success` / `errorCode`，它们的含义与 `requests` 列表完全一致。）

**五条容易搞错的契约事实**

1. **`requests` 一行一个 `requestId`，不是一行一次调用。** 一次 Agent 运行会打多轮模型（工具循环每轮各一行明细），
   `calls` 才是"打了几轮"。没有 `requestId` 的调用（定时任务、同步 RAG）**各自成行**，`requestId` 形如
   `call-<明细主键>`——不对它们编造一个假的 `requestId`，否则两个不相干的请求会被并成一条。
2. **`scene` / `success` 过滤发生在聚合之前（按调用过滤）。** 于是 `success=false` 得到的是"**含**失败调用的请求"，
   `success=true` 得到的是"**全部**调用都成功的请求"；两者相加可能小于总数，因为"又成功又失败"的请求两次都会出现。
3. **`estimatedCost` 在任一模型缺价格时是 `null`**（同时 `costComplete=false`、`unpricedModels` 列出缺价格的模型），
   **不会**返回一个偏小的数字。成本永远只是"按配置单价算出来的估值"，不是账单。
4. **`firstTokenMs` 只对有首 Token 记录的调用求平均**（非流式调用没有这个概念，记的是 `null`），一条都没有时为 `null`；
   `avgLatencyMs` 是整数毫秒、向下取整。`toolCalls` 取 `ai_tool_invocation` 在窗口内的行数，与模型调用次数无关。
5. **详情里的检索 / 工具摘要来自 `chat_message.metadata`**（`retrieval` / `toolCalls` / `citations`），
   不看 `kb_qa_record`——那张表没有 `request_id`，无法与请求关联。请求存在但助手消息还没落库时，
   返回 `retrieval.found=false`、`toolCalls=[]`，**不是 404**。

**成本配置（`nexus.pricing.<model>.input/output`，元 / 每 100 万 token）**

```yaml
nexus:
  pricing:
    deepseek-chat:
      input: 2.0
      output: 8.0
```

- 键是**供应商返回的模型名**（响应元数据里的 `model`），不是 `chat.default` / `chat.agent` 这类**角色名**——
  角色会随配置改，模型名才是账单上的那个；
- 默认留空：宁可在观测页看到"未配价格"，也不编一个数字。没配的模型成本一律 `null`；
- 模型名本身带点时（如 `gpt-4.1`），`nexus.pricing.gpt-4.1.input` 会被当成层级；请用中括号写法
  `nexus.pricing.[gpt-4.1].input`。

**数据来源与写入点**

明细表 `observability_call` 的**唯一写入点**是 `UsageMeteringAdvisor`（`nexus-ai`），也就是模型调用真实发生、
拿到了 `ChatResponse` 的那一层；业务代码里**没有**任何一处手动埋点。好处是"漏记一条"不可能发生，
代价是只有真的发出了调用才有行——被限流、被审批拦住、模型没配的请求**不会**在这里留痕，
它们的失败在 `ai_agent_run` / `chat_message` 里。

**查询上限**：单个窗口最多扫 `20000` 行（`MAX_SCAN_ROWS`），超出时按时间倒序截断并打 WARN。
报表会**偏小**而不是把库拖死——这是刻意选的失败方向。

**错误码**

| 业务码 | HTTP | 含义 |
| --- | --- | --- |
| `9101` `OBSERVABILITY_REQUEST_NOT_FOUND` | 404 | 该 `requestId` 不存在，或不属于当前用户（两种情况不区分，避免探测他人是否存在某次请求） |

---

## 11. 提示词管理（Phase 9 前置）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/prompts` | 提示词模板列表（按场景） |
| POST | `/api/prompts` | 创建模板（code 如 `rag.answer`、`agent.planner`） |
| GET | `/api/prompts/{id}/versions` | 版本列表（DRAFT/PUBLISHED/ARCHIVED） |
| POST | `/api/prompts/{id}/versions` | 新建草稿版本（content、变量、temperature、maxTokens） |
| POST | `/api/prompts/{id}/versions/{v}/publish` | 发布（同模板下旧版本自动 ARCHIVED） |
| POST | `/api/prompts/{id}/rollback` | 回滚到指定历史版本（实现为发布一个新的等价版本，不篡改历史） |

---

## 12. 限流与配额（2026-10-02 实现）

### 12.1 已实现

拒绝点是**一个** HTTP 过滤器（`RateLimitQuotaFilter`），挂在 `BearerTokenAuthenticationFilter` 之后：

| 分支 | 路径（`nexus.security.*.paths`，Ant 风格） | 限制 |
| --- | --- | --- |
| 注册 | `/api/auth/register` | 每 **IP** 每小时 10 次（`standalone` / `local` / `test` 三个开发 profile 放宽到 1000，理由见下） |
| 模型入口（花真钱的那几条） | `/api/chat/stream`、`/api/chat/conversations/*/messages`、`/api/knowledge-bases/*/ask`、`/api/knowledge-bases/*/ask/stream`、`/api/agent/ask`、`/api/agents/*/test-chat`、`/mcp` | 每用户 20 次/分钟 **＋** 每日 200 次 **＋** 每日 200,000 tokens |
| 只限速、不计配额 | `/api/users/me/password` | 每用户 20 次/分钟 |
| 其余全部 | —— | 不限（默认放行） |

**为什么挂在过滤器而不是各 Service 里**：五类入口各写一遍检查，等于把"哪些入口花钱"散布到五处，
漏一个就静默免配额。收口之后路径清单是唯一事实来源。

**⚠️ 为什么是那两条 chat 路径、而不是 `/api/chat/**`**：清单初稿就是那个通配，写错了。
它会把**读历史消息、列出会话、删除会话、停止生成**一起算进当日额度——
用户在自己完全不花钱的操作上被扣额度，"今日 N/200 次"也不再等于"问了几次"。
真机 E2E 里"刚进对话页就显示 1/200"就是这个通配留下的痕迹。
代价是 `/api/chat` 下的读接口不受限速约束：它们只查库、不调用模型，
按上面那条口径本来就不该限（`RateLimitQuotaTest.nonModelChatPathsAreNotCharged` 钉住这一点）。

**为什么位置必须在 `BearerTokenAuthenticationFilter` 之后、控制器之前**：那个位置 `SecurityContext`
已经填好（`CurrentUser` 可用），但仍在 `new SseEmitter` 之前——所以超限时返回的是**统一 JSON**，
而不是已经开流的 SSE 帧（Phase 5.2 口径：开流前失败走 JSON）。

超限响应：`429` + `7001`（限流）/ `403` + `7002`（配额用尽），带 `Retry-After`
（分钟级桶 = 60；注册桶 = 3600；**配额用尽不带**——那是次日 00:00 才重置，回 60 秒会让客户端白试一整天）。
两种 `7002` 的 `message` 是分开的：「今日问答次数已用尽（上限 N 次/天）」vs「配额服务暂时不可用」——
把"你没额度了"和"我们坏了"说成一句，用户只会去骂产品。

**为什么三个开发 profile 把注册上限放宽到 1000**：本机请求全来自同一个回环地址，
而 E2E 脚本每轮会自己注册临时账号（`verify-security.mjs` / `verify-agent-no-kb.mjs` 各一个）。
真机实测：同源连续注册到**第 9 次**开始 `429/7001`，而且**症状出现在别处**——
注册失败之后紧接的那次登录自然 401，于是"限流"看起来像"认证坏了"（排查方向完全跑偏）。
生产（不加 profile 覆盖）仍是 **10**；要在开发 profile 上验真实阈值，
显式设 `NEXUS_REGISTER_PER_HOUR=10` 即可。

**token 记账**：所有 chat 调用都经过 `ChatClientFactory`，因此记账顾问（`UsageMeteringAdvisor`）
挂在那里一次覆盖六个调用点，各调用点只多一行 `.advisors(a -> a.param(UserContextKeys.USER_ID, userId))`。
漏传 userId 时**不静默**：计入 `quota.usage.unattributed` 度量并打日志（带模型名）。

### 12.2 存储与降级

| 存储 | 何时用 | 键 |
| --- | --- | --- |
| `InMemorySecurityStore`（默认） | `standalone` / `test`（零外部基础设施） | 进程内 `ConcurrentHashMap` |
| `RedisSecurityStore` | `nexus.security.redis-enabled=true`（`local` / 生产） | 见下 |

```
nexus:rl:u:{userId}:{yyyyMMddHHmm}   用户分钟级限流计数
nexus:rl:ip:{ip}:{yyyyMMddHH}        注册限流计数（按小时）
nexus:q:req:{userId}:{yyyyMMdd}      当日请求次数
nexus:q:tok:{userId}:{yyyyMMdd}      当日 token 用量
nexus:auth:lf:{username}             登录失败次数
nexus:auth:ll:{username}             登录冻结标记
nexus:auth:tv:{userId}               用户令牌版本（缓存，DB 是事实来源）
```

**时间片写进键名，是窗口机制本身**（不是装饰）：固定窗口不需要"到点清零"的动作——
下一分钟是另一个键，计数天然从 0 开始，TTL 只负责回收。这也解释了为什么
`{ip}` 那一格在真机上是 `0:0:0:0:0:0:0:1`：`getRemoteAddr()` 在 IPv6 栈上返回的是这种写法，
按字符串入键即可，不需要归一化（同一台机器上不会一半 v4 一半 v6）。
上表是**实测** `redis-cli KEYS 'nexus:*'` 之后校正的：初稿少了三个时间片后缀。

**降级按关注点分开**（这是刻意的，不是遗漏）：

| 关注点 | 存储故障时 | 为什么 |
| --- | --- | --- |
| 登录失败限制 / 限流 | **fail-open**（放行 + 计数） | 否则存储一挂，全体用户被锁在门外，比"少限了几次"严重得多 |
| 每日配额 | **fail-closed**（拒绝，`7002`） | 否则存储一挂就是无限烧钱。文案区分"已用尽"与"配额服务不可用" |
| 令牌版本 | **回落查库** | DB 是事实来源，缓存只是加速——故障时"每请求查一次库"，依然正确 |

**⚠️ 多实例部署必须 `nexus.security.redis-enabled=true`**：内存实现每个实例各算一份，限流会被放大 N 倍。

### 12.3 规划中（尚未实现）

| 接口组 | 规划中的限制 |
| --- | --- |
| 检索 | 60 次/分钟/用户 |
| 上传 | 10 次/分钟/用户；单文件 50 MB；ZIP 200 MB |
| 并发流 | 5 并发流/用户 |
| Agent 运行 | 最长 5 分钟/次 |
| 评测批量 | 1 次/分钟/用户（后台异步跑） |

> 本节此前描述的是一套"Redis 令牌桶 + 全接口分组限流"，**全都没有代码**：
> `7001` / `7002` 两个错误码已定义、已映射（429 / 403），但**零抛出点**，`nexus-app` 里也没有
> `RedisTemplate`。2026-10-02 只实现了上表 §12.1 那一列；其余保持"规划中"，不再写成已完成。

---

## 13. 接口与 Phase 对照

| 接口组 | 落地 Phase |
| --- | --- |
| `/api/auth/**`、`/api/users/**` | Phase 0 |
| `/api/chat/**`（含 SSE） | Phase 1（RAG 部分 Phase 3） |
| `/api/knowledge-bases/**`、`/api/documents/**`、`/api/import-tasks/**` | Phase 2 |
| `/api/search/**`（跨库检索与 explain，§4.2） | Phase 3（已实现） |
| `/api/tools/**` | Phase 5 |
| `/api/agents/**`、`/api/agent/runs/**`、`/api/tool-invocations` | Phase 6 |
| `/api/mcp/**` | Phase 7（目标态，尚未实现） |
| `POST /mcp`（进程内 MCP Server，见 §7.1） | 已实现（4.3 起；身份 5.1 起改为 JWT） |
| `/api/mcp-servers/**`（外部 MCP server 客户端，§7.2） | 已实现（迁移 V32） |
| `/api/plugins/**`（插件开关与配置，§7.3） | 已实现（迁移 V32） |
| `/api/workflows/**`、`/api/workflow-runs/**` | Phase 8 |
| ~~`/api/eval/**`~~（已移除）、`/api/prompts/**` | Phase 9 |
| `/api/observability/{overview,trend,models,requests,requests/{id}}`（§10.1） | 已实现（迁移 V31）；~~`summary`/`agents`~~ 已移除 |
| `/api/automations/**`（§14） | 已实现（迁移 V30） |

---

## 14. 自动化（迁移 V30）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/automations` | 新建定时任务 |
| `GET` | `/api/automations` | 我的任务列表（分页，按 `updatedAt` 倒序） |
| `PUT` | `/api/automations/{id}` | 局部更新（省略的字段不改） |
| `DELETE` | `/api/automations/{id}` | 删除任务（逻辑删，运行记录保留） |
| `POST` | `/api/automations/{id}/run` | 立即运行一次 |
| `GET` | `/api/automations/{id}/runs` | 最近 N 条运行记录（默认 20，上限 100） |

> ✅ **本表均已实现**（`AutomationController` + `AutomationService` + `AutomationRunner`）。
> 表结构见 `database.md` 的 `automation` / `automation_run`；调度见本节末。

**请求体（`POST`）**

```json
{
  "name": "每日知识库简报",
  "prompt": "汇总知识库中今天新增的文档，按主题给出 5 条要点。",
  "agentId": null,
  "kbIds": [],
  "schedule": "daily",
  "cron": null,
  "enabled": true
}
```

- `name` 必填（≤128），`prompt` 必填（≤4000），`schedule` 必填且只接受 `hourly|daily|weekly|custom`。
- `agentId` 省略 = 用账户默认 Agent（服务端幂等补齐内置默认 Agent）。
- `kbIds` 省略/空数组 = 不限定检索范围。
- `enabled` 省略默认 `true`。

**响应体（`AutomationResponse`）**

```json
{
  "id": "1800000000000000001",
  "name": "每日知识库简报",
  "prompt": "汇总知识库中今天新增的文档…",
  "agentId": null,
  "agentName": "默认助手",
  "kbIds": [],
  "kbNames": [],
  "schedule": "daily",
  "cron": "0 9 * * *",
  "enabled": true,
  "nextRunAt": "2026-10-25T09:00:00",
  "lastRunAt": "2026-10-24T09:00:03.512",
  "lastStatus": "SUCCEEDED",
  "createdAt": "2026-10-20T11:02:41.003",
  "updatedAt": "2026-10-24T09:00:03.512"
}
```

**运行记录（`AutomationRunResponse`）**

```json
{
  "id": "1800000000000000009",
  "automationId": "1800000000000000001",
  "triggerType": "SCHEDULED",
  "status": "FAILED",
  "answer": null,
  "errorCode": 4002,
  "errorMessage": "模型响应超时",
  "promptTokens": 812,
  "completionTokens": 0,
  "totalTokens": 812,
  "startedAt": "2026-10-24T09:00:00.120",
  "finishedAt": "2026-10-24T09:00:31.884",
  "durationMs": 31764
}
```

`status` 只有 `RUNNING / SUCCEEDED / FAILED`；`triggerType` 为 `MANUAL`（点了立即运行）或 `SCHEDULED`（到点触发）。

**五条容易搞错的契约事实**

1. **`POST /{id}/run` 是同步的，且失败也返回 200**。请求返回时这一跑已经结束，
   成败看响应体的 `status`；`FAILED` 时 `errorCode`/`errorMessage` 一定有值（不是只写日志）。
   前端不要写成"触发后轮询"，也不要用 try/catch 判断失败。
2. **`schedule` 决定 `cron`**。`hourly/daily/weekly` 三个固定频率由服务端用预设覆盖
   （`0 * * * *` / `0 9 * * *` / `0 10 * * 1`），请求里传的 `cron` 会被忽略；
   只有 `schedule=custom` 才使用请求里的 `cron`（5 段或 6 段都接受，6 段时第 1 段是秒）。
   `cron` 非法 → `400` + `9003`。
3. **`PUT` 的"不改"和"清空"是两种信号**：字段省略 = 不改；`kbIds: []` = 解绑全部知识库；
   想解绑回默认 Agent 必须显式传 `useDefaultAgent: true`（`agentId` 省略只表示不改）。
4. **`lastStatus` 可能是 `null`**（建好还没跑过），别把它渲染成"排队中"。
5. **`{id}/runs` 只返回最近 N 条**（默认 20、上限 100），不做全量分页——
   任务级历史是展示用途，需要长期审计请查 `automation_run` 表。

**错误码**

| 码 | HTTP | 场景 |
| --- | --- | --- |
| `9001` | 404 | 任务不存在或不属于当前用户（不区分，防枚举） |
| `9002` | 404 | 运行记录不存在 |
| `9003` | 400 | `schedule` 不在枚举内，或 `cron` 解析失败 |
| `3001` | 404 | `kbIds` 里有不属于当前用户的知识库 |

**调度（多实例安全）**

- `@Scheduled(fixedDelay = 30s)` 扫 `enabled = 1 AND next_run_at <= now()` 的任务（每批 20 条），
  逐条用 `UPDATE automation SET next_run_at = ? WHERE id = ? AND next_run_at = ?` 做 CAS 抢占：
  抢到（影响行数 1）才执行，抢不到说明别的实例已经推进过这一轮，直接跳过。
- 一次到点触发 = 一条 `automation_run`；先插 `RUNNING` 再执行，结束写终态、耗时与 token。
- 到点触发失败会按 `nexus.automation.max-retries`（默认 2）重排重试，延迟
  `nexus.automation.retry-delay-ms`（默认 60s）；**手动运行不重试、不消耗重试预算**。
- `nexus.automation.scheduler-enabled=false` 可整体关掉调度（测试 profile 就是这么跑的）。

