# 安全机制（代码事实版）

> 本文只记录**代码里真实存在**的安全机制，每一条都带 `文件:行号` 证据。
> 设计稿、TODO、以及本项目**没有**的机制，统一放在文末「§已知安全边界与未实现」，绝不在正文写成"已实现"。
> 本文只描述现状，不描述目标态；如与 `docs/decisions.md` ADR-020 的口径冲突，以代码为准。

---

## 1. JWT（访问令牌）

### 1.1 签发

- 签发服务：`nexus-app/.../auth/token/TokenService.java:60-79`（`issueAccessToken`）。
- 算法：**HS256**，对称密钥。签发头写死 `JwsHeader.with(MacAlgorithm.HS256)`（`TokenService.java:75`）。
- 编码器：`NimbusJwtEncoder(new ImmutableSecret<>(properties.secretKey()))`（`SecurityConfig.java:138-140`）。
- 声明（claims，`TokenService.java:64-73`）：

  | claim | 值 | 行号 |
  |---|---|---|
  | `iss` | `nexus.jwt.issuer`，默认 `prisdvl-nexus`（5.9 九 起**校验**：不符或缺席 → 401） | `TokenService.java:65`，默认值见 `NexusJwtProperties.java:35` |
  | `iat` | 签发时刻 | `TokenService.java:66` |
  | `exp` | 签发时刻 + TTL，默认 30 分钟 | `TokenService.java:67`，TTL 见 `NexusJwtProperties.java:39` |
  | `sub` | 用户 ID（`String.valueOf(userId)`） | `TokenService.java:68` |
  | `jti` | 随机 UUID | `TokenService.java:69` |
  | `username` | 登录名 | `TokenService.java:70` |
  | `roles` | 角色码列表 | `TokenService.java:71` |
  | `ver` | 签发时的 `sys_user.token_version` | `TokenService.java:72`，常量名 `CLAIM_VERSION = "ver"`（`:42`） |

- 登录时签发的调用点：`AuthServiceImpl.java:162-165`（把数据库里的 `token_version` 一并写进 `ver`）。
- `jti` 只是**签发时生成了**，代码中没有任何消费方（`TokenService.java:29` 注释自陈"目前仍未使用"）。

### 1.2 密钥来源与 fail-fast

- 配置项：`nexus.jwt.secret`，来自环境变量 `JWT_SECRET`（`application.yml:102`）。
- 属性类用 `@Validated` + `@NotBlank` 约束（`NexusJwtProperties.java:25-31`）；密钥在 `secretKey()` 中还会强制 **UTF-8 至少 32 字节**，不足直接抛 `IllegalStateException`（`NexusJwtProperties.java:57-63`）。
- 缺失时 fail-fast：主配置里默认值是**空**（`${JWT_SECRET:}`，`application.yml:102`），空串触发 `@NotBlank` → 应用启动失败。设计说明见 `NexusJwtProperties.java:18-21` 与 `application.yml:93-99`。
- **注意一处例外**：`local` profile 在仓库里**提交了一个开发用默认密钥**（`application-local.yml:40`）。因此"JWT_SECRET 缺失即启动失败"只在非 local profile 成立；local profile 下会被这个默认值兜住（这正是 `deploy/docker-compose.yml` 要用 `${JWT_SECRET:?…}` 再兜一层的原因，见 §13）。

### 1.3 校验位置

- 安全链：`SecurityConfig.java:77-109`，`@Order(2)` 的 `SecurityFilterChain`。
- 启用 OAuth2 Resource Server 的 JWT 校验：`SecurityConfig.java:97-99`。
- 解码器：`NimbusJwtDecoder.withSecretKey(...).macAlgorithm(MacAlgorithm.HS256)`（`SecurityConfig.java:155-157`）——**限定算法为 HS256**，防算法混淆。
- 校验器：`new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(...), tokenVersionValidator)`（`SecurityConfig.java:169-170`）。`createDefaultWithIssuer` 含时间戳校验（exp/nbf）**与签发者校验（iss）**，另加令牌版本校验，见 §3。
  注意 `setJwtValidator` 是**替换**语义而非追加：这三个校验器必须挂在同一个 `DelegatingOAuth2TokenValidator` 上，漏掉任何一个都不会报错，只会静默少一道校验。
- 放行端点：仅 `/api/auth/register`、`/api/auth/login`（`SecurityConfig.java:64-67`）与 `/actuator/health*`、`/actuator/info`、`/error`（`:70-75`）；`/api/admin/**` 要求 `hasRole("ADMIN")`（`:94`）；其余一律 `authenticated()`（`:96`）。
- 认证失败 → 401 + `UNAUTHENTICATED(2001)`，由 `RestAuthenticationEntryPoint.java:40-43` 输出，且响应体不携带失败细节（类注释 `:28` "入口对所有认证异常都是一句话"）。

### 1.4 已知缺口（如实）

- ~~**`iss` 写了但从不校验**~~ → **已于 2026-10-02（Phase 5.9 九）关闭**。此前签发时写入
  （`TokenService.java:65`），校验侧却只有 `createDefault()`（时间戳）+ `TokenVersionValidator`，
  `iss` 的取值对令牌是否被接受没有任何影响。现在校验器是
  `JwtValidators.createDefaultWithIssuer(...)`（`SecurityConfig.java:170`），
  错误或缺席的 `iss` 一律 401。它此前算不上可被利用的缺口（对称密钥已经决定了
  只有本应用签得出令牌），关闭它的理由是：**一个写了却从不验证的声明，会让人以为这条链路
  已经校验过它**——将来出现第二个签发方、或别的组件共用同一个密钥时，这一条就是唯一能
  区分"谁签的"的东西。测试：`JwtIssuerValidationTest`（同一套声明签三支令牌、
  只有 `iss` 不同：正确放行 / 错误 401 / 缺失 401）。
