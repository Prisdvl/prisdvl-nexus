import { useCallback, useEffect, useState, type ReactElement } from 'react'
import { PageHeader } from '@/components/common/PageHeader'
import { StateEmpty } from '@/components/common/StateEmpty'
import { StateError } from '@/components/common/StateError'
import { StateLoading } from '@/components/common/StateLoading'
import { AgentCard } from '@/components/agent/AgentCard'
import { AgentEditor } from '@/components/agent/AgentEditor'
import { AgentTester } from '@/components/agent/AgentTester'
import { Button } from '@/components/ui/button'
import { listTools } from '@/api/agent'
import { useAsyncState } from '@/hooks/useAsyncState'
import { useConfirm } from '@/hooks/useConfirm'
import { useAgentStore } from '@/stores/agentStore'
import type { Agent } from '@/api/types/agent'

/**
 * Agent 管理页（`/agents`）—— R9。
 *
 * 流程按后端注释里说的"先试跑再发布"组织：
 * ```text
 * 创建（草稿）→ 试跑验证 → 发布 → 对话里可用
 * ```
 * 所以卡片上**试跑按钮对草稿也可用**（后端明确允许），
 * 禁用它等于堵死主流程。
 */
export function AgentsPage(): ReactElement {
  const store = useAgentStore()
  const confirm = useConfirm()

  const [editorOpen, setEditorOpen] = useState(false)
  const [editing, setEditing] = useState<Agent | null>(null)
  const [testing, setTesting] = useState<Agent | null>(null)

  /*
   * 列表**直接订阅 store**，不用 `useAsyncState` 包一层。
   *
   * ## 为什么（R9 唯一那条 FAIL 的根因）
   *
   * 原来写的是 `useAsyncState(async () => { await store.fetchList(); return store.list })`
   * ——那是**快照**，不是订阅。`store.remove()` 改了 `store.list`，
   * 但 `useAsyncState` 的 `data` 不会跟着变，只有 `retry()` 才重新取。
   * 于是删除后列表不刷新（E2E 实测该项 FAIL，而后端删除实际成功）。
   *
   * 这与 R8 那个「乐观消息被 `useAsyncState.setData` 覆盖」是**同一类错误**：
   * **别用"拉一次存快照"的方式管理一个"会被别处修改"的数据源。**
   * store 本来就是干这个的——直接订阅。
   */
  useEffect(() => {
    void store.fetchList()
  }, [store])

  /** 刷新 = 让 store 重新拉（而不是让快照重新取）。 */
  const refresh = useCallback(async () => {
    await store.fetchList({ force: true })
  }, [store])

  // 工具清单：编辑器要用它列可勾选项
  const tools = useAsyncState((signal) => listTools(signal), [])

  const onCreate = () => {
    setEditing(null)
    setEditorOpen(true)
  }

  const onEdit = (a: Agent) => {
    setEditing(a)
    setEditorOpen(true)
  }

  const onDelete = useCallback(
    async (a: Agent) => {
      const ok = await confirm({
        title: `删除 Agent「${a.name}」？`,
        description: a.builtin
          ? '这是**内置 Agent**，删除后使用它的对话会失败。'
          : '删除后使用它的对话会失败。此操作不可撤销。',
        confirmText: '删除',
        danger: true
      })
      if (!ok) return
      await store.remove(a.id)
      await refresh()
    },
    [confirm, store, refresh]
  )

  const onDuplicate = async (a: Agent) => {
    const copy = await store.duplicate(a.id)
    if (copy) await refresh()
  }

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 p-6" data-testid="agents-page">
      <PageHeader
        testId="agents-header"
        title="Agent"
        description="配置你的智能助手：它能检索哪些资料、能用哪些工具。"
        actions={
          <Button onClick={onCreate} data-testid="agent-create-open">
            创建 Agent
          </Button>
        }
      />

      {store.loading || store.list.length === 0 ? (
        <StateLoading variant="list" rows={3} className="p-0" />
      ) : null}

      {store.error ? (
        <StateError
          title="Agent 列表加载失败"
          {...(store.error ? { description: store.error } : {})}
          onRetry={() => void refresh()}
        />
      ) : null}

      {store.list.length === 0 && !store.loading ? (
        <StateEmpty
          title="还没有 Agent"
          description="创建一个 Agent，把它接到对话里。"
          actionProps={{ label: '创建 Agent', onClick: onCreate, testId: 'agent-empty-create' }}
        />
      ) : null}

      {store.list.length > 0 ? (
        <ul className="m-0 grid list-none grid-cols-1 gap-3 p-0 sm:grid-cols-2">
          {store.list.map((a) => (
            <li key={a.id} className="flex">
              <AgentCard
                agent={a}
                onEdit={() => onEdit(a)}
                onTest={() => setTesting(a)}
                onDuplicate={() => void onDuplicate(a)}
                onDelete={() => void onDelete(a)}
              />
            </li>
          ))}
        </ul>
      ) : null}

      {/* ---- 编辑器（创建 / 编辑共用）---- */}
      <AgentEditor
        open={editorOpen}
        agent={editing}
        tools={tools.data ?? []}
        onClose={() => setEditorOpen(false)}
        onSaved={async () => {
          setEditorOpen(false)
          await refresh()
        }}
      />

      {/* ---- 试跑面板 ---- */}
      {testing ? (
        <AgentTester
          agent={testing}
          onClose={() => setTesting(null)}
        />
      ) : null}
    </div>
  )
}
