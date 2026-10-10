/* ============================================================
 * 原型模拟数据（仅用于界面参考，非真实数据）
 * ============================================================ */

export interface KbItem {
  id: number
  name: string
  desc: string
  docs: number
  updated: string
  tone: string
}

export const KBS: KbItem[] = [
  { id: 1, name: '我的 Java 笔记', desc: '集合框架 / JVM / 并发 / 调优，校招复习主线', docs: 12, updated: '2 小时前', tone: 'terracotta' },
  { id: 2, name: '操作系统与网络', desc: '进程线程、内存分页、TCP/IP、HTTP', docs: 8, updated: '昨天', tone: 'turquoise' },
  { id: 3, name: 'Redis 源码阅读', desc: '事件循环、字典、RDB/AOF 持久化', docs: 4, updated: '3 天前', tone: 'lime' },
  { id: 4, name: '算法与数据结构', desc: 'LeetCode 高频题解 + 复杂度分析', docs: 9, updated: '上周', tone: 'turquoise' },
  { id: 5, name: '面试八股合集', desc: 'Java / MySQL / 场景题 / HR 面', docs: 15, updated: '上周', tone: 'terracotta' },
  { id: 6, name: '软技能与项目', desc: '项目讲解、埋点复盘、自我介绍', docs: 5, updated: '2 周前', tone: 'lime' },
]

export interface DocItem {
  id: number
  name: string
  type: string
  size: string
  updated: string
  status: 'done' | 'indexing' | 'failed'
}

export const DOCS: DocItem[] = [
  { id: 1, name: '集合框架.md', type: 'Markdown', size: '48 KB', updated: '2 小时前', status: 'done' },
  { id: 2, name: 'JVM 内存模型.md', type: 'Markdown', size: '76 KB', updated: '2 小时前', status: 'done' },
  { id: 3, name: '并发编程要点.md', type: 'Markdown', size: '61 KB', updated: '昨天', status: 'done' },
  { id: 4, name: 'MySQL 索引原理.pdf', type: 'PDF', size: '1.2 MB', updated: '昨天', status: 'done' },
  { id: 5, name: 'Spring 事务笔记.docx', type: 'DOCX', size: '340 KB', updated: '3 天前', status: 'indexing' },
  { id: 6, name: '面经整理 v3.md', type: 'Markdown', size: '88 KB', updated: '上周', status: 'failed' },
]

export interface AgentItem {
  id: number
  name: string
  desc: string
  model: string
  tools: string[]
  kb: string
}

export const AGENTS: AgentItem[] = [
  { id: 1, name: '面试助手', desc: '结合知识库回答八股与场景题，追问式陪练', model: 'deepseek-v4-flash', tools: ['knowledge_search', 'calculator'], kb: '我的 Java 笔记' },
  { id: 2, name: 'Java 代码审查员', desc: '贴代码即审：命名、并发、异常处理', model: 'glm-5.3', tools: ['knowledge_search', 'code_runner'], kb: '我的 Java 笔记' },
  { id: 3, name: '简历润色师', desc: '把经历改写为 STAR 句式，对齐 JD', model: 'kimi-k2.6', tools: ['knowledge_search'], kb: '软技能与项目' },
  { id: 4, name: '默认助手', desc: '通用对话，未绑定知识库时直接作答', model: 'auto', tools: [], kb: '无' },
]

export const QA_HISTORY = [
  { q: 'B+ 树为什么比 B 树更适合数据库索引？', when: '昨天' },
  { q: '说说 MySQL 索引失效的常见场景', when: '3 天前' },
]

/* ---------- 对话：每个会话独立消息 ---------- */

export interface ToolCall {
  id: string
  /** 工具类型：决定 CallChip 的字形 */
  icon: 'terminal' | 'file' | 'search' | 'edit'
  name: string
  argument: string
  /**
   * 工具调用状态。cancelled / timeout 单独分出来，因为用户需要看到**原因**：
   * - cancelled：用户点了停止（不是失败，可继续）
   * - timeout：超过等待上限（可重试，也可跳过继续）
   */
  status: 'idle' | 'awaiting' | 'running' | 'done' | 'error' | 'cancelled' | 'timeout'
  /** 高风险工具：跑之前必须让用户确认（human-in-the-loop） */
  requiresConfirm?: boolean
  /** 给人看的风险说明（确认卡里展示） */
  explain?: string
  /** 预期耗时（毫秒）：进度条按它走，计时器同步 */
  expectedMs?: number
}

