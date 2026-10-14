<template>
  <div class="privacy-page">
    <a-page-header
      title="隐私与 Cookie"
      sub-title="Cookie、分析与第三方授权的统一管理入口"
      @back="$router.back()"
    />

    <div class="hero-card">
      <div>
        <div class="eyebrow">PRIVACY CENTER · COOKIE CONTROL</div>
        <h2>你的数据偏好，由你决定</h2>
        <p>LANLinkshopping 只在获得对应授权后启用可选的偏好、分析与营销能力。必要 Cookie 始终保持开启，以保障登录、会话、安全验证和核心交易流程。</p>
      </div>
      <a-button type="primary" size="large" @click="openCookiePreferences">管理 Cookie 偏好</a-button>
    </div>

    <div class="status-card">
      <div class="status-head">
        <strong>当前授权状态</strong>
        <span>{{ consent?.updatedAt ? '已保存' : '尚未设置' }}</span>
      </div>
      <div class="status-grid">
        <div v-for="item in categories" :key="item.key" class="status-item">
          <span class="status-dot" :class="{ on: item.required || !!consent?.categories?.[item.key] }"></span>
          <div>
            <b>{{ item.title }}</b>
            <p>{{ enabledText(item.key) }}</p>
          </div>
        </div>
      </div>
    </div>

    <div class="category-grid">
      <a-card v-for="item in categories" :key="item.key" :bordered="false">
        <div class="category-title">
          <strong>{{ item.title }}</strong>
          <a-tag v-if="item.required" color="blue">始终启用</a-tag>
          <a-tag v-else :color="consent?.categories?.[item.key] ? 'green' : 'default'">
            {{ consent?.categories?.[item.key] ? '已开启' : '未开启' }}
          </a-tag>
        </div>
        <p>{{ item.description }}</p>
        <small>{{ item.scope }}</small>
      </a-card>
    </div>

    <a-card :bordered="false">
      <div class="notice">
        <b>说明</b>
        <span>你可以随时修改 Cookie 偏好。撤回分析授权后，平台会同时清除本地分析会话；不会清除服务端登录会话。</span>
      </div>
    </a-card>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { openCookiePreferences, readCookieConsent } from '../utils/cookieConsent'

const consent = ref(readCookieConsent())

const categories = [
  { key: 'necessary', title: '必要 Cookie', required: true, enabled: true, description: '登录、会话、安全验证、购物车和核心交易流程。', scope: '始终启用' },
  { key: 'preferences', title: '偏好 Cookie', description: '保存主题、语言、字号及界面选择，减少重复设置。', scope: '可选' },
  { key: 'analytics', title: '分析 Cookie', description: '支持页面访问、商品浏览和采购流程的匿名/聚合分析。', scope: '可选' },
  { key: 'marketing', title: '营销 Cookie', description: '用于活动推广及营销效果衡量；当前默认不启用第三方广告追踪。', scope: '可选' }
]

function enabledText(key) {
  if (key === 'necessary') return '核心功能依赖'
  if (!consent.value) return '尚未选择'
  return consent.value?.categories?.[key] ? '已授权' : '未授权'
}

function sync(event) {
  consent.value = event.detail || readCookieConsent()
}

onMounted(() => window.addEventListener('ll-cookie-consent-changed', sync))
onBeforeUnmount(() => window.removeEventListener('ll-cookie-consent-changed', sync))
</script>

<style scoped>
.privacy-page { max-width: 1040px; margin: 0 auto; }
.hero-card, .status-card { border: 1px solid #e6e9ef; border-radius: 18px; background: rgba(255,255,255,.86); box-shadow: 0 14px 36px rgba(15,23,42,.07); }
.hero-card { margin: 8px 0 16px; padding: 28px; display: flex; align-items: center; justify-content: space-between; gap: 24px; background: linear-gradient(135deg, rgba(19,35,58,.97), rgba(31,56,100,.92)); color: #fff; overflow: hidden; }
.eyebrow { color: #F6D38A; font-size: 10px; letter-spacing: .16em; font-weight: 800; }
.hero-card h2 { margin: 7px 0 8px; font-size: 25px; }
.hero-card p { max-width: 700px; margin: 0; color: rgba(255,255,255,.76); line-height: 1.75; font-size: 13px; }
.status-card { padding: 20px; margin-bottom: 16px; }
.status-head { display: flex; align-items: center; justify-content: space-between; color: #13233A; margin-bottom: 14px; }
.status-head span { color: #667085; font-size: 12px; }
.status-grid { display: grid; grid-template-columns: repeat(4,1fr); gap: 12px; }
.status-item { display: flex; gap: 10px; padding: 14px; border: 1px solid #edf0f3; border-radius: 12px; }
.status-item b { color: #13233A; font-size: 13px; }
.status-item p { margin: 3px 0 0; color: #98A2B3; font-size: 11px; }
.status-dot { width: 9px; height: 9px; border-radius: 50%; background: #D0D5DD; margin-top: 4px; flex: 0 0 auto; }
.status-dot.on { background: #1E6EB8; box-shadow: 0 0 0 4px rgba(30,110,184,.10); }
.category-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px; margin-bottom: 16px; }
.category-title { display: flex; align-items: center; gap: 8px; }
.category-title strong { color: #13233A; }
.category-grid p { color: #667085; line-height: 1.7; font-size: 12px; margin: 9px 0; }
.category-grid small { color: #98A2B3; font-size: 11px; }
.notice { display: flex; gap: 14px; color: #667085; font-size: 12px; line-height: 1.7; }
.notice b { color: #13233A; flex: 0 0 auto; }
@media (max-width: 767px) {
  .hero-card { flex-direction: column; align-items: stretch; padding: 22px; }
  .status-grid { grid-template-columns: 1fr 1fr; }
  .category-grid { grid-template-columns: 1fr; }
}
</style>
