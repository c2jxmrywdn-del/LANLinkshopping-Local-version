<template>
  <div class="my-page">
    <div class="head">
      <div>
        <div class="eyebrow">CAMPAIGNS / MY</div>
        <h1>我的活动</h1>
        <p>我的优惠券与激励参与记录，一目了然。</p>
      </div>
      <a-button @click="$router.push('/activity')">去活动中心</a-button>
    </div>

    <div class="seg" role="tablist">
      <button type="button" role="tab" class="seg-item" :class="{ on: tab === 'coupon' }" @click="tab = 'coupon'">
        🎟️ 我的券包 <i v-if="usableCount">{{ usableCount }}</i>
      </button>
      <button type="button" role="tab" class="seg-item" :class="{ on: tab === 'join' }" @click="tab = 'join'">
        🎁 参与记录
      </button>
    </div>

    <!-- 券包 -->
    <div v-if="tab === 'coupon'" class="coupon-zone">
      <div class="coupon-filter">
        <button v-for="f in COUPON_FILTERS" :key="f.key" type="button"
                class="fchip" :class="{ on: couponFilter === f.key }" @click="couponFilter = f.key">
          {{ f.label }} <i v-if="counts[f.key]">{{ counts[f.key] }}</i>
        </button>
      </div>
      <div class="coupon-grid" v-if="filteredCoupons.length">
        <div v-for="cp in filteredCoupons" :key="cp.couponId" class="coupon" :class="cp.state">
          <div class="cp-stub">
            <div class="cp-val">
              <template v-if="cp.discountRate">{{ rateText(cp.discountRate) }}<small>折</small></template>
              <template v-else-if="cp.benefitAmount"><small>¥</small>{{ trimZero(cp.benefitAmount) }}</template>
            </div>
            <div class="cp-kind">{{ cp.discountRate ? '折扣券' : '满减券' }}</div>
          </div>
          <div class="cp-body">
            <div class="cp-top">
              <span class="cp-scope">{{ cp.indName ? cp.indName + '专享' : '全场通用' }}</span>
              <span class="cp-state">{{ stateText(cp.state) }}</span>
            </div>
            <h4>{{ cp.title }}</h4>
            <div class="cp-rule">{{ cp.threshold > 0 ? `满 ¥${trimZero(cp.threshold)} 可用` : '无门槛' }}
              <template v-if="cp.discountRate && cp.benefitAmount">· 最高减 ¥{{ trimZero(cp.benefitAmount) }}</template>
            </div>
            <div class="cp-foot">
              <span class="cp-valid">{{ cp.state === 'used' ? `订单 ${cp.orderNo || ''}` : `有效期至 ${fmtDate(cp.endTime)}` }}</span>
              <button v-if="cp.state === 'usable'" type="button" class="cp-use" @click="$router.push('/mall')">去使用</button>
              <a v-else-if="cp.state === 'used' && cp.orderNo" class="cp-link" @click="$router.push('/orders/' + cp.orderNo)">查看订单</a>
            </div>
          </div>
        </div>
      </div>
      <a-empty v-else-if="!loading" description="这个状态下还没有优惠券">
        <a-button type="primary" @click="$router.push('/activity?tab=coupon')">去领券</a-button>
      </a-empty>
    </div>

    <!-- 参与记录 -->
    <div v-else class="join-zone">
      <div class="join-list" v-if="parts.length">
        <div v-for="(p, i) in parts" :key="i" class="join-item">
          <div class="join-ico">{{ p.type === 'purchase' ? '🧾' : '🎁' }}</div>
          <div class="join-main">
            <b @click="$router.push('/activity/' + p.campaignId)">{{ p.title }}</b>
            <span>{{ p.type === 'purchase' ? '消费有礼 · 支付后自动参与' : '注册有礼 · 主动参与' }} · {{ fmt(p.joinTime) }}</span>
          </div>
          <a-tag color="gold" class="join-pts">+{{ p.bonusPoints || 0 }} 积分</a-tag>
        </div>
      </div>
      <a-empty v-else-if="!loading" description="还没有参与过激励活动">
        <a-button type="primary" @click="$router.push('/activity')">发现活动</a-button>
      </a-empty>
    </div>

    <a-spin :spinning="loading" style="width:100%" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { campaignApi } from '../api'

