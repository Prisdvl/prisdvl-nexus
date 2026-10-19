import { lazy, Suspense, useMemo } from 'react'
import { createHashRouter, Navigate, RouterProvider, useNavigate, useSearchParams, useParams } from 'react-router'
import AppShell from '@/components/AppShell'
import { RedirectIfAuthenticated, RequireAuth } from '@/components/RequireAuth'
import { useAuth, registerAccount } from '@/lib/auth'
import { appCopy } from '@/lib/appCopy'
import { useLocale } from '@/lib/locale'
import { syncConversations } from '@/lib/sync'
import { preferredLandingPath } from '@/lib/preferences'
import type { PaletteName, ThemeMode } from '@/lib/types'
import type { ChatCitationRef } from '@/data'
import { useEffect, useState } from 'react'
import LandingPage from '@/pages/LandingPage'
/*
 * 路由级按需加载（首包瘦身）。
 *
 * 只有落地页 `/` 保持同步 import —— 它是未登录访客看到的第一个画面，不该先等 chunk；
 * 其余页面各切独立 chunk，进入对应路由时才下载。被这些页面连带引入的重型依赖
 * （gsap / simple-icons / 各种演示组件）因此不再进入口包。
 *
 * 外壳（AppShell / 侧栏 / 守卫 / Toast / 配色控件）保持同步：切页时只有内容区出现骨架，
 * 外壳不闪。占位见下面的 `pageFallback`。
 */
const AuthPage = lazy(() => import('@/pages/AuthPage'))
const ForgotPasswordPage = lazy(() => import('@/pages/ForgotPasswordPage'))
const WorkbenchPage = lazy(() => import('@/pages/WorkbenchPage'))
const KnowledgeBaseListPage = lazy(() => import('@/pages/KnowledgeBaseListPage'))
const KnowledgeBaseDetailPage = lazy(() => import('@/pages/KnowledgeBaseDetailPage'))
const ChatPage = lazy(() => import('@/pages/ChatPage'))
const AgentsPage = lazy(() => import('@/pages/AgentsPage'))
const AutomationsPage = lazy(() => import('@/pages/AutomationsPage'))
const WorkflowPage = lazy(() => import('@/pages/WorkflowPage'))
const PluginsPage = lazy(() => import('@/pages/PluginsPage'))
const AgentCommunityPage = lazy(() => import('@/pages/AgentCommunityPage'))
const AccountPage = lazy(() => import('@/pages/AccountPage'))
import CornerControls from '@/components/Appearance'
import { useTheme } from '@/lib/theme'
import ToastHost from '@/components/ToastHost'
import AppError from '@/components/AppError'
import RouteFade from '@/components/RouteFade'
import Loc from '@/components/Loc'
import { SkeletonRows } from '@/components/Skeleton'

/** 按需页面下载期间的占位：高度留够，避免内容就绪时布局跳动 */
const pageFallback = (
  <div className="page" style={{ minHeight: '60vh' }}>
    <SkeletonRows rows={5} />
  </div>
)

/* ---------------- 主题/配色（原型级，放这里供所有路由共享） ---------------- */

/* ---------------- 页面包装（注入路由能力） ---------------- */

function LandingRoute({ theme, palette }: { theme: ThemeMode; palette: PaletteName }) {
  const navigate = useNavigate()
  return (
    <>
      <LandingPage theme={theme} palette={palette} onEnterAuth={() => navigate('/login')} />
      <CornerControls />
    </>
  )
}

function ForgotPasswordRoute({ theme, palette }: { theme: ThemeMode; palette: PaletteName }) {
  const navigate = useNavigate()
  return (
    <>
      <ForgotPasswordPage
        theme={theme}
        palette={palette}
        onGoHome={() => navigate('/')}
        onGoLogin={() => navigate('/login')}
      />
      <CornerControls />
    </>
  )
}

function LoginRoute({ theme, palette }: { theme: ThemeMode; palette: PaletteName }) {
  const navigate = useNavigate()
  const { login } = useAuth()
  return (
    <>
      <AuthPage
        theme={theme}
        palette={palette}
        onGoHome={() => navigate('/')}
        onEnter={async (from, cred) => {
          /*
           * 登录失败要停在这里：authStore.login 会抛（凭证错/连不上后端），
           * 只有成功才导航。原来 login() 是同步置标记、不可能失败，
           * 所以这个 try 是接后端后新加的。
           */
          try {
            await login(cred.username, cred.password)
          } catch {
            return // 错误由 AuthPage 捕获并展示，这里不导航
          }
          /*
           * 登录成功后立刻拉一次后端会话列表 —— 这是 P1-1 的验收标准
           * （"登录后侧栏会话列表来自后端"）。
           *
           *⚠️ 失败**不阻断导航**：拿不到列表不该让用户卡在登录页，
           *   侧栏会继续显示本地数据，用户可以正常进应用。
           */
          void syncConversations()
          navigate(from && from.startsWith('/app') ? from : preferredLandingPath(), { replace: true })
        }}
        onRegister={async (cred) => {
          /*
           * 只调 `POST /auth/register` 建号，**不自动登录、不导航**。
           * 后端 register 返回 user 但**不返令牌**（实测），
           * 自动登录会让 `RedirectIfAuthenticated` 立刻把登录页弹走，
           * 用户来不及确认"注册成功"。所以留在登录页，由用户主动登录。
           */
          await registerAccount(cred)
        }}
      />
      <CornerControls />
    </>
  )
}

