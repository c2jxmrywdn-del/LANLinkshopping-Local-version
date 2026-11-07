<template>
  <div class="recon-workbench-container">
    <!-- 头部操作与条件过滤条 -->
    <div class="workbench-head">
      <div class="head-left">
        <FileSpreadsheet :size="18" class="text-indigo-600 inline mr-1 align-middle" aria-hidden="true" />
        <h3 class="head-title">承运商月结对账单三单智能勾稽工作台</h3>
        <span class="head-tag">业财一体化</span>
      </div>
      <div class="head-actions">
        <a-button type="primary" class="action-btn" @click="openImportModal">
          <UploadCloud :size="13" class="inline mr-1" />导入承运商月结账单
        </a-button>
        <a-button
          v-if="currentRecon?.reconId"
          type="primary"
          class="match-btn"
          :loading="matching"
          @click="handleRunMatching"
        >
          <Play :size="13" class="inline mr-1" />执行三单智能自动勾稽
        </a-button>
        <a-button :loading="loading" @click="fetchDetail">
          <RefreshCw :size="13" class="inline mr-1" />刷新
        </a-button>
      </div>
    </div>

    <!-- 顶部四格指标卡 (21st.dev Bento Grid) -->
    <div class="recon-bento-grid" v-if="currentRecon">
      <div class="bento-card">
        <div class="bento-label">
          <FileSpreadsheet :size="13" class="inline mr-1 text-slate-400" />
          对账总笔数
        </div>
        <div class="bento-val-row">
          <span class="bento-val text-slate-800">{{ currentRecon.totalWaybills || 0 }}</span>
          <span class="bento-unit">笔运单</span>
        </div>
        <div class="bento-sub">账期周期：{{ currentRecon.billPeriod || '当月' }}</div>
      </div>

      <div class="bento-card">
        <div class="bento-label">
          <CheckCircle2 :size="13" class="inline mr-1 text-emerald-500" />
          自动平账通过率
        </div>
        <div class="bento-val-row">
          <span class="bento-val text-emerald-600">{{ passRateText }}%</span>
          <span class="bento-unit">已平账 ({{ currentRecon.matchedCount || 0 }}单)</span>
        </div>
        <div class="bento-sub">含 3% 计重合理公差平账</div>
      </div>

      <div class="bento-card">
        <div class="bento-label">
          <Scale :size="13" class="inline mr-1 text-sky-500" />
          申报运费 vs 系统核算
        </div>
        <div class="bento-val-row">
          <span class="bento-val" :class="diffClass">
            {{ Number(currentRecon.diffAmount || 0) > 0 ? '+' : '' }}¥{{ Number(currentRecon.diffAmount || 0).toFixed(2) }}
          </span>
          <span class="bento-unit">差额</span>
        </div>
        <div class="bento-sub">
          承运商 ¥{{ Number(currentRecon.carrierBilledTotal || 0).toFixed(2) }} / 系统 ¥{{ Number(currentRecon.systemFreightTotal || 0).toFixed(2) }}
        </div>
      </div>

      <div class="bento-card">
        <div class="bento-label">
          <AlertTriangle :size="13" class="inline mr-1 text-rose-500" />
          争议与待核对单
        </div>
        <div class="bento-val-row">
          <span class="bento-val text-rose-600">{{ currentRecon.discrepancyCount || 0 }}</span>
          <span class="bento-unit">单待调解</span>
        </div>
        <div class="bento-sub">状态：<a-tag :color="reconStatusColor">{{ reconStatusText }}</a-tag></div>
      </div>
    </div>

    <!-- 筛选标签栏 -->
    <div class="recon-filter-bar">
      <div class="filter-tabs">
        <button
          v-for="t in filterTabs"
          :key="t.key"
          type="button"
          class="filter-chip"
          :class="{ 'is-active': activeFilter === t.key }"
          @click="activeFilter = t.key"
        >
          {{ t.label }} ({{ countByStatus(t.key) }})
        </button>
      </div>
    </div>

    <!-- 逐单勾稽明细表格 -->
    <div class="recon-table-wrap">
      <a-table
        :data-source="filteredItems"
        :columns="tableCols"
        row-key="itemId"
        size="middle"
        :loading="loading"
        :scroll="{ x: 950 }"
        :pagination="{ pageSize: 10, showSizeChanger: false }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'waybillNo'">
            <span class="mono font-semibold">{{ record.waybillNo }}</span>
          </template>

          <template v-else-if="column.key === 'carrierBilledFreight'">
            <span class="font-medium text-slate-800">¥{{ Number(record.carrierBilledFreight || 0).toFixed(2) }}</span>
          </template>

          <template v-else-if="column.key === 'systemFreight'">
            <span class="font-medium text-slate-600">¥{{ Number(record.systemFreight || 0).toFixed(2) }}</span>
          </template>

          <template v-else-if="column.key === 'diffAmount'">
            <span :class="Number(record.diffAmount || 0) > 0 ? 'text-rose-600 font-semibold' : 'text-emerald-600 font-semibold'">
              {{ Number(record.diffAmount || 0) > 0 ? '+' : '' }}¥{{ Number(record.diffAmount || 0).toFixed(2) }}
            </span>
          </template>

          <template v-else-if="column.key === 'matchStatus'">
            <span :class="['status-chip', record.matchStatus]">
              {{ matchStatusLabel(record.matchStatus) }}
            </span>
          </template>

          <template v-else-if="column.key === 'epod'">
            <a-button
              v-if="record.epodUrl"
              type="link"
              size="small"
              class="epod-view-btn"
              @click="openEpodPreview(record.epodUrl)"
            >
              <Stamp :size="12" class="inline mr-1 text-rose-600" />查验公章原件
            </a-button>
            <span v-else class="text-xs text-slate-400">无公章回执</span>
          </template>

          <template v-else-if="column.key === 'action'">
            <a-space>
              <a-button
                v-if="record.matchStatus === 'disputed' || record.matchStatus === 'missing_waybill'"
                type="link"
                size="small"
                @click="openDisputeModal(record)"
              >
                调解平账
              </a-button>
              <span v-else-if="record.matchStatus === 'resolved'" class="text-xs text-emerald-600">
                已冲差 ¥{{ record.adjustmentAmount }}
              </span>
              <span v-else class="text-xs text-slate-400">已平账</span>
            </a-space>
          </template>
        </template>
      </a-table>
    </div>

    <!-- 导入月结对账单弹窗 -->
    <a-modal
      v-model:open="importModalOpen"
      title="导入承运商月结对账单"
      ok-text="确认导入"
      cancel-text="取消"
      :confirm-loading="importing"
      @ok="handleImportSubmit"
    >
      <a-form layout="vertical">
        <a-form-item label="承运物流服务商" required>
          <a-select v-model:value="importForm.providerId" placeholder="选择服务商">
            <a-select-option :value="1">顺丰速运 (SF)</a-select-option>
            <a-select-option :value="2">德邦大宗物流 (DBL)</a-select-option>
            <a-select-option :value="3">京东冷链专线 (JD)</a-select-option>
            <a-select-option :value="4">安能精准快运 (ANE)</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="账单所属周期 (YYYY-MM)" required>
          <a-input v-model:value="importForm.billPeriod" placeholder="如 2026-11" maxlength="7" />
        </a-form-item>
        <a-form-item label="对账批次说明">
          <a-input v-model:value="importForm.remark" placeholder="如 2026年11月华东线重货大宗账期结算" />
        </a-form-item>
        <a-form-item label="账单明细快速填报 (支持批量粘贴或一键载入样例)">
          <div style="margin-bottom: 6px;">
            <a-button size="small" @click="loadSampleData">载入模拟工业重货账单样例 (4笔典型场景)</a-button>
          </div>
          <a-textarea
            v-model:value="importText"
            :rows="6"
            placeholder="格式：运单号,承运商申报运费 (每行一笔)&#10;例：&#10;SF20261101,100.00&#10;DBL20261102,102.00&#10;ANE20261103,250.00"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- e-POD 现场加盖公章原件查验弹窗 -->
    <a-modal v-model:open="epodModalOpen" title="现场加盖公章 e-POD 回执查验" :footer="null" :width="560">
      <div class="epod-preview-box">
        <img :src="currentEpodUrl" alt="e-POD公章原件" class="epod-modal-img" />
        <p class="epod-tip">
          <ShieldCheck :size="14" class="text-emerald-600 inline mr-1" />
          该照片已在买家收货现场完成加盖公章真实性核对，不可篡改防抵赖。
        </p>
      </div>
    </a-modal>

    <!-- 人工争议调解弹窗 -->
    <a-modal
      v-model:open="disputeModalOpen"
      title="对账差异人工复核与冲差调解"
      ok-text="确认调解平账"
      cancel-text="取消"
      @ok="handleDisputeSubmit"
    >
      <div v-if="activeDisputeItem" class="dispute-modal-body">
        <a-descriptions bordered size="small" :column="1">
          <a-descriptions-item label="争议运单号">{{ activeDisputeItem.waybillNo }}</a-descriptions-item>
          <a-descriptions-item label="承运商申报运费">¥{{ activeDisputeItem.carrierBilledFreight }}</a-descriptions-item>
          <a-descriptions-item label="系统记录预估运费">¥{{ activeDisputeItem.systemFreight }}</a-descriptions-item>
          <a-descriptions-item label="差额">¥{{ activeDisputeItem.diffAmount }}</a-descriptions-item>
        </a-descriptions>
        <div style="margin-top: 14px;">
          <a-form layout="vertical">
            <a-form-item label="财务核准调账冲差金额 (¥)" required>
              <a-input-number v-model:value="disputeForm.adjustmentAmount" :min="-9999" :max="9999" :precision="2" style="width: 100%" />
            </a-form-item>
            <a-form-item label="调解平账核准批注" required>
              <a-textarea v-model:value="disputeForm.adjustNote" :rows="2" placeholder="如核实增值叉车卸车费属实，同意调账加收 ¥150" />
            </a-form-item>
          </a-form>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import {
  FileSpreadsheet, UploadCloud, Play, RefreshCw, CheckCircle2,
  Scale, AlertTriangle, Stamp, ShieldCheck
} from 'lucide-vue-next'
import { logisticsApi } from '../../api'

