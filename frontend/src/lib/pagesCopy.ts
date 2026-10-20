import type { Locale } from '@/lib/locale'

/**
 * 页面级中英文案字典：工作台（WorkbenchPage）与 Agent 页（AgentsPage）。
 * 与 appCopy / sectionsCopy 同风格：显式 interface + 中英两份常量，带插值的文案用函数表达。
 *
 * 不在此处翻译的内容：
 * - mock 演示数据（示例文档名 / 知识库名 / 工具名 / 演示回答正文）属于业务数据；
 * - 会话名、文档名、知识库名等来自后端的动态字段；
 * - 代码注释、技术标识符（enum 值、API 字段名）。
 */
export interface PagesCopy {
  workbench: {
    /* —— 页头 —— */
    eyebrow: string
    title: string
    greeting: (name: string) => string
    sub: string
    startChat: string
    quickTitle: string
    quickChat: string
    quickChatDesc: string
    quickKb: string
    quickKbDesc: string
    quickAgent: string
    quickAgentDesc: string
    quickAutomation: string
    quickAutomationDesc: string
    /* —— 统计卡 —— */
    statKb: string
    statKbSub: (n: number) => string
    statDocs: string
    statDocsSub: string
    statConv: string
    statConvSub: string
    statAgent: string
    statAgentSub: string
    countsUnavailable: string
    /* —— 用量趋势 —— */
    trendTitle: string
    trendRange: string
    trendEmpty: string
    trendAria: string
    trendHitAria: (label: string, n: number) => string
    metricCalls: string
    metricAvg: string
    metricPeak: string
    /* —— 最近文档处理 —— */
    importsTitle: string
    importCount: (n: number) => string
    mockParsing: (pct: number, done: number, total: number) => string
    mockDone: (n: number) => string
    mockDemoHint: string
    importsEmpty: string
    statusReady: string
    statusFailed: string
    statusProcessing: string
    openKb: string
    /* —— 活动时间线 —— */
    timelineTitle: string
    timelineRange: string
    timelineEmpty: string
    untitledConv: string
    activityImport: (label: string) => string
    activityChat: (label: string) => string
    activityImportDetail: string
    activityChatDetail: string
    /* —— 功能速览（轮播）—— */
    featureTitle: string
    carouselTitle: [string, string, string, string]
    carouselDesc: [string, string, string, string]
    /* —— 活跃度热力 —— */
    heatTitle: string
    heatRange: string
    heatAria: string
    heatTotal: (n: number) => string
    heatActive: (n: number) => string
    heatLess: string
    heatMore: string
    /* —— 最近会话 —— */
    recentTitle: string
    recentAll: string
    recentEmpty: string
    manageKb: string
    browseDocs: string
  }
  agents: {
    /* —— 页头 —— */
    eyebrow: string
    title: string
    sub: string
    newAgent: string
    /* —— 列表状态 —— */
    loading: string
    listFailPrefix: string
    retry: string
    emptyTitle: string
    emptyDesc: string
    boundKb: (kb: string) => string
    /* —— 卡片操作 —— */
    test: string
    editAria: string
    editTitle: string
    duplicateAria: string
    duplicateTitle: string
    deleteAria: string
    deleteTitle: string
    /* —— 测试台 —— */
    benchTitle: string
    benchHint: string
    benchPh: string
    benchInputAria: string
    run: string
    running: string
    runDone: string
    runFailed: string
    /* —— 测试台 toast —— */
    mockTestOut: (name: string) => string
    mockTestTitle: string
    mockTestBody: string
    testDoneTitle: string
    testToolsUsed: (n: number) => string
    testNoTools: string
    testFailTitle: string
    /* —— 删除 / 复制 / 保存 —— */
    confirmDelete: (name: string) => string
    deletedTitle: string
    restoredTitle: string
    deletedOkTitle: string
    deleteFailTitle: string
    copyName: (name: string) => string
    copiedTitle: string
    copiedMockName: (name: string) => string
    copyFailTitle: string
    errNameEmpty: string
    savedTitle: string
    saveFailTitle: string
    /* —— 创建向导 —— */
    createTitle: string
    creating: string
    stepBack: string
    stepNext: string
    stepFinal: string
    errNameRequired: string
    createdTitle: string
    createdMockBody: string
    createdRealBody: string
    createFailTitle: string
    nameLabel: string
    namePh: string
    nameAria: string
    descLabel: string
    descPh: string
    descAria: string
    modelRoleHint: string
    /* —— 第二步：绑定 —— */
    bindKbLabel: string
    bindKbHint: string
    bindKbEmpty: string
    bindToolsLabel: string
    bindToolsHint: string
    bindToolsEmpty: string
    /* —— 第三步：确认 —— */
    confirmIntro: string
    sumName: (v: string) => string
    sumKb: (v: string) => string
    sumTools: (v: string) => string
    sumEmpty: string
    sumDemo: string
    sumLimit: (n: number) => string
    sumAll: string
    /* —— 编辑弹窗 —— */
    editTitleModal: string
    cancel: string
    save: string
    saving: string
    iconLabel: string
    visibilityLabel: string
    visibilityDraft: string
    visibilityPublished: string
    draftHint: string
    publishBtn: string
    publishedBadge: string
    draftBadge: string
    publishDone: string
    instructionsLabel: string
    instructionsPh: string
    instructionsHint: string
    creativityLabel: string
    creativityHint: string
    testStepTitle: string
    testStepHint: string
    testRun: string
    testRunning: string
    testResultTitle: string
    testResultEmpty: string
    examplePrompts: [string, string, string]
    benchExamples: string
    benchTrace: string
  }
  chat: {
    /* —— 模式与工具 —— */
    modePlain: string
    modeRag: string
    modeAgent: string
    toolStepSearch: string
    toolStepCalc: string
    toolStepLabel: (name: string) => string
    stepGenerate: string
    stepRetrieve: (hit: number) => string
    stepRetrievePartial: string
    unknownSource: string
    docFallback: string
    /* —— 空态 —— */
    emptyTitle: string
    emptyDesc: string
    newChat: string
    startTitle: string
    startDesc: string
    /* —— 欢迎区示例问题（按真实知识库生成，规则见 lib/kbStarters.ts） —— */
    startersAria: string
    starterOverview: (kb: string) => string
    starterPitfalls: (kb: string) => string
    starterTopic: (topic: string, kb: string) => string
    starterNoKb: string
    starterEmptyKb: string
    starterListFailed: string
    starterGoImport: string
    /* —— 页头 —— */
    metaLine: (n: number, updated: string) => string
    h1Title: (title: string) => string
    configTitle: string
    configAria: string
    summaryRag: (n: number) => string
    exportMd: string
    /* —— 会话配置浮层 —— */
    modeLabel: string
    agentLabel: string
    agentNeedMode: string
    kbLabel: string
    kbNeedRag: string
    kbLoading: string
    kbEmptyBind: string
    retry: string
    /* —— 消息操作 —— */
    saveResend: string
    cancel: string
    toolCalls: string
    viewCite: (name: string) => string
    done: string
    /** 不含前导分隔符（" · " 由 JSX 文本提供，避免 inline-block 吃掉行首空格） */
    doneWithCites: (n: number) => string
    copyMsg: string
    editResend: string
    branchFromAria: string
    continueGen: string
    /* —— 思考行 —— */
    thinkingLabel: string
    thinkingStopped: string
    thinkingSettled: string
    /* —— 输入区 —— */
    composerPh: string
    expandAria: string
    expandTitle: string
    collapseAria: string
    collapseTitle: string
    /* 以下三段不含行首/行尾空格：拼接处的空格由 JSX 的 {' '} 提供，
       避免被 <Loc> 的 inline-block 行末空白折叠掉 */
    hintKb: (agent: string, n: number) => string
    hintAt: string
    hintSlash: string
    /* —— toast：加载 / 生成 —— */
    loadFailTitle: string
    genFailTitle: string
    genFailBody: string
    chatFailTitle: string
    stoppedBody: string
    /* —— toast：队列 / 停止 / 继续 —— */
    queuedTitle: string
    queuedBody: (n: number) => string
    queueClearedTitle: string
    queueClearedBody: (n: number) => string
    stopTitle: string
    stopBody: string
    continueTitle: string
    continueBody: string
    continuePrompt: string
    retryOkTitle: string
    retryOkBody: string
    /* —— toast：Agent / 模式 —— */
    agentSwitched: string
    agentModeSwitched: string
    noAgentTitle: string
    noAgentBody: string
    modeNoAgentTitle: string
    modeNoAgentBody: string
    switchFailTitle: string
    modeSwitchedTitle: string
    modeSwitchedMock: (from: string, to: string) => string
    modeSwitchedTo: (name: string) => string
    cleanedAgent: string
    cleanedKb: string
    modeSwitchedBody: (items: string[]) => string
    historyKept: string
    /* —— toast：会话 / 分支 —— */
    archivedTitle: string
    archivedBody: string
    restoredTitle: string
    restoredBody: string
    branchMockTitle: string
    branchMockBody: string
    branchTempTitle: string
    branchTempBody: string
    branchCreatedTitle: string
    branchCreatedBody: (title: string) => string
    branchFailTitle: string
    defaultTitle: string
    justNow: string
    createdTitle: string
    createdBody: (mode: string) => string
    createFailTitle: string
    /* —— 列表分隔符（中文用「、」，英文用逗号+空格）—— */
    listSep: string
    /* —— 复制 / 导出 —— */
    copyDenied: string
    copyMsgToast: string
    copyFailTitle: string
    /** Markdown 导出里「角色：正文」冒号两侧的排版（中文全角，英文半角） */
    exportRoleSep: string
    exportAgent: (name: string) => string
    exportKb: (names: string) => string
    exportKbNone: string
    exportRoleUser: string
    exportRoleAi: string
    exportCites: (list: string) => string
    /* —— 知识库绑定 —— */
    kbModeTitle: string
    kbModeBody: string
    kbBindFailTitle: string
    kbSourceDesc: string
    /* —— 高风险工具确认 —— */
    riskExplain: string
    riskFallback: string
    /* —— 新建会话向导 —— */
    wizardTitle: string
    wizardModeLabel: string
    wizardModeAria: string
    wizardModeHint: string
    wizardAgentLabel: string
    wizardKbLabel: string
    wizardKbLoading: string
    wizardKbEmpty: string
    wizardConfirm: (mode: string) => string
    wizardBindCount: (n: number) => string
    wizardUseAgent: (name: string) => string
    wizardPlainReady: string
    wizardCreating: string
    wizardCreate: string
    stepBack: string
    stepNext: string
    /* —— 引用详情面板（ReferencePanel）—— */
    refSourceFallback: string
    refNoIdError: string
    refAria: string
    refCloseAria: string
    refOpenInKb: string
    refGoKb: string
    refLoading: string
    refHitTitle: string
    /** 相似度后缀（` · 相似度 92.0%`），分数为空时整段省略 */
    refScoreSuffix: (score: number) => string
    refDocTitle: string
    refNoSnippet: string
    /* —— 结构化结果卡（ResultCard）—— */
    kindTable: string
    kindJson: string
    kindChart: string
    /** 图表导出为 CSV 时的两列表头 */
    csvLabelCol: string
    csvValueCol: string
    resultAria: (title: string, kind: string) => string
    copyResultAria: string
    downloadResultAria: string
    copiedBtn: string
    copyBtn: string
    downloadBtn: string
    /* —— 上下文胶囊（ContextChips）—— */
    ctxAria: string
    ctxKb: (n: number) => string
    ctxHitTitle: string
    ctxHit: (n: number) => string
    ctxCiteTitle: string
    /** 「引用 N 篇」，带扫描数时追加「/ 共 M 篇」 */
    ctxCite: (used: number, scanned: number) => string
    ctxHistoryTitle: string
    ctxHistory: (n: number) => string
    ctxWebOnTitle: string
    ctxWebOffTitle: string
    ctxWebOn: string
    ctxWebOff: string
    ctxAttach: (n: number) => string
    ctxTruncatedTitle: string
    ctxTruncated: (n: number) => string
    /* —— 反馈条（FeedbackBar）—— */
    fbRecorded: string
    fbUndo: string
    fbAria: string
    fbHelpful: string
    fbNotHelpful: string
    /** 与 FeedbackBar 的 REASONS 数组按序一一对应：显示用本表，落库仍存 REASONS 的规范值 */
    fbReasons: [string, string, string, string, string, string]
    /* —— 高风险确认卡（ConfirmCard）—— */
    confirmTitle: string
    confirmAria: (name: string) => string
    confirmAllowOnce: string
    confirmAllowAlways: string
    confirmDeny: string
    /* —— 待发送队列（QueueStrip）—— */
    queueAria: (n: number) => string
    queueHead: (n: number) => string
    queueRemoveAria: (n: number) => string
    queueClear: string
    /* —— 提问导航（MessageRail / MessagePreviewPopover）—— */
    railAria: string
    railItemAria: (n: number) => string
    previewAria: string
    previewJumpAria: (n: number, summary: string) => string
    /* —— mock 演示数据（工具调用轨迹）—— */
    mockArgComplexity: string
    mockArgDocs: string
  }
  plugins: {
    eyebrow: string
    title: string
    sub: string
    importSkill: string
    searchPh: string
    searchAria: string
    tabInstalled: string
    tabMarket: string
    tabMcp: string
    featured: string
    categoryAll: string
    categoryLabels: Record<string, string>
    install: string
    installed: string
    uninstall: string
    configure: string
    details: string
    version: string
    byAuthor: (author: string) => string
    rating: string
    downloads: string
    tools: string
    verified: string
    official: string
    updated: string
    installedEmptyTitle: string
    installedEmptyDesc: string
    marketEmpty: string
    noResults: (q: string) => string
    importTitle: string
    importDesc: string
    importPh: string
    importAria: string
    importBrowse: string
    importBrowseAria: string
    importDropHint: string
    importBtn: string
    importDone: string
    importFail: string
    importInvalid: string
    skillNameUnknown: string
    mcpSub: string
    mcpConnect: string
    mcpDisconnect: string
    mcpConfigure: string
    mcpEndpoint: string
    mcpTools: (n: number) => string
    mcpConnected: string
    mcpOffline: string
    mcpModalTitle: string
    mcpNameLabel: string
    mcpEndpointLabel: string
    mcpTokenLabel: string
    mcpTokenPh: string
    mcpToolsLabel: string
    mcpSave: string
    mcpSavedTitle: string
    mcpSavedBody: string
    mcpConnectedTitle: string
    mcpDisconnectedTitle: string
    detailOverview: string
    detailTools: string
    detailCompat: string
    detailCompatBody: string
    detailTags: string
  }
  community: {
    eyebrow: string
    title: string
    sub: string
    publish: string
    searchPh: string
    searchAria: string
    tabDiscover: string
    tabMine: string
    featuredLabel: string
    use: string
    installed: string
    detail: string
    authorYou: string
    republish: string
    sendReview: string
    reviewDoneTitle: string
    reviewDoneBody: string
    downloads: string
    likes: string
    rating: string
    reviews: string
    version: string
    byAuthor: (author: string) => string
    updated: string
    verified: string
    categoryAll: string
    categoryLabels: Record<string, string>
    sortPopular: string
    sortNewest: string
    sortRating: string
    emptyTitle: string
    emptyDesc: string
    noResults: (q: string) => string
    publishTitle: string
    publishSub: string
    stepBasic: string
    stepCapability: string
    stepPublish: string
    back: string
    next: string
    create: string
    cancel: string
    nameLabel: string
    namePh: string
    nameAria: string
    descLabel: string
    descPh: string
    descAria: string
    categoryLabel: string
    tagsLabel: string
    tagsPh: string
    tagsAria: string
    modelLabel: string
    versionLabel: string
    visibilityLabel: string
    statusPublished: string
    statusDraft: string
    statusUnlisted: string
    visibilityPublic: string
    visibilityPublicHint: string
    visibilityDraft: string
    visibilityDraftHint: string
    visibilityUnlisted: string
    visibilityUnlistedHint: string
    toolsLabel: string
    readmeLabel: string
    readmePh: string
    publishDoneTitle: string
    publishDoneBody: string
    myEmptyTitle: string
    myEmptyDesc: string
    edit: string
    unpublish: string
    delete: string
    deleteConfirm: (name: string) => string
    deletedTitle: string
    noReviews: string
    writeReview: string
    sampleReviewers: [string, string, string]
    sampleReviewMeta: [string, string, string]
    sampleReviewTexts: [string, string, string]
    overviewTitle: string
    toolsTitle: string
    reviewsTitle: string
    readmeTitle: string
  }
  automations: {
    eyebrow: string
    title: string
    sub: string
    newAutomation: string
    searchPh: string
    searchAria: string
    tabAll: string
    tabEnabled: string
    tabPaused: string
    emptyTitle: string
    emptyDesc: string
    noResults: (q: string) => string
    enabled: string
    disabled: string
    scheduleHourly: string
    scheduleDaily: string
    scheduleWeekly: string
    scheduleCustom: string
    agentLabel: string
    kbLabel: string
    lastRun: string
    nextRun: string
    never: string
    runNow: string
    history: string
    edit: string
    delete: string
    deleteConfirm: (name: string) => string
    createTitle: string
    editTitle: string
    nameLabel: string
    namePh: string
    promptLabel: string
    promptPh: string
    scheduleLabel: string
    notifyLabel: string
    notifyHint: string
    cancel: string
    save: string
    createdTitle: string
    updatedTitle: string
    deletedTitle: string
    queuedTitle: string
    queuedBody: (name: string) => string
    historyTitle: string
    historyEmpty: string
    statusDone: string
    statusFailed: string
    statusQueued: string
  }
  trace: {
    viewChat: string
    viewTrace: string
    duration: string
    turns: string
    calls: string
    searchPh: string
    searchAria: string
    input: string
    model: string
    tools: string
    empty: string
    turn: (n: number) => string
    roleUser: string
    roleAssistant: string
    roleTool: string
    toolOnly: string
    statusRunning: string
    statusDone: string
    statusFailed: string
    statusTimeout: string
    statusCancelled: string
    statusAwaiting: string
    overview: string
    overviewAgent: string
    overviewModel: string
    overviewMode: string
    overviewKb: string
    legend: string
  }
  /**
   * 跨页面的零散文案：相对时间、缺省名、错误提示、反馈原因、落地页偏好选项。
   *
   * 这些字符串出现在 `lib/` 的纯函数里（没有组件上下文，拿不到 hook），
   * 所以单独成一个命名空间，由 lib 侧用 `readLocale()` 取当前语言 ——
   * 不给每个工具函数都加一个 locale 参数，避免改动几十个调用点。
   */
  common: {
    /* 相对时间（会话列表 / 文档列表 / 项目） */
    relJustNow: string
    relMinutes: (n: number) => string
    relHours: (n: number) => string
    relYesterday: string
    relDays: (n: number) => string
    relMonths: (n: number) => string
    relYears: (n: number) => string
    /* 缺省名（后端字段为空时的兜底，不是业务数据） */
    untitledSession: string
    unknownSource: string
    untitledProject: string
    defaultAgentName: string
    summaryEmpty: string
    /* 请求层错误提示 */
    errTimeout: string
    errNetwork: string
    errGeneric: string
    errGenerate: string
    /* 计数类说明 */
    kbCount: (n: number) => string
    docChunkDetail: (chunks: number, status: string) => string
    none: string
    /* 设置 → 账号偏好：落地页选项（存储值是与语言无关的 code） */
    landingWorkbench: string
    landingChat: string
    landingKb: string
    /* 回答反馈的原因标签（存储值仍是中文枚举，见 lib/feedback.ts） */
    feedbackReasons: {
      wrongFact: string
      wrongCite: string
      missedPoint: string
      verbose: string
      badFormat: string
      other: string
    }
  }
}

