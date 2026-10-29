<template>
  <section id="market-pulse" class="mp-section" aria-labelledby="mp-title">
    <div class="mp-tint a" aria-hidden="true"></div>
    <div class="mp-tint b" aria-hidden="true"></div>

    <header class="mp-head">
      <div class="mp-head-copy">
        <span class="mp-eyebrow"><i class="live-dot"></i>MARKET PULSE · 每日行情</span>
        <h2 id="mp-title">看懂今天的价格，<em>再做明天的决定。</em></h2>
        <p class="mp-sub">选定商品，用 K 线与成交量还原 30 / 60 / 90 天的价格轨迹；市场情绪、上游供应链、基础原材料、国际化与政策五维因子同步更新，随时可以让 AI 解释涨跌。</p>
      </div>
      <div class="mp-head-side">
        <div class="mp-date-row">
          <span class="mp-date" data-no-translate>{{ dateNumeric }}</span>
          <span class="mp-weekday">{{ weekdayText }}</span>
        </div>
        <SegmentedControl v-model="days" :options="rangeOptions" :aria-label="isEn ? 'Time range' : '时间范围'" />
        <button type="button" class="mp-link" @click="router.push('/price-monitor')">进入价格情报台 <span>→</span></button>
      </div>
    </header>

    <div class="mp-toolbar">
      <div class="mp-select">
        <span class="mp-label">选定商品</span>
        <a-select
          v-model:value="selectedId"
          show-search
          :options="productOptions"
          :filter-option="filterOption"
          :loading="loading"
          class="mp-product-select"
          :placeholder="isEn ? 'Select a product' : '选择要观察的商品'"
        />
      </div>
      <span class="mp-live"><i class="pulse"></i>{{ (!trend && (loading || trendLoading)) ? (isEn ? 'Syncing' : '同步中') : (isEn ? 'Live' : '实时') }}</span>
      <button type="button" class="mp-refresh" :disabled="loading || trendLoading" @click="refresh">
        {{ isEn ? 'Refresh' : '刷新数据' }}
      </button>
    </div>

    <div class="mp-bento">
      <article class="mp-card mp-chart-card">
        <header class="mp-card-head">
          <div class="mp-identity">
            <span class="mp-kicker">PRICE ACTION</span>
            <div class="mp-title-row">
              <h3>{{ selected?.title || (isEn ? 'Select a product' : '选择商品查看行情') }}</h3>
              <span v-if="trend?.industryName" class="mp-chip subtle">{{ trend.industryName }}</span>
            </div>
          </div>
          <div class="mp-quote">
            <span class="q-label">{{ isEn ? 'Current price' : '当前价' }}</span>
            <b>¥<NumberTicker :value="quotePrice" :decimal-places="priceDecimals" :stiffness="220" :damping="30" /></b>
            <span class="q-change" :class="quoteTone">{{ quoteChangeText }}</span>
          </div>
        </header>

        <StatsBand
          v-if="statItems.length"
          class="mp-stats"
          layout="divided"
          :stats="statItems"
          :duration="1.4"
          :locale="chartLocale"
        />

        <div class="mp-chart-body" :class="{ dim: trendLoading }">
          <PriceKlineChart :ohlc="trend?.ohlc || []" :height="352" />
        </div>

        <footer class="mp-chart-foot">
          <span>开 / 高 / 低 / 收来自商户真实调价留痕</span>
          <span>成交量为销量热度估算，非真实撮合</span>
          <button v-if="selectedId" type="button" @click="router.push('/product/' + selectedId)">{{ isEn ? 'View product' : '查看商品' }} →</button>
        </footer>
      </article>

      <aside class="mp-card mp-factor-card">
        <header class="mp-card-head">
          <div>
            <span class="mp-kicker">FIVE FACTORS</span>
            <h3>{{ isEn ? 'Five price factors' : '五维价格因子' }}</h3>
            <p class="mp-factor-scope">{{ factorScopeNote }}</p>
          </div>
          <span class="mp-chip subtle mp-scope-chip" :title="factorScopeChip">{{ factorScopeChip }}</span>
        </header>

        <MarketGauge
          :value="factorIndex ?? 0"
          :label="isEn ? 'Composite index' : '综合指数'"
          :segments="gaugeSegments"
          unit="/100"
          :size="262"
        />
        <p class="mp-factor-brief">{{ factorSummaryText }}</p>

        <ul class="mp-factor-list">
          <li v-for="f in factors" :key="f.key" class="mp-factor-row" :class="f.impact">
            <div class="f-top">
              <span class="f-name"><i class="f-dot" :style="{ background: factorColor(f.key) }"></i>{{ factorLabel(f) }}</span>
              <span class="f-impact">{{ impactText(f.impact) }}</span>
              <span class="f-score">{{ signed(f.score) }}</span>
            </div>
            <div class="f-track">
              <span class="f-mid"></span>
              <span class="f-fill" :style="scoreStyle(f)"></span>
            </div>
            <p v-if="factorBasis(f)" class="f-basis" :title="factorBasis(f)">{{ factorBasis(f) }}</p>
            <button
              v-if="f.newsRefs && f.newsRefs.length"
              type="button"
              class="f-evidence-link"
              :title="isEn ? 'Open evidence source' : '打开证据原文'"
              @click="openFactorRef(f.newsRefs[0])"
            >
              <span class="f-evidence">{{ f.newsRefs[0].title }}<span class="src">{{ f.newsRefs[0].source }}</span><span class="f-ext" aria-hidden="true">↗</span></span>
            </button>
            <p v-else class="f-evidence muted">{{ isEn ? 'No direct signal for this product in recent news.' : '近期资讯中没有该商品的直接信号，暂按中性处理。' }}</p>
          </li>
        </ul>
        <div class="mp-factor-foot">
          <p>{{ isEn ? '50 marks a neutral composite index; each arc length shows the factor’s product-weighted strength share.' : '综合指数 50 为中性；弧段长度表示各因子按商品权重加权后的强度占比。' }}</p>
          <button type="button" @click="router.push('/price-monitor')">{{ isEn ? 'Full evidence' : '查看完整证据' }} →</button>
        </div>
      </aside>
    </div>

    <div class="mp-lower">
      <article class="mp-card mp-news-card">
        <header class="mp-card-head">
          <div>
            <span class="mp-kicker">DAILY BRIEF</span>
            <h3>{{ isEn ? 'Daily market news' : '每日资讯' }}</h3>
          </div>
          <button type="button" class="mp-text-link" @click="router.push('/price-monitor')">{{ isEn ? 'All news' : '全部资讯' }} →</button>
        </header>

        <div v-if="tickerNews.length" class="mp-ticker">
          <span class="mp-ticker-label"><i></i>{{ isEn ? 'BRIEF' : '快讯' }}</span>
          <Marquee :repeat="2" gap="2.6rem" duration="34s" pause-on-hover>
            <span
              v-for="n in tickerNews"
              :key="'t' + n.id"
              class="mp-ticker-item"
              role="button"
              tabindex="0"
              :title="n.title"
              @click="openNews(n)"
              @keydown.enter.prevent="openNews(n)"
            >
              <i></i>{{ n.title }}<span class="f-ext" aria-hidden="true">↗</span>
            </span>
          </Marquee>
        </div>

        <div class="mp-news-tabs">
          <SegmentedControl v-model="newsCategory" :options="categoryOptions" :aria-label="isEn ? 'News category' : '资讯分类'" />
        </div>

        <ul v-if="news.length" class="mp-news-list">
          <li v-for="n in news.slice(0, 4)" :key="n.id">
            <div class="news-top">
              <span class="news-cat" :class="n.category">{{ categoryText(n.category) }}</span>
              <span v-if="newsBadge(n)" class="news-link-badge" :class="'lt-' + newsLinkType(n)">{{ newsBadge(n) }}</span>
              <time>{{ newsTime(n.publishedAt) }}</time>
            </div>
            <a
              class="news-title"
              :href="newsHref(n)"
              :target="newsExternal(n) ? '_blank' : undefined"
              :rel="newsExternal(n) ? 'noopener noreferrer' : undefined"
              @click="onNewsClick($event, n)"
            >{{ n.title }}<span v-if="newsExternal(n)" class="f-ext" aria-hidden="true">↗</span></a>
            <p class="news-summary">{{ n.summary }}</p>
            <div v-if="n.source" class="news-foot">
              <span v-if="!newsSourceUrl(n)" class="news-src">{{ n.source }}</span>
              <a
                v-else
                class="news-src"
                :href="newsSourceUrl(n)"
                target="_blank"
                rel="noopener noreferrer"
                :title="isEn ? 'Open source site' : '打开来源站点'"
              >{{ n.source }}<span class="f-ext" aria-hidden="true">↗</span></a>
            </div>
          </li>
        </ul>
        <div v-else class="mp-empty">{{ newsLoading ? (isEn ? 'Loading news…' : '资讯加载中…') : (isEn ? 'No news yet.' : '暂无资讯') }}</div>
      </article>

      <article class="mp-card mp-ai-card">
        <header class="mp-card-head">
          <div>
            <span class="mp-kicker">ASK THE ANALYST</span>
            <h3>{{ isEn ? 'Ask AI anytime' : '随时问 AI' }}</h3>
          </div>
          <span v-if="ai" class="ai-source">{{ aiSourceText }}</span>
        </header>

        <div v-if="!user.logged" class="ai-locked">
          <div class="ai-orbit" aria-hidden="true"><span></span><i></i></div>
          <p>{{ isEn ? 'Sign in to let AI read the candlesticks, five factors and today’s news, then explain the move and suggest next steps.' : '登录后，AI 会结合当前商品的 K 线、五维因子与今日资讯，解释涨跌原因并给出可执行建议。' }}</p>
          <div class="ai-preview">
            <div><b>{{ isEn ? 'Candles + volume' : 'K 线 + 成交量' }}</b><span>{{ isEn ? 'Replay real price changes' : '还原真实调价轨迹' }}</span></div>
            <div><b>{{ isEn ? 'Five factors' : '五维因子' }}</b><span>{{ isEn ? 'Locate the dominant driver' : '定位主导驱动' }}</span></div>
            <div><b>{{ isEn ? 'Actionable advice' : '可执行建议' }}</b><span>{{ isEn ? 'Know the next move' : '给出下一步动作' }}</span></div>
          </div>
          <button type="button" class="mp-primary" @click="goLogin">{{ isEn ? 'Sign in to ask' : '登录后提问' }}</button>
        </div>

        <template v-else>
          <div v-if="aiLoading" class="ai-loading"><a-spin size="small" /><span>{{ isEn ? 'AI is reading price action and news…' : 'AI 正在读取价格与资讯…' }}</span></div>
          <template v-else-if="ai">
            <p class="ai-summary">{{ ai.summary }}</p>
            <div v-if="ai.drivers && ai.drivers.length" class="ai-drivers">
              <div v-for="(d, i) in ai.drivers.slice(0, 3)" :key="i" class="ai-driver">
                <span class="impact" :class="d.impact">{{ impactIcon(d.impact) }}</span>
                <div><b>{{ d.label }}</b><p>{{ d.evidence }}</p></div>
              </div>
            </div>
            <p class="ai-suggestion">{{ ai.suggestion }}</p>
            <p v-if="ai.source === 'RULE'" class="ai-fallback">{{ isEn ? 'External model unavailable — the local rule engine answered instead.' : '外部模型暂不可用，本次由本地规则引擎分析。' }}</p>
          </template>
          <div v-else class="ai-intro">
            <p>{{ isEn ? 'Run a one-click read of this product’s candlesticks, factors and news.' : '一键读取当前商品的 K 线、五维因子与资讯，生成价格解读。' }}</p>
            <button type="button" class="mp-ghost" :disabled="aiLoading" @click="loadAi">{{ isEn ? 'Analyze now' : '让 AI 分析' }}</button>
          </div>

          <div class="ask-box">
            <a-input
              v-model:value="askInput"
              :placeholder="isEn ? 'Why did the price move recently?' : '例如：为什么最近价格上涨？'"
              allow-clear
              @press-enter="ask()"
            />
            <button type="button" class="mp-primary" :disabled="asking" @click="ask()">{{ isEn ? 'Ask' : '提问' }}</button>
          </div>
          <div class="quick-asks">
            <button v-for="q in quickAsks" :key="q" type="button" @click="ask(q)">{{ q }}</button>
          </div>

          <div v-if="asking" class="ai-loading small"><a-spin size="small" /><span>{{ isEn ? 'Tracing the cause…' : 'AI 正在追溯原因…' }}</span></div>
          <div v-else-if="askResult" class="ask-result">
            <b>{{ askResult.answer }}</b>
            <p v-if="askResult.cause">{{ askResult.cause }}</p>
            <p v-if="askResult.outlook">{{ askResult.outlook }}</p>
          </div>
        </template>
      </article>
    </div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { priceMonitorApi } from '../../api'
