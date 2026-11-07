<template>
  <div class="cc-page">
    <!-- Apple 营销页式 Hero（组件系沿用活动中心） -->
    <header class="cc-hero">
      <DotPattern class="cc-dots" :width="18" :height="18" :cr="1.2" />
      <div class="cc-hero-copy">
        <h1>领券中心<span class="cc-dot">。</span></h1>
        <p>券池一站聚合，一键批量入账，<br class="cc-br" />结算时自动抵扣货款。</p>
      </div>
      <div class="cc-hero-side">
        <div class="cc-stats">
          <div class="cc-stat"><b><NumberTicker :value="claimableCount" /></b><span>可领券</span></div>
          <div class="cc-stat"><b><NumberTicker :value="coupons.length" /></b><span>券池总量</span></div>
        </div>
        <div class="cc-hero-links">
          <ShimmerButton class="cc-cta" background="var(--ll-primary, #1E6EB8)"
                         shimmer-color="rgba(255,255,255,.9)" @click="$router.push('/activity/my')">我的券包</ShimmerButton>
          <button type="button" class="cc-pill plain" @click="$router.push('/activity')">活动中心</button>
        </div>
      </div>
    </header>

    <!-- 工具行：iOS 分段门槛筛选 + 一键批量领取 -->
    <div class="cc-toolbar">
      <div class="cc-seg" role="tablist">
        <button v-for="f in FILTERS" :key="f.key" type="button" role="tab"
                class="cc-seg-item" :class="{ on: filter === f.key }" @click="filter = f.key">
          {{ f.label }}
        </button>
      </div>
      <button type="button" class="cc-pill" :disabled="claiming || !claimableCount" @click="claimAll">
        {{ claiming ? '领取中…' : `一键领取全部（${claimableCount}）` }}
      </button>
    </div>

    <a-spin :spinning="loading">
      <div class="cc-grid" v-if="filteredCoupons.length">
        <article v-for="c in filteredCoupons" :key="c.campaignId" class="tk" :class="accent(c.accent)" @click="goDetail(c)">
          <div class="tk-stub">
            <span class="tk-kind">{{ c.discountRate ? '折扣券' : '满减券' }}</span>
            <div class="tk-value">
              <template v-if="c.discountRate"><b>{{ rateText(c.discountRate) }}</b><i>折</i></template>
              <template v-else><i>¥</i><b>{{ trimZero(c.benefitAmount) }}</b></template>
            </div>
            <span class="tk-th">{{ c.threshold > 0 ? `满${trimZero(c.threshold)}可用` : '无门槛' }}</span>
          </div>
          <div class="tk-body">
            <div class="tk-row1">
              <span class="tk-scope">{{ c.scope === 'prod' && c.merName ? '店铺 · ' + c.merName : (c.indName ? c.indName + '专享' : '全场通用') }}</span>
              <span class="tk-remain" v-if="c.remainQuota >= 0">余 {{ c.remainQuota }}</span>
            </div>
            <h3>{{ c.title }}</h3>
            <div class="tk-valid">{{ validText(c) }}</div>
            <div class="tk-actions">
              <button type="button" class="tk-btn" :disabled="!c.canClaim || !!actingId || claiming" @click.stop="claim(c)">
                {{ actingId === c.campaignId ? '领取中…' : (c.limitReached ? '已领取' : (c.remainQuota === 0 ? '已抢完' : '立即领取')) }}
              </button>
              <span class="tk-stamp" v-if="c.limitReached">已领取</span>
            </div>
          </div>
        </article>
      </div>
      <div v-else-if="!loading" class="cc-empty">
        <div class="cc-empty-ico"><Ticket :size="48" class="text-slate-300 dark:text-slate-600 inline-block" /></div>
        <p>暂无可领取的优惠券，过段时间再来看看</p>
        <button type="button" class="cc-pill" @click="$router.push('/mall')">先去商城逛逛</button>
      </div>
    </a-spin>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { Ticket } from 'lucide-vue-next'
import { campaignApi } from '../api'

import DotPattern from '../components/ui/DotPattern.vue'
import NumberTicker from '../components/ui/NumberTicker.vue'
import ShimmerButton from '../components/ui/ShimmerButton.vue'

const router = useRouter()

