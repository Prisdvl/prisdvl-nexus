# 部署与使用说明（Phase 11）

本文档写给两类人：**要把它跑起来的人**，和**要把它讲出来的人**（面试）。
架构细节见 [architecture.md](architecture.md)，接口见 [api.md](api.md)，踩坑记录见 [decisions.md](decisions.md)。

---

## 1. 两种跑法

### 方式 A：standalone —— 零依赖，5 分钟看到全貌

不需要 Docker，不需要 MySQL/ES/pgvector。后端用 H2 内存库，检索自动降级为 SQL 关键词匹配。

```bash
# 1. 后端：Java 21 + Maven
export JAVA_HOME=/path/to/jdk-21
export AI_CHAT_API_KEY=<OpenCode Zen 的 oc_ 开头 key>   # 只有问答/Agent/评测需要模型
mvn clean package -DskipTests
java -Xmx512m -jar nexus-app/target/nexus-app-0.0.1-SNAPSHOT.jar --spring.profiles.active=standalone

# 2. 前端：Node 18+
cd web
npm install
npm run dev            # http://localhost:5173
```

- 注册任意账号即可用；演示数据见第 3 节。
- **H2 是内存库：后端每次重启，数据都会清空**，需要重新导入笔记。
- standalone 下「检索 / 问答」两个页签与 Agent 的检索会**如实降级**（关键词匹配），
  而不是报错——这是刻意设计（见 decisions.md B.29）。

### 方式 B：完整栈 —— docker compose（混合检索 + 向量 + 流式全开）

```bash
cd deploy
cp .env.example .env          # 填上模型 key
docker compose up -d          # MySQL、PostgreSQL(pgvector)、Elasticsearch(IK)、Redis、RabbitMQ
cd .. && mvn clean package -DskipTests
java -jar nexus-app/target/nexus-app-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

启动后首次导入的笔记需要两步显式操作（默认 profile 关闭了自动嵌入/建索引，刻意让失败原因独立可查）：
知识库详情页 → 「向量化」→ 「建索引」。

## 2. 核心能力一览（对照界面）

| 页面 | 能力 | 依赖 |
| --- | --- | --- |
| 知识库列表 | 新建 / 导入 Vault zip（同步或异步） | 无 |
| 正文页 | Markdown 渲染 + 大纲跳转 + **关联面板**（出链/反向链接/待创建）+ **相关推荐** + **工作流** | 无 |
| 切片 | 切分结果 + 向量/索引状态（排查"为什么搜不到"） | 无 |
| 检索 | BM25 / 向量 / 混合（RRF 融合），命中项标注来源与分数 | ES + pgvector |
| 问答 | RAG：混合检索 → LLM 重排 → 上下文组装 → **流式回答**，引用可点跳原文，留档可回看 | full |
| Agent | 工具循环：模型主动决定检索（`knowledge_search`），每一步工具调用**可见** | full（检索降级可用） |
| 评测 | 对"问题 + 预期要点"跑一遍 Agent，报告要点命中率 + 引用有效性 | full |
| MCP | `POST /mcp` 暴露 `knowledge_search` / `qa_ask` 两个标准 MCP 工具 | full |

## 3. 演示数据（standalone 重建脚本）

standalone 每次重启后按下面的方式重建演示数据（一次写进一条命令）：

```bash
# 先造一个包含双链与标签的笔记包
mkdir -p /tmp/vault/Vault/Redis && cat > /tmp/vault/Vault/Redis/缓存击穿.md <<'EOF'
---
tags: [Redis, 缓存]
---

# 缓存击穿

## 互斥锁方案
用 SETNX 加锁，只让一个请求去数据库加载数据。

## 逻辑过期方案
把过期时间写进 value，异步刷新。
EOF
cd /tmp/vault && zip -r ../vault.zip Vault

