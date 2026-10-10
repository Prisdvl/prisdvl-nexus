import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  createKnowledgeBase,
  deleteDocument,
  deleteKnowledgeBase,
  getDocument,
  listDocuments,
  listKnowledgeBases
} from '@/api/knowledge'
import type { DocumentDetail, DocumentSummary, KnowledgeBase } from '@/api/knowledge'

/**
 * 知识库状态 —— 跨页面共享的那一份真相。
 *
 * ## 为什么它该是 Store（Phase B §14.2 的判据）
 *
 * > 跨页面共享且同一时刻只有一份真相 → Store。
 *
 * 实测（Phase A §4）：知识库列表被**三个视图各自拉取**
 * —— `KnowledgeBaseListView`（列表页）、`AgentView`（对话页选库）、
 * `AgentsView`（配置 Agent 时选库）。三处各有一个 `ref([])` 与自己的 loading。
 *
 * 后果不是"多发两次请求"那么简单：
 * 在 A 页新建的库，切到 B 页要**重新拉一遍**才能看见；
 * 用户会以为"刚才明明建好了"。
 *
 * ## 刻意**不**放进来的东西（Phase C §九）
 *
 * | 数据 | 归属 | 原因 |
 * | --- | --- | --- |
 * | 检索分页与当前命中 | 页面局部 | 换一个检索条件就该丢弃，没有跨页价值 |
 * | 当前选中的文档 id | 页面局部 | 详情页自己的状态，Chat 页不关心 |
 * | 当前 tab | 页面局部 | 纯 UI 状态 |
 * | 问答草稿 | 页面局部 | 离开页面就该丢掉 |
 *
 * 判别式：**"换一个页面还该不该看见它"**。不该，就留在 View。
 */
export const useKnowledgeStore = defineStore('knowledge', () => {
  /* ---------------- 列表 ---------------- */

  const list = ref<KnowledgeBase[]>([])
  const loading = ref(false)
  const error = ref<string>('')

  /**
   * 已拉取过没有。
   *
   * 存在的理由：`loading === false` 有两种含义——"没拉过"与"拉完了"。
   * 只有前者才该去发请求。用一个布尔量把两者分开，
   * 免得每次进页面都重拉一遍（那正是要消灭的重复）。
   */
  const loaded = ref(false)

  async function fetchList(force = false): Promise<void> {
    if (loading.value) return
    if (loaded.value && !force) return
    loading.value = true
    error.value = ''
    try {
      // 响应拦截器已把 {code,message,data} 解包，这里拿到的就是业务数据本身
      const res = await listKnowledgeBases()
      list.value = res.list
      loaded.value = true
    } catch (e) {
      // 保留服务端原话：它是判断"是没权限还是网络断了"的唯一依据
      error.value = e instanceof Error ? e.message : '知识库加载失败'
    } finally {
      loading.value = false
    }
  }

  /** 新建后**就地插入**而不是让调用方去 reload——省一次往返，也避免列表闪一下。 */
  async function create(name: string, description?: string): Promise<KnowledgeBase> {
    const created = await createKnowledgeBase(name, description)
    list.value = [created, ...list.value]
    return created
  }

  async function remove(id: string): Promise<void> {
    await deleteKnowledgeBase(id)
    list.value = list.value.filter((kb) => kb.id !== id)
    detailById.value.delete(id)
    documentsByKbId.value.delete(id)
  }

  /* ---------------- 详情 ---------------- */

  const detailById = ref(new Map<string, KnowledgeBase>())

  async function fetchDetail(id: string): Promise<KnowledgeBase | null> {
    // 详情接口目前没有独立的 GET，列表数据就是详情所需的一切。
    // 这里刻意**不新造一个接口**，只从列表里取——多造一个端点不是本阶段的事。
    const cached = detailById.value.get(id)
    if (cached) return cached
    if (!loaded.value) await fetchList()
    const found = list.value.find((kb) => kb.id === id) ?? null
    if (found) detailById.value.set(id, found)
    return found
  }

  /* ---------------- 文档 ---------------- */

  const documentsByKbId = ref(new Map<string, DocumentSummary[]>())

  async function fetchDocuments(kbId: string): Promise<DocumentSummary[]> {
    const res = await listDocuments(kbId)
    documentsByKbId.value.set(kbId, res.list)
    return res.list
  }

  async function removeDocument(kbId: string, documentId: string): Promise<void> {
    await deleteDocument(kbId, documentId)
    const docs = documentsByKbId.value.get(kbId)
    if (docs) {
      documentsByKbId.value.set(
        kbId,
        docs.filter((d) => d.id !== documentId)
      )
    }
    // 详情里的"文档数"会因此少一个，就地改掉，免得等下次 reload 才显示正确
    const detail = detailById.value.get(kbId)
    if (detail) detail.docCount = Math.max(0, detail.docCount - 1)
  }

  /**
   * 单篇文档详情。
   *
   * 刻意**不进 Map 缓存**：文档正文是全文，几十篇就能把内存撑起来，
   * 而它的生命周期只服务"当前打开的这一篇"。要用就现拉。
   */
  async function fetchDocument(kbId: string, documentId: string): Promise<DocumentDetail> {
    return getDocument(kbId, documentId)
  }

  /* ---------------- 派生 ---------------- */

  /** 按 id 取一个知识库，先查详情缓存再查列表。 */
  function byId(id: string): KnowledgeBase | undefined {
    return detailById.value.get(id) ?? list.value.find((kb) => kb.id === id)
  }

  const isEmpty = computed(() => loaded.value && list.value.length === 0)

  /** 退出登录时清空：这些是**上一个账号**的数据，不能留给下一个。 */
  function reset(): void {
    list.value = []
    loaded.value = false
    error.value = ''
    detailById.value.clear()
    documentsByKbId.value.clear()
  }

  return {
    list,
    loading,
    error,
    loaded,
    detailById,
    documentsByKbId,
    isEmpty,
    fetchList,
    create,
    remove,
    fetchDetail,
    fetchDocuments,
    removeDocument,
    fetchDocument,
    byId,
    reset
  }
})