const ZH: PagesCopy = {
  workbench: {
    eyebrow: '工作台',
    title: '工作台',
    greeting: (name) => `下午好，${name}`,
    sub: '沉淀知识，与 AI 对话，构建你的 Agent —— 今天也继续推进吧。',
    startChat: '开始对话',
    quickTitle: '快捷开始',
    quickChat: '新建对话',
    quickChatDesc: '从一个问题开始，自动保存上下文',
    quickKb: '导入资料',
    quickKbDesc: '上传文档，切片入库后即可检索',
    quickAgent: '创建 Agent',
    quickAgentDesc: '绑定工具与知识库，配置专属助手',
    quickAutomation: '新建自动化',
    quickAutomationDesc: '定时运行任务，产出报告与提醒',
    statKb: '知识库',
    statKbSub: (n) => `共 ${n} 篇文档`,
    statDocs: '文档',
    statDocsSub: '已入库切片可检索',
    statConv: '会话',
    statConvSub: '含历史对话',
    statAgent: 'Agent',
    statAgentSub: '含默认助手',
    countsUnavailable: '统计暂时不可用：',
    trendTitle: '用量趋势',
    trendRange: '近 14 天',
    trendEmpty: '近 14 天暂无可展示的调用数据。',
    trendAria: '近 14 天调用次数趋势',
    trendHitAria: (label, n) => `${label}：${n} 次调用`,
    metricCalls: '次调用',
    metricAvg: '日均',
    metricPeak: '峰值',
    importsTitle: '最近文档处理',
    importCount: (n) => `${n} 条`,
    mockParsing: (pct, done, total) => `解析中 · ${pct}%（${done} / ${total} 切片）`,
    mockDone: (n) => `已完成 · ${n} 切片`,
    mockDemoHint: '离线演示模式：以下为示例数据，连接后端后显示真实处理状态',
    importsEmpty: '还没有导入文档。新建知识库并上传文件后，处理状态会显示在这里。',
    statusReady: '已就绪',
    statusFailed: '失败',
    statusProcessing: '处理中',
    openKb: '打开知识库',
    timelineTitle: '活动时间线',
    timelineRange: '最近 5 条',
    timelineEmpty: '最近没有活动，发起对话或导入文档后会显示在这里。',
    untitledConv: '未命名会话',
    activityImport: (label) => `导入 ${label}`,
    activityChat: (label) => `会话「${label}」`,
    activityImportDetail: '导入任务',
    activityChatDetail: '对话',
    featureTitle: '功能速览',
    carouselTitle: ['多知识库聚合', '流式对话', '自定义 Agent', '自有部署'],
    carouselDesc: [
      '向量 + BM25 + RRF 混合检索，一次提问横跨全部笔记。',
      '边生成边输出，可随时停止；引用自动标注来源文档。',
      '绑定知识库与工具，让 Agent 带着你的上下文干活。',
      '连接本地或自建的数据服务；模型角色由应用配置管理。',
    ],
    heatTitle: '活跃度',
    heatRange: '近 30 天',
    heatAria: '近 12 周活跃度',
    heatTotal: (n) => `共 ${n} 天`,
    heatActive: (n) => `活跃 ${n} 天`,
    heatLess: '少',
    heatMore: '多',
    recentTitle: '最近会话',
    recentAll: '全部',
    recentEmpty: '还没有会话，开始对话后会显示在这里。',
    manageKb: '管理知识库',
    browseDocs: '浏览文档',
  },
  agents: {
    eyebrow: 'Agent',
    title: '我的 Agent',
    sub: '把「知识和工具」打包成一个会干活的身份',
    newAgent: '新建 Agent',
    loading: '正在加载 Agent…',
    listFailPrefix: 'Agent 列表加载失败：',
    retry: '重试',
    emptyTitle: '还没有 Agent',
    emptyDesc: 'Agent 会带着你绑定的知识库与工具干活：面试陪练、代码审查、简历润色都可以做成一个 Agent。',
    boundKb: (kb) => `绑定 ${kb}`,
    test: '测试',
    editAria: '编辑',
    editTitle: '编辑名称与描述',
    duplicateAria: '复制',
    duplicateTitle: '复制为草稿副本',
    deleteAria: '删除',
    deleteTitle: '删除 Agent',
    benchTitle: '测试台',
    benchHint: '从上方选择一个 Agent 后在这里试跑；草稿状态也可以测试。',
    benchPh: '输入一条指令，例如：帮我出一道 JVM 调优题并讲解',
    benchInputAria: '测试指令',
    run: '运行',
    running: 'Agent 正在执行（工具调用中）',
    runDone: '执行完成',
    runFailed: '执行失败',
    mockTestOut: (name) => `Agent「${name}」：演示回答（mock 模式）`,
    mockTestTitle: '已完成本地演示',
    mockTestBody: '当前为 mock 模式，没有调用后端',
    testDoneTitle: 'Agent 执行完成',
    testToolsUsed: (n) => `调用工具 ${n} 次`,
    testNoTools: '本次未调用工具',
    testFailTitle: 'Agent 执行失败',
    confirmDelete: (name) => `确定删除 Agent「${name}」吗？此操作不可恢复。`,
    deletedTitle: '已删除 Agent',
    restoredTitle: '已恢复 Agent',
    deletedOkTitle: 'Agent 已删除',
    deleteFailTitle: '删除失败',
    copyName: (name) => `${name} 副本`,
    copiedTitle: '已复制 Agent',
    copiedMockName: (name) => `${name} 副本（草稿）`,
    copyFailTitle: '复制失败',
    errNameEmpty: '名称不能为空',
    savedTitle: '已保存修改',
    saveFailTitle: '保存失败',
    createTitle: '新建 Agent',
    creating: '正在创建…',
    stepBack: '上一步',
    stepNext: '下一步',
    stepFinal: '创建 Agent',
    errNameRequired: '请先填写名称',
    createdTitle: 'Agent 已创建',
    createdMockBody: 'mock 模式：未调用后端',
    createdRealBody: '初始状态为草稿，可先在测试台验证再发布',
    createFailTitle: '创建失败',
    nameLabel: '名称',
    namePh: '例如：代码审查员',
    nameAria: 'Agent 名称',
    descLabel: '描述',
    descPh: '这个 Agent 擅长做什么、以什么语气回答',
    descAria: 'Agent 描述',
    modelRoleHint: '模型角色由系统配置统一管理，当前版本不在创建向导中选择。',
    bindKbLabel: '绑定知识库',
    bindKbHint: '（不选 = 不限制，可检索你的全部知识库）',
    bindKbEmpty: '还没有可用的知识库；不选即表示不限制。',
    bindToolsLabel: '工具权限',
    bindToolsHint: '（不选 = 不限制，装配全部已启用工具）',
    bindToolsEmpty: '没有获取到工具目录；不选即表示不限制。',
    confirmIntro: '确认创建：创建后可在测试台先验证，再发布到会话中选用。',
    sumName: (v) => `名称：${v}`,
    sumKb: (v) => `知识库：${v}`,
    sumTools: (v) => `工具：${v}`,
    sumEmpty: '（未填写）',
    sumDemo: '演示选择',
    sumLimit: (n) => `限定 ${n} 个`,
    sumAll: '不限制',
    editTitleModal: '编辑 Agent',
    cancel: '取消',
    save: '保存',
    saving: '保存中…',
    iconLabel: '角色图标',
    visibilityLabel: '发布状态',
    visibilityDraft: '草稿',
    visibilityPublished: '已发布',
    draftHint: '创建后先保存为草稿，确认测试通过后再发布。',
    publishBtn: '发布',
    publishedBadge: '已发布',
    draftBadge: '草稿',
    publishDone: 'Agent 已发布',
    instructionsLabel: '系统指令',
    instructionsPh: '例如：先检索知识库，再以要点回答；不确定时明确说明。',
    instructionsHint: '指令会作为 Agent 的固定行为准则，在每轮对话前注入。',
    creativityLabel: '创造性',
    creativityHint: '数值越高回答越发散，越低越保守、越贴近知识库。',
    testStepTitle: '测试与发布',
    testStepHint: '先在这里跑一条真实指令，确认工具与知识库配置可用。',
    testRun: '运行测试',
    testRunning: '测试中…',
    testResultTitle: '测试结果',
    testResultEmpty: '还没有测试结果',
    examplePrompts: ['帮我出一道 JVM 调优题并讲解', '总结这篇文档的三个要点', '审查这段并发代码的风险'],
    benchExamples: '示例指令',
    benchTrace: '工具轨迹',
  },
  chat: {
    modePlain: '普通对话',
    modeRag: '知识库问答',
    modeAgent: 'Agent',
    toolStepSearch: '知识库检索',
    toolStepCalc: '数学计算',
    toolStepLabel: (name) => `调用工具 · ${name}`,
    stepGenerate: '生成回答',
    stepRetrieve: (hit) => `检索知识库 · 命中 ${hit} 个切片`,
    stepRetrievePartial: '部分知识库检索失败，已跳过',
    unknownSource: '未知来源',
    docFallback: '文档',
    emptyTitle: '还没有任何会话',
    emptyDesc: '从侧栏点「新对话」开一个，或先在知识库里准备资料。',
    newChat: '新对话',
    startTitle: '言之有据',
    startDesc: '每一句回答都能追回原文；对话留在当前会话，随时接着聊。',
    startersAria: '示例问题',
    starterOverview: (kb) => `《${kb}》里主要讲了什么？`,
    starterPitfalls: (kb) => `《${kb}》里有哪些容易踩的坑？`,
    starterTopic: (topic, kb) => `关于「${topic}」，《${kb}》里是怎么说的？`,
    starterNoKb: '还没有知识库——导入资料后，示例问题会按你的资料生成。',
    starterEmptyKb: '知识库还没有资料——先导入，示例问题才有东西可问。',
    starterListFailed: '知识库列表没加载出来，示例问题暂时不生成；可以直接提问。',
    starterGoImport: '去导入资料',
    metaLine: (n, updated) => `${n} 条消息 · ${updated}`,
    h1Title: (title) => `对话 · ${title}`,
    configTitle: '会话设置：模式 / Agent / 知识库',
    configAria: '会话设置',
    summaryRag: (n) => `知识库问答 · ${n}`,
    exportMd: '导出为 Markdown',
    modeLabel: '模式',
    agentLabel: 'Agent',
    agentNeedMode: '需切换到 Agent 模式',
    kbLabel: '知识库',
    kbNeedRag: '需切换到知识库问答',
    kbLoading: '正在加载…',
    kbEmptyBind: '还没有可绑定的知识库。',
    retry: '重试',
    saveResend: '保存并重发',
    cancel: '取消',
    toolCalls: '工具调用',
    viewCite: (name) => `在右侧查看「${name}」`,
    done: '已完成',
    doneWithCites: (n) => `引用 ${n} 篇`,
    copyMsg: '复制消息',
    editResend: '编辑并重新发送',
    branchFromAria: '从此处创建分支',
    continueGen: '继续生成',
    thinkingLabel: '正在生成回答',
    thinkingStopped: '已停止',
    thinkingSettled: '本轮已结束',
    composerPh: '输入你的问题…',
    expandAria: '放大输入框',
    expandTitle: '放大输入框',
    collapseAria: '退出放大',
    collapseTitle: '退出放大 (Esc)',
    hintKb: (agent, n) => `Agent：${agent} · 知识库 ${n} 个 · 输入`,
    hintAt: '引用数据源，',
    hintSlash: '使用快捷命令',
    loadFailTitle: '消息加载失败',
    genFailTitle: '生成失败',
    genFailBody: '（生成失败）',
    chatFailTitle: '对话失败',
    stoppedBody: '（已停止，本次回答不完整）',
    queuedTitle: '已加入队列',
    queuedBody: (n) => `当前回答结束后自动发送（第 ${n} 条）`,
    queueClearedTitle: '已清空待发送队列',
    queueClearedBody: (n) => `取消了 ${n} 条排队消息`,
    stopTitle: '已停止生成',
    stopBody: '已完成的部分保留，可继续追问或点「继续生成」',
    continueTitle: '已继续生成',
    continueBody: '补完了剩余部分',
    continuePrompt: '请接着上一条被中断的回答，从中断处继续输出剩余内容。',
    retryOkTitle: '工具调用已重试成功',
    retryOkBody: '这次没有报错',
    agentSwitched: '已切换 Agent',
    agentModeSwitched: '已切换到 Agent 模式并选定 Agent',
    noAgentTitle: '没有可用的 Agent',
    noAgentBody: '请先到 Agent 页面创建一个并发布，再回到这里选用。',
    modeNoAgentTitle: '还没有可用的 Agent',
    modeNoAgentBody: 'AGENT 模式需要一个已发布的 Agent。请先到 Agent 页面创建并发布，再切回这里；切换后可在顶部选择。',
    switchFailTitle: '切换失败',
    modeSwitchedTitle: '已切换模式',
    modeSwitchedMock: (from, to) => `${from} → ${to}（原型演示，未落库）`,
    modeSwitchedTo: (name) => `已切换到${name}模式`,
    cleanedAgent: 'Agent 绑定已清除',
    cleanedKb: '知识库绑定已清除',
    modeSwitchedBody: (items) => `${items.join('，')}。历史回答保持原样，新回答按当前模式执行。`,
    historyKept: '历史回答保持原样，新回答按当前模式执行。',
    archivedTitle: '会话已归档',
    archivedBody: '可在侧栏「已归档」中找回',
    restoredTitle: '会话已恢复',
    restoredBody: '已回到最近会话',
    branchMockTitle: '已在新对话中分支',
    branchMockBody: '原会话保持不变，可在新会话里换个问法继续',
    branchTempTitle: '当前会话还没落库',
    branchTempBody: '先发一条消息创建服务端会话后，才能创建持久化分支。',
    branchCreatedTitle: '已创建分支对话',
    branchCreatedBody: (title) => `${title}：原会话保持不变`,
    branchFailTitle: '创建分支失败',
    defaultTitle: '新会话',
    justNow: '刚刚',
    createdTitle: '会话已创建',
    createdBody: (mode) => `${mode}模式`,
    createFailTitle: '会话创建失败',
    copyDenied: '当前浏览器未授权剪贴板访问。',
    copyMsgToast: '已复制消息',
    copyFailTitle: '复制失败',
    listSep: '、',
    exportRoleSep: '：',
    exportAgent: (name) => `- Agent：${name}`,
    exportKb: (names) => `- 知识库：${names}`,
    exportKbNone: '未绑定',
    exportRoleUser: '我',
    exportRoleAi: 'AI',
    exportCites: (list) => `引用：${list}`,
    kbModeTitle: '当前模式不支持绑定知识库',
    kbModeBody: '请先切换到「知识库问答」模式。',
    kbBindFailTitle: '知识库绑定失败',
    kbSourceDesc: '当前会话绑定的知识库',
    riskExplain: '这一步会在服务端沙箱里执行一段代码来验证复杂度结论。沙箱与生产隔离，但会消耗执行配额。',
    riskFallback: '这一步会执行一个高风险操作，需要你确认后再继续。',
    wizardTitle: '新建会话',
    wizardModeLabel: '选择对话模式',
    wizardModeAria: '新会话模式',
    wizardModeHint: '普通对话不绑定资料；知识库问答使用所选知识库；Agent 模式使用已发布 Agent 的配置。',
    wizardAgentLabel: '选择 Agent',
    wizardKbLabel: '绑定知识库（可多选）',
    wizardKbLoading: '正在加载你的知识库…',
    wizardKbEmpty: '还没有知识库。你可以先创建空会话，再到知识库页面创建并导入资料。',
    wizardConfirm: (mode) => `确认创建「${mode}」会话。`,
    wizardBindCount: (n) => `将绑定 ${n} 个知识库。`,
    wizardUseAgent: (name) => `将使用 Agent「${name}」。`,
    wizardPlainReady: '会话创建后可以立即提问。',
    wizardCreating: '正在创建会话…',
    wizardCreate: '创建会话',
    stepBack: '上一步',
    stepNext: '继续',
    refSourceFallback: '引用来源',
    refNoIdError: '这条历史引用未包含知识库与文档 ID，无法读取原文。',
    refAria: '引用详情',
    refCloseAria: '关闭引用面板',
    refOpenInKb: '在知识库中打开',
    refGoKb: '前往对应知识库',
    refLoading: '正在读取引用来源…',
    refHitTitle: '命中片段',
    refScoreSuffix: (score) => ` · 相似度 ${(score * 100).toFixed(1)}%`,
    refDocTitle: '文档内容',
    refNoSnippet: '服务端未提供这条引用的正文片段。',
    kindTable: '表格',
    kindJson: 'JSON',
    kindChart: '图表',
    csvLabelCol: '标签',
    csvValueCol: '数值',
    resultAria: (title, kind) => `结果：${title}（${kind}）`,
    copyResultAria: '复制结果',
    downloadResultAria: '下载结果',
    copiedBtn: '已复制',
    copyBtn: '复制',
    downloadBtn: '下载',
    ctxAria: '本轮上下文',
    ctxKb: (n) => `知识库 ${n}`,
    ctxHitTitle: '在绑定的知识库里检索到的切片数',
    ctxHit: (n) => `命中 ${n} 切片`,
    ctxCiteTitle: '查看引用的文档',
    ctxCite: (used, scanned) => (scanned > 0 ? `引用 ${used} 篇 / 共 ${scanned} 篇` : `引用 ${used} 篇`),
    ctxHistoryTitle: '带到这一轮的历史轮数',
    ctxHistory: (n) => `历史 ${n} 轮`,
    ctxWebOnTitle: '本轮允许联网检索',
    ctxWebOffTitle: '本轮未联网',
    ctxWebOn: '已联网',
    ctxWebOff: '未联网',
    ctxAttach: (n) => `附件 ${n}`,
    ctxTruncatedTitle: '超出上下文窗口，早期对话已被省略',
    ctxTruncated: (n) => `已省略早期 ${n} 轮`,
    fbRecorded: '已记录',
    fbUndo: '撤销',
    fbAria: '这条回答有帮助吗',
    fbHelpful: '有帮助',
    fbNotHelpful: '没答好',
    fbReasons: ['没答到点', '引用不对', '事实错误', '太啰嗦', '格式差', '其他'],
    confirmTitle: '这一步需要你确认',
    confirmAria: (name) => `需要确认：${name}`,
    confirmAllowOnce: '允许一次',
    confirmAllowAlways: '始终允许',
    confirmDeny: '拒绝',
    queueAria: (n) => `待发送队列，共 ${n} 条`,
    queueHead: (n) => `排队中 ${n}`,
    queueRemoveAria: (n) => `移除排队第 ${n} 条`,
    queueClear: '清空',
    railAria: '提问导航',
    railItemAria: (n) => `第 ${n} 个提问`,
    previewAria: '本次对话的问题导航',
    previewJumpAria: (n, summary) => `跳转到第 ${n} 个提问：${summary}`,
    mockArgComplexity: '复杂度过一遍',
    mockArgDocs: '官方文档最新版本',
  },
  plugins: {
    eyebrow: 'Extensions',
    title: '插件市场',
    sub: '安装技能、工具与 MCP 服务，扩展知识库和 Agent 的能力边界。',
    importSkill: '导入技能',
    searchPh: '搜索插件或技能',
    searchAria: '搜索插件或技能',
    tabInstalled: '已安装',
    tabMarket: '插件市场',
    tabMcp: 'MCP 服务',
    featured: '推荐',
    categoryAll: '全部',
    categoryLabels: {
      agent: 'Agent',
      knowledge: '知识库',
      search: '检索',
      automation: '自动化',
      productivity: '效率',
      data: '数据',
      skill: '技能',
    },
    install: '安装',
    installed: '已安装',
    uninstall: '卸载',
    configure: '配置',
    details: '详情',
    version: '版本',
    byAuthor: (author) => `作者：${author}`,
    rating: '评分',
    downloads: '下载',
    tools: '工具',
    verified: '官方认证',
    official: '官方',
    updated: '更新',
    installedEmptyTitle: '还没有安装插件',
    installedEmptyDesc: '去插件市场挑选能力，或从本地导入 SKILL.md。',
    marketEmpty: '当前分类下没有插件',
    noResults: (q) => `没有匹配「${q}」的插件`,
    importTitle: '导入技能',
    importDesc: '粘贴 SKILL.md 或技能清单 JSON，解析后加入已安装列表。',
    importPh: '---\nname: my-skill\ndescription: 描述这个技能\n---',
    importAria: '技能内容',
    importBrowse: '选择文件',
    importBrowseAria: '选择技能文件',
    importDropHint: '支持 .md 或 .json，解析 frontmatter 与 JSON 清单',
    importBtn: '导入',
    importDone: '技能已导入',
    importFail: '导入失败',
    importInvalid: '没有识别到技能名称，请检查 frontmatter 或 JSON 结构。',
    skillNameUnknown: '未命名技能',
    mcpSub: '连接 MCP 服务器，把外部工具注册进 Agent 工具箱。',
    mcpConnect: '连接',
    mcpDisconnect: '断开',
    mcpConfigure: '配置',
    mcpEndpoint: '端点',
    mcpTools: (n) => `${n} 个工具`,
    mcpConnected: '已连接',
    mcpOffline: '未连接',
    mcpModalTitle: '配置 MCP 服务',
    mcpNameLabel: '服务名称',
    mcpEndpointLabel: '服务端点',
    mcpTokenLabel: '访问令牌（可选）',
    mcpTokenPh: 'Bearer token',
    mcpToolsLabel: '启用工具',
    mcpSave: '保存配置',
    mcpSavedTitle: 'MCP 配置已保存',
    mcpSavedBody: '更改会立即应用到新会话。',
    mcpConnectedTitle: '已连接 MCP 服务',
    mcpDisconnectedTitle: '已断开 MCP 服务',
    detailOverview: '概览',
    detailTools: '工具清单',
    detailCompat: '兼容性',
    detailCompatBody: '适用于 Nexus 工作台与知识库问答；MCP 插件需要网络可达的服务端点。',
    detailTags: '标签',
  },
  community: {
    eyebrow: 'Agent Hub',
    title: 'Agent 社区',
    sub: '发现、试用并发布 Agent，把别人的配置一键带进自己的工作流。',
    publish: '发布 Agent',
    searchPh: '搜索 Agent、作者或标签',
    searchAria: '搜索 Agent 社区',
    tabDiscover: '发现',
    tabMine: '我的发布',
    featuredLabel: '本周推荐',
    use: '使用',
    installed: '已使用',
    detail: '详情',
    authorYou: '我',
    republish: '发布',
    sendReview: '发送评测',
    reviewDoneTitle: '评测已提交',
    reviewDoneBody: '感谢你的反馈，发布者会看到这条评测。',
    downloads: '下载',
    likes: '喜欢',
    rating: '评分',
    reviews: '评测',
    version: '版本',
    byAuthor: (author) => `作者：${author}`,
    updated: '更新',
    verified: '官方认证',
    categoryAll: '全部分类',
    categoryLabels: {
      writing: '写作',
      coding: '编码',
      research: '研究',
      knowledge: '知识库',
      business: '商业',
      education: '教育',
      data: '数据',
    },
    sortPopular: '最受欢迎',
    sortNewest: '最新发布',
    sortRating: '评分最高',
    emptyTitle: '还没有可展示的 Agent',
    emptyDesc: '调整筛选条件，或发布一个自己的 Agent。',
    noResults: (q) => `没有匹配「${q}」的 Agent`,
    publishTitle: '发布 Agent',
    publishSub: '配置发布信息与能力清单，可先保存为草稿。',
    stepBasic: '基本信息',
    stepCapability: '能力配置',
    stepPublish: '发布设置',
    back: '上一步',
    next: '下一步',
    create: '发布',
    cancel: '取消',
    nameLabel: '名称',
    namePh: '例如：面试陪练官',
    nameAria: 'Agent 名称',
    descLabel: '简介',
    descPh: '一句话说明这个 Agent 擅长什么',
    descAria: 'Agent 简介',
    categoryLabel: '分类',
    tagsLabel: '标签',
    tagsPh: '逗号分隔，例如 Java, Interview',
    tagsAria: '标签',
    modelLabel: '模型',
    versionLabel: '版本',
    visibilityLabel: '可见性',
    statusPublished: '已发布',
    statusDraft: '草稿',
    statusUnlisted: '不列出',
    visibilityPublic: '公开',
    visibilityPublicHint: '所有用户都能发现与安装。',
    visibilityDraft: '草稿',
    visibilityDraftHint: '仅自己可见，发布前可继续编辑。',
    visibilityUnlisted: '不列出',
    visibilityUnlistedHint: '不进入广场，但持有链接的用户可用。',
    toolsLabel: '工具权限',
    readmeLabel: '说明文档',
    readmePh: '介绍用途、场景与示例对话…',
    publishDoneTitle: '已发布到社区',
    publishDoneBody: '可在“我的发布”中继续编辑或调整可见性。',
    myEmptyTitle: '还没有发布过 Agent',
    myEmptyDesc: '把测试通过的配置发布到社区，让更多人使用。',
    edit: '编辑',
    unpublish: '取消发布',
    delete: '删除',
    deleteConfirm: (name) => `确定删除「${name}」吗？删除后不可恢复。`,
    deletedTitle: '已删除发布',
    noReviews: '还没有评测，成为第一个给出反馈的人。',
    writeReview: '写评测',
    sampleReviewers: ['Lin', '小周', 'Alex'],
    sampleReviewMeta: ['昨天', '3 天前', '上周'],
    sampleReviewTexts: [
      '回答结构清晰，追问节奏自然，配合个人知识库效果更好。',
      '配置可以直接用，省掉了很多重复编排。',
      '工具调用透明，失败时也能看到具体原因。',
    ],
    overviewTitle: '概览',
    toolsTitle: '工具清单',
    reviewsTitle: '评测',
    readmeTitle: '说明文档',
  },
  automations: {
    eyebrow: 'Automations',
    title: '自动化',
    sub: '让 Agent 按计划运行：定时汇总、监控变化、生成报告。',
    newAutomation: '新建自动化',
    searchPh: '搜索自动化任务',
    searchAria: '搜索自动化任务',
    tabAll: '全部',
    tabEnabled: '运行中',
    tabPaused: '已暂停',
    emptyTitle: '还没有自动化任务',
    emptyDesc: '创建定时任务，让 Agent 在后台持续为你工作。',
    noResults: (q) => `没有匹配「${q}」的自动化`,
    enabled: '运行中',
    disabled: '已暂停',
    scheduleHourly: '每小时',
    scheduleDaily: '每天',
    scheduleWeekly: '每周',
    scheduleCustom: '自定义',
    agentLabel: '执行 Agent',
    kbLabel: '知识库',
    lastRun: '上次运行',
    nextRun: '下次运行',
    never: '从未',
    runNow: '立即运行',
    history: '运行历史',
    edit: '编辑',
    delete: '删除',
    deleteConfirm: (name) => `确定删除自动化「${name}」吗？`,
    createTitle: '新建自动化',
    editTitle: '编辑自动化',
    nameLabel: '任务名称',
    namePh: '例如：每日知识库简报',
    promptLabel: '任务指令',
    promptPh: '用一句话描述每次运行时 Agent 要做什么…',
    scheduleLabel: '运行频率',
    notifyLabel: '完成后通知我',
    notifyHint: '运行失败或产出报告时在通知中心提醒。',
    cancel: '取消',
    save: '保存',
    createdTitle: '自动化已创建',
    updatedTitle: '自动化已更新',
    deletedTitle: '自动化已删除',
    queuedTitle: '已加入运行队列',
    queuedBody: (name) => `${name} 将在后台运行`,
    historyTitle: '运行历史',
    historyEmpty: '还没有运行记录',
    statusDone: '成功',
    statusFailed: '失败',
    statusQueued: '排队中',
  },
  trace: {
    viewChat: '对话',
    viewTrace: '轨迹',
    duration: '时长',
    turns: '轮次',
    calls: '调用',
    searchPh: '搜索轨迹',
    searchAria: '搜索轨迹',
    input: '输入',
    model: '模型',
    tools: '工具',
    empty: '当前会话还没有可显示的轨迹。',
    turn: (n) => `第 ${n} 轮`,
    roleUser: '用户',
    roleAssistant: '助手',
    roleTool: '工具',
    toolOnly: '（仅工具调用）',
    statusRunning: '运行中',
    statusDone: '已完成',
    statusFailed: '失败',
    statusTimeout: '超时',
    statusCancelled: '已停止',
    statusAwaiting: '等待确认',
    overview: '概览',
    overviewAgent: 'Agent',
    overviewModel: '模型',
    overviewMode: '模式',
    overviewKb: '知识库',
    legend: '图例',
  },
  common: {
    relJustNow: '刚刚',
    relMinutes: (n) => `${n} 分钟前`,
    relHours: (n) => `${n} 小时前`,
    relYesterday: '昨天',
    relDays: (n) => `${n} 天前`,
    relMonths: (n) => `${n} 个月前`,
    relYears: (n) => `${n} 年前`,
    untitledSession: '未命名会话',
    unknownSource: '未知来源',
    untitledProject: '未命名项目',
    defaultAgentName: '默认助手',
    summaryEmpty: '（暂无文本内容）',
    errTimeout: '请求超时，请检查后端是否在运行',
    errNetwork: '连不上后端服务，请确认 8080 端口的进程已启动',
    errGeneric: '请求失败，请稍后重试',
    errGenerate: '生成失败',
    kbCount: (n) => `${n} 个知识库`,
    docChunkDetail: (chunks, status) => `${chunks} 个切片 · ${status}`,
    none: '无',
    landingWorkbench: '工作台',
    landingChat: '对话',
    landingKb: '知识库',
    feedbackReasons: {
      wrongFact: '事实错误',
      wrongCite: '引用不对',
      missedPoint: '没答到点',
      verbose: '太啰嗦',
      badFormat: '格式差',
      other: '其他',
    },
  },
}