const props = defineProps({
  initialReconId: { type: Number, default: null }
})

const loading = ref(false)
const matching = ref(false)
const importing = ref(false)
const currentRecon = ref(null)
const items = ref([])
const activeFilter = ref('all')

const importModalOpen = ref(false)
const epodModalOpen = ref(false)
const disputeModalOpen = ref(false)
const currentEpodUrl = ref('')
const activeDisputeItem = ref(null)

const importForm = ref({
  providerId: 1,
  billPeriod: '2026-11',
  remark: '2026年11月工业专线对账结算'
})
const importText = ref('')

const disputeForm = ref({
  adjustmentAmount: 0,
  adjustNote: ''
})

const filterTabs = [
  { key: 'all', label: '全部单据' },
  { key: 'matched', label: '完全一致' },
  { key: 'diff_tolerated', label: '容差平账' },
  { key: 'disputed', label: '争议差异' },
  { key: 'resolved', label: '已调解' },
  { key: 'missing_waybill', label: '系统无此单' }
]

function countByStatus(k) {
  if (k === 'all') return items.value.length
  return items.value.filter(i => i.matchStatus === k).length
}

const filteredItems = computed(() => {
  if (activeFilter.value === 'all') return items.value
  return items.value.filter(i => i.matchStatus === activeFilter.value)
})

