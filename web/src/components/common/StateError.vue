<script setup lang="ts">
/**
 * 错误态（Error State）—— 「请求失败」。
 *
 * ## 它取代的是什么
 *
 * Phase A 审计 §6.3 记录的最严重一处混淆：文档加载失败只有
 * `ElMessage.error('文档加载失败：…')` ——**一个 3 秒消失的 toast**。
 * toast 消失后面板落到 `v-if="…length === 0"` 的"没有匹配的文档"，
 * 于是**"加载失败"最终呈现给用户的是"这里没有数据"**。
 *
 * 本组件让失败**停在屏幕上**，并且给一个能真正解决问题的动作（重试）。
 *
 * ## 与 toast 的分工（Phase C 任务书 §14、§15）
 *
 * | 场景 | 用什么 |
 * | --- | --- |
 * | 页面级/面板级加载失败 | **本组件**（常驻 + 重试） |
 * | 一次操作失败（保存、发布、删除） | `ElMessage.error`（一句即走） |
 *
 * 绝不能同时用两个——那会让用户看到同��件事被说两遍。
 * 调用方要判断"这次失败算不算'页面不可用'"，而不是无脑加 toast。
 */
import { computed } from 'vue'
import { WarningFilled } from '@element-plus/icons-vue'

interface Props {
  /** 一句失败。说明"什么失败了"，不是"出错了"。 */
  title?: string
  /** 真实原因。服务端返回什么就显示什么——用户判断要不要重试靠它。 */
  description?: string
  /** 重试按钮文案。 */
  retryLabel?: string
  /** 尺寸，同 StateEmpty。 */
  variant?: 'page' | 'card' | 'inline'
  /** 是否显示重试按钮。传 `false` 表示"重试也没用"（如 404、403）。 */
  retryable?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  title: '加载失败',
  retryLabel: '重试',
  variant: 'card',
  retryable: true
})

const emit = defineEmits<{
  /** 用户点了重试。组件自己不知道要重试什么，由调用方决定。 */
  retry: []
}>()

const showRetry = computed(() => props.retryable)
</script>

<template>
  <div
    class="state-error"
    :class="`is-${variant}`"
    role="alert"
    data-testid="state-error"
    :data-variant="variant"
  >
    <el-icon class="icon" :size="variant === 'page' ? 36 : 24" aria-hidden="true">
      <WarningFilled />
    </el-icon>

    <p class="title">{{ title }}</p>
    <p v-if="description" class="desc">{{ description }}</p>
    <div v-if="showRetry" class="action">
      <el-button size="small" data-testid="state-error-retry" @click="emit('retry')">
        {{ retryLabel }}
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.state-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  color: var(--text-secondary);
}

.is-page {
  padding: var(--space-8) var(--space-4);
  gap: var(--space-2);
}
.is-card {
  padding: var(--space-6) var(--space-4);
  gap: var(--space-2);
}
.is-inline {
  padding: var(--space-4) var(--space-2);
  gap: var(--space-1);
}

.icon {
  color: var(--danger);
  margin-bottom: var(--space-1);
}

.title {
  margin: 0;
  font-weight: var(--weight-medium);
  color: var(--text-primary);
}

.desc {
  margin: 0;
  max-width: 52ch;
  font-size: var(--text-sm);
  color: var(--text-muted);
  line-height: var(--leading-normal);
  /* 后端原话可能很长，但不该把布局撑开 */
  overflow-wrap: anywhere;
}

.action {
  margin-top: var(--space-3);
}
</style>
