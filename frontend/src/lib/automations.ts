/**
 * 自动化任务：界面视图模型 + 后端 DTO 映射。
 *
 * 真实数据来自 `/api/automations`（迁移 V30）。本文件底部保留的演示任务
 * 只在 `VITE_USE_MOCK=true` 的离线演示模式下使用——默认构建走真实接口，
 * 不会出现假数据。
 */

import type { BackendAutomation, BackendAutomationRun } from './api'
import type { Locale } from './locale'

export type AutomationStatus = 'done' | 'failed' | 'queued'
export type AutomationSchedule = 'hourly' | 'daily' | 'weekly' | 'custom'
export type ScheduleLabelKey = 'scheduleHourly' | 'scheduleDaily' | 'scheduleWeekly' | 'scheduleCustom'

export interface AutomationRun {
  id: string
  /** 结束时间（本地时区 ISO 字符串）；无结束时间回落开始时间 */
  at: string
  status: AutomationStatus
  durationMs: number
  /** 这一跑消耗的 token；采不到为 null（不写 0，避免和"真的用了 0"混淆） */
  tokens: number | null
  error: string | null
}

/** 列表行 / 历史弹窗共用的视图模型 */
export interface AutomationItem {
  id: string
  name: string
  prompt: string
  schedule: AutomationSchedule
  /** 真实生效的 cron（固定频率由服务端用预设覆盖） */
  cron: string
  agentId: string | null
  agentName: string
  kbIds: string[]
  kbNames: string[]
  enabled: boolean
  lastRunAt: string | null
  nextRunAt: string | null
  /** 从未跑过为 null——页面显示「从未」，不要假装是排队中 */
  lastStatus: AutomationStatus | null
  /** 运行历史：列表接口不带，点历史按钮时才拉（mock 模式直接给） */
  history: AutomationRun[]
}

export interface AutomationChoice {
  id: string
  name: string
}

export interface AutomationChoices {
  agents: AutomationChoice[]
  kbs: AutomationChoice[]
}

const SCHEDULES: AutomationSchedule[] = ['hourly', 'daily', 'weekly', 'custom']

/** 后端 schedule → 界面枚举；认不出的值按自定义处理（不猜频率） */
export function scheduleOf(raw: string | null | undefined): AutomationSchedule {
  const value = (raw ?? '').toLowerCase()
  return (SCHEDULES as string[]).includes(value) ? (value as AutomationSchedule) : 'custom'
}

/** 后端运行状态 → 界面三态；从没跑过（null）保持 null */
export function statusOf(raw: string | null | undefined): AutomationStatus | null {
  switch (raw) {
    case 'SUCCEEDED':
    case 'DONE':
      return 'done'
    case 'FAILED':
      return 'failed'
    case 'RUNNING':
    case 'QUEUED':
      return 'queued'
    default:
      return null
  }
}

export function automationOf(dto: BackendAutomation): AutomationItem {
  return {
    id: dto.id,
    name: dto.name,
    prompt: dto.prompt,
    schedule: scheduleOf(dto.schedule),
    cron: dto.cron ?? '',
    agentId: dto.agentId,
    agentName: dto.agentName ?? '',
    kbIds: dto.kbIds ?? [],
    kbNames: dto.kbNames ?? [],
    enabled: dto.enabled,
    lastRunAt: dto.lastRunAt,
    nextRunAt: dto.nextRunAt,
    lastStatus: statusOf(dto.lastStatus),
    history: [],
  }
}

export function runOf(dto: BackendAutomationRun): AutomationRun {
  return {
    id: dto.id,
    at: dto.finishedAt ?? dto.startedAt ?? '',
    status: statusOf(dto.status) ?? 'failed',
    durationMs: dto.durationMs ?? 0,
    tokens: dto.totalTokens,
    error: dto.errorMessage,
  }
}

