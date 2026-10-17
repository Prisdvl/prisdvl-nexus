import { Menu } from 'lucide-react'
import BrandMark from './BrandMark'
import Loc from './Loc'
import { appCopy } from '@/lib/appCopy'
import { useLocale } from '@/lib/locale'
import type { PageKey } from '@/lib/types'

interface Props {
  page: PageKey
  /** 与 AppShell 的抽屉开关同名，这里只做透传（命名保持一致便于阅读） */
  onOpenDrawer: () => void
}

export default function MobileBar({ page, onOpenDrawer }: Props) {
  const { locale } = useLocale()
  const t = appCopy(locale)
  const title = t.nav[page]

  return (
    <header className="mobile-bar">
      <button
        type="button"
        className="mb-btn"
        aria-label={t.nav.openNav}
        title={t.nav.openNav}
        onClick={onOpenDrawer}
      >
        <Menu size={19} />
      </button>
      <BrandMark wordmark={false} size={22} />
      <span className="mb-title" style={{ flex: 1, margin: 0 }}>
        <Loc key={`${page}-${title}`}>{title}</Loc>
      </span>
    </header>
  )
}
