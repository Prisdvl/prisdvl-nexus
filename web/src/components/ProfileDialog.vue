<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { getProfile, updateProfile, type UserProfile } from '@/api/user'
import { applyProfile, session } from '@/stores/auth'

/**
 * 个人资料（5.6 · P1-15）。
 *
 * <p>能改的只有昵称和邮箱。**用户名不给改**：它是登录凭证的一部分，
 * 改了会让用户困惑于"以后到底该用哪个登录"，服务端也只放开这两个字段。
 *
 * <p>字段语义照抄服务端（{@link updateProfile} 的说明）：不传 = 不修改，传空串 = 清空。
 * 这里把两个输入框的当前值**原样发回去**——表单是从服务端读来的，原值回传等于没改，
 * 所以不需要再去算"哪几个字段动过"（那套 diff 逻辑自己就会错，比如用户把昵称
 * 删掉再原样打回来，diff 说没变、用户以为改了什么）。
 *
 * <p>唯一要小心的是"清空"：空输入框发的是空串，服务端据此落 NULL。
 * 所以界面上给一句提示，别让用户以为清空只是没生效。
 */
const visible = defineModel<boolean>({ required: true })

/** 由父组件决定怎么打开改密码弹窗：两个弹窗叠在一起观感很差，所以先关自己 */
const emit = defineEmits<{ changePassword: [] }>()

const formRef = ref<FormInstance>()
const loading = ref(false)
const saving = ref(false)
const profile = ref<UserProfile | null>(null)

const form = reactive({
  nickname: '',
  email: ''
})

const rules: FormRules = {
  // 与后端 UpdateProfileRequest 的 @Size/@Email 对齐（空值合法：空 = 清空）
  nickname: [{ max: 64, message: '昵称长度不能超过 64', trigger: 'blur' }],
  email: [
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
    { max: 128, message: '邮箱长度不能超过 128', trigger: 'blur' }
  ]
}

watch(visible, async (open) => {
  if (!open) return
  formRef.value?.clearValidate()
  loading.value = true
  try {
    const current = await getProfile()
    profile.value = current
    form.nickname = current.nickname ?? ''
    form.email = current.email ?? ''
    // 顺手把会话里的昵称/角色刷新一次：这个请求已经发了，就地同步比让外面再拉一遍省一次往返。
    // （App.vue 挂载时也会拉一次，那是为了让侧栏在**打开这个弹窗前**就显示昵称，两处目的不同）
    applyProfile(current)
  } catch {
    // 拉不到资料（网络/令牌问题）时提示已由拦截器给出；这里的处理是**不让弹窗假装是空的**——
    // 空表单会被用户当成"我的资料没了"，一保存就把真实昵称抹掉
    profile.value = null
    visible.value = false
  } finally {
    loading.value = false
  }
})

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    const updated = await updateProfile({
      nickname: form.nickname.trim(),
      email: form.email.trim()
    })
    profile.value = updated
    applyProfile(updated)
    ElMessage.success('资料已保存')
    visible.value = false
  } catch {
    // 邮箱被占用（2007/409）等由拦截器展示后端原话，弹窗留着让用户改一个再试
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="账号设置" width="440px">
    <div v-loading="loading" class="body">
      <div class="who">
        <span class="avatar" aria-hidden="true">{{ (form.nickname || session.username || '?').slice(0, 1).toUpperCase() }}</span>
        <span class="names">
          <b>{{ form.nickname || session.username }}</b>
          <small class="num">@{{ session.username }}</small>
        </span>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="submit"
      >
        <el-form-item label="用户名">
          <!-- 只读展示：用户名是登录凭证，服务端也不接受修改。
               用 disabled 而不是纯文本，是为了让"这里改不了"看起来是设计而不是缺漏 -->
          <el-input :model-value="session.username" disabled />
        </el-form-item>

        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="form.nickname" placeholder="留空则显示用户名" maxlength="64" />
        </el-form-item>

        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" placeholder="用于通知，可留空" maxlength="128" />
        </el-form-item>
      </el-form>

      <p v-if="profile?.roles?.length" class="roles">
        角色：<el-tag v-for="role in profile.roles" :key="role" size="small" effect="plain">
          {{ role }}
        </el-tag>
      </p>

      <p class="hint">把昵称或邮箱清空再保存，就是删除该项资料（不是"没改动"）。</p>
    </div>

    <template #footer>
      <el-button link type="primary" class="password-entry" @click="emit('changePassword')">
        修改密码
      </el-button>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.body {
  min-height: 120px;
}

.who {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  margin-bottom: var(--space-4);
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
}

.avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 34px;
  height: 34px;
  border-radius: var(--radius-chip);
  background: var(--accent-50);
  color: var(--accent-600);
  font-weight: 600;
}

.names {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.4;
}

.names b {
  color: var(--text-strong);
  font-size: 13.5px;
}

.names small {
  color: var(--text-faint);
  font-size: 11.5px;
}

.roles {
  margin: 0 0 var(--space-2);
  color: var(--text-muted);
  font-size: 12.5px;
}

.roles :deep(.el-tag) {
  margin-left: var(--space-1);
}

.hint {
  margin: 0;
  color: var(--text-faint);
  font-size: 11.5px;
  line-height: 1.7;
}

/* 次要操作放左边（与"取消/保存"这组主操作拉开），避免误点 */
.password-entry {
  float: left;
}

:deep(.el-form-item__label) {
  color: var(--text-muted);
  font-size: 12.5px;
  padding-bottom: var(--space-1);
}
</style>
