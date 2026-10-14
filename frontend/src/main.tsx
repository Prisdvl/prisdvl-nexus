import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './styles/tokens.css'
import './styles/global.css'
/*
 * 加载顺序即优先级（同权重时后加载者胜）：
 *   tokens → global → PromptBar → chat-view
 *
 * ⚠️ PromptBar.css 原本由组件引入（路由级，加载晚于本文件）→ 会盖掉对话页样式；
 * 提到入口并放在覆盖层之前，保证 chat-view.css 最后生效。
 *
 * ⚠️ 此前还存在 chat-solid.css + chatgpt-web-style.css 两个对话页覆盖层，
 * 两者对同一批选择器各写一遍，靠顺序互相压制。现已合并为单一的 chat-view.css。
 */
import './bits/PromptBar/PromptBar.css';
import './styles/chat-view.css'

/*
 * 这里**不再**做主题的预渲染初始化。
 *
 * 主题的读取与应用在 `lib/theme.ts` 的模块体里完成；ESM 保证 import 先于模块体执行，
 * 所以 `import App`（进而 import theme.ts）已经在本文件任何语句之前把 dataset 写好了。
 *
 * 曾经这里留了一段读旧键 `nx-proto-theme` 的初始化，结果是：
 * theme.ts 先正确写入 → 本文件随后用**已废弃的旧键**覆盖回 light，
 * 表现为"切了深色、刷新又变亮"（看着像持久化坏了）。删掉即修复。
 */

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)