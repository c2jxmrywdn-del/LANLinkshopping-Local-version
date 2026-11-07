<template>
  <div class="invoice-pool-container">
    <!-- 头部操作栏 -->
    <div class="workbench-head">
      <div class="head-left">
        <Receipt :size="18" class="text-emerald-600 inline mr-1 align-middle" aria-hidden="true" />
        <h3 class="head-title">6% 交通运输专用发票池与冲抵核销中枢</h3>
        <span class="head-tag">国税防伪查验</span>
      </div>
      <div class="head-actions">
        <a-button type="primary" class="upload-inv-btn" @click="openUploadModal">
          <UploadCloud :size="13" class="inline mr-1" />专票上传进池
        </a-button>
        <a-button :loading="loading" @click="fetchInvoices">
          <RefreshCw :size="13" class="inline mr-1" />刷新专票池
        </a-button>
      </div>
    </div>

    <!-- 顶部四格指标卡 (21st.dev Bento Grid) -->
    <div class="invoice-bento-grid">
      <div class="bento-card">
        <div class="bento-label">
          <Receipt :size="13" class="inline mr-1 text-slate-400" />
          专票入池总张数
        </div>
        <div class="bento-val-row">
          <span class="bento-val text-slate-800">{{ invoices.length }}</span>
          <span class="bento-unit">张专用发票</span>
        </div>
        <div class="bento-sub">全部符合交通运输业 6% 进项专票标准</div>
      </div>

      <div class="bento-card">
        <div class="bento-label">
          <BadgePercent :size="13" class="inline mr-1 text-emerald-500" />
          进项增值税总税额 (可抵扣)
        </div>
        <div class="bento-val-row">
          <span class="bento-val text-emerald-600">¥{{ totalTaxAmountText }}</span>
          <span class="bento-unit">进项税额</span>
        </div>
        <div class="bento-sub">价税分离精算至分，依法抵扣应纳税额</div>
      </div>

      <div class="bento-card">
        <div class="bento-label">
          <ShieldCheck :size="13" class="inline mr-1 text-sky-500" />
          已冲抵结算应付金额
        </div>
        <div class="bento-val-row">
          <span class="bento-val text-sky-600">¥{{ appliedTotalText }}</span>
          <span class="bento-unit">已核销</span>
        </div>
        <div class="bento-sub">与物流结算单 1:1 冲抵见票支付</div>
      </div>

      <div class="bento-card">
        <div class="bento-label">
          <Clock :size="13" class="inline mr-1 text-amber-500" />
          待查验 / 待冲抵专票
        </div>
        <div class="bento-val-row">
          <span class="bento-val text-amber-600">{{ unappliedCount }}</span>
          <span class="bento-unit">张可用</span>
        </div>
        <div class="bento-sub">国税查验通过后可冲抵物流结算单</div>
      </div>
    </div>

    <!-- 专票列表表格 -->
    <div class="invoice-table-wrap">
      <a-table
        :data-source="invoices"
        :columns="tableCols"
        row-key="invoiceId"
        size="middle"
        :loading="loading"
        :scroll="{ x: 1000 }"
        :pagination="{ pageSize: 10, showSizeChanger: false }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'invoiceCodeNo'">
            <div class="inv-code-cell">
              <span class="mono font-semibold">{{ record.invoiceNo }}</span>
              <small class="text-slate-400">代码: {{ record.invoiceCode }}</small>
            </div>
          </template>

          <template v-else-if="column.key === 'taxRate'">
            <a-tag color="blue">6.00% 专票</a-tag>
          </template>

          <template v-else-if="column.key === 'amountWithoutTax'">
            <span class="font-medium text-slate-700">¥{{ Number(record.amountWithoutTax || 0).toFixed(2) }}</span>
          </template>

          <template v-else-if="column.key === 'taxAmount'">
            <span class="font-semibold text-emerald-600">¥{{ Number(record.taxAmount || 0).toFixed(2) }}</span>
          </template>

          <template v-else-if="column.key === 'totalAmount'">
            <span class="font-bold text-slate-800">¥{{ Number(record.totalAmount || 0).toFixed(2) }}</span>
          </template>

          <template v-else-if="column.key === 'verifyStatus'">
            <span v-if="record.verifyStatus === 'verified'" class="status-chip verified">
              <ShieldCheck :size="12" class="inline mr-1 text-emerald-600" />查验通过
            </span>
            <span v-else-if="record.verifyStatus === 'pending'" class="status-chip pending">
              <Clock :size="12" class="inline mr-1 text-amber-600" />待查验
            </span>
            <span v-else class="status-chip failed">查验异常</span>
          </template>

          <template v-else-if="column.key === 'offsetStatus'">
            <span v-if="record.offsetStatus === 'applied'" class="status-chip applied">
              已冲抵单据
            </span>
            <span v-else class="status-chip unapplied">
              待核销可用
            </span>
          </template>

          <template v-else-if="column.key === 'action'">
            <a-space>
              <a-button
                v-if="record.verifyStatus !== 'verified'"
                type="link"
                size="small"
                @click="handleVerify(record)"
              >
                国税查验
              </a-button>
              <a-button
                v-if="record.verifyStatus === 'verified' && record.offsetStatus === 'unapplied'"
                type="link"
                size="small"
                class="offset-btn"
                @click="openBindModal(record)"
              >
                冲抵结算单
              </a-button>
              <a-button
                v-if="record.fileUrl"
                type="link"
                size="small"
                :href="record.fileUrl"
                target="_blank"
              >
                原件
              </a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </div>

    <!-- 专票上传入池弹窗 -->
    <a-modal
      v-model:open="uploadModalOpen"
      title="录入 6% 交通运输专用发票进池"
      ok-text="确认入池"
      cancel-text="取消"
      :confirm-loading="uploading"
      @ok="handleUploadSubmit"
    >
      <a-form layout="vertical">
        <a-row :gutter="12">
          <a-col :span="12">
            <a-form-item label="发票号码 (8或20位)" required>
              <a-input v-model:value="form.invoiceNo" placeholder="如 12345678" maxlength="32" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="发票代码 (10或12位)" required>
              <a-input v-model:value="form.invoiceCode" placeholder="如 1100223344" maxlength="32" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="12">
          <a-col :span="12">
            <a-form-item label="开票承运商" required>
              <a-select v-model:value="form.providerId" placeholder="选择承运商">
                <a-select-option :value="1">顺丰速运 (SF)</a-select-option>
                <a-select-option :value="2">德邦大宗物流 (DBL)</a-select-option>
                <a-select-option :value="3">京东冷链专线 (JD)</a-select-option>
                <a-select-option :value="4">安能精准快运 (ANE)</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="价税合计总金额 (¥)" required>
              <a-input-number v-model:value="form.totalAmount" :min="0.01" :max="999999" :precision="2" style="width: 100%" placeholder="如 1060.00" />
            </a-form-item>
          </a-col>
        </a-row>

        <!-- 6% 价税分离实时测算看板 -->
        <div class="tax-preview-card" v-if="form.totalAmount && Number(form.totalAmount) > 0">
          <div class="preview-item">
            <span class="preview-label">交通运输专票税率：</span>
            <strong>6.00%</strong>
          </div>
          <div class="preview-item">
            <span class="preview-label">不含税金额 (运费成本)：</span>
            <strong class="text-slate-800">¥{{ calculatedAmountWithoutTax }}</strong>
          </div>
          <div class="preview-item">
            <span class="preview-label">6% 增值税税额 (进项抵扣)：</span>
            <strong class="text-emerald-600 font-bold">¥{{ calculatedTaxAmount }}</strong>
          </div>
        </div>

        <a-row :gutter="12">
          <a-col :span="12">
            <a-form-item label="销方纳税人信用代码">
              <a-input v-model:value="form.sellerTaxNo" placeholder="如 91440300MA5XXXXXXX" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="购方纳税人信用代码">
              <a-input v-model:value="form.buyerTaxNo" placeholder="如 91310000MA1YYYYYYY" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item label="电子发票源文件地址 (PDF / OFD)" required>
          <a-input v-model:value="form.fileUrl" placeholder="如 https://oss.lanlink.com/inv/12345678.pdf" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 绑定结算单冲抵弹窗 -->
    <a-modal
      v-model:open="bindModalOpen"
      title="专票冲抵核销物流结算单"
      ok-text="确认冲抵核销"
      cancel-text="取消"
      @ok="handleBindSubmit"
    >
      <div v-if="activeInvoice" class="bind-modal-body">
        <a-alert
          type="info"
          show-icon
          class="mb-3"
          :message="`发票 ${activeInvoice.invoiceNo} 价税合计 ¥${activeInvoice.totalAmount}，进项税额 ¥${activeInvoice.taxAmount}。仅可用于全额或超额冲抵承运商结算单。`"
        />
        <a-form layout="vertical">
          <a-form-item label="待冲抵核销结算单 ID" required>
            <a-input-number v-model:value="bindSettlementId" placeholder="输入待冲抵结算单ID" style="width: 100%" />
          </a-form-item>
        </a-form>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import {
  Receipt, UploadCloud, RefreshCw, BadgePercent, ShieldCheck,
  Clock
} from 'lucide-vue-next'
import { logisticsApi } from '../../api'

