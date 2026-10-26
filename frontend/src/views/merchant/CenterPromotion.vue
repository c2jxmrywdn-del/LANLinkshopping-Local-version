<template>
  <div class="ad-page">
    <section class="hero">
      <div class="hero-copy">
        <div class="eyebrow"><span class="dot"></span> 商户中心</div>
        <h1>流量推广</h1>
        <p>购买广告流量让商品出现在商城推广位：按点击计费（¥{{ cpc }}/次），预算随时可停，剩余金额自动结算退回钱包。</p>
      </div>
      <div class="hero-actions">
        <a-button size="large" type="primary" class="pill" @click="openCreate">＋ 新建推广</a-button>
      </div>
    </section>

    <!-- ===== Bento 仪表盘（参考 21st.dev Finance Bento / Spotlight Card / Progress Ring） ===== -->
    <section class="bento" @pointermove="onSpotlight">
      <!-- 主卡：活跃推广概览（预算消耗环形进度） -->
      <div class="tile tile-hero" v-if="activeCampaign">
        <div class="tile-head">
          <span class="tile-label">推广中 · {{ activeCampaign.campaign.planName }}</span>
          <a-tag color="green">投放中</a-tag>
        </div>
        <div class="hero-body">
          <div class="ring-wrap">
            <svg viewBox="0 0 120 120" class="ring">
              <circle cx="60" cy="60" r="52" class="ring-bg" />
              <circle cx="60" cy="60" r="52" class="ring-fg"
                      :style="{ strokeDasharray: ringDash, strokeDashoffset: ringOffset }" />
            </svg>
            <div class="ring-center">
              <b>{{ spentPct }}<i>%</i></b>
              <span>预算已用</span>
            </div>
          </div>
          <div class="hero-facts">
            <div class="fact">
              <span>剩余预算</span>
              <b>¥{{ money(activeCampaign.remainBudget) }}</b>
            </div>
            <div class="fact">
              <span>已获点击</span>
              <b>{{ activeCampaign.campaign.clicks }}</b>
            </div>
            <div class="fact">
              <span>剩余天数</span>
              <b>{{ daysLeft(activeCampaign.campaign) }} 天</b>
            </div>
            <div class="hero-ops">
              <a-button size="small" @click="pause(activeCampaign)">暂停投放</a-button>
              <a-button size="small" danger @click="terminate(activeCampaign)">结束并结算</a-button>
            </div>
          </div>
          <img v-if="activeCampaign.prodCover" class="hero-cover" :src="activeCampaign.prodCover" alt="" loading="lazy" />
        </div>
      </div>
      <!-- 无活跃计划：引导卡 -->
      <div class="tile tile-hero tile-guide" v-else>
        <div class="tile-head"><span class="tile-label">还没有进行中的推广</span></div>
        <div class="guide-body">
          <div class="guide-ico"><Rocket :size="28" class="text-sky-500" /></div>
          <div>
            <b>让买家第一眼看到你的商品</b>
            <p>推广商品将出现在商城列表的显眼广告位（带「推广」标识），按点击计费、花不完自动退。</p>
          </div>
        </div>
        <a-button type="primary" block class="guide-btn" @click="openCreate">立即新建推广</a-button>
      </div>

      <!-- KPI 小卡 ×4 -->
      <div class="tile">
        <div class="tile-head"><span class="tile-label">累计花费</span><span class="tile-ico">¥</span></div>
        <div class="tile-num">¥{{ money(stats.totalSpent) }}</div>
        <div class="tile-foot">结算退回 ¥{{ money(stats.totalSettled) }}</div>
        <div class="spark"><i :style="{ width: spentSpark + '%' }"></i></div>
      </div>
      <div class="tile">
        <div class="tile-head"><span class="tile-label">总点击</span><span class="tile-ico">◎</span></div>
        <div class="tile-num">{{ stats.totalClicks || 0 }}</div>
        <div class="tile-foot">单价 ¥{{ cpc }}/次</div>
        <div class="spark"><i :style="{ width: ctrSpark + '%' }"></i></div>
      </div>
      <div class="tile">
        <div class="tile-head"><span class="tile-label">总曝光</span><span class="tile-ico">◍</span></div>
        <div class="tile-num">{{ stats.totalImpressions || 0 }}</div>
        <div class="tile-foot">CTR {{ stats.ctr || 0 }}%</div>
        <div class="spark"><i :style="{ width: impSpark + '%' }"></i></div>
      </div>
      <div class="tile tile-wallet">
        <div class="tile-head"><span class="tile-label">钱包余额</span><span class="tile-ico"><Wallet :size="16" /></span></div>
        <div class="tile-num">¥{{ money(stats.walletBalance) }}</div>
        <div class="tile-foot"><a @click="$router.push('/wallet')">充值 →</a></div>
      </div>
    </section>

    <!-- 计划列表 -->
    <a-card :bordered="false" class="panel">
      <template #title>
        <div class="panel-title">
          <div><strong>推广计划</strong><small>预算冻结于钱包，点击实时扣减，结束即结算</small></div>
          <a-button type="link" @click="load">刷新</a-button>
        </div>
      </template>
      <a-table :data-source="rows" :columns="cols" row-key="campaignId" :loading="loading"
               class="mc-table" :scroll="{ x: 1080 }"
               :pagination="{ pageSize: 10, showSizeChanger: false }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'prod'">
            <div class="prod-cell">
              <span class="thumb">
                <img v-if="record.prodCover" :src="record.prodCover" alt="" loading="lazy" />
                <i v-else>商</i>
              </span>
              <span class="pt">
                <b>{{ record.campaign.planName }}</b>
                <small>{{ record.prodTitle }}</small>
              </span>
            </div>
          </template>
          <template v-else-if="column.key === 'budget'">
            <div class="budget-cell">
              <b>¥{{ money(record.campaign.totalBudget) }}</b>
              <div class="budget-track"><i :style="{ width: pct(record) + '%' }"></i></div>
              <small>已花 ¥{{ money(record.campaign.spent) }} · 余 ¥{{ money(record.remainBudget) }}</small>
            </div>
          </template>
          <template v-else-if="column.key === 'effect'">
            <div class="effect-cell">
              <span>曝光 {{ record.campaign.impressions }}</span>
              <span>点击 {{ record.campaign.clicks }}</span>
            </div>
          </template>
          <template v-else-if="column.key === 'status'">
            <a-tag :color="statusColor(record.campaign)">{{ statusText(record.campaign) }}</a-tag>
            <div v-if="record.campaign.status === 'active'" class="time-left">剩 {{ daysLeft(record.campaign) }} 天</div>
          </template>
          <template v-else-if="column.key === 'op'">
            <a-space :size="0">
              <a-button v-if="record.campaign.status === 'pending_payment'" type="link" size="small"
                        @click="pay(record)">支付</a-button>
              <a-button v-if="record.campaign.status === 'active'" type="link" size="small"
                        @click="pause(record)">暂停</a-button>
              <a-button v-if="record.campaign.status === 'paused'" type="link" size="small"
                        @click="resume(record)">恢复</a-button>
              <a-button v-if="['active','paused'].includes(record.campaign.status)" type="link" size="small"
                        @click="terminate(record)">结束</a-button>
              <a-button v-if="record.campaign.status === 'ended'" type="link" size="small"
                        @click="renew(record)">再来一单</a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- 新建推广弹窗 -->
    <a-modal v-model:open="createOpen" title="新建推广计划" :confirm-loading="creating" ok-text="创建（待支付）"
             cancel-text="取消" :ok-button-props="{ disabled: !form.prodId || !form.totalBudget || !form.durationDays }"
             @ok="submitCreate" destroy-on-close>
      <a-alert type="info" show-icon class="tip"
               :message="`按点击计费 ¥${cpc}/次，总预算支付后冻结，结束自动退回余额`" />
      <a-form layout="vertical">
        <a-form-item label="推广商品" required>
          <a-select v-model:value="form.prodId" placeholder="选择在售商品" :loading="prodsLoading">
            <a-select-option v-for="p in prods" :key="p.prodId" :value="p.prodId">
              {{ p.title }}（销量 {{ p.sales }}）
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-row :gutter="12">
          <a-col :span="12">
            <a-form-item label="总预算（元）" required>
              <a-input-number v-model:value="form.totalBudget" :min="5" :max="50000" :precision="2"
                              style="width:100%" placeholder="5 - 50000" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="投放天数" required>
              <a-input-number v-model:value="form.durationDays" :min="1" :max="90" :precision="0"
                              style="width:100%" placeholder="1 - 90" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="预计可得">
          <div class="estimate">约 <b>{{ estClicks }}</b> 次点击 · 钱包余额 ¥{{ money(stats.walletBalance) }}
            <span v-if="insufficient" class="insufficient">（余额不足，<a @click="$router.push('/wallet'); createOpen = false">去充值</a>）</span>
          </div>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { Rocket, Wallet } from 'lucide-vue-next'