const EN: PagesCopy = {
  workbench: {
    eyebrow: 'Workbench',
    title: 'Workbench',
    greeting: (name) => `Good afternoon, ${name}`,
    sub: 'Accumulate knowledge, talk with AI, and build your agents — keep moving today.',
    startChat: 'Start chatting',
    quickTitle: 'Quick start',
    quickChat: 'New chat',
    quickChatDesc: 'Start from a question with saved context',
    quickKb: 'Import material',
    quickKbDesc: 'Upload docs, chunk and index them for search',
    quickAgent: 'Create an agent',
    quickAgentDesc: 'Bind tools and knowledge for a dedicated assistant',
    quickAutomation: 'New automation',
    quickAutomationDesc: 'Run scheduled tasks that produce reports and alerts',
    statKb: 'Knowledge bases',
    statKbSub: (n) => `${n} docs in total`,
    statDocs: 'Documents',
    statDocsSub: 'Indexed and searchable',
    statConv: 'Chats',
    statConvSub: 'Including history',
    statAgent: 'Agents',
    statAgentSub: 'Including the default assistant',
    countsUnavailable: 'Stats temporarily unavailable: ',
    trendTitle: 'Usage trend',
    trendRange: 'Last 14 days',
    trendEmpty: 'No call data to show for the last 14 days.',
    trendAria: 'Call volume trend over the last 14 days',
    trendHitAria: (label, n) => `${label}: ${n} calls`,
    metricCalls: 'calls',
    metricAvg: 'Daily avg',
    metricPeak: 'Peak',
    importsTitle: 'Recent document processing',
    importCount: (n) => `${n} items`,
    mockParsing: (pct, done, total) => `Parsing · ${pct}% (${done} / ${total} chunks)`,
    mockDone: (n) => `Done · ${n} chunks`,
    mockDemoHint: 'Offline demo mode: the rows below are sample data. Connect a backend to see real processing status.',
    importsEmpty: 'No documents imported yet. Create a knowledge base and upload files — their processing status shows up here.',
    statusReady: 'Ready',
    statusFailed: 'Failed',
    statusProcessing: 'Processing',
    openKb: 'Open knowledge base',
    timelineTitle: 'Activity timeline',
    timelineRange: 'Latest 5',
    timelineEmpty: 'No recent activity. Start a chat or import a document and it will appear here.',
    untitledConv: 'Untitled chat',
    activityImport: (label) => `Import ${label}`,
    activityChat: (label) => `Chat “${label}”`,
    activityImportDetail: 'Import task',
    activityChatDetail: 'Chat',
    featureTitle: 'Feature overview',
    carouselTitle: ['Multi-library aggregation', 'Streaming chat', 'Custom agents', 'Self-hosted'],
    carouselDesc: [
      'Hybrid vector + BM25 + RRF retrieval — one question spans every note.',
      'Output streams as it generates and can be stopped anytime; citations point at their source document.',
      'Bind knowledge bases and tools so agents work with your own context.',
      'Connect a local or self-built data service; model roles are managed by app configuration.',
    ],
    heatTitle: 'Activity',
    heatRange: 'Last 30 days',
    heatAria: 'Activity over the last 12 weeks',
    heatTotal: (n) => `${n} days`,
    heatActive: (n) => `${n} active days`,
    heatLess: 'Less',
    heatMore: 'More',
    recentTitle: 'Recent chats',
    recentAll: 'All',
    recentEmpty: 'No chats yet. Start one and it will show up here.',
    manageKb: 'Manage knowledge',
    browseDocs: 'Browse docs',
  },
  agents: {
    eyebrow: 'Agents',
    title: 'My agents',
    sub: 'Package “knowledge and tools” into an identity that does the work',
    newAgent: 'New agent',
    loading: 'Loading agents…',
    listFailPrefix: 'Failed to load agents: ',
    retry: 'Retry',
    emptyTitle: 'No agents yet',
    emptyDesc: 'An agent works with the knowledge bases and tools you bind to it: mock interviews, code review, resume polishing — all can become an agent.',
    boundKb: (kb) => `Bound: ${kb}`,
    test: 'Test',
    editAria: 'Edit',
    editTitle: 'Edit name and description',
    duplicateAria: 'Duplicate',
    duplicateTitle: 'Duplicate as a draft copy',
    deleteAria: 'Delete',
    deleteTitle: 'Delete agent',
    benchTitle: 'Test bench',
    benchHint: 'Pick an agent above to try it here — drafts can be tested too.',
    benchPh: 'Enter an instruction, e.g. give me a JVM tuning question and explain it',
    benchInputAria: 'Test instruction',
    run: 'Run',
    running: 'Agent is running (calling tools)',
    runDone: 'Run finished',
    runFailed: 'Run failed',
    mockTestOut: (name) => `Agent “${name}”: 演示回答（mock 模式）`,
    mockTestTitle: 'Local demo finished',
    mockTestBody: 'Mock mode — no backend call',
    testDoneTitle: 'Agent run finished',
    testToolsUsed: (n) => `Called ${n} ${n === 1 ? 'tool' : 'tools'}`,
    testNoTools: 'No tools were called this time',
    testFailTitle: 'Agent run failed',
    confirmDelete: (name) => `Delete agent “${name}”? This cannot be undone.`,
    deletedTitle: 'Agent deleted',
    restoredTitle: 'Agent restored',
    deletedOkTitle: 'Agent deleted',
    deleteFailTitle: 'Delete failed',
    copyName: (name) => `${name} copy`,
    copiedTitle: 'Agent duplicated',
    copiedMockName: (name) => `${name} copy (draft)`,
    copyFailTitle: 'Duplicate failed',
    errNameEmpty: 'Name is required',
    savedTitle: 'Changes saved',
    saveFailTitle: 'Save failed',
    createTitle: 'New agent',
    creating: 'Creating…',
    stepBack: 'Back',
    stepNext: 'Next',
    stepFinal: 'Create agent',
    errNameRequired: 'Please enter a name first',
    createdTitle: 'Agent created',
    createdMockBody: 'Mock mode — no backend call',
    createdRealBody: 'It starts as a draft: verify it on the test bench before publishing',
    createFailTitle: 'Create failed',
    nameLabel: 'Name',
    namePh: 'e.g. Code reviewer',
    nameAria: 'Agent name',
    descLabel: 'Description',
    descPh: 'What this agent is good at, and the tone it answers in',
    descAria: 'Agent description',
    modelRoleHint: 'Model roles are managed centrally by system configuration and cannot be picked in this wizard.',
    bindKbLabel: 'Bind knowledge bases',
    bindKbHint: ' (none selected = no limit, searches all your knowledge bases)',
    bindKbEmpty: 'No knowledge base available yet; selecting none means no limit.',
    bindToolsLabel: 'Tool permissions',
    bindToolsHint: ' (none selected = no limit, mounts every enabled tool)',
    bindToolsEmpty: 'The tool catalogue could not be loaded; selecting none means no limit.',
    confirmIntro: 'Confirm creation: verify it on the test bench first, then publish it for chats to use.',
    sumName: (v) => `Name: ${v}`,
    sumKb: (v) => `Knowledge bases: ${v}`,
    sumTools: (v) => `Tools: ${v}`,
    sumEmpty: '(not filled in)',
    sumDemo: 'demo selection',
    sumLimit: (n) => `${n} selected`,
    sumAll: 'no limit',
    editTitleModal: 'Edit agent',
    cancel: 'Cancel',
    save: 'Save',
    saving: 'Saving…',
    iconLabel: 'Icon',
    visibilityLabel: 'Publish state',
    visibilityDraft: 'Draft',
    visibilityPublished: 'Published',
    draftHint: 'It is created as a draft; publish it after the test passes.',
    publishBtn: 'Publish',
    publishedBadge: 'Published',
    draftBadge: 'Draft',
    publishDone: 'Agent published',
    instructionsLabel: 'System instructions',
    instructionsPh: 'e.g. Search the knowledge base first, then answer in bullet points; say when unsure.',
    instructionsHint: 'Instructions are injected as fixed behaviour before every turn.',
    creativityLabel: 'Creativity',
    creativityHint: 'Higher is more exploratory; lower stays conservative and grounded.',
    testStepTitle: 'Test & publish',
    testStepHint: 'Run one real instruction to confirm tools and knowledge bases work.',
    testRun: 'Run test',
    testRunning: 'Testing…',
    testResultTitle: 'Test result',
    testResultEmpty: 'No test result yet',
    examplePrompts: ['Give me a JVM tuning question with explanation', 'Summarise the three key points of this doc', 'Review the risks in this concurrent code'],
    benchExamples: 'Examples',
    benchTrace: 'Tool trace',
  },
  chat: {
    modePlain: 'Plain chat',
    modeRag: 'Knowledge Q&A',
    modeAgent: 'Agent',
    toolStepSearch: 'Knowledge search',
    toolStepCalc: 'Calculator',
    toolStepLabel: (name) => `Calling tool · ${name}`,
    stepGenerate: 'Generating the answer',
    stepRetrieve: (hit) => `Searching knowledge base · ${hit} ${hit === 1 ? 'chunk' : 'chunks'} hit`,
    stepRetrievePartial: 'Some knowledge bases failed to search and were skipped',
    unknownSource: 'Unknown source',
    docFallback: 'Document',
    emptyTitle: 'No chats yet',
    emptyDesc: 'Create one from “New chat” in the sidebar, or prepare some material in a knowledge base first.',
    newChat: 'New chat',
    startTitle: 'Every word, sourced',
    startDesc: 'Each answer traces back to the source; this chat stays here for you to pick up anytime.',
    startersAria: 'Example questions',
    starterOverview: (kb) => `What does “${kb}” cover?`,
    starterPitfalls: (kb) => `What are the common pitfalls covered in “${kb}”?`,
    starterTopic: (topic, kb) => `What does “${kb}” say about “${topic}”?`,
    starterNoKb: 'No knowledge base yet — once you import material, examples are generated from it.',
    starterEmptyKb: 'Your knowledge base has no material yet — import something and examples will follow.',
    starterListFailed: 'The knowledge base list could not be loaded, so no examples are shown; you can still ask directly.',
    starterGoImport: 'Import material',
    metaLine: (n, updated) => `${n} ${n === 1 ? 'message' : 'messages'} · ${updated}`,
    h1Title: (title) => `Chat · ${title}`,
    configTitle: 'Chat settings: mode / agent / knowledge base',
    configAria: 'Chat settings',
    summaryRag: (n) => `Knowledge Q&A · ${n}`,
    exportMd: 'Export as Markdown',
    modeLabel: 'Mode',
    agentLabel: 'Agent',
    agentNeedMode: 'Switch to Agent mode first',
    kbLabel: 'Knowledge base',
    kbNeedRag: 'Switch to Knowledge Q&A first',
    kbLoading: 'Loading…',
    kbEmptyBind: 'No knowledge base to bind yet.',
    retry: 'Retry',
    saveResend: 'Save and resend',
    cancel: 'Cancel',
    toolCalls: 'Tool calls',
    viewCite: (name) => `View “${name}” on the right`,
    done: 'Done',
    /* 不含前导分隔符（" · " 由 JSX 文本提供） */
    doneWithCites: (n) => `${n} ${n === 1 ? 'citation' : 'citations'}`,
    copyMsg: 'Copy message',
    editResend: 'Edit and resend',
    branchFromAria: 'Branch from here',
    continueGen: 'Continue generating',
    thinkingLabel: 'Generating the answer',
    thinkingStopped: 'Stopped',
    thinkingSettled: 'Round finished',
    composerPh: 'Type your question…',
    expandAria: 'Expand the composer',
    expandTitle: 'Expand the composer',
    collapseAria: 'Exit expanded mode',
    collapseTitle: 'Exit expanded mode (Esc)',
    hintKb: (agent, n) => `Agent: ${agent} · ${n} ${n === 1 ? 'knowledge base' : 'knowledge bases'} · Type`,
    hintAt: 'to cite a source,',
    hintSlash: 'for quick commands',
    loadFailTitle: 'Failed to load messages',
    genFailTitle: 'Generation failed',
    genFailBody: '(generation failed)',
    chatFailTitle: 'Chat failed',
    stoppedBody: '(stopped — this answer is incomplete)',
    queuedTitle: 'Added to the queue',
    queuedBody: (n) => `Sent automatically when the current answer finishes (item ${n})`,
    queueClearedTitle: 'Send queue cleared',
    queueClearedBody: (n) => `${n} queued ${n === 1 ? 'message' : 'messages'} cancelled`,
    stopTitle: 'Generation stopped',
    stopBody: 'What was finished is kept — follow up or click “Continue generating”',
    continueTitle: 'Generation continued',
    continueBody: 'The remaining part is filled in',
    continuePrompt: 'Please continue the interrupted answer from where it stopped and output the rest.',
    retryOkTitle: 'Tool call retried successfully',
    retryOkBody: 'No error this time',
    agentSwitched: 'Agent switched',
    agentModeSwitched: 'Switched to Agent mode and selected the agent',
    noAgentTitle: 'No agent available',
    noAgentBody: 'Create and publish one on the Agents page first, then come back and pick it.',
    modeNoAgentTitle: 'No agent available yet',
    modeNoAgentBody: 'Agent mode needs a published agent. Create and publish one on the Agents page first, then switch back; you can pick it at the top afterwards.',
    switchFailTitle: 'Switch failed',
    modeSwitchedTitle: 'Mode switched',
    modeSwitchedMock: (from, to) => `${from} → ${to} (prototype demo, not persisted)`,
    modeSwitchedTo: (name) => `Switched to ${name} mode`,
    cleanedAgent: 'Agent binding cleared',
    cleanedKb: 'Knowledge base binding cleared',
    modeSwitchedBody: (items) => `${items.join(', ')}. Past answers stay as they are; new answers follow the current mode.`,
    historyKept: 'Past answers stay as they are; new answers follow the current mode.',
    archivedTitle: 'Chat archived',
    archivedBody: 'Find it under “Archived” in the sidebar',
    restoredTitle: 'Chat restored',
    restoredBody: 'Back in your recent chats',
    branchMockTitle: 'Branched into a new chat',
    branchMockBody: 'The original chat stays untouched — try another phrasing in the new one',
    branchTempTitle: 'This chat is not saved yet',
    branchTempBody: 'Send a message first to create the chat on the server, then a persistent branch is possible.',
    branchCreatedTitle: 'Branch chat created',
    branchCreatedBody: (title) => `${title}: the original chat stays untouched`,
    branchFailTitle: 'Failed to create the branch',
    defaultTitle: 'New chat',
    justNow: 'just now',
    createdTitle: 'Chat created',
    createdBody: (mode) => `${mode} mode`,
    createFailTitle: 'Failed to create the chat',
    copyDenied: 'This browser has not granted clipboard access.',
    copyMsgToast: 'Message copied',
    copyFailTitle: 'Copy failed',
    listSep: ', ',
    exportRoleSep: ': ',
    exportAgent: (name) => `- Agent: ${name}`,
    exportKb: (names) => `- Knowledge base: ${names}`,
    exportKbNone: 'none',
    exportRoleUser: 'Me',
    exportRoleAi: 'AI',
    exportCites: (list) => `Citations: ${list}`,
    kbModeTitle: 'The current mode does not support binding a knowledge base',
    kbModeBody: 'Switch to “Knowledge Q&A” mode first.',
    kbBindFailTitle: 'Failed to bind the knowledge base',
    kbSourceDesc: 'Knowledge base bound to this chat',
    riskExplain: 'This step runs a snippet of code in the server sandbox to verify the complexity conclusion. The sandbox is isolated from production, but it consumes execution quota.',
    riskFallback: 'This step performs a high-risk operation — confirm before continuing.',
    wizardTitle: 'New chat',
    wizardModeLabel: 'Choose a chat mode',
    wizardModeAria: 'New chat mode',
    wizardModeHint: 'Plain chat binds no material; Knowledge Q&A uses the selected knowledge bases; Agent mode uses the published agent configuration.',
    wizardAgentLabel: 'Choose an agent',
    wizardKbLabel: 'Bind knowledge bases (multi-select)',
    wizardKbLoading: 'Loading your knowledge bases…',
    wizardKbEmpty: 'No knowledge base yet. You can create an empty chat first, then create one on the knowledge page and import material.',
    wizardConfirm: (mode) => `Create a “${mode}” chat?`,
    wizardBindCount: (n) => `${n} ${n === 1 ? 'knowledge base' : 'knowledge bases'} will be bound.`,
    wizardUseAgent: (name) => `Agent “${name}” will be used.`,
    wizardPlainReady: 'You can start asking as soon as the chat is created.',
    wizardCreating: 'Creating the chat…',
    wizardCreate: 'Create chat',
    stepBack: 'Back',
    stepNext: 'Continue',
    refSourceFallback: 'Cited source',
    refNoIdError: 'This older citation has no knowledge base or document ID, so the source cannot be loaded.',
    refAria: 'Citation details',
    refCloseAria: 'Close citation panel',
    refOpenInKb: 'Open in knowledge base',
    refGoKb: 'Go to knowledge base',
    refLoading: 'Loading the cited source…',
    refHitTitle: 'Matched snippet',
    refScoreSuffix: (score) => ` · Similarity ${(score * 100).toFixed(1)}%`,
    refDocTitle: 'Document content',
    refNoSnippet: 'The server did not return a snippet for this citation.',
    kindTable: 'Table',
    kindJson: 'JSON',
    kindChart: 'Chart',
    csvLabelCol: 'Label',
    csvValueCol: 'Value',
    resultAria: (title, kind) => `Result: ${title} (${kind})`,
    copyResultAria: 'Copy result',
    downloadResultAria: 'Download result',
    copiedBtn: 'Copied',
    copyBtn: 'Copy',
    downloadBtn: 'Download',
    ctxAria: 'Context for this round',
    ctxKb: (n) => `Knowledge base ${n}`,
    ctxHitTitle: 'Chunks retrieved from the bound knowledge bases',
    ctxHit: (n) => `${n} ${n === 1 ? 'chunk' : 'chunks'} hit`,
    ctxCiteTitle: 'View the cited documents',
    ctxCite: (used, scanned) => (scanned > 0
      ? `${used} of ${scanned} ${scanned === 1 ? 'document' : 'documents'} cited`
      : `${used} ${used === 1 ? 'document' : 'documents'} cited`),
    ctxHistoryTitle: 'History turns carried into this round',
    ctxHistory: (n) => `History ${n} ${n === 1 ? 'turn' : 'turns'}`,
    ctxWebOnTitle: 'Web search allowed this round',
    ctxWebOffTitle: 'No web search this round',
    ctxWebOn: 'Online',
    ctxWebOff: 'Offline',
    ctxAttach: (n) => `${n} ${n === 1 ? 'attachment' : 'attachments'}`,
    ctxTruncatedTitle: 'Over the context window — earlier turns were dropped',
    ctxTruncated: (n) => `Dropped ${n} early ${n === 1 ? 'turn' : 'turns'}`,
    fbRecorded: 'Recorded',
    fbUndo: 'Undo',
    fbAria: 'Was this answer helpful?',
    fbHelpful: 'Helpful',
    fbNotHelpful: 'Not helpful',
    fbReasons: ['Off target', 'Wrong citation', 'Factually wrong', 'Too verbose', 'Bad format', 'Other'],
    confirmTitle: 'This step needs your confirmation',
    confirmAria: (name) => `Confirmation needed: ${name}`,
    confirmAllowOnce: 'Allow once',
    confirmAllowAlways: 'Always allow',
    confirmDeny: 'Deny',
    queueAria: (n) => `Send queue, ${n} ${n === 1 ? 'item' : 'items'}`,
    queueHead: (n) => `Queued ${n}`,
    queueRemoveAria: (n) => `Remove queued item ${n}`,
    queueClear: 'Clear',
    railAria: 'Question navigation',
    railItemAria: (n) => `Question ${n}`,
    previewAria: 'Question navigation for this chat',
    previewJumpAria: (n, summary) => `Jump to question ${n}: ${summary}`,
    mockArgComplexity: 'Run the complexity check',
    mockArgDocs: 'Latest official docs',
  },
  plugins: {
    eyebrow: 'Extensions',
    title: 'Plugin Marketplace',
    sub: 'Install skills, tools and MCP servers to extend knowledge bases and agents.',
    importSkill: 'Import skill',
    searchPh: 'Search plugins or skills',
    searchAria: 'Search plugins or skills',
    tabInstalled: 'Installed',
    tabMarket: 'Marketplace',
    tabMcp: 'MCP Servers',
    featured: 'Featured',
    categoryAll: 'All',
    categoryLabels: {
      agent: 'Agent',
      knowledge: 'Knowledge',
      search: 'Search',
      automation: 'Automation',
      productivity: 'Productivity',
      data: 'Data',
      skill: 'Skill',
    },
    install: 'Install',
    installed: 'Installed',
    uninstall: 'Uninstall',
    configure: 'Configure',
    details: 'Details',
    version: 'Version',
    byAuthor: (author) => `By ${author}`,
    rating: 'Rating',
    downloads: 'Downloads',
    tools: 'Tools',
    verified: 'Verified',
    official: 'Official',
    updated: 'Updated',
    installedEmptyTitle: 'No plugins installed',
    installedEmptyDesc: 'Pick capabilities from the marketplace or import a SKILL.md locally.',
    marketEmpty: 'No plugins in this category',
    noResults: (q) => `No plugins match “${q}”`,
    importTitle: 'Import a skill',
    importDesc: 'Paste SKILL.md or a skills manifest JSON and add it to the installed list.',
    importPh: '---\nname: my-skill\ndescription: Describe this skill\n---',
    importAria: 'Skill content',
    importBrowse: 'Choose file',
    importBrowseAria: 'Choose a skill file',
    importDropHint: 'Accepts .md or .json; parses frontmatter and JSON manifests',
    importBtn: 'Import',
    importDone: 'Skill imported',
    importFail: 'Import failed',
    importInvalid: 'No skill name was found. Check the frontmatter or JSON manifest.',
    skillNameUnknown: 'Unnamed skill',
    mcpSub: 'Connect MCP servers to register external tools in the agent toolbox.',
    mcpConnect: 'Connect',
    mcpDisconnect: 'Disconnect',
    mcpConfigure: 'Configure',
    mcpEndpoint: 'Endpoint',
    mcpTools: (n) => `${n} tools`,
    mcpConnected: 'Connected',
    mcpOffline: 'Offline',
    mcpModalTitle: 'Configure MCP server',
    mcpNameLabel: 'Server name',
    mcpEndpointLabel: 'Server endpoint',
    mcpTokenLabel: 'Access token (optional)',
    mcpTokenPh: 'Bearer token',
    mcpToolsLabel: 'Enabled tools',
    mcpSave: 'Save configuration',
    mcpSavedTitle: 'MCP configuration saved',
    mcpSavedBody: 'Changes apply to new sessions.',
    mcpConnectedTitle: 'MCP server connected',
    mcpDisconnectedTitle: 'MCP server disconnected',
    detailOverview: 'Overview',
    detailTools: 'Tool list',
    detailCompat: 'Compatibility',
    detailCompatBody: 'Works with the Nexus workbench and Knowledge Q&A; MCP plugins need a reachable server endpoint.',
    detailTags: 'Tags',
  },
  community: {
    eyebrow: 'Agent Hub',
    title: 'Agent Community',
    sub: 'Discover, try and publish agents, bringing community configurations into your workflow.',
    publish: 'Publish agent',
    searchPh: 'Search agents, authors or tags',
    searchAria: 'Search the agent community',
    tabDiscover: 'Discover',
    tabMine: 'My publishing',
    featuredLabel: 'Featured this week',
    use: 'Use',
    installed: 'Installed',
    detail: 'Details',
    authorYou: 'You',
    republish: 'Publish',
    sendReview: 'Send review',
    reviewDoneTitle: 'Review submitted',
    reviewDoneBody: 'Thanks for the feedback — the publisher will see it.',
    downloads: 'Downloads',
    likes: 'Likes',
    rating: 'Rating',
    reviews: 'Reviews',
    version: 'Version',
    byAuthor: (author) => `By ${author}`,
    updated: 'Updated',
    verified: 'Verified',
    categoryAll: 'All categories',
    categoryLabels: {
      writing: 'Writing',
      coding: 'Coding',
      research: 'Research',
      knowledge: 'Knowledge',
      business: 'Business',
      education: 'Education',
      data: 'Data',
    },
    sortPopular: 'Most popular',
    sortNewest: 'Newest',
    sortRating: 'Highest rated',
    emptyTitle: 'No agents to show',
    emptyDesc: 'Adjust the filters, or publish one of your own.',
    noResults: (q) => `No agents match “${q}”`,
    publishTitle: 'Publish an agent',
    publishSub: 'Configure public details and capabilities, or save as a draft first.',
    stepBasic: 'Basics',
    stepCapability: 'Capabilities',
    stepPublish: 'Publish',
    back: 'Back',
    next: 'Continue',
    create: 'Publish',
    cancel: 'Cancel',
    nameLabel: 'Name',
    namePh: 'e.g. Interview coach',
    nameAria: 'Agent name',
    descLabel: 'Description',
    descPh: 'Summarize what this agent is good at',
    descAria: 'Agent description',
    categoryLabel: 'Category',
    tagsLabel: 'Tags',
    tagsPh: 'Comma separated, e.g. Java, Interview',
    tagsAria: 'Tags',
    modelLabel: 'Model',
    versionLabel: 'Version',
    visibilityLabel: 'Visibility',
    statusPublished: 'Published',
    statusDraft: 'Draft',
    statusUnlisted: 'Unlisted',
    visibilityPublic: 'Public',
    visibilityPublicHint: 'Anyone can discover and install it.',
    visibilityDraft: 'Draft',
    visibilityDraftHint: 'Only visible to you until you publish.',
    visibilityUnlisted: 'Unlisted',
    visibilityUnlistedHint: 'Hidden from the marketplace, but usable with a direct link.',
    toolsLabel: 'Tool permissions',
    readmeLabel: 'Documentation',
    readmePh: 'Describe use cases, scenarios and example prompts…',
    publishDoneTitle: 'Published to the community',
    publishDoneBody: 'Manage and edit it under My publishing.',
    myEmptyTitle: 'No agents published yet',
    myEmptyDesc: 'Publish a tested configuration so more people can use it.',
    edit: 'Edit',
    unpublish: 'Unpublish',
    delete: 'Delete',
    deleteConfirm: (name) => `Delete “${name}”? This cannot be undone.`,
    deletedTitle: 'Publishing deleted',
    noReviews: 'No reviews yet — be the first to leave feedback.',
    writeReview: 'Write a review',
    sampleReviewers: ['Lin', 'Zoe', 'Alex'],
    sampleReviewMeta: ['yesterday', '3 days ago', 'last week'],
    sampleReviewTexts: [
      'Clear structure and natural follow-ups, even better when bound to my knowledge base.',
      'I could use the config immediately without much setup.',
      'Tool calls are transparent and failures show the exact reason.',
    ],
    overviewTitle: 'Overview',
    toolsTitle: 'Tool list',
    reviewsTitle: 'Reviews',
    readmeTitle: 'Documentation',
  },
  automations: {
    eyebrow: 'Automations',
    title: 'Automations',
    sub: 'Let agents run on a schedule — digests, monitoring and recurring reports.',
    newAutomation: 'New automation',
    searchPh: 'Search automations',
    searchAria: 'Search automations',
    tabAll: 'All',
    tabEnabled: 'Active',
    tabPaused: 'Paused',
    emptyTitle: 'No automations yet',
    emptyDesc: 'Create a scheduled task and let an agent keep working in the background.',
    noResults: (q) => `No automations match “${q}”`,
    enabled: 'Active',
    disabled: 'Paused',
    scheduleHourly: 'Hourly',
    scheduleDaily: 'Daily',
    scheduleWeekly: 'Weekly',
    scheduleCustom: 'Custom',
    agentLabel: 'Run with',
    kbLabel: 'Knowledge base',
    lastRun: 'Last run',
    nextRun: 'Next run',
    never: 'Never',
    runNow: 'Run now',
    history: 'History',
    edit: 'Edit',
    delete: 'Delete',
    deleteConfirm: (name) => `Delete automation “${name}”?`,
    createTitle: 'New automation',
    editTitle: 'Edit automation',
    nameLabel: 'Name',
    namePh: 'e.g. Daily knowledge digest',
    promptLabel: 'Instruction',
    promptPh: 'Describe what the agent should do on each run…',
    scheduleLabel: 'Frequency',
    notifyLabel: 'Notify me when finished',
    notifyHint: 'Get a notification when a run fails or produces a report.',
    cancel: 'Cancel',
    save: 'Save',
    createdTitle: 'Automation created',
    updatedTitle: 'Automation updated',
    deletedTitle: 'Automation deleted',
    queuedTitle: 'Added to the run queue',
    queuedBody: (name) => `${name} will run in the background`,
    historyTitle: 'Run history',
    historyEmpty: 'No runs yet',
    statusDone: 'Done',
    statusFailed: 'Failed',
    statusQueued: 'Queued',
  },
  trace: {
    viewChat: 'Chat',
    viewTrace: 'Trace',
    duration: 'Duration',
    turns: 'Turns',
    calls: 'Calls',
    searchPh: 'Search trace',
    searchAria: 'Search trace',
    input: 'Input',
    model: 'Model',
    tools: 'Tools',
    empty: 'This chat has no trace to show yet.',
    turn: (n) => `Turn ${n}`,
    roleUser: 'User',
    roleAssistant: 'Assistant',
    roleTool: 'Tool',
    toolOnly: '(tool calls only)',
    statusRunning: 'Running',
    statusDone: 'Done',
    statusFailed: 'Failed',
    statusTimeout: 'Timed out',
    statusCancelled: 'Stopped',
    statusAwaiting: 'Awaiting confirmation',
    overview: 'Overview',
    overviewAgent: 'Agent',
    overviewModel: 'Model',
    overviewMode: 'Mode',
    overviewKb: 'Knowledge bases',
    legend: 'Legend',
  },
  common: {
    relJustNow: 'just now',
    relMinutes: (n) => `${n} min ago`,
    relHours: (n) => `${n} h ago`,
    relYesterday: 'yesterday',
    relDays: (n) => `${n} d ago`,
    relMonths: (n) => `${n} mo ago`,
    relYears: (n) => `${n} y ago`,
    untitledSession: 'Untitled chat',
    unknownSource: 'Unknown source',
    untitledProject: 'Untitled project',
    defaultAgentName: 'Default agent',
    summaryEmpty: '(no text content)',
    errTimeout: 'Request timed out — check whether the backend is running',
    errNetwork: 'Cannot reach the backend — is the process on port 8080 up?',
    errGeneric: 'Request failed, please try again',
    errGenerate: 'Generation failed',
    kbCount: (n) => `${n} knowledge base${n === 1 ? '' : 's'}`,
    docChunkDetail: (chunks, status) => `${chunks} chunk${chunks === 1 ? '' : 's'} · ${status}`,
    none: 'None',
    landingWorkbench: 'Workbench',
    landingChat: 'Chat',
    landingKb: 'Knowledge base',
    feedbackReasons: {
      wrongFact: 'Factually wrong',
      wrongCite: 'Wrong citation',
      missedPoint: 'Missed the point',
      verbose: 'Too verbose',
      badFormat: 'Poor formatting',
      other: 'Other',
    },
  },
}

export function pagesCopy(locale: Locale): PagesCopy {
  return locale === 'en' ? EN : ZH
}
