<template>
  <div class="page">
    <div class="head">
      <div>
        <div class="eyebrow">RISK & CREDIT</div>
        <h1>企业账期审核</h1>
        <p>审核授信与调额申请，核销对公转账并跟踪企业账期状态。</p>
      </div>
    </div>

    <a-tabs v-model:activeKey="activeTab" class="tabs" @change="onTabChange">
      <a-tab-pane key="accounts" tab="授信申请">
        <a-card :bordered="false" class="card">
          <div class="toolbar">
            <a-select v-model:value="accountStatus" style="width:170px" allow-clear placeholder="全部状态" @change="loadAccounts">
              <a-select-option value="PENDING">待审核</a-select-option>
              <a-select-option value="ACTIVE">已生效</a-select-option>
              <a-select-option value="REJECTED">已驳回</a-select-option>
            </a-select>
            <a-button :loading="loadingAccounts" @click="loadAccounts">刷新</a-button>
          </div>
          <a-table :data-source="accounts" :loading="loadingAccounts" row-key="id" :scroll="{ x: 1060 }">
            <a-table-column title="申请ID" data-index="id" width="90" />
            <a-table-column title="用户ID" data-index="userId" width="90" />
            <a-table-column title="申请额度" key="requestedLimit" width="120">
              <template #default="{ record }">¥{{ money(record.requestedLimit) }}</template>
            </a-table-column>
            <a-table-column title="授信额度" key="creditLimit" width="120">
              <template #default="{ record }">¥{{ money(record.creditLimit) }}</template>
            </a-table-column>
            <a-table-column title="已用额度" key="usedLimit" width="120">
              <template #default="{ record }">¥{{ money(record.usedLimit) }}</template>
            </a-table-column>
            <a-table-column title="账期" key="termDays" width="90">
              <template #default="{ record }">{{ record.termDays }} 天</template>
            </a-table-column>
            <a-table-column title="状态" key="status" width="100">
              <template #default="{ record }">
                <a-tag :color="accountStatusColor(record.status)">{{ accountStatusLabel(record.status) }}</a-tag>
              </template>
            </a-table-column>
            <a-table-column title="申请时间" key="applyTime" width="170">
              <template #default="{ record }">{{ prettyTime(record.applyTime) }}</template>
            </a-table-column>
            <a-table-column title="操作" key="op" width="160">
              <template #default="{ record }">
                <a-button v-if="record.status === 'PENDING'" type="primary" size="small" @click="openAccountReview(record, 'ACTIVE')">通过</a-button>
                <a-button v-if="record.status === 'PENDING'" danger size="small" class="ml" @click="openAccountReview(record, 'REJECTED')">驳回</a-button>
                <a-button v-else type="link" size="small" @click="openAccountReview(record, 'ACTIVE')">调整</a-button>
              </template>
            </a-table-column>
          </a-table>
        </a-card>
      </a-tab-pane>

      <a-tab-pane key="adjust" tab="调额申请">
        <a-card :bordered="false" class="card">
          <div class="toolbar">
            <a-select v-model:value="adjustStatus" style="width:170px" allow-clear placeholder="全部状态" @change="loadAdjustments">
              <a-select-option value="PENDING">待审核</a-select-option>
              <a-select-option value="APPROVED">已通过</a-select-option>
              <a-select-option value="REJECTED">已驳回</a-select-option>
              <a-select-option value="WITHDRAWN">已撤回</a-select-option>
            </a-select>
            <a-button :loading="loadingAdjustments" @click="loadAdjustments">刷新</a-button>
          </div>
          <a-table :data-source="adjustments" :loading="loadingAdjustments" row-key="id" :scroll="{ x: 1140 }">
            <a-table-column title="申请ID" data-index="id" width="70" />
            <a-table-column title="用户ID" data-index="userId" width="70" />
            <a-table-column title="当前额度" key="creditLimit" width="110">
              <template #default="{ record }">¥{{ money(record.creditLimit) }}</template>
            </a-table-column>
            <a-table-column title="已用额度" key="usedLimit" width="100">
              <template #default="{ record }">¥{{ money(record.usedLimit) }}</template>
            </a-table-column>
            <a-table-column title="申请额度" key="requestedLimit" width="110">
              <template #default="{ record }">¥{{ money(record.requestedLimit) }}</template>
            </a-table-column>
            <a-table-column title="申请账期" key="termDays" width="90">
              <template #default="{ record }">{{ record.termDays }} 天</template>
            </a-table-column>
            <a-table-column title="状态" key="status" width="90">
              <template #default="{ record }">
                <a-tag :color="adjustStatusColor(record.status)">{{ adjustStatusLabel(record.status) }}</a-tag>
              </template>
            </a-table-column>
            <a-table-column title="提交时间" key="applyTime" width="150">
              <template #default="{ record }">{{ prettyTime(record.applyTime) }}</template>
            </a-table-column>
            <a-table-column title="调整原因" key="purpose" width="150">
              <template #default="{ record }">{{ record.purpose || '—' }}</template>
            </a-table-column>
            <a-table-column title="操作" key="op" width="160">
              <template #default="{ record }">
                <template v-if="record.status === 'PENDING'">
                  <a-button type="primary" size="small" @click="openAdjustReview(record, 'APPROVED')">通过</a-button>
                  <a-button danger size="small" class="ml" @click="openAdjustReview(record, 'REJECTED')">驳回</a-button>
                </template>
                <span v-else>{{ record.reviewRemark || '—' }}</span>
              </template>
            </a-table-column>
          </a-table>
        </a-card>
      </a-tab-pane>

      <a-tab-pane key="transfer" tab="转账核销">
        <a-card :bordered="false" class="card">
          <div class="toolbar">
            <a-select v-model:value="transferStatus" style="width:170px" placeholder="全部状态" @change="loadTransfers">
              <a-select-option value="PENDING">待确认</a-select-option>
              <a-select-option value="CONFIRMED">已入账</a-select-option>
              <a-select-option value="REJECTED">已驳回</a-select-option>
              <a-select-option value="ALL">全部</a-select-option>
            </a-select>
            <a-button :loading="loadingTransfers" @click="loadTransfers">刷新</a-button>
          </div>
          <a-table :data-source="transfers" :loading="loadingTransfers" row-key="id" :scroll="{ x: 1160 }">
            <a-table-column title="记录ID" data-index="id" width="70" />
            <a-table-column title="用户ID" data-index="userId" width="70" />
            <a-table-column title="订单号" data-index="orderNo" width="160" />
            <a-table-column title="账单待还" key="outstandingAmount" width="100">
              <template #default="{ record }">¥{{ money(record.outstandingAmount) }}</template>
            </a-table-column>
            <a-table-column title="转账金额" key="amount" width="100">
              <template #default="{ record }"><b>¥{{ money(record.amount) }}</b></template>
            </a-table-column>
            <a-table-column title="参考号" data-index="referenceNo" width="130" />
            <a-table-column title="状态" key="status" width="80">
              <template #default="{ record }">
                <a-tag :color="repaymentStatusColor(record.status)">{{ repaymentStatusLabel(record.status) }}</a-tag>
              </template>
            </a-table-column>
            <a-table-column title="提交时间" key="createTime" width="150">
              <template #default="{ record }">{{ prettyTime(record.createTime) }}</template>
            </a-table-column>
            <a-table-column title="核销备注" key="confirmRemark" width="120">
              <template #default="{ record }">{{ record.confirmRemark || '—' }}</template>
            </a-table-column>
            <a-table-column title="操作" key="op" width="170">
              <template #default="{ record }">
                <template v-if="record.status === 'PENDING'">
                  <a-button type="primary" size="small" @click="openTransferReview(record, true)">确认入账</a-button>
                  <a-button danger size="small" class="ml" @click="openTransferReview(record, false)">驳回</a-button>
                </template>
                <span v-else>—</span>
              </template>
            </a-table-column>
          </a-table>
        </a-card>
      </a-tab-pane>
    </a-tabs>

    <a-modal
      v-model:open="accountReviewOpen"
      :title="accountReviewMode === 'ACTIVE' ? '通过 / 调整授信' : '驳回授信申请'"
      ok-text="提交"
      cancel-text="取消"
      :confirm-loading="accountSubmitting"
      @ok="submitAccountReview"
    >
      <a-form layout="vertical">
        <a-form-item v-if="accountReviewMode === 'ACTIVE'" label="批准额度">
          <a-input-number v-model:value="accountForm.approvedLimit" :min="1000" :max="500000" :precision="2" style="width:100%" />
        </a-form-item>
        <a-form-item v-if="accountReviewMode === 'ACTIVE'" label="账期">
          <a-select v-model:value="accountForm.termDays" style="width:100%">
            <a-select-option :value="30">30 天</a-select-option>
            <a-select-option :value="60">60 天</a-select-option>
            <a-select-option :value="90">90 天</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="审核备注">
          <a-textarea v-model:value="accountForm.remark" :rows="3" maxlength="255" show-count />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="adjustReviewOpen"
      :title="adjustReviewMode === 'APPROVED' ? '通过调额申请' : '驳回调额申请'"
      ok-text="提交"
      cancel-text="取消"
      :confirm-loading="adjustSubmitting"
      @ok="submitAdjustReview"
    >
      <a-form layout="vertical">
        <a-form-item v-if="adjustReviewMode === 'APPROVED'" label="批准额度">
          <a-input-number v-model:value="adjustForm.approvedLimit" :min="1000" :max="500000" :precision="2" style="width:100%" />
        </a-form-item>
        <a-form-item v-if="adjustReviewMode === 'APPROVED'" label="批准账期">
          <a-select v-model:value="adjustForm.termDays" style="width:100%">
            <a-select-option :value="30">30 天</a-select-option>
            <a-select-option :value="60">60 天</a-select-option>
            <a-select-option :value="90">90 天</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="审核备注">
          <a-textarea v-model:value="adjustForm.remark" :rows="3" maxlength="255" show-count />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="transferReviewOpen"
      :title="transferForm.approved ? '确认转账入账' : '驳回转账登记'"
      ok-text="提交"
      cancel-text="取消"
      :confirm-loading="transferSubmitting"
      @ok="submitTransferReview"
    >
      <a-alert
        :type="transferForm.approved ? 'info' : 'warning'"
        show-icon
        :message="transferForm.approved ? '确认后立即冲减账单并恢复授信' : '驳回后不冲减账单，用户可重新登记'"
        :description="transferCurrent ? `转账金额 ¥${money(transferCurrent.amount)} · 订单 ${transferCurrent.orderNo || '—'}` : ''"
        class="modal-alert"
      />
      <a-form layout="vertical">
        <a-form-item label="核销备注">
          <a-textarea v-model:value="transferForm.remark" :rows="3" maxlength="255" show-count />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { creditTermApi } from '../../api'