- ~~**无 refresh token**~~ → **已实现**（2026-10，迁移 V21 `sys_user_session`）：opaque refresh +
  轮转 + 设备会话，见 §1.5。
- ~~**无服务端注销**~~ → **已实现**（同期）：`POST /api/auth/logout` 在服务端撤销当前设备的
  refresh 会话，见 §1.5。**仍存在的残留边界**：access JWT 本身依旧不可即时吊销
  （改密 / 软删除外，见 §3），只能等其 30 分钟过期——列入文末「已知安全边界」#2。

### 1.5 Refresh Token 轮转与服务端注销（设备会话）

- 登录成功后除 access JWT 外另发一支 **opaque refresh token**（`AuthServiceImpl.java:175`，
  `RefreshTokenService.issueSession`，`RefreshTokenService.java:90-94`）；刷新窗口默认 7 天
  （`nexus.jwt.refresh-token-ttl`，`application.yml:135`，`NexusJwtProperties.java:47`）。
- **库存哈希、不存明文**：refresh 仅在签发当次明文返回；持久化的是哈希
  （`RefreshTokenHasher.hash`，`RefreshTokenService.java:248`；表 `sys_user_session`，迁移 V21）。
- **轮转（rotation）**：`POST /api/auth/refresh`（`AuthController.java:56-60`）执行
  "条件撤销旧会话（独立事务先提交）→ 签新 access + 新 refresh"（`RefreshTokenService.java:102-146`）。
  并发使用同一支 refresh 时只有一个请求赢，输的一方 401——**refresh 重放会被拒**。
  账户状态在撤销前检查：禁用 / 软删用户不能换发新令牌（`:105`、`requireEnabledUser :234-240`）。
- **服务端注销**：
  - `POST /api/auth/logout`（`AuthController.java:67-69`）：按 refresh 哈希撤销当前设备会话，
    幂等、不区分"会话不存在"与"已撤销"（`RefreshTokenService.java:149-164`）。
  - `GET /api/users/me/sessions` 列设备、`DELETE /api/users/me/sessions/{id}` 撤指定设备、
    `POST /api/users/me/sessions/revoke-all` 全部退出（`UserSessionController.java:42-57`；
    只能撤自己的会话，越权撤他人设备抛 `SESSION_NOT_FOUND`，`RefreshTokenService.java:167-175`）。
- refresh / logout 是公开端点，凭证是请求体里的 refresh token 而非 access JWT
  （`SecurityConfig.java:67-72`）。Spring Security 的 `.logout()` 仍关闭（`SecurityConfig.java:94`）、
  请求链仍 `STATELESS`（`:95`）——这里的"会话"是 `sys_user_session` 行，不是 HTTP Session。

---

## 2. BCrypt 密码哈希

- 编码器 Bean：`new BCryptPasswordEncoder()`（`SecurityConfig.java:130-134`）。**未显式传入强度参数**，即使用 Spring 默认强度（10）；代码中没有 `strength(...)` 调用。
- 注册时编码：`passwordEncoder.encode(request.password())`（`AuthServiceImpl.java:113`）。
- 改密码时编码：`AuthServiceImpl.java:210`（同一条 UPDATE，见 §3）。
- 校验用 `matches`：登录 `AuthServiceImpl.java:141`；改密码验原密码 `AuthServiceImpl.java:198`。
- 存储字段：`sys_user.password_hash`，实体注释"BCrypt 密码哈希，绝不存明文"（`SysUserPO.java:35-36`）。
- 哈希不出现在任何响应里：`UserInfoResponse` 刻意不包含该字段（`UserInfoResponse.java:8-9`）。
- **明文不入日志**：登录失败只记录 `username`（`AuthServiceImpl.java:142`）；全仓 grep `log.*password` 无匹配。
- 密码长度约束（校验层，不是哈希层）：注册 8–64 位（`RegisterRequest.java:26-28`）、改密码新密码 8–64 位（`ChangePasswordRequest.java:23-24`）；登录接口刻意不做长度校验（`LoginRequest.java:8-17`）。

---

## 3. Token Version（改密码即刻吊销全部已签发令牌）

### 3.1 字段与写入

- 字段：`sys_user.token_version`（`SysUserPO.java:50-57`），迁移 V18，注册时不赋值、DB 默认 0。
- 签发时把当前版本写入 `ver` claim（`AuthServiceImpl.java:162-165`，`TokenService.java:72`）。
- 改密码时，**在同一条 UPDATE 里**同时改哈希并把版本 +1（`AuthServiceImpl.java:208-211`）：
  `setSql("token_version = token_version + 1")`。注释明确"分成两次 UPDATE 会留下'密码已改、版本没动'的中间态"（`:205-207`）。
- 改密码方法**刻意不加 `@Transactional`**，理由写在 `AuthServiceImpl.java:188-192`：挂事务会让缓存失效跑在提交之前，产生旧值回填缓存直到 TTL 的竞态。

### 3.2 校验逻辑

`TokenVersionValidator.java`（挂在 `jwtDecoder` 上，随 JWT 解码一起执行）：

