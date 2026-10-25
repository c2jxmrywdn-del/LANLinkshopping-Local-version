<template>
  <div class="cp-page">
    <!-- Hero：磨砂渐变横幅 -->
    <div class="cp-hero">
      <div class="cp-hero-glow" aria-hidden="true"></div>
      <div class="cp-hero-main">
        <div class="cp-eyebrow">LANLINK CAMPAIGNS</div>
        <h1>活动中心</h1>
        <p>领券、限时促销与积分激励，一个入口全部搞定。</p>
      </div>
      <div class="cp-hero-side">
        <div class="cp-stat"><b>{{ acts.length }}</b><span>进行中</span></div>
        <div class="cp-stat" v-if="couponCount"><b>{{ couponCount }}</b><span>可领券</span></div>
        <div class="cp-hero-links">
          <button type="button" class="cp-pill" @click="$router.push('/activity/my')">我的券包</button>
          <button type="button" class="cp-pill ghost" @click="$router.push('/activity/rules')">活动规则</button>
        </div>
      </div>
    </div>

    <!-- 分段控件（Apple Segmented Control） -->
    <div class="cp-seg" role="tablist">
      <button v-for="t in TABS" :key="t.key" type="button" role="tab"
              class="cp-seg-item" :class="{ on: tab === t.key }" @click="switchTab(t.key)">
        <i v-if="t.icon">{{ t.icon }}</i>{{ t.label }}
      </button>
    </div>

    <!-- 活动卡片网格 -->
    <a-spin :spinning="loading">
      <div class="cp-grid" v-if="acts.length">
        <template v-for="c in acts" :key="c.campaignId">
          <!-- 券票卡片（领券活动） -->
          <article v-if="c.type === 'coupon'" class="cp-ticket" :class="accent(c.accent)" @click="goDetail(c)">
            <div class="tk-stub">
              <div class="tk-value">
                <template v-if="c.discountRate">{{ rateText(c.discountRate) }}<small>折</small></template>
                <template v-else-if="c.benefitAmount"><small>¥</small>{{ trimZero(c.benefitAmount) }}</template>
              </div>
              <div class="tk-kind">{{ c.discountRate ? '折扣券' : '满减券' }}</div>
            </div>
            <div class="tk-body">
              <div class="tk-top">
                <span class="cp-chip" :class="accent(c.accent)">{{ c.indName ? c.indName + '专享' : '全场通用' }}</span>
                <span class="tk-quota" v-if="c.remainQuota >= 0">仅剩 {{ c.remainQuota }} 张</span>
              </div>
              <h3>{{ c.title }}</h3>
              <div class="tk-rule">
                {{ c.threshold > 0 ? `满 ¥${trimZero(c.threshold)} 可用` : '无门槛' }}
                <template v-if="c.discountRate && c.benefitAmount">· 最高减 ¥{{ trimZero(c.benefitAmount) }}</template>
              </div>
              <div class="tk-foot">
                <span class="tk-valid">{{ validText(c) }}</span>
                <button type="button" class="cp-btn" :class="accent(c.accent)"
                        :disabled="!c.canClaim" @click.stop="claim(c)">
                  {{ c.limitReached ? '已领取' : (c.remainQuota === 0 ? '已抢完' : '立即领取') }}
                </button>
              </div>
            </div>
          </article>

          <!-- 促销卡片（满减 / 折扣 / 限时秒杀） -->
          <article v-else-if="['full_reduce','discount','flash_sale'].includes(c.type)"
                   class="cp-card promo" :class="accent(c.accent)" @click="goDetail(c)">
            <div class="pm-banner">
              <span class="pm-type">{{ typeLabel(c.type) }}</span>
              <span v-if="c.type === 'flash_sale'" class="pm-countdown" :class="{ urgent: remainMs(c.endTime) < 3600_000 }">
                ⏱ {{ remainText(c.endTime) }}
              </span>
            </div>
            <div class="pm-benefit">
              <template v-if="c.type === 'full_reduce'">满 ¥{{ trimZero(c.threshold) }} <b>减 ¥{{ trimZero(c.benefitAmount) }}</b></template>
              <template v-else>全场 <b>{{ rateText(c.discountRate) }} 折</b><small v-if="c.benefitAmount">（最高减 ¥{{ trimZero(c.benefitAmount) }}）</small></template>
            </div>
            <h3>{{ c.title }}</h3>
            <div class="pm-meta">
              {{ c.indName ? c.indName + '行业' : '全场商品' }} · 满自动享 · 与会员折扣取最优
            </div>
            <div class="pm-foot">
              <span class="pm-valid">{{ validText(c) }}</span>
              <button type="button" class="cp-btn ghost" @click.stop="$router.push('/mall')">去下单享优惠</button>
            </div>
          </article>

          <!-- 激励卡片（注册有礼 / 消费有礼） -->
          <article v-else class="cp-card gift" :class="accent(c.accent)" @click="goDetail(c)">
            <div class="gt-badge"><i>🎁</i><b>+{{ c.rewardPoints }}</b><span>积分</span></div>
            <h3>{{ c.title }}</h3>
            <p class="gt-desc">{{ c.description }}</p>
            <div class="gt-foot">
              <span class="pm-valid">{{ validText(c) }}</span>
              <button v-if="c.type === 'register'" type="button" class="cp-btn" :class="accent(c.accent)"
                      :disabled="c.joined" @click.stop="join(c)">
                {{ c.joined ? '已参与' : '立即参与' }}
              </button>
              <span v-else class="gt-auto">下单支付自动参与</span>
            </div>
          </article>
        </template>
      </div>
      <a-empty v-else-if="!loading" description="暂无可参与活动" style="padding:48px 0" />
    </a-spin>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { campaignApi } from '../api'

