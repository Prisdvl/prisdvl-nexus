/**
 * 工作流设计器的双语文案。
 *
 * `en` 显式标注为 `typeof zh`，语言键不一致时编译期就会报错，
 * 避免只加中文文案、英文页回落成空白。
 */
import type { WfNodeType } from '@/lib/workflowGraph'

const zh = {
  eyebrow: 'Workflows', title: '工作流', sub: '把知识检索、模型、Agent 与条件分支编排成可发布、可运行的流程。',
  pickerLabel: '工作流', createPh: '新工作流名称', create: '新建', creating: '创建中…',
  presetPick: '从模板新建…', presetCreated: '已从模板创建', presetFailed: '模板创建失败',
  pickerMenu: '切换工作流', pickerEmpty: '还没有工作流，先新建一个',
  statusDraft: '草稿', statusPublished: '已发布',
  delete: '删除', deleting: '删除中…', deleted: '已删除', deleteFailed: '删除失败',
  deleteConfirm: (name: string) => `删除工作流「${name}」？它会从列表里消失，历史运行记录仍然保留。`,
  presetOpen: '模板库', presetGalleryTitle: '从模板开始', presetGallerySub: '按用途分四类。选一个直接落成草稿，再改成自己的流程——从空白画布猜连线的成本太高。',
  presetUse: '使用此模板', presetUsing: '创建中…', presetNodes: (n: number) => `${n} 个节点`, presetClose: '关闭',
  presetCategory: {
    qa: '知识问答', content: '内容生产', ops: '分流与运营', review: '审核与合规',
  } as Record<string, string>,
  /* 模板名与描述由后端按 code 下发（只有中文），这里按 code 覆盖成双语；
     未登记的 code 回落后端原文，避免新增模板时前端显示空白 */
  presetDescs: {
    'knowledge-gap-analysis': '检索知识库后给出覆盖度与缺口清单，每条结论要求附证据。',
    'kb-qa-bot': '检索命中就带依据作答，没命中走兜底文案，避免模型凭空编造。',
    'study-plan-7d': '检索笔记后生成 7 天学习计划，渲染前插一道人工审批闸门。',
    'knowledge-report': '检索知识库后生成带来源标题的报告，可直接分享。',
    'weekly-report': '把本周素材丢给模型，按「完成 / 进行中 / 下周计划」三段式成稿。',
    'meeting-notes': '把零散会议记录整理成议题 / 结论 / 待办 / 遗留问题四块，缺信息处标注。',
    'support-triage': '模型先归类，再用条件分支走「退款流程 / 转人工」两套话术。',
    'release-review': '先生成对外文案，再卡一道人工审批；拒绝则本次运行失败。',
  } as Record<string, string>,
  /* 模板名由后端按 code 下发（当前只有中文），这里按 code 覆盖成双语；
     未登记的 code 回落后端原名，避免新增模板时前端显示空白 */
  presetNames: {
    'knowledge-gap-analysis': '知识缺口分析',
    'kb-qa-bot': '知识库问答机器人',
    'study-plan-7d': '7 天学习计划',
    'knowledge-report': '知识报告生成',
    'weekly-report': '周报生成',
    'meeting-notes': '会议纪要结构化',
    'support-triage': '工单分流',
    'release-review': '对外文案审核',
  } as Record<string, string>,
  emptyList: '还没有工作流', emptyListDesc: '新建一个后，画布会自动落一张 START → END 的最小可运行图。',
  pickHint: '从上方选择一个工作流开始编辑。',
  loadFailed: '加载失败', loadListFailed: '工作流列表加载失败', retry: '重试', loading: '加载中…',
  save: '保存草稿', saving: '保存中…', saved: '草稿已保存', savedDesc: '已生成一个新版本快照。', saveFailed: '保存失败',
  validate: '校验', validating: '校验中…', validOk: '校验通过', validFail: '校验未通过',
  validationTitle: '校验结果', noValidation: '尚未校验。校验针对已保存的草稿，保存后会自动带上当前改动。',
  publish: '发布', publishing: '发布中…', published: '已发布', publishedDesc: '正式运行将使用该版本。', publishFailed: '发布失败',
  debugRun: '试跑草稿', run: '正式运行', running: '执行中…',
  runTitle: '运行', inputs: '输入（JSON）', inputsHint: '变量用 {{ input.question }} 引用这里的字段。',
  inputsInvalid: '输入不是合法的 JSON 对象',
  /* 「试跑草稿」输入框预填的示例问题：模板里常见 {{ input.question }}，空着会渲染成空串 */
  sampleQuestion: '示例问题',
  runDone: '运行完成', debugDone: '试跑完成', runFailed: '运行失败',
  traceTitle: '节点轨迹', noRun: '选择一条运行记录查看节点轨迹。',
  historyTitle: '运行历史', historyEmpty: '还没有运行记录，运行一次就会出现在这里。',
  historyCount: '条记录', prevPage: '上一页', nextPage: '下一页',
  historyError: '运行历史加载失败', traceError: '节点轨迹加载失败',
  runFailedIsNotHttp: '这是运行本身失败（请求已成功返回），轨迹里能看到是哪个节点失败的。',
  httpFailedNote: '请求失败，拿不到 runId。失败运行已落库，已自动选中最新的那条，可在「运行历史」里切换。',
  runMeta: '状态', duration: '耗时', trigger: '触发方式',
  outputTitle: '运行结果', outputRaw: '原始输出（JSON）',
  palette: '节点', inspector: '配置', canvasHint: '点击节点编辑；「连线」后点目标节点即建立连接。',
  link: '连线', linkTrue: '连 true', linkFalse: '连 false', linking: '正在连线：点目标节点完成，Esc 取消',
  cancelLink: '取消连线', selfLoop: '不能连接自己', duplicateEdge: '该连接已存在',
  edgesTitle: '连线', noEdges: '此节点还没有出线。', deleteEdge: '删除连线', deleteNode: '删除节点',
  removeNodeConfirm: '删除该节点会同时删掉它的连线。',
  branchBothHint: '条件分支必须同时连 true 与 false 端口，否则校验不通过。',
  fields: {
    nodeId: '节点 ID', type: '类型', agent: 'Agent', kb: '知识库', topK: 'Top-K',
    query: '检索查询', mode: '检索模式', userPrompt: '提示词', temperature: '温度',
    template: '模板', operator: '操作符', left: '左值', right: '右值', endTemplate: '收尾模板（可选）',
    noConfig: '该类型没有必填配置。',
  },
  missing: '（未配置）', pickNone: '未选择', kbEmpty: '还没有知识库', agentEmpty: '还没有 Agent',
  approve: '同意并继续', reject: '拒绝', retryRun: '重试运行',
  awaitingApproval: '该运行停在人工审批上；同意后会从闸门之后继续执行，拒绝则本次运行失败。',
  approvalDone: '已同意，运行已继续', rejectDone: '已拒绝，运行已失败', approvalFailed: '审批提交失败',
  retryHint: '重试沿用本次运行的版本与入参，从失败节点继续；已成功的节点不会重跑。',
  retryDone: '已发起重试', retryFailed: '重试失败',
  dirty: '有未保存改动', clean: '已保存', publishedAs: '已发布版本', draftAs: '草稿版本', noVersion: '无版本',
  startTaken: 'START 节点只能有一个', nodesCount: '节点', edgesCount: '连线',
  statuses: {
    QUEUED: '排队', RUNNING: '执行中', SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已取消',
    PENDING: '待执行', SKIPPED: '已跳过', WAITING_APPROVAL: '待审批',
  } as Record<string, string>,
  types: {
    START: { title: '开始', desc: '接收运行时输入' },
    KNOWLEDGE_RETRIEVAL: { title: '知识检索', desc: '多库混合检索切片' },
    LLM: { title: '模型', desc: '按提示词调用对话模型' },
    AGENT: { title: 'Agent', desc: '调用已配置的 Agent' },
    IF_ELSE: { title: '条件分支', desc: '按受限操作符走 true / false' },
    TEMPLATE: { title: '模板', desc: '模板渲染出文本' },
    HUMAN_APPROVAL: { title: '人工审批', desc: '挂起等待人工放行' },
    END: { title: '结束', desc: '输出结果' },
  } as Record<WfNodeType, { title: string; desc: string }>,
}

