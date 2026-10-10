/**
 * E2E 公共会话与地址工具（`verify-*.mjs` 共用）。
 *
 * 解决两类"看起来像脚本坏了、其实是环境口径"的坑：
 *
 * 1. **地址口径不统一**。此前三个脚本对 `API_BASE` 的约定各不相同：有的期望它**含** `/api`、
 *    有的期望**不含**、默认端口一个 8088 一个 8080。同一个覆盖值于是在一个脚本里对、
 *    在另一个里必然 `fetch failed`，而报错完全指向不了真正的原因。
 *    口径的唯一定义已上移到 `./e2e-config.mjs`（§43），本文件只做重新导出。
 *
 * 2. **令牌寿命**。`access-token-ttl` 只有 30 分钟，而 `.demo-token` 是一份**外部预置文件**：
 *    它一过期，脚本的表现是"跑着跑着莫名超时/401"，与"脚本坏了"无法区分。
 *    {@link demoToken} 改成每次**现登录换一个新令牌**，登录失败才回落到文件——
 *    自愈优先，但不把外部状态变成硬依赖。
 *
 * 3. **往保护账号里漏数据**。用 demo 令牌跑的脚本会在 **demo 名下**建会话，而 demo 是
 *    **禁止清理**的演示账号：漏下的会话 `cleanup-test-data` 永远不会回收（它被保护规则挡在
 *    门外），于是"每跑一轮 E2E，保护账号就胖一圈"——这正好违反任务书 §十四
 *    「重复执行不会无限污染数据库」。{@link listConversationIds} +
 *    {@link deleteConversationsCreatedSince} 用**跑前快照 + 差集删除**解决：
 *    只删本轮新建的，demo 原有的一条不动。
 */
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { API_ROOT, WEB_ROOT, apiUrl } from './e2e-config.mjs'

// 地址口径的唯一定义在 ./e2e-config.mjs；这里重新导出，
// 免得每个脚本都要知道"该从哪个文件拿地址"。新脚本请直接从 e2e-config.mjs 导入。
export { API_ROOT, WEB_ROOT, apiUrl }

/**
 * 取一个**新鲜**的 demo 令牌：先尝试登录，失败再回落到 `web/.demo-token`。
 *
 * <p>回落路径按**本文件的位置**解析（`web/scripts/lib/` → `web/.demo-token`），
 * 因此调用方不需要自己传目录，也不会随 cwd 漂移。
 */
export async function demoToken() {
  const username = process.env.DEMO_USER ?? 'demo'
  const password = process.env.DEMO_PASSWORD ?? 'Demo@12345'
  try {
    const response = await fetch(apiUrl('/auth/login'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    })
    if (response.ok) {
      const token = (await response.json())?.data?.accessToken
      if (token) {
        console.log(`[session] 已用 ${username} 现登录换取新令牌（不再依赖 .demo-token 的剩余寿命）`)
        return token
      }
    }
    console.warn(`[session] 登录换取令牌失败（HTTP ${response.status}），回落到 .demo-token`)
  } catch (error) {
    console.warn(`[session] 登录换取令牌异常（${error.message}），回落到 .demo-token`)
  }
  const fallback = resolve(dirname(fileURLToPath(import.meta.url)), '../../.demo-token')
  return readFileSync(fallback, 'utf8').trim()
}

/**
 * 列出某账号**当前全部**会话 id（分页取完，返回 `Set<string>`）。
 *
 * <p>用于"跑测试之前先给账号拍一张会话快照"，见 {@link deleteConversationsCreatedSince}。
 * 分页字段名在不同实现里可能是 `records` / `list` / `items`，这里逐个回落，
 * 任一页失败即停止分页（返回已拿到的部分），绝不把分页异常升级成脚本失败。
 */
export async function listConversationIds(token, apiRoot = API_ROOT) {
  const ids = new Set()
  const pageSize = 100
  for (let pageNum = 1; pageNum <= 50; pageNum += 1) {
    let body = null
    try {
      const response = await fetch(
        `${apiRoot}/api/chat/conversations?pageNum=${pageNum}&pageSize=${pageSize}`,
        { headers: { Authorization: `Bearer ${token}` } },
      )
      if (!response.ok) break
      body = await response.json()
    } catch {
      break
    }
    const data = body?.data ?? {}
    const records = data.records ?? data.list ?? data.items ?? (Array.isArray(data) ? data : [])
    if (!Array.isArray(records) || records.length === 0) break
    for (const record of records) {
      if (record?.id !== undefined && record?.id !== null) ids.add(String(record.id))
    }
    if (records.length < pageSize) break
  }
  return ids
}

/**
 * 只删掉「跑测试期间**新建**」的会话，保留快照里已有的一切。
 *
 * <p>为什么必须是差集、不能"删掉本账号全部会话"：本工具最主要的调用方是
 * `demo` 这类**演示账号**——它名下有真实演示数据。用"清空全部会话"收尾等于
 * 把演示数据一起删掉，这比残留本身更严重。
 *
 * <p>写法约定：**只统计、只告警，不抛异常**。收尾失败不该改变验收结论，
 * 但必须打印出来让人看得见（静默失败会让下一个人以为已经清干净了）。
 *
 * @returns 实际尝试删除的会话数（含删除失败的）
 */
export async function deleteConversationsCreatedSince(token, before, apiRoot = API_ROOT) {
  const after = await listConversationIds(token, apiRoot)
  const created = [...after].filter((id) => !before.has(id))
  for (const id of created) {
    try {
      const response = await fetch(`${apiRoot}/api/chat/conversations/${id}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${token}` },
      })
      console.log(`· 清理本轮新建会话 ${id}：HTTP ${response.status}`)
    } catch (error) {
      console.warn(`· 清理本轮新建会话 ${id} 失败：${error.message}`)
    }
  }
  console.log(`· 会话收尾：本轮新建 ${created.length} 个，已全部请求删除（快照内 ${before.size} 个原样保留）`)
  return created.length
}