import { adApi, productApi } from '../../api'
import { fmtMoney } from '../../utils/merchantStatus'

const money = fmtMoney
const loading = ref(true)
const rows = ref([])
const stats = ref({})
const cpc = computed(() => Number(stats.value.cpcPrice || 0.5).toFixed(2))

const cols = [
  { title: '计划 / 商品', key: 'prod', width: 240 },
  { title: '预算与消耗', key: 'budget', width: 220 },
  { title: '效果', key: 'effect', width: 130 },
  { title: '状态', key: 'status', width: 110 },
  { title: '操作', key: 'op', width: 130 }
]

// ===== Bento 数据 =====
const activeCampaign = computed(() => rows.value.find(r => r.campaign.status === 'active'))
const spentPct = computed(() => pct(activeCampaign.value || { campaign: {} }))
/** 环形进度（周长 2πr ≈ 326.7）：消耗百分比 */
const ringDash = '326.7'
const ringOffset = computed(() => (326.7 * (1 - spentPct.value / 100)).toFixed(1))
const spentSpark = computed(() => Math.min(100, Math.round(Number(stats.value.totalSpent || 0) / Math.max(Number(stats.value.totalSpent || 0) + Number(stats.value.totalSettled || 0), 1) * 100)))
const ctrSpark = computed(() => Math.min(100, Math.round(Number(stats.value.ctr || 0) * 10)))
const impSpark = computed(() => Math.min(100, Math.round(Number(stats.value.totalClicks || 0) / Math.max(Number(stats.value.totalImpressions || 1), 1) * 100)))

