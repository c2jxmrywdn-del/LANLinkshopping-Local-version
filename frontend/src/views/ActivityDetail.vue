<template>
  <div class="cd-page" v-if="c">
    <button type="button" class="cd-back" @click="$router.push('/activity')">‹ 活动中心</button>

    <!-- Apple 产品页式 Hero -->
    <header class="cd-hero">
      <div class="cd-chips">
        <span class="cd-type">{{ typeLabel(c.type) }}</span>
        <span class="cd-scope">{{ c.indName ? c.indName + '行业专享' : '全场通用' }}</span>
      </div>
      <h1>{{ c.title }}</h1>
      <p v-if="c.description">{{ c.description }}</p>
    </header>

    <div class="cd-cols">
      <!-- 左列：票券预览 + 规格 -->
      <div class="cd-main">
        <!-- 券面 / 优惠横幅 -->
        <div class="cd-face" v-if="c.type === 'coupon'">
          <div class="face-stub">
            <span class="face-kind">{{ c.discountRate ? '折扣' : '满减券' }}</span>
            <div class="face-value">
              <template v-if="c.discountRate"><b>{{ rateText(c.discountRate) }}</b><i>折</i></template>
              <template v-else><i>¥</i><b>{{ trimZero(c.benefitAmount) }}</b></template>
            </div>
            <span class="face-th">{{ c.threshold > 0 ? `满${trimZero(c.threshold)}可用` : '无门槛' }}</span>
          </div>
          <div class="face-body">
            <span class="face-scope">{{ c.indName ? c.indName + '专享' : '全场通用' }}</span>
            <b class="face-title">{{ c.title }}</b>
            <i class="face-barcode" aria-hidden="true"></i>
          </div>
        </div>
        <div class="cd-banner" v-else-if="['full_reduce','discount','flash_sale'].includes(c.type)">
          <template v-if="c.type === 'full_reduce'">
            <small>满 ¥{{ trimZero(c.threshold) }}</small><b>减 ¥{{ trimZero(c.benefitAmount) }}</b>
          </template>
          <template v-else>
            <b>{{ rateText(c.discountRate) }}<i>折</i></b>
            <small>满 ¥{{ trimZero(c.threshold || 0) }} 可享<template v-if="c.benefitAmount">，最高减 ¥{{ trimZero(c.benefitAmount) }}</template></small>
          </template>
        </div>
        <div class="cd-banner" v-else>
          <b class="cd-pts">+{{ c.rewardPoints }}</b><small>参与奖励积分</small>
        </div>
        <!-- 秒杀倒计时条 -->
        <div class="cd-flashbar" v-if="c.type === 'flash_sale' && remainMs > 0">
          ⚡ 距结束 <b class="cd-cd">{{ remainText }}</b>
        </div>

        <!-- 规格：Apple tech-specs 行 -->
        <div class="cd-specs">
          <h2>活动规则</h2>
          <div v-for="(r, i) in rules(c)" :key="i" class="spec-row">
            <span>{{ r[0] }}</span><b>{{ r[1] }}</b>
          </div>
          <ul class="cd-notes">
            <li v-for="(n, i) in notes(c)" :key="'n' + i">{{ n }}</li>
          </ul>
        </div>
      </div>

      <!-- 右列：行动卡（毛玻璃） -->
      <aside class="cd-act">
        <div class="act-count" v-if="c.endTime && remainMs > 0">
          <span>距活动结束</span>
          <b>{{ remainText }}</b>
        </div>
        <template v-if="c.type === 'coupon'">
          <button type="button" class="act-btn" :disabled="!c.canClaim" @click="claim">
            {{ c.limitReached ? '已达限领上限' : (c.remainQuota === 0 ? '已抢完' : '立即领取') }}
          </button>
          <p class="act-hint">领取后进入"我的券包"，结算下单时选用核销。</p>
          <button type="button" class="act-btn ghost" @click="$router.push('/activity/my')">查看我的券包</button>
        </template>
        <template v-else-if="c.type === 'register'">
          <button type="button" class="act-btn" :disabled="c.joined" @click="join">{{ c.joined ? '已参与' : '立即参与' }}</button>
          <p class="act-hint">参与成功后积分即时到账，可在会员中心查看流水。</p>
          <button type="button" class="act-btn ghost" @click="$router.push('/membership/points')">查看积分</button>
        </template>
        <template v-else-if="c.type === 'purchase'">
          <button type="button" class="act-btn" @click="$router.push('/mall')">去下单</button>
          <p class="act-hint">活动期间完成任意订单支付，自动参与并发放积分。</p>
        </template>
        <template v-else>
          <button type="button" class="act-btn" @click="$router.push('/mall')">去下单享优惠</button>
          <p class="act-hint">满足门槛后结算时自动享受，与会员折扣互斥取最优。</p>
        </template>
      </aside>
    </div>
  </div>

  <div v-else class="cd-empty">
    <div class="cd-empty-ico">🎟️</div>
    <p>活动不存在或已结束</p>
    <button type="button" class="cd-back-btn" @click="$router.push('/activity')">返回活动中心</button>
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
  const hh = String(Math.floor((s % 86400) / 3600)).padStart(2, '0')
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  return d > 0 ? `${d} 天 ${hh}:${mm}:${ss}` : `${hh}:${mm}:${ss}`
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
const typeLabel = (t) => ({ coupon: '领券活动', full_reduce: '满减促销', discount: '折扣促销', flash_sale: '限时秒杀', register: '注册有礼', purchase: '消费有礼' }[t] || '平台活动')
const trimZero = (v) => Number(v) % 1 === 0 ? String(Number(v)) : String(v)
const rateText = (r) => trimZero(Number(r) * 10)
const fmt = (v) => v ? String(v).replace('T', ' ').slice(0, 16) : ''
// [label, value] 规格行
function rules(cm) {
  const rows = [
    ['活动时间', `${fmt(cm.startTime) || '即日起'} 至 ${fmt(cm.endTime) || '长期有效'}`],
    ['适用范围', cm.indName ? `${cm.indName}行业商品订单` : '全场商品订单']
  ]
  if (cm.type === 'coupon') {
    rows.push(['使用门槛', cm.threshold > 0 ? `订单满 ¥${trimZero(cm.threshold)}` : '无门槛'])
    rows.push(['优惠方式', cm.discountRate
      ? `按 ${rateText(cm.discountRate)} 折结算${cm.benefitAmount ? `，最高优惠 ¥${trimZero(cm.benefitAmount)}` : ''}`
      : `下单立减 ¥${trimZero(cm.benefitAmount)}`])
    rows.push(['领取限制', `每人限领 ${cm.perUserLimit || 1} 张${cm.totalQuota > 0 ? `，共 ${cm.totalQuota} 张` : '，不限总量'}`])
  } else if (['full_reduce', 'discount', 'flash_sale'].includes(cm.type)) {
    rows.push(['享受方式', '结算时满足门槛自动享受，无需领取'])
    rows.push(['叠加规则', '与会员等级折扣互斥，系统自动取更优'])
  } else if (cm.type === 'register') {
    rows.push(['参与奖励', `${cm.rewardPoints} 积分，参与后即时到账`])
    rows.push(['参与限制', '同一活动每位用户仅可参与一次'])
  } else if (cm.type === 'purchase') {
    rows.push(['参与奖励', `${cm.rewardPoints} 积分`])
    rows.push(['享受方式', '订单支付成功后自动参与并发放'])
  }
  return rows
}
function notes(cm) {
  if (cm.type === 'coupon') return ['数量有限，先到先得，抢完即止', '优惠券在提交订单时选用，逾期未使用自动作废', '订单取消或全额退款时，优惠券自动退回券包']
  if (cm.type === 'flash_sale') return ['限时秒杀逾时自动恢复原价，以下单结算时间为准']
  if (cm.type === 'purchase') return ['积分在订单支付成功后发放，可在会员中心查看流水']
  return []
}

