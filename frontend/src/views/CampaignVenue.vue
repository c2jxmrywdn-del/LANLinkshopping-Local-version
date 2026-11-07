<template>
  <div class="cv-page">
    <!-- Apple 营销页式 Hero（组件系沿用活动中心） -->
    <header class="cv-hero">
      <DotPattern class="cv-dots" :width="18" :height="18" :cr="1.2" />
      <div class="cv-hero-copy">
        <h1>大促会场<span class="cv-dot">。</span></h1>
        <p>行业大促一站逛齐，<br class="cv-br" />促销让利与热销现货同场直采。</p>
      </div>
      <div class="cv-hero-side">
        <div class="cv-stats">
          <div class="cv-stat"><b><NumberTicker :value="venueCampaigns.length" /></b><span>进行中活动</span></div>
          <div class="cv-stat"><b><NumberTicker :value="hotProducts.length" /></b><span>热销现货</span></div>
        </div>
        <div class="cv-hero-links">
          <ShimmerButton class="cv-cta" background="var(--ll-primary, #1E6EB8)"
                         shimmer-color="rgba(255,255,255,.9)" @click="$router.push('/coupons')">领券中心</ShimmerButton>
          <button type="button" class="cv-pill plain" @click="$router.push('/activity')">活动中心</button>
        </div>
      </div>
    </header>

    <!-- iOS 分段控件：全部会场 + 按活动行业聚合（行业名取自现有 campaign 数据，零新枚举） -->
    <div class="cv-seg" role="tablist">
      <button v-for="t in venueTabs" :key="t.key" type="button" role="tab"
              class="cv-seg-item" :class="{ on: tab === t.key }" @click="tab = t.key">
        {{ t.label }}
      </button>
    </div>

    <a-spin :spinning="loading">
      <!-- ===== 促销活动：满减 / 折扣 / 秒杀（白卡 + 秒杀倒计时） ===== -->
      <section v-if="promoList.length" class="cv-section">
        <h2 class="cv-sec-title"><Zap :size="16" :stroke-width="2.2" class="cv-sec-ico" /> 促销让利</h2>
        <div class="cv-grid">
          <article v-for="c in promoList" :key="c.campaignId" class="pc" :class="accent(c.accent)" @click="goDetail(c)">
            <div class="pc-head">
              <span class="pc-type">{{ typeLabel(c.type) }}</span>
              <span class="pc-scope">{{ c.indName ? c.indName + '行业' : '全场' }}</span>
            </div>
            <div class="pc-benefit">
              <template v-if="c.type === 'full_reduce'"><small>满 ¥{{ trimZero(c.threshold) }}</small><b>减 ¥{{ trimZero(c.benefitAmount) }}</b></template>
              <template v-else-if="c.type === 'discount'"><b>{{ rateText(c.discountRate) }}<i>折</i></b><small v-if="c.benefitAmount">最高减 ¥{{ trimZero(c.benefitAmount) }}</small></template>
              <template v-else><b>{{ rateText(c.discountRate) }}<i>折</i></b><small>限时抢购 · {{ remainText(c.endTime) }}</small></template>
            </div>
            <h3>{{ c.title }}</h3>
            <div class="pc-foot">
              <span class="pc-valid">{{ validText(c) }}</span>
              <span class="pc-go">结算自动享 <ArrowRight :size="12" :stroke-width="2.4" /></span>
            </div>
          </article>
        </div>
      </section>

      <!-- ===== 可领优惠券（券票卡组件系；批量入账在领券中心） ===== -->
      <section v-if="couponList.length" class="cv-section">
        <div class="cv-sec-head">
          <h2 class="cv-sec-title"><Ticket :size="16" :stroke-width="2.2" class="cv-sec-ico" /> 可领优惠券</h2>
          <button type="button" class="cv-link" @click="$router.push('/coupons')">进入领券中心 <ArrowRight :size="12" :stroke-width="2.4" /></button>
        </div>
        <div class="cv-grid">
          <article v-for="c in couponList" :key="c.campaignId" class="tk" :class="accent(c.accent)" @click="goDetail(c)">
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
                <span class="tk-scope">{{ c.indName ? c.indName + '专享' : '全场通用' }}</span>
                <span class="tk-remain" v-if="c.remainQuota >= 0">余 {{ c.remainQuota }}</span>
              </div>
              <h3>{{ c.title }}</h3>
              <div class="tk-actions">
                <button type="button" class="tk-btn" :disabled="!c.canClaim || !!actingId" @click.stop="claim(c)">
                  {{ actingId === c.campaignId ? '领取中…' : (c.limitReached ? '已领取' : (c.remainQuota === 0 ? '已抢完' : '立即领取')) }}
                </button>
              </div>
            </div>
          </article>
        </div>
      </section>

      <!-- ===== 热销爆款：homeApi.hot 现货直采 ===== -->
      <section v-if="filteredHot.length" class="cv-section">
        <h2 class="cv-sec-title"><Flame :size="16" :stroke-width="2.2" class="cv-sec-ico" /> 热销爆款</h2>
        <a-row :gutter="[16, 20]">
          <a-col v-for="p in filteredHot" :key="p.prodId" :xs="12" :sm="12" :md="8" :lg="6">
            <InteractiveProductCard
              :image-url="p.coverUrl || ''"
              :title="p.title"
              :description="p.brand || 'LANLINK SELECTION'"
              :price="'¥' + p.price"
              @click="$router.push('/product/' + p.prodId)"
            >
              <template #footer>
                <div class="venue-card-meta">销量 {{ p.sales }} · 库存 {{ p.stock }}</div>
              </template>
            </InteractiveProductCard>
          </a-col>
        </a-row>
      </section>

      <!-- 空态：所选行业暂无活动 -->
      <div v-if="!loading && !promoList.length && !couponList.length && !filteredHot.length" class="cv-empty">
        <div class="cv-empty-ico"><Store :size="48" class="text-slate-300 dark:text-slate-600 inline-block" /></div>
        <p>该行业暂无进行中的会场活动</p>
        <button type="button" class="cv-pill" @click="tab = 'all'">逛全部会场</button>
      </div>
    </a-spin>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { Zap, Ticket, Flame, Store, ArrowRight } from 'lucide-vue-next'
