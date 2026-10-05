import { useEffect, useRef, useState, type ChangeEvent, type ReactElement } from 'react'
import { useNavigate, useParams } from 'react-router'
import { PageHeader } from '@/components/common/PageHeader'
import { StateEmpty } from '@/components/common/StateEmpty'
import { StateError } from '@/components/common/StateError'
import { StateLoading } from '@/components/common/StateLoading'
import { AdvancedPanel } from '@/components/knowledge/AdvancedPanel'
import { DocStatusBadge, formatSizeKb } from '@/components/knowledge/docStatus'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog } from '@/components/ui/dialog'
import { Tabs } from '@/components/ui/tabs'
import { Tooltip } from '@/components/ui/tooltip'
import { listDocuments } from '@/api/knowledge'
import { importItemLabel, isItemFailed, isImportFinished } from '@/api/types/knowledge'
import { useAsyncState } from '@/hooks/useAsyncState'
import { useImportTask } from '@/hooks/useImportTask'

/**
 * 知识库详情页（`/kb/:id`）—— **R7 聚焦「资料」区**。
 *
 * 三个区（任务书 §16）：
 * ```text
 * 资料   ← 本阶段实现
 * 问答   ← R8
 * 高级   ← R10（切片 / 检索 / 知识工作流 / 向量化，默认折叠）
 * ```
 * 页签用 R1 的 `Tabs` 原语，内容先占位——占位里写清归属阶段，
 * 不写"开发中"这种 Soon。
 */
