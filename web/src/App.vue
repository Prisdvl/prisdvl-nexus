<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ChatDotRound, Collection, MagicStick, SwitchButton } from '@element-plus/icons-vue'
import { isLoggedIn, logout, session } from '@/stores/auth'

const route = useRoute()
const router = useRouter()

/*
 * 登录页不套外壳；其余页面都是"左导航 + 主区"的常规产品布局。
 *
 * 注意这里的 isLoggedIn() 读的是 client.ts 里的 tokenRef（响应式）。
 * 早先它直接读 localStorage，导致这个 computed 没有任何响应式依赖、
 * 值被永久缓存 —— 登录成功后外壳不挂载，必须整页刷新才恢复。
 */
const showShell = computed(() => isLoggedIn() && route.name !== 'login')

const navItems = [
  {
    to: '/',
    label: '知识库',
    icon: Collection,
    match: ['knowledge-bases', 'knowledge-base-detail']
  },
  { to: '/agent', label: '对话', icon: ChatDotRound, match: ['agent'] },
  { to: '/agents', label: 'Agent 管理', icon: MagicStick, match: ['agents'] }
]

/** 头像里的首字母：没有头像时比一个通用图标更能区分账号 */
const initial = computed(() => (session.username || '?').slice(0, 1).toUpperCase())

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
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="nav-item"
          :class="{ active: item.match.includes(String(route.name)) }"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>

      <div class="sidebar-foot">
        <div class="who">
          <span class="avatar" aria-hidden="true">{{ initial }}</span>
          <span class="truncate">{{ session.username || '未登录' }}</span>
        </div>
        <el-button
          link
          size="small"
          class="logout"
          :icon="SwitchButton"
          aria-label="退出登录"
          @click="handleLogout"
        >
          退出
        </el-button>
      </div>
    </aside>

    <main class="main">
      <RouterView />
    </main>
  </div>

  <RouterView v-else />
</template>

<style scoped>
/* 外壳自己占满视口、内部各自滚动：
 * 聊天页需要"输入框固定、消息区独立滚动"，这个结构同时满足了两种页面 */
.shell {
  display: grid;
  grid-template-columns: 244px minmax(0, 1fr);
  gap: var(--space-5);
  height: 100vh;
  padding: var(--space-5);
}

/* 侧栏做成一块浮起来的圆角面板（而不是贴边的深色竖条）：
 * 圆润路线的分层靠"面板 + 柔和投影"，不靠分隔线 */
.sidebar {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  min-height: 0;
  padding: var(--space-5) var(--space-3);
  background: var(--surface-raised);
  border-radius: var(--radius-overlay);
  box-shadow: var(--shadow-card);
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
  width: 34px;
  height: 34px;
  border-radius: 12px;
  background: linear-gradient(145deg, var(--accent-400), var(--accent-600));
  color: #fff;
  font-weight: 700;
  font-size: 15px;
  box-shadow: 0 8px 18px -10px rgb(58 143 136 / 90%);
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}

.brand-text strong {
  font-size: 14px;
  color: var(--text-strong);
}

.brand-text small {
  color: var(--text-faint);
  font-size: 11px;
}

.nav {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  flex: 1;
}

/* 导航项 = 胶囊：选中态用浅色填充 + 主色文字，不用左侧竖条（竖条是最模板化的做法） */
.nav-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  min-height: 38px;
  padding: 0 var(--space-4);
  border-radius: var(--radius-chip);
  color: var(--text-muted);
  font-size: 13.5px;
  transition:
    background-color var(--dur-fast) var(--ease-soft),
    color var(--dur-fast) var(--ease-soft),
    transform var(--dur-fast) var(--ease-soft);
}

.nav-item:hover {
  background: var(--surface-sunken);
  color: var(--text-strong);
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
  border-top: 1px solid var(--border-soft);
}

.who {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
  font-size: 13px;
  color: var(--text-muted);
}

.avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 26px;
  height: 26px;
  border-radius: var(--radius-chip);
  background: var(--accent-50);
  color: var(--accent-600);
  font-size: 12px;
  font-weight: 600;
}

.truncate {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.logout {
  flex: none;
  color: var(--text-faint);
}

.logout:hover {
  color: var(--danger);
}

.main {
  min-width: 0;
  min-height: 0;
  overflow: auto;
}

/* 窄屏：侧栏收成图标条，避免主区被挤扁 */
@media (max-width: 900px) {
  .shell {
    grid-template-columns: 68px minmax(0, 1fr);
    gap: var(--space-3);
    padding: var(--space-3);
  }

  .sidebar {
    padding: var(--space-4) var(--space-2);
    align-items: center;
  }

  .brand-text,
  .nav-item span,
  .who .truncate,
  .logout :deep(span) {
    display: none;
  }

  .nav-item {
    justify-content: center;
    padding: 0;
    width: 40px;
  }

  .sidebar-foot {
    flex-direction: column;
    gap: var(--space-2);
  }
}
</style>