import { money, prettyTime, repaymentStatusColor, repaymentStatusLabel } from '../../utils/creditTerm'

const activeTab = ref('accounts')

const accounts = ref([])
const loadingAccounts = ref(false)
const accountStatus = ref()
const accountReviewOpen = ref(false)
const accountReviewMode = ref('ACTIVE')
const accountCurrent = ref(null)
const accountSubmitting = ref(false)
const accountForm = reactive({ approvedLimit: 0, termDays: 30, remark: '' })

const adjustments = ref([])
const loadingAdjustments = ref(false)
const adjustStatus = ref()
const adjustReviewOpen = ref(false)
const adjustReviewMode = ref('APPROVED')
const adjustCurrent = ref(null)
const adjustSubmitting = ref(false)
const adjustForm = reactive({ approvedLimit: 0, termDays: 30, remark: '' })

const transfers = ref([])
const loadingTransfers = ref(false)
const transferStatus = ref('PENDING')
const transferReviewOpen = ref(false)
const transferCurrent = ref(null)
const transferSubmitting = ref(false)
const transferForm = reactive({ approved: true, remark: '' })

const accountStatusLabel = (s) => (s === 'ACTIVE' ? '已生效' : s === 'PENDING' ? '待审核' : '已驳回')
const accountStatusColor = (s) => (s === 'ACTIVE' ? 'green' : s === 'PENDING' ? 'orange' : 'red')
const adjustStatusLabel = (s) =>
  s === 'PENDING' ? '待审核' : s === 'APPROVED' ? '已通过' : s === 'REJECTED' ? '已驳回' : '已撤回'
