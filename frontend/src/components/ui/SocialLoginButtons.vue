<template>
  <div class="social-login" :class="`is-${variant}`">
    <div class="social-login__grid">
      <button
        v-for="item in list"
        :key="item.provider"
        type="button"
        class="social-login__btn"
        :class="{ 'is-off': !item.enabled }"
        :aria-label="`${item.name}${item.enabled ? '' : '（未配置）'}`"
        :title="item.enabled ? `${item.name} 登录` : `${item.name} 登录未配置`"
        @click="start(item)"
      >
        <span class="social-login__icon" aria-hidden="true">
          <!-- 微信：官方品牌剪影（双气泡 + 眼睛挖空；镂空处透出按钮底色即为白色） -->
          <svg v-if="item.provider === 'wechat'" viewBox="0 0 24 24" fill="#07C160">
            <path d="M8.691 2.188C3.891 2.188 0 5.476 0 9.53c0 2.212 1.17 4.203 3.002 5.55a.59.59 0 0 1 .213.665l-.39 1.48c-.019.07-.048.141-.048.213 0 .163.13.295.29.295a.326.326 0 0 0 .167-.054l1.903-1.114a.864.864 0 0 1 .717-.098 10.16 10.16 0 0 0 2.837.403c.276 0 .543-.027.811-.05-.857-2.578.157-4.972 1.932-6.446 1.703-1.415 3.882-1.98 5.853-1.838-.576-3.583-4.196-6.348-8.596-6.348zM5.785 5.991c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 0 1-1.162 1.178A1.17 1.17 0 0 1 4.623 7.17c0-.651.52-1.18 1.162-1.18zm5.813 0c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 0 1-1.162 1.178 1.17 1.17 0 0 1-1.162-1.178c0-.651.52-1.18 1.162-1.18zm5.34 2.867c-1.797-.052-3.746.512-5.28 1.786-1.72 1.428-2.687 3.72-1.78 6.22.942 2.453 3.666 4.229 6.884 4.229.826 0 1.622-.12 2.361-.336a.722.722 0 0 1 .598.082l1.584.926a.272.272 0 0 0 .14.047c.134 0 .24-.111.24-.247 0-.06-.023-.12-.038-.177l-.327-1.233a.582.582 0 0 1-.023-.156.49.49 0 0 1 .201-.398C23.024 18.48 24 16.82 24 14.98c0-3.21-2.931-5.837-6.656-6.088V8.89c-.135-.01-.27-.027-.407-.03zm-2.53 3.274c.535 0 .969.44.969.982a.976.976 0 0 1-.969.983.976.976 0 0 1-.969-.983c0-.542.434-.982.97-.982zm4.844 0c.535 0 .969.44.969.982a.976.976 0 0 1-.969.983.976.976 0 0 1-.969-.983c0-.542.434-.982.969-.982z" />
          </svg>
          <!-- 支付宝：官方应用图标（蓝色圆角方块 + 「支」字挖空） -->
          <svg v-else-if="item.provider === 'alipay'" viewBox="0 0 24 24" fill="#1677FF">
            <path d="M19.695 15.07c3.426 1.158 4.203 1.22 4.203 1.22V3.846c0-2.124-1.705-3.845-3.81-3.845H3.914C1.808.001.102 1.722.102 3.846v16.31c0 2.123 1.706 3.845 3.813 3.845h16.173c2.105 0 3.81-1.722 3.81-3.845v-.157s-6.19-2.602-9.315-4.119c-2.096 2.602-4.8 4.181-7.607 4.181-4.75 0-6.361-4.19-4.112-6.949.49-.602 1.324-1.175 2.617-1.497 2.025-.502 5.247.313 8.266 1.317a16.796 16.796 0 0 0 1.341-3.302H5.781v-.952h4.799V6.975H4.77v-.953h5.81V3.591s0-.409.411-.409h2.347v2.84h5.744v.951h-5.744v1.704h4.69a19.453 19.453 0 0 1-1.986 5.06c1.424.52 2.702 1.011 3.654 1.333m-13.81-2.032c-.596.06-1.71.325-2.321.869-1.83 1.608-.735 4.55 2.968 4.55 2.151 0 4.301-1.388 5.99-3.61-2.403-1.182-4.438-2.028-6.637-1.809" />
          </svg>
          <!-- Apple：苹果剪影 -->
          <svg v-else-if="item.provider === 'apple'" viewBox="0 0 24 24">
            <path d="M16.5 12.6c0-2.3 1.9-3.4 2-3.4-1.1-1.6-2.8-1.8-3.4-1.9-1.4-.1-2.8.8-3.5.8-.7 0-1.8-.8-3-.8-1.5 0-3 .9-3.8 2.3-1.6 2.8-.4 7 1.2 9.3.8 1.1 1.7 2.4 2.9 2.3 1.2 0 1.6-.7 3-.7s1.8.7 3 .7c1.2 0 2-1.1 2.8-2.3.9-1.3 1.2-2.6 1.2-2.7 0-.1-2.4-.9-2.4-3.6Z" fill="#111" />
            <path d="M14.3 5.5c.6-.8 1.1-1.9 1-3-1 .1-2.1.7-2.8 1.5-.6.7-1.1 1.8-1 2.9 1.1.1 2.2-.6 2.8-1.4Z" fill="#111" />
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
        <span v-if="!item.enabled && showState" class="social-login__badge">未配置</span>
      </button>
    </div>
    <p v-if="loading" class="social-login__hint">正在探测可用渠道…</p>
    <p v-else-if="showHint" class="social-login__hint">
      {{ enabledCount === 0
        ? '社交登录均已接入但尚未配置凭证，填写 application-social.yml 后自动启用'
        : `${enabledCount} 个渠道已启用` }}
    </p>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { socialApi } from '../../api'

