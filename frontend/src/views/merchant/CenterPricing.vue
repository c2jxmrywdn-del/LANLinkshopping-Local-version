<template>
  <div class="pp-page">
    <div class="pp-head">
      <div>
        <h2>价格监控</h2>
        <p>对标行业行情基准，波动早知道，调价有依据。</p>
      </div>
      <a-select v-model:value="selectedProd" style="min-width: 240px" show-search
                option-filter-prop="label" placeholder="选择监控商品" @change="loadDetail">
        <a-select-option v-for="p in overview" :key="p.prodId" :value="p.prodId" :label="p.title">
          {{ p.title }}
        </a-select-option>
      </a-select>
    </div>

    <!-- KPI 行 -->
    <div class="pp-kpis">
      <div class="kpi"><b><NumberTicker :value="overview.length" /></b><span>监控商品</span></div>
      <div class="kpi"><b><NumberTicker :value="totalChanges" /></b><span>近30日调价</span></div>
      <div class="kpi" :class="{ warn: anomalyCount > 0 }"><b><NumberTicker :value="anomalyCount" /></b><span>异常异动</span></div>
      <div class="kpi"><b><NumberTicker :value="totalAlerts" /></b><span>生效预警</span></div>
    </div>

    <!-- 走势 + 建议 -->
    <a-spin :spinning="detailLoading">
      <div class="pp-grid" v-if="selectedProd">
        <div class="pp-card pp-trend">
          <div class="pp-card-head">
            <b>价格走势 vs 行业基准</b>
            <a-radio-group v-model:value="days" size="small" @change="loadDetail">
              <a-radio-button :value="7">7天</a-radio-button>
              <a-radio-button :value="30">30天</a-radio-button>
              <a-radio-button :value="90">90天</a-radio-button>
            </a-radio-group>
          </div>
          <PriceChart v-if="chartSeries.length" :series="chartSeries" :height="250" />
          <a-empty v-else-if="!detailLoading" description="暂无价格留痕，调价后自动生成走势" :image-style="{ height: '48px' }" />
          <div class="pp-trend-meta" v-if="analysis">
            <span>现价 <b>¥{{ money(analysis.currentPrice) }}</b></span>
            <span v-if="analysis.benchmarkPrice">行业基准 <b>¥{{ money(analysis.benchmarkPrice) }}</b></span>
            <span v-if="analysis.deviationPct !== null">偏离 <b :class="devClass">{{ analysis.deviationPct }}%</b></span>
            <span v-if="analysis.volatilityPct !== null">基准波动 <b>{{ analysis.volatilityPct }}%</b></span>
          </div>
        </div>

        <div class="pp-card pp-advice" v-if="analysis">
          <div class="pp-card-head"><b>智能调价建议</b></div>
          <div class="advice-badge" :class="'lv-' + analysis.level">{{ levelText(analysis.level) }}</div>
          <p class="advice-text">{{ analysis.advice }}</p>
          <div class="advice-meta" v-if="analysis.changeCount !== undefined">
            {{ analysis.changeCount }} · 调价次数（近30日）
          </div>
          <!-- 快捷预警 -->
          <div class="advice-alert">
            <div class="advice-alert-head">价格预警</div>
            <div class="alert-form">
              <a-select v-model:value="alertCond" size="small" style="width: 128px">
                <a-select-option value="BELOW">价格低于</a-select-option>
                <a-select-option value="ABOVE">价格高于</a-select-option>
                <a-select-option value="DROP_PCT">单次降幅≥%</a-select-option>
                <a-select-option value="RISE_PCT">单次涨幅≥%</a-select-option>
              </a-select>
              <a-input-number v-model:value="alertThreshold" size="small" :min="0.01" :precision="2" style="flex:1" />
              <a-button type="primary" size="small" :loading="alertSaving" @click="addAlert">订阅</a-button>
            </div>
            <div class="alert-list" v-if="prodAlerts.length">
              <div v-for="a in prodAlerts" :key="a.id" class="alert-row">
                <span>{{ condText(a.conditionType) }} {{ a.threshold }}</span>
                <a type="danger" @click="removeAlert(a.id)">取消</a>
              </div>
            </div>
            <p class="alert-hint" v-else>暂无订阅；命中后站内消息通知。</p>
          </div>
        </div>
      </div>
    </a-spin>

    <!-- 商品总览表 -->
    <div class="pp-card pp-table-card">
      <div class="pp-card-head"><b>商品价格总览（近30日）</b></div>
      <a-table :data-source="overview" :columns="columns" row-key="prodId" size="small"
               :pagination="{ pageSize: 8 }" :scroll="{ x: 720 }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'title'">
            <a class="pp-prod-link" @click="selectProd(record.prodId)">{{ record.title }}</a>
          </template>
          <template v-else-if="column.key === 'price'">¥{{ money(record.price) }}</template>
          <template v-else-if="column.key === 'lastChangePct'">
            <span v-if="record.lastChangePct !== null && record.lastChangePct !== undefined"
                  :class="record.lastChangePct > 0 ? 'up' : 'down'">
              {{ record.lastChangePct > 0 ? '+' : '' }}{{ record.lastChangePct }}%
            </span>
            <span v-else class="muted">—</span>
          </template>
          <template v-else-if="column.key === 'anomaly'">
            <a-tag v-if="record.anomaly" color="red">异常异动</a-tag>
            <a-tag v-else color="default">正常</a-tag>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-button type="link" size="small" @click="selectProd(record.prodId)">分析</a-button>
          </template>
        </template>
      </a-table>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { priceMonitorApi } from '../../api'
