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
const SCHEMA_KEY = 'nx-theme-schema'
const SCHEMA_VER = '3'

type Listener = () => void
const listeners = new Set<Listener>()

const systemDark = () => typeof matchMedia !== 'undefined' && matchMedia('(prefers-color-scheme: dark)').matches

const PALETTES: PaletteName[] = ['prisdvl', 'terracotta', 'turquoise', 'lime', 'indigo', 'ocean', 'violet', 'rose']

/** schema 3：强制启用独立深色深钴蓝主题 Prisdvl */
function migrateThemeSchema() {
  if (typeof localStorage === 'undefined') return
  if (localStorage.getItem(SCHEMA_KEY) === SCHEMA_VER) return
  localStorage.setItem(PALETTE_KEY, 'prisdvl')
  localStorage.setItem(MODE_KEY, 'dark')
  localStorage.removeItem(LEGACY_KEY)
  localStorage.setItem(SCHEMA_KEY, SCHEMA_VER)
}

migrateThemeSchema()

function readMode(): ThemeModeSetting {
  const v = localStorage.getItem(MODE_KEY)
  if (v === 'light' || v === 'dark' || v === 'system') return v
  const legacy = localStorage.getItem(LEGACY_KEY)
  if (legacy === 'light' || legacy === 'dark') return legacy
  return 'dark'
}

function readPalette(): PaletteName {
  const v = localStorage.getItem(PALETTE_KEY)
  return (PALETTES as string[]).includes(v ?? '') ? (v as PaletteName) : 'prisdvl'
}

let mode: ThemeModeSetting = readMode()
let palette: PaletteName = readPalette()

/** Prisdvl 是独立深色主题：选中时界面始终按 dark 渲染 */
const resolve = (m: ThemeModeSetting): ThemeMode => {
  if (palette === 'prisdvl') return 'dark'
  return m === 'system' ? (systemDark() ? 'dark' : 'light') : m
}

function apply() {
  const root = document.documentElement
  /* Prisdvl 锁定暗色，避免亮色壳盖住深钴蓝界面 */
  if (palette === 'prisdvl' && mode !== 'dark') {
    mode = 'dark'
    localStorage.setItem(MODE_KEY, 'dark')
    localStorage.removeItem(LEGACY_KEY)
  }
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
    /* Prisdvl 模式不接受亮色 / 跟随系统 */
    mode = palette === 'prisdvl' ? 'dark' : m
    localStorage.setItem(MODE_KEY, mode)
    localStorage.removeItem(LEGACY_KEY)
    emit()
  },
  setPalette: (p: PaletteName) => {
    palette = p
    localStorage.setItem(PALETTE_KEY, p)
    if (p === 'prisdvl') {
      mode = 'dark'
      localStorage.setItem(MODE_KEY, 'dark')
      localStorage.removeItem(LEGACY_KEY)
    }
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