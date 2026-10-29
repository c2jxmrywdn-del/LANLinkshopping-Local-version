<template>
  <div class="pi-page">
    <!-- Hero：市场脉搏 -->
    <header class="pi-hero">
      <div class="hero-glow"></div>
      <div class="hero-copy">
        <span class="eyebrow">MARKET INTELLIGENCE · 价格情报台</span>
        <h1>看懂价格背后的<em>风向。</em></h1>
        <p>追踪商品价格与行业基准，结合实时市场资讯与 AI 分析，回答“为什么涨跌、起因、经过、结果”。</p>
        <div class="hero-actions">
          <span class="live-pill"><i class="pulse"></i>实时 · {{ lastRefresh }}</span>
          <a-button size="small" :loading="loadingAll" @click="refreshAll">刷新数据</a-button>
        </div>
      </div>
      <div class="hero-stats">
        <div v-for="s in stats" :key="s.label" class="hero-stat">
          <b>{{ s.value }}</b><span>{{ s.label }}</span>
        </div>
      </div>
    </header>

    <div v-if="news.length" class="pi-tape">
      <span class="pi-tape-label"><i></i>{{ isEn ? 'LIVE' : '快讯' }}</span>
      <Marquee :repeat="2" gap="2.6rem" duration="38s" pause-on-hover>
        <span
          v-for="n in news"
          :key="'tp' + n.id"
          class="tape-item"
          role="button"
          tabindex="0"
          :title="n.title"
          @click="openNews(n)"
          @keydown.enter.prevent="openNews(n)"
        ><i></i>{{ n.title }}<span class="ext" aria-hidden="true">↗</span></span>
      </Marquee>
    </div>

    <div class="pi-grid">
      <!-- 商品监控列表 -->
      <aside class="card product-panel">
        <div class="panel-head">
          <div>
            <span class="kicker">WATCHLIST</span>
            <h3>监控商品</h3>
          </div>
          <a-tag color="blue">{{ filtered.length }}</a-tag>
        </div>
        <a-input v-model:value="query" placeholder="搜索商品名称" allow-clear size="small" class="search" />
        <div class="product-list">
          <button
            v-for="p in filtered"
            :key="p.prodId"
            type="button"
            class="product-item"
            :class="{ active: p.prodId === selectedId }"
            @click="selectProduct(p.prodId)"
          >
            <div class="pi-title">
              <span class="pi-name">{{ p.title }}</span>
              <span v-if="p.anomaly" class="anomaly-dot" title="近30日异常波动">!</span>
            </div>
            <div class="pi-meta">
              <b>¥{{ money(p.price) }}</b>
              <span v-if="p.lastChangePct != null" :class="p.lastChangePct >= 0 ? 'up' : 'down'">
                {{ p.lastChangePct >= 0 ? '+' : '' }}{{ Number(p.lastChangePct).toFixed(2) }}%
              </span>
              <span v-else class="muted">无调价</span>
            </div>
          </button>
          <div v-if="!filtered.length" class="empty">暂无匹配商品</div>
        </div>
      </aside>

      <main class="main-col">
        <!-- 价格走势 -->
        <MagicCard class="card chart-card" :gradient-from="'#C8A45C'" :gradient-to="'#1E6EB8'" :gradient-color="'rgba(30,110,184,.10)'" :gradient-opacity="0.7">
          <div class="chart-head">
            <div class="chart-title">
              <span class="kicker">PRICE TREND</span>
              <h2>{{ selected?.title || '选择商品查看走势' }}</h2>
              <div v-if="selected" class="chart-sub">
                <span class="price">¥<NumberTicker :value="Number(selected.price) || 0" :decimal-places="Number(selected.price) > 0 && Number(selected.price) < 1 ? 4 : 2" :stiffness="220" :damping="30" /></span>
                <span v-if="relativeLevel" class="level" :class="relativeLevel.key">{{ relativeLevel.text }}</span>
                <span v-if="trend?.industryName" class="industry">{{ trend.industryName }}</span>
              </div>
            </div>
            <SegmentedControl v-model="days" :options="rangeOptions" :aria-label="isEn ? 'Range' : '时间范围'" />
          </div>
          <div v-if="trend" class="chart-body">
            <PriceTrendChart :series="trend.series || []" :benchmark="trend.benchmark || []" :height="300" />
          </div>
          <div v-else class="chart-empty">加载走势中…</div>
          <div v-if="analysis" class="chart-foot">
            <span v-if="ai?.relativePct != null">近30日相对行业 <b :class="Number(ai.relativePct) >= 0 ? 'up' : 'down'">{{ Number(ai.relativePct).toFixed(2) }} pct</b></span>
            <span>行业均价 ¥{{ money(analysis.benchmarkPrice) }}</span>
            <span>30日波动率 {{ Number(analysis.volatilityPct ?? 0).toFixed(2) }}%</span>
          </div>
        </MagicCard>

        <div class="two-col">
          <!-- AI 洞察 -->
          <MagicCard class="card ai-card" :gradient-from="'#C8A45C'" :gradient-to="'#1E6EB8'" :gradient-color="'rgba(200,164,92,.10)'" :gradient-opacity="0.7">
            <div class="card-head">
              <div>
                <span class="kicker">AI INSIGHT</span>
                <h3>AI 实时分析</h3>
              </div>
              <div class="ai-actions">
                <a-tag v-if="ai" :color="ai.source === 'AI' ? 'blue' : 'default'">{{ ai.source === 'AI' ? '外部模型' : '规则引擎' }}</a-tag>
                <a-button size="small" :loading="aiLoading" :disabled="!selectedId" @click="loadAi(true)">重新分析</a-button>
              </div>
            </div>
            <div v-if="aiLoading" class="ai-loading"><a-spin size="small" /> AI 正在分析价格与资讯…</div>
            <template v-else-if="ai">
              <p class="ai-summary">{{ ai.summary }}</p>
              <div class="drivers">
                <div v-for="(d, i) in ai.drivers || []" :key="i" class="driver">
                  <span class="impact" :class="d.impact">{{ impactIcon(d.impact) }}</span>
                  <div>
                    <b>{{ d.label }}</b>
                    <p>{{ d.evidence }}</p>
                  </div>
                </div>
              </div>
              <div class="ai-bottom">
                <span class="risk" :class="String(ai.risk || '').toLowerCase()">风险 {{ riskText(ai.risk) }}</span>
                <p class="suggestion">{{ ai.suggestion }}</p>
              </div>
              <p v-if="ai.notice" class="notice">{{ ai.notice }}</p>
            </template>
            <div v-else class="empty-soft">选择商品后点击“重新分析”，生成价格驱动与建议。</div>
          </MagicCard>

          <!-- 因果问答 -->
          <section class="card ask-card">
            <div class="card-head">
              <div>
                <span class="kicker">CAUSAL Q&amp;A</span>
                <h3>问 AI：为什么、起因、经过、结果</h3>
              </div>
            </div>
            <div class="quick-asks">
              <button v-for="q in quickAsks" :key="q" type="button" @click="ask(q)">{{ q }}</button>
            </div>
            <div class="ask-input">
              <a-textarea v-model:value="question" :rows="2" :maxlength="200" placeholder="例如：为什么最近价格上涨？起因、经过和结果分别是什么？" />
              <a-button type="primary" :loading="asking" :disabled="!selectedId" @click="ask(question)">提问</a-button>
            </div>
            <div v-if="askResult" class="answer">
              <div class="answer-head">
                <a-tag :color="askResult.source === 'AI' ? 'blue' : 'default'">{{ askResult.source === 'AI' ? 'AI 回答' : '规则回答' }}</a-tag>
                <span>{{ askResult.question }}</span>
              </div>
              <p class="answer-main">{{ askResult.answer }}</p>
              <div class="answer-grid">
                <div><span>起因</span><p>{{ askResult.cause }}</p></div>
                <div><span>经过</span><p>{{ askResult.process }}</p></div>
                <div><span>结果</span><p>{{ askResult.result }}</p></div>
                <div><span>后续</span><p>{{ askResult.outlook }}</p></div>
              </div>
              <div v-if="askResult.evidence?.length" class="evidence">
                <span>依据</span>
                <i v-for="(e, i) in askResult.evidence" :key="i">{{ e }}</i>
              </div>
            </div>
            <div v-else class="empty-soft">向 AI 提问，获得结构化的因果解释与后续判断。</div>
          </section>
        </div>

        <!-- 预警 -->
        <section class="card alerts-card">
          <div class="card-head">
            <div>
              <span class="kicker">ALERTS</span>
              <h3>价格预警</h3>
            </div>
            <span class="muted">{{ alerts.length }} 条订阅</span>
          </div>
          <div class="alert-create">
            <a-select v-model:value="alertForm.conditionType" size="small" style="width: 150px">
              <a-select-option value="BELOW">低于指定价</a-select-option>
              <a-select-option value="ABOVE">高于指定价</a-select-option>
              <a-select-option value="DROP_PCT">单次降幅 %</a-select-option>
              <a-select-option value="RISE_PCT">单次涨幅 %</a-select-option>
            </a-select>
            <a-input-number v-model:value="alertForm.threshold" :min="0.01" size="small" style="width: 120px" />
            <a-button size="small" type="primary" ghost :disabled="!selectedId" @click="subscribeAlert">为当前商品设置</a-button>
          </div>
          <div v-if="alerts.length" class="alert-list">
            <div v-for="a in alerts" :key="a.id" class="alert-item">
              <span class="alert-title">{{ productTitle(a.prodId) }}</span>
              <span class="alert-cond">{{ condText(a.conditionType) }} {{ money(a.threshold) }}{{ a.conditionType.includes('PCT') ? '%' : '' }}</span>
              <a-button type="link" size="small" danger @click="cancelAlert(a.id)">取消</a-button>
            </div>
          </div>
          <div v-else class="empty-soft">尚未设置预警；价格触发后会通过站内消息通知你。</div>
        </section>
      </main>

      <!-- 市场资讯 -->
      <aside class="card news-panel">
        <div class="panel-head">
          <div>
            <span class="kicker">MARKET NEWS</span>
            <h3>市场资讯 <i class="live-dot"></i></h3>
          </div>
          <a-button type="link" size="small" :loading="newsLoading" @click="loadNews">刷新</a-button>
        </div>
        <div class="news-tabs">
          <SegmentedControl :model-value="newsCategory" :options="newsSegOptions" :aria-label="isEn ? 'Category' : '资讯分类'" @update:model-value="setCategory" />
        </div>
        <div class="news-list">
          <article v-for="n in news" :key="n.id" class="news-item" :class="{ top: n.importance === 3 }">
            <div class="news-tag">
              <span class="cat">{{ categoryText(n.category) }}</span>
              <span v-if="n.importance === 3" class="hot">头条</span>
              <span v-if="newsBadge(n)" class="link-badge" :class="'lt-' + newsLinkType(n)">{{ newsBadge(n) }}</span>
            </div>
            <h4>
              <a
                class="news-title-link"
                :href="newsHref(n)"
                :target="newsExternal(n) ? '_blank' : undefined"
                :rel="newsExternal(n) ? 'noopener noreferrer' : undefined"
                @click="onNewsClick($event, n)"
              >{{ n.title }}<span v-if="newsExternal(n)" class="ext" aria-hidden="true">↗</span></a>
            </h4>
            <p>{{ n.summary }}</p>
            <div class="news-meta">
              <a
                v-if="newsSourceUrl(n)"
                class="news-src"
                :href="newsSourceUrl(n)"
                target="_blank"
                rel="noopener noreferrer"
                :title="isEn ? 'Open source site' : '打开来源站点'"
              >{{ n.source }}<span class="ext" aria-hidden="true">↗</span></a>
              <span v-else>{{ n.source }}</span>
              <span>{{ timeAgo(n.publishedAt) }}</span>
            </div>
          </article>
          <div v-if="!news.length" class="empty">暂无资讯</div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { priceMonitorApi, productApi } from '../api'
