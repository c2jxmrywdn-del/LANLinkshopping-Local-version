<template>
  <div class="admin-track-exc">
    <a-card :bordered="false" class="card">
      <div class="page-head">
        <div>
          <div class="eyebrow">LOGISTICS · EXCEPTION BOARD</div>
          <h2>物流异常件看板</h2>
          <p class="lead">聚合同步链路中的异常运单，一键转交客服工单跟进处理。</p>
        </div>
        <a-button :loading="loading" @click="load">
          <RefreshCw :size="13" class="btn-ico" />
          <span>刷新</span>
        </a-button>
      </div>

      <!-- 后端并行实现中的占位提示：404/未就绪时不打断页面，界面给出可读状态 -->
      <a-alert v-if="endpointPending" class="pending" type="warning" show-icon>
        <template #message>看板接口尚未接入</template>
        <template #description>
          GET /api/admin/logistics/exception 后端并行实现中，就绪后刷新即可见数据。
        </template>
      </a-alert>

      <div class="sum-row">
        <span class="sum">{{ '共 ' + rows.length + ' 件异常' }}</span>
      </div>

      <a-table
        :data-source="rows"
        :columns="cols"
        row-key="_key"
        size="middle"
        :loading="loading"
        :pagination="{ pageSize: 10, showSizeChanger: false, size: 'small' }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'waybillNo'">
            <span class="wb-no">{{ record.waybillNo || '—' }}</span>
          </template>
          <template v-else-if="column.key === 'status'">
            <span class="status" :class="statusClass(record.status)">{{ statusLabel(record.status) }}</span>
          </template>
          <template v-else-if="column.key === 'lastEventTime'">
            {{ fmt(record.lastEventTime) }}
          </template>
          <template v-else-if="column.key === 'hoursStuck'">
            <span class="stuck" :class="stuckClass(record.hoursStuck)">{{ stuckText(record.hoursStuck) }}</span>
          </template>
          <template v-else-if="column.key === 'op'">
            <a-button
              size="small"
              type="primary"
              ghost
              :loading="ticketing === record._key"
              :disabled="converted.has(record._key)"
              @click="toTicket(record)"
            >
              <LifeBuoy :size="12" class="btn-ico" />
              <span>转工单</span>
            </a-button>
          </template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { RefreshCw, LifeBuoy } from 'lucide-vue-next'
import { trackingApi } from '../../api'
import { locale } from '../../i18n'

const loading = ref(false)
const rows = ref([])
const endpointPending = ref(false)
const ticketing = ref('')
const converted = ref(new Set())

/* 平台 9 态标签与配色（与物流域状态机/时间轴组件同一口径） */
const STATUS_META = {
  pending_ship: { label: '待发货', cls: 'info' },
  picked_up: { label: '已揽收', cls: 'info' },
  in_transit: { label: '干线运输', cls: 'run' },
  out_for_delivery: { label: '末端派送', cls: 'run' },
  delivered: { label: '已签收', cls: 'ok' },
  returned: { label: '已退回', cls: 'warn' },
  lost: { label: '疑似丢失', cls: 'bad' },
  exception: { label: '运输异常', cls: 'bad' },
  cancelled: { label: '已取消', cls: 'muted' }
}

const cols = [
  { title: '运单号', key: 'waybillNo', width: 180 },
  { title: '承运商', key: 'carrierName', width: 130 },
  { title: '状态', key: 'status', width: 110 },
  { title: '异常原因', key: 'statusReason' },
  { title: '最近事件', key: 'lastEventTime', width: 150 },
  { title: '滞留时长', key: 'hoursStuck', width: 130 },
  { title: '操作', key: 'op', width: 110 }
]

function statusLabel(s) {
  const meta = STATUS_META[String(s || '')]
  return meta ? meta.label : String(s || '—')
}
function statusClass(s) {
  const meta = STATUS_META[String(s || '')]
  return 'st-' + ((meta && meta.cls) || 'muted')
}

function stuckText(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  return '滞留 ' + n + ' 小时'
}
function stuckClass(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return ''
  if (n >= 24) return 'stuck-bad'
  if (n >= 6) return 'stuck-warn'
  return ''
}

function fmt(v) {
  if (!v) return '—'
  const d = new Date(String(v).replace('T', ' ').replace(/-/g, '/'))
  if (Number.isNaN(d.getTime())) return String(v).replace('T', ' ').slice(0, 16)
  return new Intl.DateTimeFormat(locale.value === 'en-US' ? 'en-US' : 'zh-CN',
    { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(d)
}

async function load() {
  loading.value = true
  try {
    const data = await trackingApi.exceptions()
    const list = Array.isArray(data) ? data : (Array.isArray(data?.list) ? data.list : [])
    rows.value = list.map((r, i) => ({ ...r, _key: String(r?.waybillId ?? r?.waybillNo ?? 'row-' + i) }))
    endpointPending.value = false
  } catch (e) {
    rows.value = []
    endpointPending.value = true
  } finally {
    loading.value = false
  }
}

/* 行内「转工单」占位动作：POST /admin/logistics/exception/{waybillId}/ticket；
 * 后端并行实现中，404 时提示待接入（silent 配置由组件自管提示，避免拦截器双重 toast） */
async function toTicket(record) {
  ticketing.value = record._key
  try {
    await trackingApi.createTicket(record.waybillId ?? record.waybillNo)
    converted.value = new Set([...converted.value, record._key])
    message.success('已转客服工单')
  } catch (e) {
    const http404 = Number(e?.response?.status) === 404
    const biz404 = /404|不存在|未接入|not\s*found/i.test(String(e?.message || ''))
    if (http404 || biz404) {
      message.info('工单接口尚未接入')
    } else {
      message.error('转工单失败')
    }
  } finally {
    ticketing.value = ''
  }
}

onMounted(() => load())
</script>

<style scoped>
.admin-track-exc { max-width: 1200px; margin: 0 auto; }
.card { border-radius: 18px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.eyebrow { font-size: 10.5px; font-weight: 800; letter-spacing: .18em; color: #0284c7; }
.page-head h2 { margin: 6px 0 4px; font-size: 24px; letter-spacing: -.02em; color: var(--ll-ink, #0f172a); }
.lead { margin: 0; color: var(--ll-muted, #667085); font-size: 13px; }
.btn-ico { margin-right: 4px; vertical-align: -2px; }

.pending { margin-bottom: 14px; }

.sum-row { display: flex; justify-content: flex-end; margin-bottom: 8px; }
.sum { font-size: 12.5px; color: var(--ll-muted, #667085); }

.wb-no { font-weight: 600; color: var(--ll-ink, #0f172a); font-family: ui-monospace, monospace; font-size: 12.5px; }

.status { font-size: 11.5px; font-weight: 700; padding: 3px 10px; border-radius: 999px; white-space: nowrap; }
.status.st-info { background: rgba(14, 165, 233, .12); color: #0369a1; }
.status.st-run { background: rgba(2, 132, 199, .12); color: #075985; }
.status.st-ok { background: rgba(16, 185, 129, .12); color: #047857; }
.status.st-warn { background: rgba(245, 158, 11, .14); color: #b45309; }
.status.st-bad { background: rgba(239, 68, 68, .12); color: #b91c1c; }
.status.st-muted { background: rgba(100, 116, 139, .14); color: #475569; }

.stuck { font-size: 12px; color: var(--ll-muted, #475569); font-variant-numeric: tabular-nums; white-space: nowrap; }
.stuck.stuck-warn { color: #b45309; font-weight: 600; }
.stuck.stuck-bad { color: #b91c1c; font-weight: 700; }
</style>
