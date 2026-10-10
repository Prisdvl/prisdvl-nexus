<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
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
  password: '',
  confirmPassword: ''
})

/**
 * 校验规则随模式变化：登录不需要"确认密码"，注册必须一致——
 * 否则用户会把一个自己打错的密码当成登录密码记下来，下次直接登不进来。
 * 后端会重新校验一遍（前端校验只是体验，不是安全边界）。
 */
const rules = computed<FormRules>(() => ({
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度 3–32 个字符', trigger: 'blur' },
    // 与后端一致：只允许字母数字下划线连字符（后端 RegisterRequest 的正则）
    { pattern: /^[A-Za-z0-9_-]+$/, message: '只能包含字母、数字、下划线、连字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, message: '至少 8 位', trigger: 'blur' }
  ],
  confirmPassword:
    mode.value === 'register'
      ? [
          { required: true, message: '请再次输入密码', trigger: 'blur' },
          {
            validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
              if (value === form.password) callback()
              else callback(new Error('两次输入的密码不一致'))
            },
            trigger: ['blur', 'change']
          }
        ]
      : []
}))

/** 切模式时清掉残留输入与校验红字，避免"切回登录还红着一片" */
function switchMode() {
  mode.value = mode.value === 'login' ? 'register' : 'login'
  form.password = ''
  form.confirmPassword = ''
  formRef.value?.clearValidate()
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  // 首尾空格在提交前归一：后端也 trim，但前端先处理，
  // 免得"空格 + 已存在用户名"这类输入在校验层通过、到服务端才碰撞
  const username = form.username.trim()
  loading.value = true
  try {
    if (mode.value === 'register') {
      await register(username, form.password)
      ElMessage.success('注册成功，正在登录…')
    }
    const result = await login(username, form.password)
    // 用户名从 user 里取（UA-002）：登录响应没有顶层 username，
    // 早先这里拼出来的是"欢迎回来，undefined"。兜底用刚输入的用户名，
    // 保证任何情况下都不会把 undefined 显示出来或写进 localStorage。
    const name = result.user?.username ?? username
    saveSession(result.accessToken, name)
    ElMessage.success('欢迎回来，' + name)
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
          <el-input
            v-model="form.username"
            data-testid="auth-username"
            placeholder="例如 lky"
            autocomplete="username"
          />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            data-testid="auth-password"
            type="password"
            show-password
            placeholder="至少 8 位"
            :autocomplete="mode === 'register' ? 'new-password' : 'current-password'"
            @keyup.enter="submit"
          />
        </el-form-item>
        <!-- 只有注册需要确认密码：登录少一个框，少一次无谓的输入 -->
        <el-form-item v-if="mode === 'register'" label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            data-testid="auth-confirm-password"
            type="password"
            show-password
            placeholder="再输入一次"
            autocomplete="new-password"
            @keyup.enter="submit"
          />
        </el-form-item>

        <el-button
          type="primary"
          size="large"
          class="submit"
          data-testid="auth-submit"
          :loading="loading"
          @click="submit"
        >
          {{ mode === 'login' ? '登录' : '注册并登录' }}
        </el-button>
      </el-form>

      <div class="switch">
        <span>{{ mode === 'login' ? '还没有账号？' : '已经有账号？' }}</span>
        <el-button link type="primary" data-testid="auth-switch-mode" @click="switchMode">
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