import { useUserStore } from '../../store/user'
import { locale } from '../../i18n'
import {
  isInternalNews,
  newsLinkLabel,
  newsLinkUrl,
  openNewsLink,
  safeHttpUrl
} from '../../utils/newsLink'
import PriceKlineChart from './PriceKlineChart.vue'
import SegmentedControl from '../ui/SegmentedControl.vue'
import StatsBand from '../ui/StatsBand.vue'
import MarketGauge from '../ui/MarketGauge.vue'
import Marquee from '../ui/Marquee.vue'
import NumberTicker from '../ui/NumberTicker.vue'

const router = useRouter()
const user = useUserStore()
const isEn = computed(() => String(locale.value || '').toLowerCase().startsWith('en'))
const chartLocale = computed(() => isEn.value ? 'en-US' : 'zh-CN')

const products = ref([])
const selectedId = ref(null)
const days = ref(30)
const trend = ref(null)
const factorData = ref(null)
const factors = ref([])
const factorLoading = ref(false)
const news = ref([])
const newsCategory = ref('')
const newsLoading = ref(false)
const loading = ref(false)
const trendLoading = ref(false)
const ai = ref(null)
const aiLoading = ref(false)
const askInput = ref('')
const asking = ref(false)
const askResult = ref(null)

let newsTimer = null
let trendSeq = 0
let factorSeq = 0
let newsSeq = 0

