<template>
  <main class="wrap">
    <a-card class="box">
      <template #title><span class="ll-wordmark ll-wordmark-on-light"><span class="ll-lan">LAN</span><span class="ll-link">Link</span><span class="ll-shopping">shopping</span></span></template>
      <a-tabs v-model:activeKey="tab" centered>
        <!-- ===== 登录 ===== -->
        <a-tab-pane key="login" tab="登录">
          <!-- 第一步：手机号 + 密码 -->
          <a-form v-if="!twofa" :model="form" layout="vertical" @finish="submit">
            <a-form-item label="手机号" :rules="[{ required: true, message: '请输入手机号' }]">
              <a-input v-model:value="form.phone" placeholder="请输入已注册的手机号" size="large" />
            </a-form-item>
            <a-form-item label="密码" :rules="[{ required: true, message: '请输入密码' }]">
              <a-input-password v-model:value="form.password" placeholder="请输入注册时设置的密码" size="large" />
            </a-form-item>
            <a-button type="primary" html-type="submit" block size="large" :loading="loading">登录</a-button>
            <div class="tip">首次使用？请切换到“注册”创建账号。</div>
          </a-form>

          <!-- 第二步：两步验证动态码 -->
          <a-form v-else :model="twofa" layout="vertical" @finish="submit2fa">
            <a-alert type="info" show-icon class="twofa-tip">
              <template #message>该账号已开启两步验证，请输入验证器 App 上的动态验证码（绑定手机 {{ twofa.hint }}）</template>
            </a-alert>
            <a-form-item label="动态验证码" :rules="[{ required: true, pattern: /^\d{6}$/, message: '请填写 6 位动态验证码' }]">
              <a-input v-model:value="twofa.code" placeholder="6 位数字" size="large" maxlength="6" autofocus />
            </a-form-item>
            <a-button type="primary" html-type="submit" block size="large" :loading="loading">验证并登录</a-button>
            <div class="tip"><a @click="reset2fa">返回重新登录</a></div>
          </a-form>

        </a-tab-pane>

        <!-- ===== 注册 ===== -->
        <a-tab-pane key="register" tab="注册">
          <a-form :model="reg" layout="vertical" @finish="doRegister">
            <a-form-item label="注册身份">
              <a-radio-group v-model:value="reg.roleCode" button-style="solid">
                <a-radio-button value="buyer">采购方</a-radio-button>
                <a-radio-button value="merchant">商户/供应商</a-radio-button>
              </a-radio-group>
            </a-form-item>
            <a-form-item label="手机号" :rules="[{ required: true, message: '请输入手机号' }, { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确' }]">
              <a-input v-model:value="reg.phone" size="large" placeholder="11 位手机号" />
            </a-form-item>
            <a-form-item label="邮箱（选填）" :rules="reg.email ? [{ type: 'email', message: '邮箱格式不正确' }] : []">
              <a-input v-model:value="reg.email" size="large" placeholder="name@example.com" />
            </a-form-item>
            <a-form-item label="昵称">
              <a-input v-model:value="reg.nickname" size="large" />
            </a-form-item>
            <a-form-item label="验证码" :rules="[{ required: true, message: '请输入验证码' }]">
              <div class="code-row">
                <a-input v-model:value="reg.code" size="large" placeholder="6 位验证码" maxlength="6" />
                <a-button size="large" :disabled="cd > 0" @click="sendCode">{{ cd > 0 ? cd + 's' : '获取验证码' }}</a-button>
              </div>
            </a-form-item>
            <a-form-item label="密码" :rules="[{ required: true, message: '请输入密码' }]">
              <a-input-password v-model:value="reg.password" size="large" placeholder="至少 8 位，含字母与数字" />
              <div class="strength">
                <span class="bars"><i :class="['bar', strength >= 1 ? 'on s1' : '']"></i><i :class="['bar', strength >= 2 ? 'on s2' : '']"></i><i :class="['bar', strength >= 3 ? 'on s3' : '']"></i></span>
                <em>{{ strengthText }}</em>
              </div>
            </a-form-item>
            <a-form-item label="确认密码" :rules="[{ required: true, message: '请再次输入密码' }]">
              <a-input-password v-model:value="reg.confirm" size="large" />
            </a-form-item>
            <a-button type="primary" html-type="submit" block size="large" :loading="regLoading">注册</a-button>
            <div class="tip">已有账号？<a @click="tab = 'login'">去登录</a></div>
          </a-form>
        </a-tab-pane>
      </a-tabs>
    </a-card>
  </main>