import { campaignApi, homeApi } from '../api'

import DotPattern from '../components/ui/DotPattern.vue'
import NumberTicker from '../components/ui/NumberTicker.vue'
import ShimmerButton from '../components/ui/ShimmerButton.vue'
import InteractiveProductCard from '../components/ui/InteractiveProductCard.vue'

const router = useRouter()
const campaigns = ref([])
const hotProducts = ref([])
const loading = ref(false)
const now = ref(Date.now())
let timer = null

// ===== 会场分段：全部 + 按活动的行业名聚合（纯前端派生，零新枚举；indId 供热销过滤） =====
const tab = ref('all')
const venueTabs = computed(() => {
  const seen = new Map()
  for (const c of campaigns.value) {
    const name = c.indName || '全行业'
    if (!seen.has(name)) seen.set(name, { key: name, label: name, indId: c.indId ?? null })
  }
  return [{ key: 'all', label: '全部会场', indId: null }, ...seen.values()]
})

// 行业过滤：活动按行业名匹配；热销按 tab 对应 indId 匹配（活动无行业信息时不过滤热销）
const matchedTab = computed(() => venueTabs.value.find(t => t.key === tab.value) || null)
const venueCampaigns = computed(() => {
  if (tab.value === 'all') return campaigns.value
  return campaigns.value.filter(c => (c.indName || '全行业') === tab.value)
})
const promoList = computed(() => venueCampaigns.value.filter(c => ['full_reduce', 'discount', 'flash_sale'].includes(c.type)))
const couponList = computed(() => venueCampaigns.value.filter(c => c.type === 'coupon'))
const filteredHot = computed(() => {
  const indId = matchedTab.value?.indId
  if (tab.value === 'all' || indId == null) return hotProducts.value
  const hit = hotProducts.value.filter(p => Number(p.indId) === Number(indId))
  return hit.length ? hit : []
})