const passRateText = computed(() => {
  if (!currentRecon.value?.totalWaybills) return '0.0'
  const matched = Number(currentRecon.value.matchedCount || 0)
  const tot = Number(currentRecon.value.totalWaybills || 1)
  return ((matched / tot) * 100).toFixed(1)
})

const diffClass = computed(() => {
  const d = Number(currentRecon.value?.diffAmount || 0)
  if (d > 0) return 'text-rose-600'
  if (d < 0) return 'text-emerald-600'
  return 'text-slate-800'
})

const reconStatusText = computed(() => {
  const s = currentRecon.value?.status || ''
  const map = {
    imported: '已导入未勾稽',
    matching: '智能勾稽中',
    discrepancy: '存在差异争议',
    reconciled: '全部平账通过',
    closed: '已结算归档'
  }
  return map[s] || s
})

const reconStatusColor = computed(() => {
  const s = currentRecon.value?.status || ''
  if (s === 'reconciled') return 'green'
  if (s === 'discrepancy') return 'red'
  if (s === 'imported') return 'orange'
  return 'blue'
})

function matchStatusLabel(s) {
  const map = {
    matched: '完全一致',
    diff_tolerated: '容差平账 (<=3%)',
    disputed: '超额争议',
    resolved: '已人工调解',
    missing_waybill: '系统无单',
    pending: '待勾稽'
  }
  return map[s] || s
}

const tableCols = [
  { title: '运单号', key: 'waybillNo', width: 140 },
  { title: '关联订单', dataIndex: 'orderNo', key: 'orderNo', width: 130 },
  { title: '承运商申报运费', key: 'carrierBilledFreight', width: 120 },
  { title: '系统记录运费', key: 'systemFreight', width: 120 },
  { title: '运费差额', key: 'diffAmount', width: 110 },
  { title: '勾稽判定结论', key: 'matchStatus', width: 130 },
  { title: '差异分析 / 批注', dataIndex: 'diffReason', key: 'diffReason', ellipsis: true },
  { title: 'e-POD公章回执', key: 'epod', width: 120 },
  { title: '操作', key: 'action', width: 100 }
]

