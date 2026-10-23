<template>
  <div class="import-page">
    <a-button type="link" @click="$router.push('/merchant/products')">← 我的商品</a-button>
    <a-card :bordered="false" class="card">
      <div class="eyebrow">WORKFLOW / BATCH IMPORT</div>
      <h1>商品批量导入</h1>
      <p class="lead">把商品清单交给可恢复工作流：逐条发布、失败自动重试，进程中断也能从断点继续。</p>

      <a-tabs v-model:activeKey="mode">
        <a-tab-pane key="json" tab="粘贴 JSON">
          <a-textarea v-model:value="jsonText" :rows="8" :placeholder="jsonPlaceholder" />
        </a-tab-pane>
        <a-tab-pane key="csv" tab="上传 CSV">
          <a-upload :before-upload="pickCsv" :show-upload-list="false" accept=".csv,text/csv">
            <a-button>选择 CSV 文件</a-button>
          </a-upload>
          <div v-if="csvName" class="csv-name">已选：{{ csvName }}</div>
          <a-textarea v-model:value="csvText" :rows="8" class="csv-box"
            placeholder="title,brand,spec,price,indId,catId,coverUrl,stock,detail" />
        </a-tab-pane>
      </a-tabs>

      <div class="actions">
        <a-button type="primary" :loading="starting" :disabled="running || !hasInput" @click="start">开始导入</a-button>
        <a-button v-if="running" @click="stopPolling">停止轮询</a-button>
        <a-button @click="reset">清空</a-button>
      </div>

      <a-alert v-if="error" type="error" :message="error" show-icon class="alert" />
      <a-alert v-if="fieldErrors.length" type="error" class="alert" show-icon
               message="以下行未通过校验，请修正后重试">
        <template #description>
          <ul class="field-errors">
            <li v-for="(e, i) in fieldErrors" :key="i">{{ e }}</li>
          </ul>
        </template>
      </a-alert>

      <div v-if="runId" class="progress-wrap">
        <div class="progress-head">
          <span>运行 ID：<code>{{ runId }}</code></span>
          <a-tag :color="statusColor">{{ statusText }}</a-tag>
        </div>
        <a-progress :percent="percent" :status="percentStatus" />
        <a-table v-if="result" :data-source="result.results" :columns="cols" row-key="index"
          size="small" :pagination="false" class="result-table">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'ok'">
              <a-tag :color="record.ok ? 'green' : 'red'">{{ record.ok ? '成功' : '失败' }}</a-tag>
            </template>
          </template>
        </a-table>
        <div v-if="result" class="summary">
          {{ summaryText }}
        </div>
      </div>
    </a-card>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { message } from 'ant-design-vue'
import { workflowApi } from '../api'
import { locale } from '../i18n'

const mode = ref('json')
const jsonText = ref('')
const csvText = ref('')
const csvName = ref('')
const starting = ref(false)
const running = ref(false)
const error = ref('')
const fieldErrors = ref([])
const runId = ref('')
const status = ref('')
const result = ref(null)
let timer = null

const cols = [
  { title: '#', dataIndex: 'index', width: 50 },
  { title: '商品名称', dataIndex: 'title' },
  { title: '结果', key: 'ok', width: 90 },
  { title: '商品 ID', dataIndex: 'prodId', width: 110 },
  { title: '说明', dataIndex: 'error' },
]

const hasInput = computed(() =>
  mode.value === 'json' ? jsonText.value.trim().length > 0 : csvText.value.trim().length > 0)
const statusText = computed(() =>
  status.value === 'completed' ? '已完成' : status.value === 'failed' ? '失败' : status.value === 'running' ? '进行中' : '排队中')
const statusColor = computed(() =>
  status.value === 'completed' ? 'green' : status.value === 'failed' ? 'red' : 'blue')
const percent = computed(() => {
  if (status.value === 'completed') return 100
  if (!result.value || !result.value.total) return 10
  return Math.round((result.value.results.length / result.value.total) * 100)
})
const percentStatus = computed(() =>
  status.value === 'failed' ? 'exception' : status.value === 'completed' ? 'success' : 'active')

