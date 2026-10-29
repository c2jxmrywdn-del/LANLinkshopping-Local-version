<template>
  <div class="ap-page">
    <div class="ap-head">
      <div>
        <h2>价格监控</h2>
        <p>全平台价格异动排行与行业行情基准，异常调价早发现。</p>
      </div>
      <a-button :loading="refreshing" @click="refresh">
        <ThunderboltOutlined /> 刷新行业基准
      </a-button>
    </div>

    <!-- 行业基准总览 -->
    <div class="ap-card">
      <div class="ap-card-head"><b>行业行情基准（最新）</b></div>
      <div class="bench-grid">
        <div v-for="b in benchmarks" :key="b.indId" class="bench-card">
          <span class="bench-name">{{ b.industryName }}</span>
          <b class="bench-val">¥{{ money(b.benchmark) }}</b>
          <span class="bench-meta">{{ b.day }} ·
            <i :class="Number(b.changePct) > 0 ? 'up' : Number(b.changePct) < 0 ? 'down' : 'flat'">
              {{ Number(b.changePct) > 0 ? '+' : '' }}{{ b.changePct }}%
            </i>
          </span>
        </div>
        <a-empty v-if="!benchmarks.length && !loading" description="暂无基准数据" style="padding: 12px" />
      </div>
    </div>

    <!-- 异动排行 -->
    <div class="ap-card">
      <div class="ap-card-head">
        <b>价格异动排行</b>
        <a-radio-group v-model:value="days" size="small" @change="loadAnomalies">
          <a-radio-button :value="1">今日</a-radio-button>
          <a-radio-button :value="7">近7天</a-radio-button>
          <a-radio-button :value="30">近30天</a-radio-button>
        </a-radio-group>
      </div>
      <a-table :data-source="anomalies" :columns="columns" row-key="id" size="small"
               :loading="loading" :pagination="{ pageSize: 10 }" :scroll="{ x: 760 }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'prodTitle'">
            <span class="ap-title">{{ record.prod_title || ('商品 #' + record.prod_id) }}</span>
          </template>
          <template v-else-if="column.key === 'change'">
            <b :class="Number(record.change_pct) > 0 ? 'up' : 'down'">
              {{ Number(record.change_pct) > 0 ? '+' : '' }}{{ record.change_pct }}%
            </b>
            <span class="ap-pair">（¥{{ record.old_price }} → ¥{{ record.new_price }}）</span>
          </template>
          <template v-else-if="column.key === 'severity'">
            <a-tag :color="Number(Math.abs(record.change_pct)) >= 20 ? 'red' : Number(Math.abs(record.change_pct)) >= 10 ? 'orange' : 'default'">
              {{ Math.abs(record.change_pct) >= 20 ? '高危' : Math.abs(record.change_pct) >= 10 ? '关注' : '正常' }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'source'">
            <a-tag :color="record.source === 'MERCHANT' ? 'blue' : record.source === 'ADMIN' ? 'purple' : 'default'">
              {{ { MERCHANT: '商户调价', ADMIN: '平台调价', SEED: '初始化' }[record.source] || record.source }}
            </a-tag>
          </template>
        </template>
      </a-table>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { ThunderboltOutlined } from '@ant-design/icons-vue'
import { priceMonitorApi } from '../../api'

const benchmarks = ref([])
const anomalies = ref([])
const days = ref(7)
const loading = ref(false)
const refreshing = ref(false)

const columns = [
  { key: 'prodTitle', title: '商品', ellipsis: true },
  { key: 'change', title: '变动', width: 220 },
  { key: 'severity', title: '级别', width: 80 },
  { key: 'source', title: '来源', width: 100 },
  { key: 'create_time', dataIndex: 'create_time', title: '时间', width: 160 }
]

const money = (v) => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

async function loadBenchmarks() {
  try { benchmarks.value = (await priceMonitorApi.adminBenchmarks()) || [] } catch (e) { /* 拦截器已提示 */ }
}
async function loadAnomalies() {
  loading.value = true
  try { anomalies.value = (await priceMonitorApi.adminAnomalies(days.value, 100)) || [] }
  catch (e) { /* 拦截器已提示 */ } finally { loading.value = false }
}
async function refresh() {
  refreshing.value = true
  try {
    const n = await priceMonitorApi.refresh?.() ?? null
    message.success('行业基准已刷新' + (n ? `（${n} 个行业）` : ''))
    loadBenchmarks()
  } catch (e) { /* 拦截器已提示 */ } finally { refreshing.value = false }
}

onMounted(() => { loadBenchmarks(); loadAnomalies() })
</script>

<style scoped>
.ap-page { padding: 4px 2px; }
.ap-head { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 18px; flex-wrap: wrap; }
.ap-head h2 { margin: 0 0 4px; font-size: 22px; letter-spacing: -.02em; }
.ap-head p { margin: 0; color: var(--ll-muted, #64748b); font-size: 13px; }
.ap-card { background: var(--ll-card, #fff); border-radius: 16px; padding: 18px 20px; margin-bottom: 16px;
           box-shadow: var(--ll-shadow-sm, 0 1px 2px rgba(16, 24, 40, .06)); }
.ap-card-head { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.ap-card-head b { font-size: 15px; }
.bench-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 12px; }
.bench-card { border: 1px solid var(--ll-border, #e5e7eb); border-radius: 12px; padding: 12px 16px; }
.bench-name { display: block; font-size: 12px; color: var(--ll-muted, #64748b); margin-bottom: 4px; }
.bench-val { display: block; font-size: 20px; font-weight: 700; letter-spacing: -.02em; font-variant-numeric: tabular-nums; }
.bench-meta { font-size: 11.5px; color: var(--ll-muted, #64748b); }
.bench-meta .up { color: #d93025; font-style: normal; font-weight: 600; }
.bench-meta .down { color: #047857; font-style: normal; font-weight: 600; }
.bench-meta .flat { color: var(--ll-muted, #64748b); font-style: normal; }
.ap-title { font-weight: 600; }
.ap-pair { color: var(--ll-muted, #64748b); font-size: 12px; font-variant-numeric: tabular-nums; }
.up { color: #d93025; }
.down { color: #047857; }
@media (max-width: 700px) {
  .ap-head { flex-direction: column; align-items: flex-start; }
}
</style>
