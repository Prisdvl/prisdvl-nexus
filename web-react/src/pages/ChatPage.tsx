import { useCallback, useEffect, useRef, useState, type ReactElement } from 'react'
import { StateEmpty } from '@/components/common/StateEmpty'
import { StateError } from '@/components/common/StateError'
import { StateLoading } from '@/components/common/StateLoading'
import { CitationList } from '@/components/chat/CitationList'
import { MessageBubble } from '@/components/chat/MessageBubble'
import { KbBindingBar } from '@/components/chat/KbBindingBar'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/form'
import {
  createConversation,
  deleteConversation,
  listConversations,
  listMessages
} from '@/api/chat'
import { listKnowledgeBases } from '@/api/knowledge'
import type { ChatMessage, Conversation } from '@/api/types/chat'
import { useAsyncState } from '@/hooks/useAsyncState'
import { useChatStream } from '@/hooks/useChatStream'
import { useConfirm } from '@/hooks/useConfirm'

/**
 * 对话页（`/agent`）—— R8。
 *
 * ## 布局（任务书 §16）
 * ```text
 * 会话列表（240px） │ 消息流（自适应）│ 输入区（底部）
 *                              │ 引用（右侧，可收起）
 * ```
 * 会话列表在 `<1024` 时隐藏（用侧栏抽屉替代，那是 R2 已有的能力，不重复实现）。
 *
 * ## 为什么发消息前先确保有会话
 *
 * 后端 `StreamChatRequest.conversationId` 是 `@NotNull`——**没有会话就没法提问**。
 * 而"没有会话"是正常状态（新用户），不是错误。所以首次发消息时若没有会话，
 * 先 `POST /chat/conversations` 建一个。这比让用户先手动点"新建会话"少一步，
 * 也不算自动创建垃圾数据（只有真的发了消息才建）。
 */
/** sessionStorage 的键。放模块级，避免每次渲染新建字符串。 */
const LAST_CONVO_KEY = 'nexus.chat.lastConversation'