# 注册登录 → 建库 → 导入（都是已封装的 REST 接口）
curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"Passw0rd!23"}'
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"Passw0rd!23"}' | jq -r .data.accessToken)
KB=$(curl -s -X POST localhost:8080/api/knowledge-bases -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"我的 Java 笔记"}' | jq -r .data.id)
curl -s -X POST "localhost:8080/api/knowledge-bases/$KB/vault" -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/vault.zip;type=application/zip"
```

## 4. 把知识库接进外部 LLM 客户端（MCP）

项目自带最小 MCP Server：`POST http://localhost:8080/mcp`。

```bash
# 1) 先登录拿一个 JWT（身份就是它，没有别的来源）
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"Passw0rd!23"}' | jq -r .data.accessToken)

# 2) 任意 MCP 客户端 / curl 都要带这个令牌：
curl -s -X POST localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
# tools/call: {"jsonrpc":"2.0","id":2,"method":"tools/call",
#              "params":{"name":"knowledge_search","arguments":{"query":"缓存击穿"}}}
```

**身份模型**：与全站一致——`Authorization: Bearer <JWT>`，用户身份取自令牌的 `sub`。
**没有任何请求头可以声明"我是谁"**（2026-10-01 之前有：`X-Nexus-User`，它使 `/mcp` 成为一条
免认证即可读任意用户知识库的路径，见 decisions.md B.42）。

因此：

- MCP 客户端配置里要放一个**令牌**（多数客户端支持自定义 header），令牌过期后需重新登录；
- 令牌等同于该用户的全部数据权限——**不要写进脚本仓库、不要贴进聊天**；
- 想只在本机使用，就把服务绑到 `127.0.0.1`（例如 `server.address=127.0.0.1`）：
  **隔离靠网络边界，不靠请求头**。仍然不要把 8080 暴露到公网。

## 5. 简历 / 面试讲解大纲（3 分钟讲法）

1. **一句话**：个人知识库 AI 助手——导入 Obsidian 笔记，混合检索 + 带引用的流式问答 + 会自己查资料的 Agent。
2. **架构**：Maven 多模块 modulular monolith（common → ai → knowledge → app），
   "能拆要是微服务，但不拆"的理由：数据都在一个库、一个进程内方法调用，
   逻辑边界靠模块依赖方向保证。
3. **三个技术亮点（各讲 30 秒）**：
   - **检索**：BM25（ES+IK 中文分词）+ bge-m3 向量 + **RRF 融合**，模型无关（可换供应商）；
   - **RAG**：重排（失败自动降级）+ 上下文预算 + **引用可核对**（区分"用到的资料"与"召回到的"），
     资料为空**不调用模型**——从根上防幻觉；流式输出解决长延迟体感；
   - **Agent**：用户可控三要素 = 痕迹可见 + 轮数有界 + 降级如实；再往上
     **MCP Server** 把知识库暴露成标准工具给外部客户端。
4. **工程素养（讲 30 秒）**：567 个测试（104 个类，0 失败 / 18 个依赖基础设施的 live 测试跳过）；
   /**每轮交付都 live 验证**（真实模型 + 真实 ES；5.6 那期改用 `standalone` + 本机原生 Redis，
   因为要避开 Docker；5.5.1 同样用 `standalone` + 桩 SSE 帧验了界面层，**只有"真 ES + 一个真挂掉的库"这条腿顺延到 5.7，这一条要如实说，不能含糊**）；
   22 条 ADR + B-node 记录踩坑；前端有自己写的动效层；**观测注册表**把"被吞掉的降级"变成可巡检的数字。
5. **一句话收尾**：它不是 demo——`git log` 里每个提交都能对应一条设计决策，都回答过"为什么"。

## 6. 模型与环境事实

- 模型网关：OpenCode Zen（`https://opencode.ai/zen/go/v1`，模型 `deepseek-v4-flash`），
  已用守卫测试验证其**支持函数调用**（AgentLoopTest）。
- 嵌入：Ollama `bge-m3`（1024 维），维度在知识库创建时冻结（ADR-008）。
- 环境坑速查：Docker Desktop 常未启动 → 依赖容器的能力都有降级路径；
  **打包前必须先杀 java**（jar 文件锁会让 repackage 失败）。