export interface ChatMsg {
  /** 字符串 id（同 SessionItem.id：雪花 ID 不能转 Number） */
  id: string
  role: 'user' | 'bot'
  text: string
  cites?: string[]
  /** Agent 的工具调用轨迹（原型：模拟数据 + 发送时逐条推进） */
  calls?: ToolCall[]
  /** 这一轮用到的上下文（检索到多少、引用了哪些、是否联网、是否省略了历史） */
  context?: RunContext
  /** 结构化结果（表格 / JSON / 图表）：可一键复制成 CSV/JSON 或下载 */
  result?: ResultPayload
}

/**
 * 这一轮回答用到的上下文（P1-2）。
 *
 * 存在的意义：用户看到答案不满意时，需要能判断**是检索没给到料，还是模型没答好**。
 * 只展示"调用了什么工具"不够 —— 那只说明动作，不说明拿到了什么。
 */
/**
 * 结构化结果（P1-3）。
 *
 * 回答里出现表格/JSON/图表时，Markdown 只能"看着像"，用户没法直接取走 ——
 * 复制出来是带竖线的文本，粘到 Excel 就散。所以把它作为一等数据渲染成结果卡。
 */
export type ResultPayload =
  | { kind: 'table'; title: string; columns: string[]; rows: (string | number)[][] }
  | { kind: 'json'; title: string; json: unknown }
  | { kind: 'chart'; title: string; unit?: string; series: { label: string; value: number }[] }

export interface RunContext {
  /** 参与检索的知识库名 */
  kbs: string[]
  /** 命中的切片数（检索侧） */
  retrieved: number
  /** 实际引用的文档数（= cites.length） */
  usedDocs: number
  /** 绑定知识库的文档总数（用来判断"是不是库太小"） */
  scannedDocs: number
  /** 参与的历史轮数 */
  historyTurns: number
  /** 是否联网 */
  web: boolean
  /** 附件 */
  attachments?: string[]
  /** 因超出窗口被省略的早期轮数（>0 时要在界面上明说，否则用户以为模型忘了） */
  truncated?: number
}

export interface SessionItem {
  /**
   * 字符串 id（后端是雪花 ID，序列化成字符串；实测 `"2108423642637959170"`）。
   * ⚠️ **不要 Number() 转一下** —— 19 位超出JS 安全整数上限，会抹掉末位。
   * 未落库的本地会话用 `tmp_` 前缀（见 lib/conversations.ts 的 `isTempId`）。
   */
  id: string
  title: string
  /**
   * 决策 4：存 Agent 的 id，**null = 用内置默认 Agent**（后端 `agentId` 就是这个语义）。
   * 展示名由 `GET /api/agents` 缓存的 id→name 映射得到，`null` 显示「默认助手」。
   *
   * 原来是自由文本（用户直接改名不落库），现已改为真实 id。
   */
  agentId: string | null
  /**
   * 会话模式：PLAIN / RAG / AGENT。
   *
   * ⚠️ **后端建会话后不可改**（实测：`ConversationServiceImpl.update` 只接受
   * title / pinned / kbIds / agentId / resetAgent，**没有 mode**；
   * 且 `agentId` 只在会话「已经是 AGENT 模式」时才允许设，
   * 否则报 1001「只有 AGENT 会话可以选用 Agent（当前模式 PLAIN）」）。
   * → mode 只能在**新建时**通过 `POST /api/chat/conversations` 指定；
   *   对已存在的 PLAIN 会话换 Agent 是后端不允许的，不是前端能绕过的。
   */
  mode?: 'PLAIN' | 'RAG' | 'AGENT'

  /** 后端字段名是 `kbIds`；这里沿用 `kbs` 以免动 13 个引用方 */
  kbs: string[]
  updated: string
  pinned?: boolean
  /**
   * 消息是否已从后端加载。
   *
   * ⚠️ **不能靠 `messages.length === 0` 判断"没加载"** ——
   * 那会把「已加载但确实是空的会话」和「还没请求」混成同一个状态，
   * UI 会永远显示加载中。决策 2 的懒加载必须靠这个显式标记。
   */
  messagesLoaded: boolean
  messages: ChatMsg[]
}

/**
 * 种子消息 / 会话 id 生成器。
 *
 * ⚠️ 原来是 `Math.random()`（返回 number）。后端雪花 id 是 19 位字符串，
 * 类型统一成 string 后这里必须生成字符串，否则 TS 报错、
 * 且两种 id 在同一命名空间里会互相污染。
 *
 * 用计数器而不是随机数：种子数据是静态的，可预测的 id 更利于 QA 断言。
 * 前缀 `seed_` 与真实后端 id、临时 id（`tmp_`）三者互不冲突。
 */
let seedSeq = 0
const seedId = () => `seed_${++seedSeq}`

