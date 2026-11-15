<template>
  <div class="page">
    <div class="head">
      <div>
        <div class="eyebrow">MERCHANT SETTLEMENT</div>
        <h1>{{ txt(L.title) }}</h1>
        <p>{{ txt(L.sub) }}</p>
      </div>
      <div class="head-actions">
        <a-button :loading="syncing" @click="doSync">
          <RefreshCw :size="14" class="mr-1" /> {{ txt(L.sync) }}
        </a-button>
        <a-button type="primary" :loading="generating" @click="openGenerate">
          <Plus :size="14" class="mr-1" /> {{ txt(L.generate) }}
        </a-button>
      </div>
    </div>

    <a-alert type="info" show-icon class="notice" :message="txt(L.noticeTitle)" :description="txt(L.noticeBody)" />

    <a-card :bordered="false" class="card">
      <div class="toolbar">
        <a-input-number v-model:value="query.merId" :min="1" :precision="0"
                        style="width: 150px" :placeholder="txt(L.merIdPh)" />
        <a-select v-model:value="query.status" style="width: 150px" allow-clear
                  :placeholder="txt(L.allStatus)" @change="reload(1)">
          <a-select-option value="DRAFT">{{ txt(L.stDraft) }}</a-select-option>
          <a-select-option value="APPROVED">{{ txt(L.stApproved) }}</a-select-option>
          <a-select-option value="PAID">{{ txt(L.stPaid) }}</a-select-option>
        </a-select>
        <a-button @click="reload(1)">{{ txt(L.search) }}</a-button>
        <a-button @click="resetQuery">{{ txt(L.reset) }}</a-button>
      </div>

      <a-table :data-source="rows" :columns="cols" row-key="settleId" :loading="loading"
               :scroll="{ x: 1240 }" :pagination="pagination">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'settleNo'"><span class="mono">{{ record.settleNo }}</span></template>
          <template v-else-if="column.key === 'period'">{{ periodText(record) }}</template>
          <template v-else-if="column.key === 'totalNet'">¥{{ money(record.totalNet) }}</template>
          <template v-else-if="column.key === 'commissionTotal'">¥{{ money(record.commissionTotal) }}</template>
          <template v-else-if="column.key === 'status'">
            <a-tag :color="BATCH_STATUS_COLOR[record.status] || 'default'">{{ batchStatusText(record.status) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'payVoucher'">
            <span class="mono">{{ record.payVoucher || '—' }}</span>
          </template>
          <template v-else-if="column.key === 'createTime'">{{ shortTime(record.createTime) || '—' }}</template>
          <template v-else-if="column.key === 'op'">
            <a-space size="small">
              <a @click="openDetail(record)">{{ txt(L.detail) }}</a>
              <a v-if="record.status === 'DRAFT'" :class="{ disabled: approvingId === record.settleId }"
                 @click="doApprove(record)">{{ txt(L.approve) }}</a>
              <a v-if="record.status === 'APPROVED'" @click="openPay(record)">{{ txt(L.registerPay) }}</a>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- Batch details -->
    <a-modal v-model:open="detailOpen" :title="txt(L.detailTitle)" :footer="null" width="1000px">
      <a-spin :spinning="detailLoading">
        <div v-if="detail.batch" class="detail-head">
          <div class="dh-row"><span class="k">{{ txt(L.colSettleNo) }}</span><span class="mono">{{ detail.batch.settleNo }}</span></div>
          <div class="dh-row"><span class="k">{{ txt(L.colPeriod) }}</span>{{ periodText(detail.batch) }}</div>
          <div class="dh-row"><span class="k">{{ txt(L.colTotalNet) }}</span>¥{{ money(detail.batch.totalNet) }}</div>
          <div class="dh-row"><span class="k">{{ txt(L.colCommissionTotal) }}</span>¥{{ money(detail.batch.commissionTotal) }}</div>
          <div class="dh-row">
            <span class="k">{{ txt(L.colStatus) }}</span>
            <a-tag :color="BATCH_STATUS_COLOR[detail.batch.status] || 'default'">{{ batchStatusText(detail.batch.status) }}</a-tag>
          </div>
          <div class="dh-row"><span class="k">{{ txt(L.colPayVoucher) }}</span><span class="mono">{{ detail.batch.payVoucher || '—' }}</span></div>
        </div>
        <a-table :data-source="detail.items || []" :columns="itemCols" row-key="earningId"
                 size="small" :pagination="false" :scroll="{ x: 640 }" class="mt-12">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'orderNo'"><span class="mono">{{ record.orderNo || '—' }}</span></template>
            <template v-else-if="column.key === 'type'">
              <a-tag :color="record.type === 'REVERSE' ? 'red' : 'green'">
                {{ record.type === 'REVERSE' ? txt(L.reverse) : txt(L.earn) }}
              </a-tag>
            </template>
            <template v-else-if="column.key === 'netAmount'">
              <span :class="record.type === 'REVERSE' ? 'amt-reverse' : 'amt-earn'">
                {{ record.type === 'REVERSE' ? '−' : '' }}¥{{ money(Math.abs(Number(record.netAmount || 0))) }}
              </span>
            </template>
          </template>
        </a-table>
      </a-spin>
    </a-modal>

    <!-- Generate batch -->
    <a-modal v-model:open="generateOpen" :title="txt(L.generateTitle)" :confirm-loading="generating"
             :ok-text="txt(L.generate)" :cancel-text="txt(L.cancel)" @ok="doGenerate">
      <a-form layout="vertical">
        <a-form-item :label="txt(L.merId)" required>
          <a-input-number v-model:value="genForm.merId" :min="1" :precision="0"
                          style="width: 100%" :placeholder="txt(L.merIdPh)" />
        </a-form-item>
        <a-form-item :label="txt(L.colPeriod)">
          <a-range-picker v-model:value="genForm.range" show-time style="width: 100%" />
        </a-form-item>
        <a-alert type="warning" show-icon :message="txt(L.generateHint)" />
      </a-form>
    </a-modal>

    <!-- Register payment -->
    <a-modal v-model:open="payOpen" :title="txt(L.payTitle)" :confirm-loading="paying"
             :ok-text="txt(L.registerPay)" :cancel-text="txt(L.cancel)" @ok="doPay">
      <a-form layout="vertical">
        <a-form-item :label="txt(L.colSettleNo)">
          <span class="mono">{{ payTarget?.settleNo }}</span>
        </a-form-item>
        <a-form-item :label="txt(L.colTotalNet)">
          <b>¥{{ money(payTarget?.totalNet) }}</b>
        </a-form-item>
        <a-form-item :label="txt(L.colPayVoucher)" required>
          <a-input v-model:value="payVoucher" :placeholder="txt(L.payVoucherPh)" :maxlength="64" />
        </a-form-item>
        <a-alert type="warning" show-icon :message="txt(L.payHint)" />
      </a-form>
    </a-modal>

    <!-- Commission rate -->
    <a-card :bordered="false" class="card">
      <template #title>
        <div class="card-title">
          <div>
            <strong>{{ txt(L.commissionTitle) }}</strong>
            <small>{{ txt(L.commissionSub) }}</small>
          </div>
        </div>
      </template>
      <div class="toolbar">
        <a-input-number v-model:value="commissionForm.merId" :min="1" :precision="0"
                        style="width: 160px" :placeholder="txt(L.merIdPh)" />
        <a-input-number v-model:value="commissionForm.rate" :min="0" :max="100" :precision="2"
                        style="width: 160px" :placeholder="txt(L.commissionPh)" />
        <a-button type="primary" :loading="savingCommission" @click="doSaveCommission">{{ txt(L.save) }}</a-button>
        <a-button :loading="savingCommission" @click="doClearCommission">{{ txt(L.clear) }}</a-button>
        <span class="hint">{{ txt(L.commissionHint) }}</span>
      </div>
    </a-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { Plus, RefreshCw } from 'lucide-vue-next'
import { adminSettlementApi } from '../../api'
import { locale } from '../../i18n'
import { UI_ZH_EN_EXTRA } from '../../i18n/zh-en-extra'
import { fmtMoney } from '../../utils/merchantStatus'

const isEn = computed(() => String(locale.value || '').toLowerCase().startsWith('en'))
// 模板 CJK=0：UI 串集中在下方 L 表，txt() 按语言查补充词典（缺失回退中文原文）
const txt = (zh) => (isEn.value ? (UI_ZH_EN_EXTRA[zh] || zh) : zh)

// 文案表（与 zh-en-extra.js 同名键一一对应）
const L = {
  title: '商户结算运营',
  sub: '对账、生成结算批次、审核与登记线下打款凭证；平台只记账不对私划转。',
  noticeTitle: '二清红线与打款口径',
  noticeBody: '本页只做记账与对账：生成批次聚合商户可结算收益（含退款冲正负向结转），审核后由财务线下对公打款，再在此登记凭证号。平台不代收代付、不发起任何出金。',
  sync: '手动对账',
  generate: '生成批次',
  merId: '商户 ID',
  merIdPh: '商户 ID',
  allStatus: '全部状态',
  search: '查询',
  reset: '重置',
  detail: '详情',
  approve: '审核',
  registerPay: '打款登记',
  detailTitle: '批次详情',
  generateTitle: '生成结算批次',
  generateHint: '将聚合该商户全部可结算收益并冻结明细，被纳入的收益置为已结算。账期留空则按实际纳入行手工填写。',
  payTitle: '登记线下打款',
  payVoucherPh: '银行流水号 / 回单编号',
  payHint: '仅登记凭证号，平台不会发起支付。请先完成线下对公打款再登记。',
  commissionTitle: '商户佣金率',
  commissionSub: '按商户维度设置平台佣金率；留空并保存即回落到全局默认费率',
  commissionPh: '佣金率 %',
  save: '保存',
  clear: '清除（回默认）',
  commissionHint: '仅影响后续记入的收益，历史行按入账时快照费率结算，不追溯已生成批次。',
  cancel: '取消',
  colSettleNo: '批次号',
  colPeriod: '账期',
  colTotalNet: '应结净额',
  colCommissionTotal: '佣金合计',
  colItemCount: '明细数',
  colStatus: '状态',
  colPayVoucher: '打款凭证',
  colCreateTime: '创建时间',
  colOp: '操作',
  colOrderNo: '订单号',
  colType: '类型',
  colNet: '净额',
  unitRow: '条',
  stDraft: '草稿',
  stApproved: '已审核',
  stPaid: '已打款',
  reverse: '冲正',
  earn: '计入',
  // 提示
  syncOk: '对账已完成',
  approveOk: '批次已审核',
  payOk: '打款凭证已登记',
  generateOk: '批次已生成',
  commissionOk: '佣金率已更新',
  commissionCleared: '佣金率已清除，回落全局默认',
  merIdRequired: '请填写商户 ID',
  voucherRequired: '请填写打款凭证号',
  rateRequired: '请填写佣金率',
  approveConfirm: '确认审核该批次？',
  confirm: '确认',
}

const BATCH_STATUS_COLOR = { DRAFT: 'default', APPROVED: 'blue', PAID: 'green' }
const batchStatusText = (v) => {
  const map = { DRAFT: L.stDraft, APPROVED: L.stApproved, PAID: L.stPaid }
  return txt(map[v] || v || '—')
}

const money = fmtMoney
function shortTime(t) { return (t || '').replace('T', ' ').slice(0, 16) }
function periodText(record) {
  return `${shortTime(record?.periodStart) || '—'} ~ ${shortTime(record?.periodEnd) || '—'}`
}

const loading = ref(false)
const syncing = ref(false)
const generating = ref(false)
const paying = ref(false)
const savingCommission = ref(false)
const approvingId = ref(null)

const rows = ref([])
const page = ref({ current: 1, size: 10, total: 0 })
const query = reactive({ merId: undefined, status: undefined })

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref({})

const generateOpen = ref(false)
const genForm = reactive({ merId: undefined, range: null })

const payOpen = ref(false)
const payTarget = ref(null)
const payVoucher = ref('')

const commissionForm = reactive({ merId: undefined, rate: undefined })

const cols = computed(() => [
  { title: txt(L.colSettleNo), key: 'settleNo', width: 200 },
  { title: txt(L.colPeriod), key: 'period', width: 300 },
  { title: txt(L.colTotalNet), key: 'totalNet', width: 122 },
  { title: txt(L.colCommissionTotal), key: 'commissionTotal', width: 120 },
  { title: txt(L.colItemCount), dataIndex: 'itemCount', width: 88 },
  { title: txt(L.colStatus), key: 'status', width: 96 },
  { title: txt(L.colPayVoucher), key: 'payVoucher', width: 150 },
  { title: txt(L.colCreateTime), key: 'createTime', width: 148 },
  { title: txt(L.colOp), key: 'op', width: 180, fixed: 'right' }
])

const itemCols = computed(() => [
  { title: txt(L.colOrderNo), key: 'orderNo', width: 190 },
  { title: txt(L.colType), key: 'type', width: 96 },
  { title: txt(L.colNet), key: 'netAmount', width: 140 }
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
    const res = await adminSettlementApi.list({
      current: p,
      size: page.value.size,
      merId: query.merId || undefined,
      status: query.status || undefined
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
  query.status = undefined
  reload(1)
}

async function doSync() {
  syncing.value = true
  try {
    await adminSettlementApi.sync()
    message.success(txt(L.syncOk))
    await reload(page.value.current)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    syncing.value = false
  }
}

function openGenerate() {
  genForm.merId = query.merId
  genForm.range = null
  generateOpen.value = true
}

async function doGenerate() {
  if (!genForm.merId) {
    message.warning(txt(L.merIdRequired))
    return
  }
  generating.value = true
  try {
    const params = { merId: genForm.merId }
    // 后端 LocalDateTime.parse 需 ISO-8601（2026-11-01T00:00:00）
    if (genForm.range && genForm.range[0]) params.periodStart = genForm.range[0].format('YYYY-MM-DDTHH:mm:ss')
    if (genForm.range && genForm.range[1]) params.periodEnd = genForm.range[1].format('YYYY-MM-DDTHH:mm:ss')
    await adminSettlementApi.generate(params)
    message.success(txt(L.generateOk))
    generateOpen.value = false
    await reload(1)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    generating.value = false
  }
}

async function openDetail(record) {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = {}
  try {
    detail.value = (await adminSettlementApi.detail(record.settleId)) || {}
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    detailLoading.value = false
  }
}

function doApprove(record) {
  Modal.confirm({
    title: txt(L.approveConfirm),
    okText: txt(L.confirm),
    cancelText: txt(L.cancel),
    onOk: async () => {
      approvingId.value = record.settleId
      try {
        await adminSettlementApi.approve(record.settleId)
        message.success(txt(L.approveOk))
        await reload(page.value.current)
      } catch (e) {
        /* 拦截器已提示 */
      } finally {
        approvingId.value = null
      }
    }
  })
}

function openPay(record) {
  payTarget.value = record
  payVoucher.value = ''
  payOpen.value = true
}

async function doPay() {
  if (!payVoucher.value || !payVoucher.value.trim()) {
    message.warning(txt(L.voucherRequired))
    return
  }
  paying.value = true
  try {
    await adminSettlementApi.pay(payTarget.value.settleId, payVoucher.value.trim())
    message.success(txt(L.payOk))
    payOpen.value = false
    await reload(page.value.current)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    paying.value = false
  }
}

async function doSaveCommission() {
  if (!commissionForm.merId) {
    message.warning(txt(L.merIdRequired))
    return
  }
  if (commissionForm.rate === undefined || commissionForm.rate === null) {
    message.warning(txt(L.rateRequired))
    return
  }
  savingCommission.value = true
  try {
    await adminSettlementApi.setCommission(commissionForm.merId, commissionForm.rate)
    message.success(txt(L.commissionOk))
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    savingCommission.value = false
  }
}

async function doClearCommission() {
  if (!commissionForm.merId) {
    message.warning(txt(L.merIdRequired))
    return
  }
  savingCommission.value = true
  try {
    // null = 清除商户费率，回落全局默认（app.settlement.default-commission-rate）
    await adminSettlementApi.setCommission(commissionForm.merId, null)
    message.success(txt(L.commissionCleared))
    commissionForm.rate = undefined
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    savingCommission.value = false
  }
}

onMounted(() => reload(1))
</script>

<style scoped>
.page { display: flex; flex-direction: column; gap: 16px; }
.head { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; flex-wrap: wrap; }
.eyebrow { font-size: 11px; letter-spacing: .12em; color: #94a3b8; font-weight: 700; margin-bottom: 6px; }
.head h1 { margin: 0; font-size: 24px; font-weight: 750; letter-spacing: -.02em; color: #0f172a; }
.head p { margin: 6px 0 0; font-size: 13px; color: #64748b; }
.head-actions { display: flex; gap: 10px; }
.notice { border-radius: 12px; }
.card { border-radius: 14px; border: 1px solid #e8e6e1; box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.card-title strong { display: block; font-size: 14px; color: #0f172a; }
.card-title small { display: block; font-size: 11px; color: #94a3b8; margin-top: 3px; }
.toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 14px; }
.hint { font-size: 12px; color: #94a3b8; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12.5px; }
.mt-12 { margin-top: 12px; }
.detail-head { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 24px; padding: 4px 2px 10px; }
.dh-row { display: flex; gap: 10px; font-size: 13px; color: #334155; align-items: center; }
.dh-row .k { color: #94a3b8; min-width: 88px; }
.amt-earn { color: #15803d; font-weight: 600; }
.amt-reverse { color: #b91c1c; font-weight: 600; }
.disabled { opacity: .5; pointer-events: none; }
@media (max-width: 720px) {
  .head { flex-direction: column; align-items: flex-start; }
  .detail-head { grid-template-columns: 1fr; }
}
</style>
