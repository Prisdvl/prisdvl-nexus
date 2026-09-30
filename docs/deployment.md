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
# 任意 MCP 客户端 / curl 都可以：
curl -s -X POST localhost:8080/mcp -H 'Content-Type: application/json' -H 'X-Nexus-User: <你的用户id>' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
# tools/call: {"name":"knowledge_search","arguments":{"query":"缓存击穿"}}
```

**身份模型（如实声明）**：个人本机服务，用 `X-Nexus-User` 头指定用户 id，
不做 OAuth——不是公开 API，别暴露到公网。

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
4. **工程素养（讲 30 秒）**：174 个测试；/**每轮交付都 live 验证**（真实模型 + 真实 ES）；
   17 条 ADR + B-node 记录踩坑；前端有自己写的动效层；**观测注册表**把"被吞掉的降级"变成可巡检的数字。
5. **一句话收尾**：它不是 demo——`git log` 里每个提交都能对应一条设计决策，都回答过"为什么"。

## 6. 模型与环境事实

- 模型网关：OpenCode Zen（`https://opencode.ai/zen/go/v1`，模型 `deepseek-v4-flash`），
  已用守卫测试验证其**支持函数调用**（AgentLoopTest）。
- 嵌入：Ollama `bge-m3`（1024 维），维度在知识库创建时冻结（ADR-008）。
- 环境坑速查：Docker Desktop 常未启动 → 依赖容器的能力都有降级路径；
  **打包前必须先杀 java**（jar 文件锁会让 repackage 失败）。