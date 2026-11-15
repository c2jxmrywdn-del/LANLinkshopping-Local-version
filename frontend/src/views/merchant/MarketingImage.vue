<template>
  <div class="mimg-page">
    <div class="mimg-head">
      <div>
        <h2>营销图生成</h2>
        <p>输入商品卖点，AI 生成可直接用于商品主图、活动海报的营销素材（自动带「AI 生成」水印）。</p>
      </div>
      <a-button :loading="loading" @click="reload">
        <RefreshCw :size="15" :stroke-width="2.2" style="vertical-align:-2px;margin-right:4px" /> 刷新
      </a-button>
    </div>

    <div class="mimg-grid">
      <!-- 创建任务 -->
      <a-card :bordered="false" class="mimg-card">
        <template #title>
          <span class="mimg-card-title"><Sparkles :size="16" :stroke-width="2.2" /> 创建生成任务</span>
        </template>
        <a-form layout="vertical" @submit.prevent>
          <a-form-item label="画面描述" required>
            <a-textarea v-model:value="form.prompt" :rows="4" :maxlength="800" show-count
                        placeholder="例如：秋季新品保温杯，暖色木桌背景，柔和侧光，电商主图风格，留白便于加文案" />
          </a-form-item>
          <a-form-item label="不要出现（选填）">
            <a-input v-model:value="form.negativePrompt" :maxlength="500" show-count
                     placeholder="例如：人物、文字、水印、杂乱背景" />
          </a-form-item>
          <div class="mimg-form-row">
            <a-form-item label="图片尺寸">
              <a-radio-group v-model:value="form.size">
                <a-radio-button value="1024x1024">1024×1024 方图</a-radio-button>
                <a-radio-button value="1280x720">1280×720 横图</a-radio-button>
              </a-radio-group>
            </a-form-item>
            <a-form-item label="模型">
              <a-radio-group v-model:value="form.mode" button-style="solid" size="small">
                <a-radio-button value="turbo">标准</a-radio-button>
                <a-radio-button value="pro">增强</a-radio-button>
                <a-radio-button value="mt">MT</a-radio-button>
              </a-radio-group>
            </a-form-item>
          </div>
          <a-button type="primary" size="large" block :loading="submitting" :disabled="!canSubmit"
                    @click="submit">
            生成营销图（约 ¥{{ unitPrice }} / 张）
          </a-button>
          <p class="mimg-tip">生成通常需要 10-60 秒，任务在后台执行；可离开本页，稍后回来查看结果。</p>
        </a-form>
      </a-card>

      <!-- 预算 / 统计 -->
      <div class="mimg-side">
        <a-card :bordered="false" class="mimg-card">
          <template #title>
            <span class="mimg-card-title"><Wallet :size="16" :stroke-width="2.2" /> 本月预算</span>
          </template>
          <div class="mimg-budget">
            <b>¥{{ budgetView.monthCost }}</b>
            <span>/ ¥{{ budgetView.budget }}</span>
          </div>
          <div class="mimg-bar">
            <i :style="{ width: budgetView.percent + '%' }" :class="{ warn: budgetView.percent >= 80 }"></i>
          </div>
          <div class="mimg-kv">
            <span>单张估算</span><b>¥{{ unitPrice }}</b>
          </div>
          <div class="mimg-kv">
            <span>剩余可生成</span><b>{{ budgetView.remainingCount }} 张</b>
          </div>
        </a-card>

        <a-card :bordered="false" class="mimg-card">
          <template #title>
            <span class="mimg-card-title"><BarChart3 :size="16" :stroke-width="2.2" /> 近 30 天</span>
          </template>
          <div class="mimg-kv"><span>提交任务</span><b>{{ stats.total }} 个</b></div>
          <div class="mimg-kv"><span>成功生成</span><b>{{ stats.success }} 张</b></div>
          <div class="mimg-kv"><span>累计花费</span><b>¥{{ stats.cost }}</b></div>
          <p class="mimg-tip">统计时间窗为最近 30 天，仅包含本账号提交的任务。</p>
        </a-card>

        <a-card :bordered="false" class="mimg-card mimg-note">
          <template #title>
            <span class="mimg-card-title"><ShieldCheck :size="16" :stroke-width="2.2" /> 使用提示</span>
          </template>
          <p>· 请使用自有商品素材与卖点描述，不要输入他人品牌名或违规内容。</p>
          <p>· 生成图已标注「AI 生成」，发布前请自行核对商品信息真实性。</p>
          <p>· 预算按自然月统计，额度用尽后需联系平台运营调整。</p>
        </a-card>
      </div>
    </div>

    <!-- 任务列表 -->
    <a-card :bordered="false" class="mimg-card mimg-list-card">
      <template #title>
        <span class="mimg-card-title"><Images :size="16" :stroke-width="2.2" /> 生成记录</span>
      </template>
      <a-table :data-source="tasks" :columns="columns" row-key="taskNo" size="small"
               :loading="loading" :pagination="pagination" :scroll="{ x: 760 }"
               @change="onTableChange">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'preview'">
            <a-image v-if="record.imageUrl" :src="record.imageUrl" :width="64" :height="64"
                     class="mimg-thumb" />
            <div v-else class="mimg-thumb placeholder">
              {{ record.status === 'PENDING' ? '生成中' : '—' }}
            </div>
          </template>
          <template v-else-if="column.key === 'prompt'">
            <a-tooltip :title="record.prompt">
              <div class="mimg-prompt">{{ record.prompt }}</div>
            </a-tooltip>
            <div class="mimg-sub">{{ record.size }} · {{ record.model }}</div>
          </template>
          <template v-else-if="column.key === 'status'">
            <a-tag :color="statusOf(record.status).color">{{ statusOf(record.status).text }}</a-tag>
          </template>
          <template v-else-if="column.key === 'info'">
            <div v-if="record.status === 'FAILED'" class="mimg-error" :title="record.errorMessage">
              {{ record.errorMessage || '生成失败' }}
            </div>
            <div v-else class="mimg-sub">{{ fmtTime(record.createdAt) }}</div>
            <div v-if="record.status === 'SUCCESS'" class="mimg-sub">花费 ¥{{ microsToYuan(record.costMicros) }}</div>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space :size="2">
              <a-button type="link" size="small" @click="retry(record)">再次生成</a-button>
              <a-button v-if="record.imageUrl" type="link" size="small" :href="record.imageUrl"
                        target="_blank" rel="noopener">查看原图</a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { marketingImageApi } from '../../api'