export function ChatPage(): ReactElement {
  const confirm = useConfirm()
  const stream = useChatStream()

  /*
   * 当前会话**持久化到 sessionStorage**，刷新后自动恢复。
   *
   * ## 为什么需要（R8 遗留的那条 FAIL）
   * 刷新后 URL 是 `/#/agent`，`activeId` 丢失 → 界面回到"开始提问"空态，
   * 用户必须重新在左侧点一次会话。消息明明在，��像"丢了"。
   *
   * ## 为什么用 sessionStorage 而不是 localStorage
   * 刷新要保留（同一标签页内），但**关掉标签页就不该残留**——
   * 下次进来应该是干净的"开始提问"，而不是莫名其妙停在上次的对话上。
   *
   * ## 读值是**惰性初始化**（useState 的第二个参数）
   * 不能放 useEffect 里——那会先渲染一帧 `null` 再跳到真实 id，
   * 造成"闪一下空态"。惰性初始化第一帧就是对的。
   */
  const [activeId, setActiveId] = useState<string | null>(
    () => sessionStorage.getItem(LAST_CONVO_KEY) ?? null
  )
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [sendError, setSendError] = useState<string | null>(null)

  /** 切换当前会话：改 state + 落 sessionStorage（刷新后能恢复）。 */
  const selectConversation = useCallback((id: string | null) => {
    setActiveId(id)
    if (id) sessionStorage.setItem(LAST_CONVO_KEY, id)
    else sessionStorage.removeItem(LAST_CONVO_KEY)
  }, [])
  /*
   * 乐观消息**独立于 msgs.data**，且**自描述它属于哪个会话**。
   *
   * ## 为什么不塞进 msgs.data
   * `useAsyncState` 在请求返回时**无条件** `setData(result)`，而消息列表请求会在
   * 流式生成**期间**完成——那时后端还没落库，返回空列表，于是刚插入的乐观消息被
   * **静默覆盖**。E2E 实测：点了发送，界面上什么都不出现。
   *
   * ## 为什么要带 `forId`（R8 反复失败的那一项）
   *
   * 前两版都在**试图让 effect 判断"该不该清"**：
   *   - `skipNextClear` 标记位 → effect 跑两次就失效
   *   - `prevIdRef.current !== activeId` → 依赖 effect 的执行顺序，仍不稳
   *
   * 根因是**这些方案都依赖 effect 的执行次数，而 React 不保证这一点**。
   *
   * 正确做法：**让状态自己说明它属于谁**，渲染时判断，**完全不用 effect 同步**：
   * ```text
   * null → cid   首次发送自动建会话   forId === activeId  → 显示 ✓
   * cid1 → cid2  切到别的对话         forId !== activeId  → 隐藏 ✓
   * cid1 → null  删掉当前会话         forId !== null      → 隐藏 ✓
   * ```
   * 无论 effect 跑几次、顺序如何，结果都一样。
   */
  const [pendingUserMsg, setPendingUserMsg] = useState<{
    msg: ChatMessage
    forId: string
  } | null>(null)
  const bottomRef = useRef<HTMLDivElement>(null)

  /* ---- 会话列表 ---- */
  const convos = useAsyncState((signal) => listConversations({ pageSize: 50 }, signal), [])

  /* ---- 当前会话的消息 ---- */
  const msgs = useAsyncState(
    (signal) => listMessages(activeId as string, { pageSize: 100 }, signal),
    [activeId],
    { immediate: activeId !== null }
  )

  // 新消息 / 流式文本增长时滚到底
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [msgs.data?.list.length, stream.streamingText])

  /*
   * 切换会话时**清空流式状态**。
   * 否则上一个会话的半截回答会挂在新会话上——**R8 最容易犯的错**。
   * 用 ref 拿最新的 reset，避免把它放进依赖导致每次渲染都重置。
   */
  const resetRef = useRef(stream.reset)
  resetRef.current = stream.reset
  /*
   * 清空乐观消息的**判据是"换成了另一个已有会话"**，不是"activeId 变了"。
   *
   * ```text
   * null  → cid   首次发送，自动建会话      → **不清**（乐观消息正要显示）
   * cid1  → cid2  切换到别的对话           → 清
   * cid1  → null  删掉当前会话             → 清
   * ```
   *
   * ## 为什么不能用"标记位"跳过（第一版的错误）
   *
   * 我先写的是 `skipNextClear.current = true` + effect 里消费它。
   * 看起来对，但**间歇性失效**（E2E 同一份代码一次 PASS 一次 FAIL）——
   * 因为 effect 可能因 StrictMode 或依赖抖动跑两次，标记被第一次消费掉，
   * 第二次就把刚上屏的消息清了。
   *
   * **标记位依赖"只跑一次"的假设，而 React 不保证这一点。**
   * 改成比较 `prevIdRef.current !== activeId`（值比较，不依赖执行次数），
   * 无论 effect 跑几次结果都一样。
   */
  useEffect(() => {
    /*
     * ## 这里为什么**不能**写成 `resetStream()` on [activeId]
     *
     * 那个写法看起来最自然，但它是 R8 反复失败的**真正根因**：
     * ```text
     * onSend: setActiveId(cid)            → 触发本 effect
     *         stream.start(cid, content)  → 发出 POST /api/chat/stream
     * effect: reset() → ctrl.abort()      → **把刚发出的请求打断**
     * ```
     * 探针实测：`[REQFAIL] POST /api/chat/stream :: net::ERR_ABORTED`。
     * `streaming` 刚被置 true 又被置 false，界面上什么都留不下。
     *
     * 更早的两版（`skipNextClear` 标记位、`prevIdRef` 值比较）都是**在给这个 effect
     * 打补丁**——方向就错了：**副作用该跟着"用户动作"走，不该靠 effect 猜测时机。**
     *
     * 现在改成：在每个**明确的用户动作**里显式 `stream.reset()`
     * （见会话列表的 onClick 与删除后的分支）。
     */
  }, [activeId])

  const onSend = useCallback(async () => {
    const content = input.trim()
    if (!content || sending || stream.streaming) return

    setSending(true)
    setSendError(null)
    try {
      // ① 没有会话就先建一个（后端 conversationId 是 @NotNull）
      let cid = activeId
      if (!cid) {
        /*
         * RAG 会话**必须绑定知识库**才能提问，否则后端返回 400：
         * ```json
         * { "code": 1001, "message": "该会话（RAG 模式）尚未绑定知识库，请先绑定后再提问" }
         * ```
         * 这是产品约束（RAG 的前提是"有资料可检索"），不是 bug。
         *
         * 这里**默认绑定用户的所有知识库**——与 Vue 侧行为一致，
         * 且比让用户先手动挑一遍更少一步。将来要做"每个会话选不同库"时，
         * 把这里换成选择器即可。
         */
        const kbs = await listKnowledgeBases({ pageSize: 100 })
        const kbIds = kbs.list.map((k) => k.id)
        if (kbIds.length === 0) {
          setSendError('还没有知识库。先去知识库页导入一些资料，才能开始提问。')
          return
        }
        const created = await createConversation({ mode: 'RAG', kbIds })
        cid = created.id
        selectConversation(cid)
      }

      // ② 乐观插入用户消息：不等接口返回就上屏，体感快一帧。
      //    id 用 `pending-` 前缀标识"还没落库"，避免与真实 id 混淆。
      setInput('')
      const optimistic: ChatMessage = {
        id: `pending-${Date.now()}`,
        role: 'USER',
        agentId: null,
        content,
        model: null,
        promptTokens: null,
        completionTokens: null,
        totalTokens: null,
        latencyMs: null,
        firstTokenMs: null,
        status: null,
        errorMsg: null,
        createdAt: null,
        toolCalls: [],
        citations: []
      }
      /*
       * 乐观插入。**`prev` 可能是 null**——刚 `setActiveId(cid)` 时消息列表
       * 还没加载完（`useAsyncState` 仍是 idle/loading）。第一版写的是
       * `prev ? {...} : prev`，于是**首条消息的乐观插入被静默丢弃**，
       * 用户看到"点了发送但什么都没出现"。E2E 实测抓到了这一条。
       */
      setPendingUserMsg({ msg: optimistic, forId: cid })

      // ③ 流式回答
      await stream.start(cid, content)

      // ④ 重新拉消息：拿后端落库的完整记录（含引用），然后清掉乐观消息
      await msgs.retry()
      setPendingUserMsg(null)
      await convos.retry()
    } catch (e) {
      /*
       * code 1001 = 会话没绑知识库。上面已经预防了，这里兜底给一句能行动的提示，
       * 而不是把后端原话直接甩给用户。
       */
      const msg = e instanceof Error ? e.message : '发送失败'
      setSendError(
        msg.includes('尚未绑定知识库')
          ? '这个对话还没有绑定知识库。请新建一个对话，或先导入资料。'
          : msg
      )
    } finally {
      setSending(false)
    }
  }, [activeId, input, sending, stream, msgs, convos, selectConversation])

  const onDelete = async (c: Conversation) => {
    const ok = await confirm({
      title: `删除对话「${c.title ?? '未命名'}」？`,
      description: '这条对话里的所有消息都会被删除，不可撤销。',
      confirmText: '删除',
      danger: true
    })
    if (!ok) return
    await deleteConversation(c.id)
    if (activeId === c.id) {
      stream.reset() // 删掉当前会话：显式停掉生成
      selectConversation(null)
    }
    await convos.retry()
  }

  const list = convos.data?.list ?? []
  const messages = msgs.data?.list ?? []
  /** 当前会话对象（绑定条要读它的 kbIds/mode；会话列表是唯一权威来源，不另存一份） */
  const activeConvo = list.find((c) => c.id === activeId) ?? null
  // 只有"乐观消息所属会话 == 当前会话"时才显示（见状态声明处的说明）
  const visiblePending =
    pendingUserMsg && pendingUserMsg.forId === activeId ? pendingUserMsg.msg : null
  const allMessages = visiblePending ? [...messages, visiblePending] : messages

  return (
    <div className="flex h-full min-h-0 min-w-0" data-testid="chat-page">
      {/* ---- 会话列表 ---- */}
      <aside
        className="hidden w-60 shrink-0 flex-col border-r border-[var(--nx-color-border)] lg:flex"
        data-testid="chat-conversations"
      >
        <div className="border-b border-[var(--nx-color-border)] p-3">
          <h2 className="m-0 text-[var(--nx-text-sm)] font-[var(--nx-weight-medium)]">对话</h2>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto p-2">
          {convos.state === 'loading' ? (
            <StateLoading variant="list" rows={3} className="p-0" />
          ) : null}
          {convos.state === 'error' ? (
            <StateError title="对话列表加载失败" onRetry={() => void convos.retry()} />
          ) : null}
          {convos.state === 'success' && list.length === 0 ? (
            <p className="m-0 px-2 py-4 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
              还没有对话。直接提问就会自动开一个。
            </p>
          ) : null}
          {list.map((c) => (
            <div
              key={c.id}
              data-testid="chat-conversation-item"
              data-active={c.id === activeId}
              className={
                'group mb-1 flex items-center gap-1 rounded-[var(--nx-radius-md)] px-2 py-1.5 ' +
                (c.id === activeId
                  ? 'bg-[var(--nx-color-primary-container)]'
                  : 'hover:bg-[var(--nx-color-surface-muted)]')
              }
            >
              <button
                type="button"
                onClick={() => {
                  // 切换会话：显式停掉进行中的生成（**不靠 effect**——见上方说明）
                  stream.reset()
                  selectConversation(c.id)
                }}
                className="min-w-0 flex-1 truncate text-left text-[var(--nx-text-sm]"
                data-testid="chat-conversation-open"
              >
                {c.title ?? '未命名对话'}
              </button>
              <button
                type="button"
                onClick={() => void onDelete(c)}
                aria-label="删除对话"
                data-testid="chat-conversation-delete"
                className="shrink-0 opacity-0 transition-opacity group-hover:opacity-100"
              >
                ×
              </button>
            </div>
          ))}
        </div>
      </aside>

      {/* ---- 消息流 ---- */}
      <div className="flex min-w-0 flex-1 flex-col">
        {/*
          知识库绑定条：RAG 会话的**数据来源**在这里可改。
          为什么要露在消息流上方而不是塞进设置弹窗：
          "这次检索了哪些库"直接决定回答的依据范围，用户问出不满意的答案时，
          第一反应就是"我是不是绑错库了"——那就得一眼能看见、一下就改掉。
        */}
        <KbBindingBar
          conversation={activeConvo}
          onChanged={() => {
            void convos.retry()
          }}
        />

        <div className="min-h-0 flex-1 overflow-y-auto p-4" data-testid="chat-messages">
          {msgs.state === 'error' ? (
            <StateError
              title="消息加载失败"
              {...(msgs.error ? { description: msgs.error } : {})}
              onRetry={() => void msgs.retry()}
            />
          ) : null}

          {activeId === null ? (
            <StateEmpty title="开始提问" description="问一个问题，我会基于你的知识库回答。" />
          ) : null}

          {activeId !== null && msgs.state === 'loading' ? (
            <StateLoading variant="list" rows={3} className="p-0" />
          ) : null}

          {activeId !== null && msgs.state === 'success' && allMessages.length === 0 ? (
            <StateEmpty title="还没有消息" description="在下面提问吧。" />
          ) : null}

          {allMessages.map((m) => (
            <MessageBubble key={m.id} message={m} />
          ))}

          {/* 流式中的回答：作为一条"正在生成"的消息显示 */}
          {stream.streaming || stream.streamingText ? (
            <MessageBubble
              message={{
                id: stream.assistantMessageId ?? '__streaming__',
                role: 'ASSISTANT',
                agentId: null,
                content: stream.streamingText,
                model: null,
                promptTokens: null,
                completionTokens: null,
                totalTokens: null,
                latencyMs: null,
                firstTokenMs: null,
                status: 'GENERATING',
                errorMsg: null,
                createdAt: null,
                /*
                 * 形状转换：SSE 的 `StreamToolCall` 与消息记录里的 `MessageToolCall`
                 * **字段不同**（前者 `toolCode`/`toolName`，后者 `tool`）。
                 * 它们是两个后端 DTO，不能直接塞——R8 踩过一次类型错误，
                 * 这里显式映射而不是 `as any` 糊过去。
                 */
                toolCalls: stream.toolCalls.map((t) => ({
                  tool: t.toolName || t.toolCode,
                  input: typeof t.input === 'string' ? t.input : JSON.stringify(t.input),
                  resultSummary: null
                })),
                citations: stream.citations
              }}
              streaming
            />
          ) : null}

          {stream.error ? (
            <p
              role="alert"
              data-testid="chat-stream-error"
              className="m-0 mt-2 rounded-[var(--nx-radius-md)] bg-[var(--nx-color-danger-container)] px-3 py-2 text-[var(--nx-text-sm)] text-[var(--nx-color-danger)]"
            >
              {stream.error}
            </p>
          ) : null}

          {/*
            RAG 0 命中的提示（任务书 §28）。
            后端在 0 命中时走"无检索上下文"路径而不是硬答，
            但用户**必须知道为什么这次没引用资料**——否则他会以为引用坏了。
          */}
          {stream.retrieval && stream.retrieval.count === 0 ? (
            <p
              data-testid="chat-zero-hits"
              className="m-0 mt-2 rounded-[var(--nx-radius-md)] bg-[var(--nx-color-surface-muted)] px-3 py-2 text-[var(--nx-text-sm)] text-[var(--nx-color-text-secondary)]"
            >
              这次没有从知识库里检索到相关内容，下面是通用回答。
              {stream.retrieval.failedKnowledgeBases.length > 0
                ? `（${stream.retrieval.failedKnowledgeBases
                    .map((f) => f.knowledgeBaseName ?? f.knowledgeBaseId)
                    .join('、')} 检索失败）`
                : ''}
            </p>
          ) : null}

          <div ref={bottomRef} />
        </div>

        {/* ---- 输入区 ---- */}
        <div className="border-t border-[var(--nx-color-border)] p-3">
          {sendError ? (
            <p
              role="alert"
              className="mb-2 text-[var(--nx-text-sm)] text-[var(--nx-color-danger)]"
            >
              {sendError}
            </p>
          ) : null}
          <div className="flex items-end gap-2">
            <Textarea
              rows={2}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="问点什么…"
              data-testid="chat-input"
              // 8000 是后端 @Size 上限；提前拦，不让用户白等一次 400
              maxLength={8000}
              className="flex-1"
            />
            {stream.streaming ? (
              <Button variant="outline" onClick={() => void stream.stop()} data-testid="chat-stop">
                停止
              </Button>
            ) : (
              <Button
                onClick={() => void onSend()}
                loading={sending}
                disabled={!input.trim()}
                data-testid="chat-send"
              >
                发送
              </Button>
            )}
          </div>
        </div>
      </div>

      {/* ---- 引用（右侧栏，仅在有引用时出现）---- */}
      {stream.citations.length > 0 ? <CitationList citations={stream.citations} /> : null}
    </div>
  )
}