import { useUserStore } from '../store/user'
import { locale } from '../i18n'
import {
  isInternalNews,
  newsLinkLabel,
  newsLinkUrl,
  openNewsLink,
  safeHttpUrl
} from '../utils/newsLink'
import PriceTrendChart from '../components/pricemonitor/PriceTrendChart.vue'
import SegmentedControl from '../components/ui/SegmentedControl.vue'
import MagicCard from '../components/ui/MagicCard.vue'
import NumberTicker from '../components/ui/NumberTicker.vue'
import Marquee from '../components/ui/Marquee.vue'

const router = useRouter()
const user = useUserStore()
const lang = computed(() => String(locale.value || '').toLowerCase().startsWith('en') ? 'en' : 'zh')
const products = ref([])
const query = ref('')
const selectedId = ref(null)
const trend = ref(null)
const days = ref(30)
const analysis = ref(null)
const ai = ref(null)
const aiLoading = ref(false)
const question = ref('')
const asking = ref(false)
const askResult = ref(null)
const news = ref([])
const newsCategory = ref('')
const newsLoading = ref(false)
const alerts = ref([])
const loadingAll = ref(false)
const lastRefresh = ref('刚刚')
let newsTimer = null
let adminRows = []

const alertForm = reactive({ conditionType: 'DROP_PCT', threshold: 5 })