const router = useRouter()
const TABS = [
  { key: 'all', label: '全部', icon: '✦' },
  { key: 'coupon', label: '领券专区', icon: '🎟️' },
  { key: 'promo', label: '限时促销', icon: '⚡' },
  { key: 'incentive', label: '积分激励', icon: '🎁' }
]
const tab = ref('all')
const acts = ref([])
const loading = ref(false)
const now = ref(Date.now())
let timer = null

const couponCount = computed(() => acts.value.filter(c => c.type === 'coupon' && c.canClaim).length)

function switchTab(t) {
  if (tab.value === t) return
  tab.value = t
  load()
}
async function load() {
  loading.value = true
  try {
    acts.value = (await campaignApi.list(tab.value)) || []
  } catch (e) { acts.value = [] } finally { loading.value = false }
}
function goDetail(c) { router.push('/activity/' + c.campaignId) }
const logged = () => !!localStorage.getItem('ll_user')
function requireLogin() {
  message.info('请先登录')
  router.push({ path: '/login', query: { redirect: '/activity' } })
}

async function claim(c) {
  if (!logged()) return requireLogin()
  try {
    await campaignApi.claim(c.campaignId)
    message.success('领取成功，结算时可用')
    load()
  } catch (e) { /* 拦截器已提示 */ }
}
async function join(c) {
  if (!logged()) return requireLogin()
  try {
    await campaignApi.join(c.campaignId)
    message.success(`参与成功，+${c.rewardPoints} 积分已到账`)
    router.push(`/activity/${c.campaignId}/participation`)
  } catch (e) { /* 拦截器已提示 */ }
}

