import { useState, type FormEvent, type ReactElement } from 'react'
import { PageHeader } from '@/components/common/PageHeader'
import { StateEmpty } from '@/components/common/StateEmpty'
import { StateError } from '@/components/common/StateError'
import { StateLoading } from '@/components/common/StateLoading'
import { KnowledgeBaseCard } from '@/components/knowledge/KnowledgeBaseCard'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, Input, Textarea } from '@/components/ui/form'
import { useAsyncState } from '@/hooks/useAsyncState'
import { useConfirm } from '@/hooks/useConfirm'
import { useKnowledgeStore } from '@/stores/knowledgeStore'
import { listKnowledgeBases } from '@/api/knowledge'
import type { KnowledgeBase } from '@/api/types/knowledge'

/**
 * 知识库列表页（`/`）—— **R6 的第一个业务页面**。
 *
 * 本页同时承担三件事，任一件都不能省：
 *
 * | 状态 | 组件 | 判据 |
 * | --- | --- | --- |
 * | 加载中 | `StateLoading variant="list"` | 请求在途 |
 * | 失败 | `StateError` + 重试 | **服务端没成功** |
 * | 为空 | `StateEmpty` + 创建 | 成功，但 0 条 |
 *
 * 最后一行是重点：`loaded === true && list.length === 0` 才是空态；
 * 加载失败会走 StateError 那条分支，**不会**退化成"你还没有知识库"
 * （那正是 Vue 侧「加载失败最终显示成『没有数据』」的缺陷）。
 */
export function KnowledgeBaseListPage(): ReactElement {
  const store = useKnowledgeStore()
  const confirm = useConfirm()

  const [createOpen, setCreateOpen] = useState(false)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [nameError, setNameError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  // 列表数据：用 R4 的 useAsyncState（四态 + 竞态防护 + 卸载时真 abort）
  const list = useAsyncState((signal) => listKnowledgeBases({}, signal), [])

  /*
   * 提交逻辑。**故意接受可选的 event**：
   *
   * 第一版写成 `async (e: FormEvent) => { e.preventDefault(); … }`，
   * 然后在 Dialog 的 footer 按钮上写 `onClick={(e) => void onCreate(e as unknown as FormEvent)}`。
   * 那是个**假 Event**（`preventDefault` 是 no-op），而且——
   *
   * **footer 与 children 是兄弟节点，不是父子**。所以那个 `type="submit"` 按钮
   * 根本不在 `<form>` 里，点它**不会触发 submit**，创建功能完全失效。
   * R0~R4 没有这种"UI 结构与交互耦合"的问题，只有走真实用户路径才暴露。
   *
   * 现在：footer 按钮直接 `onClick={onCreate}`，form 保留 `onSubmit` 处理 Enter 键。
   */
  const onCreate = async (e?: FormEvent) => {
    e?.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) {
      setNameError('请输入知识库名称')
      return
    }
    setNameError(null)
    setSaving(true)
    const created = await store.create({
      name: trimmed,
      ...(description.trim() ? { description: description.trim() } : {})
    })
    setSaving(false)
    if (!created) return
    // 成功后关闭并清空：不清空的话下次打开会看到上次的残留内容
    setCreateOpen(false)
    setName('')
    setDescription('')
    // 同步卡片网格的本地数据，避免要等下一次 fetch 才看到
    void list.retry()
  }

  const onDelete = async (kb: KnowledgeBase) => {
    // 删除不可撤销：必须写清会失去什么
    const ok = await confirm({
      title: `确认删除「${kb.name}」？`,
      description:
        `将删除该知识库及其 ${kb.docCount ?? 0} 篇文档、${kb.chunkCount ?? 0} 个切片` +
        '（向量与索引一并清除）。此操作不可撤销。',
      confirmText: '删除',
      danger: true
    })
    if (!ok) return
    await store.remove(kb.id)
    void list.retry()
  }

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 p-6" data-testid="kb-list-page">
      <PageHeader
        testId="kb-list-header"
        title="知识库"
        description="把你的资料放进来，之后就能基于它们提问。"
        actions={
          <Button onClick={() => setCreateOpen(true)} data-testid="kb-create-open">
            创建知识库
          </Button>
        }
      />

      {/* ---- 加载中 ---- */}
      {list.state === 'loading' ? (
        <StateLoading variant="list" rows={3} className="p-0" />
      ) : null}

      {/* ---- 失败（与空态严格分离）---- */}
      {list.state === 'error' ? (
        <StateError
          title="知识库加载失败"
          {...(list.error ? { description: list.error } : {})}
          onRetry={() => void list.retry()}
        />
      ) : null}

      {/* ---- 成功但为空 ---- */}
      {list.state === 'success' && list.data && list.data.list.length === 0 ? (
        <StateEmpty
          title="还没有知识库"
          description="创建一个知识库后，就可以导入自己的资料。"
          actionProps={{
            label: '创建知识库',
            onClick: () => setCreateOpen(true),
            testId: 'kb-empty-create'
          }}
        />
      ) : null}

      {/* ---- 有数据 ---- */}
      {list.state === 'success' && list.data && list.data.list.length > 0 ? (
        <>
          <ul className="m-0 grid list-none grid-cols-1 gap-3 p-0 sm:grid-cols-2 lg:grid-cols-3">
            {list.data.list.map((kb) => (
              <li key={kb.id} className="flex">
                <div className="flex w-full flex-col">
                  <KnowledgeBaseCard kb={kb} />
                  <div className="mt-1 flex justify-end">
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => void onDelete(kb)}
                      data-testid={`kb-delete-${kb.id}`}
                    >
                      删除
                    </Button>
                  </div>
                </div>
              </li>
            ))}
          </ul>

          <p className="m-0 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
            共 {list.data.total} 个知识库
          </p>
        </>
      ) : null}

      {/* ---- 创建弹窗 ---- */}
      <Dialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="创建知识库"
        // 表单类弹窗：点遮罩不关，否则会丢已填内容
        closeOnOverlayClick={false}
        footer={
          <>
            <Button variant="ghost" onClick={() => setCreateOpen(false)} disabled={saving}>
              取消
            </Button>
            {/* 按钮在 form 之外，所以用 onClick 而不是 type="submit" */}
            <Button onClick={() => void onCreate()} loading={saving} data-testid="kb-create-submit">
              创建
            </Button>
          </>
        }
      >
        <form
          className="flex flex-col gap-4"
          onSubmit={(e) => void onCreate(e)}
          noValidate
          data-testid="kb-create-form"
        >
          <Field label="名称" required error={nameError}>
            {(p) => (
              <Input
                {...p}
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="例如：Java 学习笔记"
                data-testid="kb-create-name"
              />
            )}
          </Field>
          <Field label="描述" description="可选。一句话说明这个库里放什么。">
            {(p) => (
              <Textarea
                {...p}
                rows={3}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="例如：JVM 调优、Spring 源码笔记"
                data-testid="kb-create-description"
              />
            )}
          </Field>
          {/*
            隐藏的 submit 按钮：`<form>` 内没有任何 submit 控件时，
            部分浏览器按 Enter 不会触发 submit。有了它，Enter 键才可靠。
            它在 form 内，所以是**真正**的 submit 按钮。
          */}
          <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
        </form>
      </Dialog>
    </div>
  )
}