const tab = ref('coupon')
const couponFilter = ref('usable')
const coupons = ref([])
const parts = ref([])
const loading = ref(false)

const COUPON_FILTERS = [
  { key: 'usable', label: '可用' },
  { key: 'used', label: '已使用' },
  { key: 'expired', label: '已过期' }
]
const counts = computed(() => {
  const acc = { usable: 0, used: 0, expired: 0 }
  coupons.value.forEach(c => { if (acc[c.state] !== undefined) acc[c.state]++ })
  return acc
})
const usableCount = computed(() => counts.value.usable)
const filteredCoupons = computed(() => coupons.value.filter(c => c.state === couponFilter.value))

const stateText = (s) => ({ usable: '可使用', used: '已使用', expired: '已过期' }[s] || s)
const trimZero = (v) => Number(v) % 1 === 0 ? String(Number(v)) : String(v)
const rateText = (r) => trimZero(Number(r) * 10)
const fmt = (v) => v ? String(v).replace('T', ' ').slice(0, 16) : ''
const fmtDate = (v) => v ? String(v).slice(0, 10) : ''

onMounted(async () => {
  loading.value = true
  try {
    const [cs, ps] = await Promise.all([campaignApi.myCoupons(), campaignApi.myParticipations()])
    coupons.value = cs || []
    parts.value = ps || []
    // 默认落在有内容的筛选上（可用为空时切到已使用/已过期）
    if (!counts.value.usable && counts.value.used) couponFilter.value = 'used'
    else if (!counts.value.usable && counts.value.expired) couponFilter.value = 'expired'
  } catch (e) { /* 拦截器已提示 */ } finally { loading.value = false }
})
</script>

