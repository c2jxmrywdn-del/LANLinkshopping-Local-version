<template>
  <section class="fs-card" aria-label="自主分析快照">
    <a-spin :spinning="loading" size="small">
      <!-- 空态：商品尚未被自动分析链纳入（未来发布商品最迟整点 50 分后完成） -->
      <template v-if="!hasSnapshot">
        <header class="fs-head">
          <div class="fs-head-copy">
            <span class="fs-kicker">AUTO SNAPSHOT</span>
            <h3>自主分析快照</h3>
          </div>
        </header>
        <div class="fs-empty">
          <p class="fs-empty-title">待自动分析，预计整点 50 分后完成</p>
          <p class="fs-empty-sub">自动分析链会为该商品生成五维因子与市场资讯摘要（经有害信息过滤）。</p>
        </div>
      </template>

      <!-- ① 头部：标题 + 自动分析时间标签（超过 48h 追加灰色「数据较旧」） -->
      <template v-else>
      <header class="fs-head">
        <div class="fs-head-copy">
          <span class="fs-kicker">AUTO SNAPSHOT</span>
          <h3>自主分析快照</h3>
        </div>
        <div class="fs-head-tags">
          <a-tag color="gold" class="fs-tag-auto">自动分析 {{ stampText }}</a-tag>
          <a-tag v-if="isDegraded" class="fs-tag-degraded" :title="degradedReason || undefined">网络资讯暂缺（降级）</a-tag>
          <a-tag v-if="isStale" class="fs-tag-stale">数据较旧</a-tag>
        </div>
      </header>

      <!-- ② 综合情绪指数：0-100 进度条 + 主导因子标签 -->
      <div class="fs-index">
        <div class="fs-index-top">
          <span class="fs-index-label">综合情绪指数</span>
          <a-tag v-if="dominant" class="fs-dominant" :class="'is-' + dominant.impact">
            {{ dominant.label }} {{ dominantArrow }}
          </a-tag>
        </div>
        <div class="fs-index-body">
          <b class="fs-index-num">{{ sentimentIndex }}<i>/ 100</i></b>
          <div class="fs-index-bar">
            <a-progress
              :percent="sentimentIndex"
              :show-info="false"
              :stroke-color="indexGradient"
              trail-color="rgba(19, 35, 58, .08)"
            />
            <div class="fs-index-scale" aria-hidden="true"><i>0</i><i>50</i><i>100</i></div>
          </div>
        </div>
      </div>

      <!-- ③ 五维热力条：score -100~100 以中轴映射，负分向左红调 / 正分向右绿调 -->
      <div class="fs-factors">
        <div v-for="(f, i) in factors" :key="f.key || 'fx' + i" class="fs-factor" :class="toneOf(f.score)">
          <div class="fs-factor-top">
            <span class="fs-factor-name" :title="f.headline || f.label">{{ f.label }}</span>
            <span class="fs-factor-score">{{ signedScore(f.score) }}</span>
          </div>
          <div class="fs-track" aria-hidden="true">
            <i class="fs-track-mid"></i>
            <i class="fs-track-fill" :style="fillStyle(f.score)"></i>
          </div>
          <div class="fs-factor-meta">
            <span>强度 {{ strengthText(f) }}</span>
            <span>{{ newsCountText(f) }} 条资讯</span>
          </div>
        </div>
      </div>

      <!-- ④ 网络资讯：自主检索的类目行情，最多 5 条，均经有害信息过滤 -->
      <div v-if="webItems.length" class="fs-web">
        <div class="fs-web-head">
          <span class="fs-web-title">网络资讯</span>
          <span class="fs-web-filter">经有害信息过滤</span>
        </div>
        <ul class="fs-web-list">
          <li v-for="(w, i) in webItems" :key="i">
            <a
              class="fs-web-link"
              :class="{ plain: !webHref(w) }"
              :href="webHref(w)"
              :target="webHref(w) ? '_blank' : undefined"
              :rel="webHref(w) ? 'noopener' : undefined"
              :title="webTitle(w)"
            >{{ webTitle(w) }}<span class="fs-ext" aria-hidden="true">↗</span></a>
            <p v-if="webSnippet(w)" class="fs-web-snippet">{{ webSnippet(w) }}</p>
          </li>
        </ul>
      </div>

      <!-- ⑤ 内容安全：快照生成时检出有害信息，给出原因提示 -->
      <a-alert
        v-if="safetyReason !== null"
        class="fs-alert"
        type="warning"
        show-icon
        :message="safetyReason"
      />
      </template>
    </a-spin>
  </section>
