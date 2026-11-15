<template>
  <div class="social-login" :class="`is-${variant}`">
    <button
      v-if="appleItem"
      type="button"
      class="siwa"
      :class="{ 'is-off': !appleItem.enabled && !appleItem.sandbox }"
      :aria-label="appleItem.enabled ? '通过 Apple 登录' : '通过 Apple 登录（未配置）'"
      @click="start(appleItem)"
    >
      <!-- Apple：苹果官方经典剪影 -->
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M16.5 12.6c0-2.3 1.9-3.4 2-3.4-1.1-1.6-2.8-1.8-3.4-1.9-1.4-.1-2.8.8-3.5.8-.7 0-1.8-.8-3-.8-1.5 0-3 .9-3.8 2.3-1.6 2.8-.4 7 1.2 9.3.8 1.1 1.7 2.4 2.9 2.3 1.2 0 1.6-.7 3-.7s1.8.7 3 .7c1.2 0 2-1.1 2.8-2.3.9-1.3 1.2-2.6 1.2-2.7 0-.1-2.4-.9-2.4-3.6Z" fill="currentColor" />
        <path d="M14.3 5.5c.6-.8 1.1-1.9 1-3-1 .1-2.1.7-2.8 1.5-.6.7-1.1 1.8-1 2.9 1.1.1 2.2-.6 2.8-1.4Z" fill="currentColor" />
      </svg>
      <span>通过 Apple 登录</span>
      <em v-if="appleItem.sandbox">沙箱</em>
    </button>

    <div class="social-login__others" role="group" aria-label="其他登录方式">
      <button
        v-for="item in otherItems"
        :key="item.provider"
        type="button"
        class="social-login__btn"
        :class="[
          `is-${item.provider}`,
          { 'is-off': !item.enabled && !item.sandbox }
        ]"
        :aria-label="`${item.name}${item.enabled ? '' : '（未配置）'}`"
        :title="item.enabled ? (item.sandbox ? `${item.name} 登录（快速体验）` : `${item.name} 登录`) : `${item.name} 登录未配置`"
        @click="start(item)"
      >
        <span class="social-login__icon" aria-hidden="true">
          <!-- 微信：官方品牌剪影 -->
          <svg v-if="item.provider === 'wechat'" viewBox="0 0 24 24" fill="#07C160">
            <path d="M8.691 2.188C3.891 2.188 0 5.476 0 9.53c0 2.212 1.17 4.203 3.002 5.55a.59.59 0 0 1 .213.665l-.39 1.48c-.019.07-.048.141-.048.213 0 .163.13.295.29.295a.326.326 0 0 0 .167-.054l1.903-1.114a.864.864 0 0 1 .717-.098 10.16 10.16 0 0 0 2.837.403c.276 0 .543-.027.811-.05-.857-2.578.157-4.972 1.932-6.446 1.703-1.415 3.882-1.98 5.853-1.838-.576-3.583-4.196-6.348-8.596-6.348zM5.785 5.991c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 0 1-1.162 1.178A1.17 1.17 0 0 1 4.623 7.17c0-.651.52-1.18 1.162-1.18zm5.813 0c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 0 1-1.162 1.178 1.17 1.17 0 0 1-1.162-1.178c0-.651.52-1.18 1.162-1.18zm5.34 2.867c-1.797-.052-3.746.512-5.28 1.786-1.72 1.428-2.687 3.72-1.78 6.22.942 2.453 3.666 4.229 6.884 4.229.826 0 1.622-.12 2.361-.336a.722.722 0 0 1 .598.082l1.584.926a.272.272 0 0 0 .14.047c.134 0 .24-.111.24-.247 0-.06-.023-.12-.038-.177l-.327-1.233a.582.582 0 0 1-.023-.156.49.49 0 0 1 .201-.398C23.024 18.48 24 16.82 24 14.98c0-3.21-2.931-5.837-6.656-6.088V8.89c-.135-.01-.27-.027-.407-.03zm-2.53 3.274c.535 0 .969.44.969.982a.976.976 0 0 1-.969.983.976.976 0 0 1-.969-.983c0-.542.434-.982.97-.982zm4.844 0c.535 0 .969.44.969.982a.976.976 0 0 1-.969.983.976.976 0 0 1-.969-.983c0-.542.434-.982.969-.982z" />
          </svg>
          <!-- 支付宝：官方应用图标 -->
          <svg v-else-if="item.provider === 'alipay'" viewBox="0 0 24 24" fill="#1677FF">
            <path d="M19.695 15.07c3.426 1.158 4.203 1.22 4.203 1.22V3.846c0-2.124-1.705-3.845-3.81-3.845H3.914C1.808.001.102 1.722.102 3.846v16.31c0 2.123 1.706 3.845 3.813 3.845h16.173c2.105 0 3.81-1.722 3.81-3.845v-.157s-6.19-2.602-9.315-4.119c-2.096 2.602-4.8 4.181-7.607 4.181-4.75 0-6.361-4.19-4.112-6.949.49-.602 1.324-1.175 2.617-1.497 2.025-.502 5.247.313 8.266 1.317a16.796 16.796 0 0 0 1.341-3.302H5.781v-.952h4.799V6.975H4.77v-.953h5.81V3.591s0-.409.411-.409h2.347v2.84h5.744v.951h-5.744v1.704h4.69a19.453 19.453 0 0 1-1.986 5.06c1.424.52 2.702 1.011 3.654 1.333m-13.81-2.032c-.596.06-1.71.325-2.321.869-1.83 1.608-.735 4.55 2.968 4.55 2.151 0 4.301-1.388 5.99-3.61-2.403-1.182-4.438-2.028-6.637-1.809" />
          </svg>
          <!-- Google：四色 G -->
          <svg v-else viewBox="0 0 24 24">
            <path d="M21.6 12.2c0-.7-.1-1.4-.2-2H12v3.8h5.4c-.2 1.2-.9 2.3-2 3v2.5h3.2c1.9-1.7 3-4.3 3-7.3Z" fill="#4285F4" />
            <path d="M12 22c2.7 0 5-.9 6.6-2.4l-3.2-2.5c-.9.6-2 .9-3.4.9-2.6 0-4.8-1.7-5.6-4.1H3.1v2.6C4.7 19.7 8.1 22 12 22Z" fill="#34A853" />
            <path d="M6.4 13.9c-.2-.6-.3-1.2-.3-1.9s.1-1.3.3-1.9V7.5H3.1A9.9 9.9 0 0 0 2 12c0 1.6.4 3.1 1.1 4.5l3.3-2.6Z" fill="#FBBC05" />
            <path d="M12 6c1.5 0 2.8.5 3.8 1.5l2.9-2.9C16.9 2.9 14.7 2 12 2 8.1 2 4.7 4.3 3.1 7.5l3.3 2.6C7.2 7.7 9.4 6 12 6Z" fill="#EA4335" />
          </svg>
        </span>
        <span class="social-login__label">{{ item.name }}</span>
      </button>
    </div>

    <p v-if="loading" class="social-login__hint">正在探测可用渠道…</p>
    <p v-else-if="showHint" class="social-login__hint">
      支持 Google、Apple、微信与支付宝快捷登录；已开启沙箱一键测试模式。
    </p>

    <!-- ===== Google / Apple 官方标准授权弹窗 (Sandbox / Fast Login) ===== -->
    <a-modal
      v-model:open="modalOpen"
      :title="null"
      :footer="null"
      :width="440"
      centered
      class="oauth-dialog"
      :mask-closable="true"
    >
      <div v-if="activeProvider === 'google'" class="google-oauth-card">
        <div class="google-header">
          <svg viewBox="0 0 24 24" width="32" height="32">
            <path d="M21.6 12.2c0-.7-.1-1.4-.2-2H12v3.8h5.4c-.2 1.2-.9 2.3-2 3v2.5h3.2c1.9-1.7 3-4.3 3-7.3Z" fill="#4285F4" />
            <path d="M12 22c2.7 0 5-.9 6.6-2.4l-3.2-2.5c-.9.6-2 .9-3.4.9-2.6 0-4.8-1.7-5.6-4.1H3.1v2.6C4.7 19.7 8.1 22 12 22Z" fill="#34A853" />
            <path d="M6.4 13.9c-.2-.6-.3-1.2-.3-1.9s.1-1.3.3-1.9V7.5H3.1A9.9 9.9 0 0 0 2 12c0 1.6.4 3.1 1.1 4.5l3.3-2.6Z" fill="#FBBC05" />
            <path d="M12 6c1.5 0 2.8.5 3.8 1.5l2.9-2.9C16.9 2.9 14.7 2 12 2 8.1 2 4.7 4.3 3.1 7.5l3.3 2.6C7.2 7.7 9.4 6 12 6Z" fill="#EA4335" />
          </svg>
          <div class="google-header-text">
            <h2>使用 Google 帐号登录</h2>
            <p>前往「LANLinkshopping 电商平台」</p>
          </div>
        </div>

        <div class="oauth-account-picker">
          <div class="picker-label">选择要登录的 Google 帐号：</div>
          <div
            v-for="acc in googlePresets"
            :key="acc.email"
            class="oauth-account-row"
            :class="{ active: selectedEmail === acc.email }"
            @click="selectAccount(acc)"
          >
            <div class="acc-avatar google-avatar">{{ acc.name.charAt(0) }}</div>
            <div class="acc-info">
              <div class="acc-name">{{ acc.name }}</div>
              <div class="acc-email">{{ acc.email }}</div>
            </div>
            <span class="acc-check" v-if="selectedEmail === acc.email"><Check :size="14" /></span>
          </div>

          <div class="custom-acc-input">
            <span class="custom-label">或输入自定义 Gmail：</span>
            <a-input v-model:value="customEmail" placeholder="例如 yourname@gmail.com" />
          </div>
        </div>

        <div class="oauth-scope-notice">
          <p>LANLinkshopping 将获得查看您的个人资料信息（姓名、电子邮件地址和头像）的权限。</p>
        </div>

        <div class="oauth-actions">
          <a-button @click="modalOpen = false">取消</a-button>
          <a-button type="primary" :loading="loggingIn" class="google-btn" @click="confirmSocialLogin">
            使用选定 Google 帐号登录
          </a-button>
        </div>
      </div>

      <div v-else-if="activeProvider === 'apple'" class="apple-oauth-card">
        <div class="apple-header">
          <div class="apple-logo-badge">
            <svg viewBox="0 0 24 24" width="36" height="36" fill="#fff">
              <path d="M16.5 12.6c0-2.3 1.9-3.4 2-3.4-1.1-1.6-2.8-1.8-3.4-1.9-1.4-.1-2.8.8-3.5.8-.7 0-1.8-.8-3-.8-1.5 0-3 .9-3.8 2.3-1.6 2.8-.4 7 1.2 9.3.8 1.1 1.7 2.4 2.9 2.3 1.2 0 1.6-.7 3-.7s1.8.7 3 .7c1.2 0 2-1.1 2.8-2.3.9-1.3 1.2-2.6 1.2-2.7 0-.1-2.4-.9-2.4-3.6Z" />
              <path d="M14.3 5.5c.6-.8 1.1-1.9 1-3-1 .1-2.1.7-2.8 1.5-.6.7-1.1 1.8-1 2.9 1.1.1 2.2-.6 2.8-1.4Z" />
            </svg>
          </div>
          <h2>通过 Apple 登录</h2>
          <p class="apple-sub">LANLinkshopping</p>
        </div>

        <div class="oauth-account-picker">
          <div class="picker-label">选择 Apple ID 身份凭据：</div>
          <div
            v-for="acc in applePresets"
            :key="acc.email"
            class="oauth-account-row apple-row"
            :class="{ active: selectedEmail === acc.email }"
            @click="selectAccount(acc)"
          >
            <div class="acc-avatar apple-avatar"></div>
            <div class="acc-info">
              <div class="acc-name">{{ acc.name }}</div>
              <div class="acc-email">{{ acc.email }}</div>
            </div>
            <span class="acc-check" v-if="selectedEmail === acc.email"><Check :size="14" /></span>
          </div>

          <div class="apple-privacy-toggle">
            <a-radio-group v-model:value="applePrivacy" button-style="solid" size="small">
              <a-radio-button value="share">共享我的电子邮件</a-radio-button>
              <a-radio-button value="hide">隐藏我的电子邮件 (私密中继)</a-radio-button>
            </a-radio-group>
          </div>
        </div>

        <div class="apple-auth-indicator">
          <div class="face-id-icon">
            <ShieldCheck :size="18" class="text-sky-500 inline-block align-middle" />
          </div>
          <span>使用 Touch ID / 密码认证即可完成登录</span>
        </div>

        <div class="oauth-actions">
          <a-button @click="modalOpen = false">取消</a-button>
          <a-button type="primary" :loading="loggingIn" class="apple-btn" @click="confirmSocialLogin">
            继续 (Sign in with Apple)
          </a-button>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { ShieldCheck, Check } from 'lucide-vue-next'
