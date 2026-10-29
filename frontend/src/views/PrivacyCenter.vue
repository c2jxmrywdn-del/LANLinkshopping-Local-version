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

    <a-card :bordered="false" class="habit-card">
      <div class="habit-head">
        <strong>用户习惯分析（分析类 Cookie）</strong>
        <a-tag :color="habit ? 'green' : 'default'">{{ habit ? '已开启' : '未开启' }}</a-tag>
      </div>
      <template v-if="habit">
        <div class="habit-grid">
          <div class="habit-item"><b>{{ habit.visits }}</b><p>近 30 天访问次数</p></div>
          <div class="habit-item"><b>{{ habit.topSearches.length }}</b><p>记录的搜索偏好</p></div>
          <div class="habit-item"><b>{{ habit.recentProducts.length }}</b><p>最近浏览的商品</p></div>
        </div>
        <ul v-if="habit.topSearches.length" class="habit-list">
          <li v-for="s in habit.topSearches" :key="s.term">搜索"{{ s.term }}" × {{ s.count }}</li>
        </ul>
        <ul v-if="habit.topCategories.length" class="habit-list">
          <li v-for="c in habit.topCategories" :key="c.name">偏好类目：{{ c.name }} × {{ c.count }}</li>
        </ul>
        <div class="habit-actions">
          <a-button size="small" @click="clearHabitData">清除我的习惯数据</a-button>
          <small>登录状态下会同步清除服务端副本；画像仅存浏览偏好，不含凭证信息。</small>
        </div>
      </template>
      <p v-else class="habit-off">
        未开启分析类 Cookie：平台不会记录你的搜索、浏览与访问习惯。可在上方 Cookie 分类中开启"分析 Cookie"。
      </p>
    </a-card>

    <a-card :bordered="false">
      <div class="notice">
        <b>说明</b>
        <span>你可以随时修改 Cookie 偏好。撤回分析授权后，平台会同时清除本地分析会话；不会清除服务端登录会话。</span>
      </div>
      <div class="doc-links">
        <strong>相关文档全文：</strong>
        <a @click.prevent="$router.push({ name: 'legal-doc', params: { docKey: 'privacy' } })">《隐私政策》</a>
        <a @click.prevent="$router.push({ name: 'legal-doc', params: { docKey: 'cookies' } })">《Cookie 政策》</a>
        <a @click.prevent="$router.push({ name: 'legal-doc', params: { docKey: 'terms' } })">《用户服务协议》</a>
      </div>
    </a-card>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { openCookiePreferences, readCookieConsent } from '../utils/cookieConsent'
import { habitSummary, clearHabits } from '../utils/habitTracker'

const consent = ref(readCookieConsent())
const habit = ref(habitSummary())

async function clearHabitData() {
  await clearHabits()
  habit.value = habitSummary()
  message.success('习惯数据已清除（含服务端副本，如已登录）')
}

/** 授权撤回联动：关闭"分析 Cookie"即清除习惯画像（本机 + 服务端），个性化推荐随之自动降级 */
async function withdrawHabitIfRevoked(newConsent) {
  if (newConsent && newConsent.categories && !newConsent.categories.analytics) {
    await clearHabits()
  }
}

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
  habit.value = habitSummary()
  withdrawHabitIfRevoked(consent.value)
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
.doc-links { margin-top: 12px; padding-top: 12px; border-top: 1px solid #f1f3f6; color: #667085; font-size: 12px; display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.doc-links strong { color: #13233A; }
.doc-links a { color: #1E6EB8; cursor: pointer; }
.doc-links a:hover { text-decoration: underline; }
.habit-card { margin-bottom: 16px; }
.habit-head { display: flex; align-items: center; gap: 10px; color: #13233A; }
.habit-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin: 14px 0 10px; }
.habit-item { padding: 13px; border: 1px solid #edf0f3; border-radius: 12px; }
.habit-item b { color: #1E6EB8; font-size: 19px; }
.habit-item p { margin: 4px 0 0; color: #98A2B3; font-size: 11px; }
.habit-list { margin: 6px 0; padding-left: 18px; color: #667085; font-size: 12px; line-height: 1.8; }
.habit-actions { display: flex; align-items: center; gap: 12px; margin-top: 10px; }
.habit-actions small { color: #98A2B3; font-size: 11px; line-height: 1.6; }
.habit-off { margin: 10px 0 0; color: #667085; font-size: 12px; line-height: 1.7; }
@media (max-width: 575px) {
  .habit-grid { grid-template-columns: 1fr; }
  .habit-actions { flex-direction: column; align-items: flex-start; }
}
@media (max-width: 767px) {
  .hero-card { flex-direction: column; align-items: stretch; padding: 22px; }
  .status-grid { grid-template-columns: 1fr 1fr; }
  .category-grid { grid-template-columns: 1fr; }
}
</style>
