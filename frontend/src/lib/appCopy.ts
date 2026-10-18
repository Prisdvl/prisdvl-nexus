import type { Locale } from '@/lib/locale'

/**
 * 应用内（登录后工作台）中英文案字典。
 * 落地页文案在 landingCopy.ts；此处覆盖全局外壳（侧栏 / 移动顶栏）与知识库全域。
 * 带插值的文案用函数表达。
 */
export interface AppCopy {
  common: {
    cancel: string
    save: string
    saving: string
    confirm: string
    delete: string
    retry: string
    loading: string
    search: string
    close: string
    optional: string
    remove: string
    copied: string
    copyFailed: string
    addSource: string
  }
  nav: {
    workbench: string
    kb: string
    chat: string
    agents: string
    plugins: string
    community: string
    automations: string
    workflows: string
    account: string
    newChat: string
    searchSessions: string
    settings: string
    settingsAria: string
    pinned: string
    recent: string
    archived: (n: number) => string
    restore: string
    noSessions: string
    noMatch: (q: string) => string
    openNav: string
    collapse: string
    expand: string
    switchLang: string
    renameTitle: string
    renameHint: string
    renameAria: string
    savingShort: string
    pin: string
    unpin: string
    archive: string
    archiveAction: string
    deleteTitle: string
    deleteBody: (name: string) => string
    deleteBodyHint: string
    useArchive: string
    deleting: string
    archivedToast: string
    pinOk: string
    unpinOk: string
    pinFail: string
    unpinFail: string
    deleteOk: string
    renameFail: string
    deleteFail: string
    restored: string
    /* —— 侧栏项目栏目（暂时隐藏，保留以便后端支持后一键恢复）—— */
    projectGroup: string
    projectName: string
    projectNameAria: string
    createProject: string
    createProjectBtn: string
    projectCreated: string
    projectDeleted: string
    deleteProjectAria: (name: string) => string
    allSessions: string
  }
  /** 全局外壳：侧栏容器 / 路由错误页 / 品牌标语 / 404 */
  shell: {
    sidebarAria: string
    resizeAria: string
    navAria: string
    navRubberAria: string
    errorTitle: string
    errorBody: string
    errorReload: string
    errorHome: string
    errorDetails: string
    errorUnknown: string
    notFoundTitle: string
    notFoundBody: string
    tagline: string
    mermaidFail: string
    skipToContent: string
  }
  kbList: {
    eyebrow: string
    title: string
    sub: string
    searchPlaceholder: string
    searchAria: string
    new: string
    docsBadge: (n: number) => string
    updated: (t: string) => string
    noDesc: string
    loadFailTitle: string
    emptyTitle: string
    emptyDesc: string
    emptyQTitle: (q: string) => string
    emptyQDesc: string
    createTitle: string
    createName: string
    createNamePlaceholder: string
    createDesc: string
    createDescHint: string
    createDescPlaceholder: string
    createAfterHint: string
    createSubmit: string
    creating: string
    errNameRequired: string
    errNameLong: string
    errDescLong: string
    errMock: string
    created: string
    addCard: string
    addCardHint: string
    newAria: string
  }
  kb: {
    back: string
    editMeta: string
    docsCount: (n: number) => string
    addDesc: string
    import: string
    tabs: { docs: string; qa: string; adv: string; links: string }
    tabsAria: string
    railLabel: string
    railHead: string
    metaLoading: string
    metaEmpty: string
    docsAria: string
    filterPlaceholder: string
    filterAria: string
    docsLoading: string
    docsLoadFail: string
    emptyTitle: string
    emptyDesc: string
    emptyImport: string
    noFilter: (q: string) => string
    statusDone: string
    statusIndexing: string
    statusFailed: string
    deleteAria: (name: string) => string
    previewAria: (name: string) => string
    deleteDocTitle: string
    deleteDocBody: (name: string) => string
    deleteConfirm: string
    undoDeleted: string
    undoRestore: string
    qaTitle: string
    qaSub: string
    starters: [string, string, string]
    qaPlaceholder: string
    qaHint: string
    citeA11y: string
    copyAnswer: string
    regenerate: string
    done: string
    doneWithCites: (n: number) => string
    genFailed: string
    stopped: string
    advAria: string
    configTitle: string
    modeLabel: string
    modes: { vector: string; bm25: string; hybrid: string }
    modeHint: string
    indexStatus: string
    indexChunks: (total: number, vec: number, es: number) => string
    indexNone: string
    indexPending: string
    buildIndex: string
    rebuildIndex: string
    searchAria: string
    searchPlaceholder: string
    searchBtn: string
    searching: string
    emptySearchTitle: string
    emptySearchDesc: string
    noHit: string
    resultsCount: (n: number) => string
    score: string
    linksAria: string
    pickDoc: string
    pickDocAria: string
    statsMock: (back: number, out: number, nodes: number) => string
    statsEmpty: string
    noEdgesHint: string
    noDocsHint: string
    emptyGraphTitle: string
    emptyGraphDesc: string
    metaModalTitle: string
    nameLabel: string
    descLabel: string
    descPlaceholder: string
    descShownHint: string
    errNameEmpty: string
    errNameLong: string
    errDescLong: string
    savedToast: string
    savedMockToast: string
    importTitle: string
    importSubmit: string
    importing: string
    dropChoose: string
    dropRelease: string
    dropHint: string
    dropReselect: string
    dropAria: string
    typeMd: string
    typeTxt: string
    typePdf: string
    typeDocx: string
    step1: string
    step2: string
    step3: string
    errPickFile: string
    errBadType: string
    errMockUpload: string
    importSubmitted: (id: string | number) => string
    indexRebuilt: string
    indexQueued: string
    indexFail: string
    indexMock: string
    searchFail: string
    chunksFail: string
    /* —— 文档 Tab —— */
    contextAria: string
    contextHead: string
    docsManageAria: string
    docsLoadFailTitle: (reason: string) => string
    unknownDoc: string
    openInDocs: string
    /* —— 问答 Tab —— */
    qaAria: string
    thinkingLabel: string
    thinkingStepsMock: [string, string, string]
    thinkingStepReal: string
    /* —— 检索配置 Tab —— */
    modeGroupHint: string
    topKLabel: string
    topKHint: string
    effectiveGroup: string
    dirtyBadge: string
    savedBadge: string
    resetBtn: string
    topKRange: string
    graphOpenHint: string
    graphStatBack: string
    graphStatOut: string
    graphStatNodes: string
    docDeleted: string
    docDeleteFail: string
    fileUploading: string
    swapFile: string
    typesAria: string
    stepsAria: string
    importProgressAria: string
    askFailPrefix: string
    backlinkWhy: (linkType: string) => string
    relatedWhy: string
    relatedScore: (score: string) => string
    docFallback: string
    centerFallback: string
    graphLegend: { back: string; out: string; related: string }
    graphZoomAria: string
    graphZoomIn: string
    graphZoomOut: string
    graphZoomReset: string
    graphNeighbors: (n: number) => string
    graphOpenDetail: string
    graphPanHint: string
    graphTruncated: (n: number) => string
    graphLonelyHint: string
    graphAria: (name: string) => string
    graphOpen: (name: string) => string
    mockAnswer: string
  }
  auth: {
    tabsAria: string
    loginTab: string
    registerTab: string
    back: string
    h1Login: string
    h1Register: string
    username: string
    password: string
    confirmPassword: string
    nickname: string
    email: string
    usernamePh: string
    passwordPh: string
    errUsername: string
    errPassword: string
    errConfirm: string
    errNickname: string
    errEmailEmpty: string
    errEmailFormat: string
    regUsernamePh: string
    regPasswordPh: string
    regConfirmPh: string
    regNicknamePh: string
    regEmailPh: string
    forgot: string
    loggingIn: string
    creating: string
    noAccount: string
    createAccount: string
    hasAccount: string
    goLogin: string
    terms: string
    formInvalidTitle: string
    formInvalidBody: string
    registerOkTitle: string
    registerOkBody: string
  }
  forgot: {
    title: string
    back: string
    ack: string
    ackMock: string
    formInvalidTitle: string
    emailRequiredBody: string
    emailRequired: string
    emailPh: string
    errEmail: string
    codeLabel: string
    codeAria: string
    codeHint: string
    sending: string
    resendIn: (sec: number) => string
    sendCode: string
    resendCode: string
    newPassword: string
    confirmPassword: string
    newPasswordPh: string
    confirmPh: string
    errPasswordLen: string
    errConfirm: string
    formInvalidBody: string
    resetting: string
    resetPassword: string
    resetFailTitle: string
    sendFailTitle: string
    doneBody: string
    backToLogin: string
    remembered: string
    resetOkMockTitle: string
    resetOkMockBody: string
    resetOkTitle: string
    resetOkBody: string
    submittedTitle: string
  }
  appearance: {
    themeLabel: string
    themeAria: string
    accentLabel: string
    accentAria: string
    accentPickAria: string
    prisdvlNote: string
    collapseAccent: string
    expandAccent: string
    toLight: string
    toDark: string
    tapCollapse: string
    tapToggleTheme: string
    tapExpandAccent: string
  }
  tour: {
    aria: string
    closeAria: string
    skip: string
    prev: string
    next: string
    done: string
    stepAria: (n: number, total: number) => string
    s1title: string
    s1desc: string
    s2title: string
    s2desc: string
    s3title: string
    s3desc: string
    s4title: string
    s4desc: string
    s5title: string
    s5desc: string
  }
  doc: {
    aria: (name: string) => string
    download: string
    downloadUnsupported: string
    closeAria: string
    loading: string
    binaryTitle: (type: string) => string
    binaryDesc: string
    chunksTitle: (n: number) => string
    chunkFallback: (i: number) => string
    noChunks: string
    noText: string
  }
  /** PromptBar（对话 / 知识库问答共用输入栏）的可见文案与无障碍名 */
  prompt: {
    menuSources: string
    menuCommands: string
    menuModels: string
    menuEffort: string
    effortTitle: string
    effortHelp: string
    faster: string
    smarter: string
    effortAria: string
    removeFile: (name: string) => string
    inputAria: string
    listening: string
    chooseModel: string
    chooseEffort: string
    dictate: string
    stopDictation: string
    send: string
    stop: string
    noMatches: (query: string) => string
  }
  settings: {
    pageEyebrow: string
    pageTitle: string
    pageSub: string
    navAria: string
    crumbsAria: string
    searchPh: string
    searchAria: string
    noMatch: (q: string) => string
    footNote: string
    dialogAria: string
    dialogSearchPh: string
    dialogSearchAria: string
    dialogClearAria: string
    dialogListAria: string
    dialogNoMatch: (q: string) => string
    replayTour: string
    logout: string
    barTitleFallback: string
    closeAria: string
    groupPersonal: string
    groupAccess: string
    groupNotify: string
    groupData: string
    groupDanger: string
    itemProfile: string
    itemModel: string
    itemEmbedding: string
    itemPrefs: string
    itemAppearance: string
    itemSecurity: string
    itemEmail: string
    itemSessions: string
    itemNotify: string
    itemUsage: string
    itemExport: string
    itemDanger: string
  }
}