const A = (text: string, cites?: string[], calls?: ToolCall[], context?: RunContext, result?: ResultPayload): ChatMsg =>
  ({ id: seedId(), role: 'bot', text, cites, calls, context, result })

/** 工具调用构造器（原型：状态先给 done，运行中的推进在页面里模拟） */
/**
 * 上下文构造器（原型里是手写演示数据）。
 * 真实实现里这些数字来自后端：检索命中数、参与的历史轮数、被窗口截断的轮数。
 */
const CTX = (c: RunContext): RunContext => c

const T = (icon: ToolCall['icon'], name: string, argument: string, status: ToolCall['status'] = 'done', expectedMs = 1600): ToolCall =>
  ({ id: `${name}-${Math.random().toString(36).slice(2, 7)}`, icon, name, argument, status, expectedMs })
const U = (text: string): ChatMsg => ({ id: seedId(), role: 'user', text })

export const SESSIONS: SessionItem[] = [
  {
    id: 'seed_s1',
    title: '什么是 B+ 树？为什么适合做索引',
    agentId: null,
    kbs: ['我的 Java 笔记'],
    updated: '2 小时前',
    /* 种子数据自带消息，本就是已加载的 —— 避免进会话时多发一次请求 */
    messagesLoaded: true,
    pinned: true,
    messages: [
      U('B+ 树为什么适合做数据库索引？'),
      A(
        '原因有三：\n1. 非叶子节点只存键，单个页能装更多键，树高更矮，磁盘 IO 次数更少；\n2. 叶子节点按序排列并串联成链表，范围查询退化为一次顺序扫描；\n3. 与 16KB 页大小配合，一次 IO 加载整页，局部性更好。',
        ['集合框架.md', 'MySQL 索引原理.pdf'],
        [
          T('search', 'kb.search', 'B+ 树 索引 页', 'done', 900),
          T('file', 'doc.read', '集合框架.md · 第 3 节', 'done', 1200),
        ],
        /* 正常上下文：命中/引用/历史轮数都能对上，用户一眼能判断"检索给到料了" */
        CTX({ kbs: ['我的 Java 笔记', '面试八股合集'], retrieved: 6, usedDocs: 2, scannedDocs: 27, historyTurns: 1, web: true }),
        /* 结构化结果：表格（Markdown 里也有，但这里能一键复制成 CSV） */
        {
          kind: 'table',
          title: 'B 树 vs B+ 树',
          columns: ['维度', 'B 树', 'B+ 树'],
          rows: [
            ['数据存放', '所有节点', '仅叶子节点'],
            ['单页键数', '较少', '更多'],
            ['树高', '较高', '更矮'],
            ['范围查询', '需中序遍历', '叶子链表顺序扫描'],
          ],
        },
      ),
      /* 多轮问答：让「消息时间轴」有多个跳转点可演示 */
      U('那 B+ 树和 B 树的区别到底在哪？'),
      A(
        '关键差别在**数据存放位置**：\n· B 树的每个节点都存键和值，命中的层数不固定；\n· B+ 树只在叶子存值，非叶子纯做索引 —— 同样页大小能放下更多键，树更矮。\n\n代价是命中非叶子节点时还要多走一层到叶子，但换来的是叶子层的顺序扫描能力，这在数据库场景里更值钱。',
        ['集合框架.md'],
      ),
      U('为什么 InnoDB 用 B+ 树而不是哈希索引？'),
      A(
        '哈希索引只擅长**等值查询**，不支持范围与排序：\n· `WHERE id > 100` 在哈希索引上只能全表扫；\n· `ORDER BY` 也用不上，会退化成 filesort。\n\nInnoDB 的自适应哈希索引（AHI）是另一回事：它是引擎为热点等值查询自动加的**内存层**缓存，底层仍然是 B+ 树。',
        ['MySQL 索引原理.pdf', '集合框架.md'],
      ),
    ],
  },
  {
    id: 'seed_s2',
    title: '帮我总结 JVM 调优的参数',
    agentId: null,
    kbs: ['我的 Java 笔记', '面试八股合集'],
    updated: '昨天',
    /* 种子数据自带消息，本就是已加载的 —— 避免进会话时多发一次请求 */
    messagesLoaded: true,
    messages: [
      U('帮我总结 JVM 调优常用参数'),
      A(
        '按「堆 / GC / 诊断」三类记：\n· 堆：-Xms 与 -Xmx 设为相同值，避免运行期扩缩容；-Xmn 控制新生代。\n· GC：G1 用 -XX:MaxGCPauseMillis 定目标停顿；-XX:InitiatingHeapOccupancyPercent 触发并发标记。\n· 诊断：-XX:+HeapDumpOnOutOfMemoryError 配合 -XX:HeapDumpPath，线上必备。',
        ['JVM 内存模型.md'],
        [
          T('search', 'kb.search', 'JVM 调优 参数 G1', 'done', 800),
          T('file', 'doc.read', 'JVM 内存模型.md · 参数表', 'done', 1100),
        ],
        CTX({ kbs: ['我的 Java 笔记'], retrieved: 4, usedDocs: 1, scannedDocs: 12, historyTurns: 1, web: false }),
        /* 结构化结果：JSON（配置类回答的常见形态，方便直接粘进配置） */
        {
          kind: 'json',
          title: 'G1 常用参数（可直接粘贴到启动脚本）',
          json: {
            '-Xms': '4g',
            '-Xmx': '4g',
            '-XX:+UseG1GC': true,
            '-XX:MaxGCPauseMillis': 200,
            '-XX:+HeapDumpOnOutOfMemoryError': true,
            '-XX:HeapDumpPath': '/var/log/app/heap.hprof',
          },
        },
      ),
    ],
  },
  {
    id: 'seed_s3',
    title: '聊聊 Redis 的持久化方案',
    agentId: null,
    kbs: ['Redis 源码阅读'],
    updated: '3 天前',
    /* 种子数据自带消息，本就是已加载的 —— 避免进会话时多发一次请求 */
    messagesLoaded: true,
    messages: [
      U('RDB 和 AOF 怎么选？'),
      A(
        '看你能接受多少数据丢失：RDB 是周期性快照，恢复快、文件小，但两次快照之间会丢；AOF 记录每条写命令，everysec 最多丢 1 秒，代价是文件更大、恢复更慢。生产常见做法是两者同时开启。',
        ['Redis 源码阅读 · ## 持久化'],
        undefined,
        /* 长会话演示：历史被窗口截断时必须让用户看见，否则会被误判成"模型变笨了" */
        CTX({ kbs: ['Redis 源码阅读'], retrieved: 9, usedDocs: 1, scannedDocs: 4, historyTurns: 14, web: false, truncated: 6 }),
        /* 结构化结果：图表（原型用 CSS 条形图，不为几根柱子引图表库） */
        {
          kind: 'chart',
          title: 'RDB vs AOF：恢复耗时（越小越好，单位秒 / GB）',
          unit: 's',
          series: [
            { label: 'RDB 恢复', value: 4 },
            { label: 'AOF 恢复', value: 26 },
            { label: 'RDB 文件', value: 3 },
            { label: 'AOF 文件', value: 18 },
          ],
        },
      ),
    ],
  },
  {
    id: 'seed_s4',
    title: '设计一个限流器（场景题）',
    agentId: null,
    kbs: ['面试八股合集'],
    updated: '上周',
    /* 种子数据自带消息，本就是已加载的 —— 避免进会话时多发一次请求 */
    messagesLoaded: true,
    messages: [
      U('让我设计一个分布式限流器，怎么答？'),
      A('先问清约束（QPS 量级、是否允许突发、单机还是集群），再给方案：单机令牌桶 → 集群用 Redis + Lua 保证原子性 → 最后补充降级与监控。'),
    ],
  },
]