import PriceChart from '../../components/ui/PriceChart.vue'
import NumberTicker from '../../components/ui/NumberTicker.vue'

const overview = ref([])
const selectedProd = ref(null)
const days = ref(30)
const trend = ref(null)
const analysis = ref(null)
const detailLoading = ref(false)
const alertCond = ref('BELOW')
const alertThreshold = ref(null)
const alertSaving = ref(false)
const prodAlerts = ref([])

const columns = [
  { key: 'title', title: '商品', ellipsis: true },
  { key: 'price', title: '现价', width: 110 },
  { key: 'lastChangePct', title: '最近变动', width: 100 },
  { key: 'changeCount30d', title: '30日调价', width: 90 },
  { key: 'alertCount', title: '预警订阅', width: 90 },
  { key: 'anomaly', title: '异动', width: 90 },
  { key: 'action', title: '', width: 70 }
]

const totalChanges = computed(() => overview.value.reduce((s, r) => s + Number(r.changeCount30d || 0), 0))
const anomalyCount = computed(() => overview.value.filter(r => r.anomaly).length)
const totalAlerts = computed(() => overview.value.reduce((s, r) => s + Number(r.alertCount || 0), 0))

const chartSeries = computed(() => {
  if (!trend.value) return []
  const product = (trend.value.series || []).map(x => ({ label: x.day, value: Number(x.price) }))
  // 补当前价作为尾点，走势延伸到"今天"
  if (trend.value.product?.price && product.length) {
    const today = new Date().toISOString().slice(0, 10)
    if (!product.some(p => p.label === today)) {
      product.push({ label: today, value: Number(trend.value.product.price) })
    }
  }
  const bench = (trend.value.benchmark || []).map(b => ({ label: String(b.day), value: Number(b.benchmark) }))
  const out = []
  if (product.length) out.push({ name: '商品价', color: '#1E6EB8', data: product })
  if (bench.length) out.push({ name: '行业基准', color: '#c97e12', data: bench, dashed: true, width: 1.6, fill: false })
  return out
})

const devClass = computed(() => {
  const d = Number(analysis.value?.deviationPct || 0)
  return d > 0 ? 'up' : d < 0 ? 'down' : ''
})

const levelText = (l) => ({ HOLD: '建议保持', RAISE: '关注上调空间', CUT: '具备让利空间', URGENT: '显著偏离市场' }[l] || l)
const condText = (c) => ({ BELOW: '价格低于', ABOVE: '价格高于', DROP_PCT: '单次降幅≥', RISE_PCT: '单次涨幅≥' }[c] || c)
const money = (v) => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

async function loadOverview() {
  try {
    overview.value = (await priceMonitorApi.merchantOverview()) || []
    if (!selectedProd.value && overview.value.length) {
      // 默认选中最近有异动或调价最多的商品
      const hot = [...overview.value].sort((a, b) =>
        (b.anomaly ? 1 : 0) - (a.anomaly ? 1 : 0) || Number(b.changeCount30d || 0) - Number(a.changeCount30d || 0))[0]
      selectProd(hot.prodId)
    }
  } catch (e) { /* 拦截器已提示 */ }
}

async function loadDetail() {
  if (!selectedProd.value) return
  detailLoading.value = true
  try {
    const [t, a, alerts] = await Promise.all([
      priceMonitorApi.trend(selectedProd.value, days.value),
      priceMonitorApi.analysis(selectedProd.value),
      priceMonitorApi.productAlerts(selectedProd.value)
    ])
    trend.value = t
    analysis.value = a
    prodAlerts.value = alerts || []
  } catch (e) { /* 拦截器已提示 */ } finally { detailLoading.value = false }
}

