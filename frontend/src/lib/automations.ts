/** 自动化任务（前端演示数据；真实接入后换成 /api/automations） */

import type { Locale } from './locale'

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

/** 演示任务骨架：排期/开关是逻辑，不随界面语言变 */
const TASKS: Pick<Automation, 'id' | 'schedule' | 'cron' | 'enabled' | 'lastStatus'>[] = [
  { id: 'auto-1', schedule: 'daily', cron: '0 9 * * *', enabled: true, lastStatus: 'done' },
  { id: 'auto-2', schedule: 'weekly', cron: '0 10 * * 1', enabled: true, lastStatus: 'done' },
  { id: 'auto-3', schedule: 'hourly', cron: '0 * * * *', enabled: false, lastStatus: 'failed' },
]

/**
 * 演示文案：任务名/提示词/绑定的 Agent 与知识库/运行时间都是本页的假数据
 * （这个页面没有后端），所以按语言给两份，EN 界面里不会出现中文。
 */
interface DemoCopy {
  agents: string[]
  kbs: string[]
  tasks: {
    name: string
    prompt: string
    agent: string
    kb: string
    lastRun: string
    nextRun: string
    runs: { at: string; status: AutomationStatus; durationMs: number }[]
  }[]
}

const ZH: DemoCopy = {
  agents: ['默认助手', '面试助手', 'Java 代码审查员', '论文共读助手', '竞品情报员'],
  kbs: ['我的 Java 笔记', '面试八股合集', '软技能与项目', 'Redis 源码阅读'],
  tasks: [
    {
      name: '每日知识库简报',
      prompt: '汇总知识库中今天新增的文档，按主题给出 5 条要点。',
      agent: '默认助手',
      kb: '我的 Java 笔记',
      lastRun: '今天 09:00',
      nextRun: '明天 09:00',
      runs: [
        { at: '今天 09:00', status: 'done', durationMs: 8210 },
        { at: '昨天 09:00', status: 'done', durationMs: 7900 },
        { at: '前天 09:00', status: 'failed', durationMs: 3200 },
      ],
    },
    {
      name: '每周竞品情报',
      prompt: '抓取竞品官网与公告更新，生成周报摘要并标注来源。',
      agent: '竞品情报员',
      kb: '软技能与项目',
      lastRun: '周一 10:00',
      nextRun: '下周一 10:00',
      runs: [{ at: '周一 10:00', status: 'done', durationMs: 26400 }],
    },
    {
      name: '面试错题回顾',
      prompt: '从最近的面试练习会话中找出答错的题目，生成复习清单。',
      agent: '面试助手',
      kb: '面试八股合集',
      lastRun: '昨天 22:00',
      nextRun: '—',
      runs: [
        { at: '昨天 22:00', status: 'failed', durationMs: 1800 },
        { at: '昨天 21:00', status: 'done', durationMs: 6400 },
      ],
    },
  ],
}

const EN: DemoCopy = {
  agents: ['Default assistant', 'Interview coach', 'Java reviewer', 'Paper reading buddy', 'Competitor analyst'],
  kbs: ['My Java notes', 'Interview prep', 'Soft skills & projects', 'Redis source reading'],
  tasks: [
    {
      name: 'Daily knowledge digest',
      prompt: 'Summarize the documents added to the knowledge base today and list five key points by topic.',
      agent: 'Default assistant',
      kb: 'My Java notes',
      lastRun: 'Today 09:00',
      nextRun: 'Tomorrow 09:00',
      runs: [
        { at: 'Today 09:00', status: 'done', durationMs: 8210 },
        { at: 'Yesterday 09:00', status: 'done', durationMs: 7900 },
        { at: 'Two days ago 09:00', status: 'failed', durationMs: 3200 },
      ],
    },
    {
      name: 'Weekly competitor intel',
      prompt: 'Scrape competitor sites and announcement updates, then produce a weekly digest with sources cited.',
      agent: 'Competitor analyst',
      kb: 'Soft skills & projects',
      lastRun: 'Mon 10:00',
      nextRun: 'Next Mon 10:00',
      runs: [{ at: 'Mon 10:00', status: 'done', durationMs: 26400 }],
    },
    {
      name: 'Interview mistake review',
      prompt: 'Find the questions missed in recent interview practice sessions and draft a review checklist.',
      agent: 'Interview coach',
      kb: 'Interview prep',
      lastRun: 'Yesterday 22:00',
      nextRun: '—',
      runs: [
        { at: 'Yesterday 22:00', status: 'failed', durationMs: 1800 },
        { at: 'Yesterday 21:00', status: 'done', durationMs: 6400 },
      ],
    },
  ],
}

const DEMO: Record<Locale, DemoCopy> = { zh: ZH, en: EN }

export function demoAutomations(locale: Locale): Automation[] {
  const copy = DEMO[locale]
  return TASKS.map((task, i) => {
    const text = copy.tasks[i]
    return {
      ...task,
      name: text.name,
      prompt: text.prompt,
      agent: text.agent,
      kb: text.kb,
      lastRun: text.lastRun,
      nextRun: text.nextRun,
      history: text.runs.map((run, j) => ({ id: `h${j + 1}`, ...run })),
    }
  })
}

/** 新建/编辑表单里的 Agent 与知识库候选（同样是演示数据） */
export function demoAutomationChoices(locale: Locale): { agents: string[]; kbs: string[] } {
  const copy = DEMO[locale]
  return { agents: copy.agents, kbs: copy.kbs }
}

export function scheduleLabelKey(schedule: AutomationSchedule) {
  return schedule === 'hourly' ? 'scheduleHourly' : schedule === 'daily' ? 'scheduleDaily' : schedule === 'weekly' ? 'scheduleWeekly' : 'scheduleCustom'
}
