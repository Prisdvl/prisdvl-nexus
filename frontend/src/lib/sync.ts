/**
 * 会话列表同步：登录后从后端拉一次列表，写进 store。
 *
 * ## 为什么单独一个模块，而不是直接在 LoginRoute 里调
 *
 * 拉列表这件事有三个容易被忽略的分支，收在一起比散在调用点好维护：
 *   1. **mock 模式**：不调后端，保持种子数据（门禁靠这个跑）。
 *   2. **失败**：不抛、不阻断导航 —— 拿不到列表不该让用户卡在登录页。
 *   3. **合并**：`mergeFromServer` 要保留本地临时会话与已加载的消息（见sessions.ts）。
 *
 * ## 时机
 *
 * 现在是"登录成功后拉一次"。刷新页面时靠 `localStorage` 快照兜底
 * （`STORE_VERSION=2`），下次进应用再拉一次 —— 这对个人项目够用，
 * 不需要额外的"启动即同步"逻辑。
 */
import { api, USE_MOCK, errMessage } from '@/lib/api'
import { toSessionItems } from '@/lib/conversations'
import { sessionStore } from '@/lib/sessions'
import { toast } from '@/lib/toast'

let syncing = false

/**
 * 从后端拉会话列表并合并进 store。
 *
 * @returns 是否真的拉了。mock 模式返回 false（没发请求）。
 */
export async function syncConversations(): Promise<boolean> {
  if (USE_MOCK) return false
  if (syncing) return false
  syncing = true
  try {
    const page = await api.conversations.list(1, 100)
    sessionStore.mergeFromServer(toSessionItems(page.list))
    return true
  } catch (e) {
    /*
     * 刻意只提示不抛：登录已经成功了，为了一份侧栏列表把用户堵在登录页
     * 是本末倒置。常见原因是后端没启动或网络不通，`errMessage` 会给出
     * 「连不上后端服务，请确认 8080 端口的进程已启动」这种可操作的提示。
     */
    toast.error('会话列表加载失败', errMessage(e))
    return false
  } finally {
    syncing = false
  }
}