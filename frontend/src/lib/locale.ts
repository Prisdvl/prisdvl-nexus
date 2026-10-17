import { useEffect, useState } from 'react'
import { ACCOUNT_PREFS_KEY, readAccountPreferences } from '@/lib/preferences'

export type Locale = 'zh' | 'en'

const LOCALE_EVENT = 'nx-locale'

export function normalizeLocale(raw?: string | null): Locale {
  if (!raw) return 'zh'
  const v = raw.trim().toLowerCase()
  if (v === 'en' || v === 'english' || v.startsWith('en')) return 'en'
  return 'zh'
}

export function readLocale(): Locale {
  return normalizeLocale(readAccountPreferences().language)
}

export function writeLocale(locale: Locale) {
  const prefs = readAccountPreferences()
  localStorage.setItem(ACCOUNT_PREFS_KEY, JSON.stringify({
    ...prefs,
    language: locale === 'en' ? 'English' : '简体中文',
  }))
  applyDocumentLang(locale)
  window.dispatchEvent(new CustomEvent(LOCALE_EVENT, { detail: locale }))
}

export function applyDocumentLang(locale: Locale = readLocale()) {
  document.documentElement.lang = locale === 'en' ? 'en' : 'zh-CN'
}

export function subscribeLocale(handler: (locale: Locale) => void) {
  const onCustom = (e: Event) => {
    handler(normalizeLocale((e as CustomEvent<Locale>).detail))
  }
  const onStorage = (e: StorageEvent) => {
    if (e.key !== ACCOUNT_PREFS_KEY) return
    handler(readLocale())
  }
  window.addEventListener(LOCALE_EVENT, onCustom)
  window.addEventListener('storage', onStorage)
  return () => {
    window.removeEventListener(LOCALE_EVENT, onCustom)
    window.removeEventListener('storage', onStorage)
  }
}

export function useLocale() {
  const [locale, setLocaleState] = useState<Locale>(() => {
    const current = readLocale()
    applyDocumentLang(current)
    return current
  })

  useEffect(() => subscribeLocale(setLocaleState), [])

  const setLocale = (next: Locale) => {
    if (next === locale) return
    writeLocale(next)
    setLocaleState(next)
  }

  return {
    locale,
    setLocale,
    toggle: () => setLocale(locale === 'zh' ? 'en' : 'zh'),
  }
}
