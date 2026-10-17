/** 插件 / 技能 / MCP 市场的前端数据模型（本轮只做界面，数据存本地） */

export type PluginCategory = 'agent' | 'knowledge' | 'search' | 'automation' | 'productivity' | 'data' | 'skill'

export interface PluginMeta {
  id: string
  name: string
  author: string
  version: string
  category: PluginCategory
  description: string
  rating: number
  downloads: string
  tools: string[]
  tags: string[]
  verified?: boolean
  official?: boolean
  updated: string
}

export interface SkillPack {
  id: string
  name: string
  version: string
  description: string
  source: string
  importedAt: string
}

export interface McpServerMeta {
  id: string
  name: string
  author: string
  version: string
  endpoint: string
  description: string
  tools: string[]
  connected: boolean
}

export const PLUGIN_CATALOG: PluginMeta[] = [
  {
    id: 'nexus-mcp-bridge',
    name: 'Nexus MCP Bridge',
    author: 'Prisdvl',
    version: '1.4.2',
    category: 'automation',
    description: '把 Nexus 知识库与工具能力暴露为标准 MCP 服务，供外部 Agent 与 IDE 复用。',
    rating: 4.9,
    downloads: '18.2k',
    tools: ['mcp.tools.list', 'knowledge.search', 'qa.ask'],
    tags: ['MCP', 'Protocol', 'Integration'],
    verified: true,
    official: true,
    updated: '2026-10-12',
  },
  {
    id: 'web-fetch',
    name: 'Web Fetch',
    author: 'Prisdvl',
    version: '2.0.1',
    category: 'search',
    description: '抓取网页正文与元信息，支持 Markdown 化、链接提取与引用来源。',
    rating: 4.8,
    downloads: '12.7k',
    tools: ['web.fetch', 'web.extract_links'],
    tags: ['Web', 'Search'],
    verified: true,
    official: true,
    updated: '2026-10-08',
  },
  {
    id: 'code-review',
    name: 'Code Review Copilot',
    author: 'devtools-org',
    version: '1.8.0',
    category: 'productivity',
    description: '为代码变更生成结构化审查意见，输出风险分级与修改建议。',
    rating: 4.7,
    downloads: '9.4k',
    tools: ['code.diff', 'code.review'],
    tags: ['Code', 'Review'],
    updated: '2026-09-30',
  },
  {
    id: 'rag-tuner',
    name: 'RAG Tuner',
    author: 'agent-lab',
    version: '0.9.3',
    category: 'knowledge',
    description: '分析检索命中质量，给出 chunk 切分、嵌入与混合检索参数建议。',
    rating: 4.5,
    downloads: '6.1k',
    tools: ['rag.analyze', 'rag.suggest'],
    tags: ['RAG', 'Knowledge'],
    updated: '2026-09-26',
  },
  {
    id: 'schedule-watch',
    name: 'Schedule Watch',
    author: 'automation-hub',
    version: '1.2.4',
    category: 'automation',
    description: '定时检查任务、会话与知识库状态，变化时向工作台推送提醒。',
    rating: 4.4,
    downloads: '4.8k',
    tools: ['schedule.monitor', 'schedule.notify'],
    tags: ['Automation', 'Reminder'],
    updated: '2026-09-18',
  },
  {
    id: 'spreadsheet-kit',
    name: 'Spreadsheet Kit',
    author: 'data-works',
    version: '2.3.1',
    category: 'data',
    description: '读取与生成 xlsx / csv，支持公式、透视表与图表数据导出。',
    rating: 4.3,
    downloads: '3.2k',
    tools: ['sheet.read', 'sheet.write', 'sheet.chart'],
    tags: ['Spreadsheet', 'Data'],
    updated: '2026-09-10',
  },
  {
    id: 'agent-tester-pro',
    name: 'Agent Tester Pro',
    author: 'agent-lab',
    version: '1.1.0',
    category: 'agent',
    description: '为 Agent 提供回归用例、上下文隔离与失败重放测试台。',
    rating: 4.6,
    downloads: '2.9k',
    tools: ['agent.test', 'agent.replay'],
    tags: ['Agent', 'Testing'],
    updated: '2026-09-02',
  },
]

