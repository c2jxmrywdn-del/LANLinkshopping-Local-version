<template>
  <div class="al-page">
    <div class="al-head">
      <div>
        <h2>直播管理</h2>
        <p>全平台直播间治理与复盘，异常直播间可强制关播。</p>
      </div>
      <a-radio-group v-model:value="status" size="small" @change="load">
        <a-radio-button value="">全部</a-radio-button>
        <a-radio-button :value="1">直播中</a-radio-button>
        <a-radio-button :value="0">预告</a-radio-button>
        <a-radio-button :value="2">已结束</a-radio-button>
      </a-radio-group>
    </div>

    <div class="al-card">
      <a-table
        :data-source="rooms"
        :columns="columns"
        row-key="roomId"
        size="small"
        :loading="loading"
        :pagination="{ pageSize: 10 }"
        :scroll="{ x: 860 }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'roomId'">
            <span class="al-room-id">#{{ record.roomId }}</span>
          </template>
          <template v-else-if="column.key === 'title'">
            <span class="al-title">{{ record.title }}</span>
          </template>
          <template v-else-if="column.key === 'status'">
            <a-tag :color="Number(record.status) === 1 ? 'red' : Number(record.status) === 0 ? 'blue' : 'default'">
              <template v-if="Number(record.status) === 1"><span class="al-dot" aria-hidden="true"></span></template>
              {{ statusText(record.status) }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'reviewStatus'">
            <a-tag v-if="Number(record.reviewStatus) === 0" color="warning">待审核</a-tag>
            <a-tag v-else-if="Number(record.reviewStatus) === 1" color="success">已通过</a-tag>
            <a-tag v-else-if="Number(record.reviewStatus) === 2" color="error">已驳回</a-tag>
            <span v-else class="al-muted">—</span>
          </template>
          <template v-else-if="column.key === 'viewerCount'">
            <EyeOutlined style="margin-right:4px" />{{ record.viewerCount || 0 }}
          </template>
          <template v-else-if="column.key === 'likeCount'">{{ record.likeCount || 0 }}</template>
          <template v-else-if="column.key === 'startTime'">{{ record.startTime || '—' }}</template>
          <template v-else-if="column.key === 'action'">
            <a-button type="link" size="small" @click="openReview(record)">查看复盘</a-button>
            <template v-if="Number(record.reviewStatus) === 0">
              <a-button type="link" size="small" class="al-approve" :loading="approving[record.roomId]" @click="approveRoom(record)">批准</a-button>
              <a-button type="link" size="small" danger :loading="approving[record.roomId]" @click="rejectRoom(record)">驳回</a-button>
            </template>
            <a-button v-if="Number(record.status) !== 2" type="link" size="small" danger @click="forceClose(record)">下架</a-button>
          </template>
        </template>
      </a-table>
    </div>

    <!-- 复盘抽屉 -->
    <a-drawer v-model:open="reviewOpen" :title="reviewTitle" placement="right" :width="drawerWidth">
      <a-spin :spinning="reviewLoading">
        <template v-if="review">
          <div class="al-kpis">
            <div class="kpi"><b>¥<NumberTicker :value="Number(review.totalGmv || 0)" :decimal-places="2" /></b><span>总成交（GMV）</span></div>
            <div class="kpi"><b><NumberTicker :value="Number(review.totalOrders || 0)" /></b><span>总单数</span></div>
            <div class="kpi"><b><NumberTicker :value="Number(review.room ? review.room.peakViewerCount : 0)" /></b><span>峰值观看</span></div>
            <div class="kpi"><b><NumberTicker :value="Number(review.commentCount || 0)" /></b><span>弹幕数</span></div>
          </div>
          <div class="al-funnel-head">商品漏斗（曝光 = 挂载 → 点击 → 成交）</div>
          <a-table
            :data-source="(review.products || [])"
            :columns="funnelColumns"
            row-key="id"
            size="small"
            :pagination="false"
            :scroll="{ x: 640 }"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'title'"><span class="al-title">{{ record.title }}</span></template>
              <template v-else-if="column.key === 'exposure'"><a-tag color="blue">挂载</a-tag></template>
              <template v-else-if="column.key === 'gmv'">¥{{ money(record.gmv) }}</template>
              <template v-else-if="column.key === 'cvr'">{{ cvr(record) }}</template>
            </template>
          </a-table>
        </template>
        <a-empty v-else-if="!reviewLoading" description="暂无复盘数据" style="padding: 32px 0" />
      </a-spin>
    </a-drawer>
  </div>
</template>

<script setup>
/**
 * 管理端直播治理（/admin/live）：
 * - 全部直播间表格（状态过滤），平台审核（批准/驳回 + 驳回原因），强制关播（下架，status != 2）
 * - 查看复盘：drawer 拉取 /admin/live/{roomId}/stats，KPI + 商品漏斗
 * 风格对齐 AdminPriceMonitor（--ll-* 变量 + 白卡 + 细表格）。
 */
import { computed, h, onMounted, reactive, ref } from 'vue'
import { Modal, message } from 'ant-design-vue'
import { EyeOutlined } from '@ant-design/icons-vue'
import { liveApi } from '../../api'
import NumberTicker from '../../components/ui/NumberTicker.vue'

const status = ref('')
const rooms = ref([])
const loading = ref(false)

const approving = reactive({}) // 行级防重：roomId -> loading
const reviewOpen = ref(false)
const reviewLoading = ref(false)
const review = ref(null)
const reviewRoom = ref(null)

const columns = [
  { key: 'roomId', title: '房间', width: 76 },
  { key: 'merchantName', title: '商户', ellipsis: true, width: 140 },
  { key: 'title', title: '主题', ellipsis: true },
  { key: 'status', title: '状态', width: 96 },
  { key: 'reviewStatus', title: '审核状态', width: 100 },
  { key: 'viewerCount', title: '在线', width: 84 },
  { key: 'peakViewerCount', dataIndex: 'peakViewerCount', title: '峰值', width: 84 },
  { key: 'likeCount', title: '点赞', width: 84 },
  { key: 'startTime', title: '开播时间', width: 170 },
  { key: 'action', title: '操作', width: 226, fixed: 'right' }
]
const funnelColumns = [
  { key: 'title', title: '商品', ellipsis: true },
  { key: 'exposure', title: '曝光', width: 80 },
  { key: 'clickCount', dataIndex: 'clickCount', title: '点击', width: 80 },
  { key: 'orders', dataIndex: 'orders', title: '成交单数', width: 90 },
  { key: 'gmv', title: '成交额', width: 100 },
  { key: 'cvr', title: '转化率', width: 90 }
]

const money = (v) => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const cvr = (r) => (Number(r.clickCount) > 0 ? ((Number(r.orders || 0) / Number(r.clickCount)) * 100).toFixed(1) + '%' : '—')
const statusText = (s) => ({ 0: '预告', 1: '直播中', 2: '已结束' }[Number(s)] || '直播')

const reviewTitle = computed(() =>
  reviewRoom.value ? `复盘 · ${reviewRoom.value.title || ('房间 #' + reviewRoom.value.roomId)}` : '复盘')
const drawerWidth = computed(() => (window.innerWidth <= 560 ? window.innerWidth : 560))

async function load() {
  loading.value = true
  try {
    rooms.value = (await liveApi.adminRooms(status.value === '' ? undefined : status.value)) || []
  } catch (e) { /* 拦截器已提示 */ }
  loading.value = false
}

async function openReview(record) {
  reviewRoom.value = record
  review.value = null
  reviewOpen.value = true
  reviewLoading.value = true
  try { review.value = await liveApi.adminStats(record.roomId) }
  catch (e) { review.value = null }
  reviewLoading.value = false
}

function forceClose(record) {
  Modal.confirm({
    title: '确认强制关播？',
    content: `直播间「${record.title || record.roomId}」将被立即下架，观众端停止播放。`,
    okText: '强制关播',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await liveApi.adminForceClose(record.roomId)
        message.success('已强制关播')
        load()
      } catch (e) { /* 拦截器已提示 */ }
    }
  })
}