const adjustStatusColor = (s) =>
  s === 'PENDING' ? 'orange' : s === 'APPROVED' ? 'green' : s === 'REJECTED' ? 'red' : 'default'

async function loadAccounts() {
  loadingAccounts.value = true
  try {
    accounts.value = await creditTermApi.adminAccounts(accountStatus.value) || []
  } catch (e) {
    accounts.value = []
  } finally {
    loadingAccounts.value = false
  }
}

async function loadAdjustments() {
  loadingAdjustments.value = true
  try {
    adjustments.value = await creditTermApi.adminAdjustments(adjustStatus.value) || []
  } catch (e) {
    adjustments.value = []
  } finally {
    loadingAdjustments.value = false
  }
}

async function loadTransfers() {
  loadingTransfers.value = true
  try {
    transfers.value = await creditTermApi.adminRepayments(transferStatus.value) || []
  } catch (e) {
    transfers.value = []
  } finally {
    loadingTransfers.value = false
  }
}

function onTabChange(key) {
  if (key === 'accounts') loadAccounts()
  else if (key === 'adjust') loadAdjustments()
  else loadTransfers()
}

function openAccountReview(row, mode) {
  accountCurrent.value = row
  accountReviewMode.value = mode
  accountForm.approvedLimit = Number(row.creditLimit || row.requestedLimit || 0)
  accountForm.termDays = row.termDays || 30
  accountForm.remark = ''
  accountReviewOpen.value = true
}

