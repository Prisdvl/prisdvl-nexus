/**
 * Preferences persisted per browser until a server-side preference endpoint exists.
 *
 * ⚠️ `landing` 以前把**中文当枚举**存进 localStorage（'工作台' / '对话' / '知识库'）。
 * 那是把展示文案写进数据契约 —— 界面一换语言，历史数据就成了待翻译的脏值。
 * 现在存与语言无关的 code（workbench / chat / kb），读取时通过 `normalizeLanding`
 * 兼容老值并自动迁移；`language` 保持原样（`lib/locale.ts` 的 normalizeLocale 同时认
 * '简体中文' / 'English' / 'zh' / 'en'）。
 */
export const ACCOUNT_PREFS_KEY = 'nx-account-preferences'

/** 落地页目标：稳定 code，不随语言变化 */
export type LandingKey = 'workbench' | 'chat' | 'kb'

export const LANDING_KEYS: LandingKey[] = ['workbench', 'chat', 'kb']

export interface AccountPreferences {
  language: string
  timezone: string
  landing: LandingKey
}

const DEFAULTS: AccountPreferences = { language: '简体中文', timezone: 'Asia/Shanghai', landing: 'workbench' }

/** 旧版把中文当枚举写进了存储 —— 保留一张只读映射，读到就迁到 code */
const LEGACY_LANDING: Record<string, LandingKey> = {
  工作台: 'workbench',
  对话: 'chat',
  知识库: 'kb',
}

function normalizeLanding(raw: unknown): LandingKey {
  if (raw === 'workbench' || raw === 'chat' || raw === 'kb') return raw
  if (typeof raw === 'string' && LEGACY_LANDING[raw]) return LEGACY_LANDING[raw]
  return DEFAULTS.landing
}

export function readAccountPreferences(): AccountPreferences {
  try {
    const value = JSON.parse(localStorage.getItem(ACCOUNT_PREFS_KEY) ?? '{}') as Partial<AccountPreferences> & { landing?: unknown }
    return {
      language: value.language ?? DEFAULTS.language,
      timezone: value.timezone ?? DEFAULTS.timezone,
      landing: normalizeLanding(value.landing),
    }
  } catch {
    return DEFAULTS
  }
}

/** 写回整份偏好（读旧值 → 覆盖补丁 → 落盘），保证不会丢字段 */
export function writeAccountPreferences(patch: Partial<AccountPreferences>): AccountPreferences {
  const next: AccountPreferences = { ...readAccountPreferences(), ...patch }
  localStorage.setItem(ACCOUNT_PREFS_KEY, JSON.stringify(next))
  return next
}

export function preferredLandingPath(): string {
  switch (readAccountPreferences().landing) {
    case 'chat': return '/app/chat'
    case 'kb': return '/app/kb'
    default: return '/app/workbench'
  }
}