/** Spotlight 追光：卡片跟随指针的柔和光晕（21st.dev Spotlight Card 模式，纯 CSS 变量实现） */
function onSpotlight(e) {
  const tile = e.target.closest('.tile')
  if (!tile) return
  const r = tile.getBoundingClientRect()
  tile.style.setProperty('--mx', (e.clientX - r.left) + 'px')
  tile.style.setProperty('--my', (e.clientY - r.top) + 'px')
}

function pct(r) {
  const total = Number(r.campaign.totalBudget || 0)
  if (!total) return 0
  return Math.min(100, Math.round(Number(r.campaign.spent || 0) / total * 100))
}
function daysLeft(c) {
  if (!c.endTime) return '—'
  const ms = new Date(c.endTime.replace(' ', 'T')) - Date.now()
  return Math.max(0, Math.ceil(ms / 86400000))
}
function statusText(c) {
  return { pending_payment: '待支付', active: '投放中', paused: '已暂停', ended: '已结束' }[c.status] || c.status
}
function statusColor(c) {
  return { pending_payment: 'orange', active: 'green', paused: 'default', ended: 'blue' }[c.status] || 'default'
}

async function load() {
  loading.value = true
  try {
    const [list, s] = await Promise.all([adApi.my(), adApi.stats()])
    rows.value = list.records || []
    stats.value = s || {}
  } catch (e) { /* 拦截器已提示 */ }
  loading.value = false
}