const rangeOptions = computed(() => [30, 60, 90].map(d => ({ value: d, label: isEn.value ? d + 'D' : d + ' 天' })))
const categoryOptions = computed(() => [
  { value: '', label: isEn.value ? 'All' : '全部' },
  { value: 'price', label: isEn.value ? 'Prices' : '价格' },
  { value: 'supply', label: isEn.value ? 'Supply' : '供给' },
  { value: 'policy', label: isEn.value ? 'Policy' : '政策' },
  { value: 'international', label: isEn.value ? 'Global' : '国际' },
  { value: 'market', label: isEn.value ? 'Market' : '市场' }
])

const quickAsks = computed(() => isEn.value
  ? ['Why did the price move?', 'What is the main driver?', 'What should I watch next?']
  : ['为什么最近价格波动？', '主要驱动因素是什么？', '接下来要关注什么？'])

const productOptions = computed(() => products.value.map(p => ({
  value: p.prodId,
  label: `${p.title} · ¥${money(p.price)}`
})))

const selected = computed(() => products.value.find(p => p.prodId === selectedId.value) || null)
const factorIndex = computed(() => factorData.value?.sentimentIndex ?? null)
const stance = computed(() => {
  const idx = factorIndex.value
  if (idx == null) return { key: 'neutral', text: isEn.value ? 'Waiting' : '等待' }
  if (idx >= 58) return { key: 'up', text: isEn.value ? 'Bullish' : '偏多' }
  if (idx <= 42) return { key: 'down', text: isEn.value ? 'Bearish' : '偏空' }
  return { key: 'neutral', text: isEn.value ? 'Neutral' : '中性' }
})

/** 因子卡 header chip：明确表达「按商品」而不是市场级 */
const factorScopeChip = computed(() => {
  const scope = trend.value?.industryName || selected.value?.title || ''
  const prefix = isEn.value ? 'Per product' : '按商品'
  return scope ? `${prefix} · ${scope}` : prefix
})
const factorScopeNote = computed(() => isEn.value
  ? 'Factors are computed from this product’s price track and related news, so each product has its own factors.'
  : '因子按当前商品的价格轨迹与相关资讯计算，不同商品因子不同。')

/** 主导因子的可读名称（优先用 factors 里的 key 做本地化，兼容后端直接给 label） */
function dominantLabel() {
  const dominant = factorData.value?.dominant
  if (!dominant) return '—'
  const key = typeof dominant === 'string' ? dominant : dominant.key
  const matched = factors.value.find(f => f.key === key)
  if (matched) return factorLabel(matched)
  if (typeof dominant === 'object' && dominant.label) return dominant.label
  return '—'
}

const factorLine = computed(() => {
  const idx = factorIndex.value
  if (idx == null) return isEn.value ? 'Waiting for this product’s latest factor signals.' : '等待当前商品最新的因子信号。'
  if (isEn.value) {
    return `This product’s five-factor index is ${idx}/100 — ${stance.value.text.toLowerCase()}. Dominant factor: ${dominantLabel()}.`
  }
  return `当前商品五维因子综合指数 ${idx}/100，商品信号${stance.value.text}；当前主导因子：${dominantLabel()}。`
})

/**
 * 因子卡摘要：中文态直接用后端 summary；英文态用 systematicAnalysis 的结构化数值
 * （priceChange30dPct / relativePct / dominant）自己拼英文，避免展示中文 summary。
 * 后端字段缺失（并行开发 / 旧接口）时回退本地 factorLine 拼接。
 */
const factorSummaryText = computed(() => {
  const data = factorData.value
  if (!data) return factorLine.value
  if (!isEn.value) {
    // 中文态：优先用后端「系统性分析结论」（含自身涨跌 / 相对行业 / 库存等本商品数值），
    // 其次用综合指数摘要，最后回退本地拼接，避免两行长文案重复播报。
    const conclusion = typeof data.systematicAnalysis?.conclusion === 'string'
      ? data.systematicAnalysis.conclusion.trim() : ''
    const summary = typeof data.summary === 'string' ? data.summary.trim() : ''
    return conclusion || summary || factorLine.value
  }
  const sys = data.systematicAnalysis
  if (!sys || (sys.priceChange30dPct == null && sys.relativePct == null)) return factorLine.value
  const bits = []
  if (sys.priceChange30dPct != null) bits.push(`30-day price ${signedPct(sys.priceChange30dPct)}`)
  if (sys.relativePct != null) bits.push(`${signedPct(sys.relativePct)} vs industry`)
  const idx = factorIndex.value
  if (idx != null) bits.push(`index ${idx}/100 (${stance.value.text.toLowerCase()})`)
  bits.push(`dominant factor: ${dominantLabel()}`)
  return `This product — ${bits.join(' · ')}.`
})

const candles = computed(() => trend.value?.ohlc || [])
const lastCandle = computed(() => candles.value.length ? candles.value[candles.value.length - 1] : null)
const quotePrice = computed(() => Number(trend.value?.product?.price ?? selected.value?.price ?? 0))
const priceDecimals = computed(() => quotePrice.value > 0 && quotePrice.value < 1 ? 4 : 2)
const quoteChangePct = computed(() => lastCandle.value?.changePct == null ? null : Number(lastCandle.value.changePct))
const quoteTone = computed(() => quoteChangePct.value == null ? 'flat' : quoteChangePct.value > 0 ? 'up' : quoteChangePct.value < 0 ? 'down' : 'flat')
const quoteChangeText = computed(() => {
  if (quoteChangePct.value == null) return isEn.value ? 'No recent change' : '近期无调价'
  const v = quoteChangePct.value
  return (v > 0 ? '+' : '') + v.toFixed(2) + '%'
})
const totalTrades = computed(() => candles.value.reduce((n, c) => n + (Number(c.trades) || 0), 0))