/** ISO 时间 → 「10-24 22:03」；null / 解析失败回落 fallback（通常是「从未」） */
export function formatWhen(iso: string | null, locale: Locale, fallback: string): string {
  if (!iso) return fallback
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) return fallback
  return at.toLocaleString(locale === 'zh' ? 'zh-CN' : 'en-US', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function scheduleLabelKey(schedule: AutomationSchedule): ScheduleLabelKey {
  return schedule === 'hourly'
    ? 'scheduleHourly'
    : schedule === 'daily'
      ? 'scheduleDaily'
      : schedule === 'weekly'
        ? 'scheduleWeekly'
        : 'scheduleCustom'
}

/* ------------------------------------------------------------------ *
 * 离线演示数据（仅 VITE_USE_MOCK）
 * ------------------------------------------------------------------ */

/** 演示任务骨架：排期/开关是逻辑，不随界面语言变 */
const TASKS: Pick<AutomationItem, 'id' | 'schedule' | 'cron' | 'enabled' | 'lastStatus'>[] = [
  { id: 'auto-1', schedule: 'daily', cron: '0 9 * * *', enabled: true, lastStatus: 'done' },
  { id: 'auto-2', schedule: 'weekly', cron: '0 10 * * 1', enabled: true, lastStatus: 'done' },
  { id: 'auto-3', schedule: 'hourly', cron: '0 * * * *', enabled: false, lastStatus: 'failed' },
]

/**
 * 演示文案：任务名/提示词/绑定的 Agent 与知识库按语言给两份，EN 界面里不会出现中文。
 *
 * 时间用「距今多少分钟」而不是写死的「今天 09:00」——渲染时换算成 ISO 再统一格式化，
 * 演示数据和真实数据的显示路径就是同一条。
 */
interface DemoCopy {
  agents: string[]
  kbs: string[]
  tasks: {
    name: string
    prompt: string
    agent: string
    kb: string
    /** 上次运行距今分钟数；null = 从未跑过 */
    lastRunAgoMin: number | null
    /** 下次运行距今分钟数；null = 不会再跑（已暂停） */
    nextRunInMin: number | null
    runs: { agoMin: number; status: AutomationStatus; durationMs: number; tokens: number | null; error?: string }[]
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
      lastRunAgoMin: 245,
      nextRunInMin: 1095,
      runs: [
        { agoMin: 245, status: 'done', durationMs: 8210, tokens: 1420 },
        { agoMin: 1685, status: 'done', durationMs: 7900, tokens: 1390 },
        { agoMin: 3125, status: 'failed', durationMs: 3200, tokens: null, error: 'MODEL_TIMEOUT' },
      ],
    },
    {
      name: '每周竞品情报',
      prompt: '抓取竞品官网与公告更新，生成周报摘要并标注来源。',
      agent: '竞品情报员',
      kb: '软技能与项目',
      lastRunAgoMin: 4320,
      nextRunInMin: 5760,
      runs: [{ agoMin: 4320, status: 'done', durationMs: 26400, tokens: 5120 }],
    },
    {
      name: '面试错题回顾',
      prompt: '从最近的面试练习会话中找出答错的题目，生成复习清单。',
      agent: '面试助手',
      kb: '面试八股合集',
      lastRunAgoMin: 900,
      nextRunInMin: null,
      runs: [
        { agoMin: 900, status: 'failed', durationMs: 1800, tokens: null, error: 'MODEL_TIMEOUT' },
        { agoMin: 960, status: 'done', durationMs: 6400, tokens: 1080 },
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
      lastRunAgoMin: 245,
      nextRunInMin: 1095,
      runs: [
        { agoMin: 245, status: 'done', durationMs: 8210, tokens: 1420 },
        { agoMin: 1685, status: 'done', durationMs: 7900, tokens: 1390 },
        { agoMin: 3125, status: 'failed', durationMs: 3200, tokens: null, error: 'MODEL_TIMEOUT' },
      ],
    },
    {
      name: 'Weekly competitor intel',
      prompt: 'Scrape competitor sites and announcement updates, then produce a weekly digest with sources cited.',
      agent: 'Competitor analyst',
      kb: 'Soft skills & projects',
      lastRunAgoMin: 4320,
      nextRunInMin: 5760,
      runs: [{ agoMin: 4320, status: 'done', durationMs: 26400, tokens: 5120 }],
    },
    {
      name: 'Interview mistake review',
      prompt: 'Find the questions missed in recent interview practice sessions and draft a review checklist.',
      agent: 'Interview coach',
      kb: 'Interview prep',
      lastRunAgoMin: 900,
      nextRunInMin: null,
      runs: [
        { agoMin: 900, status: 'failed', durationMs: 1800, tokens: null, error: 'MODEL_TIMEOUT' },
        { agoMin: 960, status: 'done', durationMs: 6400, tokens: 1080 },
      ],
    },
  ],
}

const DEMO: Record<Locale, DemoCopy> = { zh: ZH, en: EN }

/** 演示任务（mock 模式）：id 与 demoAutomationChoices 的 agent-N / kb-N 对齐 */
export function demoAutomations(locale: Locale): AutomationItem[] {
  const copy = DEMO[locale]
  const now = Date.now()
  const ago = (min: number) => new Date(now - min * 60_000).toISOString()
  const ahead = (min: number) => new Date(now + min * 60_000).toISOString()
  return TASKS.map((task, i) => {
    const text = copy.tasks[i]
    const agentIdx = copy.agents.indexOf(text.agent)
    const kbIdx = copy.kbs.indexOf(text.kb)
    return {
      id: task.id,
      name: text.name,
      prompt: text.prompt,
      schedule: task.schedule,
      cron: task.cron,
      agentId: agentIdx >= 0 ? `agent-${agentIdx + 1}` : null,
      agentName: text.agent,
      kbIds: kbIdx >= 0 ? [`kb-${kbIdx + 1}`] : [],
      kbNames: kbIdx >= 0 ? [text.kb] : [],
      enabled: task.enabled,
      lastRunAt: text.lastRunAgoMin === null ? null : ago(text.lastRunAgoMin),
      nextRunAt: task.enabled && text.nextRunInMin !== null ? ahead(text.nextRunInMin) : null,
      lastStatus: task.lastStatus,
      history: text.runs.map((run, j) => ({
        id: `${task.id}-run-${j + 1}`,
        at: ago(run.agoMin),
        status: run.status,
        durationMs: run.durationMs,
        tokens: run.tokens,
        error: run.error ?? null,
      })),
    }
  })
}

/** 新建/编辑表单里的 Agent 与知识库候选（同样是演示数据） */
export function demoAutomationChoices(locale: Locale): AutomationChoices {
  const copy = DEMO[locale]
  return {
    agents: copy.agents.map((name, i) => ({ id: `agent-${i + 1}`, name })),
    kbs: copy.kbs.map((name, i) => ({ id: `kb-${i + 1}`, name })),
  }
}
