/**
 * 主题与配色（原型级全局 store）
 *
 * 为什么要从"右下角悬浮条"改到这里：
 * - 悬浮条常驻在**每个页面**上，属于开发者工具，不该出现在产品界面里；
 * - 主题是**设置项**，应该能在设置页找到，并在登录前也能改（入口/登录页的角落里）。
 *
 * 支持 `system`（跟随系统），默认即为 system —— 用户没表过态时就跟随系统最不容易出错。
 */
import { useSyncExternalStore } from 'react'
import type { PaletteName, ThemeMode } from '@/lib/types'

export type ThemeModeSetting = ThemeMode | 'system'

const MODE_KEY = 'nx-proto-theme-mode'
const LEGACY_KEY = 'nx-proto-theme' // 兼容旧键
const PALETTE_KEY = 'nx-proto-palette'

type Listener = () => void
const listeners = new Set<Listener>()

const systemDark = () => typeof matchMedia !== 'undefined' && matchMedia('(prefers-color-scheme: dark)').matches

function readMode(): ThemeModeSetting {
  const v = localStorage.getItem(MODE_KEY)
  if (v === 'light' || v === 'dark' || v === 'system') return v
  const legacy = localStorage.getItem(LEGACY_KEY)
  if (legacy === 'light' || legacy === 'dark') return legacy
  return 'system'
}

function readPalette(): PaletteName {
  const v = localStorage.getItem(PALETTE_KEY)
  return v === 'turquoise' || v === 'lime' || v === 'terracotta' ? v : 'terracotta'
}

let mode: ThemeModeSetting = readMode()
let palette: PaletteName = readPalette()

const resolve = (m: ThemeModeSetting): ThemeMode => (m === 'system' ? (systemDark() ? 'dark' : 'light') : m)

function apply() {
  const root = document.documentElement
  root.dataset.theme = resolve(mode)
  root.dataset.palette = palette
  root.dataset.themeMode = mode
}

const emit = () => {
  apply()
  listeners.forEach((l) => l())
}

const subscribe = (l: Listener) => {
  listeners.add(l)
  return () => listeners.delete(l)
}

/* 跟随系统：系统切换时自动重算（只在 mode === 'system' 时才有影响） */
if (typeof matchMedia !== 'undefined') {
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (mode === 'system') emit()
  })
}

apply()

export const themeStore = {
  mode: () => mode,
  palette: () => palette,
  /** 实际生效的主题（system 已解析） */
  resolved: () => resolve(mode),
  setMode: (m: ThemeModeSetting) => {
    mode = m
    localStorage.setItem(MODE_KEY, m)
    localStorage.removeItem(LEGACY_KEY)
    emit()
  },
  setPalette: (p: PaletteName) => {
    palette = p
    localStorage.setItem(PALETTE_KEY, p)
    emit()
  },
}

/*
 * 仅开发环境的测试钩子。
 * 端到端测试需要"不刷新就切主题"（刷新会丢掉内存态：刚发的消息、打开的面板）。
 * 用 import.meta.env.DEV 包住，生产构建里这段会被摇掉。
 */
if (import.meta.env.DEV) {
  ;(window as unknown as Record<string, unknown>).__nxTheme = {
    setMode: themeStore.setMode,
    setPalette: themeStore.setPalette,
    state: () => ({ mode, palette, resolved: themeStore.resolved() }),
  }
}

export function useTheme() {
  const mode = useSyncExternalStore(subscribe, themeStore.mode, themeStore.mode)
  const palette = useSyncExternalStore(subscribe, themeStore.palette, themeStore.palette)
  const resolved = useSyncExternalStore(subscribe, themeStore.resolved, themeStore.resolved)
  return {
    mode,
    palette,
    resolved,
    setMode: themeStore.setMode,
    setPalette: themeStore.setPalette,
  }
}