</template>

<script setup>
/**
 * 自主分析快照面板（factor-auto 自动分析链的展示端）：
 * 数据来自 GET /price-monitor/auto-profile/{prodId}（ProductFactorAutoService.profileOf），
 * 父组件负责取数与空白态；本组件只按 snapshot / loading 渲染。
 * 商户商品与检索数据保持后端原文（i18n 护栏不覆盖商品数据），仅界面文案走词典。
 */
import { computed } from 'vue'
import { safeHttpUrl } from '../../utils/newsLink'

const props = defineProps({
  /** auto-profile 的 data：{ payload, webInfoCount, generatedAt } */
  snapshot: { type: Object, default: null },
  loading: { type: Boolean, default: false }
})

/** 快照超过 48h 视为陈旧（后端每 24h 滚动重算，48h 是「两轮未跟上」的保守阈值） */
const STALE_MS = 48 * 60 * 60 * 1000

const payload = computed(() => (props.snapshot && typeof props.snapshot.payload === 'object' && props.snapshot.payload) ? props.snapshot.payload : {})
const hasSnapshot = computed(() => Object.keys(payload.value).length > 0)
const factors = computed(() => (Array.isArray(payload.value.factors) ? payload.value.factors : []).slice(0, 5))
const webItems = computed(() => (Array.isArray(payload.value.webInfo) ? payload.value.webInfo : []).slice(0, 5))

/** 网络检索降级标注（后端 analyzeOne 在检索链异常时写入 degraded=true，本地五维因子不受影响） */
const isDegraded = computed(() => payload.value.degraded === true)
const degradedReason = computed(() => String(payload.value.degradedReason || '').trim())

