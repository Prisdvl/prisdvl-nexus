import client from './client'

/**
 * Agent 评测接口。
 *
 * 问答本身走 {@code /api/chat/stream}（见 {@code api/chat.ts}）：
 * Agent 对话是"会话 + 消息 + 流式"的通用链路，不该为它单开一套接口。
 * 这里只保留评测——它是 Agent 质量的回归手段，与聊天界面无关。
 */
export interface EvalCase {
  question: string
  expectedPoints: string[]
}

export interface EvalResult {
  testCase: EvalCase
  answer: string
  missingPoints: string[]
  citationsValid: boolean
  citationCount: number
  pointRecall: number
}

export interface EvalReport {
  results: EvalResult[]
  avgPointRecall: number
  passed: number
}

/**
 * 默认评测集（**演示用例**）。
 *
 * 它把"问题 + 期望要点"写死在代码里，因此**只有在资料恰好覆盖该主题时**才会有非零命中率——
 * 它验证的是"评测链路能不能跑通"，不是对你资料的评估。
 *
 * 措辞刻意不写"我的笔记里…"（UA-003 的同类问题：暗示用户有一份并不存在的资料）。
 * 弹窗里也会明确标注这是演示用例，避免用户把这个数字当成针对自己知识库的结论。
 */
export const DEFAULT_EVAL_CASES: EvalCase[] = [
  { question: '缓存击穿怎么解决？', expectedPoints: ['SETNX', '逻辑过期'] }
]

/** 跑评测：对每个用例走一遍 Agent，报告要点命中率与引用有效性。 */
export function runEval(kbId: string, cases: EvalCase[]) {
  return client.post<never, EvalReport>(`/eval/kb/${kbId}`, { cases })
}