import { Sparkles, RefreshCw, Wallet, BarChart3, ShieldCheck, Images } from 'lucide-vue-next'

const form = ref({ prompt: '', negativePrompt: '', size: '1024x1024', mode: 'turbo' })
const tasks = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)
const submitting = ref(false)
const budget = ref(null)
const stats30d = ref(null)

const STATUS = {
  PENDING: { text: '生成中', color: 'processing' },
  SUCCESS: { text: '已完成', color: 'green' },
  FAILED: { text: '失败', color: 'red' }
}

const columns = [
  { key: 'preview', title: '预览', width: 90 },
  { key: 'prompt', title: '画面描述', ellipsis: true },
  { key: 'status', title: '状态', width: 92 },
  { key: 'info', title: '结果 / 时间', width: 200 },
  { key: 'action', title: '操作', width: 150 }
]

const canSubmit = computed(() => {
  const p = form.value.prompt.trim()
  return p.length > 0 && p.length <= 800 && !submitting.value
})

const unitPrice = computed(() => {
  const micros = Number(budget.value?.unitCostMicros || 220000)
  return (micros / 1e6).toFixed(2)
})

const budgetView = computed(() => {
  const b = budget.value || {}
  const used = Number(b.monthCostMicros || 0)
  const totalMicros = Number(b.monthlyBudgetMicros || 0)
  const unit = Number(b.unitCostMicros || 0)
  const remaining = Number(b.remainingMicros || 0)
  return {
    monthCost: (used / 1e6).toFixed(2),
    budget: (totalMicros / 1e6).toFixed(0),
    percent: totalMicros > 0 ? Math.min(100, Math.round((used / totalMicros) * 100)) : 0,
    remainingCount: unit > 0 ? Math.floor(remaining / unit) : 0
  }
})

const stats = computed(() => {
  const s = stats30d.value || {}
  return {
    total: Number(s.total || 0),
    success: Number(s.success || 0),
    cost: (Number(s.costMicros || 0) / 1e6).toFixed(2)
  }
})

const pagination = computed(() => ({
  current: page.value,
  pageSize: size.value,
  total: total.value,
  showSizeChanger: false,
  showTotal: (t) => `共 ${t} 条`
}))

function statusOf(status) {
  return STATUS[status] || { text: status || '未知', color: 'default' }
}

function microsToYuan(micros) {
  return (Number(micros || 0) / 1e6).toFixed(2)
}

function fmtTime(text) {
  if (!text) return '—'
  return String(text).replace('T', ' ').slice(0, 19)
}

// 轮询：只要当前页还有 PENDING 任务，就每 3 秒刷新一次；离开页面或全部完成即停止
let pollTimer = null
function schedulePoll() {
  clearPoll()
  if (!tasks.value.some((t) => t.status === 'PENDING')) return
  pollTimer = setTimeout(async () => {
    await load(false)
    schedulePoll()
  }, 3000)
}
function clearPoll() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

