/**
 * 命令式确认框 —— 全站单例。
 *
 * ## 为什么做成命令式，而不是让每个页面各持一个 `<ConfirmDialog>`
 *
 * 现有 8 处调用的形态是 `await ElMessageBox.confirm(...)`，
 * 改造成本越低越好。如果让每个页面自己 `const visible = ref(false)` 再传 props，
 * 8 处就要各自多出 5～6 行样板（visible、options、以及 cancel/confirm 两个处理函数），
 * 而且**8 份样板本身就是新的重复**——那正是这次要消灭的东西。
 *
 * 折中：**状态集中在一个模块级单例里**，页面只 `import { useConfirm }` 拿一个 `confirm` 函数，
 * 调用形态几乎不变。
 *
 * ## 未决调用的处理
 *
 * `confirm()` 在上一个还没答完时被再次调用（理论上不该发生——弹窗是模态的，
 * 但 ESC 关闭动画期间确实可能穿透），**前一个视为取消**而不是被永远挂起。
 * 挂起意味着那个 `await` 永远不 resolve，它后面的逻辑（比如 `finally` 里的清理）
 * 永远不会执行——那比"误判为取消"危险得多。
 */
import { reactive, readonly } from 'vue'

export interface ConfirmOptions {
  /** 一句话说明在确认什么。危险操作必须把后果写进 description。 */
  title: string
  /** 具体影响。危险操作**必填**——只说"确定吗"等于没确认。 */
  description?: string
  confirmText?: string
  cancelText?: string
  /** 危险操作：确认按钮变红，且语义上要求 description 必填。 */
  danger?: boolean
  /** 确认后请求在途：按钮转圈并禁用，防止重复提交。 */
  loading?: boolean
  /**
   * 根节点上的自定义 class。
   *
   * **这是 E2E 的定位钩子**（Phase 0 建立）：`verify-*.mjs` 按
   * `.nexus-confirm-xxx .el-button--primary` 点确认。改名会让脚本失效。
   */
  customClass?: string
  width?: string
}

interface ConfirmState {
  visible: boolean
  options: ConfirmOptions
}

const state = reactive<ConfirmState>({
  visible: false,
  options: { title: '' }
})

/** 当前挂起的 resolve。`null` 表示没有未决调用。 */
let resolver: ((result: boolean) => void) | null = null

const DEFAULTS: Required<Pick<ConfirmOptions, 'confirmText' | 'cancelText'>> = {
  confirmText: '确定',
  cancelText: '取消'
}

export function useConfirm() {
  /**
   * 打开确认框。
   *
   * @returns 用户点确认为 `true`，点取消/ESC/点遮罩为 `false`。
   *          **永远不会 reject** ——调用方不需要 try/catch。
   */
  function confirm(options: ConfirmOptions): Promise<boolean> {
    if (resolver) {
      // 上一个还没答完：判为取消，让它的 await 正常往下走
      resolver(false)
      resolver = null
    }
    state.options = { ...DEFAULTS, ...options }
    state.visible = true
    return new Promise<boolean>((resolve) => {
      resolver = resolve
    })
  }

  /** 由组件调用，落地一次调用。 */
  function settle(result: boolean): void {
    state.visible = false
    const r = resolver
    resolver = null
    r?.(result)
  }

  return {
    /** 只读暴露状态，供 `<ConfirmDialog>` 绑定。 */
    confirmState: readonly(state),
    confirm,
    settle
  }
}