/** Stats Band 四联统计：区间最低 / 最高 / 振幅 / 调价次数，各自带迷你图 */
const statItems = computed(() => {
  const list = candles.value
  if (!list.length || !trend.value) return []
  const closes = list.map(c => Number(c.close))
  const lows = list.map(c => Number(c.low))
  const highs = list.map(c => Number(c.high))
  const low = trend.value.lowest || {}
  const high = trend.value.highest || {}
  const price = quotePrice.value
  const lowDiff = low.price && price ? ((price - Number(low.price)) / Number(low.price) * 100) : null
  const highDiff = high.price && price ? ((price - Number(high.price)) / Number(high.price) * 100) : null

  // 振幅分布：每日 (high-low)/close 的直方图，标记当前区间振幅
  const ranges = list.map(c => c.close ? (Number(c.high) - Number(c.low)) / Number(c.close) * 100 : 0)
  const maxRange = Math.max(...ranges, 0.01)
  const BINS = 14
  const bins = new Array(BINS).fill(0)
  ranges.forEach(r => {
    const bi = Math.min(BINS - 1, Math.floor(r / maxRange * BINS))
    bins[bi] += 1
  })

  // 成交量热力：按当日量归一为 0-100（<100 记为缺口，形成密集条形）
  const vols = list.map(c => Number(c.volume) || 0)
  const maxVol = Math.max(...vols, 1)
  const volDays = vols.map(v => Math.round(64 + 36 * (v / maxVol)))

  return [
    {
      value: Number(low.price || 0), decimals: priceDecimals.value, tone: 'low',
      label: isEn.value ? 'Range low' : '区间最低',
      detail: shortDay(low.day),
      context: lowDiff == null ? '' : (isEn.value ? `${signedPct(lowDiff)} vs now` : `较现价 ${signedPct(lowDiff)}`),
      visual: { kind: 'trend', values: lows }
    },
    {
      value: Number(high.price || 0), decimals: priceDecimals.value, tone: 'high',
      label: isEn.value ? 'Range high' : '区间最高',
      detail: shortDay(high.day),
      context: highDiff == null ? '' : (isEn.value ? `${signedPct(highDiff)} vs now` : `较现价 ${signedPct(highDiff)}`),
      visual: { kind: 'trend', values: highs }
    },
    {
      value: Number(trend.value.rangePct || 0), decimals: 2, suffix: '%', tone: 'range',
      label: isEn.value ? 'Range swing' : '区间振幅',
      detail: isEn.value ? 'high vs low' : '最高 / 最低',
      context: isEn.value ? `avg daily ${(ranges.reduce((a, b) => a + b, 0) / ranges.length).toFixed(2)}%` : `日均 ${(ranges.reduce((a, b) => a + b, 0) / ranges.length).toFixed(2)}%`,
      visual: { kind: 'distribution', bins, max: maxRange, marker: Number(trend.value.rangePct || 0) }
    },
    {
      value: totalTrades.value, decimals: 0, tone: 'changes',
      label: isEn.value ? 'Price changes' : '调价次数',
      detail: isEn.value ? `last ${days.value} days` : `近 ${days.value} 天`,
      context: isEn.value ? `${list.length} sessions` : `覆盖 ${list.length} 个交易日`,
      visual: { kind: 'uptime', days: volDays }
    }
  ]
})

/** 仪表环：按五维强度占比着色（沿用 Finance Bento Allocation 的占比着色逻辑）。
 *  后端新增 per-product 的 weight 时把权重乘进 strength；字段缺失（旧数据）按 strength 原值。 */
const factorColors = { sentiment: '#C8A45C', supply: '#B5654F', material: '#7E9BB8', international: '#7FA89B', politics: '#13233A' }
const gaugeSegments = computed(() => {
  const weighted = factors.value.map(f => {
    const strength = Math.max(0, Number(f.strength) || 0)
    const weight = Number(f.weight)
    const value = strength * (Number.isFinite(weight) && weight > 0 ? weight : 1)
    return { key: f.key, value }
  })
  const total = weighted.reduce((n, f) => n + f.value, 0) || 1
  return weighted.map(f => ({
    share: f.value / total,
    color: factorColors[f.key] || '#C8A45C'
  }))
})


const tickerNews = computed(() => news.value)

const dateNumeric = computed(() => {
  const d = new Date()
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')}`
})
const weekdayText = computed(() => {
  const day = new Date().getDay()
  return isEn.value
    ? ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][day]
    : ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][day]
})

function money(v) {
  if (v == null || v === '') return '—'
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  return n.toLocaleString('zh-CN', { maximumFractionDigits: n < 1 ? 4 : 2 })
}
function signed(v) {
  const n = Number(v) || 0
  return (n > 0 ? '+' : '') + Math.round(n)
}
function signedPct(v) {
  const n = Number(v) || 0
  return (n > 0 ? '+' : '') + n.toFixed(2) + '%'
}
function shortDay(d) {
  if (!d) return '—'
  return String(d).slice(5).replace('-', '.')
}
function impactIcon(i) {
  return i === 'up' ? '↑' : i === 'down' ? '↓' : '•'
}
function factorLabel(f) {
  const map = {
    sentiment: { zh: '市场情绪', en: 'Market sentiment' },
    supply: { zh: '上游供应链', en: 'Upstream supply' },
    material: { zh: '基础原材料', en: 'Raw materials' },
    international: { zh: '国际化因素', en: 'Global factors' },
    politics: { zh: '政治与政策', en: 'Policy & politics' }
  }
  const item = map[f?.key]
  return item ? item[isEn.value ? 'en' : 'zh'] : (f?.label || '—')
}
function factorColor(key) {
  return factorColors[key] || '#C8A45C'
}
function impactText(i) {
  if (i === 'up') return isEn.value ? 'Bullish' : '偏多'
  if (i === 'down') return isEn.value ? 'Bearish' : '偏空'
  return isEn.value ? 'Neutral' : '中性'
}
function categoryText(c) {
  const map = {
    policy: { zh: '政策', en: 'Policy' },
    supply: { zh: '供给', en: 'Supply' },
    demand: { zh: '需求', en: 'Demand' },
    price: { zh: '价格', en: 'Price' },
    tech: { zh: '技术', en: 'Tech' },
    international: { zh: '国际', en: 'Global' },
    market: { zh: '市场', en: 'Market' }
  }
  const item = map[c]
  return item ? item[isEn.value ? 'en' : 'zh'] : (isEn.value ? 'Market' : '市场')
}
function newsTime(v) {
  if (!v) return ''
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return String(v).slice(5, 16).replace('T', ' ')
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
function scoreStyle(f) {
  const score = Math.max(-100, Math.min(100, Number(f.score) || 0))
  const width = Math.abs(score) / 2
  return score >= 0
    ? { left: '50%', width: width + '%' }
    : { right: '50%', width: width + '%' }
}
function filterOption(input, option) {
  return String(option.label || '').toLowerCase().includes(String(input || '').toLowerCase())
}

/** 因子依据：优先后端 basis，其次 productSignals[0]；缺失时返回空串（行不渲染） */
function factorBasis(f) {
  const raw = f?.basis || (Array.isArray(f?.productSignals) && f.productSignals.length ? f.productSignals[0] : '')
  if (typeof raw === 'string') return raw.trim()
  if (raw && typeof raw === 'object') return String(raw.text || raw.label || raw.title || '').trim()
  return ''
}

/** 资讯标题最终 href：外链原文 / 检索兜底；无合法链接时返回 undefined（按钮态） */
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
  if (!newsLinkUrl(n)) {
    e.preventDefault()
    openNews(n)
    return
  }
  if (isInternalNews(n)) {
    e.preventDefault()
    openNewsLink(n, router)
  }
}
function openNews(n) {
  // 后端字段缺失（旧数据）时退化为站内价格情报台，保持原有可用性
  if (!openNewsLink(n, router)) router.push('/price-monitor')
}
function openFactorRef(ref) {
  if (!openNewsLink(ref, router)) router.push('/price-monitor')
}

