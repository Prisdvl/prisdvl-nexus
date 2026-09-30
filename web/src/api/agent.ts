import client from './client'

/** 一次工具调用痕迹（"用户可控"的透明性：每一步做了什么都要看得见）。 */
export interface ToolCallRecord {
  tool: string
  input: string
  resultSummary: string
}

export interface AgentAnswer {
  answer: string
  toolCalls: ToolCallRecord[]
  usedTools: boolean
}

/**
 * 问 Agent：模型先决定是否检索知识库（工具循环），再基于结果回答。
 * 工具调用痕迹随回答返回，前端把它们渲染成步骤条。
 */
export function askAgent(question: string) {
  return client.post<never, AgentAnswer>('/agent/ask', { question })
}