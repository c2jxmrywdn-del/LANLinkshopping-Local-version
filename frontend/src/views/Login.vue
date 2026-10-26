<template>
  <main class="auth">
    <!-- 动态背景：折叠玻璃 WebGL 着色器（GradFlow "Glass Fold"，speed=1 / noise=0.1 / scale=1） -->
    <div class="auth__bg" aria-hidden="true">
      <GlassFold class="auth__shader" :speed="1" :noise="0.1" :scale="1" />
      <span class="auth__bg-scrim"></span>
    </div>

    <!-- 左：品牌叙事栏（21st.dev 分栏范式；窄屏隐藏，保证移动端表单优先） -->
    <section class="auth__brand">
      <div class="auth__brand-inner">
        <span class="auth__logo"><b>LAN</b>Link<b>shopping</b></span>
        <h1 class="auth__claim">聚合 · 连接 · 一体多元解决方案</h1>
        <p class="auth__desc">面向 B2B 采购与供给的一体化平台，覆盖商品、订单、钱包、账期、会员与物流全链路。</p>
        <ul class="auth__points">
          <li><span>01</span>全行业采购供给</li>
          <li><span>02</span>企业钱包与账期</li>
          <li><span>03</span>会员权益与活动</li>
        </ul>
        <p class="auth__note">演示平台 · 数据仅用于功能验证</p>
      </div>
    </section>

    <!-- 右：登录 / 注册面板 -->
    <section class="auth__panel">
      <div class="auth__card">
        <!-- 社交优先：品牌入口置顶 + 分隔线 + 账号表单回退 -->
        <SocialLoginButtons :show-hint="true" @login-success="afterLoginSuccess" />
        <div class="auth__divider"><i aria-hidden="true"></i><span>或使用账号登录</span><i aria-hidden="true"></i></div>

        <a-tabs v-model:activeKey="tab" centered>
          <!-- ===== 登录 ===== -->
          <a-tab-pane key="login" tab="登录">
            <!-- 第一步：手机号 + 密码 -->
            <a-form v-if="!twofa" :model="form" layout="vertical" @finish="submit">
              <a-form-item label="手机号" :rules="[{ required: true, message: '请输入手机号' }]">
                <a-input id="login-phone" name="phone" autocomplete="username"
                         v-model:value="form.phone" placeholder="请输入已注册的手机号" size="large" />
              </a-form-item>
              <a-form-item label="密码" :rules="[{ required: true, message: '请输入密码' }]">
                <a-input-password id="login-password" name="password" autocomplete="current-password"
                                  v-model:value="form.password" placeholder="请输入注册时设置的密码" size="large" />
              </a-form-item>
              <!-- 忘记密码入口：密码框下方、登录按钮上方 -->
              <div class="forgot-row">
                <a class="forgot-link" role="button" tabindex="0"
                   @click="openForgot" @keyup.enter="openForgot">忘记密码？</a>
              </div>
              <a-button type="primary" html-type="submit" block size="large" :loading="loading">登录</a-button>
              <div class="tip">首次使用？请 <a @click="tab = 'register'">注册</a> 创建账号。</div>

              <!-- 演示账号快捷填入 -->
              <div class="demo-fill notranslate" translate="no">
                <div class="demo-fill__label">演示账号（密码均为 123456）：</div>
                <div class="demo-fill__btns">
                  <a-button size="small" @click="fillDemo('buyer')">采购方 (13900000001)</a-button>
                  <a-button size="small" @click="fillDemo('merchant')">商户 (13700000002)</a-button>
                  <a-button size="small" @click="fillDemo('admin')">平台运营 (13800000000)</a-button>
                </div>
              </div>
            </a-form>

            <!-- 第二步：两步验证动态码 -->
            <a-form v-else :model="twofa" layout="vertical" @finish="submit2fa">
              <a-alert type="info" show-icon class="twofa-tip">
                <template #message>该账号已开启两步验证，请输入验证器 App 上的动态验证码（绑定手机 {{ twofa.hint }}）</template>
              </a-alert>
              <a-form-item label="动态验证码" :rules="[{ required: true, pattern: /^\d{6}$/, message: '请填写 6 位动态验证码' }]">
                <a-input id="login-2fa-code" name="twofaCode" autocomplete="one-time-code"
                         v-model:value="twofa.code" placeholder="6 位数字" size="large" :maxlength="6" autofocus />
              </a-form-item>
              <a-button type="primary" html-type="submit" block size="large" :loading="loading">验证并登录</a-button>
              <div class="tip"><a @click="reset2fa">返回重新登录</a></div>
            </a-form>
          </a-tab-pane>

          <!-- ===== 扫码登录 ===== -->
          <a-tab-pane key="scan" tab="扫码登录">
            <QrCodeScanLogin
              app-name="LANLinkshopping"
              device-name="本机浏览器"
              welcome-message="欢迎回来"
              @confirmed="onQrConfirmed"
              @use-password="tab = 'login'"
            />
            <div class="tip">本地未接入扫码换票后端，演示确认后以平台演示账号登录。</div>
          </a-tab-pane>

          <!-- ===== 注册 ===== -->
          <a-tab-pane key="register" tab="注册">
            <a-form :model="reg" layout="vertical" @finish="doRegister">
              <a-form-item label="注册身份">
                <a-radio-group v-model:value="reg.roleCode" name="registerRole" button-style="solid">
                  <a-radio-button value="buyer">采购方</a-radio-button>
                  <a-radio-button value="merchant">商户/供应商</a-radio-button>
                </a-radio-group>
              </a-form-item>
              <a-form-item label="手机号" :rules="[{ required: true, message: '请输入手机号' }, { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确' }]">
                <a-input id="register-phone" name="phone" autocomplete="tel"
                         v-model:value="reg.phone" size="large" placeholder="11 位手机号" />
              </a-form-item>
              <a-form-item label="邮箱（选填）" :rules="reg.email ? [{ type: 'email', message: '邮箱格式不正确' }] : []">
                <a-input id="register-email" name="email" autocomplete="email"
                         v-model:value="reg.email" size="large" placeholder="name@example.com" />
              </a-form-item>
              <a-form-item label="昵称" name="nickname" :rules="[{ required: true, whitespace: true, message: '请输入昵称' }, { max: 32, message: '昵称不能超过 32 字' }]">
                <a-input id="register-nickname" name="nickname" autocomplete="nickname"
                         v-model:value="reg.nickname" size="large" :maxlength="32" />
              </a-form-item>
              <a-form-item label="验证码" :rules="[{ required: true, message: '请输入验证码' }]">
                <div class="code-row">
                  <a-input id="register-code" name="verifyCode" autocomplete="one-time-code"
                           v-model:value="reg.code" size="large" placeholder="6 位验证码" :maxlength="6" />
                  <a-button size="large" :disabled="cd > 0" @click="sendCode">{{ cd > 0 ? cd + 's' : '获取验证码' }}</a-button>
                </div>
              </a-form-item>
              <a-form-item label="密码" :rules="[{ required: true, message: '请输入密码' }]">
                <a-input-password id="register-password" name="password" autocomplete="new-password"
                                  v-model:value="reg.password" size="large" placeholder="至少 8 位，含字母与数字" />
                <div class="strength">
                  <span class="bars"><i :class="['bar', strength >= 1 ? 'on s1' : '']"></i><i :class="['bar', strength >= 2 ? 'on s2' : '']"></i><i :class="['bar', strength >= 3 ? 'on s3' : '']"></i></span>
                  <em>{{ strengthText }}</em>
                </div>
              </a-form-item>
              <a-form-item label="确认密码" :rules="[{ required: true, message: '请再次输入密码' }]">
                <a-input-password id="register-confirm" name="confirmPassword" autocomplete="new-password"
                                  v-model:value="reg.confirm" size="large" />
              </a-form-item>
              <a-button type="primary" html-type="submit" block size="large" :loading="regLoading">注册</a-button>
              <div class="tip">已有账号？请 <a @click="tab = 'login'">登录</a></div>
            </a-form>
          </a-tab-pane>
        </a-tabs>
      </div>
    </section>

    <!-- ===== 忘记密码模态框（居中，响应式） ===== -->
    <a-modal v-model:open="forgotOpen" title="重置密码" centered :mask-closable="false"
             :width="420">
      <a-form :model="forgot" layout="vertical" class="forgot-form">
        <a-form-item label="用户账号 / 邮箱"
                     :validate-status="forgotErr.account ? 'error' : ''" :help="forgotErr.account">
          <a-input id="forgot-account" name="account" autocomplete="username"
                   v-model:value="forgot.account" size="large" allow-clear
                   placeholder="请输入注册手机号或邮箱" @blur="checkForgotAccount" />
        </a-form-item>
        <a-form-item label="验证码"
                     :validate-status="forgotErr.code ? 'error' : ''" :help="forgotErr.code">
          <div class="code-row">
            <a-input id="forgot-code" name="verifyCode" autocomplete="one-time-code"
                     v-model:value="forgot.code" size="large" :maxlength="6" allow-clear
                     placeholder="6 位验证码" @blur="checkForgotCode" />
            <a-button size="large" :disabled="forgotCd > 0"
                      @click="sendForgotCode">
              {{ forgotCd > 0 ? forgotCd + 's 后重发' : '获取验证码' }}
            </a-button>
          </div>
        </a-form-item>
      </a-form>
      <template #footer>
        <a-button @click="closeForgot">关闭</a-button>
        <a-button type="primary" :loading="forgotLoading" @click="submitForgot">重置密码</a-button>
      </template>
    </a-modal>
  </main>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { useUserStore } from '../store/user'
