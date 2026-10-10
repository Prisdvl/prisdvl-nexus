/**
 * R4b：SSE 解析器与类型守卫验证。
 *
 * **零依赖、零额度、可重复**——用构造的字节序列测纯函数解析器，
 * 不开真实流、不需要后端、不消耗 LLM 额度。
 *
 * 测的是"协议解析对不对"，不是"后端发得对不对"。后者由 R8 的 E2E 负责。
 */
import { createSseParser, dispatchFrame } from '../src/lib/sse.ts'
import { isChatDone, isRagDone, isChatError } from '../src/api/types/sse.ts'

const pass = []
const fail = []

function check(ok, label, detail = '') {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ' — ' + detail : ''}`)
  ;(ok ? pass : fail).push(label + (detail ? ` (${detail})` : ''))
}

console.log('① 基本帧解析')
{
  const p = createSseParser()
  const frames = p.push('event: delta\ndata: {"text":"你好"}\n\n')
  check(frames.length === 1, '单帧被解析出来', `${frames.length} 帧`)
  check(frames[0]?.event === 'delta', 'event 名正确', frames[0]?.event)
  check(frames[0]?.data?.text === '你好', 'data 内容正确（含中文）', JSON.stringify(frames[0]?.data))
}

console.log('② 帧被 TCP 分片切开')
{
  const p = createSseParser()
  // 第一块只到 data 行的一半
  const a = p.push('event: delta\ndata: {"text":"Hel')
  const b = p.push('lo"}\n\n')
  check(a.length === 0, '不完整的第一块不产出帧', `${a.length} 帧`)
  check(b.length === 1, '补齐后产出 1 帧', `${b.length} 帧`)
  check(b[0]?.data?.text === 'Hello', '分片后内容完整', JSON.stringify(b[0]?.data))
}

console.log('③ 多帧在一次 push 里（跨块切分）')
{
  const p = createSseParser()
  const frames = p.push(
    'event: meta\ndata: {"requestId":"r1"}\n\nevent: delta\ndata: {"text":"A"}\n\nevent: done\ndata: {"finishReason":"stop"}\n\n'
  )
  check(frames.length === 3, '一次收到 3 帧', `${frames.length} 帧`)
  check(
    frames.map((f) => f.event).join(',') === 'meta,delta,done',
    '事件顺序与名字正确',
    frames.map((f) => f.event).join(',')
  )
}

console.log('④ 心跳 / 非 JSON 被忽略')
{
  const p = createSseParser()
  const frames = p.push(
    ': ping\n\n' + 'event: delta\ndata: {"text":"X"}\n\n' + 'data: not-json\n\n'
  )
  check(frames.length === 1, '心跳与坏 JSON 都被忽略', `${frames.length} 帧`)
  check(frames[0]?.data?.text === 'X', '正常帧未受影响', JSON.stringify(frames[0]?.data))
}

console.log('⑤ CRLF 换行')
{
  const p = createSseParser()
  const frames = p.push('event: delta\r\ndata: {"text":"CR"}\r\n\r\n')
  check(frames.length === 1, 'CRLF 帧被解析', `${frames.length} 帧`)
  check(frames[0]?.data?.text === 'CR', 'CRLF 下内容正确', JSON.stringify(frames[0]?.data))
}

console.log('⑥ 省略 event: 时沿用上一个')
{
  const p = createSseParser()
  const frames = p.push('event: delta\ndata: {"text":"1"}\n\n' + 'data: {"text":"2"}\n\n')
  check(frames.length === 2, '两帧都解析出来', `${frames.length} 帧`)
  check(
    frames[1]?.event === 'delta',
    '第二帧省略 event: 后沿用上一个',
    frames[1]?.event
  )
}

console.log('⑦ 流末尾的不完整帧（flush）')
{
  const p = createSseParser()
  p.push('event: done\ndata: {"finishReason":"stop"}\n') // 没有结尾空行
  const tail = p.flush()
  check(tail.length === 1, 'flush 取出末尾帧', `${tail.length} 帧`)
  check(tail[0]?.event === 'done', 'flush 帧的事件名正确', tail[0]?.event)
}

console.log('⑧ 中文多字节不被切坏')
{
  const p = createSseParser()
  const full = 'event: delta\ndata: {"text":"缓存击穿与线程池"}\n\n'
  // 在一个中文字符中间切开（"缓"占 3 字节）
  const cut = full.indexOf('缓') + 1
  const a = p.push(full.slice(0, cut))
  const b = p.push(full.slice(cut))
  const all = [...a, ...b]
  check(all.length === 1, '切开后合成 1 帧', `${all.length} 帧`)
  check(all[0]?.data?.text === '缓存击穿与线程池', '中文内容未乱码', JSON.stringify(all[0]?.data))
}

console.log('⑨ 两条链路的 done 字段不同（类型守卫）')
{
  const chatDone = { requestId: 'r', finishReason: 'stop', stopped: true, citations: [], resultSummary: null, firstTokenMs: 12, latencyMs: 300, tool: null }
  const ragDone = { requestId: 'r', finishReason: 'stop', stop: true, citations: [], recordId: 7, empty: false }
  check(isChatDone(chatDone) === true, 'Chat 的 done（stopped）判定为 chat')
  check(isRagDone(chatDone) === false, 'Chat 的 done 不被误判为 rag')
  check(isRagDone(ragDone) === true, 'RAG 的 done（stop）判定为 rag')
  check(isChatDone(ragDone) === false, 'RAG 的 done 不被误判为 chat')
}

console.log('⑩ 两条链路的 error 字段不同')
{
  const chatErr = { requestId: 'r', code: 4006, message: '模型未注册', retryable: false }
  const ragErr = { requestId: 'r', errorCode: 8001, error: '会话不存在', retryable: false }
  check(isChatError(chatErr) === true, 'Chat 的 error（code）判定为 chat')
  check(isChatError(ragErr) === false, "RAG 的 error（errorCode）不被误判为 chat")
}

console.log('⑪ dispatchFrame 分派')
{
  const seen = []
  const handled = dispatchFrame(
    { event: 'delta', data: { text: 'A' } },
    { delta: (d) => seen.push(d) }
  )
  check(handled === true, '已注册的事件被分派', String(handled))
  check(seen.length === 1 && seen[0].text === 'A', '分派带对了数据')
  const missed = dispatchFrame({ event: 'unknown_future', data: {} }, { delta: () => {} })
  check(missed === false, '未注册的事件返回 false（不崩）', String(missed))
}

console.log('\n结论：PASS ' + pass.length + ' / FAIL ' + fail.length)
if (fail.length) {
  console.log('失败项：')
  for (const f of fail) console.log('  - ' + f)
  process.exit(1)
}
console.log('PASS R4b：SSE 解析器（分片 / 心跳 / CRLF / 中文 / flush / 事件名沿用）+ 两条链路类型守卫')