export function KnowledgeBaseDetailPage(): ReactElement {
  const { id } = useParams<{ id: string }>()
  /*
   * ⚠️ **不要 `Number(id)`**（R7 实测踩过）。
   *
   * 后端 id 是 19 位雪花（`Long` → JSON string），超出 JS 安全整数上限
   * 2^53-1。`Number('2106725528004902914')` 会得到 `2106725528004903000`——
   * 请求打到一个不存在的库，表现为「资料加载失败 / HTTP 404」，
   * 而 URL 看过去完全正常，**毫无报错线索**。
   *
   * `useParams` 给的本来就是 string，直接用。
   */
  const kbId = id ?? ''
  const navigate = useNavigate()
  const importer = useImportTask()
  const fileInput = useRef<HTMLInputElement>(null)
  const [tab, setTab] = useState('docs')
  const [importOpen, setImportOpen] = useState(false)
  // 高级区的检索模式：hybrid / vector / es 三选一，用来定位是哪一路失灵
  const [searchMode, setSearchMode] = useState<'hybrid' | 'vector' | 'es'>('hybrid')
  const [chosen, setChosen] = useState<File[]>([])

  const valid = /^\d+$/.test(kbId)
  const docs = useAsyncState((signal) => listDocuments(kbId, {}, signal), [kbId], {
    immediate: valid
  })

  if (!valid) {
    return (
      <div className="p-6" data-testid="kb-detail-page">
        <StateError title="知识库编号无效" description="地址里的 /kb/ 后面不是合法编号。" />
      </div>
    )
  }

  /*
   * 导入完成 → 自动重拉文档列表。
   *
   * **不这么做的话**：上传返回任务后，后端还在切片 + 向量化（要调 Ollama，很慢），
   * 文档要等任务完成才出现在 `documents` 列表里。而列表是进入页面时拉的那一次，
   * 于是用户看到的是「导入完成了，但资料列表还是空的」——
   * **必须手动刷新页面才看得见刚导入的文件**。
   *
   * R7 实测：import-result 与逐文件明细都正常显示，但列表一直是 0 篇，
   * 就是漏了这一步。
   */
  const finishedStatus = importer.task?.status
  useEffect(() => {
    if (finishedStatus && isImportFinished(finishedStatus)) {
      void docs.retry()
    }
    // 只在任务状态**变化**时重拉；docs.retry 是稳定的引用（useCallback([])）
  }, [finishedStatus, docs.retry])

  const onPick = (e: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? [])
    setChosen(files)
    if (files.length > 0) setImportOpen(true)
    /*
     * **必须清空 input.value**（任务书 §19）。
     * 不清的话，用户选同一个文件两次时，第二次的 change 事件**不会触发**
     *（value 没变），于是第二次什么也没发生——看起来像"点了没反应"。
     * 这是文件选择最常见的坑。
     */
    if (fileInput.current) fileInput.current.value = ''
  }

  const onImport = async () => {
    if (chosen.length === 0) return
    await importer.start(kbId, chosen)
    setChosen([])
  }

  const docCount = docs.data?.list.length ?? 0

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 p-6" data-testid="kb-detail-page">
      <PageHeader
        testId="kb-detail-header"
        title="知识库详情"
        back={
          <Button
            variant="ghost"
            size="sm"
            onClick={() => navigate('/')}
            data-testid="kb-detail-back"
          >
            ← 知识库
          </Button>
        }
        actions={
          <>
            <input
              ref={fileInput}
              type="file"
              multiple
              className="hidden"
              data-testid="kb-file-picker"
              onChange={onPick}
            />
            <Button
              onClick={() => fileInput.current?.click()}
              data-testid="kb-import-open"
              loading={importer.uploading}
            >
              导入资料
            </Button>
          </>
        }
      />

      <Tabs
        value={tab}
        onChange={setTab}
        items={[
          { value: 'docs', label: '资料', badge: docCount > 0 ? <Badge>{docCount}</Badge> : undefined },
          { value: 'qa', label: '问答' },
          { value: 'advanced', label: '高级' }
        ]}
        panel={(v) => (
          <>
            {v === 'docs' ? (
              <section className="flex flex-col gap-3">
                {/* ---- 导入结果：常驻，不自动消失（任务书 §22） ---- */}
                {importer.task ? (
                  <ImportResult task={importer.task} items={importer.items} onDismiss={importer.reset} />
                ) : null}
                {importer.error ? (
                  <StateError
                    title="导入失败"
                    description={importer.error}
                    onRetry={() => importer.reset()}
                    retryLabel="知道了"
                  />
                ) : null}

                {docs.state === 'loading' ? (
                  <StateLoading variant="list" rows={3} className="p-0" />
                ) : null}

                {docs.state === 'error' ? (
                  <StateError
                    title="资料加载失败"
                    {...(docs.error ? { description: docs.error } : {})}
                    onRetry={() => void docs.retry()}
                  />
                ) : null}

                {docs.state === 'success' && docCount === 0 ? (
                  <StateEmpty
                    title="还没有资料"
                    description="导入你自己的文件，之后就能基于它们提问。"
                    actionProps={{
                      label: '导入资料',
                      onClick: () => fileInput.current?.click(),
                      testId: 'kb-docs-empty-import'
                    }}
                  />
                ) : null}

                {docs.state === 'success' && docCount > 0 ? (
                  <ul className="m-0 flex list-none flex-col gap-2 p-0" data-testid="doc-list">
                    {docs.data?.list.map((d) => (
                      <li key={d.id}>
                        <Card padding="none" className="px-4 py-3" data-testid="doc-item">
                          <div className="flex items-center justify-between gap-3">
                            <div className="min-w-0 flex-1">
                              {/* 用 sourcePath 而非 fileName：Vault 导入时它带目录层级 */}
                              <p className="m-0 truncate text-[var(--nx-text-sm)] font-[var(--nx-weight-medium)]">
                                {d.sourcePath || d.fileName}
                              </p>
                              <p className="m-0 mt-0.5 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
                                {formatSizeKb(d.fileSizeKb)}
                                {d.chunkCount !== null ? ` · ${d.chunkCount} 个切片` : ''}
                                {d.version !== null && d.version > 1 ? ` · 第 ${d.version} 版` : ''}
                              </p>
                            </div>
                            <DocStatusBadge status={d.status} />
                          </div>
                        </Card>
                      </li>
                    ))}
                  </ul>
                ) : null}
              </section>
            ) : null}

            {v === 'qa' ? <Placeholder text="在这里基于你的资料提问。R8 实现。" /> : null}
            {v === 'advanced' ? (
              <div className="flex flex-col gap-3">
                {/*
                  检索模式切换：这是排查召回问题最有用的开关。
                  `vector` 只走向量——搜不到说明向量这一路断了；
                  `es` 只走关键词——搜不到说明向量之外那一路断了。
                */}
                <div className="flex items-center gap-2">
                  <span className="text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
                    检索模式
                  </span>
                  {(['hybrid', 'vector', 'es'] as const).map((m) => (
                    <Button
                      key={m}
                      size="sm"
                      variant={searchMode === m ? 'primary' : 'ghost'}
                      onClick={() => setSearchMode(m)}
                      data-testid={`kb-mode-${m}`}
                    >
                      {m === 'hybrid' ? '混合' : m === 'vector' ? '仅向量' : '仅关键词'}
                    </Button>
                  ))}
                </div>
                <AdvancedPanel
                  kbId={kbId}
                  documents={docs.data?.list ?? []}
                  mode={searchMode}
                />
              </div>
            ) : null}
          </>
        )}
      />

      {/* ---- 导入确认：让用户看清选了哪些文件再开始 ---- */}
      <Dialog
        open={importOpen}
        onClose={() => setImportOpen(false)}
        title="确认导入"
        closeOnOverlayClick={false}
        footer={
          <>
            <Button
              variant="ghost"
              onClick={() => {
                setChosen([])
                setImportOpen(false)
              }}
              disabled={importer.uploading}
            >
              取消
            </Button>
            <Button
              onClick={() => void onImport()}
              loading={importer.uploading}
              data-testid="kb-import-confirm"
            >
              开始导入（{chosen.length} 个）
            </Button>
          </>
        }
      >
        <ul className="m-0 flex list-none flex-col gap-1 p-0 text-[var(--nx-text-sm)]">
          {chosen.map((f) => (
            <li key={f.name + f.size} className="truncate">
              · {f.name}
              <span className="ml-1 text-[var(--nx-color-text-muted)]">
                {formatSizeKb(f.size / 1024)}
              </span>
            </li>
          ))}
        </ul>
        <p className="mt-3 mb-0 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
          导入后会切片、向量化并建索引，全部完成时状态才变成「已完成」。
        </p>
      </Dialog>
    </div>
  )
}