- 取 `sub` 解析为 userId，失败即拒绝（`:46-49`）。
- 从 `UserAuthCache.expectedTokenVersion(userId)` 取当前版本；**返回 null（用户不存在/已软删）即拒绝**（`:51-54`）。
- 令牌中的 `ver`：`Number` 或可解析的 `String`，**缺失或类型异常一律按 0**（`:73-87`）——因此升级前签发的无 `ver` 令牌仍然有效（见类注释 `:18-20`）。
- `presented != expected` → 拒绝（`:56-62`）。
- 拒绝以 `JwtValidationException` 冒到 `RestAuthenticationEntryPoint` → 401 + `UNAUTHENTICATED(2001)`（类注释 `:25-28`）。

### 3.3 缓存与失效

- `UserAuthCache.java`，cache-aside（读穿 + 主动失效，`:15-23`）。
- 缓存键：`nexus:auth:tv:{userId}`（`:40`、`:110-112`）；TTL 默认 **5 分钟**（`NexusJwtProperties.java:49`）。
- 读缓存失败（Redis 挂）→ **回落查库**（`:89-99`）。这是本项目唯一"降级"而非 fail-open/closed 的安全路径：DB 是版本号的事实来源（`:24-28`）。
- 用户不存在（软删）→ 返回 null 且**不缓存**，避免出现"复活窗口"（`:70-72`）。
- `invalidate(userId)` 在改密码后调用（`AuthServiceImpl.java:214`），尽力而为：失败只计数 `SECURITY_TOKEN_VERSION_INVALIDATE_FAILED` 并记 WARN（`UserAuthCache.java:79-87`）。
- 失效失败的后果**有界**：旧令牌最多再存活一个缓存 TTL（默认 5 分钟），类注释 `:30-33`。
- 软删除立即生效：MyBatis-Plus `@TableLogic`（`SysUserPO.java:64-65`）让 `selectById` 自动追加 `deleted = 0`，因此软删用户会被 `expectedTokenVersion` 判为 null → 令牌被拒。

---

## 4. 登录失败冻结（LoginAttemptLimiter）

- 组件：`nexus-app/.../security/LoginAttemptLimiter.java`。
- 触发点全部在 `AuthServiceImpl.login`：进门前 `checkAllowed`（`AuthServiceImpl.java:136`）、凭证错误 `recordFailure`（`:143`）、成功 `reset`（`:153`）。
- 键与语义：
  - 失败计数键 `nexus:auth:lf:{username}`，TTL = 窗口（`LoginAttemptLimiter.java:34,76`）。
  - 冻结标记键 `nexus:auth:ll:{username}`，存在即冻结，TTL = 冻结时长（`:37,78`）。
  - 失败达到阈值时写冻结标记（`:77-80`）。
- 阈值（可配置，默认值）：
  - `nexus.security.login.max-failures` = **5**（`NexusSecurityProperties.java:99-100`；`application.yml:119`）。
  - 窗口 `window` = **15 分钟**（`NexusSecurityProperties.java:103`；`application.yml:120`）。
  - 冻结时长 `lock-duration` = **15 分钟**（`NexusSecurityProperties.java:106`；`application.yml:121`）。
- 冻结中调用 `checkAllowed` 抛 `RATE_LIMITED(7001)` → HTTP 429（`LoginAttemptLimiter.java:65-69`；`ErrorCode.java:98`；`ErrorCodeHttpStatusMapper.java:75`）。即使密码正确也拦（`:54-56`）。
- **维度是按用户名，不是按 IP**（`:22-26`）：已知代价是**定向 DoS**——攻击者能用错密码把某个账号刷到冻结。缓解只是短窗口/短冻结，不是"未实现"。
- **fail-open 语义（明确）**：存储（Redis/内存）不可用时**一律放行**，只计数。三处降级路径：`checkAllowed` 返回（`:60-64`）、`recordFailure` 吞掉（`:81-83`）、`reset` 吞掉（`:90-92`），统一走 `degrade(...)`（`:103-106`），计数 `SECURITY_STORE_UNAVAILABLE`。
  - 理由（类注释 `:16-20`）：反向（fail-closed）会让 Redis 一挂全体用户登不进来——用"防暴破"制造"整个系统不可用"是拿更大的事故换更小的事故。
- **注意**：登录冻结的 429 是在 Service 层抛出的业务异常，不经过 §5 的过滤器，因此**不带 `Retry-After` 响应头**（全仓 `Retry-After` 只出现在 `RateLimitQuotaFilter.java:161`）。

---

## 5. 请求频率限制（RateLimiter + RateLimitQuotaFilter）

### 5.1 算法：固定窗口，不是令牌桶

- `RateLimiter.java:14-21` 明确：**固定窗口计数**，键里带时间片（分钟/小时）作为窗口边界，一次 `INCR`，不用 Lua、不用滑动窗口。已知代价是窗口边界处可能放过约两倍请求（`:20-21`）。
- 时间片格式：用户维度 `yyyyMMddHHmm`（`:37`），注册维度 `yyyyMMddHH`（`:40`）。键 TTL 比窗口长一档（2 分钟 / 2 小时，`:47-48`）以避开时钟边界。
- 时间基准是**服务器本地时间**（`LocalDateTime.now()`），不是 UTC。

### 5.2 维度与阈值