const quickAsks = [
  '为什么最近价格会涨/跌？',
  '这次波动的起因是什么？',
  '价格变化的经过是怎样的？',
  '结果和影响如何？',
  '后续价格会怎么走？'
]
const isEn = computed(() => lang.value === 'en')
const newsCategories = [
  { key: '', label: '全部' },
  { key: 'market', label: '市场' },
  { key: 'price', label: '价格' },
  { key: 'supply', label: '供给' },
  { key: 'demand', label: '需求' },
  { key: 'policy', label: '政策' },
  { key: 'international', label: '国际' }
]
// 分段控件（21st.dev Finance Bento .seg 移植）选项
const rangeOptions = computed(() => [30, 60, 90].map(d => ({ value: d, label: `${d}天` })))
// 切换时间范围重新拉取走势（图表数据跟随天数）
watch(days, () => { loadTrend() })
const newsSegOptions = computed(() => newsCategories.map(c => ({ value: c.key, label: c.label })))

const selected = computed(() => products.value.find(p => p.prodId === selectedId.value) || null)
const relativeLevel = computed(() => {
  if (!ai.value || ai.value.relativePct == null) return null
  const r = Number(ai.value.relativePct)
  if (r >= 8) return { key: 'strong', text: '强于行业' }
  if (r <= -8) return { key: 'weak', text: '弱于行业' }
  return { key: 'sync', text: '与行业同步' }
})
const filtered = computed(() => {
  const q = query.value.trim()
  return q ? products.value.filter(p => String(p.title || '').includes(q)) : products.value
})

