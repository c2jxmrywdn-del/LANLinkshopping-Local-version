<template>
  <div class="cd-page" v-if="c">
    <button type="button" class="cd-back" @click="$router.back()">← 返回活动中心</button>

    <div class="cd-hero" :class="accent(c.accent)">
      <div class="cd-hero-body">
        <div class="cd-chips">
          <span class="cd-type">{{ typeLabel(c.type) }}</span>
          <span class="cd-scope">{{ c.indName ? c.indName + '行业专享' : '全场通用' }}</span>
        </div>
        <h1>{{ c.title }}</h1>
        <p v-if="c.description">{{ c.description }}</p>
        <!-- 券面 / 优惠摘要 -->
        <div class="cd-value" v-if="c.type === 'coupon'">
          <template v-if="c.discountRate"><b>{{ rateText(c.discountRate) }}</b>折<small v-if="c.benefitAmount">，最高减 ¥{{ trimZero(c.benefitAmount) }}</small></template>
          <template v-else><small>¥</small><b>{{ trimZero(c.benefitAmount) }}</b></template>
          <span class="cd-th">{{ c.threshold > 0 ? `满 ¥${trimZero(c.threshold)} 可用` : '无门槛' }}</span>
        </div>
        <div class="cd-value" v-else-if="['full_reduce','discount','flash_sale'].includes(c.type)">
          <template v-if="c.type === 'full_reduce'"><b>减 ¥{{ trimZero(c.benefitAmount) }}</b><span class="cd-th">满 ¥{{ trimZero(c.threshold) }} 可享</span></template>
          <template v-else><b>{{ rateText(c.discountRate) }} 折</b><span class="cd-th">满 ¥{{ trimZero(c.threshold || 0) }} 可享<small v-if="c.benefitAmount">，最高减 ¥{{ trimZero(c.benefitAmount) }}</small></span></template>
        </div>
        <div class="cd-value" v-else>
          <b class="cd-pts">+{{ c.rewardPoints }}</b><span class="cd-th">参与奖励积分</span>
        </div>
      </div>
      <!-- 倒计时（秒杀醒目展示） -->
      <div class="cd-countdown" v-if="c.endTime">
        <span class="cd-cd-label">{{ remainMs > 0 ? '距活动结束' : '活动已结束' }}</span>
        <b v-if="remainMs > 0" class="cd-cd-time" :class="{ flash: c.type === 'flash_sale' }">{{ remainText }}</b>
      </div>
    </div>

    <div class="cd-cols">
      <!-- 规则说明 -->
      <div class="cd-panel">
        <h2>活动规则</h2>
        <ul class="cd-rules">
          <li v-for="(r, i) in rules(c)" :key="i">{{ r }}</li>
        </ul>
        <div class="cd-meta">
          <div><span>开始时间</span><b>{{ fmt(c.startTime) || '—' }}</b></div>
          <div><span>结束时间</span><b>{{ fmt(c.endTime) || '长期有效' }}</b></div>
          <div v-if="c.type === 'coupon'"><span>每人限领</span><b>{{ c.perUserLimit || 1 }} 张</b></div>
          <div v-if="c.type === 'coupon' && c.totalQuota > 0"><span>发放总量</span><b>{{ c.claimedCount || 0 }} / {{ c.totalQuota }} 张</b></div>
          <div v-if="c.type === 'coupon'"><span>我的已领</span><b>{{ c.myCouponCount || 0 }} 张</b></div>
        </div>
      </div>

      <!-- 行动区 -->
      <div class="cd-panel cd-act">
        <template v-if="c.type === 'coupon'">
          <button type="button" class="cd-btn" :class="accent(c.accent)" :disabled="!c.canClaim" @click="claim">
            {{ c.limitReached ? '已达限领上限' : (c.remainQuota === 0 ? '已抢完' : '立即领取') }}
          </button>
          <p class="cd-hint">领取后进入"我的券包"，结算下单时选用核销。</p>
          <button type="button" class="cd-btn ghost" @click="$router.push('/activity/my')">查看我的券包</button>
        </template>
        <template v-else-if="c.type === 'register'">
          <button type="button" class="cd-btn" :class="accent(c.accent)" :disabled="c.joined" @click="join">
            {{ c.joined ? '已参与' : '立即参与' }}
          </button>
          <p class="cd-hint">参与成功后积分即时到账，可在会员中心查看积分流水。</p>
          <button type="button" class="cd-btn ghost" @click="$router.push('/membership/points')">查看积分</button>
        </template>
        <template v-else-if="c.type === 'purchase'">
          <button type="button" class="cd-btn" :class="accent(c.accent)" @click="$router.push('/mall')">去下单</button>
          <p class="cd-hint">活动期间完成任意订单支付，系统自动记录参与并发放积分。</p>
        </template>
        <template v-else>
          <button type="button" class="cd-btn" :class="accent(c.accent)" @click="$router.push('/mall')">去下单享优惠</button>
          <p class="cd-hint">满足门槛后结算时自动享受，与会员等级折扣互斥取最优。</p>
        </template>
      </div>
    </div>
  </div>

  <div v-else class="cd-empty">
    <a-empty description="活动不存在或已结束">
      <a-button type="primary" @click="$router.push('/activity')">返回活动中心</a-button>
    </a-empty>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { campaignApi } from '../api'

