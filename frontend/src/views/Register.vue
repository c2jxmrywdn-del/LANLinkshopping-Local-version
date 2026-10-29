<template>
  <div class="wrap">
    <a-card class="box" title="注册账号">
      <a-form :model="form" layout="vertical" @finish="submit">
        <a-form-item label="注册身份">
          <a-radio-group v-model:value="form.roleCode" button-style="solid">
            <a-radio-button value="buyer">采购方</a-radio-button>
            <a-radio-button value="merchant">商户/供应商</a-radio-button>
          </a-radio-group>
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
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useUserStore } from '../store/user'

const router = useRouter()
const user = useUserStore()
const loading = ref(false)
const agree = ref(false)
const form = reactive({ roleCode: 'buyer', phone: '', nickname: '', password: '' })

function validateAgree() {
  if (!agree.value) return Promise.reject('请先阅读并同意用户服务协议与隐私政策')
  return Promise.resolve()
}

async function submit() {
  loading.value = true
  try {
    await user.register(form)
    message.success('注册成功，请登录')
    router.push('/login')
  } finally { loading.value = false }
}
</script>

<style scoped>
.wrap { display: flex; justify-content: center; align-items: center; min-height: 70vh; }
.box { width: 400px; }
.tip { text-align: center; margin-top: 12px; color: #888; }
.agree-links { font-size: 13px; }
.agree-links a { color: #1E6EB8; cursor: pointer; }
</style>
