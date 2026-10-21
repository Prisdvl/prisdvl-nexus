import type { Locale } from '@/lib/locale'

export type LandingCopy = {
  nav: { home: string; what: string; flow: string; agents: string; stack: string }
  login: string
  register: string
  start: string
  seeFlow: string
  heroBadge: string
  heroSub: string
  heroWheel: readonly string[]
  schematic: {
    ingest: { t: string; s: string }
    search: { t: string; s: string }
    trail: { t: string; s: string }
  }
  sections: {
    what: { eyebrow: string; title: string; sub: string }
    flow: { eyebrow: string; title: string; sub: string }
    agents: { eyebrow: string; title: string; sub: string }
    stack: { eyebrow: string; title: string; sub: string }
  }
  whatCols: { kb: { t: string; p: string }; tools: { t: string; p: string }; agents: { t: string; p: string } }
  useCasesAria: string
  useCases: {
    interview: { t: string; p: string }
    code: { t: string; p: string }
    notes: { t: string; p: string }
    project: { t: string; p: string }
  }
  flowSteps: { key: string; label: string; hint: string }[]
  trailLabel: string
  trail: { aria: string; eyebrow: string; title: string; sub: string }
  cites: { aria: string; eyebrow: string; title: string; done: string }
  ctx: { aria: string; eyebrow: string; title: string; sub: string }
  modesAria: string
  modes: { plain: { t: string; p: string }; rag: { t: string; p: string }; agent: { t: string; p: string } }
  faqTitle: string
  faq: { q: string; a: string }[]
  cta: { title: string; sub: string; btn: string }
  foot: string
  about: string
  aboutBody: string
  cardNav: {
    browse: string
    agent: string
    account: string
    myAgents: string
    plain: string
    rag: string
  }
  agentDemo: { eyebrow: string; title: string; new: string; bound: string }
  chips: { kb: string; hits: string; cites: string; hist: string; net: string }
  scrollHint: string
  stackRows: [string, string][]
  /** 仿终端演示与回顶按钮的无障碍文案（landingCopy 此前遗漏） */
  termAria: string
  termInputAria: string
  termInputPh: string
  toBottomAria: string
  toTopAria: string
  githubRepoAria: string
  githubOpenAria: string
  avatarAlt: string
  /** FloatingNav 的无障碍文案（此前硬编码在 bit 内） */
  navAria: string
  sectionNavAria: string
}

