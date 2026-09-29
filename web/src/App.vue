<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Collection, SwitchButton } from '@element-plus/icons-vue'
import { isLoggedIn, logout, session } from '@/stores/auth'

const route = useRoute()
const router = useRouter()

// 登录页不套外壳；其余页面都是"左导航 + 主区"的常规产品布局
const showShell = computed(() => isLoggedIn() && route.name !== 'login')

async function handleLogout() {
  await ElMessageBox.confirm('退出后需要重新登录。', '确认退出？', {
    confirmButtonText: '退出',
    cancelButtonText: '取消',
    type: 'warning'
  })
  logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div v-if="showShell" class="shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="mark">N</span>
        <span class="brand-text">
          <strong>Prisdvl-Nexus</strong>
          <small>知识库控制台</small>
        </span>
      </div>

      <nav class="nav">
        <RouterLink
          to="/"
          class="nav-item"
          :class="{ active: route.name === 'knowledge-bases' || route.name === 'knowledge-base-detail' }"
        >
          <el-icon><Collection /></el-icon>
          <span>知识库</span>
        </RouterLink>
      </nav>

      <div class="sidebar-foot">
        <div class="who">
          <span class="dot" />
          <span class="truncate">{{ session.username || '未登录' }}</span>
        </div>
        <el-button link size="small" :icon="SwitchButton" @click="handleLogout">退出</el-button>
      </div>
    </aside>

    <main class="main">
      <RouterView />
    </main>
  </div>

  <RouterView v-else />
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: 232px 1fr;
  min-height: 100vh;
}

/* 侧栏用"浅色微染"而不是全深色——全深色侧栏是最常见的模板感来源 */
.sidebar {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  padding: var(--space-5) var(--space-3);
  background: var(--neutral-100);
  border-right: 1px solid var(--neutral-200);
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: 0 var(--space-2);
}

.mark {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: var(--accent-500);
  color: #fff;
  font-weight: 700;
  font-size: 15px;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.brand-text strong {
  font-size: 14px;
  letter-spacing: -0.01em;
}

.brand-text small {
  color: var(--neutral-500);
  font-size: 11px;
}

.nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: 8px var(--space-3);
  border-radius: var(--radius-input);
  color: var(--neutral-700);
  font-size: 13.5px;
  transition: background-color 120ms ease-out, color 120ms ease-out;
}

.nav-item:hover {
  background: var(--neutral-200);
}

.nav-item.active {
  background: var(--accent-50);
  color: var(--accent-600);
  font-weight: 600;
}

.sidebar-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-2) 0;
  border-top: 1px solid var(--neutral-200);
}

.who {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: 13px;
  color: var(--neutral-600);
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ok);
  flex: none;
}

.truncate {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main {
  min-width: 0;
  padding: var(--space-6) var(--space-6) var(--space-7);
}
</style>
