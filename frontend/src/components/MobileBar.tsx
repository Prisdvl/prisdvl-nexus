import { Menu } from 'lucide-react'
import BrandMark from './BrandMark'
import type { PageKey } from '@/lib/types'

const TITLE: Record<PageKey, string> = {
  workbench: '工作台',
  kb: '知识库',
  chat: '对话',
  agents: 'Agent',
  account: '账号',
}

interface Props {
  page: PageKey
  onOpenDrawer: () => void
}

/** 手机顶栏：汉堡 + 品牌 + 当前页名（<768px 才显示，见 CSS） */
export default function MobileBar({ page, onOpenDrawer }: Props) {
  return (
    <header className="mobile-bar">
      <button className="mb-btn" type="button" aria-label="打开导航" aria-expanded={false} onClick={onOpenDrawer}>
        <Menu size={18} />
      </button>
      <BrandMark size={26} wordmark={false} />
      <span className="mb-title">{TITLE[page]}</span>
    </header>
  )
}