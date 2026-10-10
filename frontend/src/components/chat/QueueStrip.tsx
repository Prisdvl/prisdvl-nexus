import { ListOrdered, X } from 'lucide-react'
import '@/styles/queue.css'

interface Props {
  /** 排队中的文本（按先后顺序） */
  items: string[]
  onRemove: (index: number) => void
  onClear: () => void
}

/**
 * 待发送队列（P1-4）。
 *
 * 为什么要有它：Agent 回答动辄几十秒，用户在这期间想到第二问是很正常的。
 * 之前 busy 时按回车只是弹个提示（消息被丢掉），用户得记住"等它完了我还要再问一遍"。
 * 现在消息**收进队列**：能看见、能删、当前这轮结束后自动接着发。
 *
 * 取舍：不做"多任务并行"（同一会话并行回答会让上下文互相污染），
 * 顺序队列才是符合人类对话直觉的模型。
 */
export default function QueueStrip({ items, onRemove, onClear }: Props) {
  if (items.length === 0) return null

  return (
    <div className="queue" role="status" aria-label={`待发送队列，共 ${items.length} 条`}>
      <span className="queue__head">
        <ListOrdered size={13} aria-hidden="true" />
        排队中 {items.length}
      </span>
      <ul className="queue__list">
        {items.map((text, i) => (
          <li className="queue__item" key={`${i}-${text.slice(0, 8)}`}>
            <span className="queue__idx">{i + 1}</span>
            <span className="queue__text" title={text}>{text}</span>
            <button className="queue__del" type="button" aria-label={`移除排队第 ${i + 1} 条`} onClick={() => onRemove(i)}>
              <X size={12} />
            </button>
          </li>
        ))}
      </ul>
      <button className="queue__clear" type="button" onClick={onClear}>清空</button>
    </div>
  )
}
