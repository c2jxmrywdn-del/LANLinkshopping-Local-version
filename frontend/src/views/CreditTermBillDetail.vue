<template>
  <div class="detail">
    <div class="top">
      <div>
        <a-button type="link" class="back" @click="$router.push('/credit-term/bills')">← 返回账单中心</a-button>
        <div class="eyebrow">BILL DETAIL</div>
        <h1>账期账单 #{{ bill?.id }}</h1>
        <p v-if="bill">关联订单 {{ bill.orderNo }} · 到期日 {{ bill.dueDate }}</p>
      </div>
      <a-tag v-if="bill" :color="billColor(bill)">{{ billLabel(bill) }}</a-tag>
    </div>

    <a-alert
      v-if="loadError"
      type="error"
      show-icon
      message="账单详情加载失败"
      description="请检查网络连接后重试，或返回账单中心。"
      class="notice"
    >
      <template #action><a-button size="small" @click="load">重试</a-button></template>
    </a-alert>
    <a-skeleton v-else-if="loading" active :paragraph="{ rows: 8 }" />

    <template v-else-if="bill">
      <a-card class="summary" :bordered="false">
        <div class="summary-main">
          <span>待还金额</span>
          <strong>¥{{ money(bill.outstandingAmount) }}</strong>
          <small v-if="pendingAmount > 0">其中待确认转账 ¥{{ money(pendingAmount) }}</small>
          <small v-else-if="isOverdue">已逾期 {{ overdueDays }} 天，请尽快还款</small>
          <small v-else>可还金额 ¥{{ money(payable) }}</small>
        </div>
        <div><span>账单金额</span><b>¥{{ money(bill.amount) }}</b></div>
        <div><span>已还</span><b>¥{{ money(bill.paidAmount) }}</b></div>
        <div><span>到期日</span><b>{{ bill.dueDate }}</b></div>
      </a-card>

      <a-alert
        v-if="pendingAmount > 0"
        type="warning"
        show-icon
        class="notice"
        message="有对公转账待财务核销"
        :description="`待确认金额 ¥${money(pendingAmount)}，核销前不冲减账单；核销后自动恢复对应授信额度。`"
      />

      <div class="cols">
        <a-card title="账单信息" :bordered="false" class="info-card">
          <a-descriptions bordered :column="{ xs: 1, sm: 1, md: 2 }" :label-style="{ width: '100px', whiteSpace: 'nowrap' }">
            <a-descriptions-item label="账单号">{{ bill.id }}</a-descriptions-item>
            <a-descriptions-item label="关联订单">
              <a class="order-link" @click="goOrder">{{ bill.orderNo }}</a>
            </a-descriptions-item>
            <a-descriptions-item label="账单金额">¥{{ money(bill.amount) }}</a-descriptions-item>
            <a-descriptions-item label="已还金额">¥{{ money(bill.paidAmount) }}</a-descriptions-item>
            <a-descriptions-item label="待还金额">¥{{ money(bill.outstandingAmount) }}</a-descriptions-item>
            <a-descriptions-item label="账期">{{ account?.termDays || '—' }} 天</a-descriptions-item>
            <a-descriptions-item label="出账时间">{{ prettyTime(bill.createTime) }}</a-descriptions-item>
            <a-descriptions-item label="结清时间">{{ prettyTime(bill.settledTime) }}</a-descriptions-item>
            <a-descriptions-item label="授信状态">{{ accountStatusLabel }}</a-descriptions-item>
            <a-descriptions-item label="账单状态">{{ billLabel(bill) }}</a-descriptions-item>
          </a-descriptions>
        </a-card>

        <a-card title="还款" :bordered="false" class="repay-card">
          <a-alert
            v-if="bill.status === 'PAID'"
            type="success"
            show-icon
            message="账单已结清"
            description="本账单对应授信已全部释放。"
          />
          <a-alert
            v-else-if="bill.status === 'CANCELLED'"
            type="info"
            show-icon
            message="账单已取消"
            description="订单取消后未使用授信已释放，无需还款。"
          />
          <template v-else>
            <div class="wallet-row">
              <span>企业钱包余额</span>
              <b>¥{{ money(walletBalance) }}</b>
              <a v-if="method === 'wallet' && walletInsufficient" class="recharge-link" @click="$router.push('/wallet/recharge')">
                余额不足，去充值
              </a>
            </div>

            <a-form layout="vertical">
              <a-form-item label="还款金额">
                <a-input-number
                  v-model:value="amount"
                  :min="0.01"
                  :max="maxAmount"
                  :precision="2"
                  style="width:100%"
                  addon-before="¥"
                />
                <div class="quick-row">
                  <a-button size="small" @click="fillAmount(payable)">全额 ¥{{ money(payable) }}</a-button>
                  <a-button size="small" @click="fillAmount(payable / 2)">还一半</a-button>
                  <span v-if="pendingAmount > 0" class="quick-tip">存在待确认转账，最多可还 ¥{{ money(payable) }}</span>
                </div>
              </a-form-item>

              <a-form-item label="还款方式">
                <a-radio-group v-model:value="method">
                  <a-radio value="wallet">企业钱包</a-radio>
                  <a-radio value="transfer">对公转账</a-radio>
                </a-radio-group>
              </a-form-item>

              <a-form-item v-if="method === 'transfer'" label="转账参考号 / 附言">
                <a-input v-model:value="referenceNo" :maxlength="64" show-count placeholder="选填，例如银行流水号或转账附言" />
                <p class="hint">提交后生成待确认记录，平台财务核销后自动冲减账单并恢复授信。</p>
              </a-form-item>

              <a-button
                type="primary"
                block
                html-type="button"
                :loading="submitting"
                :disabled="!canSubmit"
                @click="confirmRepay"
              >
                {{ method === 'transfer' ? '提交转账登记' : '确认还款' }}
              </a-button>
              <p class="hint">企业钱包还款实时入账；对公转账需财务核销后入账。</p>
            </a-form>
          </template>
        </a-card>
      </div>

      <a-card title="还款记录" :bordered="false" class="records-card">
        <a-table
          v-if="repayments.length"
          :data-source="repayments"
          :pagination="false"
          row-key="id"
          size="small"
          :scroll="{ x: 760 }"
        >
          <a-table-column title="还款金额" key="amount" width="120">
            <template #default="{ record }"><b>¥{{ money(record.amount) }}</b></template>
          </a-table-column>
          <a-table-column title="方式" key="method" width="110">
            <template #default="{ record }">{{ repayMethodLabel(record.method) }}</template>
          </a-table-column>
          <a-table-column title="状态" key="status" width="100">
            <template #default="{ record }">
              <a-tag :color="repaymentStatusColor(record.status)">{{ repaymentStatusLabel(record.status) }}</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="参考号" data-index="referenceNo" key="referenceNo" width="170" />
          <a-table-column title="时间" key="time" width="170">
            <template #default="{ record }">{{ prettyTime(record.createTime) }}</template>
          </a-table-column>
          <a-table-column title="备注" key="remark">
            <template #default="{ record }">{{ record.confirmRemark || record.remark || '—' }}</template>
          </a-table-column>
        </a-table>
        <a-empty v-else description="暂无还款记录" />
      </a-card>
    </template>

    <a-empty v-else description="未找到该账单">
      <a-button type="primary" @click="$router.push('/credit-term/bills')">返回账单中心</a-button>
    </a-empty>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Modal, message } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import { creditTermApi, walletApi } from '../api'