| 入口 | 维度 | 键 | 阈值（默认） | 证据 |
|---|---|---|---|---|
| 模型入口 + `extra-paths` | 用户 | `nexus:rl:u:{userId}:{分钟片}` | **20 次/分钟** | `RateLimiter.java:67-74`；`NexusSecurityProperties.java:48`；`application.yml:133` |
| 注册 | 来源 IP | `nexus:rl:ip:{ip}:{小时片}` | **10 次/小时**（dev profile 放宽为 1000） | `RateLimiter.java:86-93`；`NexusSecurityProperties.java:112-118`；dev 覆盖 `application-local.yml:51` |
| 改密码（extra-path） | 用户 | 同用户分钟桶 | 只限流，**不计 token 配额** | `NexusSecurityProperties.java:52-57`；`application.yml:134-137` |

- **不信任 `X-Forwarded-For`**：注册限流取 `request.getRemoteAddr()`（`RateLimitQuotaFilter.java:99`；`RateLimiter.java:78-84`）。代理后 `getRemoteAddr()` 是代理地址，全体用户共用一个桶——这是已知边界。
- 过滤器路由三分支：注册路径按 IP（`RateLimitQuotaFilter.java:94-99`）、配额路径限流 + 占请求额度 + 查 token 预算（`:100-106`）、extra 路径只限流（`:107-112`）；其余路径直接放行（`:49-51` 注释说明刻意默认不限流）。
- 过滤器位置：挂在 `BearerTokenAuthenticationFilter` 之后（`SecurityConfig.java:103-106`），`SecurityContext` 已填充但早于控制器。并显式禁用容器的二次注册（`SecurityConfig.java:122-128`），否则限流器会被跑两遍、额度砍半。

### 5.3 返回头

- `Retry-After` **只在 HTTP 429 时设置**（`RateLimitQuotaFilter.java:158-162`）：
  - 分钟级限流 / 登录冻结类：默认 60 秒（`:57-58`）。
  - 注册（小时桶）：3600 秒（`:96-98`）。
- **配额用尽（403）不设 `Retry-After`**：因为要等次日 00:00，回 60 秒会诱导客户端白试（`:158-160` 注释）。
- 响应体统一为 `Result.fail(errorCode, message)`（`:163`）。

### 5.4 fail-open

- 限流是 **fail-open**：`RateLimiter.increment` 捕获存储异常后返回 `1L`（必然不超限）并计数 `SECURITY_STORE_UNAVAILABLE`（`RateLimiter.java:95-104`）。理由（类注释 `:23-26`）：限流是"防滥用"，不是业务正确性，为它把正常用户挡在门外本末倒置。

### 5.5 `redis-enabled=false` 时降级到进程内实现

- 开关：`nexus.security.redis-enabled`，**默认 false**（`NexusSecurityProperties.java:32`；`application.yml:114`）；`local` profile 置 true（`application-local.yml:45`）。
- 装配二选一：true → `RedisSecurityStore`，false/缺省 → `InMemorySecurityStore`（`SecurityStoreConfiguration.java:27-40`）。
- 内存实现是**单实例**的：多实例部署下各算各的，等于额度乘以实例数（`SecurityStore.java:14-21`；`.env.example:34-38`）。两者都不持久化，重启即清（`SecurityStore.java:21`）。
- 即便用 Redis，`maxmemory-policy=allkeys-lru` 会**淘汰计数键**，表现为"配额被重置、限流漏几次"（`deploy/docker-compose.yml:105-108`）——配额不是硬保证。

---

## 6. 每日配额（QuotaService）

### 6.1 上限与键

- 请求上限 `nexus.security.quota.daily-requests` = **200 次/天**（`NexusSecurityProperties.java:66`；`application.yml:142`）。
- token 上限 `daily-tokens` = **200000 tokens/天**（prompt + completion）（`NexusSecurityProperties.java:69`；`application.yml:143`）。
- 键：`nexus:q:req:{userId}:{yyyyMMdd}`、`nexus:q:tok:{userId}:{yyyyMMdd}`（`QuotaService.java:34-35,152-157`）。
- 日期来自 `LocalDate.now()`（`:153,157`）——**JVM 默认时区的本地日历日，不是 UTC**。容器里设了 `TZ=Asia/Shanghai`（`docker-compose.yml:253`）。
- TTL = 距次日 00:00 的时长，下限 1 分钟（`QuotaService.java:160-164`）。边界是自然日，不是"从首次使用起 24 小时"（`:160`）。
- 计入配额的路径由 `nexus.security.quota.paths` 显式列出（`application.yml:144-161`），刻意**不写 `/api/chat/**`**，避免把读接口也算进额度（注释 `:148-153`）。属性类另有一套默认值（`NexusSecurityProperties.java:78-85`）。

### 6.2 判定与 fail-closed

- 请求额度：**先自增再判断**（`used > limit` 才拒），自增是原子预留动作（`QuotaService.java:62-69`；理由注释 `:55-61`）。
- token 预算：**只读不写**的事后闸门（`used >= limit` 才拒），因为事前不知道会花多少（`:71-85`）。
- **fail-closed**：存储不可用时**拒绝**，抛 `QUOTA_EXHAUSTED(7002)` + "配额服务暂时不可用"（`increment` `:131-139`、`read` `:141-150`）。类注释 `:16-27` 明确这是刻意选择：配额失效 = 真实费用被烧光，与限流 fail-open 的失败后果不对称。
- 超限返回：`QUOTA_EXHAUSTED(7002)` → HTTP **403**（`ErrorCode.java:99`；`ErrorCodeHttpStatusMapper.java:32-33`）。快照接口展示用量时用 `min(used, limit)` 收敛（`QuotaService.java:122`）。

### 6.3 用量由谁记录