function selectProd(id) {
  selectedProd.value = id
  loadDetail()
}

async function addAlert() {
  if (!alertThreshold.value) return message.warning('请填写预警阈值')
  alertSaving.value = true
  try {
    await priceMonitorApi.subscribe({ prodId: selectedProd.value, conditionType: alertCond.value, threshold: alertThreshold.value })
    message.success('预警已订阅')
    alertThreshold.value = null
    prodAlerts.value = (await priceMonitorApi.productAlerts(selectedProd.value)) || []
  } catch (e) { /* 拦截器已提示 */ } finally { alertSaving.value = false }
}

async function removeAlert(id) {
  try {
    await priceMonitorApi.cancelAlert(id)
    prodAlerts.value = prodAlerts.value.filter(a => a.id !== id)
  } catch (e) { /* 拦截器已提示 */ }
}

onMounted(loadOverview)
</script>

<style scoped>
.pp-page { padding: 6px 2px; }
.pp-head { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 18px; flex-wrap: wrap; }
.pp-head h2 { margin: 0 0 4px; font-size: 22px; letter-spacing: -.02em; color: var(--mc-ink, var(--ll-ink, #1d1d1f)); }
.pp-head p { margin: 0; font-size: 13px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }

.pp-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px; }
.kpi { background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1); border-radius: 14px; padding: 14px 18px; }
.kpi b { display: block; font-size: 24px; font-weight: 700; letter-spacing: -.02em; color: var(--mc-ink, var(--ll-ink, #1d1d1f)); font-variant-numeric: tabular-nums; }
.kpi span { font-size: 12px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }
.kpi.warn b { color: #d93025; }

.pp-grid { display: grid; grid-template-columns: 1.7fr 1fr; gap: 14px; align-items: start; margin-bottom: 16px; }
.pp-card { background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1); border-radius: 16px; padding: 18px 20px; }
.pp-card-head { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.pp-card-head b { font-size: 15px; color: var(--mc-ink, var(--ll-ink, #1d1d1f)); }
.pp-trend-meta { display: flex; gap: 18px; flex-wrap: wrap; margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--mc-border-soft, #f0eee9); font-size: 12.5px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }
.pp-trend-meta b { color: var(--mc-ink, var(--ll-ink, #1d1d1f)); font-variant-numeric: tabular-nums; }
.pp-trend-meta .up { color: #d93025; }
.pp-trend-meta .down { color: #047857; }

.advice-badge { display: inline-block; font-size: 12px; font-weight: 700; padding: 4px 14px; border-radius: 999px; color: #fff; margin-bottom: 10px; }
.lv-HOLD { background: linear-gradient(135deg, #047857, #34d399); }
.lv-RAISE { background: linear-gradient(135deg, #1E6EB8, #38bdf8); }
.lv-CUT { background: linear-gradient(135deg, #b45309, #f2b544); }
.lv-URGENT { background: linear-gradient(135deg, #e0354b, #ff7a59); }
.advice-text { margin: 0 0 10px; font-size: 13px; line-height: 1.7; color: var(--mc-text2, var(--ll-muted, #3a3a3c)); }
.advice-meta { font-size: 12px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); margin-bottom: 12px; }

.advice-alert { border-top: 1px dashed var(--mc-border, #e8e6e1); padding-top: 12px; }
.advice-alert-head { font-size: 13px; font-weight: 700; color: var(--mc-ink, var(--ll-ink, #1d1d1f)); margin-bottom: 8px; }
.alert-form { display: flex; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.alert-row { display: flex; justify-content: space-between; align-items: center; font-size: 12.5px; padding: 5px 0; color: var(--mc-text2, var(--ll-muted, #3a3a3c)); }
.alert-row a { cursor: pointer; }
.alert-hint { margin: 0; font-size: 12px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }

.pp-table-card { margin-bottom: 16px; }
.pp-prod-link { cursor: pointer; }
.pp-prod-link:hover { color: var(--ll-primary, #1E6EB8); }
.up { color: #d93025; font-weight: 600; }
.down { color: #047857; font-weight: 600; }
.muted { color: var(--mc-muted, var(--ll-muted, #6e6e73)); }

@media (max-width: 900px) {
  .pp-grid { grid-template-columns: 1fr; }
  .pp-kpis { grid-template-columns: repeat(2, 1fr); }
}
</style>