import {
  billCategory,
  billColor,
  billLabel,
  dayGap,
  money,
  prettyTime,
  repayMethodLabel,
  repaymentStatusColor,
  repaymentStatusLabel
} from '../utils/creditTerm'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const loadError = ref(false)
const bill = ref(null)
const account = ref(null)
const repayments = ref([])
const walletBalance = ref(0)
const amount = ref(0)
const method = ref('wallet')
const referenceNo = ref('')
const submitting = ref(false)

const pendingAmount = computed(() =>
  repayments.value
    .filter((r) => r.status === 'PENDING')
    .reduce((sum, r) => sum + Number(r.amount || 0), 0)
)
const payable = computed(() =>
  Math.max(0, Number(bill.value?.outstandingAmount || 0) - pendingAmount.value)
)
const maxAmount = computed(() => Math.max(payable.value, 0.01))
const walletInsufficient = computed(() => Number(amount.value || 0) > Number(walletBalance.value || 0))
const isOverdue = computed(() => bill.value && billCategory(bill.value) === 'overdue')
const overdueDays = computed(() => (bill.value?.dueDate ? Math.abs(dayGap(bill.value.dueDate)) : 0))
const accountStatusLabel = computed(() => {
  const status = account.value?.status
  return status === 'ACTIVE' ? '已生效' : status === 'PENDING' ? '待审核' : status === 'REJECTED' ? '已驳回' : '未开通'
})
const canSubmit = computed(() =>
  Number(amount.value || 0) > 0
  && Number(amount.value || 0) <= payable.value
  && !(method.value === 'wallet' && walletInsufficient.value)
)