</template>

<script setup>
import { reactive, ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { useUserStore } from '../store/user'
import { useCartStore } from '../store/cart'

const router = useRouter(); const route = useRoute()
const user = useUserStore(); const cart = useCartStore()
const tab = ref('login')
const loading = ref(false)
const regLoading = ref(false)
const form = reactive({ phone: '', password: '' })
const reg = reactive({ roleCode: 'buyer', phone: '', email: '', nickname: '', code: '', password: '', confirm: '', _srvCode: '' })

// 两步验证步骤状态：{ loginTicket, hint, code }
const twofa = ref(null)
function reset2fa() { twofa.value = null; loading.value = false }

// 密码强度：长度 / 字母+数字 / 特殊符号 计分
const strength = computed(() => {
  const p = reg.password || ''
  let s = 0
  if (p.length >= 8) s++
  if (/[a-zA-Z]/.test(p) && /\d/.test(p)) s++
  if (/[^a-zA-Z0-9]/.test(p)) s++
  return p ? s : 0
})
const strengthText = computed(() => ['', '弱', '中', '强'][strength.value] || '')

// 验证码（演示：未接真实短信，随机码以提示返回）
const cd = ref(0)
let timer = null
function sendCode() {
  if (!/^1[3-9]\d{9}$/.test(reg.phone)) { message.warning('请先填写正确的手机号'); return }
  const code = String(Math.floor(100000 + Math.random() * 900000))
  reg._srvCode = code
  message.info(`演示验证码：${code}（未接真实短信）`)
  cd.value = 60
  timer = setInterval(() => { cd.value--; if (cd.value <= 0) clearInterval(timer) }, 1000)
}

async function afterLoginSuccess() {
  await cart.load()
  message.success('登录成功')
  router.push(route.query.redirect || '/')
}

async function submit() {
  if (!form.phone) return
  loading.value = true
  try {
    const res = await user.login({ phone: form.phone, password: form.password })
    if (res && res.require2fa) {
      // 两步验证第二步：暂不建立会话
      twofa.value = { loginTicket: res.loginTicket, hint: res.hint || form.phone, code: '' }
      return
    }
    await afterLoginSuccess()
  } finally { loading.value = false }
}

async function submit2fa() {
  if (!/^\d{6}$/.test(twofa.value.code)) { message.warning('请填写 6 位动态验证码'); return }
  loading.value = true
  try {
    await user.login2fa(twofa.value.loginTicket, twofa.value.code)
    await afterLoginSuccess()
  } finally {
    loading.value = false
    reset2fa()
  }
}

async function doRegister() {
  if (strength.value < 2) { message.warning('密码强度不足，至少 8 位且含字母与数字'); return }
  if (reg.password !== reg.confirm) { message.error('两次输入的密码不一致'); return }
  if (!reg._srvCode || reg.code !== reg._srvCode) { message.error('验证码错误或未获取'); return }
  regLoading.value = true
  try {
    await user.register({ phone: reg.phone, password: reg.password, nickname: reg.nickname, roleCode: reg.roleCode })
    message.success('注册成功，请登录')
    form.phone = reg.phone; form.password = reg.password
    tab.value = 'login'
  } finally { regLoading.value = false }
}
</script>

<style scoped>
.wrap { display: flex; justify-content: center; align-items: center; min-height: 70vh; padding: 16px; }
.box { width: 420px; max-width: 100%; }
.tip { text-align: center; margin-top: 12px; color: var(--ll-gray2); }
.twofa-tip { margin-bottom: 16px; }
.code-row { display: flex; gap: 8px; }
.code-row :deep(.ant-input-affix-wrapper), .code-row :deep(.ant-input) { flex: 1; }
.strength { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.strength .bars { display: inline-flex; gap: 4px; }
.strength .bar { width: 34px; height: 5px; border-radius: 3px; background: #e5e7eb; }
.strength .bar.on.s1 { background: #ef4444; }
.strength .bar.on.s2 { background: #f59e0b; }
.strength .bar.on.s3 { background: #10b981; }
.strength em { font-style: normal; font-size: 12px; color: var(--ll-gray2); }
@media (max-width: 480px) {
  .box { width: 100%; }
  .strength .bar { width: 26px; }
}
</style>