const loading = ref(false)
const uploading = ref(false)
const invoices = ref([])

const uploadModalOpen = ref(false)
const bindModalOpen = ref(false)
const activeInvoice = ref(null)
const bindSettlementId = ref(null)

const form = ref({
  invoiceNo: '',
  invoiceCode: '',
  providerId: 1,
  totalAmount: 1060.00,
  sellerTaxNo: '91440300MA5DBL001X',
  buyerTaxNo: '91310000MA1LANLINK9',
  fileUrl: 'https://oss.lanlink.com/invoices/202611-sf-001.pdf'
})

const calculatedAmountWithoutTax = computed(() => {
  const tot = Number(form.value.totalAmount || 0)
  if (tot <= 0) return '0.00'
  return (tot / 1.06).toFixed(2)
})

const calculatedTaxAmount = computed(() => {
  const tot = Number(form.value.totalAmount || 0)
  if (tot <= 0) return '0.00'
  const withoutTax = tot / 1.06
  return (tot - withoutTax).toFixed(2)
})

const totalTaxAmountText = computed(() => {
  const sum = invoices.value.reduce((acc, cur) => acc + Number(cur.taxAmount || 0), 0)
  return sum.toFixed(2)
})

const appliedTotalText = computed(() => {
  const sum = invoices.value
    .filter(i => i.offsetStatus === 'applied')
    .reduce((acc, cur) => acc + Number(cur.totalAmount || 0), 0)
  return sum.toFixed(2)
})