/** generatedAt 兼容三种形态：ISO 字符串 / MySQL "yyyy-MM-dd HH:mm:ss" / 纪元毫秒数 */
const generatedDate = computed(() => {
  const raw = props.snapshot?.generatedAt ?? payload.value.generatedAt
  if (raw == null || raw === '') return null
  const d = typeof raw === 'number' && Number.isFinite(raw) ? new Date(raw) : new Date(String(raw).replace(' ', 'T'))
  return Number.isNaN(d.getTime()) ? null : d
})
/** 金标内容：generatedAt 截取为 MM-dd HH:mm */
const stampText = computed(() => {
  const d = generatedDate.value
  if (d) {
    const p = n => String(n).padStart(2, '0')
    return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
  }
  const m = String(props.snapshot?.generatedAt ?? payload.value.generatedAt ?? '').match(/(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/)
  return m ? `${m[1]}-${m[2]} ${m[3]}:${m[4]}` : ''
})
const isStale = computed(() => {
  const d = generatedDate.value
  return !!d && Date.now() - d.getTime() > STALE_MS
})

/** 综合情绪指数：0-100，缺失或越界时收敛到该区间 */
const sentimentIndex = computed(() => {
  const n = Number(payload.value.sentimentIndex)
  return Number.isFinite(n) ? Math.min(100, Math.max(0, Math.round(n))) : 0
})
const indexGradient = { '0%': '#C8A45C', '100%': '#1E6EB8' }

/** 主导因子：后端为因子结构（key/label/impact/score...），仅取标签与方向 */
const dominant = computed(() => {
  const d = payload.value.dominant
  if (!d || typeof d !== 'object') return null
  const label = String(d.label || d.key || '').trim()
  if (!label) return null
  const impact = ['up', 'down'].includes(String(d.impact))
    ? String(d.impact)
    : (Number(d.score) > 0 ? 'up' : Number(d.score) < 0 ? 'down' : 'neutral')
  return { label, impact }
})
const dominantArrow = computed(() => {
  const i = dominant.value?.impact
  return i === 'up' ? '↑' : i === 'down' ? '↓' : '•'
})

/** contentSafety.clean=false 时返回提示文案（后端 reason 缺失时兜底），否则 null 不渲染 */
const safetyReason = computed(() => {
  const cs = payload.value.contentSafety
  if (!cs || cs.clean !== false) return null
  return String(cs.reason || '').trim() || '内容安全校验未通过'
})

function signedScore(v) {
  const n = Math.max(-100, Math.min(100, Math.round(Number(v) || 0)))
  return (n > 0 ? '+' : '') + n
}
function toneOf(v) {
  const n = Number(v) || 0
  return n > 0 ? 'is-pos' : n < 0 ? 'is-neg' : 'is-flat'
}
/** 热力条填充：|score|/2 即从中轴到端点的宽度，负分锚右侧、正分锚左侧 */
function fillStyle(v) {
  const n = Math.max(-100, Math.min(100, Number(v) || 0))
  const width = (Math.abs(n) / 2) + '%'
  return n >= 0 ? { left: '50%', width } : { right: '50%', width }
}
function strengthText(f) {
  return Math.min(100, Math.max(0, Math.round(Number(f?.strength) || 0)))
}
function newsCountText(f) {
  return Math.max(0, Math.round(Number(f?.newsCount) || 0))
}
function webTitle(w) {
  return String(w?.title || '').trim() || '—'
}
/** 摘要压成单行后截 60 字 */
function webSnippet(w) {
  const s = String(w?.snippet || '').replace(/\s+/g, ' ').trim()
  if (!s) return ''
  return s.length > 60 ? s.slice(0, 60) + '…' : s
}
/** 仅放行 http/https 绝对地址，非法链接降级为纯文本（不可点击） */
function webHref(w) {
  return safeHttpUrl(w?.url) || undefined
}
</script>

<style scoped>
/* 页面 --pi-* 变量（PriceIntelligence 页内自动继承；独立使用时取兜底值） */
.fs-card {
  padding: 20px 22px;
  border-radius: 26px;
  border: 1px solid rgba(19, 35, 58, .07);
  background: linear-gradient(180deg, rgba(255, 255, 255, .92) 0%, rgba(255, 255, 255, .7) 100%);
  box-shadow: 0 30px 72px -46px rgba(19, 35, 58, .42), inset 0 1px 0 rgba(255, 255, 255, .95);
  backdrop-filter: blur(20px) saturate(1.35);
  color: var(--pi-ink, #1B2434);
  transition: transform .5s cubic-bezier(.22, 1, .36, 1), box-shadow .5s ease;
}
.fs-card:hover {
.fs-empty { padding: 6px 0 2px; }
.fs-empty-title { margin: 0 0 4px; color: #13233A; font-size: 13px; font-weight: 700; }
.fs-empty-sub { margin: 0; color: #98A2B3; font-size: 11.5px; line-height: 1.7; }
  transform: translateY(-2px);
  box-shadow: 0 42px 92px -50px rgba(19, 35, 58, .5), inset 0 1px 0 rgba(255, 255, 255, .95);
}

/* ① 头部 */
.fs-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.fs-kicker { display: block; font-size: 9.5px; font-weight: 800; letter-spacing: .17em; color: var(--pi-gold-strong, #80601F); margin-bottom: 6px; }
.fs-head h3 { margin: 0; font-size: 16px; font-weight: 700; letter-spacing: -.02em; color: var(--pi-ink, #1B2434); }
.fs-head-tags { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; margin-right: 0; }
.fs-tag-auto { margin: 0; border-radius: 999px; font-variant-numeric: tabular-nums; }
.fs-tag-stale { margin: 0; border-radius: 999px; color: var(--pi-muted, #6B6F76); }
/* 网络资讯降级标注：红调警示但不阻断（五维因子仍为本地确定性计算结果） */
.fs-tag-degraded { margin: 0; border-radius: 999px; color: #9B2D20; background: rgba(155, 45, 32, .07); border-color: rgba(155, 45, 32, .22); }

/* ② 综合情绪指数 */
.fs-index { margin-top: 16px; padding: 14px 16px; border-radius: 16px; background: var(--pi-paper, #F8F4EC); border: 1px solid var(--pi-line, rgba(19, 35, 58, .09)); }
.fs-index-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.fs-index-label { font-size: 12px; font-weight: 700; letter-spacing: .04em; color: var(--pi-muted, #6B6F76); }
.fs-dominant { margin: 0; border-radius: 999px; font-size: 11px; max-width: 60%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 与热力条同语义：正分（up）绿调 / 负分（down）红调 / 中性灰 */
.fs-dominant.is-up { background: rgba(4, 120, 87, .09); border-color: rgba(4, 120, 87, .28); color: #047857; }
.fs-dominant.is-down { background: rgba(155, 45, 32, .09); border-color: rgba(155, 45, 32, .28); color: #9B2D20; }
.fs-index-body { display: flex; align-items: center; gap: 16px; margin-top: 10px; }
.fs-index-num { flex: none; font-size: 30px; font-weight: 750; letter-spacing: -.03em; color: var(--pi-ink, #1B2434); font-variant-numeric: tabular-nums; line-height: 1; }
.fs-index-num i { font-style: normal; margin-left: 3px; font-size: 11px; font-weight: 600; color: var(--pi-muted, #6B6F76); }
.fs-index-bar { flex: 1; min-width: 0; }
.fs-index-bar :deep(.ant-progress-line) { margin: 0; font-size: 0; }
.fs-index-scale { display: flex; justify-content: space-between; margin-top: 2px; font-size: 9px; color: var(--pi-muted, #94A3B8); font-variant-numeric: tabular-nums; }

/* ③ 五维热力条 */
.fs-factors { display: grid; gap: 12px; margin-top: 16px; }
.fs-factor-top { display: flex; align-items: baseline; gap: 8px; }
.fs-factor-name { min-width: 0; font-size: 12.5px; font-weight: 650; color: var(--pi-ink, #1B2434); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fs-factor-score { margin-left: auto; font-size: 11.5px; font-weight: 750; color: var(--pi-muted, #6B6F76); font-variant-numeric: tabular-nums; }
.fs-factor.is-pos .fs-factor-score { color: #047857; }
.fs-factor.is-neg .fs-factor-score { color: #9B2D20; }
.fs-track { position: relative; height: 6px; margin-top: 5px; border-radius: 999px; background: rgba(19, 35, 58, .06); overflow: hidden; }
.fs-track-mid { position: absolute; left: 50%; top: 0; bottom: 0; width: 1px; background: rgba(19, 35, 58, .18); z-index: 1; }
.fs-track-fill { position: absolute; top: 0; bottom: 0; border-radius: 999px; transition: width .5s cubic-bezier(.22, 1, .36, 1); }
/* 负分：从中轴向左、越远越红；正分：从中轴向右、越远越绿 */
.fs-factor.is-pos .fs-track-fill { background: linear-gradient(90deg, rgba(4, 120, 87, .35), #047857); }
.fs-factor.is-neg .fs-track-fill { background: linear-gradient(270deg, rgba(155, 45, 32, .35), #9B2D20); }
.fs-factor-meta { display: flex; justify-content: space-between; gap: 8px; margin-top: 4px; font-size: 10.5px; color: var(--pi-muted, #6B6F76); font-variant-numeric: tabular-nums; }

/* ④ 网络资讯 */
.fs-web { margin-top: 16px; padding-top: 12px; border-top: 1px solid var(--pi-line, rgba(19, 35, 58, .09)); }
.fs-web-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.fs-web-title { font-size: 12.5px; font-weight: 700; color: var(--pi-ink, #1B2434); }
.fs-web-filter { font-size: 9.5px; font-weight: 700; padding: 2px 8px; border-radius: 999px; background: #EDF3EE; color: #2F6B46; }
.fs-web-list { list-style: none; margin: 10px 0 0; padding: 0; display: grid; gap: 8px; }
.fs-web-list li { min-width: 0; padding: 8px 10px; border-radius: 12px; background: rgba(19, 35, 58, .03); }
.fs-web-link { display: block; font-size: 12px; font-weight: 650; line-height: 1.5; color: var(--pi-ink, #1B2434); text-decoration: none; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
a.fs-web-link:hover { color: #1E6EB8; }
.fs-web-link.plain { cursor: default; }
.fs-ext { margin-left: 3px; font-size: 9px; color: var(--pi-gold-strong, #80601F); }
.fs-web-snippet { margin: 3px 0 0; font-size: 10.5px; line-height: 1.6; color: var(--pi-muted, #6B6F76); }

/* ⑤ 内容安全告警 */
.fs-alert { margin-top: 14px; border-radius: 12px; }

@media (max-width: 640px) {
  .fs-card { padding: 16px; border-radius: 22px; }
  .fs-head { flex-direction: column; }
  .fs-head-tags { flex-direction: row; align-items: center; }
  .fs-index-body { gap: 12px; }
  .fs-index-num { font-size: 24px; }
}
@media (prefers-reduced-motion: reduce) {
  .fs-card, .fs-track-fill { transition: none; }
}
</style>