const ZH: LandingCopy = {
  nav: { home: '首页', what: '产品', flow: '提问', agents: '智能体', stack: '实现' },
  login: '登录',
  register: '注册',
  start: '开始',
  seeFlow: '了解流程',
  heroBadge: '个人知识库工作台',
  heroSub: '上传文档建成知识库，提问可引用原文；需要时智能体可调用工具。登录后即可使用。',
  heroWheel: ['知识库', '智能体', '混合检索', '原文引用', '工具轨迹', 'Nexus'],
  schematic: {
    ingest: { t: '资料入库', s: '切片 · 索引' },
    search: { t: '混合检索', s: '向量 · 全文' },
    trail: { t: '工具轨迹', s: '可回看' },
  },
  sections: {
    what: { eyebrow: '产品', title: '资料变知识库', sub: '可检索、可引用原文；智能体需要时会调工具。' },
    flow: { eyebrow: '提问', title: '一次提问怎么跑', sub: '选智能体 → 检索 → 读文档 → 作答并挂引用。' },
    agents: { eyebrow: '智能体', title: '按任务配置', sub: '点选卡片查看绑定的知识库与工具。' },
    stack: { eyebrow: '实现', title: '同一仓库', sub: 'Spring AI 编排，React 工作台。悬停可暂停浏览。' },
  },
  whatCols: {
    kb: { t: '知识库', p: '支持 Markdown / PDF / DOCX。后台完成切片与索引；提问时混合检索，结论旁附原文切片。' },
    tools: { t: '工具调用', p: '可走知识库检索、计算器、代码沙箱等。每次调用的参数与返回可在回答中展开查看。' },
    agents: { t: '多智能体', p: '同一账号可配置多个智能体，各自绑定知识库、工具与提示词，按任务分开使用。' },
  },
  useCasesAria: '常见用法',
  useCases: {
    interview: { t: '课程与面试', p: '课件、笔记建好索引后，按自己的材料追问。' },
    code: { t: '代码与报错', p: '接上文档和报错记录，边查边算，结论仍可核对出处。' },
    notes: { t: '读书笔记', p: '多本书笔记入库后，可以跨书对照同一问题。' },
    project: { t: '个人项目', p: '把设计与需求绑给专属智能体，带着上下文继续改。' },
  },
  flowSteps: [
    { key: 'mode', label: '选模式', hint: '智能体 · 面试助手' },
    { key: 'think', label: '思考', hint: 'ThoughtLine' },
    { key: 'kb', label: '检索', hint: 'kb.search' },
    { key: 'doc', label: '读文档', hint: 'doc.read' },
    { key: 'ctx', label: '上下文', hint: '命中 6 · 引用 2' },
    { key: 'answer', label: '作答', hint: '流式 · 可核对' },
  ],
  trailLabel: '工具与出处',
  trail: { aria: '工具调用轨迹演示', eyebrow: '工具', title: '每次调用可展开', sub: '检索 → 读文档 → 沙箱确认' },
  cites: { aria: '引用出处示意', eyebrow: '出处', title: '原文切片', done: '已完成 · 引用 2 篇' },
  ctx: { aria: '本轮上下文示意', eyebrow: '上下文', title: '本轮用料', sub: '命中与引用分开看，分清是没给到料还是没答好。' },
  modesAria: '当前支持的会话模式',
  modes: {
    plain: { t: '普通对话', p: '直接使用对话模型；消息与会话由后端保存。' },
    rag: { t: '知识库问答', p: '选定知识库后再问；未命中资料时会明确说明。' },
    agent: { t: '智能体', p: '选用已发布的智能体；自带工具与绑定知识库。' },
  },
  faqTitle: '常见问题',
  faq: [
    { q: '支持上传哪些文件？', a: 'Markdown / PDF / DOCX。上传后异步完成切片、向量化与全文索引，完成后即可提问。' },
    { q: '和直接用通用大模型聊天有什么区别？', a: '会先检索你上传的资料再作答，并附原文切片；没有命中时会说明，而不是编造出处。' },
    { q: '智能体的工具调用安全吗？', a: '计算器与代码沙箱在隔离环境执行；入参与返回会写入回答轨迹，可回看。' },
    { q: '数据存在哪里？', a: '知识库、会话与智能体按账号隔离；可用 Docker Compose 部署，Nginx 反代并带健康检查。' },
  ],
  cta: { title: '进入工作台', sub: '知识库、对话与智能体连接当前账号。', btn: '登录' },
  foot: '个人知识库 · 混合检索 · 自定义智能体',
  about: '关于',
  aboutBody: '个人知识库与对话工作台。支持检索引用、工具调用与自定义智能体。',
  cardNav: {
    browse: '浏览',
    agent: '智能体',
    account: '账户',
    myAgents: '我的智能体',
    plain: '普通对话',
    rag: '知识库问答',
  },
  agentDemo: { eyebrow: '智能体', title: '我的智能体', new: '新建智能体', bound: '绑定' },
  chips: { kb: '知识库 1', hits: '命中 6 切片', cites: '引用 2 篇', hist: '历史 3 轮', net: '已联网' },
  scrollHint: '滚动到此处开始演示…',
  stackRows: [
    ['后端', 'Java 21 · Spring Boot 4.1 · Spring AI 2.0 · MyBatis-Plus · Flyway'],
    ['检索', 'PostgreSQL + pgvector · Elasticsearch 9 + IK · RRF 混合检索'],
    ['智能体', '工具注册与执行 · 知识库绑定 · 会话记忆 · 调用轨迹与指标'],
    ['异步与流式', 'RabbitMQ 导入流水线 · Redis · SseEmitter 流式输出'],
    ['前端', 'React 19 · Vite · TypeScript · CSS Tokens'],
    ['部署', 'Docker Compose · Nginx 反代 · 健康检查'],
  ],
  termAria: '仿终端提问演示',
  termInputAria: '终端输入，以 ask 等指令开头',
  termInputPh: 'ask "你的问题"',
  toBottomAria: '前往底部',
  toTopAria: '回到顶部',
  githubRepoAria: 'GitHub 仓库',
  githubOpenAria: '在 GitHub 打开 prisdvl-nexus 仓库',
  avatarAlt: 'Prisdvl 头像',
  navAria: '页面导航',
  sectionNavAria: '页面分区',
}

