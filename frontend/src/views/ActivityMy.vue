<template>
  <div class="my-page">
    <header class="my-hero">
      <div>
        <h1>我的券包<span class="my-dot">。</span></h1>
        <p>优惠券与激励记录，像卡包一样一目了然。</p>
      </div>
      <button type="button" class="my-pill" @click="$router.push('/activity')">去领券</button>
    </header>

    <div class="my-seg" role="tablist">
      <button type="button" role="tab" class="my-seg-item" :class="{ on: tab === 'coupon' }" @click="tab = 'coupon'">
        券包 <i v-if="usableCount">{{ usableCount }}</i>
      </button>
      <button type="button" role="tab" class="my-seg-item" :class="{ on: tab === 'join' }" @click="tab = 'join'">
        激励记录
      </button>
    </div>

    <!-- ============ 券包：Apple Wallet pass 卡 ============ -->
    <div v-if="tab === 'coupon'" class="wallet-zone">
      <div class="wallet-filter">
        <button v-for="f in COUPON_FILTERS" :key="f.key" type="button"
                class="wf-chip" :class="{ on: couponFilter === f.key }" @click="couponFilter = f.key">
          {{ f.label }}<i v-if="counts[f.key]">{{ counts[f.key] }}</i>
        </button>
      </div>

      <div class="wallet-list" v-if="filteredCoupons.length">
        <div v-for="(cp, idx) in filteredCoupons" :key="cp.couponId"
             class="pass" :class="[cp.state, accent(cp.campaignId)]" :style="{ '--stack': idx }">
          <!-- 色带头 -->
          <div class="pass-band">
            <div class="pass-band-main">
              <span class="pass-org">{{ cp.indName ? cp.indName + '行业' : 'LANLinkshopping' }}</span>
              <span class="pass-state">{{ stateText(cp.state) }}</span>
            </div>
            <h4 class="pass-title">{{ cp.title }}</h4>
          </div>
          <!-- 白色主体 -->
          <div class="pass-body">
            <div class="pass-value">
              <template v-if="cp.discountRate"><b>{{ rateText(cp.discountRate) }}</b><i>折</i></template>
              <template v-else><i>¥</i><b>{{ trimZero(cp.benefitAmount) }}</b></template>
              <span class="pass-th">{{ cp.threshold > 0 ? `满 ¥${trimZero(cp.threshold)} 可用` : '无门槛' }}</span>
            </div>
            <div class="pass-meta">
              <div><span>有效期至</span><b>{{ fmtDate(cp.endTime) || '长期' }}</b></div>
              <div v-if="cp.state === 'used'"><span>核销订单</span><b class="pass-link" @click="$router.push('/orders/' + cp.orderNo)">{{ cp.orderNo }}</b></div>
              <div v-else><span>领取时间</span><b>{{ fmt(cp.receiveTime) }}</b></div>
            </div>
            <!-- 伪条码 + 行动 -->
            <div class="pass-foot">
              <i class="pass-barcode" :class="{ dead: cp.state !== 'usable' }" aria-hidden="true"></i>
              <button v-if="cp.state === 'usable'" type="button" class="pass-btn" @click="$router.push('/mall')">去使用</button>
              <span v-else class="pass-dead">{{ cp.state === 'used' ? '已核销' : '已过期' }}</span>
            </div>
          </div>
        </div>
      </div>
      <div v-else-if="!loading" class="my-empty">
        <div class="my-empty-ico">🎫</div>
        <p>这个分类下还没有优惠券</p>
        <button type="button" class="my-pill" @click="$router.push('/activity')">去活动中心领券</button>
      </div>
    </div>

    <!-- ============ 激励记录 ============ -->
    <div v-else class="join-zone">
      <div class="join-list" v-if="parts.length">
        <div v-for="(p, i) in parts" :key="i" class="join-item" @click="$router.push('/activity/' + p.campaignId)">
          <div class="join-ico">{{ p.type === 'purchase' ? '🧾' : '🎁' }}</div>
          <div class="join-main">
            <b>{{ p.title }}</b>
            <span>{{ p.type === 'purchase' ? '消费有礼 · 支付后自动参与' : '注册有礼 · 主动参与' }} · {{ fmt(p.joinTime) }}</span>
          </div>
          <span class="join-pts">+{{ p.bonusPoints || 0 }}<i>积分</i></span>
        </div>
      </div>
      <div v-else-if="!loading" class="my-empty">
        <div class="my-empty-ico">🎁</div>
        <p>还没有参与过激励活动</p>
        <button type="button" class="my-pill" @click="$router.push('/activity')">发现活动</button>
      </div>
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
  { key: 'usable', label: '可使用' },
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
// 按活动 id 稳定分配 pass 主题色
const PASS_ACCENTS = ['blue', 'red', 'gold', 'green', 'purple']
const accent = (id) => 'ac-' + PASS_ACCENTS[Number(id) % PASS_ACCENTS.length]

