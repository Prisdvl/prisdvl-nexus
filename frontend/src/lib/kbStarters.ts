/**
 * 「新对话」欢迎区示例问题的**规则**（不含文案）。
 *
 * 为什么单独一层：
 *   · 示例问题不能凭空编 —— 输入只有"用户自己有哪些库、库里有没有资料"，
 *     所以这个文件里不出现任何具体库名，也不出现任何问题文本；
 *   · 文案（中/英）留在 `pagesCopy.ts` 的 chat 段，与页面其他文案同处维护，避免两边漂移；
 *   · 规则是纯函数，可直接被 node:test 覆盖（见 `frontend/scripts/kb-starters.test.mts`）。
 *
 * 刻意不做的事：不用文档标题生成问题。文档名要额外为每个库调一次列表接口，
 * 而"《库名》里主要讲了什么"已经命中该库的真实资料 —— 按最小改动原则不扩大请求。
 */

/** 句型：整库概览 / 常见坑 / 描述里的某个主题。 */
export type StarterTemplate = 'overview' | 'pitfalls' | 'topic'

/** 生成示例问题所需的最小知识库信息（`KbItem` 的子集，便于测试直接构造）。 */
export interface StarterKb {
  key: string
  name: string
  desc?: string
  /** 文档数：0 = 库里还没有资料，"讲了什么"这种问题就是假的 */
  docs: number
}

/** 一条示例问题的草稿：说清用哪个句型、提到哪个库；文案由调用方按语言拼。 */
export interface StarterDraft {
  kbKey: string
  template: StarterTemplate
  /** `template === 'topic'` 时才有：从知识库描述里取出的短主题 */
  topic?: string
}

/** 主题长度上限：超出就截断，避免把整段描述塞进问句。 */
const TOPIC_MAX = 24

/** 从描述里取一小段当主题：第一句、去首尾空白、按需截断；没有描述返回 null。 */
export function topicOf(desc?: string): string | null {
  const text = (desc ?? '').trim()
  if (!text) return null
  const first = text.split(/[。．.!！?？;；\n]/)[0]?.trim() ?? ''
  const picked = first || text
  return picked.length <= TOPIC_MAX ? picked : `${picked.slice(0, TOPIC_MAX - 1)}…`
}

/**
 * 生成示例问题草稿：最多 `limit` 条、一个库一条、只取**有资料**的库。
 *
 * 句型按序轮换 overview → pitfalls → topic，多库时不会给出一模一样的三句；
 * topic 需要描述，描述为空时退回避 overview（宁可换通用句型，也不编主题）。
 */
export function buildStarters(kbs: StarterKb[], limit = 3): StarterDraft[] {
  const usable = kbs.filter((kb) => kb.docs > 0).slice(0, Math.max(0, limit))
  const order: StarterTemplate[] = ['overview', 'pitfalls', 'topic']
  return usable.map((kb, i) => {
    const wanted = order[i % order.length] ?? 'overview'
    if (wanted !== 'topic') return { kbKey: kb.key, template: wanted }
    const topic = topicOf(kb.desc)
    return topic ? { kbKey: kb.key, template: 'topic', topic } : { kbKey: kb.key, template: 'overview' }
  })
}