function openImportModal() {
  importModalOpen.value = true
}

function loadSampleData() {
  importText.value = `SF20261101,100.00\nDBL20261102,102.50\nANE20261103,260.00\nSF20261199,88.00`
}

async function handleImportSubmit() {
  if (!importText.value?.trim()) {
    message.warning('请录入对账运单及运费数据')
    return
  }
  importing.value = true
  try {
    const lines = importText.value.trim().split('\n')
    const list = []
    for (const l of lines) {
      const parts = l.split(',')
      if (parts.length >= 2) {
        list.push({
          waybillNo: parts[0].trim(),
          carrierBilledFreight: Number(parts[1].trim())
        })
      }
    }
    const payload = {
      providerId: importForm.value.providerId,
      billPeriod: importForm.value.billPeriod,
      remark: importForm.value.remark,
      items: list
    }
    const res = await logisticsApi.importRecon(payload)
    message.success('承运商账单导入成功')
    importModalOpen.value = false
    currentRecon.value = res?.data || res
    await fetchDetail()
  } catch (e) {
    // 拦截器已处理
  } finally {
    importing.value = false
  }
}

async function handleRunMatching() {
  if (!currentRecon.value?.reconId) return
  matching.value = true
  try {
    const res = await logisticsApi.runReconMatching(currentRecon.value.reconId)
    const d = res?.data || res
    currentRecon.value = d.recon
    items.value = d.items || []
    message.success(`三单智能勾稽完成：自动平账 ${d.matchedCount} 单，存在差异争议 ${d.discrepancyCount} 单`)
  } catch (e) {} finally {
    matching.value = false
  }
}

async function fetchDetail() {
  const rId = currentRecon.value?.reconId || props.initialReconId
  if (!rId) return
  loading.value = true
  try {
    const res = await logisticsApi.reconDetail(rId)
    const d = res?.data || res
    currentRecon.value = d?.recon
    items.value = d?.items || []
  } catch (e) {} finally {
    loading.value = false
  }
}

function openEpodPreview(url) {
  currentEpodUrl.value = url
  epodModalOpen.value = true
}

function openDisputeModal(item) {
  activeDisputeItem.value = item
  disputeForm.value = {
    adjustmentAmount: item.diffAmount || 0,
    adjustNote: ''
  }
  disputeModalOpen.value = true
}

async function handleDisputeSubmit() {
  if (!activeDisputeItem.value?.itemId) return
  try {
    await logisticsApi.resolveReconDispute({
      itemId: activeDisputeItem.value.itemId,
      adjustmentAmount: disputeForm.value.adjustmentAmount,
      adjustNote: disputeForm.value.adjustNote
    })
    message.success('已完成人工调解冲差平账')
    disputeModalOpen.value = false
    await fetchDetail()
  } catch (e) {}
}

onMounted(() => {
  if (props.initialReconId) {
    fetchDetail()
  }
})
</script>

<style scoped>
.recon-workbench-container {
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
  background: #ede9fe;
  color: #6d28d9;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 600;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.action-btn {
  background: #4f46e5;
  border-color: #4f46e5;
}
.match-btn {
  background: #0284c7;
  border-color: #0284c7;
}

/* Bento 指标卡 */
.recon-bento-grid {
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

/* 过滤栏 */
.recon-filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.filter-tabs {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.filter-chip {
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 11.5px;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  color: #64748b;
  cursor: pointer;
  transition: all 0.15s;
  touch-action: manipulation;
}
.filter-chip.is-active {
  background: #0284c7;
  border-color: #0284c7;
  color: #ffffff;
  font-weight: 600;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

/* 状态标签 */
.status-chip {
  display: inline-block;
  padding: 2px 7px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}
.status-chip.matched {
  background: #dcfce7;
  color: #15803d;
}
.status-chip.diff_tolerated {
  background: #e0f2fe;
  color: #0369a1;
}
.status-chip.disputed {
  background: #fee2e2;
  color: #b91c1c;
}
.status-chip.resolved {
  background: #fef3c7;
  color: #b45309;
}
.status-chip.missing_waybill {
  background: #f1f5f9;
  color: #64748b;
}

.epod-preview-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}
.epod-modal-img {
  max-width: 100%;
  max-height: 400px;
  object-fit: contain;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
}
.epod-tip {
  font-size: 12px;
  color: #475569;
}

@media (max-width: 768px) {
  .recon-bento-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