// ===== 新建 =====
const createOpen = ref(false)
const creating = ref(false)
const prods = ref([])
const prodsLoading = ref(false)
const form = reactive({ prodId: null, totalBudget: null, durationDays: 7 })

const estClicks = computed(() => {
  const b = Number(form.totalBudget || 0)
  return b > 0 ? Math.floor(b / Number(cpc.value)) : 0
})
const insufficient = computed(() =>
  Number(form.totalBudget || 0) > Number(stats.value.walletBalance || 0))

function openCreate() {
  Object.assign(form, { prodId: null, totalBudget: null, durationDays: 7 })
  createOpen.value = true
  if (!prods.value.length) loadProds()
}
async function loadProds() {
  prodsLoading.value = true
  try {
    const page = await productApi.myList({ current: 1, size: 50, status: 1, reviewStatus: 1 })
    prods.value = page.records || []
  } catch (e) { /* 拦截器已提示 */ }
  prodsLoading.value = false
}
async function submitCreate() {
  creating.value = true
  try {
    await adApi.create({ ...form })
    message.success('计划已创建，请支付预算')
    createOpen.value = false
    load()
  } catch (e) { /* 拦截器已提示 */ }
  creating.value = false
}

// ===== 操作 =====
async function pay(r) {
  Modal.confirm({
    title: `支付推广预算 ¥${money(r.campaign.totalBudget)}`,
    content: `从钱包余额冻结（当前余额 ¥${money(stats.walletBalance)}），投放期间按点击扣减，结束退回剩余部分。`,
    okText: '确认支付', cancelText: '取消',
    onOk: async () => {
      try {
        await adApi.pay(r.campaign.campaignId)
        message.success('支付成功，推广已上线')
        load()
      } catch (e) { /* 拦截器已提示 */ }
    }
  })
}
async function pause(r) {
  try { await adApi.pause(r.campaign.campaignId); message.success('已暂停'); load() } catch (e) {}
}
async function resume(r) {
  try { await adApi.resume(r.campaign.campaignId); message.success('已恢复投放'); load() } catch (e) {}
}
function terminate(r) {
  Modal.confirm({
    title: '结束该推广计划？',
    content: `剩余预算 ¥${money(r.remainBudget)} 将立即结算退回钱包。`,
    okText: '结束并结算', cancelText: '取消',
    onOk: async () => {
      try {
        await adApi.terminate(r.campaign.campaignId)
        message.success('已结束，剩余预算退回钱包')
        load()
      } catch (e) { /* 拦截器已提示 */ }
    }
  })
}
function renew(r) {
  Object.assign(form, { prodId: r.campaign.prodId, totalBudget: r.campaign.totalBudget, durationDays: r.campaign.durationDays })
  createOpen.value = true
  if (!prods.value.length) loadProds()
}

onMounted(load)
</script>