// 竞态守卫（同活动中心口径）：仅最新一次请求写入
let loadSeq = 0
async function load() {
  const seq = ++loadSeq
  loading.value = true
  try {
    const [actList, hotList] = await Promise.all([
      campaignApi.list().catch(() => []),
      homeApi.hot(8).catch(() => [])
    ])
    if (seq !== loadSeq) return
    campaigns.value = actList || []
    hotProducts.value = hotList || []
  } catch (e) {
    if (seq !== loadSeq) return
    campaigns.value = []
    hotProducts.value = []
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

// 券票卡领取：单券原子领取（同活动中心口径），批量入账引导至领券中心
const actingId = ref(null)
const logged = () => !!localStorage.getItem('ll_user')
function requireLogin() {
  message.info('请先登录')
  router.push({ path: '/login', query: { redirect: '/venue' } })
}
async function claim(c) {
  if (!logged()) return requireLogin()
  if (actingId.value) return
  actingId.value = c.campaignId
  try {
    await campaignApi.claim(c.campaignId)
    message.success('领取成功，结算时可用')
    load()
  } catch (e) {
    /* 拦截器已提示 */
  } finally { actingId.value = null }
}

function goDetail(c) {
  router.push('/activity/' + c.campaignId)
}

// ===== 展示口径（与活动中心一致） =====
const ACCENTS = ['blue', 'red', 'gold', 'green', 'purple']
const accent = (a) => 'ac-' + (ACCENTS.includes(a) ? a : 'blue')
const typeLabel = (t) => ({ full_reduce: '满减', discount: '折扣', flash_sale: '限时秒杀' }[t] || '促销')
const trimZero = (v) => { const n = Math.round(Number(v) * 100) / 100; return String(n) }
const rateText = (r) => trimZero(Number(r) * 10)
const validText = (c) => c.endTime ? `有效期至 ${fmtDate(c.endTime)}` : '长期有效'
const fmtDate = (v) => v ? String(v).slice(0, 10) : ''

function remainText(end) {
  const ms = !end ? Infinity : new Date(end).getTime() - now.value
  if (ms === Infinity) return '不限时'
  if (ms <= 0) return '已结束'
  const s = Math.floor(ms / 1000)
  const d = Math.floor(s / 86400)
  const hh = String(Math.floor((s % 86400) / 3600)).padStart(2, '0')
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  return d > 0 ? `${d}天 ${hh}:${mm}:${ss}` : `${hh}:${mm}:${ss}`
}

onMounted(() => {
  load()
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.cv-page {
  max-width: 1120px;
  margin: 0 auto;
}

/* ===== Hero：与活动中心同款排版 ===== */
.cv-hero {
  position: relative;
  overflow: hidden;
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 24px;
  padding: 20px 4px 26px;
}
.cv-dots { color: rgba(30, 110, 184, .18); }
.cv-hero-copy, .cv-hero-side { position: relative; }
.cv-hero h1 {
  margin: 0;
  font-size: clamp(30px, 4.4vw, 46px);
  font-weight: 700;
  letter-spacing: -.028em;
  line-height: 1.08;
  color: var(--ll-ink, #1d1d1f);
}
.cv-dot { color: var(--ll-primary, #1E6EB8); }
.cv-hero p { margin: 12px 0 0; font-size: 17px; line-height: 1.5; color: var(--ll-muted, #6e6e73); }
.cv-br { display: none; }
.cv-hero-side { display: flex; align-items: center; gap: 22px; flex-shrink: 0; }
.cv-stats { display: flex; gap: 20px; }
.cv-stat { text-align: right; }
.cv-stat b {
  display: block;
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -.03em;
  font-variant-numeric: tabular-nums;
  color: var(--ll-ink, #1d1d1f);
}
.cv-stat span { font-size: 12px; color: var(--ll-muted, #6e6e73); }
.cv-hero-links { display: flex; gap: 10px; }
.cv-cta { padding: 10px 22px; font-size: 14px; }

/* 胶囊按钮：iOS 圆钮 */
.cv-pill {
  border: none;
  cursor: pointer;
  padding: 10px 20px;
  border-radius: 980px;
  font-size: 14px;
  font-weight: 500;
  background: var(--ll-primary, #1E6EB8);
  color: #fff;
  transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s;
}
.cv-pill:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 6px 18px rgba(30, 110, 184, .3); }
.cv-pill:active:not(:disabled) { transform: scale(.97); }
.cv-pill.plain {
  background: transparent;
  color: var(--ll-primary, #1E6EB8);
  box-shadow: inset 0 0 0 1px var(--ll-border-strong, #d2d2d7);
}

/* ===== iOS 分段控件 ===== */
.cv-seg {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border-radius: 12px;
  margin-bottom: 22px;
  max-width: 100%;
  overflow-x: auto;
  scrollbar-width: none;
  background: rgba(120, 120, 128, .14);
  backdrop-filter: blur(20px) saturate(180%);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
}
.cv-seg::-webkit-scrollbar { display: none; }
.cv-seg-item {
  border: none;
  background: transparent;
  cursor: pointer;
  padding: 7px 18px;
  border-radius: 10px;
  font-size: 13.5px;
  font-weight: 500;
  color: var(--ll-ink, #1d1d1f);
  white-space: nowrap;
  opacity: .72;
  transition: all .25s var(--ll-ease-out, ease);
}
.cv-seg-item.on {
  background: var(--ll-card, #fff);
  opacity: 1;
  font-weight: 600;
  box-shadow: 0 3px 8px rgba(0, 0, 0, .12), 0 1px 1px rgba(0, 0, 0, .16);
}
.cv-seg-item:not(.on):hover { opacity: 1; }

/* ===== 分区标题与网格 ===== */
.cv-section { margin-bottom: 34px; }
.cv-sec-head { display: flex; justify-content: space-between; align-items: center; }
.cv-sec-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 16px;
  font-size: 19px;
  font-weight: 700;
  letter-spacing: -.02em;
  color: var(--ll-ink, #1d1d1f);
}
.cv-sec-ico { color: var(--ll-primary, #1E6EB8); }
.cv-link {
  border: 0;
  background: transparent;
  cursor: pointer;
  font: inherit;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ll-primary, #1E6EB8);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.cv-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 18px; }
.cv-grid > article { min-width: 0; }
.venue-card-meta { color: rgba(255, 255, 255, .84); font-size: 12px; line-height: 1.5; text-shadow: 0 1px 8px rgba(0, 0, 0, .2); }

/* ============ 促销白卡（活动中心满减/折扣卡组件系） ============ */
.pc {
  display: flex;
  flex-direction: column;
  border-radius: 22px;
  padding: 22px 24px 18px;
  background: var(--ll-card, #fff);
  cursor: pointer;
  box-shadow: 0 1px 1px rgba(0, 0, 0, .04), 0 8px 28px rgba(0, 0, 0, .06);
  transition: transform .3s var(--ll-ease-out, ease), box-shadow .3s;
}
.pc:hover { transform: translateY(-4px); box-shadow: 0 2px 2px rgba(0,0,0,.04), 0 16px 40px rgba(0, 0, 0, .1); }
.pc-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.pc-type { font-size: 11.5px; font-weight: 700; letter-spacing: .12em; color: #fff; padding: 4px 12px; border-radius: 999px; }
.ac-blue .pc-type { background: var(--ll-primary, #1E6EB8); }
.ac-red .pc-type { background: #e0354b; }
.ac-gold .pc-type { background: #c97e12; }
.ac-green .pc-type { background: #047857; }
.ac-purple .pc-type { background: #6d28d9; }
.pc-scope { font-size: 12px; color: var(--ll-muted, #6e6e73); }
.pc-benefit { display: flex; align-items: baseline; gap: 8px; margin-bottom: 8px; }
.pc-benefit b {
  font-size: 36px;
  font-weight: 700;
  letter-spacing: -.03em;
  line-height: 1;
  color: var(--ll-ink, #1d1d1f);
  font-variant-numeric: tabular-nums;
}
.pc-benefit i { font-style: normal; font-size: 16px; font-weight: 600; color: var(--ll-ink, #1d1d1f); }
.pc-benefit small { font-size: 13.5px; color: var(--ll-muted, #6e6e73); }
.pc h3 { margin: 0 0 14px; font-size: 15px; font-weight: 600; color: var(--ll-ink, #1d1d1f); }
.pc-foot {
  margin-top: auto;
  padding-top: 12px;
  border-top: 1px solid var(--ll-border, #e5e5ea);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.pc-valid { font-size: 12px; color: var(--ll-muted, #6e6e73); }
.pc-go { font-size: 12.5px; font-weight: 600; color: var(--ll-primary, #1E6EB8); }

/* ============ 券票（活动中心券票卡组件系） ============ */
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
  padding: 16px 20px 14px 22px;
  display: flex;
  flex-direction: column;
  border-left: 2px dashed var(--ll-border, #e5e5ea);
  min-width: 0;
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
.ac-gold .tk-scope { color: #a56a0b; background: rgba(201, 126, 18, .1); }
.ac-red .tk-scope { color: #d6303f; background: rgba(224, 53, 75, .08); }
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
.tk-actions { margin-top: auto; padding-top: 10px; display: flex; justify-content: flex-end; }
.tk-btn {
  border: none;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  padding: 7px 16px;
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

/* ===== 空态 ===== */
.cv-empty { text-align: center; padding: 72px 0 88px; }
.cv-empty-ico { display: grid; place-items: center; margin-bottom: 14px; color: var(--ll-muted, #6e6e73); }
.cv-empty p { margin: 0 0 20px; font-size: 15px; color: var(--ll-muted, #6e6e73); }

/* ===== 移动端响应式（纪律：≤991 / ≤700 两档） ===== */
@media (max-width: 991px) {
  .cv-hero { flex-direction: column; align-items: flex-start; gap: 14px; padding-top: 8px; }
  .cv-br { display: inline; }
  .cv-hero-side { width: 100%; justify-content: space-between; }
  .cv-grid { grid-template-columns: 1fr 1fr; gap: 14px; }
}
@media (max-width: 700px) {
  .cv-page { padding-left: 12px; padding-right: 12px; }
  .cv-hero h1 { font-size: 28px; }
  .cv-grid { grid-template-columns: 1fr; }
  .cv-sec-head { flex-wrap: wrap; row-gap: 6px; }
  .tk { grid-template-columns: 92px 1fr; }
  .tk-stub { padding: 18px 6px; }
  .tk-value b { font-size: 27px; }
  .tk-body { padding: 14px 14px 12px 16px; }
  .pc { padding: 20px 18px 16px; }
  .pc-head, .pc-benefit, .pc-foot { flex-wrap: wrap; row-gap: 6px; }
  .pc-benefit b { font-size: 30px; }
}
</style>
