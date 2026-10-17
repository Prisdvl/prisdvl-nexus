import { useMemo, useState } from 'react'
import { Search } from 'lucide-react'
import type { ThemeMode } from '@/lib/types'
import { buildSettingsGroups, type SectionId } from '@/pages/settings/schema'
import { appCopy } from '@/lib/appCopy'
import { useLocale } from '@/lib/locale'
import Loc from '@/components/Loc'

/**
 * 设置页：GitHub 式的多级界面。
 *
 * 单路由 + 内部状态（按约定不使用子路由）：左侧按组分节，右侧只渲染当前分区。
 * 手机端左侧导航降级为**横向可滑的分类 tab**。
 * 顶部有设置搜索：输入关键词过滤分区（含中英别名，例如"主题"和 "theme" 都能搜到"外观"）。
 *
 * 分组定义复用 settings/schema（与设置弹层同源），避免两处分叉。
 */

export default function AccountPage({ theme }: { theme: ThemeMode }) {
  const { locale } = useLocale()
  const t = appCopy(locale)
  const st = t.settings
  const GROUPS = useMemo(() => buildSettingsGroups(st), [st])
  const [active, setActive] = useState<SectionId>('profile')
  const [query, setQuery] = useState('')

  const q = query.trim().toLowerCase()
  const groups = useMemo(() => {
    if (!q) return GROUPS
    return GROUPS
      .map((g) => ({ ...g, items: g.items.filter((i) => (i.label + ' ' + i.alias).toLowerCase().includes(q)) }))
      .filter((g) => g.items.length > 0)
  }, [q])

  const all = GROUPS.flatMap((g) => g.items)
  const current = all.find((i) => i.id === active) ?? all[0]
  const visibleCount = groups.reduce((n, g) => n + g.items.length, 0)

  return (
    <div className="page settings-page">
      <div className="page-head">
        <div>
          <p className="eyebrow" style={{ margin: 0 }}><Loc>{st.pageEyebrow}</Loc></p>
          <h1 className="page-title"><Loc>{st.pageTitle}</Loc></h1>
          <p className="page-sub"><Loc>{st.pageSub}</Loc></p>
        </div>
      </div>

      <div className="settings-layout">
        <aside className="settings-nav" aria-label={st.navAria}>
          <div className="settings-search">
            <Search size={15} aria-hidden="true" />
            <input
              className="input"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={st.searchPh}
              aria-label={st.searchAria}
            />
          </div>

          {groups.map((g) => (
            <div className="settings-group-wrap" key={g.title}>
              <p className="settings-group"><Loc>{g.title}</Loc></p>
              {g.items.map((i) => (
                <button
                  className="settings-item"
                  type="button"
                  key={i.id}
                  aria-current={i.id === active ? 'true' : undefined}
                  onClick={() => { setActive(i.id); setQuery('') }}
                >
                  <i.icon size={15} aria-hidden="true" />
                  <span><Loc>{i.label}</Loc></span>
                </button>
              ))}
            </div>
          ))}

          {visibleCount === 0 && (
            <p className="settings-empty">{st.noMatch(query)}</p>
          )}
        </aside>

        <div className="settings-content">
          <nav className="settings-crumbs" aria-label={st.crumbsAria}>
            <span><Loc>{GROUPS.find((g) => g.items.some((i) => i.id === current.id))?.title ?? ""}</Loc></span>
            <span aria-hidden="true">/</span>
            <strong><Loc>{current.label}</Loc></strong>
          </nav>
          {/* 分区切换：key 变化触发淡入，与全站切换过渡一致 */}
          <div className="settings-body route-fade" key={current.id}>
            {current.render()}
          </div>
          <p className="foot-note">{st.footNote}</p>
        </div>
      </div>
    </div>
  )
}