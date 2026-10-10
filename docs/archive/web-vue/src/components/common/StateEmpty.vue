<script setup lang="ts">
/**
 * 空态（Empty State）—— 「成功但没有数据」。
 *
 * ## 与错误态的界线（Phase B §13.2）
 *
 * 判别式只有一条：**服务端有没有说成功**。
 * 成功但数据为 0 → 用这个组件；请求失败 → 用 `StateError`。
 * 二者的文案绝不能互相越界（"没有数据"不能用来表达"加载失败"）。
 *
 * ## 为什么强制要有 action
 *
 * Phase B §13.2 规定 Empty 必须"一句状态 + **一个下一步动作**"。
 * 光有"还没有知识库"这句话，用户是卡住的——他不知道该点什么。
 * 所以本组件在开发模式下会对"没给 action"发出警告：
 * **设计规范由组件 API 兜住**，而不是靠每个使用点自觉。
 */
import { computed, onMounted } from 'vue'
import { ChatDotRound, Document, FolderOpened, Search } from '@element-plus/icons-vue'

interface Props {
  /** 一句状态。必须是"用户能懂的话"，不是"暂无数据"。 */
  title: string
  /** 补充说明：为什么空、接下来会发生什么。 */
  description?: string
  /**
   * 尺寸。
   *
   * - `page`：整页为空（新建用户的知识库列表）
   * - `card`：卡片内为空（某个面板里没有条目）
   * - `inline`：行内一小块（表格无数据时的简版）
   */
  variant?: 'page' | 'card' | 'inline'
  /** 图标。默认不显示——多数空态不需要图标，一句话加一个按钮就够。 */
  icon?: 'folder' | 'document' | 'chat' | 'search' | 'none'
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'card',
  icon: 'none'
})

const slots = defineSlots<{
  /** 下一步动作。**应该提供**（缺失时开发模式会警告）。 */
  action?: () => unknown
}>()

const hasAction = computed(() => !!slots.action)

/** 图标组件映射：让 `icon="folder"` 这种字符串能用，而不必每个使用点自己 import。 */
const ICONS = {
  folder: FolderOpened,
  document: Document,
  chat: ChatDotRound,
  search: Search
} as const

const iconComponent = computed(() =>
  props.icon === 'none' ? null : ICONS[props.icon]
)

onMounted(() => {
  if (!hasAction.value && import.meta.env.DEV) {
    console.warn(
      `[StateEmpty] "${props.title}" 没有提供 action 插槽。` +
        '空态必须给用户一个下一步动作，否则他不知道该点什么（Phase B §13.2）。'
    )
  }
})
</script>

<template>
  <div
    class="state-empty"
    :class="`is-${variant}`"
    data-testid="state-empty"
    :data-variant="variant"
  >
    <el-icon v-if="iconComponent" class="icon" :size="variant === 'page' ? 40 : 28" aria-hidden="true">
      <component :is="iconComponent" />
    </el-icon>

    <p class="title">{{ title }}</p>
    <p v-if="description" class="desc">{{ description }}</p>
    <div v-if="hasAction" class="action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.state-empty {
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
  color: var(--text-faint);
  margin-bottom: var(--space-1);
}

.title {
  margin: 0;
  font-weight: var(--weight-medium);
  color: var(--text-primary);
}
.is-page .title {
  font-size: var(--text-lg);
}
.is-card .title {
  font-size: var(--text-base);
}
.is-inline .title {
  font-size: var(--text-sm);
}

.desc {
  margin: 0;
  max-width: 46ch;
  font-size: var(--text-sm);
  color: var(--text-muted);
  line-height: var(--leading-normal);
}

.action {
  margin-top: var(--space-3);
}
</style>
