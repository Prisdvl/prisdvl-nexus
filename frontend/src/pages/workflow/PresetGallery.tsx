import { useEffect, useMemo, useRef } from 'react'
import { ArrowRight, X } from 'lucide-react'
import type { WorkflowPreset } from '@/lib/api'
import {
  PRESET_CATEGORY_ORDER, presetDescOf, presetNameOf, type WorkflowCopy,
} from '@/pages/workflow/copy'

interface Props {
  presets: WorkflowPreset[]
  copy: WorkflowCopy
  /** 实例化中：所有「使用此模板」一起禁用，避免连点建出一堆空草稿 */
  busy: boolean
  onUse: (code: string) => void
  onClose: () => void
}

/**
 * 模板库弹窗：按用途分组展示内置模板，选一个直接落成草稿。
 *
 * <p>原来是一个「从模板新建…」的 select，只看得见名字、看不见这个流程长什么样——
 * 用户反馈「工作流的选择不行」指的就是这件事。这里改成卡片：名称、描述、看点、节点数
 * 一眼可见，按 n8n / Dify / Coze 的四类用途分组，找模板不用逐个试。
 */
export default function PresetGallery({ presets, copy, busy, onUse, onClose }: Props) {
  const c = copy
  const closeRef = useRef<HTMLButtonElement>(null)

  /* 打开即把焦点放到关闭按钮：键盘用户 Tab 一次就能进卡片列表，而不是从页首重走一遍 */
  useEffect(() => {
    closeRef.current?.focus()
  }, [])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  /* 分类分组：未登记的分类兜到「other」，新模板上线不会因为前端没跟上就从列表里消失 */
  const groups = useMemo(() => {
    const known = new Set<string>(PRESET_CATEGORY_ORDER)
    const buckets = new Map<string, WorkflowPreset[]>()
    for (const preset of presets) {
      const key = known.has(preset.category) ? preset.category : 'other'
      buckets.set(key, [...(buckets.get(key) ?? []), preset])
    }
    return [...PRESET_CATEGORY_ORDER, 'other']
      .filter((key) => buckets.has(key))
      .map((key) => ({ key, items: buckets.get(key)! }))
  }, [presets])

  return (
    <div
      className="wf-gallery__backdrop"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <div className="wf-gallery" role="dialog" aria-modal="true" aria-labelledby="wf-gallery-title">
        <header className="wf-gallery__head">
          <div className="wf-gallery__intro">
            <h2 className="wf-gallery__title" id="wf-gallery-title">{c.presetGalleryTitle}</h2>
            <p className="wf-gallery__sub">{c.presetGallerySub}</p>
          </div>
          <button ref={closeRef} type="button" className="btn btn-ghost btn-sm" aria-label={c.presetClose} onClick={onClose}>
            <X size={15} />
          </button>
        </header>

        <div className="wf-gallery__body">
          {groups.map((group) => (
            <section key={group.key} className="wf-gallery__group">
              <h3 className="wf-gallery__group-title">
                {c.presetCategory[group.key] ?? group.key}
                <span className="wf-gallery__group-count">{group.items.length}</span>
              </h3>
              <div className="wf-gallery__grid">
                {group.items.map((preset) => (
                  <article key={preset.code} className="wf-gallery__card">
                    <div className="wf-gallery__card-head">
                      <h4 className="wf-gallery__card-title">{presetNameOf(c, preset.code, preset.name)}</h4>
                      <span className="wf-gallery__nodes">{c.presetNodes(preset.nodeCount)}</span>
                    </div>
                    <p className="wf-gallery__desc">{presetDescOf(c, preset.code, preset.description)}</p>
                    <ul className="wf-gallery__tags">
                      {preset.tags.map((tag) => <li key={tag} className="wf-gallery__tag">{tag}</li>)}
                    </ul>
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm wf-gallery__use"
                      disabled={busy}
                      onClick={() => onUse(preset.code)}
                    >
                      {busy ? c.presetUsing : c.presetUse}
                      <ArrowRight size={13} />
                    </button>
                  </article>
                ))}
              </div>
            </section>
          ))}
        </div>
      </div>
    </div>
  )
}
