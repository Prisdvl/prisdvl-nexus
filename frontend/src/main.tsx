import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './styles/tokens.css'
import './styles/global.css'

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