const unappliedCount = computed(() => {
  return invoices.value.filter(i => i.offsetStatus === 'unapplied').length
})

const tableCols = [
  { title: '发票号码 / 代码', key: 'invoiceCodeNo', width: 170 },
  { title: '发票类型', key: 'taxRate', width: 110 },
  { title: '不含税金额', key: 'amountWithoutTax', width: 120 },
  { title: '6%进项税额', key: 'taxAmount', width: 120 },
  { title: '价税合计', key: 'totalAmount', width: 120 },
  { title: '国税查验状态', key: 'verifyStatus', width: 130 },
  { title: '结算单冲抵状态', key: 'offsetStatus', width: 130 },
  { title: '操作', key: 'action', width: 150 }
]

async function fetchInvoices() {
  loading.value = true
  try {
    const res = await logisticsApi.invoicePool()
    invoices.value = res?.data || res || []
  } catch (e) {} finally {
    loading.value = false
  }
}

function openUploadModal() {
  uploadModalOpen.value = true
}

async function handleUploadSubmit() {
  if (!form.value.invoiceNo || !form.value.invoiceCode) {
    message.warning('请填写发票代码和号码')
    return
  }
  uploading.value = true
  try {
    await logisticsApi.uploadInvoice(form.value)
    message.success('6% 交通运输专用发票已成功入池')
    uploadModalOpen.value = false
    await fetchInvoices()
  } catch (e) {} finally {
    uploading.value = false
  }
}

async function handleVerify(record) {
  try {
    await logisticsApi.verifyInvoice(record.invoiceId)
    message.success('全国增值税发票查验平台真伪查验通过！')
    await fetchInvoices()
  } catch (e) {}
}

function openBindModal(record) {
  activeInvoice.value = record
  bindSettlementId.value = null
  bindModalOpen.value = true
}

async function handleBindSubmit() {
  if (!bindSettlementId.value) {
    message.warning('请输入结算单 ID')
    return
  }
  try {
    await logisticsApi.bindInvoiceSettlement({
      invoiceId: activeInvoice.value.invoiceId,
      settlementId: bindSettlementId.value
    })
    message.success('专票已成功绑定并冲抵结算单！')
    bindModalOpen.value = false
    await fetchInvoices()
  } catch (e) {}
}

onMounted(fetchInvoices)
</script>

<style scoped>
.invoice-pool-container {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.workbench-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}
.head-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
.head-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: #1e293b;
}
.head-tag {
  font-size: 11px;
  background: #ecfdf5;
  color: #059669;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 600;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.upload-inv-btn {
  background: #059669;
  border-color: #059669;
}

/* Bento 指标卡 */
.invoice-bento-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.bento-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 12px 14px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
}
.bento-label {
  font-size: 11.5px;
  color: #64748b;
  margin-bottom: 4px;
}
.bento-val-row {
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.bento-val {
  font-size: 20px;
  font-weight: 800;
  letter-spacing: -0.02em;
}
.bento-unit {
  font-size: 11px;
  color: #94a3b8;
}
.bento-sub {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 4px;
}

.inv-code-cell {
  display: flex;
  flex-direction: column;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

/* 状态标签 */
.status-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 7px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}
.status-chip.verified {
  background: #ecfdf5;
  color: #059669;
  border: 1px solid #a7f3d0;
}
.status-chip.pending {
  background: #fffbeb;
  color: #b45309;
  border: 1px solid #fde68a;
}
.status-chip.failed {
  background: #fef2f2;
  color: #dc2626;
  border: 1px solid #fecaca;
}
.status-chip.applied {
  background: #f0fdf4;
  color: #166534;
  border: 1px solid #bbf7d0;
}
.status-chip.unapplied {
  background: #eff6ff;
  color: #1d4ed8;
  border: 1px solid #bfdbfe;
}

.tax-preview-card {
  background: #f8fafc;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.preview-item {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
}
.preview-label {
  color: #64748b;
}

.offset-btn {
  color: #059669;
}

@media (max-width: 768px) {
  .invoice-bento-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
