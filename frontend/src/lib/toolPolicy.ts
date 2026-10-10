/**
 * 工具授权策略（P1）
 *
 * 高风险工具（执行代码、写文件、发消息）在跑之前必须让用户点头 —— 这是 Agent 产品信任的基础。
 * 「始终允许」需要记住，否则每次都要点，用户会烦到直接关掉整个确认机制。
 *
 * 存储：localStorage（键 nx-proto-tool-policy），记录被"始终允许"的工具名集合。
 * 真实实现里它应该跟着账号走后端（换设备也生效），并且**可撤销**（设置里能看到并收回授权）。
 */
const KEY = 'nx-proto-tool-policy'

const load = (): Set<string> => {
  try {
    const raw = localStorage.getItem(KEY)
    const list = raw ? (JSON.parse(raw) as string[]) : []
    return new Set(Array.isArray(list) ? list : [])
  } catch {
    return new Set()
  }
}

let allowed = load()

export const toolPolicy = {
  /** 该工具是否已被"始终允许" */
  isAllowed: (name: string) => allowed.has(name),
  list: () => [...allowed],

  allowAlways: (name: string) => {
    allowed = new Set([...allowed, name])
    try { localStorage.setItem(KEY, JSON.stringify([...allowed])) } catch { /* 忽略写入失败 */ }
  },

  /** 撤销授权（设置里可调用；下次同工具会重新询问） */
  revoke: (name: string) => {
    allowed = new Set([...allowed].filter((n) => n !== name))
    try { localStorage.setItem(KEY, JSON.stringify([...allowed])) } catch { /* 忽略 */ }
  },

  revokeAll: () => {
    allowed = new Set()
    try { localStorage.removeItem(KEY) } catch { /* 忽略 */ }
  },
}

/*
 * DEV 测试钩子（与 window.__nxTheme 同风格）：
 * 授权同时存在于**内存**与 localStorage 两处，只删 localStorage 不会复位内存状态，
 * 测试必须走这里才能可靠地把"已授权"清掉。
 */
if (import.meta.env.DEV) {
  ;(window as unknown as Record<string, unknown>).__nxToolPolicy = {
    list: () => [...allowed],
    allowAlways: (name: string) => toolPolicy.allowAlways(name),
    revoke: (name: string) => toolPolicy.revoke(name),
    revokeAll: () => toolPolicy.revokeAll(),
  }
}