/* ---------- 工作台数据 ---------- */

/** 近 14 天 LLM 调用数（趋势图） */
export const TREND = [42, 58, 51, 76, 64, 88, 73, 96, 84, 110, 92, 128, 104, 118]

/** 近 12 周 × 7 天 活跃度（0-4 级，瓷砖墙） */
export const HEAT: number[] = Array.from({ length: 84 }, (_, i) => {
  const s = Math.sin(i * 0.7) + Math.cos(i * 0.31)
  const v = Math.round((s + 2) / 4 * 4)
  return i % 11 === 0 ? 0 : Math.max(0, Math.min(4, v))
})

export type ActivityKind = 'import' | 'qa' | 'agent' | 'chat'

export const ACTIVITY: { kind: ActivityKind; title: string; detail: string; time: string }[] = [
  { kind: 'import', title: '导入《面经整理 v4.md》', detail: '切片 42 段 · 向量化完成', time: '12 分钟前' },
  { kind: 'qa', title: '向「我的 Java 笔记」提问', detail: '命中 3 个切片 · 引用 2 篇文档', time: '2 小时前' },
  { kind: 'agent', title: '新建 Agent「代码审查员」', detail: '绑定 glm-5.3 · 开启 code_runner', time: '昨天' },
  { kind: 'chat', title: '会话「JVM 调优参数」', detail: '6 轮对话 · 使用面试助手', time: '昨天' },
  { kind: 'import', title: '导入《MySQL 索引原理.pdf》', detail: '切片 118 段 · 索引重建完成', time: '3 天前' },
]