const ZH: AppCopy = {
  common: {
    cancel: '取消', save: '保存', saving: '保存中…', confirm: '确认', delete: '删除',
    retry: '重试', loading: '加载中…', search: '搜索', close: '关闭', optional: '可选',
    remove: '移除', copied: '已复制', copyFailed: '复制失败', addSource: '添加数据源',
  },
  nav: {
    workbench: '工作台', kb: '知识库', chat: '对话', agents: 'Agent',
    plugins: '插件', community: 'Agent 社区',
    automations: '自动化', workflows: '工作流', account: '账户',
    newChat: '新对话', searchSessions: '搜索会话', settings: '设置', settingsAria: '设置',
    pinned: '置顶', recent: '最近',
    archived: (n) => `已归档（${n}）`, restore: '恢复',
    noSessions: '还没有会话', noMatch: (q) => `没有匹配「${q}」的会话`,
    openNav: '打开导航', collapse: '折叠侧栏', expand: '展开侧栏',
    switchLang: '切换到 English',
    renameTitle: '重命名聊天', renameHint: '保持简短且易于识别', renameAria: '会话名称',
    savingShort: '保存中…',
    pin: '置顶', unpin: '取消置顶', archive: '归档', archiveAction: '改为归档',
    deleteTitle: '删除会话',
    deleteBody: (name) => `确定删除会话「${name}」吗？`,
    deleteBodyHint: '消息与对话记忆会被一并清除，无法恢复。只是想暂时收起的话，可以改用「归档」，归档后可随时找回。',
    useArchive: '改为归档', deleting: '删除中…',
    archivedToast: '会话已归档，可在「已归档」中找回',
    pinOk: '已置顶会话', unpinOk: '已取消置顶', pinFail: '置顶失败', unpinFail: '取消置顶失败',
    deleteOk: '会话已删除', renameFail: '重命名失败', deleteFail: '删除失败', restored: '会话已恢复',
    projectGroup: '项目',
    projectName: '项目名称',
    projectNameAria: '项目名称',
    createProject: '创建项目',
    createProjectBtn: '创建',
    projectCreated: '项目已创建',
    projectDeleted: '项目已删除',
    deleteProjectAria: (name) => `删除项目 ${name}`,
    allSessions: '全部会话',
  },
  shell: {
    sidebarAria: '侧边栏',
    resizeAria: '拖拽调整侧边栏宽度',
    navAria: '主导航',
    navRubberAria: '主导航',
    errorTitle: '这个页面出错了',
    errorBody: '界面遇到了未预期的问题，你的会话数据已保存在本地，刷新不会丢。',
    errorReload: '重新加载',
    errorHome: '回到工作台',
    errorDetails: '技术细节（反馈问题时请附上）',
    errorUnknown: '未知错误',
    notFoundTitle: '这个地址没有页面',
    notFoundBody: '目前只有 入口 / 登录 / 工作台 / 知识库 / 对话 / Agent / 账号 这些路由。',
    tagline: '个人知识库 · AI 对话 · 自定义 Agent',
    mermaidFail: 'Mermaid 渲染失败：',
    skipToContent: '跳到主内容',
  },
  kbList: {
    eyebrow: '知识库',
    title: '我的知识库',
    sub: '把散落的笔记变成可检索、可对话的知识资产',
    searchPlaceholder: '搜索知识库',
    searchAria: '搜索知识库',
    new: '新建知识库',
    docsBadge: (n) => `${n} 篇文档`,
    updated: (t) => `更新于 ${t || '—'}`,
    noDesc: '还没有简介，打开后可以补充',
    loadFailTitle: '知识库加载失败',
    emptyTitle: '还没有知识库',
    emptyDesc: '新建第一个知识库，写上简介，再把笔记导入进来。',
    emptyQTitle: (q) => `没有匹配「${q}」的知识库`,
    emptyQDesc: '换个关键词，或直接新建一个知识库开始沉淀。',
    createTitle: '新建知识库',
    createName: '名称',
    createNamePlaceholder: '例如：产品文档',
    createDesc: '简介',
    createDescHint: '可选，最多 512 字',
    createDescPlaceholder: '例如：校招复习主线 · JVM / 并发 / MySQL 调优笔记',
    createAfterHint: '创建后可随时在详情页修改',
    createSubmit: '创建知识库',
    creating: '创建中…',
    errNameRequired: '请输入知识库名称。',
    errNameLong: '名称不能超过 80 个字符。',
    errDescLong: '简介不能超过 512 个字符。',
    errMock: '当前为离线测试模式，无法创建服务端知识库。请使用默认的真实 API 模式。',
    created: '知识库已创建',
    addCard: '新建知识库',
    addCardHint: '为一个主题建立专属资料库',
    newAria: '新建知识库',
  },
  kb: {
    back: '返回',
    editMeta: '编辑名称与简介',
    docsCount: (n) => `${n} 篇文档`,
    addDesc: '添加简介',
    import: '导入',
    tabs: { docs: '文档', qa: '问答', adv: '高级检索', links: '关联' },
    tabsAria: '知识库分区',
    railLabel: '当前知识库文档',
    railHead: '文档',
    metaLoading: '加载中…',
    metaEmpty: '暂无文档',
    docsAria: '文档管理',
    filterPlaceholder: '按名称筛选',
    filterAria: '按名称筛选文档',
    docsLoading: '正在加载文档…',
    docsLoadFail: '文档列表加载失败：',
    emptyTitle: '这个知识库还没有文档',
    emptyDesc: '导入 Markdown、TXT、PDF 或 DOCX，解析切片并建立索引后才能被检索与引用。',
    emptyImport: '导入文档',
    noFilter: (q) => `没有名称包含「${q}」的文档。`,
    statusDone: '已索引',
    statusIndexing: '解析中',
    statusFailed: '失败',
    deleteAria: (name) => `删除 ${name}`,
    previewAria: (name) => `预览 ${name}`,
    deleteDocTitle: '删除文档',
    deleteDocBody: (name) => `确定删除文档「${name}」吗？它的切片与索引会一并清除，此操作无法恢复。`,
    deleteConfirm: '确认删除',
    undoDeleted: '已删除文档',
    undoRestore: '已恢复文档',
    qaTitle: '问这个知识库',
    qaSub: '回答基于库内切片并附原文引用。试试下面的问题：',
    starters: ['B+ 树为什么适合做索引？', 'synchronized 的锁升级过程？', 'JVM 运行时内存是怎么划分的？'],
    qaPlaceholder: '向这个知识库提问…',
    qaHint: '回答仅基于本知识库内容并附原文引用 · 输入 ',
    citeA11y: '引用文档',
    copyAnswer: '复制回答',
    regenerate: '重新生成',
    done: '已完成',
    doneWithCites: (n) => `已完成 · 引用 ${n} 篇`,
    genFailed: '生成失败',
    stopped: '已停止生成',
    advAria: '高级检索',
    configTitle: '检索配置',
    modeLabel: '检索模式',
    modes: { vector: '向量检索', bm25: 'BM25', hybrid: '混合检索' },
    modeHint: '模式与 topK 作用于即时切片查询；相似度阈值由服务端配置。',
    indexStatus: '索引状态',
    indexChunks: (total, vec, es) => `切片 ${total} · 向量 ${vec} · 全文 ${es}`,
    indexNone: '该库暂无切片',
    indexPending: '（有未建索引切片）',
    buildIndex: '建立索引',
    rebuildIndex: '重建',
    searchAria: '在知识库内检索切片',
    searchPlaceholder: '输入关键词，直接检索切片（不调用模型）',
    searchBtn: '检索',
    searching: '检索中…',
    emptySearchTitle: '切片检索',
    emptySearchDesc: '输入关键词，直接在本库的解析切片里做 BM25 / 向量 / 混合检索，不调用模型。',
    noHit: '没有命中。若左侧提示有未建索引切片，先建立索引再检索。',
    resultsCount: (n) => `命中 ${n} 条切片`,
    score: '相似度',
    linksAria: '关联图谱',
    pickDoc: '选择文档',
    pickDocAria: '选择要查看关联的文档',
    statsMock: (back, out, nodes) => `反链 ${back} · 出链 ${out} · 节点 ${nodes}`,
    statsEmpty: '选择文档后显示局部关系',
    noEdgesHint: '还没有显式双链。右侧图谱已用同库文档作为弱相关示意；写入 ',
    noDocsHint: '该知识库暂无文档，先导入再来看关联。',
    emptyGraphTitle: '选择一篇文档',
    emptyGraphDesc: '上方下拉或左侧列表选中后，这里会画出它的局部关系网。',
    metaModalTitle: '名称与简介',
    nameLabel: '名称',
    descLabel: '简介',
    descPlaceholder: '例如：校招复习主线 · JVM / 并发 / MySQL 调优笔记',
    descShownHint: '会显示在列表卡片与详情顶栏',
    errNameEmpty: '名称不能为空。',
    errNameLong: '名称不能超过 128 个字符。',
    errDescLong: '简介不能超过 512 个字符。',
    savedToast: '知识库信息已保存',
    savedMockToast: '已更新简介',
    importTitle: '导入文档',
    importSubmit: '上传并开始解析',
    importing: '正在上传…',
    dropChoose: '拖入文件，或点击选择',
    dropRelease: '松开以放入文件',
    dropHint: '单次一个文件 · 最大体积以服务端配置为准',
    dropReselect: '更换',
    dropAria: '选择或拖入文件',
    typeMd: 'Markdown',
    typeTxt: '纯文本',
    typePdf: 'PDF 文档',
    typeDocx: 'Word',
    step1: '上传到解析队列',
    step2: '切片并写入索引',
    step3: '问答与检索即可引用',
    errPickFile: '请选择或拖入一个文件。',
    errBadType: '仅支持 Markdown、TXT、PDF、DOCX。',
    errMockUpload: '离线测试模式不会上传或保存文件。请连接真实后端后重试。',
    importSubmitted: (id) => `任务 ${id} 已进入解析队列`,
    indexRebuilt: '索引已重建',
    indexQueued: '索引任务已提交',
    indexFail: '建索引失败',
    indexMock: 'mock 模式不建索引',
    searchFail: '检索失败',
    chunksFail: '切片加载失败',
    contextAria: '当前知识库文档',
    contextHead: '文档',
    docsManageAria: '文档管理',
    docsLoadFailTitle: (reason) => `文档列表加载失败：${reason}`,
    unknownDoc: '未知文档',
    openInDocs: '在文档列表中查看',
    qaAria: '智能问答',
    thinkingLabel: '正在检索并生成回答',
    thinkingStepsMock: ['读取会话上下文', '混合检索 12 个切片', '拼接引用于回答'],
    thinkingStepReal: '正在检索知识库并生成回答',
    modeGroupHint: '作用于本库的切片查询与问答检索。',
    topKLabel: '召回数量（Top-K）',
    topKHint: '每次检索返回的切片条数，越大召回越全、噪声也越多。',
    effectiveGroup: '当前生效',
    dirtyBadge: '未保存',
    savedBadge: '已生效',
    resetBtn: '恢复默认',
    topKRange: 'Top-K',
    graphOpenHint: '点击节点可在左侧打开该文档',
    graphStatBack: '反链',
    graphStatOut: '出链',
    graphStatNodes: '节点',
    docDeleted: '文档已删除',
    docDeleteFail: '删除失败',
    fileUploading: '上传中',
    swapFile: '更换',
    typesAria: '支持的格式',
    stepsAria: '导入流程',
    importProgressAria: '上传中',
    askFailPrefix: '检索/生成失败',
    backlinkWhy: (linkType) => `反向链接${linkType ? ` · ${linkType}` : ''}`,
    relatedWhy: '相关推荐',
    relatedScore: (score) => `相关度 ${score}`,
    docFallback: '文档',
    centerFallback: '当前文档',
    graphLegend: { back: '反链', out: '出链', related: '相关' },
    graphZoomAria: '图谱缩放',
    graphZoomIn: '放大', graphZoomOut: '缩小', graphZoomReset: '复位',
    graphNeighbors: (n) => (n > 0 ? `直接关联 ${n} 篇` : '没有直接关联'),
    graphOpenDetail: '打开该文档',
    graphPanHint: '拖拽平移 · Ctrl + 滚轮缩放 · 点击节点看详情',
    graphTruncated: (n) => `关联过多，仅显示前 ${n} 个节点。拖拽平移 · Ctrl + 滚轮缩放。`,
    graphLonelyHint: '还没有双链或共同标签。导入带 ',
    graphAria: (name) => `${name} 的局部关系图谱`,
    graphOpen: (name) => `打开 ${name}`,
    mockAnswer: `## Why B+ trees fit indexing

**Core idea**: data lives only in leaf nodes; inner nodes store keys.

| Item | B-tree | B+ tree |
| --- | --- | --- |
| Data in inner nodes | Yes | No |
| Keys per page | Fewer | More (flatter tree) |
| Range scan | In-order backtrack | Leaf chain scan |

### Page size

\`\`\`text
One disk IO = one page (16KB), better locality
\`\`\``,
  },
  auth: {
    tabsAria: '登录或注册',
    loginTab: '登录',
    registerTab: '注册',
    back: '返回',
    h1Login: '登录 Prisdvl Nexus',
    h1Register: '注册 Prisdvl Nexus 账号',
    username: '用户名', password: '密码', confirmPassword: '确认密码',
    nickname: '昵称', email: '邮箱',
    usernamePh: '请输入用户名', passwordPh: '请输入密码',
    errUsername: '请输入用户名', errPassword: '请输入密码',
    errConfirm: '两次输入的密码不一致',
    errNickname: '昵称不能超过 64 位',
    errEmailEmpty: '请填写邮箱', errEmailFormat: '邮箱格式不正确',
    regUsernamePh: '3–32 位字母、数字、下划线或连字符',
    regPasswordPh: '8–64 位',
    regConfirmPh: '再次输入密码',
    regNicknamePh: '展示名称，不超过 64 位',
    regEmailPh: '用于验证与找回密码',
    forgot: '忘记密码？',
    loggingIn: '登录中…', creating: '创建中…',
    noAccount: '还没有账号？', createAccount: '创建账号',
    hasAccount: '已有账号？', goLogin: '去登录',
    terms: '登录即表示同意平台使用条款与隐私政策',
    formInvalidTitle: '表单还有问题', formInvalidBody: '请按提示修正后再继续',
    registerOkTitle: '注册成功',
    registerOkBody: '账号已创建，切到「登录」标签用刚才的密码登录',
  },
  forgot: {
    title: '忘记密码', back: '返回',
    ack: '如果该邮箱对应符合条件的账户，请查收邮件或稍后重试。',
    ackMock: '（演示模式不会真正发信）',
    formInvalidTitle: '表单还有问题',
    emailRequiredBody: '请填写正确的邮箱',
    emailRequired: '邮箱',
    emailPh: '请输入已验证的邮箱',
    errEmail: '请输入正确的邮箱地址',
    codeLabel: '验证码', codeAria: '密码重置验证码',
    codeHint: '6 位数字；冷却与限流由服务端强制执行。',
    sending: '发送中…',
    resendIn: (sec) => `${sec}s 后可重发`,
    sendCode: '发送验证码', resendCode: '重新发送验证码',
    newPassword: '新密码', confirmPassword: '确认新密码',
    newPasswordPh: '8–64 位', confirmPh: '再次输入新密码',
    errPasswordLen: '密码长度为 8–64 位',
    errConfirm: '两次输入的密码不一致',
    formInvalidBody: '请检查邮箱、验证码与新密码',
    resetting: '重置中…', resetPassword: '重置密码',
    resetFailTitle: '重置失败', sendFailTitle: '发送失败',
    doneBody: '密码已重置，请使用新密码登录。',
    backToLogin: '返回登录',
    remembered: '想起密码了？',
    resetOkMockTitle: '密码已重置（演示）', resetOkMockBody: '请返回登录页使用新密码登录',
    resetOkTitle: '密码已重置', resetOkBody: '请使用新密码登录',
    submittedTitle: '已提交重置请求',
  },
  appearance: {
    themeLabel: '主题', themeAria: '主题',
    accentLabel: '配色', accentAria: '配色', accentPickAria: '选择配色',
    prisdvlNote: 'Prisdvl · 深色深钴蓝（独立模式）',
    collapseAccent: '收起配色', expandAccent: '展开配色',
    toLight: '切换到浅色主题', toDark: '切换到深色主题',
    tapCollapse: '点击收起', tapToggleTheme: '点击切换浅/深色 · Esc 收起',
    tapExpandAccent: '点击展开配色环',
  },
  tour: {
    aria: '新手引导', closeAria: '关闭引导',
    skip: '跳过', prev: '上一步', next: '下一步', done: '完成',
    stepAria: (n, total) => `第 ${n} 步，共 ${total} 步`,
    s1title: '从「新对话」开始',
    s1desc: '想聊就聊，不用先建文件夹。会话会自动收进左侧列表，随时回来接着问。',
    s2title: '先让知识库有料',
    s2desc: '上传 PDF / Markdown 后会被切分、向量化；对话里 @ 一下就能引用它作答。',
    s3title: '回答要能追溯',
    s3desc: '对话区右侧的横线是提问导航；回答上方的工具调用轨迹会显示检索了哪些库、读了哪篇文档。',
    s4title: '输入框里的两个快捷键',
    s4desc: '输入 @ 引用数据源（知识库 / 上传文件 / 联网），输入 / 打开快捷命令。',
    s5title: '外观与数据都在这里',
    s5desc: '主题、配色、通知、用量与数据导出，统一收在「设置」里。',
  },
  doc: {
    aria: (name) => `预览 ${name}`,
    download: '下载原文', downloadUnsupported: '当前文件类型暂不支持下载',
    closeAria: '关闭', loading: '正在读取文档内容…',
    binaryTitle: (type) => `暂不支持内嵌 ${type} 预览`,
    binaryDesc: '当前 API 返回文档元信息与解析切片，不提供 PDF/DOCX 原始文件下载或内嵌预览。',
    chunksTitle: (n) => `解析切片（${n}）`,
    chunkFallback: (i) => `切片 ${i + 1}`,
    noChunks: '暂无可显示的切片。',
    noText: '服务端没有返回可预览的原始文本。',
  },
  prompt: {
    menuSources: '数据源',
    menuCommands: '快捷命令',
    menuModels: '模型',
    menuEffort: '思考力度',
    effortTitle: '思考力度',
    effortHelp: '力度越高，回答前的思考越久',
    faster: '更快',
    smarter: '更聪明',
    effortAria: '思考力度',
    removeFile: (name) => `移除 ${name}`,
    inputAria: '提问输入框',
    listening: '正在听写…',
    chooseModel: '选择模型',
    chooseEffort: '选择思考力度',
    dictate: '语音输入',
    stopDictation: '停止语音输入',
    send: '发送',
    stop: '停止',
    noMatches: (q) => `没有匹配「${q}」的内容`,
  },
  settings: {
    pageEyebrow: '账号', pageTitle: '设置',
    pageSub: '管理公开资料、安全、通知与用量配额',
    navAria: '设置分区', crumbsAria: '当前位置',
    searchPh: '搜索设置项', searchAria: '搜索设置项',
    noMatch: (q) => `没有匹配「${q}」的设置项`,
    footNote: '资料、密码与用量连接后端；偏好与通知开关保存在当前浏览器。尚未支持的服务端操作会明确标注。',
    dialogAria: '设置', dialogSearchPh: '搜索设置', dialogSearchAria: '搜索设置',
    dialogClearAria: '清除搜索', dialogListAria: '设置分类',
    dialogNoMatch: (q) => `没有匹配「${q}」的设置`,
    replayTour: '重新查看新手引导', logout: '退出登录',
    barTitleFallback: '设置', closeAria: '关闭设置',
    groupPersonal: '个人', groupAccess: '访问与安全', groupNotify: '通知',
    groupData: '数据与用量', groupDanger: '危险区',
    itemProfile: '公开资料', itemModel: '模型 Key', itemEmbedding: '嵌入模型', itemPrefs: '账号偏好', itemAppearance: '外观',
    itemSecurity: '密码与登录', itemEmail: '邮箱验证', itemSessions: '会话与设备',
    itemNotify: '通知偏好', itemUsage: '用量配额', itemExport: '数据导出', itemDanger: '注销账号',
  },
}

