import { createPinia } from 'pinia'
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
// 代码高亮主题：与正文里 pre 的深色背景搭配（技术笔记大量出现代码块）
import 'highlight.js/styles/github-dark.css'
// 顺序不能反：令牌 → 语义令牌 → Element Plus 皮肤（皮肤靠同权重后置覆盖框架默认值）
//
// semantic.css 必须在 tokens.css **之后**：它只定义别名（`--primary: var(--accent-500)`），
// 不定义具体值，所以谁先谁后都不影响正确性；但放在后面能让"读 main.ts 的人"
// 立刻看出它是叠在基础层之上的一层，而不是另一套独立变量。
// markdown.css 放在最后：它引用的全是 tokens 里的变量（--font-mono / --radius-card 等），
// 放在 tokens 之后才解析得到。
import './styles/tokens.css'
import './styles/semantic.css'
import './styles/element.css'
import './styles/markdown.css'

import App from './App.vue'
import router from './router'

// Element Plus 全量引入：这是本机使用的内部工具，不追求首屏体积；
// 中文语言包让分页/表格/日期等内置文案不再是英文。
//
// **Pinia 必须在 router 之前挂载**：路由守卫（router/index.ts）会读 authStore
// 的登录态来决定放不放行。顺序反了的话守卫里拿不到 store，
// 表现是"首屏直接被踢回登录页"或守卫静默失效。
const pinia = createPinia()

createApp(App).use(pinia).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