const stats = computed(() => {
  const changes = user.isMerchant
    ? products.value.reduce((n, p) => n + Number(p.changeCount30d || 0), 0)
    : adminRows.length
  const anomalies = user.isMerchant
    ? products.value.filter(p => p.anomaly).length
    : adminRows.filter(r => Math.abs(Number(r.change_pct || 0)) >= 10).length
  return [
    { label: '监控商品', value: products.value.length },
    { label: '近30日调价', value: changes },
    { label: '异常波动', value: anomalies },
    { label: '市场资讯', value: news.value.length + ' 条' }
  ]
})

function money(v) {
  if (v == null || v === '') return '—'
  return Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}
function signPct(v) {
  if (v == null) return '—'
  const n = Number(v)
  return (n > 0 ? '+' : '') + n.toFixed(2) + '%'
}
function levelText(l) {
  return { HOLD: '建议保持', RAISE: '建议上调', CUT: '建议下调', URGENT: '显著偏离' }[l] || l || '—'
}
function riskText(r) {
  return { HIGH: '高', MEDIUM: '中', LOW: '低' }[r] || r || '—'
}
function impactIcon(i) {
  return i === 'up' ? '↑' : i === 'down' ? '↓' : '•'
}
function categoryText(c) {
  return { policy: '政策', supply: '供给', demand: '需求', price: '价格', market: '市场', tech: '技术', international: '国际' }[c] || '市场'
}
function condText(t) {
  return { BELOW: '低于', ABOVE: '高于', DROP_PCT: '单次降幅 ≥', RISE_PCT: '单次涨幅 ≥' }[t] || t
}
function productTitle(prodId) {
  return products.value.find(p => p.prodId === prodId)?.title || ('商品 #' + prodId)
}
function timeAgo(v) {
  if (!v) return ''
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return String(v).slice(5, 16).replace('T', ' ')
  const diff = Date.now() - d.getTime()
  if (diff < 60 * 1000) return '刚刚'
  if (diff < 3600 * 1000) return Math.floor(diff / 60000) + ' 分钟前'
  if (diff < 86400 * 1000) return Math.floor(diff / 3600000) + ' 小时前'
  return Math.floor(diff / 86400000) + ' 天前'
}

/* ===== 资讯源头链接（与首页共用 utils/newsLink.js） ===== */
function newsHref(n) {
  return newsLinkUrl(n) || undefined
}
function newsExternal(n) {
  return !!newsLinkUrl(n) && !isInternalNews(n)
}
function newsSourceUrl(n) {
  return safeHttpUrl(n?.sourceUrl)
}
function newsLinkType(n) {
  const type = String(n?.linkType || '').toLowerCase()
  if (type) return type
  if (isInternalNews(n)) return 'internal'
  return newsLinkUrl(n) ? 'article' : ''
}
function newsBadge(n) {
  return newsLinkLabel(n, isEn.value)
}
function onNewsClick(e, n) {
  if (!newsLinkUrl(n)) return
  if (isInternalNews(n)) {
    e.preventDefault()
    openNewsLink(n, router)
  }
}
function openNews(n) {
  openNewsLink(n, router)
}

async function loadProducts() {
  loadingAll.value = true
  try {
    if (user.isMerchant) {
      const rows = await priceMonitorApi.merchantOverview()
      products.value = (rows || []).map(p => ({
        prodId: p.prodId, title: p.title, price: p.price,
        lastChangePct: p.lastChangePct, changeCount30d: p.changeCount30d, anomaly: !!p.anomaly
      }))
    } else {
      const res = await productApi.page({ current: 1, size: 100 })
      const recs = res?.records || res?.list || (Array.isArray(res) ? res : [])
      products.value = recs.map(p => ({
        prodId: p.prodId, title: p.title, price: p.price,
        lastChangePct: null, changeCount30d: 0, anomaly: false
      }))
      if (user.isAdmin) {
        adminRows = (await priceMonitorApi.adminAnomalies(30, 100)) || []
        const map = new Map(adminRows.map(r => [Number(r.prod_id), r]))
        products.value = products.value.map(p => {
          const r = map.get(Number(p.prodId))
          if (!r) return p
          return { ...p, lastChangePct: r.change_pct, anomaly: Math.abs(Number(r.change_pct || 0)) >= 10 }
        })
      }
    }
    if (!products.value.find(p => p.prodId === selectedId.value)) {
      selectedId.value = products.value[0]?.prodId || null
      if (selectedId.value) await loadProduct(selectedId.value)
    }
  } catch (e) {
    message.error('加载监控商品失败')
  } finally {
    loadingAll.value = false
  }
}

async function loadProduct(prodId) {
  trend.value = null
  analysis.value = null
  ai.value = null
  askResult.value = null
  await Promise.all([loadTrend(), loadAnalysis()])
  loadAi()
}

async function loadTrend() {
  if (!selectedId.value) return
  try {
    trend.value = await priceMonitorApi.trend(selectedId.value, days.value)
  } catch (e) {
    trend.value = null
  }
}
async function loadAnalysis() {
  if (!selectedId.value) return
  try {
    analysis.value = await priceMonitorApi.analysis(selectedId.value)
  } catch (e) {
    analysis.value = null
  }
}
async function loadAi(force = false) {
  if (!selectedId.value) return
  aiLoading.value = true
  try {
    ai.value = await priceMonitorApi.aiAnalysis(selectedId.value, lang.value)
  } catch (e) {
    if (force) message.error('AI 分析失败，请稍后重试')
  } finally {
    aiLoading.value = false
  }
}