async function submitAccountReview() {
  accountSubmitting.value = true
  try {
    await creditTermApi.review(accountCurrent.value.id, {
      status: accountReviewMode.value,
      approvedLimit: accountReviewMode.value === 'ACTIVE' ? accountForm.approvedLimit : null,
      termDays: accountReviewMode.value === 'ACTIVE' ? accountForm.termDays : null,
      remark: accountForm.remark
    })
    message.success('审核完成')
    accountReviewOpen.value = false
    await loadAccounts()
  } catch (e) {
    // 请求拦截器已统一提示
  } finally {
    accountSubmitting.value = false
  }
}

function openAdjustReview(row, mode) {
  adjustCurrent.value = row
  adjustReviewMode.value = mode
  adjustForm.approvedLimit = Number(row.requestedLimit || 0)
  adjustForm.termDays = row.termDays || 30
  adjustForm.remark = ''
  adjustReviewOpen.value = true
}

async function submitAdjustReview() {
  adjustSubmitting.value = true
  try {
    await creditTermApi.reviewAdjust(adjustCurrent.value.id, {
      status: adjustReviewMode.value,
      approvedLimit: adjustReviewMode.value === 'APPROVED' ? adjustForm.approvedLimit : null,
      termDays: adjustReviewMode.value === 'APPROVED' ? adjustForm.termDays : null,
      remark: adjustForm.remark
    })
    message.success('调额审核完成')
    adjustReviewOpen.value = false
    await loadAdjustments()
  } catch (e) {
    // 请求拦截器已统一提示
  } finally {
    adjustSubmitting.value = false
  }
}

function openTransferReview(row, approved) {
  transferCurrent.value = row
  transferForm.approved = approved
  transferForm.remark = ''
  transferReviewOpen.value = true
}

async function submitTransferReview() {
  transferSubmitting.value = true
  try {
    await creditTermApi.confirmRepayment(transferCurrent.value.id, {
      approved: transferForm.approved,
      remark: transferForm.remark
    })
    message.success(transferForm.approved ? '转账已核销入账' : '转账登记已驳回')
    transferReviewOpen.value = false
    await loadTransfers()
  } catch (e) {
    // 请求拦截器已统一提示
  } finally {
    transferSubmitting.value = false
  }
}

onMounted(loadAccounts)
</script>

<style scoped>
.page{max-width:1200px;margin:0 auto}
.head{display:flex;justify-content:space-between;align-items:flex-end;gap:20px;margin-bottom:14px}
.eyebrow{font-size:11px;letter-spacing:.18em;color:#1677ff}
h1{margin:5px 0}
.head p{margin:0;color:#64748b}
.tabs :deep(.ant-tabs-nav){margin-bottom:12px}
.card{border-radius:20px}
.toolbar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:14px}
.ml{margin-left:6px}
.modal-alert{margin-bottom:14px;border-radius:10px}
@media(max-width:700px){
  .head{align-items:flex-start;flex-direction:column;gap:8px}
  .toolbar{align-items:stretch}
  .toolbar :deep(.ant-select){width:100% !important}
}
</style>