export const MCP_SERVERS: McpServerMeta[] = [
  {
    id: 'filesystem',
    name: 'Local Filesystem',
    author: 'Prisdvl',
    version: '1.0.0',
    endpoint: 'http://localhost:19000/mcp',
    description: '受限访问工作区文件，提供读取、搜索与变更预览。',
    tools: ['fs.read', 'fs.search', 'fs.diff'],
    connected: true,
  },
  {
    id: 'github',
    name: 'GitHub',
    author: 'community',
    version: '0.8.2',
    endpoint: 'https://mcp.github.com/mcp',
    description: '读取仓库、Issue、PR 与 CI 状态，只读权限默认开启。',
    tools: ['gh.repo', 'gh.issue', 'gh.pr', 'gh.ci'],
    connected: false,
  },
  {
    id: 'browser',
    name: 'Browser Control',
    author: 'automation-hub',
    version: '1.5.0',
    endpoint: 'http://localhost:19001/mcp',
    description: '无头浏览器导航、表单填写、截图与 DOM 快照。',
    tools: ['browser.open', 'browser.click', 'browser.snapshot', 'browser.shot'],
    connected: false,
  },
  {
    id: 'postgres',
    name: 'PostgreSQL',
    author: 'data-works',
    version: '1.3.0',
    endpoint: 'http://localhost:19002/mcp',
    description: '只读查询与 schema 浏览，需要连接配置。',
    tools: ['pg.query', 'pg.tables', 'pg.explain'],
    connected: false,
  },
]

const INSTALLED_KEY = 'nx-plugins-installed'
const SKILLS_KEY = 'nx-plugins-skills'
const MCP_STATE_KEY = 'nx-plugins-mcp'

function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : fallback
  } catch {
    return fallback
  }
}

export function readInstalledPlugins(): string[] {
  return readJson<string[]>(INSTALLED_KEY, ['nexus-mcp-bridge', 'web-fetch'])
}

export function writeInstalledPlugins(ids: string[]) {
  localStorage.setItem(INSTALLED_KEY, JSON.stringify(ids))
}

export function readSkills(): SkillPack[] {
  return readJson<SkillPack[]>(SKILLS_KEY, [])
}

export function writeSkills(skills: SkillPack[]) {
  localStorage.setItem(SKILLS_KEY, JSON.stringify(skills))
}

export function readMcpState(): Record<string, boolean> {
  return readJson<Record<string, boolean>>(MCP_STATE_KEY, { filesystem: true })
}

export function writeMcpState(state: Record<string, boolean>) {
  localStorage.setItem(MCP_STATE_KEY, JSON.stringify(state))
}

export function parseSkillContent(raw: string): { name?: string; version?: string; description?: string } {
  const trimmed = raw.trim()
  if (!trimmed) return {}
  try {
    const parsed = JSON.parse(trimmed) as Record<string, unknown>
    const name = typeof parsed.name === 'string' ? parsed.name : undefined
    const version = typeof parsed.version === 'string' ? parsed.version : undefined
    const description = typeof parsed.description === 'string' ? parsed.description : undefined
    if (name) return { name, version, description }
  } catch {
    /* 不是 JSON，继续按 SKILL.md frontmatter 解析 */
  }
  const meta: Record<string, string> = {}
  const m = /^---\s*\n([\s\S]*?)\n---/.exec(trimmed)
  const header = m ? m[1] : trimmed.slice(0, 400)
  for (const line of header.split('\n')) {
    const kv = /^([a-zA-Z][\w-]*)\s*:\s*(.*)$/.exec(line.trim())
    if (kv) meta[kv[1].toLowerCase()] = kv[2].replace(/^["']|["']$/g, '').trim()
  }
  const name = meta.name ?? /^#\s+(.+)$/m.exec(trimmed)?.[1]?.trim()
  return { name, version: meta.version, description: meta.description }
}