function fillAmount(value) {
  amount.value = Math.max(0.01, Math.round(Number(value || 0) * 100) / 100)
}
function goOrder() {
  if (bill.value?.orderNo) router.push('/orders/' + bill.value.orderNo)
}

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const id = route.params.id
    const [billData, reps, overview, wallet] = await Promise.all([
      creditTermApi.detail(id),
      creditTermApi.repayments(id),
      creditTermApi.overview().catch(() => null),
      walletApi.my().catch(() => null)
    ])
    bill.value = billData
    repayments.value = reps || []
    account.value = overview?.account || null
    walletBalance.value = Number(wallet?.balance || 0)
    const pending = repayments.value
      .filter((r) => r.status === 'PENDING')
      .reduce((sum, r) => sum + Number(r.amount || 0), 0)
    amount.value = Math.max(0, Number(billData?.outstandingAmount || 0) - pending)
  } catch (e) {
    loadError.value = true
    bill.value = null
  } finally {
    loading.value = false
  }
}

function confirmRepay() {
  const value = Number(amount.value || 0)
  if (!(value > 0)) {
    message.warning('请输入还款金额')
    return
  }
  if (value > payable.value) {
    message.warning(`当前最多可还 ¥${money(payable.value)}`)
    return
  }
  if (method.value === 'wallet' && walletInsufficient.value) {
    message.warning('企业钱包余额不足，请先充值')
    return
  }
  const transfer = method.value === 'transfer'
  Modal.confirm({
    title: transfer ? '确认提交转账登记？' : '确认还款？',
    content: transfer
      ? `本次登记对公转账 ¥${money(value)}，提交后需平台财务核销，核销前不冲减账单。`
      : `将从企业钱包扣款 ¥${money(value)}，还款后授信额度实时恢复。`,
    okText: transfer ? '提交登记' : '确认还款',
    cancelText: '取消',
    onOk: doRepay
  })
}

async function doRepay() {
  submitting.value = true
  try {
    const result = await creditTermApi.repay(route.params.id, Number(amount.value), method.value, referenceNo.value)
    if (result?.pending) message.success('转账登记已提交，待平台财务核销')
    else message.success('还款成功')
    referenceNo.value = ''
    await load()
  } catch (e) {
    // 请求拦截器已统一提示，保留页面数据供用户调整
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.detail{max-width:1000px;margin:0 auto}
.top{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin-bottom:18px;flex-wrap:wrap}
.back{padding-left:0;margin-bottom:4px}
.eyebrow{font-size:11px;letter-spacing:.18em;color:var(--ll-primary)}
h1{margin:5px 0}
.top p{margin:0;color:var(--ll-muted)}
.notice{margin-bottom:16px;border-radius:14px}
.summary{border-radius:20px;background:var(--ll-brand-hero-gradient);color:#fff;margin-bottom:16px}
.summary :deep(.ant-card-body){display:flex;align-items:flex-start;gap:14px;flex-wrap:wrap}
.summary :deep(.ant-card-body) > div{flex:1 1 0;min-width:0}
.summary :deep(.ant-card-body) > .summary-main{flex:1.6 1 0}
.summary span{display:block;opacity:.65;font-size:12px}
.summary strong{display:block;font-size:40px;margin-top:4px}
.summary b{display:block;margin-top:8px;font-size:18px}
.summary small{display:block;margin-top:6px;font-size:12px;opacity:.72}
.cols{display:grid;grid-template-columns:1.15fr 1fr;gap:16px;margin-bottom:16px}
.info-card,.repay-card,.records-card{border-radius:18px}
.wallet-row{display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:10px 12px;margin-bottom:16px;border-radius:12px;background:rgba(22,119,255,.06);font-size:12px}
.wallet-row b{font-size:16px;color:var(--ll-ink)}
.recharge-link{color:#d45645;font-weight:600}
.quick-row{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-top:8px}
.quick-tip{font-size:12px;color:var(--ll-muted)}
.hint{font-size:12px;color:var(--ll-muted);margin:10px 0 0;line-height:1.6}
.order-link{color:var(--ll-primary);cursor:pointer;border-bottom:1px dashed rgba(22,119,255,.35)}
.order-link:hover{border-bottom-style:solid}
@media(max-width:900px){
  .summary :deep(.ant-card-body) > .summary-main{flex:1 1 100%}
  .summary :deep(.ant-card-body) > div{flex:1 1 28%}
  .cols{grid-template-columns:1fr}
}
@media(max-width:700px){
  .summary :deep(.ant-card-body) > div{flex:1 1 40%}
  .summary strong{font-size:32px}
  .summary b{font-size:16px}
  .top{align-items:flex-start}
}
</style>