<style scoped>
.my-page { max-width: 960px; margin: 0 auto; }
.head { display: flex; justify-content: space-between; gap: 18px; align-items: flex-start; margin-bottom: 18px; }
.eyebrow { font-size: 11px; letter-spacing: .18em; color: var(--ll-primary, #1E6EB8); font-weight: 600; }
.head h1 { margin: 5px 0; font-size: 28px; letter-spacing: -.02em; color: var(--ll-ink, #0F172A); }
.head p { margin: 0; color: var(--ll-muted, #64748B); }

.seg { display: inline-flex; gap: 4px; padding: 4px; border-radius: 14px; margin-bottom: 16px;
  background: var(--ll-soft, rgba(15,23,42,.05)); }
.seg-item { border: none; background: transparent; cursor: pointer; padding: 8px 18px; border-radius: 10px;
  font-size: 13.5px; color: var(--ll-muted, #64748B); transition: all .25s var(--ll-ease-out, ease); }
.seg-item.on { background: var(--ll-card, #fff); color: var(--ll-ink, #0F172A); font-weight: 600;
  box-shadow: 0 1px 4px rgba(16,24,40,.1); }
.seg-item i, .fchip i { font-style: normal; font-size: 11px; background: var(--ll-primary, #1E6EB8); color: #fff;
  border-radius: 999px; padding: 1px 7px; margin-left: 6px; }

.coupon-filter { display: flex; gap: 8px; margin-bottom: 14px; }
.fchip { border: none; cursor: pointer; font-size: 12.5px; padding: 5px 13px; border-radius: 999px;
  background: var(--ll-soft, rgba(15,23,42,.05)); color: var(--ll-muted, #64748B); transition: all .2s; }
.fchip.on { background: var(--ll-primary, #1E6EB8); color: #fff; font-weight: 600; }
.fchip.on i { background: rgba(255,255,255,.25); }

.coupon-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 12px; }
.coupon { display: grid; grid-template-columns: 104px 1fr; border-radius: 18px; overflow: hidden;
  background: var(--ll-card, #fff); box-shadow: var(--ll-shadow-sm, 0 1px 2px rgba(16,24,40,.06)); position: relative; }
.cp-stub { display: flex; flex-direction: column; justify-content: center; align-items: center; color: #fff;
  background: linear-gradient(135deg, #1E6EB8, #38bdf8); padding: 16px 8px; }
.coupon.used .cp-stub, .coupon.expired .cp-stub { background: linear-gradient(135deg, #94a3b8, #cbd5e1); }
.cp-val { font-size: 26px; font-weight: 700; letter-spacing: -.03em; line-height: 1; }
.cp-val small { font-size: 13px; }
.cp-kind { margin-top: 6px; font-size: 10.5px; opacity: .85; letter-spacing: .12em; }
.cp-body { padding: 13px 16px 12px; display: flex; flex-direction: column; position: relative; }
.cp-body::before { content: ''; position: absolute; left: -6px; top: 50%; width: 12px; height: 12px;
  border-radius: 50%; background: var(--ll-page, #f5f6f8); transform: translateY(-50%); }
.cp-top { display: flex; justify-content: space-between; gap: 8px; margin-bottom: 7px; }
.cp-scope { font-size: 11px; color: var(--ll-primary, #1E6EB8); background: rgba(30,110,184,.08);
  padding: 2px 8px; border-radius: 999px; }
.coupon.used .cp-scope, .coupon.expired .cp-scope { color: var(--ll-muted, #64748B); background: var(--ll-soft, rgba(15,23,42,.05)); }
.cp-state { font-size: 11px; color: var(--ll-muted, #64748B); }
.cp-body h4 { margin: 0 0 5px; font-size: 14.5px; color: var(--ll-ink, #0F172A);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.coupon.used h4, .coupon.expired h4 { text-decoration: line-through; opacity: .55; }
.cp-rule { font-size: 12.5px; color: var(--ll-muted, #64748B); }
.cp-foot { margin-top: auto; padding-top: 12px; display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.cp-valid { font-size: 11.5px; color: var(--ll-muted, #64748B); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cp-use { border: none; cursor: pointer; background: linear-gradient(135deg, #1E6EB8, #38bdf8); color: #fff;
  font-size: 12.5px; font-weight: 600; padding: 6px 14px; border-radius: 999px; transition: transform .2s, box-shadow .2s; }
.cp-use:hover { transform: translateY(-1px); box-shadow: 0 6px 16px rgba(30,110,184,.3); }
.cp-link { font-size: 12.5px; color: var(--ll-primary, #1E6EB8); cursor: pointer; }

.join-list { display: flex; flex-direction: column; gap: 10px; }
.join-item { display: flex; align-items: center; gap: 14px; background: var(--ll-card, #fff);
  border-radius: 16px; padding: 15px 18px; box-shadow: var(--ll-shadow-sm, 0 1px 2px rgba(16,24,40,.06)); }
.join-ico { font-size: 22px; }
.join-main { flex: 1; min-width: 0; }
.join-main b { display: block; font-size: 14.5px; color: var(--ll-ink, #0F172A); cursor: pointer;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.join-main b:hover { color: var(--ll-primary, #1E6EB8); }
.join-main span { font-size: 12px; color: var(--ll-muted, #64748B); }
.join-pts { margin: 0; }

@media (max-width: 700px) {
  .head { flex-direction: column; }
  .coupon-grid { grid-template-columns: 1fr; }
}
@media (max-width: 480px) {
  .coupon { grid-template-columns: 92px 1fr; }
  .cp-val { font-size: 22px; }
  .join-item { padding: 13px 14px; }
}
</style>
