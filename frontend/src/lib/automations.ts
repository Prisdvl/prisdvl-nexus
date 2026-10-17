/** 自动化任务（前端演示数据；真实接入后换成 /api/automations） */

export type AutomationStatus = 'done' | 'failed' | 'queued'
export type AutomationSchedule = 'hourly' | 'daily' | 'weekly' | 'custom'

export interface AutomationRun {
  id: string
  at: string
  status: AutomationStatus
  durationMs: number
}

export interface Automation {
  id: string
  name: string
  prompt: string
  schedule: AutomationSchedule
  cron: string
  agent: string
  kb: string
  enabled: boolean
  lastRun: string
  nextRun: string
  lastStatus: AutomationStatus
  history: AutomationRun[]
}

export const AUTOMATIONS: Automation[] = [
  {
    id: 'auto-1',
    name: '每日知识库简报',
    prompt: '汇总知识库中今天新增的文档，按主题给出 5 条要点。',
    schedule: 'daily',
    cron: '0 9 * * *',
    agent: '默认助手',
    kb: '我的 Java 笔记',
    enabled: true,
    lastRun: '今天 09:00',
    nextRun: '明天 09:00',
    lastStatus: 'done',
    history: [
      { id: 'h1', at: '今天 09:00', status: 'done', durationMs: 8210 },
      { id: 'h2', at: '昨天 09:00', status: 'done', durationMs: 7900 },
      { id: 'h3', at: '前天 09:00', status: 'failed', durationMs: 3200 },
    ],
  },
  {
    id: 'auto-2',
    name: '每周竞品情报',
    prompt: '抓取竞品官网与公告更新，生成周报摘要并标注来源。',
    schedule: 'weekly',
    cron: '0 10 * * 1',
    agent: '竞品情报员',
    kb: '软技能与项目',
    enabled: true,
    lastRun: '周一 10:00',
    nextRun: '下周一 10:00',
    lastStatus: 'done',
    history: [{ id: 'h1', at: '周一 10:00', status: 'done', durationMs: 26400 }],
  },
  {
    id: 'auto-3',
    name: '面试错题回顾',
    prompt: '从最近的面试练习会话中找出答错的题目，生成复习清单。',
    schedule: 'hourly',
    cron: '0 * * * *',
    agent: '面试助手',
    kb: '面试八股合集',
    enabled: false,
    lastRun: '昨天 22:00',
    nextRun: '—',
    lastStatus: 'failed',
    history: [
      { id: 'h1', at: '昨天 22:00', status: 'failed', durationMs: 1800 },
      { id: 'h2', at: '昨天 21:00', status: 'done', durationMs: 6400 },
    ],
  },
]

export function scheduleLabelKey(schedule: AutomationSchedule) {
  return schedule === 'hourly' ? 'scheduleHourly' : schedule === 'daily' ? 'scheduleDaily' : schedule === 'weekly' ? 'scheduleWeekly' : 'scheduleCustom'
}
