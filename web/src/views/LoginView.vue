<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { login, register } from '@/api/knowledge'
import { saveSession } from '@/stores/auth'

const route = useRoute()
const router = useRouter()

const mode = ref<'login' | 'register'>('login')
const loading = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  username: '',
  password: ''
})

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    // 与后端一致：只允许字母数字下划线连字符（后端 RegisterRequest 的正则）
    { pattern: /^[A-Za-z0-9_-]+$/, message: '只能包含字母、数字、下划线、连字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, message: '至少 8 位', trigger: 'blur' }
  ]
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    if (mode.value === 'register') {
      await register(form.username, form.password)
      ElMessage.success('注册成功，正在登录…')
    }
    const result = await login(form.username, form.password)
    saveSession(result.accessToken, result.username)
    ElMessage.success('欢迎回来，' + result.username)
    await router.push((route.query.redirect as string) || '/')
  } catch {
    // 错误提示已在 axios 拦截器里统一处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth">
    <div class="grid-backdrop" aria-hidden="true"></div>

    <div class="panel">
      <div class="head">
        <span class="mark">N</span>
        <h1 class="shine">Prisdvl-Nexus</h1>
        <p class="sub">知识库 · 混合检索 · 可追溯对话</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="submit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="例如 lky" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="至少 8 位"
            autocomplete="current-password"
            @keyup.enter="submit"
          />
        </el-form-item>

        <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
          {{ mode === 'login' ? '登录' : '注册并登录' }}
        </el-button>
      </el-form>

      <div class="switch">
        <span>{{ mode === 'login' ? '还没有账号？' : '已经有账号？' }}</span>
        <el-button link type="primary" @click="mode = mode === 'login' ? 'register' : 'login'">
          {{ mode === 'login' ? '注册一个' : '去登录' }}
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 认证页：中性色表面 + 单列表单（不做"品牌色大色块 + 表单"那种模板结构） */
/* 点阵背景是 fixed 定位，按绘制顺序会盖在普通流内容之上；
   因此把除背景外的内容抬到 z-index: 1，保证卡片在背景之前 */
.auth > :not(.grid-backdrop) {
  position: relative;
  z-index: 1;
}

.auth {
  display: grid;
  place-items: center;
  min-height: 100vh;
  padding: var(--space-5);
  /* 顶部一层极浅的主色晕染（不是模糊光斑：只有颜色渐变，没有大范围 blur） */
  background:
    radial-gradient(120% 60% at 50% 0%, var(--accent-50) 0%, transparent 60%),
    var(--surface-page);
}

.panel {
  width: 100%;
  max-width: 400px;
  padding: var(--space-7) var(--space-6) var(--space-6);
  background: var(--surface-raised);
  border-radius: var(--radius-overlay);
  box-shadow: var(--shadow-pop);
}

.head {
  text-align: center;
  margin-bottom: var(--space-6);
}

.mark {
  display: inline-grid;
  place-items: center;
  width: 46px;
  height: 46px;
  border-radius: 16px;
  background: linear-gradient(145deg, var(--accent-400), var(--accent-600));
  color: #fff;
  font-weight: 700;
  font-size: 20px;
  margin-bottom: var(--space-4);
  box-shadow: 0 14px 28px -14px rgb(58 143 136 / 90%);
}

h1 {
  font-size: 20px;
}

.sub {
  margin: var(--space-2) 0 0;
  color: var(--text-muted);
  font-size: 13px;
}

/* 登录按钮做成胶囊：页面上唯一的"实心大色块"，圆润一点更像邀请而不是命令 */
.submit {
  width: 100%;
  height: 44px;
  margin-top: var(--space-2);
  border-radius: var(--radius-chip);
  font-size: 15px;
}

.switch {
  margin-top: var(--space-5);
  text-align: center;
  font-size: 13px;
  color: var(--text-muted);
}

:deep(.el-form-item__label) {
  color: var(--text-muted);
  font-size: 12.5px;
  padding-bottom: var(--space-1);
}
</style>
