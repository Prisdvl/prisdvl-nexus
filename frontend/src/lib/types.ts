/** 跨页面共享的轻量类型（避免页面互相 import 造成环） */
export type ThemeMode = 'light' | 'dark'
export type PaletteName = 'terracotta' | 'turquoise' | 'lime'
export type PageKey = 'workbench' | 'kb' | 'chat' | 'agents' | 'account'

export const PAGE_PATH: Record<PageKey, string> = {
  workbench: '/app/workbench',
  kb: '/app/kb',
  chat: '/app/chat',
  agents: '/app/agents',
  account: '/app/account',
}