<script setup lang="ts">
/**
 * 加载态（Loading State）—— 四种粒度。
 *
 * ## 为什么必须分粒度（任务书 §五 B）
 *
> 不能所有地方都是全屏 loading。
 *
 * 现状的问题很具体：`KnowledgeBaseDetailView:612` 整页 `v-loading`，
 * 而"删一篇文档"走的是 `busy = 'delete:' + doc.id`（按文档独立）——
 * 这一点 Phase A 已确认是**全仓最好的范例**。但反过来说，
 * 面板级的等待在别处是靠 `el-button :loading` 零散处理的，
 * 于是"哪些等待有反馈、哪些没有"完全看实现者心情。
 *
 * 有了这四种粒度，"等待"这件事就变成**显式声明**而不是碰运气。
 *
 * | variant | 用在哪 | 形态 |
 * | --- | --- | --- |
 * | `page` | 整页首次加载 | 居中转圈 + 文案 |
 * | `section` | 某个面板/卡片加载 | 面板内居中转圈 |
 * | `list` | 列表加载 | **骨架屏**（转圈在长列表里看不出在加载什么） |
 * | `inline` | 按钮/一行文字旁 | 小转圈，无文案 |
 */
import { Loading } from '@element-plus/icons-vue'

interface Props {
  variant?: 'page' | 'section' | 'list' | 'inline'
  /** `list` 时渲染几行骨架。 */
  rows?: number
  /** 转圈旁的说明。`inline` 忽略（那一档放不下字）。 */
  label?: string
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'section',
  rows: 3
})

/** 骨架行数夹到 1~8：0 行等于什么都不显示，十几行会让长列表等待时页面全是灰条。 */
const skeletonRows = () => Math.min(8, Math.max(1, props.rows))
</script>

<template>
  <!-- 骨架屏：列表专用。aria-busy 让读屏软件知道"在加载"而不是"这里没内容" -->
  <div
    v-if="variant === 'list'"
    class="state-loading-skeleton"
    aria-busy="true"
    data-testid="state-loading"
    data-variant="list"
  >
    <div v-for="row in skeletonRows()" :key="row" class="skeleton-row" :style="{ '--i': row }" />
  </div>

  <div
    v-else
    class="state-loading"
    :class="`is-${variant}`"
    aria-busy="true"
    data-testid="state-loading"
    :data-variant="variant"
  >
    <el-icon class="icon" :size="variant === 'inline' ? 14 : 24" aria-hidden="true">
      <Loading class="spin" />
    </el-icon>
    <span v-if="label && variant !== 'inline'" class="label">{{ label }}</span>
  </div>
</template>

<style scoped>
.state-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
}

.is-page {
  min-height: 40vh;
  flex-direction: column;
  gap: var(--space-3);
}
.is-section {
  min-height: 120px;
  flex-direction: column;
  gap: var(--space-2);
}
.is-inline {
  /* 按钮内：贴着内容，不额外占位 */
  display: inline-flex;
}

.label {
  font-size: var(--text-sm);
}

/* 只动 transform，遵守 tokens.css 的动效约束 */
.spin {
  animation: loading-spin 0.9s linear infinite;
}

@keyframes loading-spin {
  to {
    transform: rotate(360deg);
  }
}

/* ---------- 骨架屏 ---------- */

.state-loading-skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-1) 0;
}

.skeleton-row {
  height: 14px;
  border-radius: var(--radius-full);
  background: var(--surface-hover);
  /* 逐行错落，避免整片同时闪——那是"整页重绘"的观感 */
  animation: skeleton-pulse 1.4s var(--ease-soft) infinite;
  animation-delay: calc((var(--i) - 1) * 90ms);
}

/* 末行短一些，更像真实列表而不是等宽色块 */
.skeleton-row:last-child {
  width: 62%;
}

@keyframes skeleton-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.45;
  }
}
</style>
