/** Preferences persisted per browser until a server-side preference endpoint exists. */
export const ACCOUNT_PREFS_KEY = 'nx-account-preferences'
export interface AccountPreferences {
  language: string
  timezone: string
  landing: '工作台' | '对话' | '知识库'
}
const DEFAULTS: AccountPreferences = { language: '简体中文', timezone: 'Asia/Shanghai', landing: '工作台' }

export function readAccountPreferences(): AccountPreferences {
  try {
    const value = JSON.parse(localStorage.getItem(ACCOUNT_PREFS_KEY) ?? '{}') as Partial<AccountPreferences>
    const landing = value.landing === '对话' || value.landing === '知识库' ? value.landing : '工作台'
    return { language: value.language ?? DEFAULTS.language, timezone: value.timezone ?? DEFAULTS.timezone, landing }
  } catch {
    return DEFAULTS
  }
}

export function preferredLandingPath(): string {
  switch (readAccountPreferences().landing) {
    case '对话': return '/app/chat'
    case '知识库': return '/app/kb'
    default: return '/app/workbench'
  }
}