const route = useRoute()
const router = useRouter()
const c = ref(null)
const now = ref(Date.now())
let timer = null

const remainMs = computed(() => {
  if (!c.value?.endTime) return -1
  return new Date(c.value.endTime).getTime() - now.value
})
const remainText = computed(() => {
  const ms = remainMs.value
  if (ms <= 0) return '00:00:00'
  const s = Math.floor(ms / 1000)
  const d = Math.floor(s / 86400)
  const h = String(Math.floor((s % 86400) / 3600)).padStart(2, '0')
  const m = String(Math.floor((s % 3600) / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  return d > 0 ? `${d} 天 ${h}:${m}:${ss}` : `${h}:${m}:${ss}`
})

async function load() {
  try { c.value = await campaignApi.detail(route.params.id) || null }
  catch (e) { c.value = null }
}
const logged = () => !!localStorage.getItem('ll_user')
function requireLogin() {
  message.info('请先登录')
  router.push({ path: '/login', query: { redirect: route.fullPath } })
}
async function claim() {
  if (!logged()) return requireLogin()
  try {
    await campaignApi.claim(c.value.campaignId)
    message.success('领取成功，结算时可用')
    load()
  } catch (e) { /* 拦截器已提示 */ }
}
async function join() {
  if (!logged()) return requireLogin()
  try {
    await campaignApi.join(c.value.campaignId)
    router.push(`/activity/${c.value.campaignId}/participation`)
  } catch (e) { /* 拦截器已提示 */ }
}

// ===== 展示口径 =====
const ACCENTS = ['blue', 'red', 'gold', 'green', 'purple']
const accent = (a) => 'ac-' + (ACCENTS.includes(a) ? a : 'blue')
const typeLabel = (t) => ({ coupon: '领券活动', full_reduce: '满减促销', discount: '折扣促销', flash_sale: '限时秒杀', register: '注册有礼', purchase: '消费有礼' }[t] || '平台活动')
const trimZero = (v) => Number(v) % 1 === 0 ? String(Number(v)) : String(v)
const rateText = (r) => trimZero(Number(r) * 10)
const fmt = (v) => v ? String(v).replace('T', ' ').slice(0, 16) : ''
function rules(cm) {
  const base = [
    `活动时间：${fmt(cm.startTime) || '即日起'} 至 ${fmt(cm.endTime) || '长期有效'}`,
    cm.indName ? `适用范围：${cm.indName}行业商品订单` : '适用范围：全场商品订单'
  ]
  if (cm.type === 'coupon') {
    base.push(cm.threshold > 0 ? `使用门槛：订单金额满 ¥${trimZero(cm.threshold)}` : '使用门槛：无门槛')
    if (cm.discountRate) base.push(`优惠方式：订单金额按 ${rateText(cm.discountRate)} 折结算${cm.benefitAmount ? `，最高优惠 ¥${trimZero(cm.benefitAmount)}` : ''}`)
    else base.push(`优惠方式：下单立减 ¥${trimZero(cm.benefitAmount)}`)
    base.push(`每人限领 ${cm.perUserLimit || 1} 张，数量有限先到先得`, '优惠券在提交订单时选用，逾期未使用自动作废', '订单取消或全额退款时，优惠券自动退回券包')
  } else if (['full_reduce', 'discount', 'flash_sale'].includes(cm.type)) {
    base.push('结算时满足门槛自动享受，无需领取', '促销优惠与会员等级折扣互斥，系统自动取对您更优的一种')
    if (cm.type === 'flash_sale') base.push('限时秒杀逾时自动恢复原价，以下单结算时间为准')
  } else if (cm.type === 'register') {
    base.push(`参与奖励：${cm.rewardPoints} 积分，参与后即时到账`, '同一活动每位用户仅可参与一次')
  } else if (cm.type === 'purchase') {
    base.push(`参与奖励：${cm.rewardPoints} 积分`, '活动期间完成任意订单支付即自动参与，无需手动报名', '积分在订单支付成功后发放，可在会员中心查看流水')
  }
  return base
}

onMounted(() => {
  load()
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.cd-page { max-width: 920px; margin: 0 auto; }
.cd-back { border: none; background: none; cursor: pointer; color: var(--ll-primary, #1E6EB8);
  font-size: 13.5px; padding: 4px 0; margin-bottom: 12px; }
.cd-back:hover { opacity: .8; }

.cd-hero { display: flex; justify-content: space-between; align-items: center; gap: 20px;
  border-radius: 24px; padding: 32px 34px; margin-bottom: 18px; position: relative; overflow: hidden;
  background: var(--ll-card, #fff); box-shadow: var(--ll-shadow-sm, 0 1px 2px rgba(16,24,40,.06)); }
.cd-hero::after { content: ''; position: absolute; right: -60px; top: -80px; width: 300px; height: 300px;
  border-radius: 50%; opacity: .12; pointer-events: none; }
.ac-blue.cd-hero::after { background: var(--ll-primary, #1E6EB8); }
.ac-red.cd-hero::after { background: #e0354b; }
.ac-gold.cd-hero::after { background: #b45309; }
.ac-green.cd-hero::after { background: #047857; }
.ac-purple.cd-hero::after { background: #6d28d9; }
.cd-hero-body { position: relative; flex: 1; min-width: 0; }
.cd-chips { display: flex; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.cd-type { color: #fff; font-size: 12px; font-weight: 700; padding: 4px 12px; border-radius: 999px; }
.ac-blue .cd-type { background: linear-gradient(135deg, #1E6EB8, #38bdf8); }
.ac-red .cd-type { background: linear-gradient(135deg, #e0354b, #ff7a59); }
.ac-gold .cd-type { background: linear-gradient(135deg, #b45309, #f2b544); }
.ac-green .cd-type { background: linear-gradient(135deg, #047857, #34d399); }
.ac-purple .cd-type { background: linear-gradient(135deg, #6d28d9, #c084fc); }
.cd-scope { font-size: 12px; color: var(--ll-muted, #64748B); background: var(--ll-soft, rgba(15,23,42,.06));
  padding: 4px 10px; border-radius: 999px; }
.cd-hero h1 { margin: 0 0 8px; font-size: 28px; letter-spacing: -.02em; color: var(--ll-ink, #0F172A); }
.cd-hero p { margin: 0 0 4px; color: var(--ll-muted, #64748B); font-size: 14px; max-width: 520px; }
.cd-value { margin-top: 14px; display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
.cd-value b { font-size: 40px; letter-spacing: -.03em; color: var(--ll-ink, #0F172A); line-height: 1; }
.cd-value small { font-size: 14px; color: var(--ll-muted, #64748B); }
.cd-value .cd-th { font-size: 13px; color: var(--ll-muted, #64748B); }
.cd-pts { color: #b45309; }

.cd-countdown { text-align: center; position: relative; }
.cd-cd-label { display: block; font-size: 12px; color: var(--ll-muted, #64748B); margin-bottom: 6px; }
.cd-cd-time { font-variant-numeric: tabular-nums; font-size: 26px; letter-spacing: -.02em; color: var(--ll-ink, #0F172A); }
.cd-cd-time.flash { color: #d93025; }

.cd-cols { display: grid; grid-template-columns: 1.6fr 1fr; gap: 16px; align-items: start; }
.cd-panel { background: var(--ll-card, #fff); border-radius: 20px; padding: 22px 24px;
  box-shadow: var(--ll-shadow-sm, 0 1px 2px rgba(16,24,40,.06)); }
.cd-panel h2 { margin: 0 0 12px; font-size: 16px; color: var(--ll-ink, #0F172A); }
.cd-rules { margin: 0; padding-left: 18px; color: var(--ll-muted, #64748B); font-size: 13.5px; line-height: 2; }
.cd-meta { margin-top: 16px; border-top: 1px solid var(--ll-border, #e5e7eb); padding-top: 14px;
  display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 12px; }
.cd-meta span { display: block; font-size: 12px; color: var(--ll-muted, #64748B); margin-bottom: 3px; }
.cd-meta b { font-size: 14px; color: var(--ll-ink, #0F172A); font-weight: 600; }

.cd-act { display: flex; flex-direction: column; gap: 10px; }
.cd-btn { border: none; cursor: pointer; width: 100%; padding: 13px 0; border-radius: 14px;
  font-size: 15px; font-weight: 700; color: #fff; transition: transform .2s, box-shadow .2s, opacity .2s; }
.cd-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 10px 24px rgba(16,24,40,.16); }
.cd-btn:disabled { cursor: not-allowed; opacity: .45; }
.cd-btn.ghost { background: var(--ll-soft, rgba(15,23,42,.06)); color: var(--ll-ink, #0F172A);
  box-shadow: inset 0 0 0 1px var(--ll-border, #e5e7eb); }
.ac-blue.cd-btn:not(.ghost) { background: linear-gradient(135deg, #1E6EB8, #38bdf8); }
.ac-red.cd-btn:not(.ghost) { background: linear-gradient(135deg, #e0354b, #ff7a59); }
.ac-gold.cd-btn:not(.ghost) { background: linear-gradient(135deg, #b45309, #f2b544); }
.ac-green.cd-btn:not(.ghost) { background: linear-gradient(135deg, #047857, #34d399); }
.ac-purple.cd-btn:not(.ghost) { background: linear-gradient(135deg, #6d28d9, #c084fc); }
.cd-hint { margin: 0; font-size: 12.5px; color: var(--ll-muted, #64748B); line-height: 1.7; text-align: center; }

.cd-empty { max-width: 400px; margin: 80px auto; }

@media (max-width: 760px) {
  .cd-hero { flex-direction: column; align-items: flex-start; padding: 24px 20px; }
  .cd-value b { font-size: 32px; }
  .cd-cols { grid-template-columns: 1fr; }
  .cd-countdown { text-align: left; }
}
</style>
