<template>
  <div class="page">
    <div class="head">
      <div>
        <div class="eyebrow">WITHDRAWAL REVIEW</div>
        <h1>{{ txt(L.title) }}</h1>
        <p>{{ txt(L.sub) }}</p>
      </div>
    </div>

    <a-alert type="info" show-icon class="notice" :message="txt(L.noticeTitle)" :description="txt(L.noticeBody)" />

    <a-card :bordered="false" class="card">
      <div class="toolbar">
        <a-input-number v-model:value="query.merId" :min="1" :precision="0"
                        style="width: 150px" :placeholder="txt(L.merIdPh)" />
        <a-select v-model:value="query.status" style="width: 150px" allow-clear
                  :placeholder="txt(L.allStatus)" @change="reload(1)">
          <a-select-option :value="0">{{ txt(L.stWdPending) }}</a-select-option>
          <a-select-option :value="1">{{ txt(L.stWdPaid) }}</a-select-option>
          <a-select-option :value="2">{{ txt(L.stWdRejected) }}</a-select-option>
          <a-select-option :value="3">{{ txt(L.stWdWithdrawn) }}</a-select-option>
        </a-select>
        <a-button @click="reload(1)">{{ txt(L.search) }}</a-button>
        <a-button @click="resetQuery">{{ txt(L.reset) }}</a-button>
      </div>

      <a-table :data-source="rows" :columns="cols" row-key="id" :loading="loading"
               :scroll="{ x: 1280 }" :pagination="pagination">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'withdrawNo'"><span class="mono">{{ record.withdrawNo }}</span></template>
          <template v-else-if="column.key === 'merId'"><span class="mono">#{{ record.merId }}</span></template>
          <template v-else-if="column.key === 'amount'">
            <div>¥{{ money(record.amount) }}</div>
            <small class="muted">{{ txt(L.colBalanceSnapshot) }}: ¥{{ money(record.balanceSnapshot) }}</small>
          </template>
          <template v-else-if="column.key === 'bankName'">
            <span class="wd-bank">{{ record.bankName }}<br /><span class="mono">{{ record.bankAccountNoMasked }}</span></span>
          </template>
          <template v-else-if="column.key === 'status'">
            <a-tag :color="WD_STATUS_COLOR[record.status] || 'default'">{{ wdStatusText(record.status) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'voucherUrl'">
            <a v-if="record.voucherUrl" :href="record.voucherUrl" target="_blank" rel="noopener">{{ txt(L.wdViewVoucher) }}</a>
            <span v-else>—</span>
          </template>
          <template v-else-if="column.key === 'reviewedAt'">{{ shortTime(record.reviewedAt) || '—' }}</template>
          <template v-else-if="column.key === 'createTime'">{{ shortTime(record.createTime) || '—' }}</template>
          <template v-else-if="column.key === 'op'">
            <a-space size="small">
              <a v-if="Number(record.status) === 0" @click="openApprove(record)">{{ txt(L.pass) }}</a>
              <a v-if="Number(record.status) === 0" class="danger" @click="openReject(record)">{{ txt(L.reject) }}</a>
              <span v-if="Number(record.status) !== 0">—</span>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- Approve: upload voucher then mark paid -->
    <a-modal v-model:open="approveOpen" :title="txt(L.approveTitle)" :confirm-loading="approving"
             :ok-text="txt(L.confirm)" :cancel-text="txt(L.cancel)" @ok="doApprove">
      <a-form layout="vertical">
        <a-form-item :label="txt(L.colWithdrawNo)">
          <span class="mono">{{ approveTarget?.withdrawNo }}</span>
        </a-form-item>
        <a-form-item :label="txt(L.colAmount)">
          <b>¥{{ money(approveTarget?.amount) }}</b>
          <div class="muted small">
            {{ approveTarget?.bankAccountName }} · {{ approveTarget?.bankAccountNoMasked }} · {{ approveTarget?.bankName }}
          </div>
        </a-form-item>
        <a-form-item :label="txt(L.voucherFile)" required>
          <a-upload :before-upload="handleVoucherFile" :max-count="1" accept=".jpg,.jpeg,.png,.webp,.pdf"
                    :file-list="voucherFileList">
            <a-button>
              <Upload :size="14" class="mr-1" /> {{ txt(L.voucherUploadBtn) }}
            </a-button>
          </a-upload>
          <div v-if="voucherUploading" class="muted small">{{ txt(L.voucherUploading) }}</div>
          <div v-else-if="voucherUrl" class="muted small">{{ txt(L.voucherReady) }}</div>
        </a-form-item>
        <a-alert type="warning" show-icon :message="txt(L.approveHint)" />
      </a-form>
    </a-modal>

    <!-- Reject -->
    <a-modal v-model:open="rejectOpen" :title="txt(L.rejectTitle)" :confirm-loading="rejecting"
             :ok-text="txt(L.confirm)" :cancel-text="txt(L.cancel)" @ok="doReject">
      <a-form layout="vertical">
        <a-form-item :label="txt(L.colWithdrawNo)">
          <span class="mono">{{ rejectTarget?.withdrawNo }}</span>
        </a-form-item>
        <a-form-item :label="txt(L.rejectReasonLabel)" required>
          <a-textarea v-model:value="rejectReason" :rows="3" :maxlength="200"
                      :placeholder="txt(L.rejectReasonPh)" />
        </a-form-item>
        <a-alert type="warning" show-icon :message="txt(L.rejectHint)" />
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { Upload } from 'lucide-vue-next'
import { adminFinanceApi, attachmentApi } from '../../api'
import { locale } from '../../i18n'
import { UI_ZH_EN_EXTRA } from '../../i18n/zh-en-extra'
import { fmtMoney } from '../../utils/merchantStatus'

const isEn = computed(() => String(locale.value || '').toLowerCase().startsWith('en'))
// 模板 CJK=0：UI 串集中在 L 表，txt() 按语言查补充词典（缺失回退中文原文）
const txt = (zh) => (isEn.value ? (UI_ZH_EN_EXTRA[zh] || zh) : zh)

const L = {
  title: '提现审核',
  sub: '商户提现申请的冻结单审核：通过需登记线下对公打款凭证，驳回即解冻。',
  noticeTitle: '打款与解冻口径',
  noticeBody: '通过=已完成线下对公打款并上传凭证（平台不发起任何出金调用）；驳回=冻结资金原路解冻回商户可提余额。商户申请时金额已冻结，余额快照仅供参考。',
  merIdPh: '商户 ID',
  allStatus: '全部状态',
  search: '查询',
  reset: '重置',
  pass: '通过并登记打款',
  reject: '驳回',
  approveTitle: '通过并登记打款',
  approveHint: '请先完成线下对公打款，再上传银行回单作为凭证并确认。确认后单据流转为已打款，不可回退。',
  rejectTitle: '驳回提现申请',
  rejectReasonLabel: '驳回原因',
  rejectReasonPh: '请填写驳回原因（商户可见）',
  rejectHint: '驳回后冻结金额将立即解冻回商户可提余额，并记录台账。',
  voucherFile: '打款凭证（JPG/PNG/WebP/PDF）',
  voucherUploadBtn: '上传凭证',
  voucherUploading: '凭证上传中…',
  voucherReady: '凭证已就绪',
  cancel: '取消',
  confirm: '确认',
  colWithdrawNo: '提现单号',
  colMerId: '商户',
  colAmount: '金额（余额快照）',
  colBalanceSnapshot: '快照',
  colBankInfo: '收款账户',
  colStatus: '状态',
  colVoucher: '打款凭证',
  colReviewedAt: '审核时间',
  colCreateTime: '申请时间',
  colOp: '操作',
  unitRow: '条',
  stWdPending: '审核中',
  stWdPaid: '已打款',
  stWdRejected: '已驳回',
  stWdWithdrawn: '已撤回',
  wdViewVoucher: '查看凭证',
  approveOk: '已登记打款',
  rejectOk: '已驳回并解冻',
  voucherRequired: '请先上传打款凭证',
  reasonRequired: '请填写驳回原因',
  uploadFailed: '凭证上传失败，请重试',
}

const WD_STATUS_COLOR = { 0: 'blue', 1: 'green', 2: 'red', 3: 'default' }
const wdStatusText = (v) => {
  const map = { 0: L.stWdPending, 1: L.stWdPaid, 2: L.stWdRejected, 3: L.stWdWithdrawn }
  return txt(map[Number(v)] || String(v ?? '—'))
}

const money = fmtMoney
function shortTime(t) { return (t || '').replace('T', ' ').slice(0, 16) }

const loading = ref(false)
const rows = ref([])
const page = ref({ current: 1, size: 10, total: 0 })
const query = reactive({ merId: undefined, status: 0 })

const approveOpen = ref(false)
const approveTarget = ref(null)
const approving = ref(false)
const voucherUrl = ref('')
const voucherUploading = ref(false)
const voucherFileList = ref([])

const rejectOpen = ref(false)
const rejectTarget = ref(null)
const rejecting = ref(false)
const rejectReason = ref('')

const cols = computed(() => [
  { title: txt(L.colWithdrawNo), key: 'withdrawNo', width: 170 },
  { title: txt(L.colMerId), key: 'merId', width: 70 },
  { title: txt(L.colAmount), key: 'amount', width: 150 },
  { title: txt(L.colBankInfo), key: 'bankName', width: 220 },
  { title: txt(L.colStatus), key: 'status', width: 92 },
  { title: txt(L.colVoucher), key: 'voucherUrl', width: 110 },
  { title: txt(L.colReviewedAt), key: 'reviewedAt', width: 140 },
  { title: txt(L.colCreateTime), key: 'createTime', width: 140 },
  { title: txt(L.colOp), key: 'op', width: 170, fixed: 'right' }
])

const pagination = computed(() => ({
  current: page.value.current,
  pageSize: page.value.size,
  total: page.value.total,
  showSizeChanger: false,
  showTotal: (t) => `${t} ${txt(L.unitRow)}`,
  onChange: (p) => reload(p)
}))

async function reload(p = 1) {
  loading.value = true
  try {
    const res = await adminFinanceApi.page({
      current: p,
      size: page.value.size,
      merId: query.merId || undefined,
      status: query.status === null || query.status === undefined ? undefined : query.status
    })
    rows.value = res?.records || []
    page.value = {
      current: Number(res?.current) || p,
      size: Number(res?.size) || page.value.size,
      total: Number(res?.total) || 0
    }
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  query.merId = undefined
  query.status = 0
  reload(1)
}

function openApprove(record) {
  approveTarget.value = record
  voucherUrl.value = ''
  voucherFileList.value = []
  approveOpen.value = true
}

// antd before-upload：拦截浏览器默认 POST，改走 attachmentApi（bizType=withdrawal-voucher）
async function handleVoucherFile(file) {
  voucherUploading.value = true
  try {
    const res = await attachmentApi.upload('withdrawal-voucher', file)
    voucherUrl.value = res?.url || res?.data?.url || ''
    voucherFileList.value = [{ uid: file.uid, name: file.name, status: 'done' }]
  } catch (e) {
    voucherFileList.value = []
    message.error(txt(L.uploadFailed))
  } finally {
    voucherUploading.value = false
  }
  return false
}

async function doApprove() {
  if (!voucherUrl.value) {
    message.warning(txt(L.voucherRequired))
    return
  }
  approving.value = true
  try {
    await adminFinanceApi.approve(approveTarget.value.id, voucherUrl.value)
    message.success(txt(L.approveOk))
    approveOpen.value = false
    await reload(page.value.current)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    approving.value = false
  }
}

function openReject(record) {
  rejectTarget.value = record
  rejectReason.value = ''
  rejectOpen.value = true
}

async function doReject() {
  if (!rejectReason.value || !rejectReason.value.trim()) {
    message.warning(txt(L.reasonRequired))
    return
  }
  rejecting.value = true
  try {
    await adminFinanceApi.reject(rejectTarget.value.id, rejectReason.value.trim())
    message.success(txt(L.rejectOk))
    rejectOpen.value = false
    await reload(page.value.current)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    rejecting.value = false
  }
}

// 撤回单等终态不做操作入口；待审单驳回前无需二次确认弹窗（驳回弹窗本身即确认）
onMounted(() => reload(1))
</script>

<style scoped>
.page { display: flex; flex-direction: column; gap: 16px; }
.head { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; flex-wrap: wrap; }
.eyebrow { font-size: 11px; letter-spacing: .12em; color: #94a3b8; font-weight: 700; margin-bottom: 6px; }
.head h1 { margin: 0; font-size: 24px; font-weight: 750; letter-spacing: -.02em; color: #0f172a; }
.head p { margin: 6px 0 0; font-size: 13px; color: #64748b; }
.notice { border-radius: 12px; }
.card { border-radius: 14px; border: 1px solid #e8e6e1; box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 14px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12.5px; }
.muted { color: #94a3b8; }
.small { font-size: 12px; }
.wd-bank { font-size: 12px; line-height: 1.5; }
.danger { color: #b91c1c; }
@media (max-width: 720px) {
  .head { flex-direction: column; align-items: flex-start; }
}
</style>
