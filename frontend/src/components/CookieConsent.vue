<template>
  <Teleport to="body">
    <transition name="cookie-fade">
      <div v-if="bannerVisible" class="cookie-banner" role="dialog" aria-label="Cookie 使用提示">
        <div class="cookie-copy">
          <div class="cookie-eyebrow">PRIVACY · COOKIES</div>
          <h3>我们使用 Cookie 优化 LANLinkshopping</h3>
          <p>
            必要 Cookie 用于登录、会话和安全功能；可选 Cookie 用于记住偏好、分析平台使用情况及改善营销体验。
            你可以接受全部，也可以仅启用必要 Cookie。
          </p>
          <button class="text-link" type="button" @click="openPreferences">查看 Cookie 详情与偏好</button>
        </div>
        <div class="cookie-actions">
          <a-button @click="rejectOptional">仅必要 Cookie</a-button>
          <a-button type="primary" @click="acceptAll">接受全部</a-button>
        </div>
      </div>
    </transition>

    <a-modal
      v-model:open="preferencesOpen"
      title="Cookie 偏好设置"
      :footer="null"
      :width="680"
      destroy-on-close
    >
      <div class="preferences-intro">
        <p>你可以随时修改可选 Cookie。必要 Cookie 用于身份认证、会话保持、CSRF 防护和核心交易流程，无法关闭。</p>
        <div class="privacy-meta">
          <span>策略版本 v1.0</span>
          <span v-if="consent">最近更新：{{ formatUpdatedAt(consent.updatedAt) }}</span>
          <span v-else>当前状态：尚未选择</span>
        </div>
      </div>

      <div class="cookie-list">
        <section v-for="item in categories" :key="item.key" class="cookie-item">
          <div class="cookie-item-main">
            <div class="cookie-title">
              <strong>{{ item.title }}</strong>
              <a-tag v-if="item.required" color="blue">始终启用</a-tag>
            </div>
            <p>{{ item.description }}</p>
            <small>{{ item.examples }}</small>
          </div>
          <a-switch
            :checked="draft[item.key]"
            :disabled="item.required"
            @change="v => draft[item.key] = v"
          />
        </section>
      </div>

      <div class="preference-actions">
        <a-button @click="rejectOptional">仅必要 Cookie</a-button>
        <a-button @click="saveDraft">保存选择</a-button>
        <a-button type="primary" @click="acceptAll">接受全部</a-button>
      </div>
    </a-modal>
  </Teleport>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { readCookieConsent, saveCookieConsent } from '../utils/cookieConsent'

const consent = ref(readCookieConsent())
const preferencesOpen = ref(false)
const draft = reactive({
  necessary: true,
  preferences: false,
  analytics: false,
  marketing: false
})

const categories = [
  { key: 'necessary', title: '必要 Cookie', required: true, description: '保证登录、会话、安全验证、购物车及核心交易流程正常运行。', examples: '示例：会话 Cookie、CSRF 安全状态' },
  { key: 'preferences', title: '偏好 Cookie', required: false, description: '记住主题、语言、字号及部分界面选择，减少重复设置。', examples: '示例：界面偏好、地区/语言选择' },
  { key: 'analytics', title: '分析 Cookie', required: false, description: '帮助平台了解页面访问、商品浏览及采购流程表现，用于改善产品。', examples: '示例：匿名访问统计、页面行为指标' },
  { key: 'marketing', title: '营销 Cookie', required: false, description: '用于营销活动、个性化推广和活动效果衡量。', examples: '当前平台默认不启用第三方广告追踪' }
]

const bannerVisible = computed(() => !consent.value)

function syncDraft() {
  const current = consent.value?.categories || {}
  draft.necessary = true
  draft.preferences = !!current.preferences
  draft.analytics = !!current.analytics
  draft.marketing = !!current.marketing
}

function save(categoriesValue, notify = true) {
  consent.value = saveCookieConsent(categoriesValue)
  syncDraft()
  if (notify) message.success('Cookie 偏好已保存')
  preferencesOpen.value = false
}