// ===== 展示口径 =====
const ACCENTS = ['blue', 'red', 'gold', 'green', 'purple']
const accent = (a) => 'ac-' + (ACCENTS.includes(a) ? a : 'blue')
const typeLabel = (t) => ({ full_reduce: '满减', discount: '折扣', flash_sale: '限时秒杀' }[t] || '促销')
const trimZero = (v) => Number(v) % 1 === 0 ? String(Number(v)) : String(v)
const rateText = (r) => trimZero(Number(r) * 10)
const validText = (c) => {
  const end = fmtDate(c.endTime)
  return c.endTime ? `有效期至 ${end}` : '长期有效'
}
const fmtDate = (v) => v ? String(v).slice(0, 10) : ''
const remainMs = (end) => {
  if (!end) return Infinity
  return new Date(end).getTime() - now.value
}
function remainText(end) {
  const ms = remainMs(end)
  if (ms === Infinity) return '不限时'
  if (ms <= 0) return '已结束'
  const s = Math.floor(ms / 1000)
  const d = Math.floor(s / 86400)
  const h = String(Math.floor((s % 86400) / 3600)).padStart(2, '0')
  const m = String(Math.floor((s % 3600) / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  return d > 0 ? `${d}天 ${h}:${m}` : `${h}:${m}:${ss}`
}

onMounted(() => {
  load()
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.cp-page { max-width: 1120px; margin: 0 auto; }

/* ===== Hero：渐变 + 磨砂 ===== */
.cp-hero { position: relative; overflow: hidden; display: flex; justify-content: space-between; align-items: center;
  gap: 20px; padding: 34px 36px; border-radius: 24px; margin-bottom: 22px;
  background: var(--ll-card, #fff); box-shadow: var(--ll-shadow-sm, 0 1px 2px rgba(16,24,40,.06)); }
.cp-hero-glow { position: absolute; inset: -40% -20% auto auto; width: 420px; height: 420px; border-radius: 50%;
  background: radial-gradient(closest-side, rgba(30,110,184,.16), rgba(155,45,32,.08), transparent 70%);
  filter: blur(10px); pointer-events: none; }
.cp-eyebrow { font-size: 11px; letter-spacing: .2em; color: var(--ll-primary, #1E6EB8); font-weight: 600; }
.cp-hero h1 { margin: 6px 0 8px; font-size: 32px; letter-spacing: -.02em; color: var(--ll-ink, #0F172A); }
.cp-hero p { margin: 0; color: var(--ll-muted, #64748B); font-size: 14px; }
.cp-hero-side { display: flex; align-items: center; gap: 18px; position: relative; }
.cp-stat { text-align: center; }
.cp-stat b { display: block; font-size: 26px; color: var(--ll-ink, #0F172A); letter-spacing: -.02em; }
.cp-stat span { font-size: 12px; color: var(--ll-muted, #64748B); }
.cp-hero-links { display: flex; flex-direction: column; gap: 8px; }
.cp-pill { border: none; cursor: pointer; padding: 7px 16px; border-radius: 999px; font-size: 13px;
  background: var(--ll-primary, #1E6EB8); color: #fff; transition: transform .2s, box-shadow .2s; }
.cp-pill:hover { transform: translateY(-1px); box-shadow: 0 8px 20px rgba(30,110,184,.25); }
.cp-pill.ghost { background: transparent; color: var(--ll-primary, #1E6EB8); box-shadow: inset 0 0 0 1px var(--ll-border-strong, #d5dae2); }

/* ===== 分段控件 ===== */
.cp-seg { display: inline-flex; gap: 4px; padding: 4px; border-radius: 14px; margin-bottom: 18px;
  background: var(--ll-soft, rgba(15,23,42,.05)); }
.cp-seg-item { border: none; background: transparent; cursor: pointer; padding: 8px 18px; border-radius: 10px;
  font-size: 13.5px; color: var(--ll-muted, #64748B); transition: all .25s var(--ll-ease-out, ease); }
.cp-seg-item i { font-style: normal; margin-right: 6px; }
.cp-seg-item.on { background: var(--ll-card, #fff); color: var(--ll-ink, #0F172A); font-weight: 600;
  box-shadow: 0 1px 4px rgba(16,24,40,.1); }

/* ===== 网格 ===== */
.cp-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 16px; }

/* ===== 券票卡片 ===== */
.cp-ticket { display: grid; grid-template-columns: 112px 1fr; border-radius: 20px; overflow: hidden;
  background: var(--ll-card, #fff); box-shadow: var(--ll-shadow-sm, 0 1px 2px rgba(16,24,40,.06));
  cursor: pointer; transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s; position: relative; }
.cp-ticket:hover { transform: translateY(-4px); box-shadow: 0 18px 40px rgba(19,35,58,.1); }
.tk-stub { display: flex; flex-direction: column; justify-content: center; align-items: center; color: #fff; padding: 18px 8px; }
.tk-value { font-size: 30px; font-weight: 700; letter-spacing: -.03em; line-height: 1; }
.tk-value small { font-size: 15px; font-weight: 600; }
.tk-kind { margin-top: 7px; font-size: 11px; opacity: .85; letter-spacing: .12em; }
.tk-body { padding: 16px 18px 14px; display: flex; flex-direction: column; position: relative; }
/* 券票缺口虚线：以页面底色打孔，暗色模式自动跟随 */
.tk-body::before { content: ''; position: absolute; left: -7px; top: 50%; width: 14px; height: 14px;
  border-radius: 50%; background: var(--ll-page, #f5f6f8); transform: translateY(-50%); }
.tk-body::after { content: ''; position: absolute; bottom: 14px; left: 0; right: 18px;
  border-top: 2px dashed var(--ll-border, #e5e7eb); }
.tk-top { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-bottom: 9px; }
.tk-quota { font-size: 12px; color: #d93025; font-weight: 600; }
.tk-body h3 { margin: 0 0 6px; font-size: 15.5px; color: var(--ll-ink, #0F172A);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tk-rule { font-size: 13px; color: var(--ll-muted, #64748B); }
.tk-foot { margin-top: auto; padding-top: 26px; display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.tk-valid { font-size: 12px; color: var(--ll-muted, #64748B); }

/* ===== 促销 / 激励卡片 ===== */
.cp-card { border-radius: 20px; padding: 18px 20px 16px; background: var(--ll-card, #fff);
  box-shadow: var(--ll-shadow-sm, 0 1px 2px rgba(16,24,40,.06)); cursor: pointer;
  display: flex; flex-direction: column; transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s; }
.cp-card:hover { transform: translateY(-4px); box-shadow: 0 18px 40px rgba(19,35,58,.1); }
.pm-banner { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.pm-type { font-size: 12px; font-weight: 700; color: #fff; padding: 4px 12px; border-radius: 999px; letter-spacing: .06em; }
.pm-countdown { font-variant-numeric: tabular-nums; font-size: 13px; font-weight: 700; color: var(--ll-ink, #0F172A);
  background: var(--ll-soft, rgba(15,23,42,.06)); padding: 4px 10px; border-radius: 8px; }
.pm-countdown.urgent { color: #d93025; }
.pm-benefit { font-size: 15px; color: var(--ll-ink, #0F172A); margin-bottom: 8px; }
.pm-benefit b { font-size: 24px; letter-spacing: -.02em; }
.pm-benefit small { color: var(--ll-muted, #64748B); }
.cp-card h3 { margin: 0 0 6px; font-size: 15.5px; color: var(--ll-ink, #0F172A); }
.pm-meta { font-size: 12.5px; color: var(--ll-muted, #64748B); }
.pm-foot, .gt-foot { margin-top: auto; padding-top: 16px; display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.pm-valid { font-size: 12px; color: var(--ll-muted, #64748B); }
.gt-badge { display: flex; align-items: baseline; gap: 6px; margin-bottom: 10px; }
.gt-badge i { font-style: normal; font-size: 22px; }
.gt-badge b { font-size: 28px; letter-spacing: -.02em; color: var(--ll-ink, #0F172A); }
.gt-badge span { font-size: 12px; color: var(--ll-muted, #64748B); }
.gt-desc { margin: 0; font-size: 13px; color: var(--ll-muted, #64748B); line-height: 1.6;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; min-height: 42px; }
.gt-auto { font-size: 12.5px; color: var(--ll-muted, #64748B); }

/* ===== 徽章 / 按钮 ===== */
.cp-chip { font-size: 11.5px; color: #fff; padding: 3px 10px; border-radius: 999px; }
.cp-btn { border: none; cursor: pointer; font-size: 13px; font-weight: 600; color: #fff;
  padding: 8px 16px; border-radius: 999px; transition: transform .2s, box-shadow .2s, opacity .2s; }
.cp-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 8px 18px rgba(16,24,40,.16); }
.cp-btn:disabled { cursor: not-allowed; opacity: .45; }
.cp-btn.ghost { background: var(--ll-soft, rgba(15,23,42,.06)); color: var(--ll-ink, #0F172A);
  box-shadow: inset 0 0 0 1px var(--ll-border, #e5e7eb); }

/* ===== 主题色（21st.dev 渐变点缀） ===== */
.ac-blue .tk-stub, .ac-blue.pm-type, .ac-blue.cp-btn:not(.ghost), .ac-blue.cp-chip
  { background: linear-gradient(135deg, #1E6EB8, #38bdf8); }
.ac-red .tk-stub, .ac-red.pm-type, .ac-red.cp-btn:not(.ghost), .ac-red.cp-chip
  { background: linear-gradient(135deg, #e0354b, #ff7a59); }
.ac-gold .tk-stub, .ac-gold.pm-type, .ac-gold.cp-btn:not(.ghost), .ac-gold.cp-chip
  { background: linear-gradient(135deg, #b45309, #f2b544); }
.ac-green .tk-stub, .ac-green.pm-type, .ac-green.cp-btn:not(.ghost), .ac-green.cp-chip
  { background: linear-gradient(135deg, #047857, #34d399); }
.ac-purple .tk-stub, .ac-purple.pm-type, .ac-purple.cp-btn:not(.ghost), .ac-purple.cp-chip
  { background: linear-gradient(135deg, #6d28d9, #c084fc); }

/* ===== 响应式：平板 / 手机 ===== */
@media (max-width: 760px) {
  .cp-hero { flex-direction: column; align-items: flex-start; padding: 26px 22px; }
  .cp-hero-side { width: 100%; justify-content: space-between; }
  .cp-hero-links { flex-direction: row; }
  .cp-seg { width: 100%; overflow-x: auto; white-space: nowrap; scrollbar-width: none; }
  .cp-seg-item { padding: 8px 14px; }
}
@media (max-width: 480px) {
  .cp-grid { grid-template-columns: 1fr; gap: 12px; }
  .cp-ticket { grid-template-columns: 96px 1fr; }
  .tk-value { font-size: 24px; }
  .cp-hero h1 { font-size: 26px; }
}
</style>
