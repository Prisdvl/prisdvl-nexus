import { useState, type ReactElement, type ReactNode } from 'react'
import { StateEmpty } from '@/components/common/StateEmpty'
import { StateError } from '@/components/common/StateError'
import { StateLoading, StateLoadingText } from '@/components/common/StateLoading'
import { StatusBadge } from '@/components/common/StatusBadge'
import { PageHeader } from '@/components/common/PageHeader'
import { ConfirmDialog } from '@/components/common/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Field, Input, Select, Textarea } from '@/components/ui/form'
import { Sheet } from '@/components/ui/sheet'
import { Tabs } from '@/components/ui/tabs'
import { Tooltip } from '@/components/ui/tooltip'
import { toStatusKind, type StatusKind } from '@/components/common/status'

/**
 * R1 组件样例页 —— **仅开发期存在的验证工具**（任务书 §38）。
 *
 * ## 它为什么不算"产品页面"
 *
 * - 路由在 `main.tsx` 里用 `import.meta.env.DEV` 守卫：生产构建**不含**这条路由，
 *   组件树里也不会有它（R1 结束时可整体删掉）。
 * - 它不调任何 API、不连 store、不含业务判断——纯粹把组件摆出来看交互。
 *
 * ## 存在的理由
 *
 * 任务书 §39 要求**真实浏览器**验证 hover / focus / disabled / loading / keyboard /
 * responsive。这些没法靠"build 通过"证明，必须有个能点的地方。
 */
