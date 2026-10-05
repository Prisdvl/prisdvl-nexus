import { useEffect, useState, type FormEvent, type ReactElement } from 'react'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, Input, Textarea } from '@/components/ui/form'
import { useAgentStore } from '@/stores/agentStore'
import {
  AGENT_CODE_PATTERN,
  type Agent,
  type AnswerPolicy,
  type CreateAgentRequest,
  type Tool
} from '@/api/types/agent'

/**
 * Agent 编辑器（创建 / 编辑共用一个 Dialog）。
 *
 * ## 为什么分「基础 / 高级」两段（任务书 §42）
 *
 * 高级项有 `systemPrompt` / `modelRole` / `answerPolicy` / `temperature`。
 * 把它们和名称、描述平铺在一起，用户会被一堆看不懂的字段吓走——
 * 而多数 Agent 只需要"起个名 + 写句描述"。
 *
 * 基础：名称 · 标识（code）· 描述 · 知识库范围
 * 高级：系统提示词 · 回答策略 · 温度（默认折叠）
 *
 * ## `code` 只在创建时可改
 *
 * 它是业务标识（对话里引用 Agent 用它），改了就等于换了一个 Agent。
 * 编辑时置灰并说明——**禁用而不隐藏**，否则用户会以为代码丢了。
 */
export interface AgentEditorProps {
  open: boolean
  /** 传 null = 创建模式。 */
  agent: Agent | null
  tools: Tool[]
  onClose: () => void
  onSaved: () => void | Promise<void>
}

type Errors = Partial<Record<'name' | 'code' | 'description', string>>

