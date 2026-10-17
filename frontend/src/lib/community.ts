/** Agent 社区前端数据模型（本轮只做界面，数据存本地） */

export type CommunityCategory = 'writing' | 'coding' | 'research' | 'knowledge' | 'business' | 'education' | 'data'
export type CommunityStatus = 'published' | 'draft' | 'unlisted'

export interface CommunityAgent {
  id: string
  name: string
  description: string
  author: string
  avatar: string
  category: CommunityCategory
  tags: string[]
  model: string
  version: string
  tools: string[]
  downloads: string
  likes: string
  rating: number
  reviews: number
  updated: string
  verified?: boolean
  official?: boolean
  status: CommunityStatus
  readme: string
}

export const COMMUNITY_AGENTS: CommunityAgent[] = [
  {
    id: 'c-1',
    name: '面试陪练官',
    description: '结合 Java、MySQL、并发与场景题题库，进行追问式面试陪练并给出评分。',
    author: 'NexusStudio',
    avatar: 'NS',
    category: 'education',
    tags: ['Java', 'Interview', 'Mock'],
    model: 'deepseek-v4-flash',
    version: '2.4.0',
    tools: ['knowledge_search', 'calculator'],
    downloads: '32.8k',
    likes: '4.2k',
    rating: 4.9,
    reviews: 386,
    updated: '2026-10-12',
    verified: true,
    official: true,
    status: 'published',
    readme: '## 面试陪练官\n\n按“先答要点、再展开细节、最后追问”的节奏训练面试表达。\n\n- 支持场景题与八股题\n- 自动检查回答完整性\n- 可绑定个人知识库',
  },
  {
    id: 'c-2',
    name: '简历润色师',
    description: '将经历改写为 STAR 句式，对齐 JD 关键词，输出可投递版本。',
    author: 'CareerLab',
    avatar: 'CL',
    category: 'writing',
    tags: ['Resume', 'Career'],
    model: 'glm-5.3',
    version: '1.9.1',
    tools: ['knowledge_search'],
    downloads: '21.6k',
    likes: '3.1k',
    rating: 4.8,
    reviews: 214,
    updated: '2026-10-05',
    verified: true,
    status: 'published',
    readme: '## 简历润色师\n\n把零散经历改写成结构清晰、可量化、对齐岗位的简历条目。\n\n- STAR 结构\n- 关键词对齐\n- 中文 / 英文双版本',
  },
  {
    id: 'c-3',
    name: 'SQL 侦探',
    description: '分析慢查询与执行计划，定位索引失效和回表问题。',
    author: 'DataWizard',
    avatar: 'DW',
    category: 'data',
    tags: ['SQL', 'Database', 'Performance'],
    model: 'kimi-k2.6',
    version: '1.2.0',
    tools: ['knowledge_search', 'code_runner'],
    downloads: '15.9k',
    likes: '2.4k',
    rating: 4.7,
    reviews: 168,
    updated: '2026-09-28',
    status: 'published',
    readme: '## SQL 侦探\n\n粘贴慢 SQL 与 EXPLAIN，分析索引、回表、缓冲与连接顺序。',
  },
  {
    id: 'c-4',
    name: '技术方案评审官',
    description: '评审架构图、接口设计与技术选型，产出风险清单与替代方案。',
    author: 'NexusStudio',
    avatar: 'NX',
    category: 'coding',
    tags: ['Architecture', 'Review'],
    model: 'deepseek-v4-flash',
    version: '1.7.2',
    tools: ['knowledge_search', 'web_fetch'],
    downloads: '12.3k',
    likes: '2.0k',
    rating: 4.8,
    reviews: 142,
    updated: '2026-09-20',
    verified: true,
    status: 'published',
    readme: '## 技术方案评审官\n\n从一致性、可维护性、容量与回滚四个维度评审方案。',
  },
  {
    id: 'c-5',
    name: '论文共读助手',
    description: '解析论文结构、方法、实验与结论，生成可讨论的阅读笔记。',
    author: 'ResearchHub',
    avatar: 'RH',
    category: 'research',
    tags: ['Paper', 'Reading'],
    model: 'glm-5.3',
    version: '0.8.4',
    tools: ['knowledge_search'],
    downloads: '8.7k',
    likes: '1.6k',
    rating: 4.5,
    reviews: 93,
    updated: '2026-09-15',
    status: 'published',
    readme: '## 论文共读助手\n\n提取研究问题、方法、数据与结论，辅助精读与组会讨论。',
  },
  {
    id: 'c-6',
    name: '竞品情报员',
    description: '按公司、产品与时间维度整理公开信息，形成周报式竞品摘要。',
    author: 'GrowthKit',
    avatar: 'GK',
    category: 'business',
    tags: ['Market', 'Research'],
    model: 'kimi-k2.6',
    version: '1.3.1',
    tools: ['web_fetch', 'knowledge_search'],
    downloads: '6.2k',
    likes: '980',
    rating: 4.4,
    reviews: 67,
    updated: '2026-09-08',
    status: 'published',
    readme: '## 竞品情报员\n\n结构化整理官网、公告、招聘与评测信息，输出竞争格局摘要。',
  },
  {
    id: 'c-7',
    name: '学习路线规划师',
    description: '根据目标岗位与时间预算，输出分阶段学习路线与每周复盘。',
    author: 'EduPilot',
    avatar: 'EP',
    category: 'education',
    tags: ['Learning', 'Plan'],
    model: 'deepseek-v4-flash',
    version: '2.0.0',
    tools: ['knowledge_search'],
    downloads: '5.1k',
    likes: '740',
    rating: 4.6,
    reviews: 58,
    updated: '2026-09-01',
    status: 'published',
    readme: '## 学习路线规划师\n\n基于岗位能力地图生成学习路线，并支持每周复盘。',
  },
  {
    id: 'c-8',
    name: '产品需求分析师',
    description: '把口头需求拆成用户故事、验收标准与边界场景。',
    author: 'PMStack',
    avatar: 'PM',
    category: 'business',
    tags: ['PRD', 'PM'],
    model: 'glm-5.3',
    version: '1.1.0',
    tools: ['knowledge_search', 'spreadsheet'],
    downloads: '4.4k',
    likes: '610',
    rating: 4.3,
    reviews: 44,
    updated: '2026-08-26',
    status: 'published',
    readme: '## 产品需求分析师\n\n输出用户故事、验收标准、异常分支与数据埋点建议。',
  },
]

const MINE_KEY = 'nx-community-mine'

export function readMyAgents(): CommunityAgent[] {
  try {
    const raw = localStorage.getItem(MINE_KEY)
    return raw ? (JSON.parse(raw) as CommunityAgent[]) : []
  } catch {
    return []
  }
}

export function writeMyAgents(items: CommunityAgent[]) {
  localStorage.setItem(MINE_KEY, JSON.stringify(items))
}

export const COMMUNITY_CATEGORIES: CommunityCategory[] = [
  'writing', 'coding', 'research', 'knowledge', 'business', 'education', 'data',
]