链路：`UsageMeteringAdvisor`（挂在 `ChatClientFactory` 的 defaultAdvisor 上，覆盖全部模型调用入口）→ `ChatUsageRecorder` 端口 → `QuotaChatUsageRecorder` → `QuotaService.recordTokens`。

- `UsageMeteringAdvisor.java:22-31` 说明它经 `ChatClientFactory` 一次性覆盖 6 个调用点；流式取最后一帧有量的记录一次（`:99-121`）。
- 归属用户从 Advisor 上下文 `UserContextKeys.USER_ID` 取；取不到记 `recordUnattributed` 计数，不静默丢（`:142-148`）。系统调用（如重排）不进用户配额（`:138-141`）。
- 端口实现：`QuotaChatUsageRecorder.java:29-31` 调 `quotaService.recordTokens(...)`；Bean 装配见 `AppConfig.java:33-34`。
- `recordTokens` **尽力而为、绝不抛异常**（模型已调用，钱已花）：失败只计数 `QUOTA_RECORD_FAILED`（`QuotaService.java:94-108`）。

### 6.4 存储配额（StorageQuotaService）

与 §6 的「按天请求/Token 配额」并列，但两套东西互不相干：那一套限制**调用成本**，这一套限制**落盘体积**。

- **口径**：`kb_document.file_size`（原始文件字节）之和，条件 `deleted = 0`。不是解析后的正文、不是 chunk 行数、更不是向量体积——用户能理解「这个 PDF 有 12 MB」，理解不了「你占了 37 MB 的 chunk 行」。删除文档即刻释放额度（逻辑删除行不计）。
- **两个维度**：账号总量 `nexus.storage.total-bytes`（默认 **5 GiB** = `5368709120`）、单库上限 `nexus.storage.per-knowledge-base-bytes`（默认 **1 GiB** = `1073741824`）。环境变量 `NEXUS_STORAGE_TOTAL_BYTES` / `NEXUS_STORAGE_PER_KB_BYTES`。**配成 0 或负数表示该维度不限**（临时放开不必改代码）。
- **闸门位置**（`StorageQuotaServiceImpl.ensureRoom`）：
  1. 入队前预检（`KnowledgeImportServiceImpl`）——快速失败，不占导入队列；
  2. 落库事务内终检（`DocumentPersistServiceImpl`）——两个入口（单文件 / Vault 批量）都经过这里。
- **增量口径**：终检只校验 `file.size() - 原文档 file_size` 的正差额。同一路径用更小的文件覆盖导入不该被判超限（`DocumentPersistServiceImpl` 注释）。
- **超限返回**：`QUOTA_EXHAUSTED(7002)` → HTTP **403**，消息带可读差额（按 MB/GB 分档，避免「还差 0.0 MB」）。
- **fail-closed**：配额查询本身失败时导入直接失败，不静默放行。
- **已知边界（如实）**：① 没有「预留额度」，两个并发导入各自读到同一个 `used`，极限情况下合计可小幅超限——判定与写入在同一事务，但读的是已提交数据；② 上限是**全局配置**，没有 per-user / per-plan 的库表，用户不能自助调整；③ 限的是原始文件字节，`data/` 目录下的实际占用还要加上解析中间产物；④ 只拦导入，不拦检索/导出。

---

## 7. `CurrentUser.requireId()`

- 工具类：`nexus-app/.../auth/security/CurrentUser.java`。
- `requireId()`：从 `SecurityContextHolder` 取 `Authentication`，其 principal 必须是 `Jwt`，返回 `Long.valueOf(sub)`；取不到抛 `BusinessException(UNAUTHENTICATED, 2001)`（`CurrentUser.java:30-36,47-56`）。
- **`user_id` 的唯一可信来源是 JWT 的 `sub`**，绝不接受请求参数/请求体里的 userId（类注释 `:11-13`）。
- 为什么 controller 一律用它：从参数取 userId 是水平越权的典型来源，攻击者改一个数字就能操作他人数据（`UserController.java:21-24,37-42`）。`UserController` 全部接口均无 userId 参数，统一 `CurrentUser.requireId()`：`/me`（`:45`）、`updateProfile`（`:55`）、`changePassword`（`:71`）、`/me/quota`（`:86`）。
- `idOrNull()`（返回 null 不抛）仅供"不一定已认证"的过滤器使用，避免公开端点因取不到用户而 500（`CurrentUser.java:38-46`）。

---

## 8. SQL 层用户隔离（requireOwned / requireAllOwned）

隔离**写在 SQL 的 WHERE 条件里**，不是"查回来再在内存里比对"。这本身是安全属性，不是性能优化：过滤进查询意味着数据根本不会越出用户边界。

- `KnowledgeBaseServiceImpl.requireOwned`（`KnowledgeBaseServiceImpl.java:259-272`）：一次 `selectOne` 同时 `.eq(id).eq(user_id)`（`:264-267`）；查不到（含不属于该用户）→ `KNOWLEDGE_BASE_NOT_FOUND(3001)`。
- `KnowledgeBaseServiceImpl.requireAllOwned`（`:274-311`）：一次 `selectList` 用 `.eq(user_id).in(id)`（`:288-293`）；随后按**请求顺序**逐个核对，**任一 id 查不到就整批失败**并抛 3001（`:299-307`）。
  - 为什么整批失败而不做部分成功（`:303-306`）：把越权探测变成"部分成功"比报错更糟——"绑定成功、检索却少查了一个库"不会报错。
  - `null` id 同样判为查不到（`:280-283`）；重复 id 去重保序（`:279-282`）。