const EN: LandingCopy = {
  nav: { home: 'Home', what: 'Product', flow: 'Ask', agents: 'Agents', stack: 'Stack' },
  login: 'Sign in',
  register: 'Register',
  start: 'Start',
  seeFlow: 'See how it works',
  heroBadge: 'Personal knowledge workbench',
  heroSub: 'Upload docs into a knowledge base, ask with citations; agents can call tools when needed. Sign in to begin.',
  heroWheel: ['Knowledge', 'Agents', 'Hybrid search', 'Citations', 'Tool trail', 'Nexus'],
  schematic: {
    ingest: { t: 'Ingest', s: 'Chunk · index' },
    search: { t: 'Hybrid search', s: 'Vector · text' },
    trail: { t: 'Tool trail', s: 'Reviewable' },
  },
  sections: {
    what: { eyebrow: 'Product', title: 'Docs to knowledge', sub: 'Searchable, citable; agents call tools when needed.' },
    flow: { eyebrow: 'Ask', title: 'How a question runs', sub: 'Pick agent → retrieve → read docs → answer with cites.' },
    agents: { eyebrow: 'Agents', title: 'Configure by task', sub: 'Select a card to see bound KBs and tools.' },
    stack: { eyebrow: 'Stack', title: 'One repo', sub: 'Spring AI orchestration, React workbench. Hover to pause.' },
  },
  whatCols: {
    kb: { t: 'Knowledge bases', p: 'Markdown / PDF / DOCX. Chunking and indexing run in the background; answers cite source snippets.' },
    tools: { t: 'Tool calls', p: 'KB search, calculator, code sandbox, and more. Expand each call’s args and results in the reply.' },
    agents: { t: 'Multiple agents', p: 'Configure agents per task with their own KBs, tools, and prompts.' },
  },
  useCasesAria: 'Use cases',
  useCases: {
    interview: { t: 'Courses & interviews', p: 'Index lecture notes, then ask against your own material.' },
    code: { t: 'Code & errors', p: 'Attach docs and stack traces; still verify citations.' },
    notes: { t: 'Reading notes', p: 'Compare the same question across books in one library.' },
    project: { t: 'Side projects', p: 'Bind design and requirements to a dedicated agent.' },
  },
  flowSteps: [
    { key: 'mode', label: 'Mode', hint: 'Agent · Interview' },
    { key: 'think', label: 'Think', hint: 'ThoughtLine' },
    { key: 'kb', label: 'Retrieve', hint: 'kb.search' },
    { key: 'doc', label: 'Read', hint: 'doc.read' },
    { key: 'ctx', label: 'Context', hint: '6 hits · 2 cites' },
    { key: 'answer', label: 'Answer', hint: 'Stream · verify' },
  ],
  trailLabel: 'Tools & sources',
  trail: { aria: 'Tool trail demo', eyebrow: 'Tools', title: 'Every call expandable', sub: 'Retrieve → read → sandbox confirm' },
  cites: { aria: 'Citation preview', eyebrow: 'Sources', title: 'Source snippets', done: 'Done · 2 cites' },
  ctx: { aria: 'Turn context preview', eyebrow: 'Context', title: 'This turn’s inputs', sub: 'Hits and cites stay separate—missed retrieval vs weak answer.' },
  modesAria: 'Supported session modes',
  modes: {
    plain: { t: 'Plain chat', p: 'Talk to the model; messages are stored by the backend.' },
    rag: { t: 'KB Q&A', p: 'Ask after picking a knowledge base; misses are stated clearly.' },
    agent: { t: 'Agent', p: 'Use a published agent with its tools and bound KBs.' },
  },
  faqTitle: 'FAQ',
  faq: [
    { q: 'Which files are supported?', a: 'Markdown / PDF / DOCX. Chunking, embedding, and full-text indexing run async.' },
    { q: 'How is this different from a generic LLM chat?', a: 'We retrieve your uploads first and attach snippets; if nothing hits, we say so.' },
    { q: 'Are agent tool calls safe?', a: 'Calculator and sandbox run isolated; args and results stay in the trail.' },
    { q: 'Where is data stored?', a: 'KBs, chats, and agents are per account; deploy with Docker Compose and Nginx.' },
  ],
  cta: { title: 'Open the workbench', sub: 'Knowledge, chat, and agents on your account.', btn: 'Sign in' },
  foot: 'Personal KB · hybrid search · custom agents',
  about: 'About',
  aboutBody: 'A personal knowledge and chat workbench with retrieval, citations, tool calls, and custom agents.',
  cardNav: {
    browse: 'Browse',
    agent: 'Agents',
    account: 'Account',
    myAgents: 'My agents',
    plain: 'Plain chat',
    rag: 'KB Q&A',
  },
  agentDemo: { eyebrow: 'Agents', title: 'My agents', new: 'New agent', bound: 'Bound' },
  chips: { kb: 'KB 1', hits: '6 chunks', cites: '2 cites', hist: '3 turns', net: 'Online' },
  scrollHint: 'Scroll here to start the demo…',
  stackRows: [
    ['Backend', 'Java 21 · Spring Boot 4.1 · Spring AI 2.0 · MyBatis-Plus · Flyway'],
    ['Retrieval', 'PostgreSQL + pgvector · Elasticsearch 9 + IK · RRF hybrid'],
    ['Agents', 'Tool registry · KB binding · memory · call trail'],
    ['Async & stream', 'RabbitMQ ingest · Redis · SseEmitter'],
    ['Frontend', 'React 19 · Vite · TypeScript · CSS Tokens'],
    ['Deploy', 'Docker Compose · Nginx · health checks'],
  ],
  termAria: 'Fake-terminal ask demo',
  termInputAria: 'Terminal input, starting with a command such as ask',
  termInputPh: 'ask "your question"',
  toBottomAria: 'Go to bottom',
  toTopAria: 'Back to top',
  githubRepoAria: 'GitHub repository',
  githubOpenAria: 'Open the prisdvl-nexus repository on GitHub',
  avatarAlt: 'Prisdvl avatar',
  navAria: 'Page navigation',
  sectionNavAria: 'Page sections',
}

export function landingCopy(locale: Locale): LandingCopy {
  return locale === 'en' ? EN : ZH
}