/**
 * 导入结果 —— **常驻在主界面，不自动消失**（任务书 §22）。
 *
 * 这是"用户必须知道的状态"。Vue 侧用 3 秒 toast 报结果，用户来不及看完就没了。
 */
function ImportResult({
  task,
  items,
  onDismiss
}: {
  task: NonNullable<ReturnType<typeof useImportTask>['task']>
  items: ReturnType<typeof useImportTask>['items']
  onDismiss: () => void
}): ReactElement {
  const failed = task.failedCount > 0
  const title =
    task.status === 'DONE'
      ? '导入完成'
      : task.status === 'PARTIAL'
        ? '导入部分成功'
        : task.status === 'FAILED'
          ? '导入失败'
          : '导入处理中'

  return (
    <Card
      padding="md"
      data-testid="import-result"
      className={failed ? 'border-[var(--nx-color-danger)]' : ''}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="m-0 text-[var(--nx-text-sm)] font-[var(--nx-weight-medium)]">
            {title}：{task.createdCount} 个新增
            {task.updatedCount > 0 ? `、${task.updatedCount} 个更新` : ''}
            {task.skippedCount > 0 ? `、${task.skippedCount} 个跳过` : ''}
            {failed ? `、${task.failedCount} 个失败` : ''}
          </p>
          {task.totalChunks > 0 ? (
            <p className="mt-1 mb-0 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
              共 {task.totalChunks} 个切片
            </p>
          ) : null}
          {task.errorMsg ? (
            <p className="mt-1 mb-0 text-[var(--nx-text-xs)] text-[var(--nx-color-danger)]">
              {task.errorMsg}
            </p>
          ) : null}
        </div>

        <div className="flex shrink-0 items-center gap-2">
          {task.progress < 100 ? (
            <Tooltip content={`处理进度 ${task.progress}%`}>
              <Badge tone="primary" data-testid="import-progress" data-value={task.status}>
                {task.progress}%
              </Badge>
            </Tooltip>
          ) : null}
          <Button variant="ghost" size="sm" onClick={onDismiss} data-testid="import-result-dismiss">
            知道了
          </Button>
        </div>
      </div>

      {/*
        逐文件明细 —— 任务书 §20 的核心：**每个文件独立状态**。
        不用一个全局 isUploading，而是把后端给的 items 逐条显示：
        哪个文件失败、原因是什么。
      */}
      {items.length > 0 ? (
        <ul className="mt-3 flex list-none flex-col gap-1 border-t border-[var(--nx-color-border)] p-0 pt-2">
          {items.map((it) => (
            <li
              key={it.id}
              data-testid="import-item"
              data-action={it.action}
              className="flex items-baseline justify-between gap-2 text-[var(--nx-text-xs)]"
            >
              <span className="min-w-0 flex-1 truncate">{it.sourcePath}</span>
              <span
                className={
                  isItemFailed(it.action)
                    ? 'shrink-0 text-[var(--nx-color-danger)]'
                    : 'shrink-0 text-[var(--nx-color-text-muted)]'
                }
              >
                {/* message 是后端给的原因，原样展示——不二次加工 */}
                {it.message || importItemLabel(it.action)}
              </span>
            </li>
          ))}
        </ul>
      ) : null}
    </Card>
  )
}

function Placeholder({ text }: { text: string }): ReactElement {
  return (
    <p className="m-0 py-6 text-center text-[var(--nx-text-sm)] text-[var(--nx-color-text-muted)]">
      {text}
    </p>
  )
}
