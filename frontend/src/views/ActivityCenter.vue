<template>
  <div class="cp-page">
    <!-- Apple 营销页式 Hero -->
    <header class="cp-hero">
      <div class="cp-hero-copy">
        <h1>活动中心<span class="cp-dot">。</span></h1>
        <p>领券、限时秒杀与积分激励，<br class="cp-br" />一站式全部搞定。</p>
      </div>
      <div class="cp-hero-side">
        <div class="cp-stats">
          <div class="cp-stat"><b>{{ acts.length }}</b><span>进行中</span></div>
          <div class="cp-stat" v-if="couponCount"><b>{{ couponCount }}</b><span>可领</span></div>
        </div>
        <div class="cp-hero-links">
          <button type="button" class="cp-pill" @click="$router.push('/activity/my')">我的券包</button>
          <button type="button" class="cp-pill plain" @click="$router.push('/activity/rules')">活动规则</button>
        </div>
      </div>
    </header>

    <!-- iOS 分段控件 -->
    <div class="cp-seg" role="tablist">
      <button v-for="t in TABS" :key="t.key" type="button" role="tab"
              class="cp-seg-item" :class="{ on: tab === t.key }" @click="switchTab(t.key)">
        {{ t.label }}
      </button>
    </div>

    <a-spin :spinning="loading">
      <div class="cp-grid" v-if="acts.length">
        <template v-for="c in acts" :key="c.campaignId">
          <!-- ============ 券票（领券活动） ============ -->
          <article v-if="c.type === 'coupon'" class="tk" :class="accent(c.accent)" @click="goDetail(c)">
            <div class="tk-stub">
              <span class="tk-kind">{{ c.discountRate ? '折扣' : '满减券' }}</span>
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
              <div class="tk-valid">{{ validText(c) }}</div>
              <div class="tk-actions">
                <button type="button" class="tk-btn" :disabled="!c.canClaim" @click.stop="claim(c)">
                  {{ c.limitReached ? '已领取' : (c.remainQuota === 0 ? '已抢完' : '立即领取') }}
                </button>
                <span class="tk-stamp" v-if="c.limitReached">已领取</span>
              </div>
            </div>
          </article>

          <!-- ============ 限时秒杀：深色卡 + Border Beam Panel（@rmahammad 原版移植：双彗星+速度弹簧） ============ -->
          <BeamPanel v-else-if="c.type === 'flash_sale'" class="fk" :radius="22" :seed="7"
                     :colors="['#ffb340']" :hover-speed="300" @click="goDetail(c)">
            <div class="fk-tag">限时秒杀</div>
            <div class="fk-count">
              <span class="fk-cd">{{ remainText(c.endTime) }}</span>
              <span class="fk-cd-label" v-if="remainMs(c.endTime) > 0">后结束</span>
            </div>
            <h3>{{ c.title }}</h3>
            <div class="fk-benefit">
              <b>{{ rateText(c.discountRate) }}</b><i>折</i>
              <em v-if="c.benefitAmount">最高减 ¥{{ trimZero(c.benefitAmount) }}</em>
            </div>
            <div class="fk-foot">
              <span>{{ c.indName ? c.indName + '行业' : '全场商品' }} · 满足门槛自动享</span>
              <button type="button" class="fk-btn" @click.stop="$router.push('/mall')">去抢购</button>
            </div>
          </BeamPanel>

          <!-- ============ 满减 / 折扣：白卡 ============ -->
          <article v-else-if="['full_reduce','discount'].includes(c.type)" class="pc" :class="accent(c.accent)" @click="goDetail(c)">
            <div class="pc-head">
              <span class="pc-type">{{ typeLabel(c.type) }}</span>
              <span class="pc-scope">{{ c.indName ? c.indName + '行业' : '全场' }}</span>
            </div>
            <div class="pc-benefit">
              <template v-if="c.type === 'full_reduce'"><small>满 ¥{{ trimZero(c.threshold) }}</small><b>减 ¥{{ trimZero(c.benefitAmount) }}</b></template>
              <template v-else><b>{{ rateText(c.discountRate) }}<i>折</i></b><small v-if="c.benefitAmount">最高减 ¥{{ trimZero(c.benefitAmount) }}</small></template>
            </div>
            <h3>{{ c.title }}</h3>
            <div class="pc-foot">
              <span class="pc-valid">{{ validText(c) }}</span>
              <span class="pc-go">结算自动享 →</span>
            </div>
          </article>

          <!-- ============ 积分激励：金色浅底卡 ============ -->
          <article v-else class="gc" @click="goDetail(c)">
            <div class="gc-icon">🎁</div>
            <div class="gc-main">
              <div class="gc-head">
                <span class="gc-type">{{ c.type === 'register' ? '注册有礼' : '消费有礼' }}</span>
                <b class="gc-points">+{{ c.rewardPoints }}<i>积分</i></b>
              </div>
              <h3>{{ c.title }}</h3>
              <p class="gc-desc">{{ c.description }}</p>
              <div class="gc-foot">
                <span class="gc-valid">{{ validText(c) }}</span>
                <button v-if="c.type === 'register'" type="button" class="gc-btn"
                        :disabled="c.joined" @click.stop="join(c)">{{ c.joined ? '已参与' : '立即参与' }}</button>
                <span v-else class="gc-auto">支付后自动参与</span>
              </div>
            </div>
          </article>
        </template>
      </div>
      <div v-else-if="!loading" class="cp-empty">
        <div class="cp-empty-ico">🎟️</div>
        <p>暂无可参与的活动</p>
        <button type="button" class="cp-pill" @click="$router.push('/mall')">先去商城逛逛</button>
      </div>
    </a-spin>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { campaignApi } from '../api'