function goLogin() {
  router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
}

function normalizeProduct(r) {
  return {
    prodId: Number(r.prodId ?? r.prod_id),
    title: r.title,
    price: Number(r.price),
    changes: Number(r.changes ?? 0),
    lastChangeTime: r.lastChangeTime ?? r.last_change_time
  }
}

async function loadProducts() {
  loading.value = true
  try {
    const rows = await priceMonitorApi.marketProducts(12)
    products.value = (rows || []).map(normalizeProduct).filter(p => Number.isFinite(p.prodId))
    if (!products.value.some(p => p.prodId === selectedId.value)) {
      selectedId.value = products.value[0]?.prodId ?? null
    }
  } catch (e) {
    products.value = []
  } finally {
    loading.value = false
  }
}

async function loadTrend() {
  if (!selectedId.value) return
  const seq = ++trendSeq
  trendLoading.value = true
  try {
    const data = await priceMonitorApi.trend(selectedId.value, days.value)
    if (seq === trendSeq) trend.value = data
  } catch (e) {
    if (seq === trendSeq) trend.value = null
  } finally {
    if (seq === trendSeq) trendLoading.value = false
  }
}

async function loadFactors() {
  if (!selectedId.value) return
  const seq = ++factorSeq
  factorLoading.value = true
  try {
    // 因子按商品计算，lang 让后端本地化 summary / systematicAnalysis 文案
    const data = await priceMonitorApi.factors(selectedId.value, isEn.value ? 'en' : 'zh')
    if (seq === factorSeq) {
      factorData.value = data
      factors.value = data?.factors || []
    }
  } catch (e) {
    if (seq === factorSeq) {
      factorData.value = null
      factors.value = []
    }
  } finally {
    if (seq === factorSeq) factorLoading.value = false
  }
}

async function loadNews() {
  const seq = ++newsSeq
  newsLoading.value = true
  try {
    const rows = await priceMonitorApi.news(newsCategory.value, 6)
    if (seq === newsSeq) news.value = rows || []
  } catch (e) {
    if (seq === newsSeq) news.value = []
  } finally {
    if (seq === newsSeq) newsLoading.value = false
  }
}

async function loadProduct() {
  ai.value = null
  askResult.value = null
  askInput.value = ''
  trend.value = null
  await Promise.all([loadTrend(), loadFactors()])
}

async function refresh() {
  await Promise.all([loadTrend(), loadFactors(), loadNews()])
}

async function loadAi() {
  if (!selectedId.value) return
  if (!user.logged) return goLogin()
  aiLoading.value = true
  try {
    ai.value = await priceMonitorApi.aiAnalysis(selectedId.value, isEn.value ? 'en' : 'zh')
  } catch (e) {
    message.error(isEn.value ? 'AI analysis failed. Please try again.' : 'AI 分析失败，请稍后重试')
  } finally {
    aiLoading.value = false
  }
}

async function ask(q) {
  const text = String(q ?? askInput.value ?? '').trim()
  if (!text || !selectedId.value) return
  if (!user.logged) return goLogin()
  askInput.value = text
  asking.value = true
  askResult.value = null
  try {
    askResult.value = await priceMonitorApi.ask(selectedId.value, text, isEn.value ? 'en' : 'zh')
  } catch (e) {
    message.error(isEn.value ? 'Could not answer right now. Please try again.' : '提问失败，请稍后重试')
  } finally {
    asking.value = false
  }
}

watch(selectedId, (id) => { if (id) loadProduct() })
watch(days, () => { if (selectedId.value) loadTrend() })
watch(newsCategory, loadNews)
watch(locale, () => {
  // AI 文案由后端按语言生成，切换语言后清空，避免中英混排。
  ai.value = null
  askResult.value = null
  askInput.value = ''
  // 因子 summary / 依据随语言变化，切换语言后按新语言重新拉取
  if (selectedId.value) loadFactors()
})

onMounted(async () => {
  await loadProducts()
  await loadNews()
  newsTimer = window.setInterval(loadNews, 120_000)
})
onBeforeUnmount(() => {
  if (newsTimer) window.clearInterval(newsTimer)
})
</script>