function selectProduct(id) {
  if (id === selectedId.value) return
  selectedId.value = id
  loadProduct(id)
}
async function changeRange(d) {
  days.value = d
  await loadTrend()
}

async function ask(q) {
  const text = (q || '').trim()
  if (!text || !selectedId.value) return
  question.value = text
  asking.value = true
  askResult.value = null
  try {
    askResult.value = await priceMonitorApi.ask(selectedId.value, text, lang.value)
  } catch (e) {
    message.error('提问失败，请稍后重试')
  } finally {
    asking.value = false
  }
}

async function loadNews() {
  newsLoading.value = true
  try {
    news.value = (await priceMonitorApi.news(newsCategory.value, 20)) || []
    lastRefresh.value = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  } catch (e) {
    /* 静默 */
  } finally {
    newsLoading.value = false
  }
}
function setCategory(key) {
  newsCategory.value = key
  loadNews()
}

async function loadAlerts() {
  try {
    alerts.value = (await priceMonitorApi.myAlerts()) || []
  } catch (e) {
    alerts.value = []
  }
}
async function subscribeAlert() {
  if (!selectedId.value) return
  try {
    await priceMonitorApi.subscribe({
      prodId: selectedId.value,
      conditionType: alertForm.conditionType,
      threshold: alertForm.threshold
    })
    message.success('预警已开启')
    await loadAlerts()
  } catch (e) {
    /* 拦截器提示 */
  }
}
async function cancelAlert(id) {
  try {
    await priceMonitorApi.cancelAlert(id)
    message.success('已取消预警')
    await loadAlerts()
  } catch (e) {
    /* 拦截器提示 */
  }
}

async function refreshAll() {
  await Promise.all([loadProducts(), loadNews(), loadAlerts()])
}

onMounted(async () => {
  loadNews()
  if (!user.logged && typeof user.fetchMe === 'function') {
    try { await user.fetchMe() } catch (e) { /* ignore */ }
  }
  await loadProducts()
  loadAlerts()
  newsTimer = window.setInterval(loadNews, 60_000)
})
onBeforeUnmount(() => {
  if (newsTimer) window.clearInterval(newsTimer)
})
</script>