import { useCartStore } from '../store/cart'
import QrCodeScanLogin from '../components/ui/QrCodeScanLogin.vue'
import SocialLoginButtons from '../components/ui/SocialLoginButtons.vue'
import GlassFold from '../components/ui/GlassFold.vue'
import { socialApi } from '../api'

const router = useRouter(); const route = useRoute()
const user = useUserStore(); const cart = useCartStore()
const tab = ref('login')
const loading = ref(false)
const regLoading = ref(false)
const form = reactive({ phone: '', password: '' })
const reg = reactive({ roleCode: 'buyer', phone: '', email: '', nickname: '', code: '', password: '', confirm: '', _srvCode: '' })

// 演示账号快捷填入配置
const DEMO_CREDENTIALS = {
  buyer: { phone: '13900000001', password: '123456' },
  merchant: { phone: '13700000002', password: '123456' },
  admin: { phone: '13800000000', password: '123456' }
}
function fillDemo(role) {
  const cred = DEMO_CREDENTIALS[role]
  if (cred) {
    form.phone = cred.phone
    form.password = cred.password
    message.info(`已填入演示账号：${cred.phone}`)
  }
}

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

// ===== 扫码登录（演示链路：本地未接扫码换票后端，确认后以演示账号完成真实登录）=====
const DEMO_PHONE = '13900000001'
const DEMO_PASSWORD = '123456'
async function onQrConfirmed() {
  loading.value = true
  try {
    const res = await user.login({ phone: DEMO_PHONE, password: DEMO_PASSWORD })
    if (res && res.require2fa) {
      twofa.value = { loginTicket: res.loginTicket, hint: res.hint || DEMO_PHONE, code: '' }
      return
    }
    await afterLoginSuccess()
  } catch (e) { /* 拦截器已提示 */ }
  finally { loading.value = false }
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

// ===== 忘记密码（重置密码）：账号/邮箱 + 验证码 + 提交（演示链路与注册验证码一致）=====
const forgotOpen = ref(false)
const forgotLoading = ref(false)
const forgot = reactive({ account: '', code: '', _srvCode: '' })
const forgotErr = reactive({ account: '', code: '' })
const forgotCd = ref(0)
let forgotTimer = null

const isPhoneAccount = (v) => /^1[3-9]\d{9}$/.test(v)
const isEmailAccount = (v) => /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(v)

function openForgot() {
  forgotOpen.value = true
}
function closeForgot() {
  forgotOpen.value = false
  // 关闭后清空表单与校验状态，避免影响登录表单
  forgot.account = ''; forgot.code = ''; forgot._srvCode = ''
  forgotErr.account = ''; forgotErr.code = ''
  if (forgotTimer) { clearInterval(forgotTimer); forgotTimer = null }
  forgotCd.value = 0
}

function checkForgotAccount() {
  const v = (forgot.account || '').trim()
  if (!v) { forgotErr.account = '请输入用户账号或邮箱'; return false }
  if (!isPhoneAccount(v) && !isEmailAccount(v)) {
    forgotErr.account = '请输入正确的手机号或邮箱格式'; return false
  }
  forgotErr.account = ''
  return true
}

function checkForgotCode() {
  const v = (forgot.code || '').trim()
  if (!v) { forgotErr.code = '请输入验证码'; return false }
  if (!/^\d{6}$/.test(v)) { forgotErr.code = '请输入 6 位数字验证码'; return false }
  forgotErr.code = ''
  return true
}

function sendForgotCode() {
  if (!checkForgotAccount()) return
  // 演示验证码（与注册链路一致，未接真实短信/邮件）；真实链路接 VerifyCodeService 后替换
  const code = String(Math.floor(100000 + Math.random() * 900000))
  forgot._srvCode = code
  message.info(`演示验证码：${code}（未接真实短信）`)
  forgotCd.value = 60
  forgotTimer = setInterval(() => {
    forgotCd.value--
    if (forgotCd.value <= 0) { clearInterval(forgotTimer); forgotTimer = null }
  }, 1000)
}

async function submitForgot() {
  const okAccount = checkForgotAccount()
  const okCode = checkForgotCode()
  if (!okAccount || !okCode) return
  if (!forgot._srvCode) { message.error('请先获取验证码'); return }
  if (forgot.code.trim() !== forgot._srvCode) { message.error('验证码错误'); return }
  forgotLoading.value = true
  try {
    // 演示：校验通过即模拟发送重置链接（真实链路由后端发送邮件后返回）
    message.success('密码重置链接已发送至您的邮箱，请查收')
    closeForgot()
  } finally { forgotLoading.value = false }
}

// ===== 社交登录回跳：平台把 code/state 回跳到本页，这里交给后端换取身份并建立会话 =====
const SOCIAL_PROVIDER_KEY = 'll_social_provider'

/** 清掉回跳带的临时查询参数，避免刷新时重复换取（code 一次性） */
function cleanSocialQuery() {
  const rest = { ...route.query }
  delete rest.code; delete rest.state; delete rest.error; delete rest.error_description
  router.replace({ path: '/login', query: rest })
}

async function handleSocialCallback() {
  if (route.query.error) {
    message.warning('社交登录已取消或授权失败')
    cleanSocialQuery()
    return
  }
  const code = route.query.code
  const state = route.query.state
  if (!code || !state) return

  let provider = null
  try { provider = sessionStorage.getItem(SOCIAL_PROVIDER_KEY) } catch (e) { /* 隐私模式忽略 */ }
  if (!provider) {
    message.warning('无法确定授权渠道，请重新发起社交登录')
    cleanSocialQuery()
    return
  }

  loading.value = true
  try {
    await socialApi.callback(provider, String(code), String(state))
    try { sessionStorage.removeItem(SOCIAL_PROVIDER_KEY) } catch (e) { /* 忽略 */ }
    // 后端已建立会话：同步前端用户态后走统一登录后处理
    await user.fetchMe()
    await afterLoginSuccess()
  } catch (e) {
    // 未绑定时后端返回可执行指引，由请求拦截器提示
  } finally {
    loading.value = false
    cleanSocialQuery()
  }
}

onMounted(handleSocialCallback)
</script>

<style scoped>
/* ============ 分栏布局：动态着色器背景 + 左品牌叙事 + 右账号面板 ============ */
.auth {
  position: relative; display: grid; grid-template-columns: 1.05fr .95fr;
  min-height: 100vh; overflow: hidden;
  /* 无 WebGL 时的兜底底色（与着色器背景色一致，避免白屏） */
  background: var(--ll-brand-base-deep, #0B1220);
}
.auth__bg { position: absolute; inset: 0; z-index: 0; }
.auth__shader { position: absolute; inset: 0; }
/* 左侧压暗保证白字对比度，向右渐隐让着色器充分显现 */
.auth__bg-scrim {
  position: absolute; inset: 0;
  background: linear-gradient(105deg,
    rgba(6, 8, 30, .72) 0%,
    rgba(6, 8, 30, .62) 34%,
    rgba(6, 8, 30, .30) 58%,
    rgba(6, 8, 30, .12) 78%,
    rgba(6, 8, 30, .18) 100%);
}
.auth__brand {
  position: relative; z-index: 1;
  display: flex; align-items: center; overflow: hidden; color: #fff;
}
.auth__brand-inner { max-width: 560px; padding: 56px 52px; }
.auth__logo { display: inline-flex; align-items: center; font-weight: 800; font-size: 20px; letter-spacing: .04em; color: #fff; }
.auth__logo b { color: var(--ll-brand-gold-soft, #F6D38A); }
.auth__claim { margin: 22px 0 12px; font-size: 30px; line-height: 1.28; color: #fff; }
.auth__desc { max-width: 46ch; margin: 0; font-size: 14px; line-height: 1.8; color: rgba(255, 255, 255, .72); }
.auth__points { display: grid; gap: 12px; margin: 26px 0 0; padding: 0; list-style: none; }
.auth__points li { display: flex; align-items: center; gap: 10px; font-size: 13px; color: rgba(255, 255, 255, .78); }
.auth__points span {
  padding: 4px 6px; border: 1px solid rgba(246, 211, 138, .45); border-radius: 6px;
  font: 800 11px/1 system-ui, sans-serif; letter-spacing: .1em; color: var(--ll-brand-gold-soft, #F6D38A);
}
.auth__note { margin: 30px 0 0; font-size: 11px; letter-spacing: .06em; color: rgba(255, 255, 255, .7); }

.auth__panel {
  position: relative; z-index: 1;
  display: flex; align-items: center; justify-content: center; padding: 40px 24px;
  /* 浅色面板留住表单可读性；留 10% 透度让着色器隐约透出。
     刻意不用 backdrop-filter：大面积逐帧重模糊会明显拖累这个常驻动画背景。 */
  background: rgba(247, 249, 252, .90);
  border-left: 1px solid rgba(255, 255, 255, .20);
}
.auth__card {
  width: 420px; max-width: 100%; padding: 24px 22px 18px;
  border: 1px solid var(--ll-border, #e5e7eb); border-radius: var(--ll-radius-xl, 18px);
  background: var(--ll-card, #fff); box-shadow: var(--ll-shadow-lg);
  animation: ll-fade-slide .35s var(--ll-ease-out, cubic-bezier(.22, 1, .36, 1));
}
.auth__divider { display: flex; align-items: center; gap: 10px; margin: 18px 0 4px; font-size: 11px; color: var(--ll-muted, #64748b); }
.auth__divider i { flex: 1; height: 1px; background: var(--ll-border, #e5e7eb); }

.tip { text-align: center; margin-top: 12px; color: var(--ll-gray2); }

.demo-fill {
  margin-top: 16px;
  padding: 12px 10px;
  background: #f8fafc;
  border: 1px dashed var(--ll-border, #cbd5e1);
  border-radius: 8px;
  text-align: center;
}
.demo-fill__label {
  font-size: 12px;
  color: var(--ll-muted, #64748b);
  margin-bottom: 8px;
}
.demo-fill__btns {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: center;
}
.demo-fill__btns :deep(.ant-btn) {
  font-size: 12px;
  border-radius: 4px;
}

/* 忘记密码入口：密码框下方、登录按钮上方，右对齐贴近常见习惯 */
.forgot-row { display: flex; justify-content: flex-end; margin: -4px 0 12px; }
.forgot-link {
  font-size: 13px; color: var(--ll-primary, #1e6eb8); cursor: pointer;
  transition: color .2s ease, opacity .2s ease;
}
.forgot-link:hover { color: var(--ll-primary-hover, #155a9c); text-decoration: underline; }
.forgot-link:focus-visible { outline: 2px solid var(--ll-primary, #1e6eb8); outline-offset: 2px; border-radius: 2px; }

/* 忘记密码模态框 */
.forgot-form { margin-top: 8px; }
.twofa-tip { margin-bottom: 16px; }
.code-row { display: flex; gap: 8px; }
.code-row :deep(.ant-input-affix-wrapper), .code-row :deep(.ant-input) { flex: 1; }
.strength { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.strength .bars { display: inline-flex; gap: 4px; }
.strength .bar { width: 34px; height: 5px; border-radius: 3px; background: var(--ll-border, #e5e7eb); transition: background-color .25s ease; }
.strength .bar.on.s1 { background: #ef4444; }
.strength .bar.on.s2 { background: #f59e0b; }
.strength .bar.on.s3 { background: #10b981; }
.strength em { font-style: normal; font-size: 12px; color: var(--ll-gray2); }

/* 窄屏：隐藏品牌栏，表单优先；账号面板占满 */
@media (max-width: 900px) {
  .auth { grid-template-columns: 1fr; }
  .auth__brand { display: none; }
  .auth__panel { padding: 24px 16px; min-height: 100vh; }
}
@media (max-width: 480px) {
  .auth__card { width: 100%; }
  .strength .bar { width: 26px; }
  /* 移动端：忘记密码链接居中显示更易点击 */
  .forgot-row { justify-content: center; }
  /* 移动端模态框贴边留白 */
  :deep(.ant-modal) { margin: 12px; max-width: calc(100vw - 24px); }
}
@media (prefers-reduced-motion: reduce) {
  .auth__card { animation: none; }
}
</style>
