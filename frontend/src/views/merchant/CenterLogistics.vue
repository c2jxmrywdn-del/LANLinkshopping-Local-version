<template>
  <div class="lg-page">
    <section class="hero">
      <div class="hero-copy">
        <div class="eyebrow"><span class="dot"></span> 商户中心</div>
        <h1>物流发货</h1>
        <p>全部运单一屏管理：跟踪配送轨迹，买家签收后确认完成。</p>
      </div>
      <div class="hero-actions">
        <a-input-search v-model:value="orderNo" placeholder="按订单号筛选" allow-clear
                        class="search" @search="onSearch" @pressEnter="onSearch" />
        <a-select v-model:value="status" class="filter" @change="onSearch">
          <a-select-option :value="null">全部状态</a-select-option>
          <a-select-option v-for="(v, k) in WAYBILL_STATUS" :key="k" :value="k">{{ v.text }}</a-select-option>
        </a-select>
      </div>
    </section>

    <!-- 状态速览 -->
    <section class="mini-grid">
      <div v-for="s in summaryBars" :key="s.key" class="mini-card" @click="quickFilter(s.key)">
        <b>{{ s.count }}</b><span>{{ s.label }}</span>
      </div>
    </section>

    <a-card :bordered="false" class="panel">
      <a-table :data-source="rows" :columns="cols" row-key="waybillId" :loading="loading" class="mc-table"
               :scroll="{ x: 1000 }"
               :pagination="{ current, pageSize, total, showSizeChanger: false,
                              onChange: (p) => { current = p; load() } }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'waybillNo'">
            <div class="no-cell">
              <b class="mono">{{ record.waybillNo }}</b>
              <small v-if="record.logisticsNo">{{ record.logisticsNo }}</small>
            </div>
          </template>
          <template v-else-if="column.key === 'orderNo'">
            <a class="mono" @click="$router.push(`/merchant/center/orders/${record.orderNo}`)">{{ record.orderNo }}</a>
          </template>
          <template v-else-if="column.key === 'receiver'">
            <div class="buyer-cell">
              <span>{{ record.receiverName || '—' }}</span>
              <small>{{ maskPhone(record.receiverPhone) }}</small>
            </div>
          </template>
          <template v-else-if="column.key === 'status'">
            <a-tag :color="WAYBILL_STATUS[record.status]?.color">{{ WAYBILL_STATUS[record.status]?.text || record.status }}</a-tag>
          </template>
          <template v-else-if="column.key === 'shipTime'">{{ shortTime(record.shipTime) }}</template>
          <template v-else-if="column.key === 'op'">
            <a-space :size="0">
              <a-button type="link" size="small" @click="openTrace(record)">轨迹</a-button>
              <a-button v-if="signable(record)" type="link" size="small" @click="signConfirm(record)">确认签收</a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- L4：轨迹抽屉 -->
    <TraceDrawer v-model:open="traceOpen" :waybill="traceWaybill" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { logisticsApi } from '../../api'
import { WAYBILL_STATUS } from '../../utils/merchantStatus'
import TraceDrawer from '../../components/merchant/TraceDrawer.vue'

const rows = ref([])
const loading = ref(false)
const orderNo = ref('')
const status = ref(null)
const current = ref(1)
const pageSize = 10
const total = ref(0)
const traceOpen = ref(false)
const traceWaybill = ref(null)

const cols = [
  { title: '运单号', key: 'waybillNo', width: 168 },
  { title: '订单号', key: 'orderNo', width: 168 },
  { title: '收货人', key: 'receiver', width: 130 },
  { title: '状态', key: 'status', width: 96 },
  { title: '发货时间', key: 'shipTime', width: 140 },
  { title: '操作', key: 'op', width: 130 }
]

