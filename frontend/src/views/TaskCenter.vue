<template>
  <div class="tc-page">
    <!-- Apple 营销页式 Hero（组件系沿用活动中心） -->
    <header class="tc-hero">
      <DotPattern class="tc-dots" :width="18" :height="18" :cr="1.2" />
      <div class="tc-hero-copy">
        <h1>任务中心<span class="tc-dot">。</span></h1>
        <p>做任务赢积分与成长值，<br class="tc-br" />奖励经会员积分闭环自动到账。</p>
      </div>
      <div class="tc-hero-side">
        <div class="tc-stats">
          <div class="tc-stat"><b><NumberTicker :value="tasks.length" /></b><span>进行中任务</span></div>
          <div class="tc-stat"><b><NumberTicker :value="doneCount" /></b><span>今日已完成</span></div>
        </div>
        <div class="tc-hero-links">
          <ShimmerButton class="tc-cta" background="var(--ll-primary, #1E6EB8)"
                         shimmer-color="rgba(255,255,255,.9)" @click="$router.push('/activity')">活动中心</ShimmerButton>
          <button type="button" class="tc-pill plain" @click="$router.push('/membership/points')">积分明细</button>
        </div>
      </div>
    </header>

    <a-spin :spinning="loading">
      <div class="tc-grid" v-if="tasks.length">
        <article v-for="t in tasks" :key="t.taskKey" class="tk-card" :class="{ done: taskState(t) === 'done' }">
          <div class="tk-icon" :class="'ic-' + (t.taskKey === 'share_product' ? 'share' : 'order')">
            <Share2 v-if="t.taskKey === 'share_product'" :size="24" :stroke-width="2" />
            <PackageCheck v-else :size="24" :stroke-width="2" />
          </div>
          <div class="tk-main">
            <div class="tk-head">
              <h3>{{ t.title || taskFallbackTitle(t.taskKey) }}</h3>
              <span class="tk-rewards">
                <b v-if="Number(t.points) > 0">+{{ t.points }}<i>积分</i></b>
                <b v-if="Number(t.growth) > 0">+{{ t.growth }}<i>成长值</i></b>
              </span>
            </div>
            <p class="tk-desc">{{ t.description || taskFallbackDesc(t.taskKey) }}</p>
            <div class="tk-foot">
              <span class="tk-progress" v-if="dailyLimitOf(t) > 0">今日已完成 {{ todayCountOf(t) }}/{{ dailyLimitOf(t) }} 次</span>
              <span class="tk-progress" v-else-if="taskState(t) === 'done'">奖励已自动到账</span>
              <span class="tk-progress" v-else>完成条件后自动发放奖励</span>
              <button v-if="taskState(t) === 'share'" type="button" class="tk-btn"
                      :disabled="acting || taskState(t) !== 'share'" @click="doShare(t)">
                {{ acting ? '上报中…' : '去分享' }}
              </button>
              <button v-else-if="taskState(t) === 'claim' && t.refNo" type="button" class="tk-btn"
                      :disabled="acting" @click="claimTask(t)">
                {{ acting ? '领取中…' : '领取奖励' }}
              </button>
              <span v-else-if="taskState(t) === 'capped'" class="tk-state capped">今日已达上限</span>
              <span v-else-if="taskState(t) === 'done'" class="tk-state done"><Check :size="13" :stroke-width="2.6" /> 已完成</span>
              <span v-else class="tk-state wait">待完成</span>
            </div>
          </div>
        </article>
      </div>
      <div v-else-if="!loading" class="tc-empty">
        <div class="tc-empty-ico"><ListChecks :size="48" class="text-slate-300 dark:text-slate-600 inline-block" /></div>
        <p>暂无可参与的任务，过段时间再来看看</p>
        <div class="tc-empty-actions">
          <button type="button" class="tc-pill" @click="load">刷新试试</button>
          <button type="button" class="tc-pill plain" @click="$router.push('/mall')">先去商城逛逛</button>
        </div>
      </div>
    </a-spin>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { Share2, PackageCheck, Check, ListChecks } from 'lucide-vue-next'
import { taskApi } from '../api'

import DotPattern from '../components/ui/DotPattern.vue'
import NumberTicker from '../components/ui/NumberTicker.vue'
import ShimmerButton from '../components/ui/ShimmerButton.vue'

const router = useRouter()
const tasks = ref([])
const loading = ref(false)
const acting = ref(false)

const doneCount = computed(() => tasks.value.filter(t => taskState(t) === 'done').length)