<style scoped>
.mp-section {
  position: relative;
  margin: 6px 0 34px;
  padding: 42px 6px 6px;
  scroll-margin-top: 88px;
  color: var(--ll-ink, #0F172A);
}
.mp-tint { position: absolute; border-radius: 50%; filter: blur(64px); pointer-events: none; }
.mp-tint.a { width: 560px; height: 340px; right: -100px; top: -40px; background: radial-gradient(circle, rgba(246, 211, 138, .34), transparent 68%); }
.mp-tint.b { width: 460px; height: 320px; left: -160px; bottom: 40px; background: radial-gradient(circle, rgba(30, 110, 184, .12), transparent 70%); }

/* ===== 头部：Apple 产品页式大字 + 右侧分段控件 ===== */
.mp-head { position: relative; z-index: 1; display: flex; align-items: flex-end; justify-content: space-between; gap: 32px; margin-bottom: 26px; padding: 0 8px; }
.mp-head-copy { max-width: 840px; }
.mp-eyebrow { display: inline-flex; align-items: center; gap: 8px; font-size: 10px; font-weight: 800; letter-spacing: .18em; color: var(--ll-brand-gold-strong, #80601F); }
.live-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--ll-brand-red, #9B2D20); box-shadow: 0 0 0 4px rgba(155, 45, 32, .12); animation: mpPulse 2.4s ease-in-out infinite; }
.mp-head h2 { margin: 14px 0 10px; font-size: clamp(28px, 3.4vw, 46px); line-height: 1.08; letter-spacing: -.05em; font-weight: 750; color: var(--ll-brand-base, #13233A); }
.mp-head h2 em { font-style: normal; color: var(--ll-brand-gold-strong, #80601F); white-space: nowrap; }
.mp-sub { margin: 0; max-width: 680px; font-size: 14px; line-height: 1.85; color: var(--ll-muted, #64748B); }
.mp-head-side { display: flex; flex-direction: column; align-items: flex-end; gap: 12px; flex-shrink: 0; }
.mp-date-row { display: flex; align-items: baseline; gap: 8px; }
.mp-date { font-size: 15px; font-weight: 750; letter-spacing: .02em; color: var(--ll-brand-base, #13233A); font-variant-numeric: tabular-nums; }
.mp-weekday { font-size: 11px; letter-spacing: .1em; color: var(--ll-brand-gold-strong, #80601F); }
.mp-link { border: 0; background: transparent; padding: 0; color: var(--ll-brand-blue, #1E6EB8); font-size: 12.5px; cursor: pointer; }
.mp-link:hover { color: var(--ll-brand-base, #13233A); }

/* ===== 工具条 ===== */
.mp-toolbar { position: relative; z-index: 1; display: flex; align-items: center; gap: 14px; flex-wrap: wrap; margin: 0 8px 18px; }
.mp-select { display: flex; align-items: center; gap: 10px; flex: 1; min-width: 300px; max-width: 560px; }
.mp-label { font-size: 11px; font-weight: 700; letter-spacing: .1em; color: var(--ll-brand-gold-strong, #80601F); white-space: nowrap; }
.mp-product-select { width: 100%; }
.mp-product-select :deep(.ant-select-selector) {
  border-radius: 14px !important;
  background: rgba(255, 255, 255, .82) !important;
  border-color: rgba(19, 35, 58, .1) !important;
  box-shadow: 0 6px 18px -12px rgba(19, 35, 58, .35);
}
.mp-live { display: inline-flex; align-items: center; gap: 6px; font-size: 11.5px; color: var(--ll-muted, #64748B); margin-left: auto; }
.pulse { width: 6px; height: 6px; border-radius: 50%; background: #2E7D5B; box-shadow: 0 0 0 4px rgba(46, 125, 91, .12); animation: mpPulse 2.4s ease-in-out infinite; }
.mp-refresh {
  border: 1px solid rgba(19, 35, 58, .1);
  background: rgba(255, 255, 255, .82);
  color: var(--ll-brand-base, #13233A);
  font-size: 12px; font-weight: 600;
  padding: 7px 14px; border-radius: 12px; cursor: pointer;
  box-shadow: 0 6px 18px -12px rgba(19, 35, 58, .35);
  transition: transform .3s cubic-bezier(.22, 1, .36, 1), box-shadow .3s ease, opacity .2s;
}
.mp-refresh:hover { transform: translateY(-1px); box-shadow: 0 10px 24px -14px rgba(19, 35, 58, .45); }
.mp-refresh:disabled { opacity: .55; cursor: default; transform: none; }

/* ===== 卡片体系 ===== */
.mp-bento { position: relative; z-index: 1; display: grid; grid-template-columns: minmax(0, 1.9fr) minmax(300px, .92fr); gap: 20px; }
.mp-lower { position: relative; z-index: 1; display: grid; grid-template-columns: minmax(0, 1.06fr) minmax(0, .94fr); gap: 20px; margin-top: 20px; }
.mp-card {
  position: relative;
  border-radius: 28px;
  border: 1px solid rgba(19, 35, 58, .07);
  background: linear-gradient(180deg, rgba(255, 255, 255, .92) 0%, rgba(255, 255, 255, .68) 100%);
  box-shadow: 0 34px 80px -46px rgba(19, 35, 58, .45), inset 0 1px 0 rgba(255, 255, 255, .95);
  backdrop-filter: blur(22px) saturate(1.35);
  transition: transform .5s cubic-bezier(.22, 1, .36, 1), box-shadow .5s ease;
  padding: 24px 26px;
  min-width: 0;
}
.mp-card:hover { transform: translateY(-2px); box-shadow: 0 44px 96px -50px rgba(19, 35, 58, .5), inset 0 1px 0 rgba(255, 255, 255, .95); }
.mp-card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 16px; }
.mp-kicker { display: block; font-size: 9.5px; font-weight: 800; letter-spacing: .17em; color: var(--ll-brand-gold-strong, #80601F); margin-bottom: 6px; }
.mp-card-head h3 { margin: 0; font-size: 17px; letter-spacing: -.02em; color: var(--ll-brand-base, #13233A); }
.mp-chip { font-size: 10.5px; font-weight: 600; padding: 3px 9px; border-radius: 99px; white-space: nowrap; }
.mp-chip.subtle { background: rgba(19, 35, 58, .055); color: var(--ll-muted, #64748B); }

/* 图表卡 */
.mp-identity { min-width: 0; }
.mp-title-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.mp-title-row h3 { max-width: 480px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mp-quote { text-align: right; flex-shrink: 0; }
.q-label { display: block; font-size: 10px; letter-spacing: .06em; color: var(--ll-muted, #64748B); }
.mp-quote b { display: block; margin: 2px 0 3px; font-size: 30px; line-height: 1.05; letter-spacing: -.05em; font-weight: 650; color: var(--ll-brand-base, #13233A); font-variant-numeric: tabular-nums; }
.q-change { font-size: 12px; font-weight: 700; font-variant-numeric: tabular-nums; }
.q-change.up { color: #9B2D20; }
.q-change.down { color: #2E7D5B; }
.q-change.flat { color: var(--ll-muted, #64748B); }
.mp-stats { display: block; padding: 6px 0 10px; margin-bottom: 8px; border-bottom: 1px solid rgba(19, 35, 58, .06); }
.mp-chart-body { min-height: 300px; transition: opacity .25s ease; }
.mp-chart-body.dim { opacity: .5; }
.mp-chart-foot { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; margin-top: 10px; padding-top: 12px; border-top: 1px solid rgba(19, 35, 58, .06); font-size: 10px; color: var(--ll-muted, #94A3B8); }
.mp-chart-foot button { margin-left: auto; border: 0; background: transparent; color: var(--ll-brand-blue, #1E6EB8); font-size: 11.5px; cursor: pointer; padding: 0; }

/* 因子卡 */
.mp-factor-card { display: flex; flex-direction: column; }
.mp-factor-brief { margin: 2px 0 0; font-size: 12px; line-height: 1.7; color: var(--ll-muted, #64748B); text-align: center; }
.mp-factor-list { list-style: none; margin: 14px 0 0; padding: 14px 0 0; border-top: 1px solid rgba(19, 35, 58, .06); display: flex; flex: 1; flex-direction: column; justify-content: space-between; gap: 13px; }
.mp-factor-foot { margin-top: 14px; padding-top: 12px; border-top: 1px solid rgba(19, 35, 58, .06); display: flex; flex-direction: column; gap: 5px; }
.mp-factor-foot p { margin: 0; font-size: 10px; line-height: 1.6; color: var(--ll-muted, #94A3B8); }
.mp-factor-foot button { align-self: flex-start; border: 0; background: transparent; padding: 0; color: var(--ll-brand-blue, #1E6EB8); font-size: 11.5px; cursor: pointer; }
.mp-factor-row { min-width: 0; }
.f-top { display: flex; align-items: baseline; gap: 8px; margin-bottom: 5px; }
.f-name { display: inline-flex; align-items: center; gap: 6px; font-size: 12.5px; font-weight: 650; color: var(--ll-brand-base, #13233A); }
.f-dot { width: 6px; height: 6px; border-radius: 50%; flex: none; }
.f-impact { font-size: 9.5px; padding: 1px 7px; border-radius: 99px; background: rgba(19, 35, 58, .06); color: var(--ll-muted, #64748B); }
.mp-factor-row.up .f-impact { background: rgba(155, 45, 32, .1); color: #9B2D20; }
.mp-factor-row.down .f-impact { background: rgba(46, 125, 91, .1); color: #2E7D5B; }
.f-score { margin-left: auto; font-size: 11.5px; font-weight: 750; color: var(--ll-muted, #64748B); font-variant-numeric: tabular-nums; }
.mp-factor-row.up .f-score { color: #9B2D20; }
.mp-factor-row.down .f-score { color: #2E7D5B; }
.f-track { position: relative; height: 5px; border-radius: 99px; background: rgba(19, 35, 58, .06); overflow: hidden; }
.f-mid { position: absolute; left: 50%; top: 0; bottom: 0; width: 1px; background: rgba(19, 35, 58, .18); z-index: 1; }
.f-fill { position: absolute; top: 0; bottom: 0; border-radius: 99px; transition: width .5s cubic-bezier(.22, 1, .36, 1); }
.mp-factor-row.up .f-fill { background: linear-gradient(90deg, #C8A45C, #9B2D20); }
.mp-factor-row.down .f-fill { background: linear-gradient(270deg, #C8A45C, #2E7D5B); }
.mp-factor-row.neutral .f-fill { background: linear-gradient(90deg, #C8A45C, #94A3B8); }
.f-evidence { margin: 5px 0 0; font-size: 11px; line-height: 1.55; color: var(--ll-muted, #64748B); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.f-evidence.muted { color: var(--ll-muted, #94A3B8); }
.f-evidence .src { margin-left: 4px; color: var(--ll-brand-gold-strong, #80601F); font-size: 9.5px; white-space: nowrap; }

/* 因子按商品：scope 说明 + 每因子依据 + 可点击证据 */
.mp-factor-scope { margin: 6px 0 0; max-width: 300px; font-size: 10.5px; line-height: 1.55; color: var(--ll-muted, #64748B); }
.mp-scope-chip { flex: none; max-width: 210px; overflow: hidden; text-overflow: ellipsis; }
.f-basis { margin: 4px 0 0; font-size: 10px; line-height: 1.5; color: var(--ll-muted, #94A3B8); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.f-evidence-link { display: block; width: 100%; padding: 0; border: 0; background: transparent; text-align: left; font: inherit; cursor: pointer; }
.f-evidence-link:hover .f-evidence { color: var(--ll-brand-blue, #1E6EB8); }
.f-ext { margin-left: 3px; font-size: 9px; color: var(--ll-brand-gold-strong, #80601F); }
.f-evidence-link:hover .f-ext { color: var(--ll-brand-blue, #1E6EB8); }

/* 资讯卡 */
.mp-ticker { display: flex; align-items: center; gap: 12px; margin: 0 -4px 14px; padding-bottom: 12px; border-bottom: 1px solid rgba(19, 35, 58, .06); }
.mp-ticker-label { flex: none; display: inline-flex; align-items: center; gap: 5px; font-size: 9.5px; font-weight: 800; letter-spacing: .08em; color: #9B2D20; background: rgba(155, 45, 32, .07); padding: 3px 8px; border-radius: 99px; }
.mp-ticker-label i { width: 5px; height: 5px; border-radius: 50%; background: #9B2D20; animation: mpPulse 2.4s ease-in-out infinite; }
.mp-ticker :deep(.mq) { flex: 1; min-width: 0; mask-image: linear-gradient(90deg, transparent 0, #000 4%, #000 96%, transparent 100%); }
.mp-ticker-item { display: inline-flex; align-items: center; gap: 8px; font-size: 11.5px; color: var(--ll-muted, #64748B); white-space: nowrap; }
.mp-ticker-item i { width: 4px; height: 4px; border-radius: 50%; background: var(--ll-brand-gold, #C8A45C); flex: none; }
.mp-news-tabs { margin-bottom: 14px; }
.mp-news-list { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: 1fr 1fr; gap: 12px 14px; }
.mp-news-list li { min-width: 0; padding: 12px 13px; border-radius: 16px; background: rgba(255, 255, 255, .6); border: 1px solid rgba(19, 35, 58, .06); transition: border-color .25s ease, transform .25s ease; }
.mp-news-list li:hover { border-color: rgba(128, 96, 31, .28); transform: translateY(-1px); }
.news-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 6px; }
.news-cat { font-size: 9px; padding: 1.5px 7px; border-radius: 99px; background: rgba(19, 35, 58, .06); color: var(--ll-muted, #64748B); white-space: nowrap; }
.news-cat.price { color: #9B2D20; background: rgba(155, 45, 32, .08); }
.news-cat.supply { color: #2E7D5B; background: rgba(46, 125, 91, .09); }
.news-cat.policy { color: #80601F; background: rgba(200, 164, 92, .16); }
.news-cat.international { color: #1E6EB8; background: rgba(30, 110, 184, .09); }
.news-top time { font-size: 9.5px; color: var(--ll-muted, #94A3B8); font-variant-numeric: tabular-nums; margin-left: auto; }
.news-title { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; font-size: 12.5px; line-height: 1.55; font-weight: 650; color: var(--ll-brand-base, #13233A); text-decoration: none; }
.news-title:hover { color: var(--ll-brand-blue, #1E6EB8); }
.news-summary { margin: 4px 0 0; font-size: 10.5px; line-height: 1.55; color: var(--ll-muted, #64748B); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
/* 跳转方式徽标 + 来源站点外链 + 快讯可点击 */
.news-link-badge { font-size: 9px; padding: 1.5px 7px; border-radius: 99px; white-space: nowrap; background: rgba(19, 35, 58, .05); color: var(--ll-muted, #64748B); }
.news-link-badge.lt-article { color: #9B2D20; background: rgba(155, 45, 32, .08); }
.news-link-badge.lt-source { color: #1E6EB8; background: rgba(30, 110, 184, .09); }
.news-link-badge.lt-search { color: #80601F; background: rgba(200, 164, 92, .16); }
.news-link-badge.lt-internal { color: #2E7D5B; background: rgba(46, 125, 91, .09); }
.news-foot { display: flex; align-items: center; gap: 8px; margin-top: 5px; min-height: 12px; }
.news-src { font-size: 9.5px; color: var(--ll-brand-gold-strong, #80601F); text-decoration: none; }
a.news-src:hover { color: var(--ll-brand-blue, #1E6EB8); text-decoration: underline; }
.mp-ticker-item { cursor: pointer; transition: color .2s ease; }
.mp-ticker-item:hover { color: var(--ll-brand-blue, #1E6EB8); }
.mp-empty { padding: 28px 0; text-align: center; font-size: 12px; color: var(--ll-muted, #94A3B8); }

/* AI 卡 */
.mp-ai-card { display: flex; flex-direction: column; background: linear-gradient(165deg, rgba(255, 255, 255, .92) 0%, rgba(246, 211, 138, .16) 58%, rgba(30, 110, 184, .08) 100%); }
.ai-source { font-size: 9.5px; color: var(--ll-brand-gold-strong, #80601F); border: 1px solid rgba(128, 96, 31, .2); border-radius: 99px; padding: 2px 8px; white-space: nowrap; }
.ai-locked { flex: 1; display: flex; flex-direction: column; align-items: flex-start; justify-content: center; gap: 14px; padding: 10px 2px 16px; }
.ai-locked p { margin: 0; font-size: 12.5px; line-height: 1.75; color: var(--ll-muted, #64748B); }
.ai-preview { display: grid; gap: 8px; width: 100%; margin: 2px 0 4px; }
.ai-preview div { display: flex; align-items: baseline; gap: 10px; padding: 8px 10px; border-radius: 12px; background: rgba(255, 255, 255, .6); border: 1px solid rgba(19, 35, 58, .05); }
.ai-preview b { font-size: 11.5px; color: var(--ll-brand-base, #13233A); white-space: nowrap; }
.ai-preview span { font-size: 10.5px; color: var(--ll-muted, #64748B); }
.ai-orbit { position: relative; width: 46px; height: 46px; border-radius: 50%; border: 1px dashed rgba(128, 96, 31, .3); display: grid; place-items: center; }
.ai-orbit span { width: 12px; height: 12px; border-radius: 50%; background: linear-gradient(135deg, #C8A45C, #9B2D20); box-shadow: 0 0 20px rgba(200, 164, 92, .5); }
.ai-orbit i { position: absolute; top: -3px; left: 50%; width: 6px; height: 6px; margin-left: -3px; border-radius: 50%; background: #1E6EB8; animation: mpOrbit 6s linear infinite; transform-origin: 3px 26px; }
.mp-primary {
  border: 0; border-radius: 12px; padding: 9px 18px; cursor: pointer;
  background: linear-gradient(135deg, #1E6EB8, #3B82C4);
  color: #fff; font-weight: 650; font-size: 13px;
  box-shadow: 0 8px 22px -10px rgba(30, 110, 184, .45);
  transition: transform .3s cubic-bezier(.22, 1, .36, 1), filter .2s ease;
}
.mp-primary:hover { filter: brightness(1.08); transform: translateY(-1px); }
.mp-primary:disabled { opacity: .6; cursor: default; transform: none; }
.mp-ghost {
  border: 1px solid rgba(19, 35, 58, .12); background: rgba(255, 255, 255, .8);
  color: var(--ll-brand-base, #13233A); font-weight: 650; font-size: 12.5px;
  padding: 8px 16px; border-radius: 12px; cursor: pointer;
  box-shadow: 0 8px 20px -14px rgba(19, 35, 58, .4);
  transition: transform .3s cubic-bezier(.22, 1, .36, 1);
}
.mp-ghost:hover { transform: translateY(-1px); }
.ai-intro { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.ai-intro p { margin: 0; font-size: 12px; line-height: 1.7; color: var(--ll-muted, #64748B); }
.ai-loading { display: flex; align-items: center; gap: 9px; padding: 18px 0; font-size: 12px; color: var(--ll-muted, #64748B); }
.ai-loading.small { padding: 10px 0 0; }
.ai-summary { margin: 0 0 10px; font-size: 12.5px; line-height: 1.85; color: var(--ll-brand-base, #13233A); font-weight: 600; }
.ai-drivers { display: flex; flex-direction: column; gap: 8px; margin-bottom: 10px; }
.ai-driver { display: flex; gap: 8px; align-items: flex-start; }
.ai-driver .impact { width: 18px; height: 18px; flex-shrink: 0; border-radius: 50%; display: grid; place-items: center; font-size: 10px; background: rgba(19, 35, 58, .06); color: var(--ll-muted, #64748B); }
.ai-driver .impact.up { background: rgba(155, 45, 32, .1); color: #9B2D20; }
.ai-driver .impact.down { background: rgba(46, 125, 91, .1); color: #2E7D5B; }
.ai-driver b { display: block; font-size: 11.5px; color: var(--ll-brand-base, #13233A); }
.ai-driver p { margin: 1px 0 0; font-size: 10.5px; line-height: 1.55; color: var(--ll-muted, #64748B); }
.ai-suggestion { margin: 0 0 8px; padding: 10px 12px; border-radius: 12px; background: rgba(200, 164, 92, .14); border-left: 2px solid var(--ll-brand-gold, #C8A45C); font-size: 11px; line-height: 1.7; color: var(--ll-brand-base, #13233A); }
.ai-fallback { margin: 0 0 8px; font-size: 9.5px; color: var(--ll-muted, #94A3B8); }
.ask-box { display: flex; gap: 8px; margin-top: auto; padding-top: 12px; }
.ask-box :deep(.ant-input-affix-wrapper) { border-radius: 12px; background: rgba(255, 255, 255, .85); border-color: rgba(19, 35, 58, .1); }
.quick-asks { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 8px; }
.quick-asks button { border: 1px solid rgba(19, 35, 58, .1); background: rgba(255, 255, 255, .7); color: var(--ll-muted, #64748B); font-size: 10px; padding: 3px 9px; border-radius: 99px; cursor: pointer; transition: border-color .2s ease, color .2s ease; }
.quick-asks button:hover { border-color: rgba(128, 96, 31, .35); color: var(--ll-brand-gold-strong, #80601F); }
.ask-result { margin-top: 10px; padding: 11px 12px; border-radius: 14px; background: rgba(255, 255, 255, .75); border: 1px solid rgba(19, 35, 58, .07); }
.ask-result b { display: block; font-size: 12px; line-height: 1.65; color: var(--ll-brand-base, #13233A); }
.ask-result p { margin: 5px 0 0; font-size: 10.5px; line-height: 1.6; color: var(--ll-muted, #64748B); }

@keyframes mpPulse { 0%, 100% { opacity: 1; transform: scale(1); } 50% { opacity: .55; transform: scale(.82); } }
@keyframes mpOrbit { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

@media (max-width: 1220px) {
  .mp-bento { grid-template-columns: 1fr; }
  .mp-lower { grid-template-columns: 1fr; }
}
@media (max-width: 900px) {
  .mp-head { flex-direction: column; align-items: flex-start; gap: 18px; }
  .mp-head-side { align-items: flex-start; }
  .mp-select { min-width: 100%; max-width: none; }
  .mp-live { margin-left: 0; }
  .mp-news-list { grid-template-columns: 1fr; }
  /* 窄屏裁剪装饰光晕：560px 模糊色斑溢出视口会让页面横向平移（VmmemWSL 之外的首页适配项） */
  .mp-section { overflow: hidden; }
  .mp-tint.a { width: 420px; height: 260px; right: -120px; }
  .mp-tint.b { width: 360px; height: 250px; left: -140px; }
}
@media (max-width: 640px) {
  .mp-section { padding: 30px 0 0; }
  .mp-head { padding: 0 4px; }
  .mp-head h2 { font-size: 27px; overflow-wrap: anywhere; }
  .mp-head h2 em { white-space: normal; }
  .mp-head-side { width: 100%; }
  .mp-card { padding: 18px; border-radius: 22px; }
  .mp-quote b { font-size: 25px; }
  .mp-title-row h3 { white-space: normal; max-width: none; }
  .mp-card-head { flex-direction: column; gap: 10px; }
  .mp-quote { text-align: left; }
  .mp-chart-foot span:nth-child(2) { display: none; }
  .mp-chart-foot button { margin-left: 0; }
  .ai-intro { flex-direction: column; align-items: flex-start; }
}
@media (prefers-reduced-motion: reduce) {
  .live-dot, .pulse, .ai-orbit i { animation: none; }
  .mp-card, .mp-news-list li, .mp-refresh, .mp-primary, .mp-ghost { transition: none; }
}
</style>