<script setup lang="ts">
/**
 * 确认弹窗 —— 统一替代 8 处手写的 `ElMessageBox.confirm`。
 *
 * ## 为什么要自己建，而不是包一层 ElMessageBox
 *
 * 两个硬需求是 `ElMessageBox` 给不了的：
 *
 * 1. **`loading`**：删除/停用这类操作点了确认之后，服务端要跑一会儿。
 *    `ElMessageBox` 的按钮不能异步等待——弹窗立刻消失，用户在这段时间里
 *    完全可以再点一次删除，于是发了两个请求。
 * 2. **`danger` 让确认按钮本身变红**：危险操作的确认按钮不该和"保存"长得一样。
 *
 * ## 与 E2E 的契约（Phase 0 留下的）
 *
 * Phase 0 给 8 处确认框都加了 `customClass`（`nexus-confirm-*`），
 * `verify-*.mjs` 按 `.nexus-confirm-doc-delete .el-button--primary` 之类的选择器点确认。
 * 所以本组件**必须**把 `customClass` 渲染成一个真实存在的祖先 class。
 *
 * 实现上刻意**不用 `#footer` 插槽**，而是把按钮放进内容根 div 里：
 * `el-dialog` 是 `inheritAttrs: false` 且没有 `class` prop，
 * 写在它身上的 class 落到哪一处不受控（Phase 0 已查实）。自己排版就没有这个风险。
 */
import { computed } from 'vue'
import type { ConfirmOptions } from './useConfirm'

interface Props {
  visible: boolean
  options: ConfirmOptions
}

const props = defineProps<Props>()

const emit = defineEmits<{
  'update:visible': [boolean]
  confirm: []
  cancel: []
}>()

const o = computed(() => props.options)

function settle(result: boolean): void {
  emit('update:visible', false)
  // 刻意分两支而不是 `emit(result ? 'confirm' : 'cancel')`：
  // Vue 的 emit 类型会把联合事件名 `'confirm' | 'cancel'` 当成**一个**事件名去匹配签名，
  // 结果是 TS2769（两个 overload 都不接受）。分支写同时也让意图更直白。
  if (result) emit('confirm')
  else emit('cancel')
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="o.title"
    :width="o.width ?? '440px'"
    :close-on-click-modal="!o.loading"
    :close-on-press-escape="!o.loading"
    :show-close="!o.loading"
    align-center
    @update:model-value="(v: boolean) => !v && settle(false)"
  >
    <!--
      customClass 落在这个 div 上，而不是 el-dialog 上。
      按钮刻意也放在这里（不用 #footer）：这样 `.nexus-confirm-xxx .el-button--primary`
      一定能命中，不依赖 el-dialog 内部对 attrs 的处理方式。
    -->
    <div class="confirm-body" :class="o.customClass" data-testid="confirm-dialog">
      <!--
        描述用 data-testid 而不是靠 `.el-message-box__message`：
        那个 class 属于 ElMessageBox，本组件既然不再基于它，就不该再依赖它的内部结构。
        E2E 需要读"后果说明"来验证危险操作是否讲清影响（UA-015）。
      -->
      <p v-if="o.description" class="desc" data-testid="confirm-description">{{ o.description }}</p>
      <slot />

      <div class="footer">
        <el-button
          data-testid="confirm-cancel"
          :disabled="o.loading"
          @click="settle(false)"
        >
          {{ o.cancelText ?? '取消' }}
        </el-button>
        <el-button
          data-testid="confirm-submit"
          :type="o.danger ? 'danger' : 'primary'"
          :loading="o.loading"
          @click="settle(true)"
        >
          {{ o.confirmText ?? '确定' }}
        </el-button>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.confirm-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.desc {
  margin: 0;
  font-size: var(--text-sm);
  line-height: var(--leading-normal);
  color: var(--text-secondary);
  /* 后端可能返回很长的原因（删除知识库那句会带文档数与切片数），别把弹窗撑爆 */
  overflow-wrap: anywhere;
}

.footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
}
</style>