// 速览卡计数：独立拉取全量运单（无过滤、演示规模量级）统计，避免只数当前分页页
const summaryRows = ref([])
const summaryBars = computed(() => {
  const map = { pending_ship: '待揽收', picked_up: '已揽收', in_transit: '运输中', out_for_delivery: '派送中', delivered: '已签收', exception: '异常' }
  return Object.entries(map).map(([key, label]) => ({
    key, label, count: summaryRows.value.filter(r => r.status === key).length
  }))
})
async function loadSummary() {
  try {
    const page = await logisticsApi.waybillList({ page: 1, size: 500 })
    summaryRows.value = page.records || []
  } catch (e) { /* 速览失败可忽略 */ }
}

function shortTime(t) { return (t || '').replace('T', ' ').slice(0, 16) }
function maskPhone(p) {
  const s = String(p || '')
  return s.length === 11 ? s.slice(0, 3) + '****' + s.slice(7) : s
}
function signable(wb) {
  // 状态机仅允许「派送中 → 已签收」；运输中需等待服务商轨迹推进到派送中
  return wb.status === 'out_for_delivery'
}

function onSearch() { current.value = 1; load() }
function quickFilter(key) {
  status.value = status.value === key ? null : key
  onSearch()
}

async function load() {
  loading.value = true
  try {
    const page = await logisticsApi.waybillList({
      page: current.value, size: pageSize,
      status: status.value || undefined,
      orderNo: orderNo.value || undefined
    })
    rows.value = page.records || []
    total.value = Number(page.total || 0)
  } catch (e) { /* 拦截器已提示 */ }
  loading.value = false
}

function openTrace(wb) {
  traceWaybill.value = wb
  traceOpen.value = true
}

function signConfirm(wb) {
  Modal.confirm({
    title: '确认该运单已签收？',
    content: `运单 ${wb.waybillNo} 将被标记为「已签收」。请与买家确认后操作。`,
    okText: '确认签收', cancelText: '取消',
    onOk: async () => {
      try {
        await logisticsApi.transit(wb.waybillId, 'delivered', '商户确认签收')
        message.success('运单已签收')
        load()
        loadSummary()
      } catch (e) { /* 拦截器已提示 */ }
    }
  })
}

onMounted(() => { load(); loadSummary() })
</script>

<style scoped>
.lg-page { display: flex; flex-direction: column; gap: 14px; }
.hero { display: flex; justify-content: space-between; align-items: flex-end; gap: 14px; padding: 6px 2px 2px; }
.eyebrow { font-size: 12px; color: var(--mc-muted, #6e6e73); margin-bottom: 7px; letter-spacing: .04em; }
.dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: #9b2d20; margin-right: 7px; }
.hero h1 { margin: 0; font-size: 28px; font-weight: 750; letter-spacing: -.03em; color: var(--mc-ink, #1d1d1f); }
.hero p { margin: 8px 0 0; color: var(--mc-muted, #6e6e73); font-size: 14px; }
.hero-actions { display: flex; gap: 10px; flex-shrink: 0; }
.search { width: 230px; }
.filter { width: 130px; }

.mini-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; }
.mini-card { background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1); border-radius: 14px; padding: 12px 14px; cursor: pointer; transition: .18s ease; box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.mini-card:hover { transform: translateY(-2px); box-shadow: 0 8px 22px rgba(20, 24, 34, .08); }
.mini-card b { display: block; font-size: 20px; font-weight: 750; color: var(--mc-ink, #1d1d1f); letter-spacing: -.02em; }
.mini-card span { font-size: 11.5px; color: var(--mc-muted2, #86868b); }

.panel { border-radius: 16px; border: 1px solid var(--mc-border, #e8e6e1); box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12.5px; }
.no-cell b { display: block; color: var(--mc-ink, #1d1d1f); font-weight: 600; }
.no-cell small { color: var(--mc-faint, #a1a1a6); font-size: 11px; }
.buyer-cell span { display: block; font-size: 12.5px; color: var(--mc-ink, #1d1d1f); }
.buyer-cell small { color: var(--mc-faint, #a1a1a6); font-size: 11px; }

@media (max-width: 900px) {
  .hero { flex-direction: column; align-items: flex-start; }
  .hero h1 { font-size: 22px; }
  .hero-actions { width: 100%; }
  .search { flex: 1; width: auto; }
  .mini-grid { grid-template-columns: repeat(3, 1fr); }
}
</style>