import { socialApi } from '../../api'
import { useUserStore } from '../../store/user'
import { useCartStore } from '../../store/cart'

const props = defineProps({
  /** 视觉变体：light（浅底表单内）| auto */
  variant: { type: String, default: 'light' },
  /** 是否显示「未配置」角标 */
  showState: { type: Boolean, default: true },
  /** 是否显示底部探测说明 */
  showHint: { type: Boolean, default: false }
})

const emit = defineEmits(['login-success'])

const router = useRouter()
const route = useRoute()
const user = useUserStore()
const cart = useCartStore()

const list = ref([])
const loading = ref(false)
const appleItem = computed(() => list.value.find((item) => item.provider === 'apple') || null)
const otherItems = computed(() => list.value.filter((item) => item.provider !== 'apple'))

// 模态框与沙箱登录状态
const modalOpen = ref(false)
const loggingIn = ref(false)
const activeProvider = ref('google')
const selectedEmail = ref('')
const selectedName = ref('')
const customEmail = ref('')
const applePrivacy = ref('share')

const googlePresets = [
  { name: 'Alex Developer (采购经理)', email: 'alex.developer@gmail.com', avatar: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=100' },
  { name: 'Google Workspace 测试员', email: 'google.tester@lanlink.com', avatar: '' }
]

const applePresets = [
  { name: 'Sarah Miller (企业采购)', email: 'sarah.miller@icloud.com' },
  { name: 'LANLink Apple ID', email: 'apple.buyer@lanlink.com' }
]

function selectAccount(acc) {
  selectedEmail.value = acc.email
  selectedName.value = acc.name
}

async function load() {
  loading.value = true
  try {
    const data = await socialApi.providers()
    list.value = Array.isArray(data) ? data : []
  } catch (e) {
    // 探测失败不能假装沙箱可用，否则会弹出一套走不通的 Apple / Google 登录窗。
    list.value = [
      { provider: 'google', name: 'Google', enabled: false, sandbox: false },
      { provider: 'apple', name: 'Apple', enabled: false, sandbox: false },
      { provider: 'wechat', name: '微信', enabled: false, sandbox: false },
      { provider: 'alipay', name: '支付宝', enabled: false, sandbox: false }
    ]
  } finally {
    loading.value = false
  }
}

/** 一次性 state（防 CSRF） */
function newState() {
  const c = typeof crypto !== 'undefined' ? crypto : undefined
  if (c && typeof c.randomUUID === 'function') return c.randomUUID().replace(/-/g, '')
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 12)}`
}

async function start(item) {
  if (!item.enabled && !item.sandbox) {
    message.info(`${item.name} 登录未配置，请在 application-social.yml 中填写凭证后启用`)
    return
  }

  // 若为沙箱模式或特别测试 Google/Apple
  // 沙箱才打开本地模拟弹窗。已配置的 Apple / Google 走平台授权页，不再被弹窗截走。
  if (item.sandbox && (item.provider === 'google' || item.provider === 'apple')) {
    activeProvider.value = item.provider
    if (item.provider === 'google') {
      selectAccount(googlePresets[0])
    } else {
      selectAccount(applePresets[0])
    }
    modalOpen.value = true
    return
  }

  try {
    const res = await socialApi.authorize(item.provider, newState())
    if (res?.authorizeUrl) {
      if (res.authorizeUrl.includes('social_sandbox=')) {
        activeProvider.value = item.provider
        modalOpen.value = true
        return
      }
      try {
        sessionStorage.setItem('ll_social_provider', item.provider)
        sessionStorage.setItem('ll_social_intent', 'login')
        const redirect = route.query.redirect
        if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//') && !redirect.includes('://')) {
          sessionStorage.setItem('ll_social_redirect', redirect)
        } else {
          sessionStorage.removeItem('ll_social_redirect')
        }
      } catch (e) { /* 隐私模式忽略 */ }
      window.location.href = res.authorizeUrl
    } else {
      message.warning('未取得授权地址，请稍后重试')
    }
  } catch (e) { /* 拦截器已提示 */ }
}

async function confirmSocialLogin() {
  const finalEmail = customEmail.value.trim() || selectedEmail.value
  if (!finalEmail) {
    message.warning('请选择或输入邮箱')
    return
  }
  loggingIn.value = true
  try {
    const res = await socialApi.sandboxLogin({
      provider: activeProvider.value,
      email: finalEmail,
      name: selectedName.value || (activeProvider.value === 'google' ? 'Google User' : 'Apple User'),
      sub: `mock_${activeProvider.value}_${finalEmail.replace(/[^a-zA-Z0-9]/g, '_')}`
    })
    message.success(`${activeProvider.value === 'google' ? 'Google' : 'Apple'} 授权成功，欢迎使用`)
    modalOpen.value = false
    await user.fetchMe()
    await cart.load()
    emit('login-success')
    router.push(route.query.redirect || '/')
  } catch (e) {
    message.error(e?.message || '授权登录失败')
  } finally {
    loggingIn.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.social-login {
  width: 100%;
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "Segoe UI", sans-serif;
}
.siwa {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  min-height: 48px;
  padding: 0 18px;
  border: 0;
  border-radius: 12px;
  background: #000;
  color: #fff;
  font: 600 17px/1 -apple-system, BlinkMacSystemFont, "SF Pro Text", "Segoe UI", sans-serif;
  letter-spacing: -0.02em;
  cursor: pointer;
  transition: background-color .2s ease, transform .18s cubic-bezier(.22, 1, .36, 1);
}
.siwa svg { width: 18px; height: 18px; margin-top: -1px; }
.siwa:hover { background: #1d1d1f; }
.siwa:active { transform: scale(0.985); }
.siwa:focus-visible { outline: 2px solid #1d1d1f; outline-offset: 3px; }
.siwa.is-off { background: #d2d2d7; color: #fff; cursor: not-allowed; }
.siwa em {
  position: absolute;
  right: 14px;
  font-style: normal;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0;
  color: rgba(255, 255, 255, .72);
}
/* 渠道特定微细节 */
.social-login__others {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-top: 10px;
}
.social-login__btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 44px;
  padding: 0 8px;
  border: 1px solid rgba(0, 0, 0, .08);
  border-radius: 12px;
  background: rgba(255, 255, 255, .72);
  color: #1d1d1f;
  font: 560 13px/1 -apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif;
  letter-spacing: -0.01em;
  cursor: pointer;
  transition: background-color .2s ease, transform .18s cubic-bezier(.22, 1, .36, 1);
}
.social-login__btn:hover { background: #fff; }
.social-login__btn:active { transform: scale(0.98); }
.social-login__btn:focus-visible { outline: 2px solid #1d1d1f; outline-offset: 2px; }
.social-login__btn.is-off { opacity: .45; }
.social-login__icon { display: grid; place-items: center; width: 18px; height: 18px; }
.social-login__icon svg { width: 16px; height: 16px; }
.social-login__label { font-size: 13px; font-weight: 560; }
.social-login__hint {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.45;
  letter-spacing: -0.01em;
  color: #6e6e73;
  text-align: center;
  text-wrap: pretty;
}

/* ===== Google & Apple 拟真对话框样式 (21st.dev 风格) ===== */
.google-oauth-card, .apple-oauth-card {
  padding: 12px 6px 6px;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}
.google-header {
  display: flex; align-items: center; gap: 14px;
  padding-bottom: 16px; border-bottom: 1px solid #f1f5f9;
}
.google-header-text h2 { margin: 0; font-size: 18px; font-weight: 600; color: #1e293b; }
.google-header-text p { margin: 2px 0 0; font-size: 13px; color: #64748b; }

.apple-header {
  display: flex; flex-direction: column; align-items: center;
  padding: 8px 0 18px;
}
.apple-logo-badge {
  width: 56px; height: 56px; border-radius: 16px; background: #000;
  display: grid; place-items: center; margin-bottom: 14px;
}
.apple-header h2 {
  margin: 0;
  font-size: 22px;
  font-weight: 650;
  letter-spacing: -0.03em;
  color: #1d1d1f;
  text-wrap: balance;
}
.apple-sub { margin: 4px 0 0; font-size: 13px; color: #6e6e73; font-weight: 500; }

.oauth-account-picker { margin: 16px 0; display: flex; flex-direction: column; gap: 8px; }
.picker-label { font-size: 12px; font-weight: 600; color: #475569; }

.oauth-account-row {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 14px; border: 1px solid rgba(0, 0, 0, .08); border-radius: 14px;
  cursor: pointer; transition: background-color .18s ease, border-color .18s ease; background: #f5f5f7;
}
.oauth-account-row:hover { background: #fff; }
.oauth-account-row.active { border-color: #1d1d1f; background: #fff; }

.acc-avatar {
  width: 36px; height: 36px; border-radius: 50%; display: grid; place-items: center;
  font-weight: 700; font-size: 15px;
}
.google-avatar { background: #fee2e2; color: #dc2626; }
.apple-avatar { background: #000; color: #fff; font-size: 17px; }

.acc-info { flex: 1; min-width: 0; }
.acc-name { font-size: 13px; font-weight: 600; color: #1e293b; }
.acc-email { font-size: 12px; color: #64748b; }
.acc-check { color: #1d1d1f; font-weight: 700; font-size: 16px; }

.custom-acc-input { margin-top: 6px; }
.custom-label { display: block; font-size: 11px; color: #64748b; margin-bottom: 4px; }

.apple-privacy-toggle { margin-top: 8px; text-align: center; }
.apple-auth-indicator {
  display: flex; align-items: center; justify-content: center; gap: 8px;
  padding: 10px; background: #f8fafc; border-radius: 10px; margin: 14px 0;
  font-size: 12px; color: #475569;
}

.oauth-scope-notice { font-size: 11px; color: #94a3b8; line-height: 1.5; margin: 12px 0 16px; }

.oauth-actions { display: flex; gap: 10px; }
.oauth-actions :deep(.ant-btn) { flex: 1; height: 44px; border-radius: 12px; }
.google-btn { background: #1a73e8; border-color: #1a73e8; }
.apple-btn { background: #000; border-color: #000; color: #fff; font-weight: 600; }
.apple-btn:hover { background: #1d1d1f !important; border-color: #1d1d1f !important; }
</style>