/** 平台审核：批准（商户方可开播） */
async function approveRoom(record) {
  if (approving[record.roomId]) return
  approving[record.roomId] = true
  try {
    await liveApi.review(record.roomId, true)
    message.success('已批准，商户可开播')
    load()
  } catch (e) { /* 拦截器已提示 */ }
  approving[record.roomId] = false
}

/** 平台审核：驳回（必填/选填原因，站内通知商户，直播间同步下线） */
function rejectRoom(record) {
  let reason = ''
  Modal.confirm({
    title: '驳回直播间',
    content: () => h('div', { style: 'display:flex;flex-direction:column;gap:8px;margin-top:8px' }, [
      h('span', { style: 'font-size:12px;color:#64748b' },
        `直播间「${record.title || record.roomId}」将被下线并通知商户，请填写驳回原因（留空则使用平台默认文案）：`),
      h('input', {
        style: 'box-sizing:border-box;width:100%;padding:7px 11px;border:1px solid #d9d9d9;border-radius:8px;font-size:13px;outline:none',
        placeholder: '驳回原因',
        onInput: (e) => { reason = e.target.value }
      })
    ]),
    okText: '确认驳回',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      approving[record.roomId] = true
      try {
        await liveApi.review(record.roomId, false, reason.trim() || null)
        message.success('已驳回并通知商户')
        load()
      } catch (e) {
        return Promise.reject(e) // 失败保持弹窗打开（拦截器已提示）
      } finally {
        approving[record.roomId] = false
      }
    }
  })
}

onMounted(load)
</script>

<style scoped>
.al-page { padding: 4px 2px; }
.al-head { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 18px; flex-wrap: wrap; }
.al-head h2 { margin: 0 0 4px; font-size: 22px; letter-spacing: -.02em; }
.al-head p { margin: 0; color: var(--ll-muted, #64748b); font-size: 13px; }
.al-card {
  background: var(--ll-card, #fff);
  border-radius: 16px;
  padding: 18px 20px;
  box-shadow: var(--ll-shadow-sm, 0 1px 2px rgba(16, 24, 40, .06));
}
.al-room-id { font-variant-numeric: tabular-nums; color: var(--ll-muted, #64748b); }
.al-title { font-weight: 600; }
.al-muted { color: var(--ll-muted, #64748b); }
.al-approve { color: #389e0d; }
.al-dot {
  display: inline-block; width: 6px; height: 6px; border-radius: 50%;
  background: currentColor; margin-right: 4px; vertical-align: 1px;
  animation: al-blink 1.1s ease-in-out infinite;
}
@keyframes al-blink { 0%, 100% { opacity: 1; } 50% { opacity: .25; } }

.al-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 16px; }
.kpi { border: 1px solid var(--ll-border, #e5e7eb); border-radius: 12px; padding: 12px 14px; }
.kpi b { display: block; font-size: 19px; font-weight: 700; letter-spacing: -.02em; font-variant-numeric: tabular-nums; }
.kpi span { font-size: 11.5px; color: var(--ll-muted, #64748b); }
.al-funnel-head { font-size: 13px; font-weight: 700; margin: 6px 0 10px; }

@media (max-width: 700px) {
  .al-head { flex-direction: column; align-items: flex-start; }
  .al-kpis { grid-template-columns: repeat(2, 1fr); }
  .al-card { padding: 12px; }
}
</style>
