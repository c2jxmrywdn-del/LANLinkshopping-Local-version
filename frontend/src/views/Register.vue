<template>
  <div class="wrap">
    <a-card class="box" title="注册账号">
      <a-alert
        v-if="inviteCode"
        class="invite-alert"
        type="info"
        show-icon
        :message="`已通过邀请链接注册，注册后自动绑定邀请人（邀请码 ${inviteCode}）。`"
      />
      <a-form :model="form" layout="vertical" @finish="submit">
        <a-form-item label="注册身份">
          <div class="role-note">
            <div>注册后获得普通用户（采购方）身份。</div>
            <div>
              如需成为商户，请提交<a href="/merchant" @click.prevent="router.push('/merchant')">商户入驻申请</a>。
            </div>
          </div>
        </a-form-item>
        <a-form-item label="手机号" :rules="[{ required: true, message: '请输入手机号' }, { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确' }]">
          <a-input v-model:value="form.phone" size="large" />
        </a-form-item>
        <a-form-item label="昵称" name="nickname" :rules="[{ required: true, whitespace: true, message: '请输入昵称' }, { max: 32, message: '昵称不能超过 32 字' }]">
          <a-input v-model:value="form.nickname" size="large" :maxlength="32" />
        </a-form-item>
        <a-form-item label="密码" :rules="[{ required: true, message: '请输入密码' }]">
          <a-input-password v-model:value="form.password" size="large" />
        </a-form-item>
        <a-form-item name="agree" :rules="[{ validator: validateAgree }]">
          <a-checkbox v-model:checked="agree">我已阅读并同意</a-checkbox>
          <span class="agree-links">
            <a @click.prevent="$router.push({ name: 'legal-doc', params: { docKey: 'terms' } })">《用户服务协议》</a>
            <a @click.prevent="$router.push({ name: 'legal-doc', params: { docKey: 'privacy' } })">《隐私政策》</a>
          </span>
        </a-form-item>
        <a-button type="primary" html-type="submit" block size="large" :loading="loading">注册</a-button>
        <div class="tip">已有账号？<a @click="$router.push('/login')">去登录</a></div>
      </a-form>
    </a-card>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useUserStore } from '../store/user'

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const loading = ref(false)
const agree = ref(false)
const form = reactive({ phone: '', nickname: '', password: '' })
const inviteCode = computed(() => {
  const raw = Array.isArray(route.query.invite) ? route.query.invite[0] : route.query.invite
  return typeof raw === 'string' ? raw.trim() : ''
})

function validateAgree() {
  if (!agree.value) return Promise.reject('请先阅读并同意用户服务协议与隐私政策')
  return Promise.resolve()
}

async function submit() {
  loading.value = true
  try {
    const payload = { ...form }
    if (inviteCode.value) payload.inviteCode = inviteCode.value
    await user.register(payload)
    message.success('注册成功，请登录')
    router.push('/login')
  } finally { loading.value = false }
}

defineExpose({ submit })
</script>

<style scoped>
.wrap { display: flex; justify-content: center; align-items: center; min-height: 70vh; }
.box { width: 400px; }
.invite-alert { margin-bottom: 16px; }
.role-note { color: #555; line-height: 1.8; }
.role-note a { color: #1E6EB8; }
.tip { text-align: center; margin-top: 12px; color: #888; }
.agree-links { font-size: 13px; }
.agree-links a { color: #1E6EB8; cursor: pointer; }
</style>
