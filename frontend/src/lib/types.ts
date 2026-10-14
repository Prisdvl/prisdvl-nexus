/** 跨页面共享的轻量类型（避免页面互相 import 造成环） */
export type ThemeMode = 'light' | 'dark'
/**
 * 主题色。
 * - prisdvl：独立深色深钴蓝主题（强制 dark，非整站 Accent 点缀）
 * - terracotta/turquoise/lime：全配色（亮色中性基座带极淡同色相）
 * - indigo/ocean/violet/rose：AccentColor（只换强调色族）
 */
export type PaletteName =
  | 'prisdvl'
  | 'terracotta' | 'turquoise' | 'lime'
  | 'indigo' | 'ocean' | 'violet' | 'rose'
export type PageKey = 'workbench' | 'kb' | 'chat' | 'agents' | 'account'

export const PAGE_PATH: Record<PageKey, string> = {
  workbench: '/app/workbench',
  kb: '/app/kb',
  chat: '/app/chat',
  agents: '/app/agents',
  account: '/app/account',
}