import { create } from 'zustand'
import {
  createKnowledgeBase as createKbApi,
  deleteKnowledgeBase as deleteKbApi,
  listKnowledgeBases
} from '@/api/knowledge'
import type { CreateKnowledgeBaseRequest, KnowledgeBase } from '@/api/types/knowledge'
import { getErrorMessage, isApiError } from '@/lib/errors'

/**
 * 知识库（= 任务书里的 workspace）状态。
 *
 * ## 命名说明（R3 任务书 §4 / §12）
 *
 * 任务书要求建 `workspaceStore` 管理"workspace 列表 / 当前 workspace / 切换"。
 * **后端没有 WorkspaceController**——`workspace` 表是内部归属概念，
 * 每个用户由 `workspaceService.requirePersonalWorkspace(userId)` 自动解析出唯一的
 * "个人 workspace"，前端拿不到它的列表，也没有切换它的接口。
 *
 * 按 R3 任务书 §3「API 必须以真实 Controller/DTO 为准」，这里不虚构 workspace API，
 * 改为管理**用户真正看得见的资料空间**：`KnowledgeBase` 列表。
 * 字段名沿用后端 DTO，避免造出第二套定义（§17 禁止）。
 *
 * ## 明确不进来的东西
 * | 东西 | 归属 | 原因 |
 * | --- | --- | --- |
 * | `sidebarCollapsed` | `AppSidebar` 组件 + localStorage | 纯 UI 状态。放进这里等于把"UI 偏好"与"业务数据"混在一个 store |
 * | 详情页的检索分页 / 选中文档 | 页面局部 | 换页面就该丢弃 |
 * | Chat 会话 | 未来的 Chat store | 与知识库无关 |
 * | 导入任务状态 | R7 | 那是**另一个**概念，见下 |
 *
 * ## ⚠️ 知识库的 status 与导入任务的 status 是两件事
 *
 * 后端 `KnowledgeBaseStatus` 只有两个值：`ENABLED` / `DISABLED`（启用 / 停用）。
 * 而 `QUEUED` / `RUNNING` / `DONE` / `PARTIAL` / `FAILED` 是
 * **ImportTask 的状态**（R7 才用到）。
 *
 * Vue 侧把两者混在一个函数里：`statusText(kb)` 读的是 `importTasks[kb.id].status`——
 * 卡片上"处理状态"那一栏显示的其实是**最近一次导入任务的状态**，
 * 不是知识库自身状态；新用户还没导入过时它干脆返回空字符串。
 *
 * 本 store **只管知识库自身的 ENABLED/DISABLED**，导入任务状态留给 R7 单独建 store。
 */

interface KnowledgeState {
  /** 已加载的知识库列表。 */
  list: KnowledgeBase[]
  loading: boolean
  error: string | null
  /** 已拉取过没有。区分"没拉过"与"拉完了但为空"。 */
  loaded: boolean
}

interface KnowledgeActions {
  /**
   * 拉取列表。
   *
   * @param force 已拉取过时是否强制重拉（退出登录后要传 true，
   *             否则下一个账号会看到上一个账号的列表）。
   */
  fetchList: (options?: { force?: boolean; signal?: AbortSignal }) => Promise<void>
  /** 创建。成功后并入列表，省一次往返。 */
  create: (payload: CreateKnowledgeBaseRequest) => Promise<KnowledgeBase | null>
  /** 删除。成功后从列表移除。 */
  remove: (id: string) => Promise<boolean>
  /** 退出登录 / 切换账号时清空。**必须**调，否则会看到别人的数据。 */
  reset: () => void
}

export const useKnowledgeStore = create<KnowledgeState & KnowledgeActions>()((set, get) => ({
  list: [],
  loading: false,
  error: null,
  loaded: false,

  async fetchList({ force = false, signal } = {}) {
    const { loading, loaded } = get()
    if (loading) return
    if (loaded && !force) return

    set({ loading: true, error: null })
    try {
      const res = await listKnowledgeBases({}, signal)
      set({ list: res.list, loaded: true, loading: false })
    } catch (e) {
      if (isApiError(e) && e.isAborted) {
        set({ loading: false })
        return
      }
      set({ error: getErrorMessage(e), loading: false })
    }
  },

  async create(payload) {
    try {
      const created = await createKbApi(payload)
      // 并入列表而不是重拉：省一个往返，用户立刻就能看到刚建的。
      // 依赖"后端返回完整实体"这个契约（Vue 侧一直这么用）。
      set({ list: [created, ...get().list] })
      return created
    } catch (e) {
      set({ error: getErrorMessage(e) })
      return null
    }
  },

  async remove(id) {
    try {
      await deleteKbApi(id)
      set({ list: get().list.filter((k) => k.id !== id) })
      return true
    } catch (e) {
      set({ error: getErrorMessage(e) })
      return false
    }
  },

  reset() {
    set({ list: [], loaded: false, error: null, loading: false })
  }
}))
