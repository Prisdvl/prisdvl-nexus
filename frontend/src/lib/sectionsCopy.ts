import type { Locale } from '@/lib/locale'

/**
 * 设置页 12 个分区（sections.tsx）的中英文案字典。
 * 与 appCopy 同风格：类型完整、中英两份、带插值的文案用函数表达。
 * mock 演示数据与导出文件的列名/正文属于数据内容，不在此处翻译。
 */
export interface SectionsCopy {
  profile: {
    title: string
    desc: string
    loading: string
    usernameLabel: string
    usernameHint: string
    nicknameLabel: string
    nicknamePh: string
    emailLabel: string
    emailPh: string
    emailHint: (status: string) => string
    statusVerified: string
    statusUnverified: string
    statusUnbound: string
    save: string
    saving: string
    errEmailEmpty: string
    errEmailFormat: string
    savedTitle: string
    savedMock: string
    savedEmailChanged: string
    savedSynced: string
    saveFailTitle: string
  }
  modelKey: {
    title: string
    desc: string
    loading: string
    baseLabel: string
    baseHint: string
    keyLabel: string
    keyPhSet: (masked: string | null) => string
    keyHint: string
    modelLabel: string
    modelHint: string
    enableLabel: string
    enableHint: string
    enableAria: string
    save: string
    saving: string
    enabledTitle: string
    enabledBody: string
    savedTitle: string
    savedBody: string
    saveFailTitle: string
    embedHint: string
  }
  embeddingKey: {
    title: string
    desc: string
    loading: string
    providerLabel: string
    providerHint: string
    modelLabel: string
    modelHint: string
    baseLabel: string
    baseHint: string
    keyLabel: string
    keyPhSet: (masked: string | null) => string
    keyHint: string
    dimLabel: string
    dimHint: (dim: number) => string
    enableLabel: string
    enableHint: string
    enableAria: string
    save: string
    saving: string
    enabledTitle: string
    enabledBody: string
    savedTitle: string
    savedBody: string
    saveFailTitle: string
    frozenHint: string
  }
  prefs: {
    title: string
    desc: string
    langLabel: string
    langHint: string
    tzLabel: string
    tzHint: string
    landingLabel: string
    save: string
    savedTitle: string
    savedBody: string
  }
  appearance: {
    title: string
    desc: string
    footHint: string
  }
  security: {
    title: string
    desc: string
    pwLabel: string
    pwHint: string
    pwExpand: string
    pwCollapse: string
    curPwLabel: string
    newPwLabel: string
    newPwHint: string
    confirmPwLabel: string
    submit: string
    submitDisabledMock: string
    submitting: string
    errMock: string
    errMismatch: string
    errLength: string
    okTitle: string
    okBody: string
    failTitle: string
    twoFactorLabel: string
    twoFactorHint: string
    unavailable: string
    toolsLabel: string
    toolsNone: string
    toolsList: (names: string[]) => string
    revokeAria: (name: string) => string
    revokeBtn: (name: string) => string
    revokeAllAria: string
    revokeAllBtn: string
    revokedTitle: string
    revokedBody: (name: string) => string
    revokedAllTitle: string
  }
  email: {
    title: string
    desc: string
    loading: string
    noEmailLabel: string
    noEmailHint: string
    verifiedHint: string
    verifiedBadge: string
    unverifiedBadge: string
    unverifiedHint: string
    codeLabel: string
    codeAria: string
    codeHint: string
    sending: string
    sendCode: string
    resendIn: (sec: number) => string
    verifying: string
    submitCode: string
    sentMockTitle: string
    sentMockBody: string
    sentTitle: string
    sentBody: (email: string) => string
    sendFailTitle: string
    verifiedMockTitle: string
    verifiedTitle: string
    verifyFailTitle: string
  }
  sessions: {
    title: string
    desc: string
    loading: string
    empty: string
    deviceFallback: string
    currentBadge: string
    rowMeta: (ip: string | null, lastUsed: string, created: string) => string
    revoke: string
    revoking: string
    refresh: string
    revokeAll: string
    revokingAll: string
    revokedTitle: string
    revokedAllTitle: string
    footHint: string
  }
  notify: {
    title: string
    desc: string
    doneLabel: string
    doneHint: string
    doneAria: string
    citeLabel: string
    citeHint: string
    citeAria: string
    weeklyLabel: string
    weeklyHint: string
    weeklyAria: string
    save: string
    savedTitle: string
    savedBody: string
  }
  usage: {
    title: string
    descMock: string
    descReal: string
    loading: string
    reqLabel: string
    tokenLabel: string
    resetAt: (time: string) => string
    kbLabel: string
    footHint: string
    /* 近 365 天热力图 */
    heatTitle: string
    heatLess: string
    heatMore: string
    heatSummary: (total: number, activeDays: number) => string
    heatDayLabel: (date: string, value: number) => string
    heatDayValue: (value: number) => string
    heatHint: string
    heatFootHint: string
  }
  export: {
    title: string
    desc: string
    formatLabel: string
    formatAria: string
    formatMarkdown: string
    formatJson: string
    formatCsv: string
    localHint: string
    submit: string
    submitMock: string
    submitting: string
    doneTitle: string
    doneBody: (kb: number, chat: number, agent: number) => string
    failTitle: string
    feedbackLabel: string
    feedbackCount: (n: number) => string
    feedbackJsonAria: string
    feedbackCsvAria: string
    feedbackClearAria: string
    clearBtn: string
    clearedTitle: string
    clearedBody: string
  }
  danger: {
    title: string
    desc: string
    permanentLabel: string
    permanentHint: string
    unavailable: string
    footHint: string
  }
}