/**
 * 社交登录入口（微信 / 支付宝 / Apple / Google）
 *
 * 行为约定（与后端一致，不做任何伪造登录）：
 *  - 挂载时探测各渠道可用性；未配置的按钮仍展示品牌标识，但点击后给出明确提示；
 *  - 已配置的渠道：向服务端取真实授权地址后跳转（window.location），回跳由前端路由承接；
 *  - 服务端回调 /auth/social/callback/{provider} 为换取用户信息的唯一扩展点。
 */
const props = defineProps({
  /** 视觉变体：light（浅底表单内）| auto */
  variant: { type: String, default: 'light' },
  /** 是否显示「未配置」角标 */
  showState: { type: Boolean, default: true },
  /** 是否显示底部探测说明 */
  showHint: { type: Boolean, default: false }
})

const list = ref([])
const loading = ref(false)
const enabledCount = computed(() => list.value.filter((i) => i.enabled).length)

async function load() {
  loading.value = true
  try {
    const data = await socialApi.providers()
    list.value = Array.isArray(data) ? data : []
  } catch (e) {
    // 探测失败时兜底展示四个品牌入口（全部按未配置处理），保证界面不空
    list.value = [
      { provider: 'wechat', name: '微信', enabled: false },
      { provider: 'alipay', name: '支付宝', enabled: false },
      { provider: 'apple', name: 'Apple', enabled: false },
      { provider: 'google', name: 'Google', enabled: false }
    ]
  } finally {
    loading.value = false
  }
}

/** 一次性 state（防 CSRF），随授权地址下发、回调时原样带回校验 */
function newState() {
  const c = typeof crypto !== 'undefined' ? crypto : undefined
  if (c && typeof c.randomUUID === 'function') return c.randomUUID().replace(/-/g, '')
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 12)}`
}

async function start(item) {
  if (!item.enabled) {
    message.info(`${item.name} 登录未配置，请在 application-social.yml 中填写凭证后启用`)
    return
  }
  try {
    const res = await socialApi.authorize(item.provider, newState())
    if (res?.authorizeUrl) {
      // 记录渠道：平台回跳仅带 code/state，需据此还原是哪个渠道
      try { sessionStorage.setItem('ll_social_provider', item.provider) } catch (e) { /* 隐私模式忽略 */ }
      window.location.href = res.authorizeUrl
    } else {
      message.warning('未取得授权地址，请稍后重试')
    }
  } catch (e) { /* 拦截器已提示 */ }
}

onMounted(load)
</script>

<style scoped>
.social-login { width: 100%; }
.social-login__grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
}
.social-login__btn {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 10px 6px 8px;
  border: 1px solid var(--ll-border, #e5e7eb);
  border-radius: var(--ll-radius-md, 10px);
  background: var(--ll-card, #fff);
  color: var(--ll-ink, #0f172a);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: transform .18s ease, border-color .18s ease, box-shadow .18s ease;
}
.social-login__btn:hover {
  transform: translateY(-1px);
  border-color: var(--ll-skyblue-border, #5B8FB5);
  box-shadow: var(--ll-shadow-sm, 0 2px 8px rgba(19, 35, 58, .07));
}
.social-login__btn:focus-visible { outline: none; box-shadow: var(--ll-focus-ring, 0 0 0 3px rgba(30, 110, 184, .28)); }
.social-login__btn.is-off { opacity: .62; }
.social-login__icon { display: grid; place-items: center; width: 22px; height: 22px; }
.social-login__icon svg { width: 22px; height: 22px; }
.social-login__label { font-weight: 600; }
.social-login__badge {
  position: absolute; top: -6px; right: -4px;
  padding: 1px 5px; border-radius: 999px;
  font-size: 9px; font-weight: 800; letter-spacing: .04em;
  color: var(--ll-gray, #4b5563); background: var(--ll-thumb-bg, #eef1f6);
  border: 1px solid var(--ll-border, #e5e7eb);
}
.social-login__hint { margin: 8px 0 0; font-size: 11px; line-height: 1.6; color: var(--ll-muted, #64748b); text-align: center; }

@media (max-width: 420px) {
  .social-login__grid { grid-template-columns: repeat(2, 1fr); }
}
/* 深色模式：按钮表面随令牌翻转，文字/图标仍保持对比 */
[data-theme='dark'] .social-login__btn { background: var(--ll-card-soft, #1a2536); color: var(--ll-ink, #e8edf5); }
</style>
