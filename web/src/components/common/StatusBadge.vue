<script setup lang="ts">
/**
 * 状态徽标 —— 用户状态（L2）的唯一呈现方式。
 *
 * 替代"每个视图自己写一份 `STATUS_TEXT` + `el-tag`"的现状
 * （改造前 `AgentsView:82` 与 `KnowledgeBaseListView:196` 各有一套）。
 *
 * **只负责呈现**：不知道 Agent 是什么、也不知道任务跑到哪一步，
 * 只接收一个 {@link StatusKind}（或后端原始枚举）并按 `status.ts` 的词表显示。
 */
import { computed } from 'vue'
import { STATUS_META, toStatusKind, type StatusKind } from './status'

interface Props {
  /**
   * 状态。
   *
   * 同时接受两种输入：已归一的 `StatusKind`（`'running'`）与
   * 后端原始枚举（`'RUNNING'`）。混用不会出错——
   * 大写形式不在 `StatusKind` 名单里，按后端枚举解析。
   */
  status: StatusKind | string
  /** 是否显示进度点（进行中的状态默认显示，让"还在跑"这件事持续可见）。 */
  showDot?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  showDot: true
})

const KIND_NAMES = Object.keys(STATUS_META) as StatusKind[]

const kind = computed<StatusKind>(() =>
  KIND_NAMES.includes(props.status as StatusKind)
    ? (props.status as StatusKind)
    : toStatusKind(props.status)
)

const meta = computed(() => STATUS_META[kind.value])
</script>

<template>
  <span
    class="status-badge"
    :class="[`is-${meta.tone}`, { 'is-pending': meta.pending }]"
    :data-testid="`status-badge-${kind}`"
  >
    <span v-if="showDot" class="dot" aria-hidden="true" />
    {{ meta.label }}
  </span>
</template>

<style scoped>
/*
 * 刻意**不用 el-tag**：它带自己的边框/背景/字号，四个页面各写一遍覆写样式，
 * 而这里只需要"一枚柔和的色块 + 一个可留意的点"。
 * 视觉沿用现有页面的 el-tag 观感（plain 风格：浅底 + 同色文字）。
 */
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: 0 var(--space-2);
  height: 22px;
  font-size: var(--text-xs);
  line-height: 1;
  white-space: nowrap;
  border-radius: var(--radius-full);
  border: 1px solid transparent;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: var(--radius-full);
  background: currentcolor;
  flex: none;
}

/*
 * 进行中的状态：点做呼吸动画，提醒"还没结束"。
 * 只动 opacity，不触发布局（tokens.css 的动效自我约束）。
 */
.is-pending .dot {
  animation: status-pulse 1.4s var(--ease-soft) infinite;
}

@keyframes status-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.3;
  }
}

.is-primary {
  color: var(--primary);
  background: var(--primary-soft);
  border-color: var(--primary-border);
}
.is-success {
  color: var(--success);
  background: var(--success-soft);
  border-color: transparent;
}
.is-warning {
  color: var(--warning);
  background: var(--warning-soft);
  border-color: transparent;
}
.is-danger {
  color: var(--danger);
  background: var(--danger-soft);
  border-color: transparent;
}
.is-info {
  color: var(--info);
  background: var(--info-soft);
  border-color: transparent;
}
</style>