<style scoped>
.ad-page { display: flex; flex-direction: column; gap: 16px; }
.hero { display: flex; justify-content: space-between; align-items: flex-end; gap: 14px; padding: 6px 2px 2px; }
.eyebrow { font-size: 12px; color: var(--mc-muted, #6e6e73); margin-bottom: 7px; letter-spacing: .04em; }
.dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: var(--mc-chart-solid, #38bdf8); margin-right: 7px; }
.hero h1 { margin: 0; font-size: 28px; font-weight: 750; letter-spacing: -.03em; color: var(--mc-ink, #1d1d1f); }
.hero p { margin: 8px 0 0; color: var(--mc-muted, #6e6e73); font-size: 14px; line-height: 1.7; }
.pill { border-radius: 999px; height: 40px; padding-inline: 18px; }

/* ===== Bento：4 列网格，主卡占两列两行；卡片带追光光晕与迷你火花条 ===== */
.bento { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.tile { --mx: 50%; --my: 50%;
  position: relative; overflow: hidden; border-radius: 18px; padding: 16px 17px;
  background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1);
  box-shadow: 0 2px 14px rgba(20, 24, 34, .04);
  display: flex; flex-direction: column; min-height: 118px; }
/* Spotlight：指针处柔光（品牌天蓝，径向渐变跟随 --mx/--my） */
.tile::before { content: ""; position: absolute; inset: 0; pointer-events: none; z-index: 0;
  background: radial-gradient(220px circle at var(--mx) var(--my), rgba(56, 189, 248, .14), transparent 65%);
  opacity: 0; transition: opacity .25s ease; }
.tile:hover::before { opacity: 1; }
.tile > * { position: relative; z-index: 1; }

.tile-hero { grid-column: span 2; grid-row: span 2; min-height: 264px;
  background: linear-gradient(150deg, #f0f9ff 0%, var(--mc-card, #fff) 55%);
  border-color: rgba(14, 165, 233, .2); }
.tile-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.tile-label { font-size: 12px; color: var(--mc-muted, #6e6e73); }
.tile-ico { color: var(--mc-accent, #9b2d20); font-size: 14px; }
.tile-num { font-size: 24px; font-weight: 750; letter-spacing: -.02em; color: var(--mc-ink, #1d1d1f); margin: 8px 0 4px; }
.tile-foot { font-size: 11px; color: var(--mc-muted2, #86868b); margin-top: auto; }
.tile-foot a { color: var(--mc-accent, #9b2d20); cursor: pointer; }
/* 迷你火花条（装饰性比例指示，纯 CSS） */
.spark { height: 4px; border-radius: 99px; background: var(--mc-border-soft, #f0eee9); margin-top: 8px; overflow: hidden; }
.spark i { display: block; height: 100%; border-radius: 99px;
  background: linear-gradient(90deg, #7dd3fc, #38bdf8); transition: width .5s ease; }
.tile-wallet { border-color: rgba(200, 164, 92, .35);
  background: linear-gradient(150deg, #fdf9ef, var(--mc-card, #fff) 60%); }

/* 主卡：环形进度 + 事实列 + 商品图 */
.hero-body { display: flex; gap: 18px; align-items: center; flex: 1; }
.ring-wrap { position: relative; width: 128px; height: 128px; flex-shrink: 0; }
.ring { width: 100%; height: 100%; transform: rotate(-90deg); }
.ring-bg { fill: none; stroke: rgba(14, 165, 233, .14); stroke-width: 11; }
.ring-fg { fill: none; stroke: #38bdf8; stroke-width: 11; stroke-linecap: round;
  transition: stroke-dashoffset .6s ease; }
.ring-center { position: absolute; inset: 0; display: grid; place-content: center; text-align: center; }
.ring-center b { font-size: 26px; font-weight: 750; letter-spacing: -.03em; color: var(--mc-ink, #1d1d1f); }
.ring-center b i { font-style: normal; font-size: 13px; color: var(--mc-faint, #a1a1a6); }
.ring-center span { font-size: 10.5px; color: var(--mc-muted2, #86868b); }
.hero-facts { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 10px; }
.fact { display: flex; justify-content: space-between; align-items: baseline; border-bottom: 1px dashed var(--mc-border-soft, #f0eee9); padding-bottom: 7px; }
.fact span { font-size: 12px; color: var(--mc-muted, #6e6e73); }
.fact b { font-size: 16px; font-weight: 700; letter-spacing: -.01em; color: var(--mc-ink, #1d1d1f); }
.hero-ops { display: flex; gap: 8px; margin-top: 2px; }
.hero-cover { width: 92px; height: 92px; border-radius: 14px; object-fit: cover; flex-shrink: 0;
  box-shadow: 0 6px 18px rgba(20, 24, 34, .1); }

/* 引导卡 */
.tile-guide .guide-body { display: flex; gap: 14px; align-items: flex-start; flex: 1; }
.guide-ico { width: 52px; height: 52px; border-radius: 16px; display: grid; place-items: center;
  font-size: 26px; background: linear-gradient(135deg, #e0f2fe, #bae6fd); flex-shrink: 0; }
.guide-body b { display: block; font-size: 15px; color: var(--mc-ink, #1d1d1f); margin-bottom: 5px; letter-spacing: -.01em; }
.guide-body p { margin: 0; font-size: 12.5px; color: var(--mc-muted, #6e6e73); line-height: 1.7; }
.guide-btn { margin-top: 14px; border-radius: 999px; }

.panel { border-radius: 16px; border: 1px solid var(--mc-border, #e8e6e1); box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.panel :deep(.ant-card-head) { border-bottom: 1px solid var(--mc-border-soft, #f0eee9); padding: 0 20px; min-height: 58px; }
.panel :deep(.ant-card-body) { padding: 16px 20px; }
.panel-title { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.panel-title strong { display: block; font-size: 15px; color: var(--mc-ink, #1d1d1f); letter-spacing: -.01em; }
.panel-title small { display: block; color: var(--mc-faint, #a1a1a6); font-size: 11px; margin-top: 3px; }

.prod-cell { display: flex; gap: 11px; align-items: center; }
.thumb { width: 44px; height: 44px; border-radius: 10px; background: var(--mc-soft, #f5f5f7); display: grid; place-items: center; overflow: hidden; flex-shrink: 0; }
.thumb img { width: 100%; height: 100%; object-fit: cover; }
.thumb i { font-style: normal; color: var(--mc-faint, #b9c3d4); font-size: 15px; }
.pt { min-width: 0; }
.pt b { display: block; font-size: 13px; color: var(--mc-ink, #1d1d1f); font-weight: 500; max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pt small { color: var(--mc-faint, #a1a1a6); font-size: 11.5px; }

.budget-cell b { font-size: 13px; color: var(--mc-ink, #1d1d1f); }
.budget-track { height: 5px; background: var(--mc-border-soft, #f0eee9); border-radius: 99px; margin: 6px 0 5px; overflow: hidden; }
.budget-track i { display: block; height: 100%; background: var(--mc-chart-solid, #38bdf8); border-radius: 99px; }
.budget-cell small { color: var(--mc-muted2, #86868b); font-size: 11px; }

.effect-cell span { display: block; font-size: 12.5px; color: var(--mc-text2, #3a3a3c); }
.effect-cell span + span { color: var(--mc-muted2, #86868b); font-size: 11.5px; margin-top: 2px; }
.time-left { font-size: 11px; color: var(--mc-faint, #a1a1a6); margin-top: 3px; }

.tip { margin-bottom: 16px; border-radius: 12px; }
.estimate { font-size: 13px; color: var(--mc-text2, #3a3a3c); }
.estimate b { color: #0284c7; font-size: 16px; }
.insufficient { color: #c2410c; }
.insufficient a { color: #c2410c; font-weight: 600; cursor: pointer; text-decoration: underline; }

/* ===== 手机适配：主卡全宽纵排，KPI 两列，环形图缩小 ===== */
@media (max-width: 991px) {
  .bento { grid-template-columns: repeat(2, 1fr); }
  .tile-hero { grid-column: span 2; grid-row: auto; min-height: 0; }
}
@media (max-width: 700px) {
  .hero { flex-direction: column; align-items: flex-start; }
  .hero h1 { font-size: 22px; }
  .hero-actions { width: 100%; }
  .hero-actions .pill { width: 100%; }
  .bento { grid-template-columns: 1fr 1fr; gap: 10px; }
  .tile-hero { grid-column: span 2; padding: 14px; }
  .hero-body { flex-direction: column; align-items: stretch; gap: 14px; }
  .ring-wrap { width: 108px; height: 108px; margin: 0 auto; }
  .hero-cover { display: none; }
  .hero-ops { justify-content: space-between; }
  .hero-ops .ant-btn { flex: 1; }
  .tile { padding: 13px; min-height: 104px; }
  .tile-num { font-size: 19px; }
  .panel :deep(.ant-card-body) { padding: 14px; }
  .pt b { max-width: 150px; }
}
</style>