const EN: AppCopy = {
  common: {
    cancel: 'Cancel', save: 'Save', saving: 'Saving…', confirm: 'Confirm', delete: 'Delete',
    retry: 'Retry', loading: 'Loading…', search: 'Search', close: 'Close', optional: 'optional',
    remove: 'Remove', copied: 'Copied', copyFailed: 'Copy failed', addSource: 'Add source',
  },
  nav: {
    workbench: 'Workbench', kb: 'Knowledge', chat: 'Chat', agents: 'Agents',
    plugins: 'Plugins', community: 'Community',
    automations: 'Automations', workflows: 'Workflows', account: 'Account',
    newChat: 'New chat', searchSessions: 'Search chats', settings: 'Settings', settingsAria: 'Settings',
    pinned: 'Pinned', recent: 'Recent',
    archived: (n) => `Archived (${n})`, restore: 'Restore',
    noSessions: 'No chats yet', noMatch: (q) => `No chats match “${q}”`,
    openNav: 'Open navigation', collapse: 'Collapse sidebar', expand: 'Expand sidebar',
    switchLang: 'Switch to 简体中文',
    renameTitle: 'Rename chat', renameHint: 'Keep it short and recognizable', renameAria: 'Chat name',
    savingShort: 'Saving…',
    pin: 'Pin', unpin: 'Unpin', archive: 'Archive', archiveAction: 'Archive instead',
    deleteTitle: 'Delete chat',
    deleteBody: (name) => `Delete chat “${name}”?`,
    deleteBodyHint: 'Messages and chat memory are permanently removed. To just tuck it away, use Archive — it can be restored anytime.',
    useArchive: 'Archive instead', deleting: 'Deleting…',
    archivedToast: 'Chat archived — find it under Archived',
    pinOk: 'Chat pinned', unpinOk: 'Chat unpinned', pinFail: 'Pin failed', unpinFail: 'Unpin failed',
    deleteOk: 'Chat deleted', renameFail: 'Rename failed', deleteFail: 'Delete failed', restored: 'Chat restored',
    projectGroup: 'Projects',
    projectName: 'Project name',
    projectNameAria: 'Project name',
    createProject: 'New project',
    createProjectBtn: 'Create',
    projectCreated: 'Project created',
    projectDeleted: 'Project deleted',
    deleteProjectAria: (name) => `Delete project ${name}`,
    allSessions: 'All chats',
  },
  shell: {
    sidebarAria: 'Sidebar',
    resizeAria: 'Drag to resize the sidebar',
    navAria: 'Primary',
    navRubberAria: 'Primary navigation',
    errorTitle: 'Something went wrong on this page',
    errorBody: 'The interface hit an unexpected problem. Your chat data is already stored locally, so reloading will not lose it.',
    errorReload: 'Reload',
    errorHome: 'Back to workbench',
    errorDetails: 'Technical details (please include them when reporting an issue)',
    errorUnknown: 'Unknown error',
    notFoundTitle: 'No page at this address',
    notFoundBody: 'Only these routes exist: landing / sign in / workbench / knowledge / chat / agents / account.',
    tagline: 'Personal knowledge base · AI chat · Custom agents',
    mermaidFail: 'Mermaid render failed: ',
    skipToContent: 'Skip to main content',
  },
  kbList: {
    eyebrow: 'Knowledge bases',
    title: 'My knowledge bases',
    sub: 'Turn scattered notes into searchable, conversational knowledge',
    searchPlaceholder: 'Search knowledge bases',
    searchAria: 'Search knowledge bases',
    new: 'New knowledge base',
    docsBadge: (n) => `${n} docs`,
    updated: (t) => `Updated ${t || '—'}`,
    noDesc: 'No description yet — open to add one',
    loadFailTitle: 'Failed to load knowledge bases',
    emptyTitle: 'No knowledge bases yet',
    emptyDesc: 'Create your first knowledge base, add a description, and import your notes.',
    emptyQTitle: (q) => `No knowledge bases match “${q}”`,
    emptyQDesc: 'Try another keyword, or create a new knowledge base.',
    createTitle: 'New knowledge base',
    createName: 'Name',
    createNamePlaceholder: 'e.g. Product docs',
    createDesc: 'Description',
    createDescHint: 'Optional, up to 512 chars',
    createDescPlaceholder: 'e.g. Interview prep · JVM / concurrency / MySQL notes',
    createAfterHint: 'You can edit this later on the detail page',
    createSubmit: 'Create knowledge base',
    creating: 'Creating…',
    errNameRequired: 'Please enter a name.',
    errNameLong: 'Name must be at most 80 characters.',
    errDescLong: 'Description must be at most 512 characters.',
    errMock: 'Offline demo mode cannot create server-side knowledge bases. Use the default real API mode.',
    created: 'Knowledge base created',
    addCard: 'New knowledge base',
    addCardHint: 'Build a library around one topic',
    newAria: 'Create a knowledge base',
  },
  kb: {
    back: 'Back',
    editMeta: 'Edit name and description',
    docsCount: (n) => `${n} docs`,
    addDesc: 'Add a description',
    import: 'Import',
    tabs: { docs: 'Docs', qa: 'Q&A', adv: 'Advanced search', links: 'Links' },
    tabsAria: 'Knowledge base sections',
    railLabel: 'Documents in this knowledge base',
    railHead: 'Docs',
    metaLoading: 'Loading…',
    metaEmpty: 'No documents',
    docsAria: 'Documents',
    filterPlaceholder: 'Filter by name',
    filterAria: 'Filter documents by name',
    docsLoading: 'Loading documents…',
    docsLoadFail: 'Failed to load documents: ',
    emptyTitle: 'No documents in this knowledge base yet',
    emptyDesc: 'Import Markdown, TXT, PDF, or DOCX. Once parsed, chunked, and indexed, they become searchable and citable.',
    emptyImport: 'Import documents',
    noFilter: (q) => `No document names contain “${q}”.`,
    statusDone: 'Indexed',
    statusIndexing: 'Parsing',
    statusFailed: 'Failed',
    deleteAria: (name) => `Delete ${name}`,
    previewAria: (name) => `Preview ${name}`,
    deleteDocTitle: 'Delete document',
    deleteDocBody: (name) => `Delete “${name}”? Its chunks and index are removed too. This cannot be undone.`,
    deleteConfirm: 'Delete',
    undoDeleted: 'Document deleted',
    undoRestore: 'Document restored',
    qaTitle: 'Ask this knowledge base',
    qaSub: 'Answers are grounded in its chunks with source citations. Try:',
    starters: ['Why are B+ trees good for indexing?', 'How does synchronized lock upgrade work?', 'How is JVM runtime memory divided?'],
    qaPlaceholder: 'Ask this knowledge base…',
    qaHint: 'Answers cite only this library · Type ',
    citeA11y: 'Cited document',
    copyAnswer: 'Copy answer',
    regenerate: 'Regenerate',
    done: 'Done',
    doneWithCites: (n) => `Done · ${n} ${n === 1 ? 'citation' : 'citations'}`,
    genFailed: 'Generation failed',
    stopped: 'Generation stopped',
    advAria: 'Advanced search',
    configTitle: 'Search settings',
    modeLabel: 'Retrieval mode',
    modes: { vector: 'Vector', bm25: 'BM25', hybrid: 'Hybrid' },
    modeHint: 'Mode and topK apply to instant chunk search; the similarity threshold is set server-side.',
    indexStatus: 'Index status',
    indexChunks: (total, vec, es) => `Chunks ${total} · Vectors ${vec} · Full-text ${es}`,
    indexNone: 'No chunks yet',
    indexPending: ' (some chunks are not indexed)',
    buildIndex: 'Build index',
    rebuildIndex: 'Rebuild',
    searchAria: 'Search chunks in this knowledge base',
    searchPlaceholder: 'Search chunks directly with keywords (no model involved)',
    searchBtn: 'Search',
    searching: 'Searching…',
    emptySearchTitle: 'Chunk search',
    emptySearchDesc: 'Enter keywords to run BM25 / vector / hybrid search directly over parsed chunks — no model involved.',
    noHit: 'No hits. If the index panel shows pending chunks, build the index first.',
    resultsCount: (n) => `${n} ${n === 1 ? 'chunk' : 'chunks'} hit`,
    score: 'Score',
    linksAria: 'Link graph',
    pickDoc: 'Pick a document',
    pickDocAria: 'Choose a document to inspect links',
    statsMock: (back, out, nodes) => `Backlinks ${back} · Outlinks ${out} · Nodes ${nodes}`,
    statsEmpty: 'Pick a document to see its local graph',
    noEdgesHint: 'No explicit wiki links yet. The graph shows peer docs as weak links; add ',
    noDocsHint: 'No documents yet — import some before checking links.',
    emptyGraphTitle: 'Pick a document',
    emptyGraphDesc: 'Choose one from the dropdown or the list, and its local link network appears here.',
    metaModalTitle: 'Name and description',
    nameLabel: 'Name',
    descLabel: 'Description',
    descPlaceholder: 'e.g. Interview prep · JVM / concurrency / MySQL notes',
    descShownHint: 'Shown on the list card and the detail header',
    errNameEmpty: 'Name is required.',
    errNameLong: 'Name must be at most 128 characters.',
    errDescLong: 'Description must be at most 512 characters.',
    savedToast: 'Knowledge base saved',
    savedMockToast: 'Description updated',
    importTitle: 'Import documents',
    importSubmit: 'Upload & start parsing',
    importing: 'Uploading…',
    dropChoose: 'Drop a file here, or click to choose',
    dropRelease: 'Release to add the file',
    dropHint: 'One file at a time · size limit follows server config',
    dropReselect: 'Replace',
    dropAria: 'Choose or drop a file',
    typeMd: 'Markdown',
    typeTxt: 'Plain text',
    typePdf: 'PDF document',
    typeDocx: 'Word',
    step1: 'Upload to the parse queue',
    step2: 'Chunk and write to index',
    step3: 'Citable in search and Q&A',
    errPickFile: 'Please choose or drop a file.',
    errBadType: 'Only Markdown, TXT, PDF, and DOCX are supported.',
    errMockUpload: 'Offline demo mode does not upload or save files. Please connect a real backend.',
    importSubmitted: (id) => `Task ${id} entered the parse queue`,
    indexRebuilt: 'Index rebuilt',
    indexQueued: 'Index task submitted',
    indexFail: 'Indexing failed',
    indexMock: 'Indexing is disabled in mock mode',
    searchFail: 'Search failed',
    chunksFail: 'Failed to load chunks',
    contextAria: 'Documents in this knowledge base',
    contextHead: 'Docs',
    docsManageAria: 'Document management',
    docsLoadFailTitle: (reason) => `Failed to load documents: ${reason}`,
    unknownDoc: 'Unknown document',
    openInDocs: 'View in document list',
    qaAria: 'Ask this knowledge base',
    thinkingLabel: 'Retrieving and generating an answer',
    thinkingStepsMock: ['Reading conversation context', 'Hybrid search over 12 chunks', 'Attaching citations'],
    thinkingStepReal: 'Searching this knowledge base and generating an answer',
    modeGroupHint: 'Applies to chunk search and Q&A retrieval in this knowledge base.',
    topKLabel: 'Recall count (Top-K)',
    topKHint: 'How many chunks each search returns. Higher values recall more but add noise.',
    effectiveGroup: 'In effect',
    dirtyBadge: 'Unsaved',
    savedBadge: 'Applied',
    resetBtn: 'Reset to default',
    topKRange: 'Top-K',
    graphOpenHint: 'Click a node to open that document on the left',
    graphStatBack: 'Backlinks',
    graphStatOut: 'Outlinks',
    graphStatNodes: 'Nodes',
    docDeleted: 'Document deleted',
    docDeleteFail: 'Delete failed',
    fileUploading: 'Uploading',
    swapFile: 'Replace',
    typesAria: 'Supported formats',
    stepsAria: 'Import flow',
    importProgressAria: 'Uploading',
    askFailPrefix: 'Retrieval/generation failed',
    backlinkWhy: (linkType) => `Backlink${linkType ? ` · ${linkType}` : ''}`,
    relatedWhy: 'Related suggestion',
    relatedScore: (score) => `Relevance ${score}`,
    docFallback: 'Document',
    centerFallback: 'Current document',
    graphLegend: { back: 'Backlinks', out: 'Outlinks', related: 'Related' },
    graphZoomAria: 'Graph zoom',
    graphZoomIn: 'Zoom in', graphZoomOut: 'Zoom out', graphZoomReset: 'Reset view',
    graphNeighbors: (n) => (n > 0 ? `${n} direct link${n === 1 ? '' : 's'}` : 'No direct links'),
    graphOpenDetail: 'Open this document',
    graphPanHint: 'Drag to pan · Ctrl + scroll to zoom · click a node for details',
    graphTruncated: (n) => `Too many links — showing the first ${n} nodes. Drag to pan · Ctrl + scroll to zoom.`,
    graphLonelyHint: 'No wiki links or shared tags yet. Import a Markdown file with ',
    graphAria: (name) => `Local link graph of ${name}`,
    graphOpen: (name) => `Open ${name}`,
    mockAnswer: `## Why B+ trees fit indexing

**Core idea**: data lives only in leaf nodes; inner nodes store keys.

| Item | B-tree | B+ tree |
| --- | --- | --- |
| Data in inner nodes | Yes | No |
| Keys per page | Fewer | More (flatter tree) |
| Range scan | In-order backtrack | Leaf chain scan |

### Page size

\`\`\`text
One disk IO = one page (16KB), better locality
\`\`\``,
  },
  auth: {
    tabsAria: 'Sign in or sign up',
    loginTab: 'Sign in', registerTab: 'Sign up',
    back: 'Back',
    h1Login: 'Sign in to Prisdvl Nexus', h1Register: 'Create a Prisdvl Nexus account',
    username: 'Username', password: 'Password', confirmPassword: 'Confirm password',
    nickname: 'Display name', email: 'Email',
    usernamePh: 'Enter your username', passwordPh: 'Enter your password',
    errUsername: 'Username is required', errPassword: 'Password is required',
    errConfirm: 'Passwords do not match',
    errNickname: 'Display name must be under 64 characters',
    errEmailEmpty: 'Email is required', errEmailFormat: 'Invalid email format',
    regUsernamePh: '3–32 letters, digits, underscores or hyphens',
    regPasswordPh: '8–64 characters',
    regConfirmPh: 'Re-enter your password',
    regNicknamePh: 'Display name, up to 64 characters',
    regEmailPh: 'Used for verification and password recovery',
    forgot: 'Forgot password?',
    loggingIn: 'Signing in…', creating: 'Creating…',
    noAccount: 'No account yet?', createAccount: 'Create an account',
    hasAccount: 'Already have an account?', goLogin: 'Go to sign in',
    terms: 'Signing in means you accept the platform terms and privacy policy',
    formInvalidTitle: 'The form still has issues', formInvalidBody: 'Fix the highlighted fields and try again',
    registerOkTitle: 'Account created',
    registerOkBody: 'Your account is ready — switch to the sign-in tab and use that password',
  },
  forgot: {
    title: 'Forgot password', back: 'Back',
    ack: 'If an eligible account exists for that address, check your inbox or try again later.',
    ackMock: ' (demo mode does not really send mail)',
    formInvalidTitle: 'The form still has issues',
    emailRequiredBody: 'Enter a valid email address',
    emailRequired: 'Email',
    emailPh: 'Enter your verified email',
    errEmail: 'Enter a valid email address',
    codeLabel: 'Code', codeAria: 'Password reset code',
    codeHint: '6 digits — cooldown and rate limits are enforced server-side.',
    sending: 'Sending…',
    resendIn: (sec) => `Resend in ${sec}s`,
    sendCode: 'Send code', resendCode: 'Resend code',
    newPassword: 'New password', confirmPassword: 'Confirm new password',
    newPasswordPh: '8–64 characters', confirmPh: 'Re-enter the new password',
    errPasswordLen: 'Password must be 8–64 characters',
    errConfirm: 'Passwords do not match',
    formInvalidBody: 'Check the email, code and new password',
    resetting: 'Resetting…', resetPassword: 'Reset password',
    resetFailTitle: 'Reset failed', sendFailTitle: 'Send failed',
    doneBody: 'Your password was reset — sign in with the new one.',
    backToLogin: 'Back to sign in',
    remembered: 'Remembered it?',
    resetOkMockTitle: 'Password reset (demo)', resetOkMockBody: 'Return to the sign-in page and use your new password',
    resetOkTitle: 'Password reset', resetOkBody: 'Sign in with your new password',
    submittedTitle: 'Reset request submitted',
  },
  appearance: {
    themeLabel: 'Theme', themeAria: 'Theme',
    accentLabel: 'Accent', accentAria: 'Accent', accentPickAria: 'Choose an accent colour',
    prisdvlNote: 'Prisdvl · deep cobalt (independent mode)',
    collapseAccent: 'Collapse accent picker', expandAccent: 'Expand accent picker',
    toLight: 'Switch to light theme', toDark: 'Switch to dark theme',
    tapCollapse: 'Click to collapse', tapToggleTheme: 'Click to toggle light/dark · Esc to close',
    tapExpandAccent: 'Click to open the accent ring',
  },
  tour: {
    aria: 'Getting started', closeAria: 'Close tour',
    skip: 'Skip', prev: 'Back', next: 'Next', done: 'Done',
    stepAria: (n, total) => `Step ${n} of ${total}`,
    s1title: 'Start with a new chat',
    s1desc: 'Just type. Chats collect in the left sidebar so you can pick them up anytime.',
    s2title: 'Give your knowledge base content',
    s2desc: 'Uploaded PDF / Markdown is chunked and embedded; mention it with @ in a chat to ground answers.',
    s3title: 'Answers you can trace',
    s3desc: 'The rail on the right indexes your questions, and the tool-call trace above each answer shows which bases were searched.',
    s4title: 'Two shortcuts in the composer',
    s4desc: 'Type @ to cite a source (knowledge base / upload / web) and / to open quick commands.',
    s5title: 'Appearance and data live in Settings',
    s5desc: 'Theme, palette, notifications, usage and data export all sit under Settings.',
  },
  doc: {
    aria: (name) => `Preview ${name}`,
    download: 'Download original', downloadUnsupported: 'This file type cannot be downloaded',
    closeAria: 'Close', loading: 'Loading document…',
    binaryTitle: (type) => `No inline ${type} preview`,
    binaryDesc: 'The API returns document metadata and parsed chunks, not the original PDF/DOCX file.',
    chunksTitle: (n) => `Parsed chunks (${n})`,
    chunkFallback: (i) => `Chunk ${i + 1}`,
    noChunks: 'No chunks to show.',
    noText: 'The server returned no previewable text.',
  },
  prompt: {
    menuSources: 'Sources',
    menuCommands: 'Commands',
    menuModels: 'Models',
    menuEffort: 'Effort',
    effortTitle: 'Effort',
    effortHelp: 'Higher effort thinks longer before answering',
    faster: 'Faster',
    smarter: 'Smarter',
    effortAria: 'Effort',
    removeFile: (name) => `Remove ${name}`,
    inputAria: 'Prompt',
    listening: 'Listening…',
    chooseModel: 'Choose model',
    chooseEffort: 'Choose effort',
    dictate: 'Dictate',
    stopDictation: 'Stop dictation',
    send: 'Send',
    stop: 'Stop',
    noMatches: (q) => `No matches for “${q}”`,
  },
  settings: {
    pageEyebrow: 'Account', pageTitle: 'Settings',
    pageSub: 'Manage your profile, security, notifications and usage quota',
    navAria: 'Settings sections', crumbsAria: 'Current location',
    searchPh: 'Search settings', searchAria: 'Search settings',
    noMatch: (q) => `No settings match “${q}”`,
    footNote: 'Profile, password and usage connect to the backend; preferences and notification toggles are stored in this browser. Server-side operations that are unsupported are labelled as such.',
    dialogAria: 'Settings', dialogSearchPh: 'Search settings', dialogSearchAria: 'Search settings',
    dialogClearAria: 'Clear search', dialogListAria: 'Settings categories',
    dialogNoMatch: (q) => `No settings match “${q}”`,
    replayTour: 'Replay the tour', logout: 'Sign out',
    barTitleFallback: 'Settings', closeAria: 'Close settings',
    groupPersonal: 'Personal', groupAccess: 'Access & security', groupNotify: 'Notifications',
    groupData: 'Data & usage', groupDanger: 'Danger zone',
    itemProfile: 'Profile', itemModel: 'Model key', itemEmbedding: 'Embedding model', itemPrefs: 'Preferences', itemAppearance: 'Appearance',
    itemSecurity: 'Password & sign-in', itemEmail: 'Email verification', itemSessions: 'Sessions & devices',
    itemNotify: 'Notifications', itemUsage: 'Usage quota', itemExport: 'Data export', itemDanger: 'Delete account',
  },
}

export function appCopy(locale: Locale): AppCopy {
  return locale === 'en' ? EN : ZH
}
