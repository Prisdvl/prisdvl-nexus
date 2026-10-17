/**
 * PromptBar 共用预设：对话页与知识库问答共用同一套模型 / 档位，
 * 避免两处各自改、栏看起来「同组件不同气质」。
 *
 * 文案按语言取用（promptBarCopy(locale)）；effort 的 **value 保持规范键**，
 * 因为它参与 EFFORT_TO_TOPK 映射并可能进入持久化元数据，不能随语言漂移。
 */
import type { Locale } from '@/lib/locale'

export const PROMPT_MODELS = [
  { key: 'nexus-pro', name: 'Nexus Pro', tag: '默认' },
  { key: 'nexus-fast', name: 'Nexus Fast', tag: '快' },
] as const

export type PromptEffortKey = 'low' | 'medium' | 'high' | 'max'

/** 档位 → 知识库问答 / 检索的 topK（key 为规范值，与语言无关） */
export const EFFORT_TO_TOPK: Record<PromptEffortKey, number> = {
  low: 3,
  medium: 6,
  high: 10,
  max: 16,
}

export function promptModels(locale: Locale): { key: string; name: string; tag: string }[] {
  return locale === 'en'
    ? [
        { key: 'nexus-pro', name: 'Nexus Pro', tag: 'Default' },
        { key: 'nexus-fast', name: 'Nexus Fast', tag: 'Fast' },
      ]
    : [
        { key: 'nexus-pro', name: 'Nexus Pro', tag: '默认' },
        { key: 'nexus-fast', name: 'Nexus Fast', tag: '快' },
      ]
}

export function promptEfforts(locale: Locale): { value: PromptEffortKey; label: string }[] {
  return locale === 'en'
    ? [
        { value: 'low', label: 'Low' },
        { value: 'medium', label: 'Medium' },
        { value: 'high', label: 'High' },
        { value: 'max', label: 'Max' },
      ]
    : [
        { value: 'low', label: '低' },
        { value: 'medium', label: '中' },
        { value: 'high', label: '高' },
        { value: 'max', label: '极高' },
      ]
}

export function promptChatCommands(locale: Locale): { key: string; name: string; description: string }[] {
  return locale === 'en'
    ? [
        { key: 'summarize', name: '/summarize', description: 'Condense this conversation into key points' },
        { key: 'explain', name: '/explain', description: 'Re-explain in plainer words' },
        { key: 'tasks', name: '/tasks', description: 'Turn it into an actionable checklist' },
      ]
    : [
        { key: 'summarize', name: '/总结', description: '把当前会话浓缩成要点' },
        { key: 'explain', name: '/讲解', description: '用更直白的话重讲一遍' },
        { key: 'tasks', name: '/待办', description: '整理成可执行的清单' },
      ]
}

export function promptKbCommands(locale: Locale): { key: string; name: string; description: string }[] {
  return locale === 'en'
    ? [
        { key: 'search', name: '/search', description: 'Open advanced search over chunks' },
        { key: 'summarize', name: '/summarize', description: 'Condense the relevant findings in this library' },
        { key: 'explain', name: '/explain', description: 'Explain this library in plainer words' },
      ]
    : [
        { key: 'search', name: '/检索', description: '打开高级检索，直接查切片' },
        { key: 'summarize', name: '/总结', description: '把本库相关结论浓缩成要点' },
        { key: 'explain', name: '/讲解', description: '用更直白的话讲解本库内容' },
      ]
}
