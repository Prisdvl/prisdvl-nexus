import client from './client'

/**
 * 工具资源（Phase 4.3 后端 / 5.4 接进 Agent 配置）。
 *
 * 工具是**平台内置的能力**，不是用户创建的：`tool` 表里的行只是用户态覆盖
 * （启用/停用、改描述），真正的实现和它的身份（code）在服务端代码里
 * （`@Tool(name = ...)`）。所以这里只有读与"启停"，没有新建/删除——
 * 用户能做的选择是"这个 Agent 允许用哪些"，不是"我能有哪些工具"。
 */
export interface ToolResource {
  /** 稳定标识，也是 `@Tool(name=...)` 里的名字；Agent 的白名单引用的就是它 */
  code: string
  name: string
  description: string | null
  /** 来源：BUILTIN（平台内置）等。界面上按来源分组，让用户知道哪些是自带能力 */
  source: string
  category: string
  /** false = 该用户停用了它；停用的工具不会被装配进任何 Agent（见 AgentService.replaceTools） */
  enabled: boolean
  /** 内置工具：不可删除（今天所有工具都是内置，这个字段是给将来留的） */
  builtin: boolean
}

/** 当前用户可见的工具清单。 */
export function listTools() {
  return client.get<never, ToolResource[]>('/tools')
}