async function load(showLoading = true) {
  if (showLoading) loading.value = true
  try {
    const data = await marketingImageApi.list(page.value, size.value)
    tasks.value = data?.list || []
    total.value = Number(data?.total || 0)
    budget.value = data?.budget || null
    stats30d.value = data?.stats30d || null
    schedulePoll()
  } catch (e) {
    // request 拦截器已提示；这里只保证页面不白屏
  } finally {
    if (showLoading) loading.value = false
  }
}

function reload() {
  clearPoll()
  load()
}

function onTableChange(paginationConfig) {
  page.value = paginationConfig.current
  load()
}

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    await marketingImageApi.create({
      prompt: form.value.prompt.trim(),
      negativePrompt: form.value.negativePrompt.trim() || undefined,
      size: form.value.size,
      mode: form.value.mode
    })
    message.success('已提交生成，稍候自动刷新结果')
    form.value.prompt = ''
    form.value.negativePrompt = ''
    page.value = 1
    await load(false)
  } catch (e) {
    // 拦截器已展示后端返回的失败原因（含额度不足/敏感词/预算）
  } finally {
    submitting.value = false
  }
}

function retry(record) {
  form.value.prompt = record.prompt || ''
  form.value.negativePrompt = record.negativePrompt || ''
  form.value.size = record.size === '1280x720' ? '1280x720' : '1024x1024'
  form.value.mode = /mt/i.test(record.model || '') ? 'mt' : (/pro/i.test(record.model || '') ? 'pro' : 'turbo')
  submit()
}

onMounted(() => load())
onUnmounted(clearPoll)
</script>

<style scoped>
.mimg-page { display: flex; flex-direction: column; gap: 16px; }
.mimg-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.mimg-head h2 { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -.02em; color: var(--mc-ink, #1d1d1f); }
.mimg-head p { margin: 6px 0 0; font-size: 13px; color: var(--mc-muted, #6e6e73); line-height: 1.6; }

.mimg-grid { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(260px, 1fr); gap: 16px; align-items: start; }
.mimg-side { display: flex; flex-direction: column; gap: 16px; }
.mimg-card { background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1); border-radius: 16px; box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.mimg-card :deep(.ant-card-head) { border-bottom: 1px solid var(--mc-border-soft, #f0eee9); min-height: 46px; }
.mimg-card-title { display: inline-flex; align-items: center; gap: 6px; font-size: 14px; font-weight: 600; color: var(--mc-ink, #1d1d1f); }
.mimg-form-row { display: flex; gap: 20px; flex-wrap: wrap; }
.mimg-tip { margin: 10px 0 0; font-size: 12px; color: var(--mc-muted2, #86868b); line-height: 1.6; }

.mimg-budget { display: flex; align-items: baseline; gap: 6px; }
.mimg-budget b { font-size: 26px; letter-spacing: -.02em; color: var(--mc-ink, #1d1d1f); }
.mimg-budget span { font-size: 13px; color: var(--mc-muted2, #86868b); }
.mimg-bar { height: 8px; border-radius: 99px; background: var(--mc-soft, #f5f5f7); margin: 10px 0 14px; overflow: hidden; }
.mimg-bar i { display: block; height: 100%; border-radius: 99px; background: var(--mc-chart-solid, #38bdf8); transition: width .3s ease; }
.mimg-bar i.warn { background: var(--mc-accent, #9b2d20); }
.mimg-kv { display: flex; align-items: center; justify-content: space-between; padding: 4px 0; font-size: 13px; color: var(--mc-muted, #6e6e73); }
.mimg-kv b { color: var(--mc-ink, #1d1d1f); font-weight: 600; }
.mimg-note p { margin: 0 0 8px; font-size: 12px; color: var(--mc-muted, #6e6e73); line-height: 1.7; }
.mimg-note p:last-child { margin-bottom: 0; }

.mimg-list-card { margin-top: 2px; }
.mimg-thumb { width: 64px; height: 64px; border-radius: 10px; object-fit: cover; display: block; border: 1px solid var(--mc-border-soft, #f0eee9); }
.mimg-thumb.placeholder { display: grid; place-items: center; background: var(--mc-soft, #f5f5f7); color: var(--mc-faint, #a1a1a6); font-size: 11px; }
.mimg-prompt { font-size: 13px; color: var(--mc-ink, #1d1d1f); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 360px; }
.mimg-sub { margin-top: 2px; font-size: 11px; color: var(--mc-faint, #a1a1a6); }
.mimg-error { font-size: 12px; color: #c0392b; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }

@media (max-width: 991px) {
  .mimg-grid { grid-template-columns: 1fr; }
  .mimg-prompt { max-width: 200px; }
}
</style>
