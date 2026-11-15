<template>
  <div class="admin-exit">
    <div class="head">
      <div>
        <div class="eyebrow">MERCHANT EXIT</div>
        <h1>退店审核</h1>
        <p>申请 → 公示 → 清算 → 关闭；保证金仅账面登记，不触发真实打款。</p>
      </div>
    </div>

    <a-card :bordered="false" class="card" title="退店审核">
      <div class="toolbar">
        <a-select v-model:value="filterStatus" allow-clear placeholder="全部状态" style="width: 160px"
                  :options="statusOptions" @change="onFilterChange" />
        <a-button @click="loadList">刷新</a-button>
        <a-button @click="onRunNotice">执行公示到期扫表</a-button>
      </div>
      <a-table :columns="columns" :data-source="rows" :pagination="pagination" row-key="mer_id"
               size="small" @change="onPage">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'exit_status'">
            <a-tag :color="statusColor(record.exit_status)">{{ statusText(record.exit_status) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-button type="link" size="small" @click="openDetail(record.mer_id)">详情</a-button>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-drawer v-model:open="drawerOpen" width="720" title="退店详情" destroy-on-close>
      <a-spin :spinning="detailLoading">
        <a-descriptions :column="2" bordered size="small" title="商户">
          <a-descriptions-item label="商户ID">{{ detail.mer_id }}</a-descriptions-item>
          <a-descriptions-item label="企业名称">{{ detail.enterprise_name || '—' }}</a-descriptions-item>
          <a-descriptions-item label="退店状态">
            <a-tag :color="statusColor(detail.exit_status)">{{ statusText(detail.exit_status) }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="保证金余额">¥{{ money(detail.depositBalance) }}</a-descriptions-item>
          <a-descriptions-item label="退店原因" :span="2">{{ detail.exit_reason || '—' }}</a-descriptions-item>
          <a-descriptions-item v-if="detail.exit_reject_reason" label="驳回原因" :span="2">
            {{ detail.exit_reject_reason }}
          </a-descriptions-item>
        </a-descriptions>

        <a-card size="small" title="六项退店门禁" style="margin-top: 16px">
          <a-list v-if="(detail.gateReport?.blockers || []).length" :data-source="detail.gateReport?.blockers || []" size="small">
            <template #renderItem="{ item }">
              <a-list-item>
                <a-list-item-meta :title="item.label" :description="item.hint" />
                <a-tag color="error">{{ item.count }}</a-tag>
              </a-list-item>
            </template>
          </a-list>
          <a-tag v-else color="success">门禁全部通过</a-tag>
        </a-card>

        <a-card size="small" title="保证金台账" style="margin-top: 16px">
          <a-table :columns="depositColumns" :data-source="detail.deposits || []" row-key="depositId"
                   size="small" :pagination="false">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'type'">{{ depositTypeText(record.type) }}</template>
              <template v-else-if="column.key === 'amount'">¥{{ money(record.amount) }}</template>
              <template v-else-if="column.key === 'balanceAfter'">¥{{ money(record.balanceAfter) }}</template>
              <template v-else-if="column.key === 'createTime'">{{ dt(record.createTime) }}</template>
            </template>
          </a-table>
        </a-card>

        <a-card size="small" title="流转时间线" style="margin-top: 16px">
          <a-timeline>
            <a-timeline-item v-for="l in detail.logs || []" :key="l.id">
              {{ dt(l.createTime) }} · {{ l.fromStatus || 'ACTIVE' }} → {{ l.toStatus }}
              <span v-if="l.detail"> · {{ l.detail }}</span>
            </a-timeline-item>
          </a-timeline>
        </a-card>
      </a-spin>

      <template #footer>
        <a-space>
          <a-button v-if="detail.exit_status === 'EXIT_REQUESTED'" type="primary" @click="onApprove">审核通过</a-button>
          <a-button v-if="detail.exit_status === 'EXIT_REQUESTED'" danger @click="rejectOpen = true">驳回</a-button>
          <a-button v-if="detail.exit_status === 'FROZEN'" type="primary" danger @click="closeOpen = true">正式关闭</a-button>
        </a-space>
      </template>
    </a-drawer>

    <a-modal v-model:open="rejectOpen" title="驳回退店申请" ok-text="确认驳回" cancel-text="取消" @ok="onReject">
      <a-textarea v-model:value="rejectReason" :rows="3" placeholder="请填写驳回原因（必填）" />
    </a-modal>

    <a-modal v-model:open="closeOpen" title="正式关闭店铺" ok-text="确认关闭" cancel-text="取消"
             :confirm-loading="closing" @ok="onClose">
      <a-alert type="warning" show-icon style="margin-bottom: 12px"
               message="关闭不可逆；保证金将按下方金额抵扣后全额退还（账面登记，不触发真实打款）。" />
      <a-form layout="vertical">
        <a-form-item label="保证金余额">¥{{ money(detail.depositBalance) }}</a-form-item>
        <a-form-item label="抵扣金额（欠费/违规扣款，可空）">
          <a-input-number v-model:value="deductAmount" :min="0" :precision="2" style="width: 100%" />
        </a-form-item>
        <a-form-item label="抵扣说明"><a-input v-model:value="deductRemark" /></a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { adminMerchantExitApi } from '../../api'

const rows = ref([])
const filterStatus = ref(undefined)
const pagination = reactive({ current: 1, pageSize: 10, total: 0, showSizeChanger: false })
const detail = reactive({})
const drawerOpen = ref(false)
const detailLoading = ref(false)
const rejectOpen = ref(false)
const closeOpen = ref(false)
const closing = ref(false)
const rejectReason = ref('')
const deductAmount = ref(0)
const deductRemark = ref('')

const statusOptions = [
  { value: 'EXIT_REQUESTED', label: '退店申请中' },
  { value: 'PUBLIC_NOTICE', label: '公示中' },
  { value: 'FROZEN', label: '清算中' },
  { value: 'CLOSED', label: '已关闭' }
]
const columns = [
  { title: '商户ID', dataIndex: 'mer_id', width: 90 },
  { title: '企业名称', dataIndex: 'enterprise_name', key: 'enterprise_name' },
  { title: '退店状态', key: 'exit_status', width: 110 },
  { title: '申请时间', dataIndex: 'exit_request_time', key: 'exit_request_time', width: 170 },
  { title: '公示截止时间', dataIndex: 'notice_end_time', key: 'notice_end_time', width: 170 },
  { title: '操作', key: 'action', width: 90 }
]
const depositColumns = [
  { title: '类型', key: 'type', width: 80 },
  { title: '金额', key: 'amount', width: 100 },
  { title: '余额快照', key: 'balanceAfter', width: 110 },
  { title: '说明', dataIndex: 'remark', key: 'remark' },
  { title: '时间', key: 'createTime', width: 170 }
]

function money(v) { return Number(v || 0).toFixed(2) }
function dt(v) { return v ? String(v).replace('T', ' ').slice(0, 19) : '—' }
function statusText(s) {
  return ({ EXIT_REQUESTED: '退店申请中', PUBLIC_NOTICE: '公示中', FROZEN: '清算中', CLOSED: '已关闭' }[s] || '正常经营')
}
function statusColor(s) {
  return ({ EXIT_REQUESTED: 'processing', PUBLIC_NOTICE: 'warning', FROZEN: 'error', CLOSED: 'default' }[s] || 'success')
}
function depositTypeText(t) { return ({ PAY: '缴纳', REFUND: '退还', DEDUCT: '抵扣', FORFEIT: '罚没' }[t] || t) }

// request.js 响应拦截器已剥壳返回 data 层：列表直接读 records/total，详情即 detail 本体
async function loadList() {
  try {
    const r = await adminMerchantExitApi.list({
      current: pagination.current, size: pagination.pageSize, exitStatus: filterStatus.value
    })
    rows.value = r?.records || []
    pagination.total = r?.total || 0
  } catch (e) { /* 拦截器已提示错误，保留现有列表 */ }
}
function onFilterChange() { pagination.current = 1; loadList() }
function onPage(p) { pagination.current = p.current; loadList() }
async function openDetail(merId) {
  drawerOpen.value = true
  detailLoading.value = true
  try {
    const r = await adminMerchantExitApi.detail(merId)
    Object.keys(detail).forEach(k => delete detail[k])
    Object.assign(detail, r || {})
  } finally { detailLoading.value = false }
}
async function refreshDetail() { if (detail.mer_id) await openDetail(detail.mer_id); await loadList() }
async function onApprove() {
  await adminMerchantExitApi.approve(detail.mer_id)
  message.success('退店已审核通过，进入公示期')
  await refreshDetail()
}
async function onReject() {
  if (!rejectReason.value.trim()) { message.warning('请填写驳回原因'); return }
  await adminMerchantExitApi.reject(detail.mer_id, rejectReason.value.trim())
  message.success('退店申请已驳回')
  rejectOpen.value = false
  await refreshDetail()
}
async function onClose() {
  closing.value = true
  try {
    await adminMerchantExitApi.close(detail.mer_id,
      { deductAmount: deductAmount.value || 0, remark: deductRemark.value })
    message.success('店铺已正式关闭')
    closeOpen.value = false
    await refreshDetail()
  } finally { closing.value = false }
}
async function onRunNotice() {
  const r = await adminMerchantExitApi.runNotice()
  message.success(`公示到期转清算：${r?.frozen ?? 0} 家`)
  await loadList()
}
onMounted(loadList)
</script>

<style scoped>
.admin-exit { display: flex; flex-direction: column; gap: 16px; padding: 16px; }
.head { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; flex-wrap: wrap; }
.eyebrow { font-size: 11px; letter-spacing: .12em; color: #94a3b8; font-weight: 700; margin-bottom: 6px; }
.head h1 { margin: 0; font-size: 24px; font-weight: 750; letter-spacing: -.02em; color: #0f172a; }
.head p { margin: 6px 0 0; font-size: 13px; color: #64748b; }
.card { border-radius: 14px; border: 1px solid #e8e6e1; box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 14px; }
</style>
