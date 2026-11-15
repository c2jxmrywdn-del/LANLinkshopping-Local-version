<template>
  <div class="center-exit">
    <a-card :bordered="false" class="exit-card">
      <template #title>退店管理</template>
      <a-space direction="vertical" style="width: 100%" size="middle">
        <a-alert v-if="status.rejectReason" type="warning" show-icon
                 :message="`退店申请被驳回：${status.rejectReason}`" />
        <a-descriptions :column="2" bordered size="small">
          <a-descriptions-item label="店铺状态">
            <a-tag :color="statusColor">{{ statusText }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="保证金余额">¥{{ money(status.depositBalance) }}</a-descriptions-item>
          <a-descriptions-item v-if="status.requestTime" label="申请时间">{{ dt(status.requestTime) }}</a-descriptions-item>
          <a-descriptions-item v-if="status.noticeEndTime" label="公示截止时间">{{ dt(status.noticeEndTime) }}</a-descriptions-item>
          <a-descriptions-item v-if="status.closeTime" label="关闭时间">{{ dt(status.closeTime) }}</a-descriptions-item>
        </a-descriptions>

        <a-card size="small" title="六项退店门禁">
          <a-list v-if="gate.blockers.length" :data-source="gate.blockers" size="small">
            <template #renderItem="{ item }">
              <a-list-item>
                <a-list-item-meta :title="item.label"
                                  :description="`${item.hint}${item.count > 0 ? `（${item.count}）` : ''}`" />
                <a-tag color="error">未通过</a-tag>
              </a-list-item>
            </template>
          </a-list>
          <a-result v-else status="success" title="门禁全部通过" sub-title="满足条件后可提交退店申请" />
        </a-card>

        <a-space>
          <a-button v-if="!status.exitStatus" type="primary" danger @click="openApply">申请退店</a-button>
          <a-button v-if="status.exitStatus === 'PUBLIC_NOTICE'" @click="onWithdraw">撤回申请</a-button>
          <a-tag v-if="status.exitStatus === 'EXIT_REQUESTED'" color="processing">等待平台审核</a-tag>
          <a-tag v-if="status.exitStatus === 'FROZEN'" color="warning">清算中，等待平台关闭</a-tag>
          <a-tag v-if="status.exitStatus === 'CLOSED'" color="default">店铺已关闭</a-tag>
        </a-space>
      </a-space>
    </a-card>

    <a-card :bordered="false" class="exit-card" title="保证金台账">
      <a-table :columns="depositColumns" :data-source="deposits.records" :pagination="depositPagination"
               row-key="depositId" size="small" @change="onDepositPage">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'type'">
            <a-tag :color="depositTypeColor(record.type)">{{ depositTypeText(record.type) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'amount'">¥{{ money(record.amount) }}</template>
          <template v-else-if="column.key === 'balanceAfter'">¥{{ money(record.balanceAfter) }}</template>
          <template v-else-if="column.key === 'createTime'">{{ dt(record.createTime) }}</template>
        </template>
      </a-table>
    </a-card>

    <a-modal v-model:open="applyOpen" title="申请退店" ok-text="提交" cancel-text="取消"
             :confirm-loading="submitting" @ok="onApply">
      <a-form layout="vertical">
        <a-form-item label="退店原因" required>
          <a-textarea v-model:value="applyReason" :rows="4" placeholder="请说明退店原因" />
        </a-form-item>
        <a-alert type="info" show-icon message="提交后店铺经营类操作将冻结；审核通过后进入 30 天公示期。" />
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Modal, message } from 'ant-design-vue'
import { merchantExitApi } from '../../api'

const status = reactive({ exitStatus: null, requestTime: null, noticeEndTime: null,
  closeTime: null, rejectReason: null, depositBalance: 0 })
const gate = reactive({ ready: false, blockers: [] })
const deposits = reactive({ records: [], total: 0 })
const applyOpen = ref(false)
const applyReason = ref('')
const submitting = ref(false)

const statusText = computed(() => ({
  EXIT_REQUESTED: '退店申请中', PUBLIC_NOTICE: '公示中', FROZEN: '清算中', CLOSED: '已关闭'
}[status.exitStatus] || '正常经营'))
const statusColor = computed(() => ({
  EXIT_REQUESTED: 'processing', PUBLIC_NOTICE: 'warning', FROZEN: 'error', CLOSED: 'default'
}[status.exitStatus] || 'success'))

const depositColumns = [
  { title: '类型', key: 'type', width: 100 },
  { title: '金额', key: 'amount', width: 120 },
  { title: '余额快照', key: 'balanceAfter', width: 120 },
  { title: '说明', dataIndex: 'remark', key: 'remark' },
  { title: '时间', key: 'createTime', width: 180 }
]
const depositPagination = reactive({ current: 1, pageSize: 10, total: 0, showSizeChanger: false })

function money(v) { return Number(v || 0).toFixed(2) }
function dt(v) { return v ? String(v).replace('T', ' ').slice(0, 19) : '—' }
function depositTypeText(t) { return ({ PAY: '缴纳', REFUND: '退还', DEDUCT: '抵扣', FORFEIT: '罚没' }[t] || t) }
function depositTypeColor(t) { return ({ PAY: 'green', REFUND: 'blue', DEDUCT: 'orange', FORFEIT: 'red' }[t] || 'default') }

// request.js 响应拦截器已剥壳返回 data 层：三个接口分别直接给出 ExitStatusVO / GateReport / 分页对象，
// 不再读 .data（与 AdminRiskUsers / LogisticsCenter 的约定一致）
async function load() {
  try {
    const [s, g, d] = await Promise.all([merchantExitApi.status(), merchantExitApi.gate(),
      merchantExitApi.deposits({ current: depositPagination.current, size: depositPagination.pageSize })])
    Object.assign(status, s || {})
    Object.assign(gate, g || { ready: false, blockers: [] })
    deposits.records = d?.records || []
    depositPagination.total = d?.total || 0
  } catch (e) { /* 未入驻/会话失效已由拦截器提示，保持当前渲染 */ }
}
function openApply() { applyReason.value = ''; applyOpen.value = true }
async function onApply() {
  if (!applyReason.value.trim()) { message.warning('请填写退店原因'); return }
  submitting.value = true
  try {
    await merchantExitApi.request(applyReason.value.trim())
    message.success('退店申请已提交')
    applyOpen.value = false
    await load()
  } finally { submitting.value = false }
}
async function onWithdraw() {
  await Modal.confirm({
    title: '确认撤回退店申请',
    content: '撤回后店铺将恢复正常经营，可稍后重新提交申请。',
    okText: '撤回',
    cancelText: '取消',
    onOk: async () => {
      await merchantExitApi.withdraw()
      message.success('已撤回退店申请')
      await load()
    }
  })
}
function onDepositPage(p) { depositPagination.current = p.current; load() }
onMounted(load)
</script>

<style scoped>
.center-exit { display: flex; flex-direction: column; gap: 16px; }
/* 卡片规范沿用 CenterFinance：白卡 + 细边框 + 柔和投影 */
.exit-card { border-radius: 16px; border: 1px solid var(--mc-border, #e8e6e1); box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.exit-card :deep(.ant-card-head) { border-bottom: 1px solid var(--mc-border-soft, #f0eee9); padding: 0 20px; min-height: 56px; }
.exit-card :deep(.ant-card-head-title) { font-size: 15px; color: var(--mc-ink, #1d1d1f); letter-spacing: -.01em; }
</style>