import BeamPanel from '../components/ui/BeamPanel.vue'

const router = useRouter()
const TABS = [
  { key: 'all', label: '全部' },
  { key: 'coupon', label: '领券' },
  { key: 'promo', label: '限时促销' },
  { key: 'incentive', label: '积分激励' }
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
const typeLabel = (t) => ({ full_reduce: '满减', discount: '折扣' }[t] || '促销')
const trimZero = (v) => Number(v) % 1 === 0 ? String(Number(v)) : String(v)
const rateText = (r) => trimZero(Number(r) * 10)
const validText = (c) => c.endTime ? `有效期至 ${fmtDate(c.endTime)}` : '长期有效'
const fmtDate = (v) => v ? String(v).slice(0, 10) : ''
const remainMs = (end) => !end ? Infinity : new Date(end).getTime() - now.value
function remainText(end) {
  const ms = remainMs(end)
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
.cp-page { max-width: 1120px; margin: 0 auto; }

/* ===== Hero：Apple 营销页排版 ===== */
.cp-hero { display: flex; justify-content: space-between; align-items: flex-end; gap: 24px; padding: 20px 4px 26px; }
.cp-hero h1 { margin: 0; font-size: clamp(30px, 4.4vw, 46px); font-weight: 700; letter-spacing: -.028em;
  line-height: 1.08; color: var(--ll-ink, #1d1d1f); }
.cp-dot { color: var(--ll-primary, #1E6EB8); }
.cp-hero p { margin: 12px 0 0; font-size: 17px; line-height: 1.5; color: var(--ll-muted, #6e6e73); }
.cp-br { display: none; }
.cp-hero-side { display: flex; align-items: center; gap: 22px; flex-shrink: 0; }
.cp-stats { display: flex; gap: 20px; }
.cp-stat { text-align: right; }
.cp-stat b { display: block; font-size: 30px; font-weight: 700; letter-spacing: -.03em;
  font-variant-numeric: tabular-nums; color: var(--ll-ink, #1d1d1f); }
.cp-stat span { font-size: 12px; color: var(--ll-muted, #6e6e73); }
.cp-hero-links { display: flex; gap: 10px; }

/* 胶囊按钮：iOS 圆钮 */
.cp-pill { border: none; cursor: pointer; padding: 10px 20px; border-radius: 980px; font-size: 14px; font-weight: 500;
  background: var(--ll-primary, #1E6EB8); color: #fff;
  transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s, background-color .25s; }
.cp-pill:hover { transform: translateY(-1px); box-shadow: 0 6px 18px rgba(30, 110, 184, .3); }
.cp-pill:active { transform: scale(.97); }
.cp-pill.plain { background: transparent; color: var(--ll-primary, #1E6EB8);
  box-shadow: inset 0 0 0 1px var(--ll-border-strong, #d2d2d7); }

/* ===== iOS 分段控件 ===== */
.cp-seg { display: inline-flex; gap: 2px; padding: 2px; border-radius: 12px; margin-bottom: 22px;
  background: rgba(120, 120, 128, .14);
  backdrop-filter: blur(20px) saturate(180%); -webkit-backdrop-filter: blur(20px) saturate(180%); }
.cp-seg-item { border: none; background: transparent; cursor: pointer; padding: 7px 18px; border-radius: 10px;
  font-size: 13.5px; font-weight: 500; color: var(--ll-ink, #1d1d1f); opacity: .72;
  transition: all .25s var(--ll-ease-out, ease); }
.cp-seg-item.on { background: var(--ll-card, #fff); opacity: 1; font-weight: 600;
  box-shadow: 0 3px 8px rgba(0, 0, 0, .12), 0 1px 1px rgba(0, 0, 0, .16); }
.cp-seg-item:not(.on):hover { opacity: 1; }

.cp-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 18px; }
/* grid item 默认 min-width:auto 会被内容 min-content 撑破列宽（手机上横向溢出的根源），统一允许收缩 */
.cp-grid > article, .cp-grid > .fk { min-width: 0; }
.tk-body { min-width: 0; }

/* ============ 券票：mask 打孔 + drop-shadow 随形 ============ */
.tk { position: relative; display: grid; grid-template-columns: 116px 1fr; border-radius: 20px;
  background: var(--ll-card, #fff); cursor: pointer;
  --notch: 12px; --cut: 104px;
  -webkit-mask: radial-gradient(var(--notch) at var(--cut) 0, transparent 98%, #000) ,
                radial-gradient(var(--notch) at var(--cut) 100%, transparent 98%, #000);
  -webkit-mask-composite: source-in;
  mask: radial-gradient(var(--notch) at var(--cut) 0, transparent 98%, #000),
        radial-gradient(var(--notch) at var(--cut) 100%, transparent 98%, #000);
  mask-composite: intersect;
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, .05)) drop-shadow(0 10px 24px rgba(0, 0, 0, .07));
  transition: transform .3s var(--ll-ease-out, ease), filter .3s; }
.tk:hover { transform: translateY(-4px); filter: drop-shadow(0 2px 4px rgba(0,0,0,.06)) drop-shadow(0 18px 36px rgba(0,0,0,.12)); }
.tk-stub { display: flex; flex-direction: column; justify-content: center; align-items: center; gap: 6px;
  color: #fff; padding: 22px 10px; border-radius: 20px 0 0 20px; }
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
/* 打孔线：虚线 + 内阴影质感 */
.tk-body { position: relative; padding: 18px 20px 16px 22px; display: flex; flex-direction: column;
  border-left: 2px dashed var(--ll-border, #e5e5ea); }
.tk-row1 { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.tk-scope { font-size: 11px; font-weight: 600; color: var(--ll-primary, #1E6EB8);
  background: rgba(30, 110, 184, .08); padding: 3px 10px; border-radius: 999px; }
.ac-red .tk-scope { color: #d6303f; background: rgba(224, 53, 75, .08); }
.ac-gold .tk-scope { color: #a56a0b; background: rgba(201, 126, 18, .1); }
.ac-green .tk-scope { color: #047857; background: rgba(4, 120, 87, .09); }
.ac-purple .tk-scope { color: #6d28d9; background: rgba(109, 40, 217, .09); }
.tk-remain { font-size: 11.5px; font-variant-numeric: tabular-nums; color: var(--ll-muted, #6e6e73); }
.tk-body h3 { margin: 0 0 5px; font-size: 15px; font-weight: 600; letter-spacing: -.01em; color: var(--ll-ink, #1d1d1f);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tk-valid { font-size: 12px; color: var(--ll-muted, #6e6e73); }
.tk-actions { margin-top: auto; padding-top: 12px; display: flex; justify-content: flex-end; align-items: center; position: relative; }
.tk-btn { border: none; cursor: pointer; font-size: 13px; font-weight: 600; color: #fff;
  padding: 8px 18px; border-radius: 980px;
  transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s, opacity .25s; }
.tk-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 6px 16px rgba(0, 0, 0, .18); }
.tk-btn:active:not(:disabled) { transform: scale(.96); }
.tk-btn:disabled { cursor: not-allowed; opacity: .4; }
.ac-blue .tk-btn { background: var(--ll-primary, #1E6EB8); }
.ac-red .tk-btn { background: #e0354b; }
.ac-gold .tk-btn { background: #c97e12; }
.ac-green .tk-btn { background: #047857; }
.ac-purple .tk-btn { background: #6d28d9; }
/* 已领取：斜盖章 */
.tk-stamp { position: absolute; right: 96px; top: 2px; transform: rotate(-12deg);
  font-size: 11px; font-weight: 700; letter-spacing: .1em; color: #b0b0b6;
  border: 1.5px solid #b0b0b6; border-radius: 4px; padding: 1px 7px; opacity: .8; }

/* ============ 限时秒杀：深色卡 + BeamPanel 原版移植（双彗星边环 + 投射光晕） ============ */
.fk { display: block; grid-column: span 2; border: 0; border-radius: 22px;
  padding: 26px 28px 22px; cursor: pointer; color: #fff;
  background: linear-gradient(145deg, #232326, #151517 60%); }
.fk-tag { display: inline-block; font-size: 11px; font-weight: 700; letter-spacing: .18em;
  color: #ffd9a8; border: 1px solid rgba(255, 179, 64, .5); border-radius: 999px; padding: 3px 12px; }
.fk-count { position: absolute; top: 22px; right: 26px; text-align: right; }
.fk-cd { display: block; font-size: 30px; font-weight: 700; letter-spacing: -.02em;
  font-variant-numeric: tabular-nums; color: #fff; }
.fk-cd-label { font-size: 11.5px; color: rgba(255, 255, 255, .55); }
.fk h3 { margin: 18px 0 8px; font-size: 21px; font-weight: 700; letter-spacing: -.02em; }
.fk-benefit { display: flex; align-items: baseline; gap: 6px; }
.fk-benefit b { font-size: 44px; font-weight: 700; letter-spacing: -.04em; line-height: 1;
  background: linear-gradient(100deg, #ffd9a8, #ffb340 45%, #ff5f6d);
  -webkit-background-clip: text; background-clip: text; color: transparent; }
.fk-benefit i { font-style: normal; font-size: 18px; font-weight: 600; color: #ffb340; }
.fk-benefit em { font-style: normal; font-size: 13px; color: rgba(255, 255, 255, .6); }
.fk-foot { margin-top: 18px; padding-top: 14px; border-top: 1px solid rgba(255, 255, 255, .1);
  display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.fk-foot > span { font-size: 12.5px; color: rgba(255, 255, 255, .6); }
.fk-btn { border: none; cursor: pointer; font-size: 13.5px; font-weight: 600; color: #151517;
  padding: 9px 20px; border-radius: 980px;
  background: linear-gradient(100deg, #ffd9a8, #ffb340);
  transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s; }
.fk-btn:hover { transform: translateY(-1px); box-shadow: 0 6px 20px rgba(255, 179, 64, .35); }
.fk-btn:active { transform: scale(.96); }

/* ============ 满减 / 折扣白卡 ============ */
.pc { display: flex; flex-direction: column; border-radius: 22px; padding: 22px 24px 18px;
  background: var(--ll-card, #fff); cursor: pointer;
  box-shadow: 0 1px 1px rgba(0, 0, 0, .04), 0 8px 28px rgba(0, 0, 0, .06);
  transition: transform .3s var(--ll-ease-out, ease), box-shadow .3s; }
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
.pc-benefit b { font-size: 36px; font-weight: 700; letter-spacing: -.03em; line-height: 1;
  color: var(--ll-ink, #1d1d1f); font-variant-numeric: tabular-nums; }
.pc-benefit i { font-style: normal; font-size: 16px; font-weight: 600; color: var(--ll-ink, #1d1d1f); }
.pc-benefit small { font-size: 13.5px; color: var(--ll-muted, #6e6e73); }
.pc h3 { margin: 0 0 14px; font-size: 15px; font-weight: 600; color: var(--ll-ink, #1d1d1f); }
.pc-foot { margin-top: auto; padding-top: 12px; border-top: 1px solid var(--ll-border, #e5e5ea);
  display: flex; justify-content: space-between; align-items: center; }
.pc-valid { font-size: 12px; color: var(--ll-muted, #6e6e73); }
.pc-go { font-size: 12.5px; font-weight: 600; color: var(--ll-primary, #1E6EB8); }

/* ============ 积分激励：暖金浅底 ============ */
.gc { display: flex; gap: 16px; border-radius: 22px; padding: 20px 22px 18px; cursor: pointer;
  background: linear-gradient(150deg, #fffaf0, #fff 55%);
  box-shadow: 0 1px 1px rgba(0, 0, 0, .04), 0 8px 28px rgba(0, 0, 0, .05);
  transition: transform .3s var(--ll-ease-out, ease), box-shadow .3s; }
[data-theme='dark'] .gc { background: linear-gradient(150deg, #2a2318, #1c1c1e 55%); }
.gc:hover { transform: translateY(-4px); box-shadow: 0 2px 2px rgba(0,0,0,.04), 0 16px 40px rgba(0,0,0,.09); }
/* iOS 图标风 squircle */
.gc-icon { width: 56px; height: 56px; flex-shrink: 0; display: grid; place-items: center; font-size: 28px;
  border-radius: 16px; background: linear-gradient(160deg, #ffd98a, #f2a33c);
  box-shadow: inset 0 1px 1px rgba(255, 255, 255, .6), 0 6px 14px rgba(201, 126, 18, .28); }
[data-theme='dark'] .gc-icon { box-shadow: inset 0 1px 1px rgba(255, 255, 255, .25), 0 6px 14px rgba(0, 0, 0, .4); }
.gc-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.gc-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.gc-type { font-size: 11px; font-weight: 700; letter-spacing: .14em; color: #a56a0b; }
[data-theme='dark'] .gc-type { color: #e6a23c; }
.gc-points { font-size: 21px; font-weight: 700; letter-spacing: -.02em; color: var(--ll-ink, #1d1d1f); }
.gc-points i { font-style: normal; font-size: 11.5px; font-weight: 600; color: var(--ll-muted, #6e6e73); margin-left: 3px; }
.gc-main h3 { margin: 0 0 4px; font-size: 15px; font-weight: 600; color: var(--ll-ink, #1d1d1f); }
.gc-desc { margin: 0; font-size: 12.5px; line-height: 1.55; color: var(--ll-muted, #6e6e73);
  display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden; }
.gc-foot { margin-top: auto; padding-top: 10px; display: flex; justify-content: space-between; align-items: center; }
.gc-valid { font-size: 12px; color: var(--ll-muted, #6e6e73); }
.gc-btn { border: none; cursor: pointer; font-size: 13px; font-weight: 600; color: #fff;
  padding: 7px 16px; border-radius: 980px; background: #c97e12;
  transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s; }
.gc-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 6px 16px rgba(201, 126, 18, .3); }
.gc-btn:disabled { cursor: not-allowed; opacity: .4; }
.gc-auto { font-size: 12px; color: var(--ll-muted, #6e6e73); }

/* ===== 空态 ===== */
.cp-empty { text-align: center; padding: 72px 0 88px; }
.cp-empty-ico { font-size: 52px; margin-bottom: 14px; }
.cp-empty p { margin: 0 0 20px; font-size: 15px; color: var(--ll-muted, #6e6e73); }

/* ===== 响应式 ===== */
@media (max-width: 860px) {
  .cp-hero { flex-direction: column; align-items: flex-start; gap: 14px; padding-top: 8px; }
  .cp-br { display: inline; }
  .cp-hero-side { width: 100%; justify-content: space-between; }
  .cp-grid { grid-template-columns: 1fr 1fr; gap: 14px; }
  .fk { grid-column: span 2; }
}
@media (max-width: 560px) {
  .cp-grid { grid-template-columns: 1fr; }
  .fk { grid-column: span 1; }
  .fk-cd { font-size: 24px; }
  .cp-hero h1 { font-size: 30px; }
  .cp-seg { width: 100%; display: flex; }
  .cp-seg-item { flex: 1; padding: 7px 0; }
  /* 手机端券票：票根收窄 + 主体内边距收紧，面额字号微缩 */
  .tk { grid-template-columns: 92px 1fr; }
  .tk-stub { padding: 18px 6px; }
  .tk-value b { font-size: 27px; }
  .tk-body { padding: 15px 14px 13px 16px; }
  .tk-btn { padding: 7px 14px; }
  .tk-stamp { right: 70px; }
  /* 秒杀/满减/积分卡：大数字与长文本组合的 flex 行允许换行，避免 min-content 撑宽 */
  .pc-head, .pc-benefit, .pc-foot,
  .gc-head, .gc-foot,
  .fk-benefit, .fk-foot,
  .tk-row1 { flex-wrap: wrap; row-gap: 6px; }
  .pc-benefit b { font-size: 30px; }
  .fk-benefit b { font-size: 34px; }
  .fk { padding: 20px 18px 18px; }
  .fk-count { position: static; text-align: left; margin-bottom: 6px; }
  .fk h3 { font-size: 18px; }
}
</style>
