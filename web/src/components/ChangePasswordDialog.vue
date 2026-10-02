<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { changePassword } from '@/api/user'
import { logout } from '@/stores/auth'

/**
 * 修改密码（5.6）。
 *
 * <p>这个弹窗最要紧的一行不是"校验通过"，而是**成功之后做什么**：
 * 服务端在改密时会把这个用户**全部**已签发的令牌一起吊销（包括发起本次请求的
 * 这一支），因为它就是这么个语义——改了密码，所有设备下线。
 *
 * <p>所以这里必须主动 `logout()` 并跳登录页，且提示语要说清"用新密码重新登录"。
 * 如果什么都不做、等下一个请求撞上 401，用户看到的是拦截器那句"登录已过期"——
 * 一个刚刚主动改了密码的人被告知"你的登录过期了"，这中间少掉的那句话
 * （是我自己改的密码造成的）会让人以为是系统出了问题，甚至以为被踢下线。
 */
const visible = defineModel<boolean>({ required: true })

const router = useRouter()
const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    // 与后端 ChangePasswordRequest 的 @Size(min=8, max=64) 对齐
    { min: 8, max: 64, message: '新密码长度需在 8–64 之间', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再输入一次新密码', trigger: 'blur' },
    /*
     * 这一条是**前端独有**的校验，不是把后端的规则抄一遍：
     * 确认框根本不发给服务端（ChangePasswordRequest 只有 old/new 两个字段），
     * 服务端无从校验"两次输入是否一致"。抄后端已有的规则只会在两边漂移时
     * 变成两套说法，所以这里只放后端管不到的那一条。
     */
    {
      validator: (_rule, value, callback) => {
        if (value !== form.newPassword) {
          callback(new Error('两次输入的新密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

/** 每次打开都清空：把上一次填过的密码留在表单里，既没必要也容易被瞟见。 */
watch(visible, (open) => {
  if (open) {
    form.oldPassword = ''
    form.newPassword = ''
    form.confirmPassword = ''
    formRef.value?.clearValidate()
  }
})

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await changePassword(form.oldPassword, form.newPassword)
    visible.value = false
    // 顺序不能反：先 logout（清令牌）再提示跳转，否则跳转过程中的任何一次请求
    // 都会带着已经失效的旧令牌，把 401 那条"登录已过期"的提示又勾出来
    logout()
    ElMessage.success('密码已修改，请用新密码重新登录')
    await router.push({ name: 'login' })
  } catch {
    // 原密码错误（1001，不是 401）等提示已由 axios 拦截器统一展示，弹窗保持打开让用户重填
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="修改密码" width="420px" append-to-body>
    <p class="hint">
      修改成功后，<b>当前设备也会退出登录</b>——密码变了，之前签发的所有登录状态都会立即失效。
    </p>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      @submit.prevent="submit"
    >
      <el-form-item label="原密码" prop="oldPassword">
        <el-input
          v-model="form.oldPassword"
          type="password"
          show-password
          autocomplete="current-password"
        />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input
          v-model="form.newPassword"
          type="password"
          show-password
          placeholder="8–64 位"
          autocomplete="new-password"
        />
      </el-form-item>
      <el-form-item label="确认新密码" prop="confirmPassword">
        <el-input
          v-model="form.confirmPassword"
          type="password"
          show-password
          autocomplete="new-password"
          @keyup.enter="submit"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">确认修改</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 var(--space-4);
  padding: var(--space-2) var(--space-3);
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
  color: var(--text-muted);
  font-size: 12.5px;
  line-height: 1.7;
}

:deep(.el-form-item__label) {
  color: var(--text-muted);
  font-size: 12.5px;
  padding-bottom: var(--space-1);
}
</style>