// 门槛筛选：全部 / 无门槛 / 需满额（纯前端过滤，不改后端口径）
const FILTERS = [
  { key: 'all', label: '全部' },
  { key: 'none', label: '无门槛' },
  { key: 'threshold', label: '需满额' }
]
const filter = ref('all')
const coupons = ref([])
const loading = ref(false)

const claimableCount = computed(() => coupons.value.filter(c => c.canClaim && !c.limitReached && c.remainQuota !== 0).length)
const filteredCoupons = computed(() => {
  if (filter.value === 'none') return coupons.value.filter(c => !(Number(c.threshold) > 0))
  if (filter.value === 'threshold') return coupons.value.filter(c => Number(c.threshold) > 0)
  return coupons.value
})

const logged = () => !!localStorage.getItem('ll_user')
function requireLogin() {
  message.info('请先登录')
  router.push({ path: '/login', query: { redirect: '/coupons' } })
}

// 竞态守卫：与活动中心同款——仅允许最新一次请求写入，过期响应直接丢弃
let loadSeq = 0
async function load() {
  const seq = ++loadSeq
  loading.value = true
  try {
    const list = await campaignApi.couponCenter()
    if (seq !== loadSeq) return
    coupons.value = list || []
  } catch (e) {
    if (seq !== loadSeq) return
    coupons.value = []
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

// 领取请求中的活动 ID：防抖守卫（同活动中心 actingId 口径），批量领取期间单张按钮一并禁用
const actingId = ref(null)
const claiming = ref(false)

async function claim(c) {
  if (!logged()) return requireLogin()
  if (actingId.value || claiming.value) return
  actingId.value = c.campaignId
  try {
    await campaignApi.claim(c.campaignId)
    message.success('领取成功，结算时可用')
    load()
  } catch (e) {
    /* 拦截器已提示 */
  } finally { actingId.value = null }
}

// 一键领取全部：批量走后端 batch-claim（逐券原子领取，部分成功可接受），逐券结果计数回执
async function claimAll() {
  if (!logged()) return requireLogin()
  const ids = coupons.value
    .filter(c => c.canClaim && !c.limitReached && c.remainQuota !== 0)
    .map(c => c.campaignId)
  if (!ids.length || claiming.value) return
  claiming.value = true
  try {
    const raw = await campaignApi.batchClaim(ids)
    const results = Array.isArray(raw) ? raw : []
    const ok = results.filter(r => r && (r.ok === true || r.ok === 1)).length
    const fail = results.length - ok
    if (fail > 0) message.info(`成功领取 ${ok} 张，${fail} 张未成功（配额或资格限制）`)
    else message.success(`已领取 ${ok} 张优惠券，结算时可用`)
    load()
  } catch (e) {
    /* 拦截器已提示 */
  } finally { claiming.value = false }
}

function goDetail(c) {
  router.push('/activity/' + c.campaignId)
}

// ===== 展示口径（与活动中心券票卡完全一致） =====
const ACCENTS = ['blue', 'red', 'gold', 'green', 'purple']
const accent = (a) => 'ac-' + (ACCENTS.includes(a) ? a : 'blue')
const trimZero = (v) => { const n = Math.round(Number(v) * 100) / 100; return String(n) }
const rateText = (r) => trimZero(Number(r) * 10)
const validText = (c) => c.endTime ? `有效期至 ${fmtDate(c.endTime)}` : '长期有效'
const fmtDate = (v) => v ? String(v).slice(0, 10) : ''

onMounted(load)
</script>

<style scoped>
.cc-page {
  max-width: 1120px;
  margin: 0 auto;
}

/* ===== Hero：与活动中心同款排版 ===== */
.cc-hero {
  position: relative;
  overflow: hidden;
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 24px;
  padding: 20px 4px 26px;
}
.cc-dots { color: rgba(30, 110, 184, .18); }
.cc-hero-copy, .cc-hero-side { position: relative; }
.cc-hero h1 {
  margin: 0;
  font-size: clamp(30px, 4.4vw, 46px);
  font-weight: 700;
  letter-spacing: -.028em;
  line-height: 1.08;
  color: var(--ll-ink, #1d1d1f);
}
.cc-dot { color: var(--ll-primary, #1E6EB8); }
.cc-hero p { margin: 12px 0 0; font-size: 17px; line-height: 1.5; color: var(--ll-muted, #6e6e73); }
.cc-br { display: none; }
.cc-hero-side { display: flex; align-items: center; gap: 22px; flex-shrink: 0; }
.cc-stats { display: flex; gap: 20px; }
.cc-stat { text-align: right; }
.cc-stat b {
  display: block;
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -.03em;
  font-variant-numeric: tabular-nums;
  color: var(--ll-ink, #1d1d1f);
}
.cc-stat span { font-size: 12px; color: var(--ll-muted, #6e6e73); }
.cc-hero-links { display: flex; gap: 10px; }
.cc-cta { padding: 10px 22px; font-size: 14px; }

/* 胶囊按钮：iOS 圆钮 */
.cc-pill {
  border: none;
  cursor: pointer;
  padding: 10px 20px;
  border-radius: 980px;
  font-size: 14px;
  font-weight: 500;
  background: var(--ll-primary, #1E6EB8);
  color: #fff;
  transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s, background-color .25s, opacity .25s;
}
.cc-pill:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 6px 18px rgba(30, 110, 184, .3); }
.cc-pill:active:not(:disabled) { transform: scale(.97); }
.cc-pill:disabled { cursor: not-allowed; opacity: .4; }
.cc-pill.plain {
  background: transparent;
  color: var(--ll-primary, #1E6EB8);
  box-shadow: inset 0 0 0 1px var(--ll-border-strong, #d2d2d7);
}

/* ===== 工具行：分段筛选 + 一键领取 ===== */
.cc-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin-bottom: 22px;
}
.cc-seg {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border-radius: 12px;
  background: rgba(120, 120, 128, .14);
  backdrop-filter: blur(20px) saturate(180%);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
}
.cc-seg-item {
  border: none;
  background: transparent;
  cursor: pointer;
  padding: 7px 18px;
  border-radius: 10px;
  font-size: 13.5px;
  font-weight: 500;
  color: var(--ll-ink, #1d1d1f);
  opacity: .72;
  transition: all .25s var(--ll-ease-out, ease);
}
.cc-seg-item.on {
  background: var(--ll-card, #fff);
  opacity: 1;
  font-weight: 600;
  box-shadow: 0 3px 8px rgba(0, 0, 0, .12), 0 1px 1px rgba(0, 0, 0, .16);
}
.cc-seg-item:not(.on):hover { opacity: 1; }

.cc-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 18px; }
.cc-grid > article { min-width: 0; }
.tk-body { min-width: 0; }

/* ============ 券票：mask 打孔 + drop-shadow 随形（活动中心券票卡组件系） ============ */
.tk {
  position: relative;
  display: grid;
  grid-template-columns: 116px 1fr;
  border-radius: 20px;
  background: var(--ll-card, #fff);
  cursor: pointer;
  --notch: 12px;
  --cut: 104px;
  -webkit-mask: radial-gradient(var(--notch) at var(--cut) 0, transparent 98%, #000) ,
                radial-gradient(var(--notch) at var(--cut) 100%, transparent 98%, #000);
  -webkit-mask-composite: source-in;
  mask: radial-gradient(var(--notch) at var(--cut) 0, transparent 98%, #000),
        radial-gradient(var(--notch) at var(--cut) 100%, transparent 98%, #000);
  mask-composite: intersect;
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, .05)) drop-shadow(0 10px 24px rgba(0, 0, 0, .07));
  transition: transform .3s var(--ll-ease-out, ease), filter .3s;
}
.tk:hover { transform: translateY(-4px); filter: drop-shadow(0 2px 4px rgba(0,0,0,.06)) drop-shadow(0 18px 36px rgba(0,0,0,.12)); }
.tk-stub {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 6px;
  color: #fff;
  padding: 22px 10px;
  border-radius: 20px 0 0 20px;
}
.ac-blue .tk-stub { background: linear-gradient(160deg, #2b7fd4, #1E6EB8 55%, #16609f); }
.ac-red .tk-stub { background: linear-gradient(160deg, #f0566a, #e0354b 55%, #c22a3e); }
.ac-gold .tk-stub { background: linear-gradient(160deg, #e6a23c, #c97e12 55%, #b06e0e); }
.ac-green .tk-stub { background: linear-gradient(160deg, #22a97a, #047857 55%, #036447); }
.ac-purple .tk-stub { background: linear-gradient(160deg, #8b5cf6, #6d28d9 55%, #5b21b6); }
.tk-kind { font-size: 11px; letter-spacing: .2em; opacity: .8; }
.tk-value { display: flex; align-items: baseline; gap: 2px; line-height: 1; }
.tk-value i { font-style: normal; font-size: 15px; font-weight: 600; }
.tk-value b { font-size: 34px; font-weight: 700; letter-spacing: -.04em; font-variant-numeric: tabular-nums; }
.tk-th { font-size: 11.5px; opacity: .85; }

.tk-body {
  position: relative;
  padding: 18px 20px 16px 22px;
  display: flex;
  flex-direction: column;
  border-left: 2px dashed var(--ll-border, #e5e5ea);
}
.tk-row1 { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.tk-scope {
  font-size: 11px;
  font-weight: 600;
  color: var(--ll-primary, #1E6EB8);
  background: rgba(30, 110, 184, .08);
  padding: 3px 10px;
  border-radius: 999px;
}
.ac-red .tk-scope { color: #d6303f; background: rgba(224, 53, 75, .08); }
.ac-gold .tk-scope { color: #a56a0b; background: rgba(201, 126, 18, .1); }
.ac-green .tk-scope { color: #047857; background: rgba(4, 120, 87, .09); }
.ac-purple .tk-scope { color: #6d28d9; background: rgba(109, 40, 217, .09); }
.tk-remain { font-size: 11.5px; font-variant-numeric: tabular-nums; color: var(--ll-muted, #6e6e73); }
.tk-body h3 {
  margin: 0 0 5px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -.01em;
  color: var(--ll-ink, #1d1d1f);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tk-valid { font-size: 12px; color: var(--ll-muted, #6e6e73); }
.tk-actions { margin-top: auto; padding-top: 12px; display: flex; justify-content: flex-end; align-items: center; position: relative; }
.tk-btn {
  border: none;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  padding: 8px 18px;
  border-radius: 980px;
  transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s, opacity .25s;
}
.tk-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 6px 16px rgba(0, 0, 0, .18); }
.tk-btn:active:not(:disabled) { transform: scale(.96); }
.tk-btn:disabled { cursor: not-allowed; opacity: .4; }
.ac-blue .tk-btn { background: var(--ll-primary, #1E6EB8); }
.ac-red .tk-btn { background: #e0354b; }
.ac-gold .tk-btn { background: #c97e12; }
.ac-green .tk-btn { background: #047857; }
.ac-purple .tk-btn { background: #6d28d9; }
.tk-stamp {
  position: absolute;
  right: 96px;
  top: 2px;
  transform: rotate(-12deg);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .1em;
  color: #b0b0b6;
  border: 1.5px solid #b0b0b6;
  border-radius: 4px;
  padding: 1px 7px;
  opacity: .8;
}

/* ===== 空态 ===== */
.cc-empty { text-align: center; padding: 72px 0 88px; }
.cc-empty-ico { display: grid; place-items: center; margin-bottom: 14px; color: var(--ll-muted, #6e6e73); }
.cc-empty p { margin: 0 0 20px; font-size: 15px; color: var(--ll-muted, #6e6e73); }

/* ===== 移动端响应式（纪律：≤991 / ≤700 两档） ===== */
@media (max-width: 991px) {
  .cc-hero { flex-direction: column; align-items: flex-start; gap: 14px; padding-top: 8px; }
  .cc-br { display: inline; }
  .cc-hero-side { width: 100%; justify-content: space-between; }
  .cc-grid { grid-template-columns: 1fr 1fr; gap: 14px; }
}
@media (max-width: 700px) {
  .cc-page { padding-left: 12px; padding-right: 12px; }
  .cc-hero h1 { font-size: 28px; }
  .cc-grid { grid-template-columns: 1fr; }
  .cc-toolbar { flex-direction: column; align-items: stretch; }
  .cc-seg { width: 100%; display: flex; }
  .cc-seg-item { flex: 1; padding: 7px 0; }
  .cc-pill { width: 100%; }
  .tk { grid-template-columns: 92px 1fr; }
  .tk-stub { padding: 18px 6px; }
  .tk-value b { font-size: 27px; }
  .tk-body { padding: 15px 14px 13px 16px; }
  .tk-btn { padding: 7px 14px; }
  .tk-stamp { right: 70px; }
  .tk-row1 { flex-wrap: wrap; row-gap: 6px; }
}
</style>