export function AgentEditor({
  open,
  agent,
  tools,
  onClose,
  onSaved
}: AgentEditorProps): ReactElement {
  const store = useAgentStore()
  const isEdit = agent !== null

  const [name, setName] = useState('')
  const [code, setCode] = useState('')
  const [description, setDescription] = useState('')
  const [systemPrompt, setSystemPrompt] = useState('')
  const [answerPolicy, setAnswerPolicy] = useState<AnswerPolicy>('KB_PREFERRED')
  const [temperature, setTemperature] = useState('')
  const [toolCodes, setToolCodes] = useState<string[]>([])
  const [showAdvanced, setShowAdvanced] = useState(false)
  const [errors, setErrors] = useState<Errors>({})
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)

  // 打开时用 agent 填充。**放在 useEffect 里**而不是 render 里直接写 state——
  // 否则每次父组件重渲染都会把用户正在输入的内容重置掉。
  useEffect(() => {
    if (!open) return
    setName(agent?.name ?? '')
    setCode(agent?.code ?? '')
    setDescription(agent?.description ?? '')
    setSystemPrompt(agent?.systemPrompt ?? '')
    setAnswerPolicy((agent?.answerPolicy as AnswerPolicy) ?? 'KB_PREFERRED')
    // 空字符串 = 不传 = 跟随全局默认（后端注释：不是 0）
    setTemperature(agent?.temperature === null || agent?.temperature === undefined ? '' : String(agent.temperature))
    setToolCodes(agent?.toolCodes ?? [])
    setErrors({})
    setSaveError(null)
    setShowAdvanced(false)
  }, [open, agent])

  const validate = (): boolean => {
    const e: Errors = {}
    if (!name.trim()) e.name = '请输入名称'
    else if (name.trim().length > 128) e.name = '名称最长 128 字符'

    if (!isEdit) {
      if (!code.trim()) e.code = '请输入标识'
      else if (!AGENT_CODE_PATTERN.test(code.trim()))
        e.code = '只能用小写字母、数字、点、下划线、连字符（2-64 位），且首字符为字母或数字'
    }
    if (description.length > 512) e.description = '描述最长 512 字符'

    setErrors(e)
    return Object.keys(e).length === 0
  }

  const onSave = async (ev?: FormEvent) => {
    ev?.preventDefault()
    if (!validate()) return
    setSaving(true)
    setSaveError(null)
    try {
      const payload: CreateAgentRequest = {
        name: name.trim(),
        ...(isEdit ? {} : { code: code.trim() }),
        ...(description.trim() ? { description: description.trim() } : {}),
        ...(systemPrompt.trim() ? { systemPrompt: systemPrompt.trim() } : {}),
        ...(answerPolicy !== 'KB_PREFERRED' ? { answerPolicy } : {}),
        // 空字符串 → 不传 → 跟随全局默认（不是 0！）
        ...(temperature.trim() ? { temperature: Number(temperature) } : {}),
        ...(toolCodes.length > 0 ? { toolCodes } : {})
      }

      if (isEdit && agent) {
        const ok = await store.update(agent.id, payload)
        if (!ok) {
          setSaveError(store.error ?? '保存失败')
          return
        }
      } else {
        const created = await store.create(payload)
        if (!created) {
          setSaveError(store.error ?? '创建失败')
          return
        }
      }
      await onSaved()
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={isEdit ? `编辑「${agent?.name}」` : '创建 Agent'}
      closeOnOverlayClick={false}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={saving}>
            取消
          </Button>
          <Button onClick={() => void onSave()} loading={saving} data-testid="agent-save">
            保存
          </Button>
        </>
      }
    >
      <form className="flex flex-col gap-4" onSubmit={onSave} noValidate data-testid="agent-form">
        {saveError ? (
          <p role="alert" className="m-0 text-[var(--nx-text-sm)] text-[var(--nx-color-danger)]">
            {saveError}
          </p>
        ) : null}

        <Field label="名称" required error={errors.name}>
          {(p) => (
            <Input
              {...p}
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="例如：Java 导师"
              data-testid="agent-name"
            />
          )}
        </Field>

        <Field
          label="标识"
          required={!isEdit}
          error={errors.code}
          description={
            isEdit
              ? '创建后不可修改——对话里引用 Agent 用的就是它。'
              : '对话里引用这个 Agent 用的标识。'
          }
        >
          {(p) => (
            <Input
              {...p}
              value={code}
              onChange={(e) => setCode(e.target.value)}
              disabled={isEdit}
              placeholder="java-mentor"
              data-testid="agent-code"
            />
          )}
        </Field>

        <Field label="描述" error={errors.description}>
          {(p) => (
            <Textarea
              {...p}
              rows={2}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="一句话说明它擅长什么"
              data-testid="agent-description"
            />
          )}
        </Field>

        {/*
          知识库范围：**刻意不提供"必须选一个"的约束**。
          后端语义是「空 = 不限制（检索全部）」，所以这里只给一句说明。
        */}
        <p className="m-0 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
          知识库范围：暂不支持在编辑器里选择；留空表示检索你的全部知识库。
        </p>

        {tools.length > 0 ? (
          <Field label={`工具（${toolCodes.length}/${tools.length}）`} description="勾选后该 Agent 可以调用。">
            {() => (
              <div className="flex max-h-40 flex-col gap-1 overflow-y-auto" data-testid="agent-tools">
                {tools.map((t) => (
                  <label key={t.code} className="flex items-center gap-2 text-[var(--nx-text-sm)]">
                    <input
                      type="checkbox"
                      checked={toolCodes.includes(t.code)}
                      onChange={(e) =>
                        setToolCodes((prev) =>
                          e.target.checked ? [...prev, t.code] : prev.filter((c) => c !== t.code)
                        )
                      }
                    />
                    <span className="min-w-0 flex-1 truncate">{t.name}</span>
                    <span className="shrink-0 text-[var(--nx-text-xs)] text-[var(--nx-color-text-muted)]">
                      {t.category}
                    </span>
                  </label>
                ))}
              </div>
            )}
          </Field>
        ) : null}

        {/* ---- 高级区（默认折叠）---- */}
        <div>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setShowAdvanced((v) => !v)}
            data-testid="agent-advanced-toggle"
          >
            {showAdvanced ? '收起高级设置' : '高级设置'}
          </Button>

          {showAdvanced ? (
            <div className="mt-2 flex flex-col gap-4" data-testid="agent-advanced">
              <Field label="系统提示词" description="留空则用内置默认提示。">
                {(p) => (
                  <Textarea
                    {...p}
                    rows={4}
                    value={systemPrompt}
                    onChange={(e) => setSystemPrompt(e.target.value)}
                    placeholder="你是一个擅长 Java 的导师…"
                    data-testid="agent-prompt"
                  />
                )}
              </Field>

              <Field label="回答策略" description="没有可引用资料时怎么办。">
                {(p) => (
                  <select
                    {...p}
                    value={answerPolicy}
                    onChange={(e) => setAnswerPolicy(e.target.value as AnswerPolicy)}
                    className="nx-input"
                    data-testid="agent-policy"
                  >
                    <option value="KB_PREFERRED">优先用资料（没有就用模型自身知识）</option>
                    <option value="KB_ONLY">只用资料（没有就不答）</option>
                  </select>
                )}
              </Field>

              <Field label="温度" description="留空则跟随全局默认。越高越发散。">
                {(p) => (
                  <Input
                    {...p}
                    type="number"
                    step="0.1"
                    min="0"
                    max="2"
                    value={temperature}
                    onChange={(e) => setTemperature(e.target.value)}
                    placeholder="默认"
                    data-testid="agent-temperature"
                  />
                )}
              </Field>
            </div>
          ) : null}
        </div>
      </form>
    </Dialog>
  )
}