function WorkbenchRoute({ theme, palette }: { theme: ThemeMode; palette: PaletteName }) {
  const navigate = useNavigate()
  return (
    <WorkbenchPage
      theme={theme}
      palette={palette}
      goChat={() => navigate('/app/chat')}
      goKb={() => navigate('/app/kb')}
      goAgents={() => navigate('/app/agents')}
      goAutomations={() => navigate('/app/automations')}
      onOpenConversation={(id) => navigate(`/app/chat?conversation=${encodeURIComponent(id)}`)}
    />
  )
}

function KbListRoute() {
  const navigate = useNavigate()
  return <KnowledgeBaseListPage onOpen={(kb) => navigate(`/app/kb/${kb.id}`)} />
}

function KbDetailRoute({ theme }: { theme: ThemeMode }) {
  const navigate = useNavigate()
  const { id } = useParams()
  const [params] = useSearchParams()
  const doc = params.get('doc')
  if (!id) return <Navigate to="/app/kb" replace />
  return (
    <KnowledgeBaseDetailPage
      kbId={id}
      theme={theme}
      highlightDoc={doc}
      onBack={() => navigate('/app/kb')}
      onOpenDoc={(docName) => navigate(`/app/kb/${id}?doc=${encodeURIComponent(docName)}`)}
      onClearHighlight={() => {
        /*
         * 守卫：只有"还停在这个知识库详情页"时才清参数。
         *
         * 清参数是 2.5s 延迟执行的；如果用户在这 2.5s 内点了别的导航，
         * 迟到的 replace 会把**新页面那条历史记录**改写掉
         * （实测：点工作台后 URL 变成 /app/kb/1，前进/后退随之错乱）。
         */
        const here = window.location.hash.replace(/^#/, '').split('?')[0]
        if (here === `/app/kb/${id}`) navigate(`/app/kb/${id}`, { replace: true })
      }}
    />
  )
}

function ChatRoute({ theme }: { theme: ThemeMode }) {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const conversationId = params.get('conversation') ?? undefined
  return <ChatPage theme={theme} initialConversationId={conversationId} onOpenDoc={(citation: ChatCitationRef) => { if (citation.knowledgeBaseId) navigate(`/app/kb/${encodeURIComponent(citation.knowledgeBaseId)}?doc=${encodeURIComponent(citation.documentTitle ?? '')}`) }} />
}

function NotFoundRoute() {
  const navigate = useNavigate()
  const s = appCopy(useLocale().locale).shell
  return (
    <div className="page" style={{ textAlign: 'center', paddingTop: 80 }}>
      <p className="eyebrow">404</p>
      <h1 className="page-title"><Loc>{s.notFoundTitle}</Loc></h1>
      <p className="page-sub" style={{ marginBottom: 20 }}><Loc>{s.notFoundBody}</Loc></p>
      <button className="btn btn-primary" type="button" onClick={() => navigate('/app/workbench')}><Loc>{s.errorHome}</Loc></button>
    </div>
  )
}

/* ---------------- 路由表 ---------------- */

export default function App() {
  const { resolved: theme, palette } = useTheme()

  const router = useMemo(
    () =>
      createHashRouter([
        /* 路由级错误边界：任何渲染期异常都落到我们自己的页面，而不是 Router 的默认英文错误页 */
        {
          errorElement: <AppError />,
          children: [
        { path: '/', element: <RouteFade><LandingRoute theme={theme} palette={palette} /></RouteFade> },
        {
          path: '/login',
          element: (
            <RedirectIfAuthenticated>
              <RouteFade>
                <Suspense fallback={pageFallback}>
                  <LoginRoute theme={theme} palette={palette} />
                </Suspense>
              </RouteFade>
            </RedirectIfAuthenticated>
          ),
        },
        {
          path: '/forgot',
          element: (
            <RedirectIfAuthenticated>
              <RouteFade>
                <Suspense fallback={pageFallback}>
                  <ForgotPasswordRoute theme={theme} palette={palette} />
                </Suspense>
              </RouteFade>
            </RedirectIfAuthenticated>
          ),
        },
        {
          element: <RequireAuth />,
          children: [
            {
              element: <AppShell theme={theme} palette={palette} />,
              children: [
                { path: '/app', element: <Navigate to="/app/workbench" replace /> },
                { path: '/app/workbench', element: <WorkbenchRoute theme={theme} palette={palette} /> },
                { path: '/app/kb', element: <KbListRoute /> },
                { path: '/app/kb/:id', element: <KbDetailRoute theme={theme} /> },
                { path: '/app/chat', element: <ChatRoute theme={theme} /> },
                { path: '/app/agents', element: <AgentsPage /> },
                { path: '/app/automations', element: <AutomationsPage /> },
                { path: '/app/workflows', element: <WorkflowPage /> },
                { path: '/app/plugins', element: <PluginsPage /> },
                { path: '/app/community', element: <AgentCommunityPage /> },
                { path: '/app/account', element: <AccountPage theme={theme} /> },
                { path: '/app/*', element: <NotFoundRoute /> },
              ],
            },
          ],
        },
        { path: '*', element: <Navigate to="/" replace /> },
          ],
        },
      ]),
    [theme, palette],
  )

  return (
    <>
      <RouterProvider router={router} />
      <ToastHost theme={theme} />
    </>
  )
}