// 汇总行整体构造为单个字符串节点：DOM 翻译层无法处理被插值拆散的多个文本节点，
// 若逐段替换会得到 "Total 5 items, 3 failed 2 条。" 式乱序残留，故在此按语言直接成句。
const summaryText = computed(() => {
  const r = result.value
  if (!r) return ''
  if (locale.value === 'en-US') {
    return `${r.total} in total · ${r.succeeded} succeeded · ${r.failed} failed.`
  }
  return `共 ${r.total} 条，成功 ${r.succeeded} 条，失败 ${r.failed} 条。`
})

// 示例数据（JSON 占位符）里的中文随语言切换为等义英文，避免英文模式下示例残留中文。
const jsonPlaceholder = computed(() =>
  locale.value === 'en-US'
    ? '[{"title":"Portland Cement P.O42.5 (bagged)","brand":"Huaxin","spec":"50kg/bag","price":420,"indId":1,"catId":2,"coverUrl":"/uploads/cement.jpg","stock":8000,"detail":"Direct from the manufacturer"}]'
    : '[{"title":"硅酸盐水泥 P.O42.5 袋装","brand":"华新","spec":"50kg/袋","price":420,"indId":1,"catId":2,"coverUrl":"/uploads/cement.jpg","stock":8000,"detail":"源头厂商直供"}]'
)

function pickCsv(file) {
  const reader = new FileReader()
  reader.onload = () => { csvText.value = String(reader.result || ''); csvName.value = file.name }
  reader.readAsText(file, 'utf-8')
  return false
}

function payload() {
  if (mode.value === 'csv') return { format: 'csv', data: csvText.value }
  let parsed
  try { parsed = JSON.parse(jsonText.value) } catch { throw new Error('JSON 格式不正确') }
  return { format: 'json', data: parsed }
}

async function start() {
  error.value = ''
  let body
  try { body = payload() } catch (e) { error.value = e.message; return }
  starting.value = true
  try {
    const res = await workflowApi.startProductImport(body)
    if (res.error) {
      error.value = res.error
      fieldErrors.value = Array.isArray(res.errors) ? res.errors : []
      return
    }
    fieldErrors.value = []
    runId.value = res.runId
    result.value = null
    status.value = 'running'
    running.value = true
    poll()
  } catch (e) {
    error.value = '无法连接工作流服务，请确认 workflow/ 已启动（默认 http://localhost:3000）'
  } finally {
    starting.value = false
  }
}

function poll() {
  stopPolling()
  timer = window.setInterval(async () => {
    try {
      const res = await workflowApi.importStatus(runId.value)
      status.value = res.status || status.value
      if (res.result) result.value = res.result
      if (res.status === 'completed') {
        stopPolling()
        message.success('批量导入完成')
      } else if (res.status === 'failed') {
        stopPolling()
        error.value = res.error || '工作流执行失败'
      }
    } catch (e) {
      stopPolling()
      error.value = '状态查询中断，请稍后重试'
    }
  }, 1500)
}

function stopPolling() {
  if (timer) { window.clearInterval(timer); timer = null }
  running.value = false
}

function reset() {
  stopPolling()
  jsonText.value = ''; csvText.value = ''; csvName.value = ''
  runId.value = ''; result.value = null; status.value = ''; error.value = ''
  fieldErrors.value = []
}

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.import-page { max-width: 900px; margin: 0 auto; }
.card { border-radius: 18px; }
.eyebrow { font-size: 11px; letter-spacing: .16em; color: var(--ll-primary); }
h1 { margin: 6px 0 8px; font-size: 26px; }
.lead { color: var(--ll-muted); margin: 0 0 18px; }
.actions { display: flex; gap: 10px; margin: 16px 0; }
.alert { margin-bottom: 12px; }
.field-errors { margin: 0; padding-left: 18px; }
.field-errors li { margin: 2px 0; font-size: 13px; }
.csv-name { margin: 8px 0; color: var(--ll-muted); font-size: 12px; }
.csv-box { margin-top: 8px; }
.progress-wrap { margin-top: 8px; }
.progress-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; font-size: 13px; }
.result-table { margin-top: 12px; }
.summary { margin-top: 10px; color: var(--ll-muted); font-size: 13px; }
</style>