const ZH: SectionsCopy = {
  profile: {
    title: '公开资料',
    desc: '修改昵称与邮箱；用户名是登录凭证，不可更改。',
    loading: '正在加载账号资料…',
    usernameLabel: '用户名',
    usernameHint: '用户名不可修改，作为登录凭证',
    nicknameLabel: '昵称',
    nicknamePh: '展示名称',
    emailLabel: '邮箱',
    emailPh: '用于验证与找回密码',
    emailHint: (status) => `账号必须绑定邮箱，不能清空。更换后需在「邮箱验证」重新验证；当前状态：${status}。`,
    statusVerified: '已验证',
    statusUnverified: '未验证',
    statusUnbound: '未绑定',
    save: '保存',
    saving: '保存中…',
    errEmailEmpty: '邮箱不能为空',
    errEmailFormat: '邮箱格式不正确',
    savedTitle: '资料已保存',
    savedMock: '仅保存在本机演示数据',
    savedEmailChanged: '邮箱已更新，请到「邮箱验证」完成归属验证',
    savedSynced: '已同步到账号',
    saveFailTitle: '保存失败',
  },
  modelKey: {
    title: '模型 Key',
    desc: '填入你自己的 OpenAI 兼容端点与 API Key；启用后，你的对话、知识库问答与 Agent 将走自己的模型与额度，不再消耗服务端的全局配置。',
    loading: '正在读取配置…',
    baseLabel: 'Base URL',
    baseHint: '任意 OpenAI 兼容端点：DeepSeek、百炼兼容模式、SiliconFlow、自建 vLLM 等。',
    keyLabel: 'API Key',
    // 有掩码 = 能解开；掩码为 null 且 hasApiKey 为真 = 库里那行解不开（服务端换了加密密钥）。
    // 这时不能显示"已设置（null）"，要直接告诉用户唯一的自救动作：重新填一次。
    keyPhSet: (masked) => masked
      ? `已设置（${masked}），留空保持不变`
      : '已保存的 Key 无法解密（服务端加密密钥可能已变更），请重新填写',
    keyHint: 'Key 仅保存在你的账号下，回显一律掩码；留空提交表示不改动已存的 Key。',
    modelLabel: '模型名',
    modelHint: '如 deepseek-chat / qwen-plus / glm-4-flash；需与所选端点的模型名一致。',
    enableLabel: '启用自定义模型',
    enableHint: '关闭时回落服务端全局模型；开启需三项填齐。',
    enableAria: '启用自定义模型',
    save: '保存配置',
    saving: '保存中…',
    enabledTitle: '已启用自定义模型',
    enabledBody: '你的对话 / RAG / Agent 将走自己的模型与额度',
    savedTitle: '已保存配置（未启用）',
    savedBody: '可随时打开开关启用',
    saveFailTitle: '保存失败',
    embedHint: '嵌入模型（知识库向量化）在「嵌入模型」分区单独配置；这里只影响对话生成。',
  },
  embeddingKey: {
    title: '嵌入模型',
    desc: '填入你自己的嵌入端点与 API Key；启用后，你的知识库在向量化与检索时走自己的额度。向量维度由平台固定，知识库建库时冻结。',
    loading: '正在读取配置…',
    providerLabel: '供应商',
    providerHint: '供应商与模型名一起受平台白名单约束（白名单由服务端下发，这里不硬编码）。',
    modelLabel: '模型名',
    modelHint: '从平台白名单中选择；不在白名单里的模型不会被接受。',
    baseLabel: 'Base URL',
    baseHint: '你自己的 OpenAI 兼容嵌入端点，如百炼 compatible-mode。',
    keyLabel: 'API Key',
    // 有掩码 = 能解开；掩码为 null 且 hasApiKey 为真 = 库里那行解不开（服务端换了加密密钥）。
    // 这时不能显示"已设置（null）"，要直接告诉用户唯一的自救动作：重新填一次。
    keyPhSet: (masked) => masked
      ? `已设置（${masked}），留空保持不变`
      : '已保存的 Key 无法解密（服务端加密密钥可能已变更），请重新填写',
    keyHint: 'Key 加密后保存在你的账号下，回显一律掩码；留空提交表示不改动已存的 Key。',
    dimLabel: '向量维度',
    dimHint: (dim) => `平台固定为 ${dim} 维（向量列与知识库冻结维度都按它建立），不接受其他维度。`,
    enableLabel: '启用自定义嵌入模型',
    enableHint: '关闭时回落服务端全局嵌入模型；开启需四项填齐。',
    enableAria: '启用自定义嵌入模型',
    save: '保存配置',
    saving: '保存中…',
    enabledTitle: '已启用自定义嵌入模型',
    enabledBody: '你的知识库将用自己的嵌入端点与额度',
    savedTitle: '已保存配置（未启用）',
    savedBody: '可随时打开开关启用',
    saveFailTitle: '保存失败',
    frozenHint: '已建知识库的嵌入模型在创建时就已冻结：换模型不会自动迁移，旧库会明确报错，必须重建索引或新建知识库。',
  },
  prefs: {
    title: '账号偏好',
    desc: '默认落地页、语言与时区偏好保存在此浏览器。',
    langLabel: '界面语言',
    langHint: '应用全局即时切换：导航栏、知识库与落地页文案会同步更新。',
    tzLabel: '时区',
    tzHint: '时间文本当前按浏览器本地时区格式化，此值仅保存为偏好。',
    landingLabel: '登录后默认打开',
    save: '保存偏好',
    savedTitle: '偏好已保存',
    savedBody: '保存在此浏览器',
  },
  appearance: {
    title: '外观',
    desc: '主题默认跟随系统；配色只影响强调色与背景氛围。',
    footHint: '外观设置只保存在本地（localStorage），不会同步到服务端。',
  },
  security: {
    title: '密码与登录',
    desc: '修改密码会吊销此账号的全部令牌；其他设备也需要重新登录。',
    pwLabel: '登录密码',
    pwHint: '展开后才会出现三个输入框，收起时只是一行摘要。',
    pwExpand: '修改密码',
    pwCollapse: '收起',
    curPwLabel: '当前密码',
    newPwLabel: '新密码',
    newPwHint: '8–64 位',
    confirmPwLabel: '确认新密码',
    submit: '修改密码并重新登录',
    submitDisabledMock: '连接后端后可修改密码',
    submitting: '提交中…',
    errMock: '密码修改需要真实后端；mock 模式不更改账号密码。',
    errMismatch: '两次输入的新密码不一致',
    errLength: '新密码长度需在 8–64 之间',
    okTitle: '密码已修改',
    okBody: '所有登录令牌已吊销，请使用新密码重新登录',
    failTitle: '密码修改失败',
    twoFactorLabel: '两步验证',
    twoFactorHint: '服务端尚未提供 TOTP 绑定接口，因此当前不可用。',
    unavailable: '暂不可用',
    toolsLabel: '工具授权（始终允许）',
    toolsNone: '暂无：高风险工具每次都会先询问你。',
    toolsList: (names) => `以下工具不再询问：${names.join('、')}`,
    revokeAria: (name) => `撤销 ${name} 的授权`,
    revokeBtn: (name) => `撤销 ${name}`,
    revokeAllAria: '撤销全部工具授权',
    revokeAllBtn: '全部撤销',
    revokedTitle: '已撤销授权',
    revokedBody: (name) => `${name} 下次会重新询问`,
    revokedAllTitle: '已撤销全部工具授权',
  },
  email: {
    title: '邮箱验证',
    desc: '向当前绑定邮箱发送验证码，确认你拥有该邮箱。更换邮箱后需重新验证。',
    loading: '正在加载…',
    noEmailLabel: '尚未绑定邮箱',
    noEmailHint: '请先在「公开资料」填写邮箱并保存，再回来验证归属。',
    verifiedHint: '当前邮箱已通过归属验证。',
    verifiedBadge: '已验证',
    unverifiedBadge: '未验证',
    unverifiedHint: '未验证。发送验证码后请在有效期内输入；冷却与限流由服务端强制执行。',
    codeLabel: '验证码',
    codeAria: '邮箱验证码',
    codeHint: '6 位数字；填满后可自动提交，也可点下方按钮。',
    sending: '发送中…',
    sendCode: '发送验证码',
    resendIn: (sec) => `${sec}s 后可重发`,
    verifying: '验证中…',
    submitCode: '提交验证',
    sentMockTitle: '验证码已发送（演示）',
    sentMockBody: '离线模式不会真正发信，请任意输入 6 位数字后提交',
    sentTitle: '验证码已发送',
    sentBody: (email) => `请查收 ${email}（开发态可能在服务端控制台）`,
    sendFailTitle: '发送失败',
    verifiedMockTitle: '邮箱已验证（演示）',
    verifiedTitle: '邮箱已验证',
    verifyFailTitle: '验证失败',
  },
  sessions: {
    title: '会话与设备',
    desc: '管理仍持有 refresh 凭证的登录设备；退出指定设备后该设备需重新登录。',
    loading: '正在读取会话…',
    empty: '暂无活跃会话。',
    deviceFallback: '未知设备',
    currentBadge: '当前设备',
    rowMeta: (ip, lastUsed, created) => `${ip ? `${ip} · ` : ''}上次活动 ${lastUsed} · 登录于 ${created}`,
    revoke: '退出',
    revoking: '退出中…',
    refresh: '刷新',
    revokeAll: '退出全部设备',
    revokingAll: '处理中…',
    revokedTitle: '已退出该设备',
    revokedAllTitle: '已退出全部设备',
    footHint: '修改密码或重置密码也会吊销全部设备上的 refresh 会话；短期 access JWT 仍依赖过期或 token_version。',
  },
  notify: {
    title: '通知偏好',
    desc: '提醒选择保存在当前浏览器；邮件/推送发送服务尚未接入。',
    doneLabel: '任务完成',
    doneHint: '后台任务完成提醒偏好（通知通道未启用）',
    doneAria: '任务完成通知',
    citeLabel: '回答与引用更新',
    citeHint: '引用更新提醒偏好（通知通道未启用）',
    citeAria: '回答与引用更新通知',
    weeklyLabel: '每周摘要',
    weeklyHint: '每周摘要提醒偏好（通知通道未启用）',
    weeklyAria: '每周摘要通知',
    save: '保存偏好',
    savedTitle: '偏好已保存到此浏览器',
    savedBody: '通知发送服务尚未接入',
  },
  usage: {
    title: '用量配额',
    descMock: '本地演示样本；真实模式读取服务端',
    descReal: '当日请求与 Token 用量来自服务端配额接口。',
    loading: '正在读取用量…',
    reqLabel: '模型请求（今日）',
    tokenLabel: 'Token（今日）',
    resetAt: (time) => `额度重置：${time}`,
    kbLabel: '知识库',
    footHint: '文件存储容量没有服务端统计接口，因此不显示估算值。',
    heatTitle: '近 365 天用量',
    heatLess: '少',
    heatMore: '多',
    heatSummary: (total, activeDays) => `共 ${total.toLocaleString()} 条 · ${activeDays} 天有记录`,
    heatDayLabel: (date, value) => (value > 0 ? `${date}，${value} 条` : `${date}，无记录`),
    heatDayValue: (value) => (value > 0 ? `${value} 条用量` : '无记录'),
    heatHint: '悬停或点选任意一天查看当天用量',
    heatFootHint: '以每日会话消息条数统计（后端暂未按天聚合 token 消耗）；点某天后可在上方查看当天数值。',
  },
  export: {
    title: '数据导出',
    desc: '从后端读取当前账号的知识库正文、会话消息与 Agent，生成本地备份文件。',
    formatLabel: '导出格式',
    formatAria: '导出格式',
    formatMarkdown: '文档正文与会话消息整理成可读归档。',
    formatJson: '完整结构化备份，包含资料、会话、消息与 Agent。',
    formatCsv: '导出账号、知识库文档、会话和 Agent 清单。',
    localHint: '导出文件保存在本机；其中包含私有文档与对话，请妥善保管。',
    submit: '导出全部数据',
    submitMock: '真实数据导出（mock 不可用）',
    submitting: '正在读取并打包…',
    doneTitle: '导出已完成',
    doneBody: (kb, chat, agent) => `${kb} 个知识库 · ${chat} 个会话 · ${agent} 个 Agent`,
    failTitle: '导出失败',
    feedbackLabel: '回答反馈',
    feedbackCount: (n) => `本机保存 ${n} 条反馈（不上传到服务器）。`,
    feedbackJsonAria: '导出反馈 JSON',
    feedbackCsvAria: '导出反馈 CSV',
    feedbackClearAria: '清空反馈数据',
    clearBtn: '清空',
    clearedTitle: '已清空反馈数据',
    clearedBody: '仅清除此浏览器保存的反馈',
  },
  danger: {
    title: '注销账号',
    desc: '账号注销会级联删除名下知识库、文档、会话与 Agent，且不可恢复。',
    permanentLabel: '永久注销',
    permanentHint: '服务端目前没有账号注销接口。为避免误导，不提供会显示成功但不执行的操作。',
    unavailable: '暂不可用',
    footHint: '后续实现需包含密码二次校验、数据级联删除、令牌吊销与审计记录。',
  },
}

