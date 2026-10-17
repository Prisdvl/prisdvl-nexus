import { useEffect, useState } from 'react'
import { Outlet, useLocation } from 'react-router'
import Sidebar from '@/components/Sidebar'
import MobileBar from '@/components/MobileBar'
import RouteFade from '@/components/RouteFade'
import SkipLink from '@/components/SkipLink'
import OnboardingTour from '@/components/OnboardingTour'
import WavesBg from '@/components/WavesBg'
import type { PageKey, ThemeMode, PaletteName } from '@/lib/types'
import { syncConversations } from '@/lib/sync'

interface ShellProps {
  theme: ThemeMode
  palette: PaletteName
}

const SEGMENT_TO_PAGE: Record<string, PageKey> = {
  workbench: 'workbench',
  kb: 'kb',
  chat: 'chat',
  agents: 'agents',
  automations: 'automations',
  workflows: 'workflows',
  plugins: 'plugins',
  community: 'community',
  account: 'account',
}

/** 应用外壳：侧栏（桌面完整 / 平板图标栏 / 手机抽屉）+ 手机顶栏 + 背景 + 内容出口 */
export default function AppShell({ theme, palette }: ShellProps) {
  const { pathname } = useLocation()
  const segment = pathname.split('/')[2] ?? 'workbench'
  const page: PageKey = SEGMENT_TO_PAGE[segment] ?? 'workbench'
  const [drawerOpen, setDrawerOpen] = useState(false)

  /* 路由一变就收起抽屉：点导航、前进后退、深链都会走这里 */
  useEffect(() => { setDrawerOpen(false) }, [pathname])

  /*
   * 进入应用时同步一次会话列表（不是只在登录那一刻）。
   *
   * ⚠️ 之前只在 `LoginRoute.onEnter` 里同步，导致**刷新页面就回到种子数据**：
   * store 从 localStorage 快照或 `SESSIONS` 初始化，之后再没人去问后端。
   * 于是侧栏混进 `seed_s1`，点它去调后端就是 400
   * `conversationId=seed_s1（需要 Long）` —— 实测踩过。
   *
   * 放在 AppShell（所有受保护页面的共同外壳）里，
   * 保证"进入应用"这件事在任何入口下都会同步一次。
   * `syncConversations` 内部幂等，路由切换不会重复打接口。
   */
  useEffect(() => { void syncConversations() }, [])

  /*
   * 验收用测试钩子（仅 DEV）：window.__nxCrash = true 时主动抛错，
   * 用来验证「错误边界」真的会兜住并展示我们自己的恢复页，而不是 Router 的默认英文错误页。
   * 与已有的 window.__nxTheme 同风格，不影响生产构建。
   */
  if (import.meta.env.DEV && (window as unknown as { __nxCrash?: boolean }).__nxCrash) {
    throw new Error('手动触发的渲染错误：用于验收错误边界')
  }

  return (
    <div className="shell">
      {/* 登录后全站统一 Aurora 背景 */}
      <WavesBg theme={theme} palette={palette} variant="aurora" />
      <SkipLink />
      <MobileBar page={page} onOpenDrawer={() => setDrawerOpen(true)} />
      <Sidebar page={page} open={drawerOpen} onClose={() => setDrawerOpen(false)} />
      {/* 首次登录的分步引导（Portal 到 body；遮罩不吃点击，不阻断操作） */}
      <OnboardingTour />
      <main id="main-content" tabIndex={-1} className="shell-main">
        <RouteFade>
          <Outlet />
        </RouteFade>
      </main>
    </div>
  )
}