- 为什么不能依赖"调用方记得加 user_id 条件"：`DocumentQueryServiceImpl.java:34` 明确"多租户隔离不能依赖调用方记得加条件"。

### 使用点清单（生产代码）

- **检索**：`ChunkSearchServiceImpl.java:99`（单库 requireOwned）、`:132`（多库 requireAllOwned）；`RagAnswerServiceImpl.java:103,117,222,230`；`VectorRetriever.java:83`；`HybridRetriever.java:71`；`KeywordRetriever.java:65`；`ChunkIndexServiceImpl.java:61,117`；`ChunkEmbeddingServiceImpl.java:70`；多库扇出的检索放大说明见 `MultiKnowledgeRetriever.java:70`。
- **文档**：`DocumentQueryServiceImpl.java:63,78,99`；`DocumentPersistServiceImpl.java:248`；`DocumentLinkServiceImpl.java:57,129`；`KnowledgeImportServiceImpl.java:81,88`；异步任务执行器 `ImportTaskExecutor.java:169`。
- **会话**：`ConversationServiceImpl.requireOwned`（`ConversationServiceImpl.java:202-217`，`.eq(id).eq(user_id)` 在 `:211`）；调用点 `:145,151,192`；`ChatServiceImpl.java:101`；`ChatStreamServiceImpl.java:124`；`ChatMessageServiceImpl.java:77,98,190`。
- **消息**：消息没有独立的 userId 列，隔离**经由会话归属**完成——所有消息操作先 `conversationService.requireOwned`（`ChatMessageServiceImpl.java:77,98,190`），删会话时按 conversationId 物理删消息（`ConversationServiceImpl.java:197-198`）。
- **Agent / 工作流**：`AgentService.java:116`（Agent 自身的 `requireOwned`）及多处调用（`:112,194,224,258,283,306,498`）；`WorkflowService.java:66`（校验工作流绑定的知识库归属）。

---

## 9. 越权返回 404，不返回 403

- 知识库：`requireOwned` / `requireAllOwned` 抛 `KNOWLEDGE_BASE_NOT_FOUND`，业务码 **3001**（`KnowledgeBaseServiceImpl.java:262,269,305`；`ErrorCode.java:56`）。
- 会话：`ConversationServiceImpl.requireOwned` 抛 `CONVERSATION_NOT_FOUND`，业务码 **8001**（`ConversationServiceImpl.java:205,214`；`ErrorCode.java:102`）。
- HTTP 状态：3001 与 8001 都映射到 **404 NOT_FOUND**（`ErrorCodeHttpStatusMapper.java:39,80`）。
- 即：**业务码是 3001 / 8001，HTTP 是 404**（两者都有）。
- 为什么不是 403：403 等于告诉攻击者"这个 id 确实存在"，404 不泄露资源存在性（`ErrorCodeHttpStatusMapper.java:78-80`；`ConversationServiceImpl.java:212-214`）。
- 措辞一致性：`requireAllOwned` 注释要求"不存在"与"存在但不属于你"用完全一致的错误信息（`KnowledgeBaseServiceImpl.java:303-304`）。

---

## 10. Zip Slip 防护

`VaultPackageExtractor.normalizePath`（`nexus-knowledge/.../imports/VaultPackageExtractor.java:174-190`）：