const en: typeof zh = {
  eyebrow: 'Workflows', title: 'Workflows', sub: 'Compose retrieval, models, agents and branches into a publishable, runnable flow.',
  pickerLabel: 'Workflow', createPh: 'New workflow name', create: 'New', creating: 'Creating…',
  presetPick: 'New from template…', presetCreated: 'Created from template', presetFailed: 'Could not create from template',
  pickerMenu: 'Switch workflow', pickerEmpty: 'No workflows yet — create one first',
  statusDraft: 'Draft', statusPublished: 'Published',
  delete: 'Delete', deleting: 'Deleting…', deleted: 'Deleted', deleteFailed: 'Delete failed',
  deleteConfirm: (name: string) => `Delete “${name}”? It disappears from the list; its run history is kept.`,
  presetOpen: 'Templates', presetGalleryTitle: 'Start from a template',
  presetGallerySub: 'Four categories by intent. Pick one, get a draft, then make it yours — guessing how to wire nodes from a blank canvas is expensive.',
  presetUse: 'Use this template', presetUsing: 'Creating…', presetNodes: (n: number) => `${n} nodes`, presetClose: 'Close',
  presetCategory: {
    qa: 'Knowledge Q&A', content: 'Content', ops: 'Triage & ops', review: 'Review & compliance',
  } as Record<string, string>,
  presetDescs: {
    'knowledge-gap-analysis': 'Ranks what your notes cover and what is missing, with evidence for every claim.',
    'kb-qa-bot': 'Answers with citations when retrieval hits, falls back to a scripted reply when it misses.',
    'study-plan-7d': 'Turns your notes into a 7-day plan and pauses for human approval before rendering.',
    'knowledge-report': 'Builds a report with source titles from your knowledge base.',
    'weekly-report': 'Turns this week’s notes into done / in-progress / next-week sections.',
    'meeting-notes': 'Restructures loose notes into topics, decisions, action items and open questions.',
    'support-triage': 'Classifies a ticket, then branches into refund flow or human handoff.',
    'release-review': 'Drafts public copy and gates it behind a human approval; rejection fails the run.',
  } as Record<string, string>,
  presetNames: {
    'knowledge-gap-analysis': 'Knowledge gap analysis',
    'kb-qa-bot': 'Knowledge base Q&A bot',
    'study-plan-7d': '7-day study plan',
    'knowledge-report': 'Knowledge report',
    'weekly-report': 'Weekly report',
    'meeting-notes': 'Meeting notes digest',
    'support-triage': 'Support triage',
    'release-review': 'Public copy review',
  } as Record<string, string>,
  emptyList: 'No workflows yet', emptyListDesc: 'Create one and the canvas seeds a minimal START → END graph you can run immediately.',
  pickHint: 'Pick a workflow above to start editing.',
  loadFailed: 'Load failed', loadListFailed: 'Could not load workflows', retry: 'Retry', loading: 'Loading…',
  save: 'Save draft', saving: 'Saving…', saved: 'Draft saved', savedDesc: 'A new version snapshot was created.', saveFailed: 'Save failed',
  validate: 'Validate', validating: 'Validating…', validOk: 'Validation passed', validFail: 'Validation failed',
  validationTitle: 'Validation', noValidation: 'Not validated yet. Validation runs against the saved draft, so your current edits are saved first.',
  publish: 'Publish', publishing: 'Publishing…', published: 'Published', publishedDesc: 'Run will use this version.', publishFailed: 'Publish failed',
  debugRun: 'Debug draft', run: 'Run', running: 'Running…',
  runTitle: 'Run', inputs: 'Inputs (JSON)', inputsHint: 'Reference these fields as {{ input.question }}.',
  inputsInvalid: 'Inputs must be a JSON object',
  sampleQuestion: 'Example question',
  runDone: 'Run finished', debugDone: 'Debug finished', runFailed: 'Run failed',
  traceTitle: 'Node trace', noRun: 'Select a run to see its node trace.',
  historyTitle: 'Run history', historyEmpty: 'No runs yet — run the workflow and it will show up here.',
  historyCount: 'runs', prevPage: 'Prev', nextPage: 'Next',
  historyError: 'Could not load run history', traceError: 'Could not load node trace',
  runFailedIsNotHttp: 'The run itself failed (the request returned successfully); the trace shows which node failed.',
  httpFailedNote: 'The request failed, so there is no runId. The failed run is persisted — the newest one is selected automatically and you can switch in “Run history”.',
  runMeta: 'Status', duration: 'Duration', trigger: 'Trigger',
  outputTitle: 'Result', outputRaw: 'Raw output (JSON)',
  palette: 'Nodes', inspector: 'Inspector', canvasHint: 'Click a node to edit; after “Connect”, click a target node to link.',
  link: 'Connect', linkTrue: 'Link true', linkFalse: 'Link false', linking: 'Linking: click a target node, Esc to cancel',
  cancelLink: 'Cancel link', selfLoop: 'Cannot link a node to itself', duplicateEdge: 'That link already exists',
  edgesTitle: 'Links', noEdges: 'No outgoing links yet.', deleteEdge: 'Delete link', deleteNode: 'Delete node',
  removeNodeConfirm: 'Deleting this node also removes its links.',
  branchBothHint: 'A condition must link both the true and false ports, otherwise validation fails.',
  fields: {
    nodeId: 'Node ID', type: 'Type', agent: 'Agent', kb: 'Knowledge base', topK: 'Top-K',
    query: 'Query', mode: 'Mode', userPrompt: 'Prompt', temperature: 'Temperature',
    template: 'Template', operator: 'Operator', left: 'Left', right: 'Right', endTemplate: 'End template (optional)',
    noConfig: 'This type has no required configuration.',
  },
  missing: '(not set)', pickNone: 'None', kbEmpty: 'No knowledge bases', agentEmpty: 'No agents',
  approve: 'Approve and continue', reject: 'Reject', retryRun: 'Retry run',
  awaitingApproval: 'This run is paused on a human approval gate. Approving resumes after the gate; rejecting fails the run.',
  approvalDone: 'Approved — the run continued', rejectDone: 'Rejected — the run failed', approvalFailed: 'Could not submit the decision',
  retryHint: 'Retry reuses this run’s version and inputs and continues from the failed node; succeeded nodes are not re-run.',
  retryDone: 'Retry started', retryFailed: 'Retry failed',
  dirty: 'Unsaved changes', clean: 'Saved', publishedAs: 'Published version', draftAs: 'Draft version', noVersion: 'no version',
  startTaken: 'Only one START node is allowed', nodesCount: 'nodes', edgesCount: 'links',
  statuses: {
    QUEUED: 'Queued', RUNNING: 'Running', SUCCEEDED: 'Succeeded', FAILED: 'Failed', CANCELLED: 'Cancelled',
    PENDING: 'Pending', SKIPPED: 'Skipped', WAITING_APPROVAL: 'Awaiting approval',
  } as Record<string, string>,
  types: {
    START: { title: 'Start', desc: 'Receives run inputs' },
    KNOWLEDGE_RETRIEVAL: { title: 'Knowledge', desc: 'Hybrid search across bases' },
    LLM: { title: 'Model', desc: 'Call the chat model with a prompt' },
    AGENT: { title: 'Agent', desc: 'Invoke a configured agent' },
    IF_ELSE: { title: 'Condition', desc: 'Branch true / false on a limited operator' },
    TEMPLATE: { title: 'Template', desc: 'Render text from a template' },
    HUMAN_APPROVAL: { title: 'Approval', desc: 'Pause and wait for a human decision' },
    END: { title: 'End', desc: 'Emit the result' },
  } as Record<WfNodeType, { title: string; desc: string }>,
}

export const WORKFLOW_COPY = { zh, en }

export type WorkflowCopy = typeof zh

/** 后端下发的模板名（中文）按 code 覆盖成当前语言；未登记就回落后端原名 */
export const presetNameOf = (copy: WorkflowCopy, code: string, fallback: string) => copy.presetNames[code] ?? fallback

/** 同上，模板描述 */
export const presetDescOf = (copy: WorkflowCopy, code: string, fallback: string) => copy.presetDescs[code] ?? fallback

/** 分类展示顺序：固定在产品层面，不随后端返回顺序变 */
export const PRESET_CATEGORY_ORDER = ['qa', 'content', 'ops', 'review'] as const

/**
 * 「试跑草稿」输入框的默认值。
 *
 * 预填一个示例问题，而不是把 question 留空：模板里最常见的就是 {{ input.question }}，
 * 空着跑出来是「问： 」半截文本，看着像模板坏了。只在首次进入页面时生成，
 * 用户自己改过就一直是用户的内容。
 */
export function defaultInputs(copy: WorkflowCopy): string {
  return `{\n  "question": "${copy.sampleQuestion}"\n}`
}
