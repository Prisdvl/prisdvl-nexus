import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
// 代码高亮主题：与正文里 pre 的深色背景搭配（技术笔记大量出现代码块）
import 'highlight.js/styles/github-dark.css'
import './styles/tokens.css'

import App from './App.vue'
import router from './router'

// Element Plus 全量引入：这是本机使用的内部工具，不追求首屏体积；
// 中文语言包让分页/表格/日期等内置文案不再是英文。
createApp(App).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