<style scoped>
.pi-page {
  --pi-ink: #1B2434;
  --pi-muted: #6B6F76;
  --pi-line: rgba(19, 35, 58, .09);
  --pi-paper: #F8F4EC;
  max-width: 1560px;
  margin: 0 auto;
  padding: 8px 4px 40px;
  color: var(--pi-ink);
}
.kicker { font-size: 10px; font-weight: 800; letter-spacing: .18em; color: #80601F; }
.card {
  border-radius: 26px;
  border: 1px solid rgba(19, 35, 58, .07);
  background: linear-gradient(180deg, rgba(255, 255, 255, .92) 0%, rgba(255, 255, 255, .7) 100%);
  box-shadow: 0 30px 72px -46px rgba(19, 35, 58, .42), inset 0 1px 0 rgba(255, 255, 255, .95);
  backdrop-filter: blur(20px) saturate(1.35);
  transition: transform .5s cubic-bezier(.22, 1, .36, 1), box-shadow .5s ease;
}
.card:hover {
  transform: translateY(-2px);
  box-shadow: 0 42px 92px -50px rgba(19, 35, 58, .5), inset 0 1px 0 rgba(255, 255, 255, .95);
}
.pi-tape {
  display: flex; align-items: center; gap: 12px;
  margin: -6px 0 20px; padding: 9px 14px; border-radius: 16px;
  border: 1px solid rgba(19, 35, 58, .06);
  background: rgba(255, 255, 255, .66);
  backdrop-filter: blur(14px) saturate(1.3);
  box-shadow: 0 10px 26px -20px rgba(19, 35, 58, .4);
}
.pi-tape-label {
  flex: none; display: inline-flex; align-items: center; gap: 6px;
  font-size: 9.5px; font-weight: 800; letter-spacing: .08em;
  color: #9B2D20; background: rgba(155, 45, 32, .07); padding: 3px 9px; border-radius: 99px;
}
.pi-tape-label i { width: 5px; height: 5px; border-radius: 50%; background: #9B2D20; animation: pulse 2s infinite; }
.pi-tape :deep(.mq) { flex: 1; min-width: 0; mask-image: linear-gradient(90deg, transparent 0, #000 4%, #000 96%, transparent 100%); }
.tape-item { display: inline-flex; align-items: center; gap: 8px; font-size: 12px; color: var(--pi-muted); white-space: nowrap; cursor: pointer; transition: color .2s ease; }
.tape-item:hover { color: #1E6EB8; }
.tape-item i { width: 4px; height: 4px; border-radius: 50%; background: #C8A45C; flex: none; }
.ext { margin-left: 2px; font-size: 9px; color: #80601F; }
.pi-hero {
  position: relative; overflow: hidden;
  display: grid; grid-template-columns: 1fr auto; gap: 24px;
  padding: 46px 52px; margin-bottom: 20px;
  border-radius: 30px;
  border: 1px solid rgba(255, 255, 255, .16);
  background:
    radial-gradient(620px 340px at 92% 4%, rgba(246, 211, 138, .26), transparent 66%),
    radial-gradient(680px 420px at 106% 108%, rgba(30, 110, 184, .24), transparent 64%),
    linear-gradient(135deg, #101B2D 0%, #16294A 58%, #1E3A5F 100%);
  box-shadow: 0 24px 60px rgba(19, 35, 58, .18);
}
.hero-glow { position: absolute; inset: -30%; background: radial-gradient(50% 40% at 70% 25%, rgba(246,211,138,.14), transparent 70%); pointer-events: none; }
.hero-copy { position: relative; z-index: 2; max-width: 760px; }
.eyebrow { font-size: 10.5px; font-weight: 800; letter-spacing: .2em; color: rgba(246, 211, 138, .92); }
.hero-copy h1 { margin: 14px 0 12px; font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "PingFang SC", sans-serif; font-size: clamp(32px, 4vw, 52px); line-height: 1.08; letter-spacing: -.04em; font-weight: 650; color: #fff; }
.hero-copy h1 em { font-style: normal; background: linear-gradient(100deg, #F6D38A, #E8C67A 55%, #9FC3F0); -webkit-background-clip: text; background-clip: text; color: transparent; }
.hero-copy p { margin: 0; max-width: 680px; color: rgba(255, 255, 255, .74); font-size: 15px; line-height: 1.8; }
.hero-actions { display: flex; align-items: center; gap: 12px; margin-top: 22px; }
.live-pill { display: inline-flex; align-items: center; gap: 7px; padding: 6px 12px; border-radius: 999px; border: 1px solid rgba(255,255,255,.18); background: rgba(255,255,255,.08); color: rgba(255,255,255,.78); font-size: 11.5px; }
.pulse { width: 6px; height: 6px; border-radius: 50%; background: #6EE7B7; box-shadow: 0 0 0 0 rgba(110,231,183,.7); animation: pulse 2s infinite; }
@keyframes pulse { 70% { box-shadow: 0 0 0 8px rgba(110,231,183,0); } 100% { box-shadow: 0 0 0 0 rgba(110,231,183,0); } }
.hero-stats { position: relative; z-index: 2; display: grid; grid-template-columns: 1fr 1fr; gap: 10px; align-content: center; }
.hero-stat { min-width: 120px; padding: 14px 16px; border-radius: 16px; border: 1px solid rgba(255,255,255,.14); background: rgba(255,255,255,.07); backdrop-filter: blur(12px); }
.hero-stat b { display: block; color: #F6D38A; font-size: 24px; letter-spacing: -.02em; }
.hero-stat span { font-size: 10.5px; color: rgba(255,255,255,.6); letter-spacing: .06em; }

.pi-grid { display: grid; grid-template-columns: 264px minmax(0, 1fr) 336px; gap: 18px; align-items: start; }
.main-col { display: grid; gap: 18px; min-width: 0; }
.panel-head, .card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.panel-head h3, .card-head h3 { margin: 4px 0 0; font-size: 16px; font-weight: 700; color: var(--pi-ink); }
.product-panel, .news-panel { padding: 18px; position: sticky; top: 84px; min-width: 0; }
.search { margin: 12px 0 10px; border-radius: 10px; }
.product-list { display: flex; flex-direction: column; gap: 6px; max-height: 620px; overflow: auto; }
.product-item { text-align: left; border: 1px solid transparent; background: transparent; border-radius: 12px; padding: 10px 12px; cursor: pointer; transition: .16s ease; }
.product-item:hover { background: rgba(200, 164, 92, .08); }
.product-item.active { background: #fff; border-color: rgba(200, 164, 92, .45); box-shadow: 0 6px 18px rgba(19,35,58,.06); }
.pi-title { display: flex; align-items: center; gap: 6px; }
.pi-name { font-size: 13px; color: var(--pi-ink); font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.anomaly-dot { flex: 0 0 auto; width: 14px; height: 14px; border-radius: 50%; display: grid; place-items: center; background: #F9EDEA; color: #9B2D20; font-size: 9px; font-weight: 800; }
.pi-meta { display: flex; align-items: baseline; gap: 8px; margin-top: 4px; font-size: 11.5px; }
.pi-meta b { color: #13233A; font-size: 13px; }
.pi-meta .up { color: #B45309; }
.pi-meta .down { color: #047857; }
.pi-meta .muted { color: #94A3B8; }
.empty, .empty-soft { padding: 22px 4px; text-align: center; color: #94A3B8; font-size: 12.5px; }
.empty-soft { padding: 28px 12px; }

.chart-card { padding: 22px 24px 16px; }
.chart-head { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; flex-wrap: wrap; }
.chart-title h2 { margin: 6px 0 8px; font-size: clamp(20px, 2.2vw, 28px); letter-spacing: -.02em; color: var(--pi-ink); }
.chart-sub { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.chart-sub .price { font-size: 22px; font-weight: 750; color: #13233A; }
.chart-sub .level { padding: 3px 10px; border-radius: 999px; font-size: 11px; font-weight: 700; }
.level.HOLD { background: #EDF3EE; color: #2F6B46; }
.level.strong { background: #FBF2DE; color: #80601F; }
.level.weak { background: #F9EDEA; color: #9B2D20; }
.level.sync { background: #EDF3EE; color: #2F6B46; }
.level.RAISE, .level.CUT { background: #FBF2DE; color: #80601F; }
.level.URGENT { background: #F9EDEA; color: #9B2D20; }
.chart-sub .industry { font-size: 11.5px; color: #6B6F76; }
.range-tabs { display: inline-flex; gap: 2px; padding: 3px; border-radius: 999px; background: var(--pi-paper); border: 1px solid var(--pi-line); }
.range-tabs button { border: 0; background: transparent; border-radius: 999px; padding: 5px 11px; font-size: 11.5px; color: #6B6F76; cursor: pointer; }
.range-tabs button.active { background: #fff; color: #13233A; font-weight: 700; box-shadow: 0 3px 10px rgba(19,35,58,.08); }
.chart-body { margin-top: 10px; }
.chart-empty { min-height: 260px; display: grid; place-items: center; color: #94A3B8; font-size: 13px; }
.chart-foot { display: flex; gap: 18px; flex-wrap: wrap; margin-top: 8px; padding-top: 10px; border-top: 1px solid var(--pi-line); font-size: 11.5px; color: #6B6F76; }
.chart-foot b.up { color: #B45309; }
.chart-foot b.down { color: #047857; }

.two-col { display: grid; grid-template-columns: 1fr; gap: 18px; }
@media (min-width: 1700px) {
  .two-col { grid-template-columns: 1fr 1fr; }
}
.ai-card, .ask-card, .alerts-card { padding: 20px 22px; scroll-margin-top: 84px; }
.ai-actions { display: flex; align-items: center; gap: 8px; }
.ai-loading { display: flex; align-items: center; gap: 8px; padding: 22px 0; color: #6B6F76; font-size: 12.5px; }
.ai-summary { margin: 14px 0 12px; font-size: 13.5px; line-height: 1.8; color: #334155; }
.drivers { display: grid; gap: 8px; }
.driver { display: grid; grid-template-columns: 22px 1fr; gap: 8px; padding: 9px 11px; border-radius: 12px; background: rgba(19, 35, 58, .035); }
.driver .impact { width: 22px; height: 22px; border-radius: 50%; display: grid; place-items: center; font-size: 12px; font-weight: 800; background: #EEF0F5; color: #13233A; }
.driver .impact.up { background: #FBF2DE; color: #B45309; }
.driver .impact.down { background: #EDF3EE; color: #047857; }
.driver b { font-size: 12.5px; color: var(--pi-ink); }
.driver p { margin: 3px 0 0; font-size: 11.5px; line-height: 1.6; color: #6B6F76; }
.ai-bottom { margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--pi-line); }
.risk { display: inline-block; padding: 3px 10px; border-radius: 999px; font-size: 10.5px; font-weight: 700; background: #EDF3EE; color: #2F6B46; }
.risk.medium { background: #FBF2DE; color: #80601F; }
.risk.high { background: #F9EDEA; color: #9B2D20; }
.suggestion { margin: 8px 0 0; font-size: 12.5px; line-height: 1.75; color: #334155; }
.notice { margin: 10px 0 0; font-size: 10.5px; color: #94A3B8; }

.quick-asks { display: flex; flex-wrap: wrap; gap: 6px; margin: 12px 0; }
.quick-asks button { border: 1px solid var(--pi-line); background: #fff; border-radius: 999px; padding: 5px 10px; font-size: 11px; color: #6B6F76; cursor: pointer; transition: .16s ease; }
.quick-asks button:hover { border-color: rgba(200, 164, 92, .5); color: #80601F; }
.ask-input { display: grid; grid-template-columns: 1fr auto; gap: 8px; align-items: end; }
.answer { margin-top: 14px; padding: 14px; border-radius: 14px; background: #fff; border: 1px solid var(--pi-line); }
.answer-head { display: flex; align-items: center; gap: 8px; font-size: 11.5px; color: #6B6F76; }
.answer-main { margin: 10px 0 12px; font-size: 13.5px; line-height: 1.8; color: #13233A; font-weight: 600; }
.answer-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.answer-grid > div { padding: 9px 10px; border-radius: 10px; background: rgba(19, 35, 58, .03); }
.answer-grid span { font-size: 10.5px; font-weight: 700; color: #80601F; letter-spacing: .08em; }
.answer-grid p { margin: 4px 0 0; font-size: 11.5px; line-height: 1.7; color: #475569; }
.evidence { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 10px; }
.evidence span { font-size: 10.5px; font-weight: 700; color: #6B6F76; }
.evidence i { font-style: normal; padding: 2px 8px; border-radius: 999px; background: var(--pi-paper); color: #6B6F76; font-size: 10.5px; }

.alert-create { display: flex; gap: 8px; flex-wrap: wrap; margin: 12px 0; }
.alert-list { display: grid; gap: 6px; }
.alert-item { display: grid; grid-template-columns: 1fr auto auto; gap: 10px; align-items: center; padding: 8px 10px; border-radius: 10px; background: rgba(19, 35, 58, .03); }
.alert-title { font-size: 12px; color: var(--pi-ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.alert-cond { font-size: 11px; color: #6B6F76; }
.muted { font-size: 11px; color: #94A3B8; }

.news-tabs { margin: 10px 0; }
.news-tabs :deep(.seg) { display: flex; width: 100%; flex-wrap: wrap; }
.news-tabs :deep(.seg button) { flex: 1 1 auto; min-width: 0; padding: 0 9px; }
/* "全部"占满顶部整行，其余分类换到第二行（市场在最左） */
.news-tabs :deep(.seg button:first-child) { flex: 1 1 100%; }
.news-list { display: flex; flex-direction: column; gap: 10px; max-height: 760px; overflow: auto; }
.news-item { padding: 11px 12px; border-radius: 12px; border: 1px solid var(--pi-line); background: #fff; }
.news-item.top { border-color: rgba(200, 164, 92, .4); background: linear-gradient(165deg, #FFFDF9, #FCF6E9); }
.news-tag { display: flex; align-items: center; gap: 6px; }
.news-tag .cat { font-size: 10px; font-weight: 700; color: #1E6EB8; letter-spacing: .06em; }
.news-tag .hot { font-size: 9.5px; font-weight: 800; color: #9B2D20; background: #F9EDEA; border-radius: 999px; padding: 1px 7px; }
/* 跳转方式徽标：原文 / 来源站点 / 检索原文 / 站内 */
.link-badge { font-size: 9px; padding: 1px 7px; border-radius: 999px; background: rgba(19, 35, 58, .05); color: #6B6F76; white-space: nowrap; }
.link-badge.lt-article { color: #9B2D20; background: #F9EDEA; }
.link-badge.lt-source { color: #1E6EB8; background: #EAF2FA; }
.link-badge.lt-search { color: #80601F; background: #FBF2DE; }
.link-badge.lt-internal { color: #2F6B46; background: #EDF3EE; }
.news-item h4 { margin: 6px 0 4px; font-size: 12.5px; line-height: 1.5; color: var(--pi-ink); font-weight: 650; }
.news-item h4 .news-title-link { color: inherit; text-decoration: none; }
.news-item h4 .news-title-link:hover { color: #1E6EB8; }
.news-item h4 .ext { margin-left: 2px; font-size: 9.5px; color: #80601F; }
.news-item p { margin: 0; font-size: 11px; line-height: 1.65; color: #6B6F76; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.news-meta { display: flex; justify-content: space-between; gap: 8px; margin-top: 6px; font-size: 10px; color: #94A3B8; }
.news-meta .news-src { color: #80601F; text-decoration: none; }
.news-meta .news-src:hover { color: #1E6EB8; text-decoration: underline; }
.live-dot { display: inline-block; width: 6px; height: 6px; margin-left: 5px; border-radius: 50%; background: #22C55E; vertical-align: middle; animation: pulse 2s infinite; }

@media (max-width: 1320px) {
  .pi-grid { grid-template-columns: 240px minmax(0, 1fr); }
  .news-panel { grid-column: 1 / -1; position: static; }
  .news-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); max-height: none; }
}
@media (max-width: 960px) {
  .pi-hero { grid-template-columns: 1fr; padding: 32px 26px; }
  .pi-grid { grid-template-columns: minmax(0, 1fr); }
  .product-panel { position: static; }
  .product-list { flex-direction: row; overflow-x: auto; max-height: none; }
  .product-item { min-width: 190px; }
  .two-col { grid-template-columns: 1fr; }
  .news-list { grid-template-columns: 1fr; }
  .answer-grid { grid-template-columns: 1fr; }
}
@media (max-width: 640px) {
  .pi-page { padding: 4px 2px 28px; }
  .pi-hero { padding: 26px 20px; border-radius: 24px; gap: 18px; }
  .hero-copy h1 { font-size: 30px; }
  .hero-copy p { font-size: 13px; }
  .hero-stat { min-width: 0; padding: 12px 13px; }
  .hero-stat b { font-size: 20px; }
  .pi-tape { margin: -2px 0 16px; padding: 8px 11px; }
  .tape-item { font-size: 11px; }
  .card { border-radius: 22px; }
  .chart-card { padding: 18px 16px 12px; }
  .chart-title h2 { font-size: 20px; }
  .chart-sub .price { font-size: 19px; }
  .product-panel, .news-panel, .ai-card, .ask-card, .alerts-card { padding: 16px; }
  .news-tabs :deep(.seg), .chart-head :deep(.seg) { flex-wrap: wrap; }
  .chart-foot { gap: 10px 16px; }
  .alert-create { gap: 6px; }
  .ask-input { grid-template-columns: 1fr; }
}
</style>