function acceptAll() {
  save({ necessary: true, preferences: true, analytics: true, marketing: true })
}

function rejectOptional() {
  save({ necessary: true, preferences: false, analytics: false, marketing: false })
}

function saveDraft() {
  save({ ...draft })
}

function formatUpdatedAt(value) {
  if (!value) return '—'
  try {
    return new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit'
    }).format(new Date(value))
  } catch (e) {
    return value
  }
}

function openPreferences() {
  syncDraft()
  preferencesOpen.value = true
}

function handleOpen() {
  openPreferences()
}

function handleChanged(event) {
  consent.value = event.detail || readCookieConsent()
}

onMounted(() => {
  window.addEventListener('ll-open-cookie-preferences', handleOpen)
  window.addEventListener('ll-cookie-consent-changed', handleChanged)
  syncDraft()
})

onBeforeUnmount(() => {
  window.removeEventListener('ll-open-cookie-preferences', handleOpen)
  window.removeEventListener('ll-cookie-consent-changed', handleChanged)
})
</script>

<style scoped>
.cookie-banner {
  position: fixed;
  left: 20px;
  right: 20px;
  bottom: 20px;
  z-index: 3000;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  max-width: 1180px;
  margin: auto;
  padding: 22px 24px;
  border: 1px solid rgba(148,163,184,.24);
  border-radius: 18px;
  background: rgba(255,255,255,.96);
  box-shadow: 0 20px 55px rgba(15,23,42,.18);
  backdrop-filter: blur(18px) saturate(125%);
}
.cookie-copy { min-width: 0; }
.cookie-eyebrow { color: #9a7a39; font-size: 10px; letter-spacing: .16em; font-weight: 800; }
.cookie-banner h3 { margin: 5px 0 7px; color: var(--ll-navy,#13233A); font-size: 17px; }
.cookie-banner p { margin: 0; color: #667085; font-size: 12px; line-height: 1.7; max-width: 760px; }
.text-link { margin-top: 7px; padding: 0; border: 0; background: transparent; color: #1e6eb8; cursor: pointer; font-size: 12px; }
.cookie-actions { display: flex; gap: 9px; flex: 0 0 auto; }
.preferences-intro { color: #667085; font-size: 12px; line-height: 1.7; }
.preferences-intro p { margin: 0 0 6px; }
.privacy-meta { display: flex; flex-wrap: wrap; gap: 10px 18px; color: #98a2b3; font-size: 11px; }
.privacy-meta span::before { content: '•'; margin-right: 6px; color: #C8A45C; }
.cookie-list { margin-top: 14px; border-top: 1px solid #edf0f3; }
.cookie-item { display: flex; align-items: center; gap: 20px; padding: 16px 0; border-bottom: 1px solid #edf0f3; }
.cookie-item-main { flex: 1; min-width: 0; }
.cookie-title { display: flex; align-items: center; gap: 8px; }
.cookie-title strong { color: var(--ll-navy,#13233A); font-size: 14px; }
.cookie-item p { margin: 5px 0 3px; color: #667085; font-size: 12px; line-height: 1.6; }
.cookie-item small { color: #98a2b3; font-size: 10px; }
.preference-actions { display: flex; justify-content: flex-end; gap: 9px; margin-top: 18px; }
.cookie-fade-enter-active, .cookie-fade-leave-active { transition: opacity .2s ease, transform .2s ease; }
.cookie-fade-enter-from, .cookie-fade-leave-to { opacity: 0; transform: translateY(10px); }
@media (max-width: 767px) {
  .cookie-banner { left: 10px; right: 10px; bottom: 10px; padding: 17px; flex-direction: column; align-items: stretch; gap: 14px; }
  .cookie-actions { display: grid; grid-template-columns: 1fr 1fr; }
  .cookie-actions .ant-btn:last-child { grid-column: 1 / -1; }
  .preference-actions { display: grid; grid-template-columns: 1fr; }
}
</style>
