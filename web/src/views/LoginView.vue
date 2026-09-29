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
    <div class="panel">
      <div class="head">
        <span class="mark">N</span>
        <h1>Prisdvl-Nexus</h1>
        <p class="sub">个人知识库 · 混合检索（BM25 + 向量）</p>
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
.auth {
  display: grid;
  place-items: center;
  min-height: 100vh;
  background: var(--neutral-100);
  padding: var(--space-5);
}

.panel {
  width: 100%;
  max-width: 380px;
  padding: var(--space-6);
  background: var(--neutral-0);
  border: 1px solid var(--neutral-200);
  border-radius: var(--radius-overlay);
  box-shadow: var(--shadow-raised);
}

.head {
  text-align: center;
  margin-bottom: var(--space-5);
}

.mark {
  display: inline-grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: 11px;
  background: var(--accent-500);
  color: #fff;
  font-weight: 700;
  font-size: 18px;
  margin-bottom: var(--space-3);
}

h1 {
  font-size: 19px;
}

.sub {
  margin: 6px 0 0;
  color: var(--neutral-500);
  font-size: 13px;
}

.submit {
  width: 100%;
  margin-top: var(--space-2);
}

.switch {
  margin-top: var(--space-4);
  text-align: center;
  font-size: 13px;
  color: var(--neutral-500);
}
</style>
