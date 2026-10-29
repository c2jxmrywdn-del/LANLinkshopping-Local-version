<template>
  <div class="admin-kb">
    <a-card :bordered="false" class="card">
      <div class="page-head">
        <div>
          <div class="eyebrow">KNOWLEDGE · RAG</div>
          <h2>{{ t('admin.knowledge.title') }}</h2>
          <p class="lead">{{ t('admin.knowledge.lead') }}</p>
        </div>
        <a-button :loading="loadingStats" @click="loadStats">{{ t('admin.knowledge.refreshList') }}</a-button>
      </div>

      <!-- 库状态 -->
      <div class="stats-band">
        <div class="stat"><span class="k">{{ t('admin.knowledge.totalDocs') }}</span><b>{{ stats.totalDocs ?? 0 }}</b></div>
        <div class="stat"><span class="k">{{ t('admin.knowledge.dimension') }}</span><b>{{ stats.dim ?? 0 }}</b></div>
        <div class="stat"><span class="k">{{ t('admin.knowledge.embed') }}</span><b class="mono">{{ stats.embedProvider || '—' }}</b></div>
        <div class="stat"><span class="k">{{ t('admin.knowledge.chatModel') }}</span><b class="mono">{{ stats.chatModel || '—' }}</b></div>
        <div class="stat"><span class="k">{{ t('admin.knowledge.currentModel') }}</span><b class="mono">{{ currentModelText }}</b></div>
        <div class="stat"><span class="k">{{ t('admin.knowledge.indexMode') }}</span><b class="mono" :title="stats.indexMode || ''">{{ indexModeText }}</b></div>
        <div class="stat"><span class="k">{{ t('admin.knowledge.embedHitRate') }}</span><b>{{ embedHitRateText }}</b></div>
        <div class="stat"><span class="k">{{ t('admin.knowledge.searchAvg') }}</span><b>{{ searchAvgText }}</b></div>
      </div>
      <div class="sources">
        <span class="src-label">{{ t('admin.knowledge.sourcesTitle') }}:</span>
        <a-tag v-for="(cnt, key) in stats.sources || {}" :key="key" color="blue">{{ srcLabel(key) }} {{ cnt }}</a-tag>
      </div>

      <a-divider style="margin: 16px 0" />
      <!-- 外部资讯自动抓取（RSS → 市场资讯 → 向量库） -->
      <div class="block-title">{{ t('admin.knowledge.externalTitle') }}</div>
      <p class="ext-lead">{{ t('admin.knowledge.externalLead') }}</p>
      <div class="ext-actions">
        <a-button type="primary" :loading="fetching" @click="doFetchExternal">{{ t('admin.knowledge.externalFetchBtn') }}</a-button>
        <span class="ext-last">{{ t('admin.knowledge.externalLastRun') }}: {{ lastRunText }}</span>
      </div>
      <div class="ext-meta">
        <span>{{ t('admin.knowledge.externalFetched') }}: {{ externalInfo.fetched ?? '—' }}</span>
        <span>{{ t('admin.knowledge.externalInserted') }}: {{ externalInfo.inserted ?? '—' }}</span>
        <span>{{ t('admin.knowledge.externalSkipped') }}: {{ externalInfo.skipped ?? '—' }}</span>
        <span>{{ t('admin.knowledge.externalIngested') }}: {{ externalInfo.ingested ?? '—' }}</span>
        <span>{{ t('admin.knowledge.externalFeeds') }}: {{ externalFeedText }}</span>
        <span v-if="externalInfo.elapsedMs !== undefined && externalInfo.elapsedMs !== null">
          {{ t('admin.knowledge.elapsedLabel') }}: {{ externalInfo.elapsedMs }}ms
        </span>
      </div>
      <div v-if="externalInfo.error" class="ext-err">{{ t('admin.knowledge.externalError') }}: {{ externalInfo.error }}</div>
      <div v-if="externalItems.length" class="ext-items-wrap">
        <div class="ext-sub">{{ t('admin.knowledge.externalItems') }}</div>
        <ul class="ext-items">
          <li v-for="(it, i) in externalItems" :key="i">
            <a v-if="it.href" :href="it.href" target="_blank" rel="noopener noreferrer">{{ it.title || it.href }}</a>
            <span v-else>{{ it.title || '—' }}</span>
            <span v-if="it.source" class="ext-src">{{ it.source }}</span>
            <span v-if="it.category" class="ext-cat">{{ it.category }}</span>
          </li>
        </ul>
      </div>

      <a-divider style="margin: 16px 0" />
      <!-- 重新灌库 -->
      <div class="block-title">{{ t('admin.knowledge.ingestTitle') }}</div>
      <div class="ingest">
        <a-button type="primary" :loading="ingesting==='all'" @click="doIngest('all')">{{ t('admin.knowledge.ingestAll') }}</a-button>
        <a-button v-for="s in INGEST_SOURCES" :key="s" :loading="ingesting===s" @click="doIngest(s)">{{ srcLabel(s) }}</a-button>
      </div>
    </a-card>

    <!-- RAG 测试台 -->
    <a-card :bordered="false" class="card">
      <div class="block-title">{{ t('admin.knowledge.ragTitle') }}</div>
      <div class="rag-row">
        <a-input v-model:value="askQuestion" :placeholder="t('admin.knowledge.askPlaceholder')"
                 allow-clear @press-enter="doAsk" />
        <a-select v-model:value="askModel" class="rag-model">
          <a-select-option value="auto">{{ t('admin.knowledge.modelAuto') }}</a-select-option>
          <a-select-option value="vl">{{ t('admin.knowledge.modelVl') }}</a-select-option>
          <a-select-option value="text">{{ t('admin.knowledge.modelText') }}</a-select-option>
        </a-select>
        <a-button type="primary" :loading="asking" @click="doAsk">{{ t('admin.knowledge.run') }}</a-button>
      </div>
      <div v-if="asked" class="rag-result">
        <div class="rag-meta">
          <span v-if="ragRouteLabel">{{ t('admin.knowledge.ragRouted') }}: {{ ragRouteLabel }}<template v-if="rag.model"> · {{ rag.model }}</template></span>
          <span v-else-if="rag.model">{{ t('admin.knowledge.ragBackend') }}: {{ rag.model }}</span>
          <span>{{ t('admin.knowledge.hitsLabel') }}: {{ rag.hits ?? 0 }}</span>
          <span>{{ t('admin.knowledge.elapsedLabel') }}: {{ rag.elapsedMs ?? 0 }}ms</span>
          <span>{{ t('admin.knowledge.embed') }}: {{ rag.embedProvider || '—' }}</span>
        </div>
        <div class="rag-answer">
          <div class="ans-label">{{ t('admin.knowledge.answerLabel') }}</div>
          <p v-if="rag.answer" class="ans-text">{{ rag.answer }}</p>
          <p v-else class="ans-empty">{{ t('admin.knowledge.noAnswer') }}</p>
        </div>
        <div v-if="(rag.sources||[]).length" class="rag-sources">
          <a-tag v-for="(s, i) in rag.sources" :key="i" :color="srcTagColor(s.source)">{{ srcLabel(s.source) }} · {{ s.title }} ({{ s.score }})</a-tag>
        </div>
      </div>
    </a-card>

    <!-- 手工录入 + 条目管理 -->
    <a-card :bordered="false" class="card">
      <div class="block-title">{{ t('admin.knowledge.addTitle') }}</div>
      <div class="add-row">
        <a-select v-model:value="form.source" class="add-src">
          <a-select-option value="manual">{{ t('admin.knowledge.srcManual') }}</a-select-option>
          <a-select-option value="policy">{{ t('admin.knowledge.srcPolicy') }}</a-select-option>
        </a-select>
        <a-input v-model:value="form.title" class="add-title" :placeholder="t('admin.knowledge.titlePh')" />
      </div>
      <a-textarea v-model:value="form.text" :rows="3" :placeholder="t('admin.knowledge.textPh')" />
      <div v-if="formErr" class="form-err">{{ formErr }}</div>
      <div class="add-actions">
        <a-button type="primary" :loading="adding" @click="addEntry">{{ t('admin.knowledge.addBtn') }}</a-button>
      </div>

      <a-divider style="margin: 16px 0" />
      <div class="entries-head">
        <div class="block-title">{{ t('admin.knowledge.entriesTitle') }}</div>
        <a-space>
          <a-select v-model:value="filterSource" class="filter-src" :placeholder="t('admin.knowledge.filterSource')" allow-clear @change="loadEntries">
            <a-select-option value="">{{ t('admin.knowledge.allSources') }}</a-select-option>
            <a-select-option v-for="s in ALL_SOURCES" :key="s" :value="s">{{ srcLabel(s) }}</a-select-option>
          </a-select>
          <a-button :loading="loadingEntries" @click="loadEntries">{{ t('admin.knowledge.refreshList') }}</a-button>
        </a-space>
      </div>

      <a-table :data-source="entries" :columns="cols" row-key="id" size="small" :loading="loadingEntries"
               :pagination="{ pageSize: 10, showSizeChanger: false }" :scroll="{ x: 640 }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'source'">
            <a-tag :color="srcTagColor(record.source)">{{ srcLabel(record.source) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'op'">
            <a-popconfirm :title="t('admin.knowledge.confirmDelete')" :ok-text="t('admin.knowledge.delete')"
                          :cancel-text="t('admin.supportTickets.cancel')" @confirm="delEntry(record.id)">
              <a-button size="small" danger type="link">{{ t('admin.knowledge.delete') }}</a-button>
            </a-popconfirm>
          </template>
        </template>
        <template #expandedRowRender="{ record }">
          <div class="entry-text">{{ record.text }}</div>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { kbApi } from '../../api'
import { t } from '../../i18n'

const INGEST_SOURCES = ['kb', 'policy', 'membership', 'subscription', 'category', 'logistics', 'product', 'news']
const ALL_SOURCES = ['kb', 'policy', 'membership', 'subscription', 'category', 'logistics', 'product', 'news', 'learned', 'manual', 'intent']
const SRC_LABEL_KEY = {
  kb: 'srcKb', policy: 'srcPolicy', membership: 'srcMembership', subscription: 'srcSubscription',
  category: 'srcCategory', logistics: 'srcLogistics', product: 'srcProduct', news: 'srcNews',
  learned: 'srcLearned', manual: 'srcManual', intent: 'srcIntent'
}
const SRC_TAG = {
  kb: 'blue', policy: 'gold', membership: 'purple', subscription: 'magenta', category: 'cyan',
  logistics: 'geekblue', product: 'green', news: 'orange', learned: 'volcano', manual: 'default',
  intent: 'red'
}

const loadingStats = ref(false)
const ingesting = ref('')
const asking = ref(false)
const asked = ref(false)
const loadingEntries = ref(false)
const adding = ref(false)
const fetching = ref(false)

const stats = ref({})
const rag = ref({})
const askQuestion = ref('')
const askModel = ref('auto')
const fetchResult = ref(null)
const entries = ref([])
const filterSource = ref('')
const form = reactive({ source: 'manual', title: '', text: '' })
const formErr = ref('')

const cols = computed(() => [
  { key: 'source', title: t('admin.knowledge.colSource'), width: 130, dataIndex: 'source' },
  { key: 'title', title: t('admin.knowledge.colTitle'), dataIndex: 'title', ellipsis: true },
  { key: 'op', title: t('admin.knowledge.colOp'), width: 96 }
])

function srcLabel(s) { return t('admin.knowledge.' + (SRC_LABEL_KEY[s] || 'srcManual')) }
function srcTagColor(s) { return SRC_TAG[s] || 'default' }

// ===== 新增能力字段容错：后端并行开发中，字段缺失统一显示 — =====
const MODEL_LABEL_KEY = { auto: 'modelAuto', vl: 'modelVl', text: 'modelText' }
const EXTERNAL_FIELDS = ['lastRunAt', 'fetched', 'inserted', 'skipped', 'ingested', 'feeds', 'elapsedMs', 'error']

function isPresent(v) { return v !== undefined && v !== null && v !== '' }

function formatPercent(v) {
  if (!isPresent(v)) return '—'
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  const pct = n >= 0 && n <= 1 ? n * 100 : n
  return (pct >= 100 ? pct.toFixed(0) : pct.toFixed(1)).replace(/\.0$/, '') + '%'
}

function formatMs(v) {
  if (!isPresent(v)) return '—'
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  return (n >= 100 ? Math.round(n) : Math.round(n * 10) / 10) + 'ms'
}

function formatLastRun(v) {
  if (!isPresent(v)) return t('admin.knowledge.externalNever')
  let d = null
  if (typeof v === 'number') d = new Date(v < 1e12 ? v * 1000 : v)
  else {
    const s = String(v).trim()
    if (/^\d{10,13}$/.test(s)) {
      const n = Number(s)
      d = new Date(n < 1e12 ? n * 1000 : n)
    } else {
      d = new Date(s)
    }
  }
  return d && !Number.isNaN(d.getTime()) ? d.toLocaleString() : String(v)
}

const currentModelText = computed(() => {
  const cur = stats.value.models?.current
  if (!isPresent(cur)) return '—'
  return MODEL_LABEL_KEY[cur] ? t('admin.knowledge.' + MODEL_LABEL_KEY[cur]) : String(cur)
})

const indexModeText = computed(() => {
  const mode = stats.value.indexMode
  if (mode === 'redisearch-knn') return t('admin.knowledge.indexModeKnn')
  if (mode === 'cosine-fallback') return t('admin.knowledge.indexModeFallback')
  return isPresent(mode) ? String(mode) : '—'
})

const embedHitRateText = computed(() => {
  const cache = stats.value.embedCache || {}
  if (isPresent(cache.hitRate)) return formatPercent(cache.hitRate)
  const hit = isPresent(cache.hit) ? Number(cache.hit) : NaN
  const miss = isPresent(cache.miss) ? Number(cache.miss) : NaN
  if (Number.isFinite(hit) && Number.isFinite(miss) && hit + miss > 0) return formatPercent(hit / (hit + miss))
  return '—'
})

const searchAvgText = computed(() => formatMs(stats.value.search?.avgMs))

// 本次抓取结果优先，其次回退 stats.externalFetch：后端未升级时整块优雅降级为 —
const externalInfo = computed(() => {
  const base = stats.value.externalFetch || {}
  const run = fetchResult.value || {}
  const merged = { ...base }
  for (const field of EXTERNAL_FIELDS) if (isPresent(run[field])) merged[field] = run[field]
  return merged
})
const externalFeedText = computed(() => {
  const feeds = externalInfo.value.feeds
  if (Array.isArray(feeds)) return feeds.length
  return isPresent(feeds) ? feeds : '—'
})
const lastRunText = computed(() => formatLastRun(externalInfo.value.lastRunAt))
const externalItems = computed(() => {
  const items = fetchResult.value?.items
  if (!Array.isArray(items)) return []
  return items.slice(0, 8).map((it) => {
    const url = typeof it?.url === 'string' ? it.url.trim() : ''
    return {
      title: it?.title || '',
      source: it?.source || '',
      category: it?.category || '',
      // 外部 RSS 链接仅允许 http(s)，避免 javascript: 等注入
      href: /^https?:\/\//i.test(url) ? url : ''
    }
  })
})

const ragRouteLabel = computed(() => {
  if (rag.value.routed === 'vl') return t('admin.knowledge.ragRouteVl')
  if (rag.value.routed === 'text') return t('admin.knowledge.ragRouteText')
  return ''
})

async function loadStats() {
  loadingStats.value = true
  try { stats.value = await kbApi.stats() || {} }
  catch (e) { /* 拦截器已提示 */ }
  finally { loadingStats.value = false }
}
async function doIngest(source) {
  ingesting.value = source
  try {
    const r = await kbApi.ingest(source)
    message.success(t('admin.knowledge.ingested') + ' ' + JSON.stringify(r), 3)
    await loadStats()
    await loadEntries()
  } catch (e) { /* 拦截器已提示 */ }
  finally { ingesting.value = '' }
}
async function doAsk() {
  const q = askQuestion.value.trim()
  if (!q) return
  asking.value = true
  try {
    rag.value = await kbApi.ask({ question: q, topK: 5, model: askModel.value }) || {}
    asked.value = true
  } catch (e) { /* 拦截器已提示 */ }
  finally { asking.value = false }
}
async function doFetchExternal() {
  fetching.value = true
  try {
    const r = await kbApi.fetchExternal(30) || {}
    fetchResult.value = r
    if (r.error) {
      message.warning(t('admin.knowledge.externalError') + ': ' + r.error, 4)
    } else {
      message.success(t('admin.knowledge.externalDone', {
        fetched: r.fetched ?? 0,
        inserted: r.inserted ?? 0,
        skipped: r.skipped ?? 0,
        ingested: r.ingested ?? 0
      }), 3)
    }
    await loadStats()
    // 新资讯写入 news 来源，刷新后直接定位到市场资讯条目
    filterSource.value = 'news'
    await loadEntries()
  } catch (e) { /* 拦截器已提示 */ }
  finally { fetching.value = false }
}
async function loadEntries() {
  loadingEntries.value = true
  try {
    const params = { limit: 500 }
    if (filterSource.value) params.source = filterSource.value
    entries.value = await kbApi.entries(params) || []
  } catch (e) { entries.value = [] }
  finally { loadingEntries.value = false }
}
async function addEntry() {
  if (!form.text.trim()) { formErr.value = t('admin.knowledge.required'); return }
  formErr.value = ''
  adding.value = true
  try {
    await kbApi.addEntry({ source: form.source, title: form.title.trim(), text: form.text.trim() })
    message.success(t('admin.knowledge.added'), 2)
    form.title = ''; form.text = ''
    await loadStats()
    await loadEntries()
  } catch (e) { /* 拦截器已提示 */ }
  finally { adding.value = false }
}
async function delEntry(id) {
  try {
    await kbApi.deleteEntry(id)
    message.success(t('admin.knowledge.deleted'), 2)
    await loadStats()
    await loadEntries()
  } catch (e) { /* 拦截器已提示 */ }
}

onMounted(() => { loadStats(); loadEntries() })
</script>

<style scoped>
.admin-kb { max-width: 1200px; margin: 0 auto; display: flex; flex-direction: column; gap: 16px; }
.card { border-radius: 18px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.eyebrow { font-size: 10.5px; font-weight: 800; letter-spacing: .18em; color: var(--ll-brand-gold-strong, #92700F); }
.page-head h2 { margin: 6px 0 4px; font-size: 24px; letter-spacing: -.02em; color: var(--ll-ink, #0F172A); }
.lead { margin: 0; color: var(--ll-muted, #667085); font-size: 13px; }
.stats-band { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.stat { background: var(--ll-card-soft, #f6f7fb); border-radius: 12px; padding: 12px 14px; display: flex; flex-direction: column; gap: 4px; }
.stat .k { font-size: 11.5px; color: var(--ll-muted, #667085); }
.stat b { font-size: 18px; color: var(--ll-ink, #0F172A); }
.stat b.mono { font-size: 13px; word-break: break-all; }
.sources { margin-top: 14px; display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.src-label { font-size: 12.5px; color: var(--ll-muted, #667085); }
.ext-lead { margin: 0 0 12px; color: var(--ll-muted, #667085); font-size: 12.5px; line-height: 1.6; }
.ext-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 14px; }
.ext-last { font-size: 12.5px; color: var(--ll-muted, #667085); }
.ext-meta { margin-top: 10px; display: flex; flex-wrap: wrap; gap: 14px; font-size: 12.5px; color: var(--ll-muted, #667085); }
.ext-err { margin-top: 10px; padding: 8px 10px; border-radius: 8px; background: #fff2f0; color: #d4380d; font-size: 12.5px; word-break: break-all; }
.ext-items-wrap { margin-top: 12px; }
.ext-sub { font-size: 12px; font-weight: 600; color: var(--ll-ink, #0F172A); margin-bottom: 6px; }
.ext-items { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 6px; }
.ext-items li { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px; font-size: 12.5px; }
.ext-items a { color: var(--ll-brand-gold-strong, #92700F); }
.ext-src, .ext-cat { font-size: 11px; color: var(--ll-muted, #667085); }
.block-title { font-weight: 600; color: var(--ll-ink, #0F172A); font-size: 15px; margin-bottom: 12px; }
.ingest { display: flex; flex-wrap: wrap; gap: 10px; }
.rag-row { display: flex; gap: 10px; }
.rag-row :deep(.ant-input) { flex: 1; }
.rag-model { width: 190px; flex: 0 0 190px; }
.rag-result { margin-top: 14px; }
.rag-meta { display: flex; gap: 18px; font-size: 12px; color: var(--ll-muted, #667085); margin-bottom: 10px; flex-wrap: wrap; }
.rag-answer { background: var(--ll-brand-gold-soft, #F6EFCF); border-left: 3px solid var(--ll-brand-gold, #C9A24B); border-radius: 10px; padding: 12px 14px; }
.ans-label { font-size: 12px; color: var(--ll-brand-gold-strong, #92700F); font-weight: 700; margin-bottom: 4px; }
.ans-text { margin: 0; white-space: pre-wrap; color: var(--ll-ink, #0F172A); line-height: 1.7; }
.ans-empty { margin: 0; color: var(--ll-muted, #667085); }
.rag-sources { margin-top: 10px; display: flex; flex-wrap: wrap; gap: 6px; }
.add-row { display: flex; gap: 10px; margin-bottom: 10px; }
.add-src { width: 150px; }
.add-title { flex: 1; }
.form-err { color: #d4380d; font-size: 12px; margin-top: 6px; }
.add-actions { margin-top: 10px; }
.entries-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.entries-head .block-title { margin-bottom: 0; }
.filter-src { width: 160px; }
.entry-text { white-space: pre-wrap; color: #334155; font-size: 13px; line-height: 1.7; }
@media (max-width: 720px) {
  .stats-band { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .rag-row, .add-row { flex-wrap: wrap; }
  .rag-model { width: 100%; flex: 1 1 100%; }
}
</style>