// 任务目录兜底文案（与后端 TaskCatalog 同目录；后端已提供 title/description 时优先用后端）
function taskFallbackTitle(key) {
  return key === 'share_product' ? '分享商品' : '首单有礼'
}
function taskFallbackDesc(key) {
  return key === 'share_product'
    ? '把商城好物分享给同事或伙伴，每天前 3 次分享可得积分'
    : '完成第一笔订单后，首单奖励积分与成长值自动到账'
}

// ===== 任务态解析（字段防御式读取，兼容后端任务列表的多种口径） =====
function taskState(t) {
  const completed = t.completed === true || t.claimed === true || t.finished === true
    || t.status === 'completed' || t.status === 'claimed' || t.status === 'done'
  if (completed) return 'done'
  const limit = dailyLimitOf(t)
  if (limit > 0 && todayCountOf(t) >= limit) return 'capped'
  if (t.claimable === true && t.refNo) return 'claim'
  return t.taskKey === 'share_product' ? 'share' : 'pending'
}
const dailyLimitOf = (t) => Number(t.dailyLimit) || Number(t.dayLimit) || 0
const todayCountOf = (t) => Number(t.todayCount) || Number(t.countToday) || 0

let loadSeq = 0
async function load() {
  const seq = ++loadSeq
  loading.value = true
  try {
    const res = await taskApi.my()
    if (seq !== loadSeq) return
    tasks.value = Array.isArray(res) ? res : (res?.tasks || res?.list || [])
  } catch (e) {
    if (seq !== loadSeq) return
    tasks.value = []
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

// ===== 分享任务：复制商城链接 + refNo 幂等上报（shareId:yyyyMMdd 口径，每日上限由服务端按当日流水计数） =====
function _pad2(n) { return String(n).padStart(2, '0') }
function shareRefNo() {
  const d = new Date()
  const ymd = `${d.getFullYear()}${_pad2(d.getMonth() + 1)}${_pad2(d.getDate())}`
  const shareId = 'sh-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8)
  return `${shareId}:${ymd}`
}
async function copyText(text) {
  try { await navigator.clipboard.writeText(text); return true } catch (e) { /* 降级 execCommand */ }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch (e) { return false }
}
async function doShare(t) {
  if (acting.value) return
  acting.value = true
  try {
    const link = window.location.origin + '/mall'
    const copied = await copyText(link)
    await taskApi.claim(t.taskKey || 'share_product', shareRefNo())
    message.success(copied ? '链接已复制，分享回执已记录，积分将自动到账' : '分享回执已记录，积分将自动到账')
    load()
  } catch (e) {
    /* 拦截器已提示（每日上限/幂等重复由后端业务错误提示） */
  } finally { acting.value = false }
}

// 奖励待领取（后端改为手动领取口径时）：refNo 幂等上报
async function claimTask(t) {
  if (acting.value) return
  acting.value = true
  try {
    await taskApi.claim(t.taskKey, t.refNo)
    message.success('奖励已到账，可在积分明细查看')
    load()
  } catch (e) {
    /* 拦截器已提示 */
  } finally { acting.value = false }
}

onMounted(load)
</script>

<style scoped>
.tc-page {
  max-width: 1120px;
  margin: 0 auto;
}

/* ===== Hero：与活动中心同款排版 ===== */
.tc-hero {
  position: relative;
  overflow: hidden;
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 24px;
  padding: 20px 4px 26px;
}
.tc-dots { color: rgba(30, 110, 184, .18); }
.tc-hero-copy, .tc-hero-side { position: relative; }
.tc-hero h1 {
  margin: 0;
  font-size: clamp(30px, 4.4vw, 46px);
  font-weight: 700;
  letter-spacing: -.028em;
  line-height: 1.08;
  color: var(--ll-ink, #1d1d1f);
}
.tc-dot { color: var(--ll-primary, #1E6EB8); }
.tc-hero p { margin: 12px 0 0; font-size: 17px; line-height: 1.5; color: var(--ll-muted, #6e6e73); }
.tc-br { display: none; }
.tc-hero-side { display: flex; align-items: center; gap: 22px; flex-shrink: 0; }
.tc-stats { display: flex; gap: 20px; }
.tc-stat { text-align: right; }
.tc-stat b {
  display: block;
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -.03em;
  font-variant-numeric: tabular-nums;
  color: var(--ll-ink, #1d1d1f);
}
.tc-stat span { font-size: 12px; color: var(--ll-muted, #6e6e73); }
.tc-hero-links { display: flex; gap: 10px; }
.tc-cta { padding: 10px 22px; font-size: 14px; }

/* 胶囊按钮：iOS 圆钮 */
.tc-pill {
  border: none;
  cursor: pointer;
  padding: 10px 20px;
  border-radius: 980px;
  font-size: 14px;
  font-weight: 500;
  background: var(--ll-primary, #1E6EB8);
  color: #fff;
  transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s, opacity .25s;
}
.tc-pill:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 6px 18px rgba(30, 110, 184, .3); }
.tc-pill:active:not(:disabled) { transform: scale(.97); }
.tc-pill.plain {
  background: transparent;
  color: var(--ll-primary, #1E6EB8);
  box-shadow: inset 0 0 0 1px var(--ll-border-strong, #d2d2d7);
}

/* ===== 任务卡列表 ===== */
.tc-grid { display: flex; flex-direction: column; gap: 16px; }
.tk-card {
  display: flex;
  gap: 18px;
  padding: 22px 24px 18px;
  border-radius: 22px;
  background: var(--ll-card, #fff);
  box-shadow: 0 1px 1px rgba(0, 0, 0, .04), 0 8px 28px rgba(0, 0, 0, .06);
  transition: transform .3s var(--ll-ease-out, ease), box-shadow .3s;
}
.tk-card:hover { transform: translateY(-3px); box-shadow: 0 2px 2px rgba(0,0,0,.04), 0 16px 40px rgba(0, 0, 0, .09); }
.tk-card.done { opacity: .78; }
.tk-icon {
  width: 56px;
  height: 56px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  border-radius: 16px;
  color: #fff;
}
.ic-order { background: linear-gradient(160deg, #2b7fd4, #1E6EB8 55%, #16609f); box-shadow: 0 6px 14px rgba(30, 110, 184, .28); }
.ic-share { background: linear-gradient(160deg, #22a97a, #047857 55%, #036447); box-shadow: 0 6px 14px rgba(4, 120, 87, .28); }
.tk-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.tk-head { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; }
.tk-head h3 { margin: 0; font-size: 16px; font-weight: 600; letter-spacing: -.01em; color: var(--ll-ink, #1d1d1f); }
.tk-rewards { display: flex; gap: 10px; flex-shrink: 0; }
.tk-rewards b { font-size: 17px; font-weight: 700; color: #b45309; font-variant-numeric: tabular-nums; }
.tk-rewards b i { font-style: normal; font-size: 11px; font-weight: 600; color: var(--ll-muted, #6e6e73); margin-left: 2px; }
.tk-desc { margin: 6px 0 0; font-size: 13px; line-height: 1.6; color: var(--ll-muted, #6e6e73); }
.tk-foot { margin-top: auto; padding-top: 14px; display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.tk-progress { font-size: 12px; color: var(--ll-muted, #6e6e73); font-variant-numeric: tabular-nums; }
.tk-btn {
  border: none;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  padding: 8px 20px;
  border-radius: 980px;
  background: var(--ll-primary, #1E6EB8);
  transition: transform .25s var(--ll-ease-out, ease), box-shadow .25s, opacity .25s;
}
.tk-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 6px 16px rgba(30, 110, 184, .3); }
.tk-btn:active:not(:disabled) { transform: scale(.96); }
.tk-btn:disabled { cursor: not-allowed; opacity: .4; }
.tk-state { display: inline-flex; align-items: center; gap: 4px; font-size: 12.5px; font-weight: 600; }
.tk-state.done { color: #047857; }
.tk-state.capped { color: #a56a0b; }
.tk-state.wait { color: var(--ll-muted, #6e6e73); font-weight: 500; }

/* ===== 空态 ===== */
.tc-empty { text-align: center; padding: 72px 0 88px; }
.tc-empty-ico { display: grid; place-items: center; margin-bottom: 14px; color: var(--ll-muted, #6e6e73); }
.tc-empty p { margin: 0 0 20px; font-size: 15px; color: var(--ll-muted, #6e6e73); }
.tc-empty-actions { display: flex; justify-content: center; gap: 10px; }

/* ===== 移动端响应式（纪律：≤991 / ≤700 两档） ===== */
@media (max-width: 991px) {
  .tc-hero { flex-direction: column; align-items: flex-start; gap: 14px; padding-top: 8px; }
  .tc-br { display: inline; }
  .tc-hero-side { width: 100%; justify-content: space-between; }
}
@media (max-width: 700px) {
  .tc-page { padding-left: 12px; padding-right: 12px; }
  .tc-hero h1 { font-size: 28px; }
  .tk-card { flex-direction: column; gap: 12px; padding: 18px 16px 16px; }
  .tk-icon { width: 44px; height: 44px; border-radius: 12px; }
  .tk-head { flex-direction: column; align-items: flex-start; gap: 4px; }
  .tk-foot { flex-wrap: wrap; row-gap: 8px; }
}
</style>