const EN: SectionsCopy = {
  profile: {
    title: 'Profile',
    desc: 'Update your display name and email; the username is your sign-in credential and cannot be changed.',
    loading: 'Loading your profile…',
    usernameLabel: 'Username',
    usernameHint: 'The username cannot be changed — it is your sign-in credential',
    nicknameLabel: 'Display name',
    nicknamePh: 'Display name',
    emailLabel: 'Email',
    emailPh: 'Used for verification and password recovery',
    emailHint: (status) => `An account must keep an email and it cannot be cleared. After changing it, verify again under Email verification; current status: ${status}.`,
    statusVerified: 'verified',
    statusUnverified: 'not verified',
    statusUnbound: 'not set',
    save: 'Save',
    saving: 'Saving…',
    errEmailEmpty: 'Email is required',
    errEmailFormat: 'Invalid email format',
    savedTitle: 'Profile saved',
    savedMock: 'Stored in local demo data only',
    savedEmailChanged: 'Email updated — finish ownership verification under “Email verification”',
    savedSynced: 'Synced to your account',
    saveFailTitle: 'Save failed',
  },
  modelKey: {
    title: 'Model key',
    desc: 'Provide your own OpenAI-compatible endpoint and API key. Once enabled, your chats, knowledge-base Q&A and agents run on your own model and quota instead of the server-wide configuration.',
    loading: 'Loading configuration…',
    baseLabel: 'Base URL',
    baseHint: 'Any OpenAI-compatible endpoint: DeepSeek, Bailian compatibility mode, SiliconFlow, self-hosted vLLM, etc.',
    keyLabel: 'API Key',
    keyPhSet: (masked) => masked
      ? `Already set (${masked}) — leave blank to keep it`
      : 'The stored key cannot be decrypted (the server encryption key may have changed) — please enter it again',
    keyHint: 'The key is stored under your account only and is always masked when echoed back; submitting blank keeps the stored key.',
    modelLabel: 'Model name',
    modelHint: 'e.g. deepseek-chat / qwen-plus / glm-4-flash — must match the model name of the chosen endpoint.',
    enableLabel: 'Enable custom model',
    enableHint: 'When off, calls fall back to the server-wide model; turning it on requires all three fields.',
    enableAria: 'Enable custom model',
    save: 'Save configuration',
    saving: 'Saving…',
    enabledTitle: 'Custom model enabled',
    enabledBody: 'Your chats / RAG / agents now run on your own model and quota',
    savedTitle: 'Configuration saved (not enabled)',
    savedBody: 'Flip the switch whenever you want to enable it',
    saveFailTitle: 'Save failed',
    embedHint: 'Embedding models (knowledge-base vectorization) live in the separate “Embedding model” section; this section only affects chat generation.',
  },
  embeddingKey: {
    title: 'Embedding model',
    desc: 'Provide your own embedding endpoint and API key. Once enabled, your knowledge bases embed and retrieve on your own quota. The vector dimension is fixed by the platform and frozen when a base is created.',
    loading: 'Loading configuration…',
    providerLabel: 'Provider',
    providerHint: 'The provider is whitelisted together with the model name (the whitelist comes from the server; nothing is hard-coded here).',
    modelLabel: 'Model name',
    modelHint: 'Pick from the platform whitelist — models outside it are rejected.',
    baseLabel: 'Base URL',
    baseHint: 'Your own OpenAI-compatible embedding endpoint, e.g. Bailian compatibility mode.',
    keyLabel: 'API Key',
    keyPhSet: (masked) => masked
      ? `Already set (${masked}) — leave blank to keep it`
      : 'The stored key cannot be decrypted (the server encryption key may have changed) — please enter it again',
    keyHint: 'The key is encrypted under your account and always masked when echoed back; submitting blank keeps the stored key.',
    dimLabel: 'Vector dimension',
    dimHint: (dim) => `Fixed at ${dim} by the platform (the vector column and every frozen base use it); other dimensions are rejected.`,
    enableLabel: 'Enable custom embedding model',
    enableHint: 'When off, calls fall back to the server-wide embedding model; turning it on requires all four fields.',
    enableAria: 'Enable custom embedding model',
    save: 'Save configuration',
    saving: 'Saving…',
    enabledTitle: 'Custom embedding model enabled',
    enabledBody: 'Your knowledge bases now embed on your own endpoint and quota',
    savedTitle: 'Configuration saved (not enabled)',
    savedBody: 'Flip the switch whenever you want to enable it',
    saveFailTitle: 'Save failed',
    frozenHint: 'A knowledge base freezes its embedding model at creation: switching models does not migrate it — old bases fail loudly and must be re-indexed or rebuilt.',
  },
  prefs: {
    title: 'Preferences',
    desc: 'Default landing page, language and timezone preferences are stored in this browser.',
    langLabel: 'Interface language',
    langHint: 'Applied instantly across the app: sidebar, knowledge base and landing-page copy all update together.',
    tzLabel: 'Timezone',
    tzHint: 'Time text is currently formatted in your browser’s local timezone; this value is stored as a preference only.',
    landingLabel: 'Default page after sign-in',
    save: 'Save preferences',
    savedTitle: 'Preferences saved',
    savedBody: 'Stored in this browser',
  },
  appearance: {
    title: 'Appearance',
    desc: 'The theme follows the system by default; the palette only affects accent colours and background mood.',
    footHint: 'Appearance settings live in this browser (localStorage) and are not synced to the server.',
  },
  security: {
    title: 'Password & sign-in',
    desc: 'Changing your password revokes every token on this account; other devices must sign in again.',
    pwLabel: 'Sign-in password',
    pwHint: 'Expands on demand — the three fields appear only when you ask to change it.',
    pwExpand: 'Change password',
    pwCollapse: 'Collapse',
    curPwLabel: 'Current password',
    newPwLabel: 'New password',
    newPwHint: '8–64 characters',
    confirmPwLabel: 'Confirm new password',
    submit: 'Change password and sign in again',
    submitDisabledMock: 'Connect a backend to change your password',
    submitting: 'Submitting…',
    errMock: 'Changing a password requires a real backend; mock mode does not modify account passwords.',
    errMismatch: 'The two new passwords do not match',
    errLength: 'The new password must be 8–64 characters',
    okTitle: 'Password changed',
    okBody: 'All sign-in tokens were revoked — sign in again with the new password',
    failTitle: 'Could not change password',
    twoFactorLabel: 'Two-factor authentication',
    twoFactorHint: 'The server does not expose a TOTP binding API yet, so this is unavailable.',
    unavailable: 'Unavailable',
    toolsLabel: 'Tool permissions (always allowed)',
    toolsNone: 'None yet: high-risk tools always ask you first.',
    toolsList: (names) => `These tools no longer ask: ${names.join(', ')}`,
    revokeAria: (name) => `Revoke permission for ${name}`,
    revokeBtn: (name) => `Revoke ${name}`,
    revokeAllAria: 'Revoke all tool permissions',
    revokeAllBtn: 'Revoke all',
    revokedTitle: 'Permission revoked',
    revokedBody: (name) => `${name} will ask again next time`,
    revokedAllTitle: 'All tool permissions revoked',
  },
  email: {
    title: 'Email verification',
    desc: 'Send a verification code to your current email to confirm you own it. Changing your email requires verifying again.',
    loading: 'Loading…',
    noEmailLabel: 'No email linked yet',
    noEmailHint: 'Add an email under Profile and save first, then come back to verify ownership.',
    verifiedHint: 'This email has passed ownership verification.',
    verifiedBadge: 'Verified',
    unverifiedBadge: 'Not verified',
    unverifiedHint: 'Not verified. Send a code and enter it before it expires; cooldown and rate limits are enforced by the server.',
    codeLabel: 'Verification code',
    codeAria: 'Email verification code',
    codeHint: '6 digits — it submits automatically once filled, or use the button below.',
    sending: 'Sending…',
    sendCode: 'Send code',
    resendIn: (sec) => `Resend in ${sec}s`,
    verifying: 'Verifying…',
    submitCode: 'Submit verification',
    sentMockTitle: 'Verification code sent (demo)',
    sentMockBody: 'Offline mode does not really send mail — enter any 6 digits and submit',
    sentTitle: 'Verification code sent',
    sentBody: (email) => `Check ${email} (in dev it may appear in the server console)`,
    sendFailTitle: 'Send failed',
    verifiedMockTitle: 'Email verified (demo)',
    verifiedTitle: 'Email verified',
    verifyFailTitle: 'Verification failed',
  },
  sessions: {
    title: 'Sessions & devices',
    desc: 'Manage devices that still hold a refresh credential; after signing a device out it must sign in again.',
    loading: 'Loading sessions…',
    empty: 'No active sessions.',
    deviceFallback: 'Unknown device',
    currentBadge: 'Current device',
    rowMeta: (ip, lastUsed, created) => `${ip ? `${ip} · ` : ''}Last active ${lastUsed} · Signed in ${created}`,
    revoke: 'Sign out',
    revoking: 'Signing out…',
    refresh: 'Refresh',
    revokeAll: 'Sign out all devices',
    revokingAll: 'Working…',
    revokedTitle: 'Device signed out',
    revokedAllTitle: 'All devices signed out',
    footHint: 'Changing or resetting your password also revokes refresh sessions on every device; short-lived access JWTs still rely on expiry or token_version.',
  },
  notify: {
    title: 'Notifications',
    desc: 'Reminder choices are stored in this browser; the email/push delivery service is not connected yet.',
    doneLabel: 'Task completed',
    doneHint: 'Preference for background-task completion reminders (delivery channel not enabled)',
    doneAria: 'Task completion notifications',
    citeLabel: 'Answers & citation updates',
    citeHint: 'Preference for citation update reminders (delivery channel not enabled)',
    citeAria: 'Answer and citation update notifications',
    weeklyLabel: 'Weekly digest',
    weeklyHint: 'Preference for weekly digest reminders (delivery channel not enabled)',
    weeklyAria: 'Weekly digest notifications',
    save: 'Save preferences',
    savedTitle: 'Preferences saved in this browser',
    savedBody: 'The notification delivery service is not connected yet',
  },
  usage: {
    title: 'Usage quota',
    descMock: 'Local demo sample; real mode reads from the server',
    descReal: 'Today’s request and token usage comes from the server quota API.',
    loading: 'Loading usage…',
    reqLabel: 'Model requests (today)',
    tokenLabel: 'Tokens (today)',
    resetAt: (time) => `Quota resets: ${time}`,
    kbLabel: 'Knowledge bases',
    footHint: 'There is no server-side API for file storage size, so no estimate is shown.',
    heatTitle: 'Last 365 days',
    heatLess: 'Less',
    heatMore: 'More',
    heatSummary: (total, activeDays) => `${total.toLocaleString()} total · ${activeDays} active days`,
    heatDayLabel: (date, value) => (value > 0 ? `${date}, ${value}` : `${date}, no activity`),
    heatDayValue: (value) => (value > 0 ? `${value} requests` : 'No activity'),
    heatHint: 'Hover or select a day to see its usage',
    heatFootHint: 'Counted per daily chat messages (token usage is not yet aggregated by day on the server); selecting a day shows its value above.',
  },
  export: {
    title: 'Data export',
    desc: 'Reads this account’s knowledge-base content, chat messages and agents from the backend and builds a local backup file.',
    formatLabel: 'Export format',
    formatAria: 'Export format',
    formatMarkdown: 'Document content and chat messages organised into a readable archive.',
    formatJson: 'A complete structured backup including profile, chats, messages and agents.',
    formatCsv: 'Exports a listing of your account, knowledge-base documents, chats and agents.',
    localHint: 'The export file stays on this machine; it contains private documents and conversations, so store it safely.',
    submit: 'Export all data',
    submitMock: 'Real data export (unavailable in mock mode)',
    submitting: 'Reading and packaging…',
    doneTitle: 'Export complete',
    doneBody: (kb, chat, agent) => `${kb} knowledge bases · ${chat} chats · ${agent} agents`,
    failTitle: 'Export failed',
    feedbackLabel: 'Answer feedback',
    feedbackCount: (n) => `${n} responses stored on this machine (not uploaded to the server).`,
    feedbackJsonAria: 'Export feedback as JSON',
    feedbackCsvAria: 'Export feedback as CSV',
    feedbackClearAria: 'Clear feedback data',
    clearBtn: 'Clear',
    clearedTitle: 'Feedback data cleared',
    clearedBody: 'Only feedback stored in this browser was removed',
  },
  danger: {
    title: 'Delete account',
    desc: 'Deleting an account cascades to its knowledge bases, documents, chats and agents, and cannot be undone.',
    permanentLabel: 'Permanent deletion',
    permanentHint: 'The server has no account-deletion API yet. To avoid misleading you, no action that would report success without doing anything is offered.',
    unavailable: 'Unavailable',
    footHint: 'A future implementation needs password re-verification, cascading data deletion, token revocation and audit records.',
  },
}

export function sectionsCopy(locale: Locale): SectionsCopy {
  return locale === 'en' ? EN : ZH
}