export function ShowcasePage(): ReactElement {
  const [tab, setTab] = useState('a')
  const [sheetOpen, setSheetOpen] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [confirmLoading, setConfirmLoading] = useState(false)
  const [errorOpen, setErrorOpen] = useState(false)
  const [retrying, setRetrying] = useState(false)
  const [inputValue, setInputValue] = useState('')

  const statuses: StatusKind[] = [
    'pending',
    'running',
    'done',
    'partial',
    'failed',
    'cancelled',
    'draft',
    'published',
    'disabled'
  ]

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-8 p-6" data-testid="showcase-page">
      <PageHeader
        testId="showcase-header"
        title="R1 组件样例"
        description="只用于开发期验证交互，生产构建不包含此页。"
      />

      {/* ---------- Button ---------- */}
      <Section title="Button" hint="loading 时尺寸不变（spinner 叠在内容上，children 继续占位）">
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="primary">主要</Button>
          <Button variant="secondary">次要</Button>
          <Button variant="outline">描边</Button>
          <Button variant="ghost">幽灵</Button>
          <Button variant="danger">危险</Button>
        </div>
        <div className="mt-2 flex flex-wrap items-center gap-2">
          <Button size="sm">小</Button>
          <Button size="md">中</Button>
          <Button size="lg">大</Button>
          <Button disabled>禁用</Button>
          <Button loading>加载中</Button>
        </div>
      </Section>

      {/* ---------- Badge / StatusBadge ---------- */}
      <Section title="Badge / StatusBadge" hint="状态词表只有一份（components/common/status.ts）">
        <div className="flex flex-wrap gap-2">
          <Badge>默认</Badge>
          <Badge tone="primary">主要</Badge>
          <Badge tone="success">成功</Badge>
          <Badge tone="warning">警告</Badge>
          <Badge tone="danger">危险</Badge>
          <Badge tone="info">信息</Badge>
          <Badge tone="primary" dot pulse>
            进行中（脉冲）
          </Badge>
        </div>
        <div className="mt-3 flex flex-wrap gap-2">
          {statuses.map((s) => (
            <StatusBadge key={s} status={s} testId={`showcase-status-${s}`} />
          ))}
        </div>
        <div className="mt-3 flex flex-wrap gap-2">
          {/* 直接喂后端枚举：应该被归一并正常显示 */}
          <StatusBadge status="RUNNING" testId="showcase-status-raw-running" />
          <StatusBadge status="PARTIAL" testId="showcase-status-raw-partial" />
          <StatusBadge status={toStatusKind('PUBLISHED')} />
          <StatusBadge status="SOMETHING_UNKNOWN" />
        </div>
      </Section>

      {/* ---------- Card ---------- */}
      <Section title="Card" hint="四种变体：default / interactive / selected / danger">
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
          <Card>
            <CardHeader>
              <CardTitle>默认</CardTitle>
              <CardDescription>无交互</CardDescription>
            </CardHeader>
            <CardContent>内容</CardContent>
          </Card>
          <Card variant="interactive">
            <CardHeader>
              <CardTitle>可点</CardTitle>
              <CardDescription>hover 有反馈</CardDescription>
            </CardHeader>
            <CardContent>hover 我</CardContent>
          </Card>
          <Card variant="selected">
            <CardHeader>
              <CardTitle>已选中</CardTitle>
              <CardDescription>有环</CardDescription>
            </CardHeader>
            <CardContent>当前</CardContent>
          </Card>
          <Card variant="danger">
            <CardHeader>
              <CardTitle>危险</CardTitle>
              <CardDescription>删除类</CardDescription>
            </CardHeader>
            <CardContent>慎用</CardContent>
          </Card>
        </div>
      </Section>

      {/* ---------- 表单 ---------- */}
      <Section title="表单原语" hint="Field 统一 label / 描述 / 错误 / 必填，并自动接 aria-describedby">
        <div className="grid gap-4 md:grid-cols-2">
          <Field label="正常" description="这是补充说明，读屏会读出来">
            {(p) => <Input {...p} placeholder="请输入" />}
          </Field>
          <Field label="有错误" error="名称不能为空">
            {(p) => <Input {...p} defaultValue="" />}
          </Field>
          <Field label="禁用">
            {(p) => <Input {...p} disabled defaultValue="不可编辑" />}
          </Field>
          <Field label="必填" required>
            {(p) => <Input {...p} placeholder="带 * 标记" />}
          </Field>
          <Field label="下拉">
            {(p) => (
              <Select
                {...p}
                defaultValue="1"
                options={[
                  { value: '1', label: '选项一' },
                  { value: '2', label: '选项二' },
                  { value: '3', label: '选项三（禁用）', disabled: true }
                ]}
              />
            )}
          </Field>
          <Field label="多行文本" description="横向不可拖（resize-y）">
            {(p) => <Textarea {...p} placeholder="纵向可拖" />}
          </Field>
          <Field label="受控用法" description={`当前值：${inputValue || '(空)'}`}>
            {(p) => (
              <Input {...p} value={inputValue} onChange={(e) => setInputValue(e.target.value)} />
            )}
          </Field>
        </div>
      </Section>

      {/* ---------- 四态 ---------- */}
      <Section title="Empty / Error / Loading" hint="Error ≠ Empty：判据是服务端有没有说成功">
        <div className="grid gap-3 md:grid-cols-2">
          <Card padding="none">
            <StateEmpty
              title="还没有知识库"
              description="创建一个知识库后，就可以导入自己的资料。"
              actionProps={{ label: '创建知识库', onClick: () => {}, testId: 'showcase-empty-action' }}
            />
          </Card>

          <Card padding="none">
            {errorOpen ? (
              <StateError
                title="资料加载失败"
                description="无法获取当前资料。服务返回：连接超时"
                onRetry={() => {
                  setRetrying(true)
                  setTimeout(() => setRetrying(false), 1200)
                }}
                retrying={retrying}
              />
            ) : (
              <StateEmpty
                variant="inline"
                title="（点下面的按钮看 Error 态）"
                description="Empty 与 Error 互不越界"
                action={
                  <Button size="sm" variant="outline" onClick={() => setErrorOpen(true)}>
                    显示加载失败
                  </Button>
                }
              />
            )}
          </Card>

          <div className="flex flex-col gap-4">
            <div>
              <p className="mb-1 text-xs text-[var(--nx-color-text-muted)]">variant=page</p>
              <StateLoading variant="page" label="整页加载中" />
            </div>
            <div>
              <p className="mb-1 text-xs text-[var(--nx-color-text-muted)]">variant=inline</p>
              <div className="flex items-center gap-2">
                <StateLoading variant="inline" />
                <span className="text-sm">正在保存…</span>
              </div>
            </div>
          </div>

          <div className="flex flex-col gap-4">
            <div>
              <p className="mb-1 text-xs text-[var(--nx-color-text-muted)]">variant=list（骨架屏）</p>
              <StateLoading variant="list" rows={2} />
            </div>
            <div>
              <p className="mb-1 text-xs text-[var(--nx-color-text-muted)]">文本骨架</p>
              <StateLoadingText lines={3} />
            </div>
          </div>
        </div>
      </Section>

      {/* ---------- Tabs ---------- */}
      <Section title="Tabs" hint="键盘 ←/→ 切换，Home/End 跳首尾">
        <Tabs
          value={tab}
          onChange={setTab}
          items={[
            { value: 'a', label: '资料', badge: <Badge>12</Badge> },
            { value: 'b', label: '问答' },
            { value: 'c', label: '高级（禁用）', disabled: true }
          ]}
          panel={(v) => (
            <div className="text-sm text-[var(--nx-color-text-secondary)]">
              当前面板：<code className="font-mono">{v}</code>
            </div>
          )}
        />
      </Section>

      {/* ---------- Tooltip ---------- */}
      <Section title="Tooltip" hint="只用于被截断的标题 / 收起后的图标；字段说明请用 Field.description">
        <div className="flex flex-wrap gap-2">
          <Tooltip content="这是一个提示">
            <Button variant="outline">hover 我</Button>
          </Tooltip>
          <Tooltip content="这是底部提示" side="bottom">
            <Button variant="outline">bottom</Button>
          </Tooltip>
          <Tooltip content="点或聚焦我" trigger="focus">
            <Button variant="outline">focus 触发</Button>
          </Tooltip>
        </div>
      </Section>

      {/* ---------- 浮层 ---------- */}
      <Section title="Dialog / Sheet" hint="焦点陷阱、ESC 关闭、关闭后焦点归位">
        <div className="flex flex-wrap gap-2">
          <Button
            variant="outline"
            onClick={() => setSheetOpen(true)}
            data-testid="showcase-open-sheet"
          >
            打开 Sheet
          </Button>
          <Button
            variant="outline"
            onClick={() => setConfirmOpen(true)}
            data-testid="showcase-open-confirm"
          >
            打开 ConfirmDialog
          </Button>
        </div>
      </Section>

      <Sheet
        open={sheetOpen}
        onClose={() => setSheetOpen(false)}
        title="配置 Agent"
        footer={
          <>
            <Button variant="ghost" onClick={() => setSheetOpen(false)}>
              取消
            </Button>
            <Button onClick={() => setSheetOpen(false)}>保存</Button>
          </>
        }
      >
        <p className="mt-0 text-sm text-[var(--nx-color-text-secondary)]">
          表格单类抽屉：点遮罩**不会**关闭（不会丢输入）。
        </p>
        <div className="flex flex-col gap-4">
          <Field label="名称">{(p) => <Input {...p} defaultValue="Java 导师" />}</Field>
          <Field label="系统提示词">
            {(p) => <Textarea {...p} defaultValue="只依据检索到的资料回答。" />}
          </Field>
        </div>
      </Sheet>

      <ConfirmDialog
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        onConfirm={() => {
          setConfirmLoading(true)
          setTimeout(() => {
            setConfirmLoading(false)
            setConfirmOpen(false)
          }, 1500)
        }}
        title="确认删除知识库？"
        description="将删除「验收库」及其 12 篇文档、340 个切片（向量与索引一并清除）。此操作不可撤销。"
        confirmText="删除"
        danger
        loading={confirmLoading}
        testId="showcase-confirm"
      />
    </div>
  )
}

function Section({
  title,
  hint,
  children
}: {
  title: string
  hint?: string
  children: ReactNode
}): ReactElement {
  return (
    <section className="flex flex-col gap-3">
      <div>
        <h2 className="m-0 text-[var(--nx-text-lg)] font-[var(--nx-weight-semibold)]">{title}</h2>
        {hint ? (
          <p className="mt-0.5 mb-0 text-[var(--nx-text-sm)] text-[var(--nx-color-text-muted)]">
            {hint}
          </p>
        ) : null}
      </div>
      {children}
    </section>
  )
}