onMounted(() => {
  load()
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.cd-page { max-width: 960px; margin: 0 auto; }
.cd-back { border: none; background: none; cursor: pointer; color: var(--ll-primary, #1E6EB8);
  font-size: 14px; font-weight: 500; padding: 4px 0; margin-bottom: 14px; }
.cd-back:hover { opacity: .75; }

/* ===== Hero ===== */
.cd-hero { padding: 6px 4px 22px; }
.cd-chips { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.cd-type { font-size: 11.5px; font-weight: 700; letter-spacing: .08em; color: #fff; padding: 4px 13px; border-radius: 999px;
  background: var(--ll-primary, #1E6EB8); }
.cd-scope { font-size: 12px; font-weight: 500; color: var(--ll-ink, #1d1d1f);
  background: rgba(120, 120, 128, .12); padding: 4px 12px; border-radius: 999px; }
.cd-hero h1 { margin: 0 0 10px; font-size: clamp(28px, 4.2vw, 42px); font-weight: 700;
  letter-spacing: -.028em; line-height: 1.12; color: var(--ll-ink, #1d1d1f); }
.cd-hero p { margin: 0; font-size: 16px; line-height: 1.55; color: var(--ll-muted, #6e6e73); max-width: 620px; }

.cd-cols { display: grid; grid-template-columns: 1.65fr 1fr; gap: 20px; align-items: start; }
.cd-main { min-width: 0; }

/* ===== 券面预览（真票券） ===== */
.cd-face { position: relative; display: grid; grid-template-columns: 150px 1fr; border-radius: 22px;
  background: var(--ll-card, #fff); margin-bottom: 16px;
  --notch: 13px; --cut: 138px;
  -webkit-mask: radial-gradient(var(--notch) at var(--cut) 0, transparent 98%, #000),
                radial-gradient(var(--notch) at var(--cut) 100%, transparent 98%, #000);
  -webkit-mask-composite: source-in;
  mask: radial-gradient(var(--notch) at var(--cut) 0, transparent 98%, #000),
        radial-gradient(var(--notch) at var(--cut) 100%, transparent 98%, #000);
  mask-composite: intersect;
  filter: drop-shadow(0 2px 3px rgba(0, 0, 0, .05)) drop-shadow(0 14px 32px rgba(0, 0, 0, .08)); }
.face-stub { display: flex; flex-direction: column; justify-content: center; align-items: center; gap: 8px;
  color: #fff; padding: 30px 12px; border-radius: 22px 0 0 22px;
  background: linear-gradient(160deg, #2b7fd4, #1E6EB8 55%, #16609f); }
.face-kind { font-size: 11px; letter-spacing: .22em; opacity: .8; }
.face-value { display: flex; align-items: baseline; gap: 2px; line-height: 1; }
.face-value i { font-style: normal; font-size: 17px; font-weight: 600; }
.face-value b { font-size: 44px; font-weight: 700; letter-spacing: -.04em; font-variant-numeric: tabular-nums; }
.face-th { font-size: 12px; opacity: .85; }
.face-body { position: relative; padding: 22px 24px 20px 26px; display: flex; flex-direction: column; gap: 10px;
  border-left: 2px dashed var(--ll-border, #e5e5ea); }
.face-scope { align-self: flex-start; font-size: 11.5px; font-weight: 600; color: var(--ll-primary, #1E6EB8);
  background: rgba(30, 110, 184, .08); padding: 4px 12px; border-radius: 999px; }
.face-title { font-size: 16.5px; font-weight: 700; letter-spacing: -.01em; color: var(--ll-ink, #1d1d1f); }
.face-barcode { display: block; height: 34px; max-width: 300px; border-radius: 4px; opacity: .75; margin-top: auto;
  background: repeating-linear-gradient(90deg,
    var(--ll-ink, #1d1d1f) 0 2px, transparent 2px 5px,
    var(--ll-ink, #1d1d1f) 5px 6px, transparent 6px 9px,
    var(--ll-ink, #1d1d1f) 9px 12px, transparent 12px 15px); }

/* 促销 / 激励横幅 */
.cd-banner { display: flex; align-items: baseline; gap: 12px; border-radius: 22px; padding: 28px 30px; margin-bottom: 16px;
  background: var(--ll-card, #fff); box-shadow: 0 1px 1px rgba(0,0,0,.04), 0 10px 30px rgba(0,0,0,.06); }
.cd-banner b { font-size: 46px; font-weight: 700; letter-spacing: -.04em; line-height: 1; color: var(--ll-ink, #1d1d1f); }
.cd-banner i { font-style: normal; font-size: 19px; font-weight: 600; }
.cd-banner small { font-size: 14.5px; color: var(--ll-muted, #6e6e73); }
.cd-pts { color: #c97e12; }
.cd-flashbar { display: flex; align-items: center; gap: 10px; border-radius: 14px; padding: 12px 18px; margin-bottom: 16px;
  background: linear-gradient(100deg, #fff3e2, #ffe8ee); font-size: 13.5px; font-weight: 600; color: #b45309; }
[data-theme='dark'] .cd-flashbar { background: linear-gradient(100deg, #2a2318, #2b1a1f); color: #ffb340; }
.cd-cd { font-variant-numeric: tabular-nums; font-size: 15px; }

/* ===== 规格表：Apple tech-specs ===== */
.cd-specs { border-radius: 22px; padding: 24px 26px; background: var(--ll-card, #fff);
  box-shadow: 0 1px 1px rgba(0,0,0,.04), 0 8px 28px rgba(0,0,0,.06); }
.cd-specs h2 { margin: 0 0 6px; font-size: 19px; font-weight: 700; letter-spacing: -.02em; color: var(--ll-ink, #1d1d1f); }
.spec-row { display: grid; grid-template-columns: 96px 1fr; gap: 14px; padding: 13px 0;
  border-bottom: 1px solid var(--ll-border, #e5e5ea); }
.spec-row span { font-size: 13px; color: var(--ll-muted, #6e6e73); }
.spec-row b { font-size: 14px; font-weight: 500; color: var(--ll-ink, #1d1d1f); line-height: 1.5; }
.cd-notes { margin: 14px 0 0; padding-left: 18px; color: var(--ll-muted, #6e6e73); font-size: 12.5px; line-height: 2; }

/* ===== 行动卡 ===== */
.cd-act { position: sticky; top: 84px; display: flex; flex-direction: column; gap: 12px;
  border-radius: 22px; padding: 24px;
  background: color-mix(in srgb, var(--ll-card, #fff) 78%, transparent);
  backdrop-filter: blur(24px) saturate(180%); -webkit-backdrop-filter: blur(24px) saturate(180%);
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, .06), 0 10px 30px rgba(0, 0, 0, .07); }
[data-theme='dark'] .cd-act { box-shadow: inset 0 0 0 1px rgba(255, 255, 255, .1), 0 10px 30px rgba(0, 0, 0, .35); }
.act-count { text-align: center; padding-bottom: 14px; border-bottom: 1px solid var(--ll-border, #e5e5ea); }
.act-count span { display: block; font-size: 12px; color: var(--ll-muted, #6e6e73); margin-bottom: 4px; }
.act-count b { font-size: 24px; font-weight: 700; letter-spacing: -.02em; font-variant-numeric: tabular-nums;
  color: var(--ll-ink, #1d1d1f); }
.act-btn { border: none; cursor: pointer; width: 100%; padding: 14px 0; border-radius: 980px;
  font-size: 15px; font-weight: 600; color: #fff; background: var(--ll-primary, #1E6EB8);
  transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s, opacity .25s; }
.act-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 8px 22px rgba(30, 110, 184, .35); }
.act-btn:active:not(:disabled) { transform: scale(.98); }
.act-btn:disabled { cursor: not-allowed; opacity: .4; }
.act-btn.ghost { background: transparent; color: var(--ll-primary, #1E6EB8);
  box-shadow: inset 0 0 0 1px var(--ll-border-strong, #d2d2d7); }
.act-hint { margin: 0; font-size: 12.5px; line-height: 1.65; color: var(--ll-muted, #6e6e73); text-align: center; }

/* ===== 空态 ===== */
.cd-empty { max-width: 360px; margin: 90px auto; text-align: center; }
.cd-empty-ico { font-size: 50px; margin-bottom: 12px; }
.cd-empty p { margin: 0 0 18px; font-size: 15px; color: var(--ll-muted, #6e6e73); }
.cd-back-btn { border: none; cursor: pointer; padding: 10px 22px; border-radius: 980px; font-size: 14px;
  font-weight: 500; background: var(--ll-primary, #1E6EB8); color: #fff; }

/* ===== 响应式 ===== */
@media (max-width: 820px) {
  .cd-cols { grid-template-columns: 1fr; }
  .cd-act { position: static; }
}
@media (max-width: 560px) {
  .cd-face { grid-template-columns: 112px 1fr; --cut: 100px; }
  .face-value b { font-size: 34px; }
  .spec-row { grid-template-columns: 84px 1fr; }
}
</style>