1. `\` 统一替换为 `/`（`:175`）。
2. 循环去掉开头的 `./`（`:176-178`）。
3. 以 `/` 开头或包含 `:/`（盘符，如 `C:/`）→ **拒绝**，抛 `INVALID_ZIP_STRUCTURE(3007)`（`:179-182`）。
4. 按 `/` 切分，**任何一段是 `..` 即拒绝**（`:183-188`）。

- **发现后是整批中止，不是跳过该条目**：`normalizePath` 从 `extract` 的解压循环内抛出（`:73`），异常直接终止整个导入。理由（`:168-172`）：带 `../` 的包不是正常 Obsidian 导出，继续处理没有意义，且应让用户看到明确拒绝原因。
- 同期"跳过而非中止"的是噪音条目：目录（`:68-71`）、隐藏段（`.obsidian`/`.trash`/`.git`/`.DS_Store`，`:74-77,192-200`）、不支持的类型（`:78-82`）、空文件（`:91-94`）。

---

## 11. Zip Bomb 防护（三层上限）

上限来自 `VaultScanOptions`（`VaultScanOptions.java:16-24`），全部按**解压后**计量：

| 层 | 上限 | 判定位置 | 触发的错误 |
|---|---|---|---|
| 条目数 | **5000** | 每读一个 entry 前计数，超过即抛（`VaultPackageExtractor.java:60-65`） | `INVALID_ZIP_STRUCTURE(3007)` → 400 |
| 单文件解压后字节 | **20 MB**（`20 * 1024 * 1024`） | 边读边累计，超限即抛（`:152-166`） | `FILE_SIZE_EXCEEDED(3006)` → 413 |
| 总量解压后字节 | **200 MB**（`200 * 1024 * 1024`） | 每读出一个文件后累加比较（`:84-90`） | `INVALID_ZIP_STRUCTURE(3007)` → 400 |

- 关键点：总量是按**解压后**的字节数判断的（`totalBytes += content.length`，`:85-89`），不是按上传的 zip 大小——注释原文"这才是 ZIP 炸弹的真实指标"（`:87`）。
- 常量定义：`VaultScanOptions.java:18-20`。
- `spring.servlet.multipart.max-file-size=200MB` 只是第一道粗筛，真正的限额在 `VaultPackageExtractor`（`application.yml:45-51`）。
- 另有"整体不是 ZIP"的兜底：`totalEntries == 0` 时拒绝，避免"导入成功 0 个文件"的误导（`:103-108`）。

---

## 12. 配置中的 secret

### 12.1 哪些是 secret

- `JWT_SECRET`（→ `nexus.jwt.secret`，`application.yml:102`）。
- `AI_CHAT_API_KEY`（→ 对话模型 provider `api-key`，`application.yml:253`）。
- `AI_EMBEDDING_API_KEY`（→ 云端百炼嵌入 provider `api-key`；compose 必填，见 §13）。
- 数据库 / 中间件口令：`MYSQL_PASSWORD`、`NEXUS_VECTOR_PASSWORD`、`REDIS_PASSWORD`、`RABBITMQ_PASSWORD`（`application.yml:57,81,181` 等）。
- 注意 `ES_USERNAME` / `ES_PASSWORD` 变量在 `.env.example:64-65` 存在，但当前代码里的 ES 接入未使用它们（compose 的 ES 关闭了安全模块，见 §13）。

### 12.2 默认值与缺失行为

- **JWT_SECRET**：主配置默认空（`application.yml:102`）→ `@NotBlank` → 启动 fail-fast（`NexusJwtProperties.java:30-31`）。**但 local profile 提交了开发默认值**（`application-local.yml:40`），会兜住它——因此 compose 层再用 `${JWT_SECRET:?…}` 强制（§13）。
- **AI_CHAT_API_KEY**：默认空（`application.yml:253`），**缺失不会导致启动失败**：对应 provider 被跳过并打 WARN，真正用到该角色时才报 `4006`（`application.yml:222-224`）。
- **数据库口令**：主/本地配置里带开发默认值（如 `application.yml:57,181`；`application-local.yml:16-18`）。

### 12.3 `.env` 与 `.env.example`

- `.env.example` 提交进仓库（仓库根 `.env.example`，以及 `deploy/.env.example`），用法都是 `cp .env.example .env` 再填真值（`.env.example:3-4`；`deploy/.env.example:4`）。
- `.env`（真实密钥）不入库。

### 12.4 `.gitignore` 覆盖

- `.env`、`.env.local`、`.env.*.local`、`.env.prod`（`.gitignore:34-38`）。
- `application-secret.yml/.yaml`、`application-prod.yml/.yaml`、`*.pem`、`*.p12`、`*.jks`（`:42-48`）。
- `.secrets/` 与 `*.key.txt`（`:98-99`）。本机确实存在 `.secrets/`，`deploy/.env.example:35-36` 提示可放 `ai-key.txt` 并用 shell 注入。
- `application-local.yml` 是**有意提交**的（只含 compose 开发默认口令，`:39-41` 注释说明）。
- 另：演示 JWT 一律不入库——归档前端副本 `docs/archive/web-vue/.demo-token`、`docs/archive/web-vue/.demo-kb-id`（历史路径；现行前端为 `frontend/`，勿再写回 `web/`）。

---

## 13. Docker 环境变量与暴露面

- **fail-closed 的 `${VAR:?…}` 写法**：`deploy/docker-compose.yml` 的 backend 环境里有
  **三个**必填变量——`JWT_SECRET: ${JWT_SECRET:?...}`（`:270`）、
  `AI_CHAT_API_KEY: ${AI_CHAT_API_KEY:?...}`（`:271`）、
  `AI_EMBEDDING_API_KEY: ${AI_EMBEDDING_API_KEY:?...}`（`:259`）。
  `:?` 语义 = 变量未设置或为空时 `docker compose` **直接报错退出，连容器都不创建**（注释 `:264-269`）。这是把 `nexus.jwt.secret` 的 fail-closed 前移到编排层，防止 `application-local.yml` 的开发默认密钥把"忘配密钥"掩盖成"启动成功"。
- **backend 端口只绑回环**：`backend.ports` 为 `"127.0.0.1:8080:8080"`（`:222-223`）——容器间由
  nginx 访问，宿主机可直连调试，局域网/公网不可达（注释 `:18-25,195-197`）。
- **只有 frontend 监听 0.0.0.0**：`frontend` 发布 `${NEXUS_FRONTEND_PORT:-8088}:80`（`:315-317`），
  是唯一对外入口。其余端口全部绑定 `127.0.0.1`（mysql `:46`、postgres `:78`、redis `:116`、
  es `:140`、rabbitmq `:170-171`、backend `:222-223`）。
- backend 健康检查打 `/actuator/health`（公开端点，`:288-295`）。
- ES 开发环境关闭安全模块：`xpack.security.enabled: "false"`（`:143-145`），注释注明生产必须开启。
- **嵌入流量走容器外 HTTPS**：backend 调云端百炼 `text-embedding-v4`，编排中没有本地模型容器
  （旧 `ollama` 服务已删除，`docker-compose.yml:186-190` 只保留恢复说明）。

---

## §已知安全边界与未实现

以下均为**代码中不存在或不完整**的机制，不是设计目标，也不应被理解为已实现。

1. **Token 存 localStorage，暴露于 XSS**：前端令牌存 `localStorage`（键 `nx-token`，过期时间另存 `nx-token-expires-at`；见 `frontend/src/lib/api.ts` 的 `tokenStore`），没有 HttpOnly Cookie。任何 XSS 都能读取并外带令牌。
2. **access JWT 在到期前不可单独吊销**：refresh 轮转与服务端注销（撤销 refresh 会话）已实现
   （见 §1.5），但 **access JWT 仍只凭 exp / `ver` / 用户存在性生效**——logout 之后，那支
   尚未过期的 access token 在其剩余 TTL（默认 30 分钟）内依然有效。能即时令 access 失效的
   现有路径只有：改密码（`token_version` +1，见 §3）与软删用户（§3.3）；停用账号同样不即时
   吊销（见 #6）。若设备遗失后要求"立即踢下线"，当前需要配合改密或管理侧停用 + 等过期。
3. ~~**`iss` 从不校验**~~ → **已关闭**（2026-10-02 Phase 5.9 九），见 §1.4。
4. **限流在存储不可用时 fail-open，可被绕过**：Redis/内存不可用时限流直接放行（`RateLimiter.java:95-104`）；登录冻结同样 fail-open（`LoginAttemptLimiter.java:60-64,81-92`）。
5. **`redis-enabled=false` 时多实例限流失效**：进程内实现各实例独立计数，额度乘以实例数（`SecurityStore.java:14-21`；`.env.example:34-38`）。即便开 Redis，`allkeys-lru` 淘汰也会重置计数（`docker-compose.yml:105-108`）。
6. **停用账号不立即吊销已签发的 access JWT**：登录时检查 status（`AuthServiceImpl.java:157-159`），
   refresh 轮转时也检查（`RefreshTokenService.requireEnabledUser :234-240`，故停用后**不能换新令牌**）；
   但 `TokenVersionValidator` / `UserAuthCache` 只按用户存在与版本号判断、**不检查 status**，
   故既有 access JWT 在其剩余 TTL（≤30 分钟）内仍有效，且已持有的 refresh 虽不可换新却不会被
   主动撤销（需管理侧删会话或改密）。（软删除会立即失效，见 §3.3，两者行为不同。）
7. **无安全响应头配置**：`frontend/nginx.conf` 未配置 `Content-Security-Policy`、`X-Frame-Options`、`Strict-Transport-Security` 等任何安全响应头（仅对 `index.html` 设 `Cache-Control`，`nginx.conf:42-44`）；主 `SecurityFilterChain` 也未显式调用 `.headers(...)`（全仓仅 `StandaloneDevSecurityConfig.java:30` 为 dev 的 H2 console 设置 `frameOptions.sameOrigin`）。静态页面响应不受后端安全链保护。
8. **无 TLS**：nginx 只 `listen 80`（`nginx.conf:9`），无 HTTPS 终结、无证书配置；backend 亦无 TLS。
9. **无 OAuth2 / OIDC 第三方登录**：`AuthServiceImpl.java:48-51` 明确这是"将来接入"方向，当前只有自建用户名口令。
10. **无 CSRF token**：`.csrf(disable)`（`SecurityConfig.java:91`）——理由是纯 Token API 不用 Cookie/Session（类注释 `:36-38`），并非遗漏；但这也意味着一旦引入 Cookie 承载凭证就需重新评估。
11. **无 mTLS、无 KMS/Vault；字段级加密只覆盖两列凭据**：密钥全部来自环境变量（`application.yml:102,253`），没有密钥管理服务。
    落库凭据已做字段级加密——`user_llm_config.api_key` 与 `user_embedding_config.api_key`（V25，BYOK 路线 A）
    存的是 AES-GCM 密文（`v1:` 前缀 + base64(IV‖密文‖tag)，`nexus-app/.../security/CredentialCipher.java`），
    KEK 由 `NEXUS_SECURITY_CRYPTO_KEK` 注入；未配置 KEK 时写入路径 fail-closed（报 9999），不会静默存明文。
    仍然欠缺的部分：**KEK 自己还是环境变量**（无 KMS 托管、无自动轮换，且换 KEK 会让既有密文全部解不开，
    需用户重新保存一次）；加密**只覆盖这两列**，其他落库内容仍靠"不写入敏感原值"的脱敏
    （`AgentTraceGuards`）而非加密；另外**加密前写下的历史明文行**只在用户读取/保存配置时才会被升级。
12. **无审计日志表**：认证/安全事件只走 slf4j 日志（如 `AuthServiceImpl.java:142,167`；`RateLimitQuotaFilter.java:165`），没有独立的审计日志表；`ObservabilityRegistry` 是进程内计数器（`LongAdder` + `ConcurrentHashMap`，`ObservabilityRegistry.java:15-21`），非持久化审计。
13. **无 IP 黑名单持久化**：只有基于键 TTL 的临时冻结/限流计数，没有持久化黑名单。
14. **ES 安全模块关闭（开发编排）**：`xpack.security.enabled: "false"`（`docker-compose.yml:144`）；且 ES 端口仅绑 `127.0.0.1`（`:140`）。
15. **有 Micrometer 指标、但无 Prometheus 等指标后端**：`nexus-common` 以 `optional` 依赖
    `micrometer-core`（`nexus-common/pom.xml:43-47`），`ObservabilityRegistry` 把计数/计时转发给
    `MeterRegistry`（`ObservabilityRegistry.java:115,130,159`），actuator 暴露
    `/actuator/metrics`（需认证，不在公开端点列表；`application.yml:103-116`）。
    **不存在** `micrometer-registry-prometheus`（全仓 pom 无该依赖），因此没有
    `/actuator/prometheus` 抓取端点、没有 Grafana 看板；代码中也没有任何 `@Timed` 注解。
16. **登录失败冻结是按用户名、可被定向 DoS**：攻击者能用错密码把他人账号刷到冻结（`LoginAttemptLimiter.java:22-26`）；跨用户名的分布式撞库与代理后 IP 失真本期不覆盖。