onMounted(async () => {
  loading.value = true
  try {
    const [cs, ps] = await Promise.all([campaignApi.myCoupons(), campaignApi.myParticipations()])
    coupons.value = cs || []
    parts.value = ps || []
    if (!counts.value.usable && counts.value.used) couponFilter.value = 'used'
    else if (!counts.value.usable && counts.value.expired) couponFilter.value = 'expired'
  } catch (e) { /* 拦截器已提示 */ } finally { loading.value = false }
})
</script>

<style scoped>
.my-page { max-width: 880px; margin: 0 auto; }

/* ===== Hero ===== */
.my-hero { display: flex; justify-content: space-between; align-items: flex-end; gap: 20px; padding: 12px 4px 22px; }
.my-hero h1 { margin: 0; font-size: clamp(28px, 3.8vw, 38px); font-weight: 700; letter-spacing: -.028em;
  line-height: 1.1; color: var(--ll-ink, #1d1d1f); }
.my-dot { color: var(--ll-primary, #1E6EB8); }
.my-hero p { margin: 10px 0 0; font-size: 15.5px; color: var(--ll-muted, #6e6e73); }
.my-pill { border: none; cursor: pointer; padding: 10px 20px; border-radius: 980px; font-size: 14px; font-weight: 500;
  background: var(--ll-primary, #1E6EB8); color: #fff; flex-shrink: 0;
  transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s; }
.my-pill:hover { transform: translateY(-1px); box-shadow: 0 6px 18px rgba(30, 110, 184, .3); }
.my-pill:active { transform: scale(.97); }

/* ===== iOS 分段控件 ===== */
.my-seg { display: inline-flex; gap: 2px; padding: 2px; border-radius: 12px; margin-bottom: 18px;
  background: rgba(120, 120, 128, .14);
  backdrop-filter: blur(20px) saturate(180%); -webkit-backdrop-filter: blur(20px) saturate(180%); }
.my-seg-item { border: none; background: transparent; cursor: pointer; padding: 7px 18px; border-radius: 10px;
  font-size: 13.5px; font-weight: 500; color: var(--ll-ink, #1d1d1f); opacity: .72;
  transition: all .25s var(--ll-ease-out, ease); }
.my-seg-item i { font-style: normal; font-size: 11px; background: #e0354b; color: #fff;
  border-radius: 999px; padding: 1px 6px; margin-left: 5px; vertical-align: 1px; }
.my-seg-item.on { background: var(--ll-card, #fff); opacity: 1; font-weight: 600;
  box-shadow: 0 3px 8px rgba(0, 0, 0, .12), 0 1px 1px rgba(0, 0, 0, .16); }
.my-seg-item:not(.on):hover { opacity: 1; }

/* ===== 筛选 chips ===== */
.wallet-filter { display: flex; gap: 8px; margin-bottom: 16px; }
.wf-chip { border: none; cursor: pointer; font-size: 12.5px; font-weight: 500; padding: 6px 14px; border-radius: 980px;
  background: rgba(120, 120, 128, .12); color: var(--ll-ink, #1d1d1f); opacity: .75; transition: all .25s; }
.wf-chip i { font-style: normal; margin-left: 5px; font-variant-numeric: tabular-nums; }
.wf-chip.on { background: var(--ll-primary, #1E6EB8); color: #fff; opacity: 1; font-weight: 600; }

/* ============ Wallet pass 卡 ============ */
.wallet-list { display: flex; flex-direction: column; gap: 14px; }
.pass { border-radius: 18px; overflow: hidden; background: var(--ll-card, #fff);
  box-shadow: 0 1px 1px rgba(0, 0, 0, .04), 0 8px 24px rgba(0, 0, 0, .07);
  transition: transform .3s var(--ll-ease-out, ease), box-shadow .3s; }
.pass:hover { transform: translateY(-3px); box-shadow: 0 2px 2px rgba(0,0,0,.04), 0 14px 34px rgba(0,0,0,.11); }
/* 主题色带 */
.pass-band { padding: 16px 20px 14px; color: #fff; }
.ac-blue .pass-band { background: linear-gradient(135deg, #2b7fd4, #1E6EB8 60%); }
.ac-red .pass-band { background: linear-gradient(135deg, #f0566a, #e0354b 60%); }
.ac-gold .pass-band { background: linear-gradient(135deg, #e6a23c, #c97e12 60%); }
.ac-green .pass-band { background: linear-gradient(135deg, #22a97a, #047857 60%); }
.ac-purple .pass-band { background: linear-gradient(135deg, #8b5cf6, #6d28d9 60%); }
.pass.used .pass-band, .pass.expired .pass-band { background: linear-gradient(135deg, #a5a5aa, #8e8e93 60%); }
.pass-band-main { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.pass-org { font-size: 11px; font-weight: 600; letter-spacing: .1em; opacity: .85; text-transform: uppercase; }
.pass-state { font-size: 11px; font-weight: 600; border: 1px solid rgba(255, 255, 255, .55);
  border-radius: 999px; padding: 2px 10px; }
.pass-title { margin: 0; font-size: 16px; font-weight: 700; letter-spacing: -.01em; }
/* 白色主体 */
.pass-body { padding: 16px 20px 16px; }
.pass-value { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.pass-value b { font-size: 34px; font-weight: 700; letter-spacing: -.04em; line-height: 1;
  font-variant-numeric: tabular-nums; color: var(--ll-ink, #1d1d1f); }
.pass.used .pass-value b, .pass.expired .pass-value b { text-decoration: line-through; opacity: .45; }
.pass-value i { font-style: normal; font-size: 15px; font-weight: 600; color: var(--ll-ink, #1d1d1f); }
.pass-value i:first-child { font-size: 17px; }
.pass-th { font-size: 12.5px; color: var(--ll-muted, #6e6e73); }
/* meta 行：Apple tech-specs 式 hairline */
.pass-meta { display: flex; gap: 28px; padding: 10px 0 12px; border-top: 1px solid var(--ll-border, #e5e5ea); flex-wrap: wrap; }
.pass-meta > div > span { display: block; font-size: 11px; color: var(--ll-muted, #6e6e73); margin-bottom: 2px; }
.pass-meta > div > b { font-size: 13px; font-weight: 500; color: var(--ll-ink, #1d1d1f); }
.pass-link { cursor: pointer; text-decoration: underline; text-underline-offset: 2px; }
/* 伪条码 */
.pass-foot { display: flex; justify-content: space-between; align-items: center; gap: 14px; }
.pass-barcode { flex: 1; max-width: 240px; height: 30px; border-radius: 4px; opacity: .8;
  background: repeating-linear-gradient(90deg,
    var(--ll-ink, #1d1d1f) 0 2px, transparent 2px 5px,
    var(--ll-ink, #1d1d1f) 5px 6px, transparent 6px 9px,
    var(--ll-ink, #1d1d1f) 9px 12px, transparent 12px 15px); }
.pass-barcode.dead { opacity: .18; }
.pass-btn { border: none; cursor: pointer; font-size: 13.5px; font-weight: 600; color: #fff;
  padding: 9px 22px; border-radius: 980px; background: var(--ll-primary, #1E6EB8);
  transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s; }
.pass-btn:hover { transform: translateY(-1px); box-shadow: 0 6px 16px rgba(30, 110, 184, .3); }
.pass-btn:active { transform: scale(.96); }
.pass-dead { font-size: 12.5px; color: var(--ll-muted, #6e6e73); }

/* ============ 激励记录 ============ */
.join-list { display: flex; flex-direction: column; gap: 10px; }
.join-item { display: flex; align-items: center; gap: 14px; padding: 15px 18px; cursor: pointer;
  background: var(--ll-card, #fff); border-radius: 16px;
  box-shadow: 0 1px 1px rgba(0, 0, 0, .04), 0 6px 20px rgba(0, 0, 0, .05);
  transition: transform .3s var(--ll-ease-out, ease), box-shadow .3s; }
.join-item:hover { transform: translateY(-2px); box-shadow: 0 2px 2px rgba(0,0,0,.04), 0 12px 28px rgba(0,0,0,.09); }
.join-ico { width: 42px; height: 42px; display: grid; place-items: center; font-size: 20px; border-radius: 13px;
  background: rgba(120, 120, 128, .1); }
.join-main { flex: 1; min-width: 0; }
.join-main b { display: block; font-size: 14.5px; font-weight: 600; color: var(--ll-ink, #1d1d1f);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.join-main span { font-size: 12px; color: var(--ll-muted, #6e6e73); }
.join-pts { font-size: 17px; font-weight: 700; color: #c97e12; font-variant-numeric: tabular-nums; }
.join-pts i { font-style: normal; font-size: 11px; font-weight: 600; color: var(--ll-muted, #6e6e73); margin-left: 2px; }

/* ===== 空态 ===== */
.my-empty { text-align: center; padding: 56px 0 72px; }
.my-empty-ico { font-size: 46px; margin-bottom: 12px; }
.my-empty p { margin: 0 0 18px; font-size: 14.5px; color: var(--ll-muted, #6e6e73); }

/* ===== 响应式 ===== */
@media (max-width: 640px) {
  .my-hero { flex-direction: column; align-items: flex-start; }
  .my-seg { width: 100%; display: flex; }
  .my-seg-item { flex: 1; padding: 7px 0; }
  .pass-meta { gap: 18px; }
  .pass-foot { flex-direction: column; align-items: stretch; }
  .pass-barcode { max-width: none; }
  .pass-btn { width: 100%; }
}
</style>
