<template>
  <div class="adjust">
    <div class="hero">
      <div>
        <div class="eyebrow">CREDIT ADJUSTMENT</div>
        <h1>授信调整</h1>
        <p>提交新的额度与账期申请，审核期间原授信额度继续可用。</p>
      </div>
      <div class="hero-actions">
        <a-button @click="$router.push('/credit-term')">返回账期中心</a-button>
        <a-button @click="$router.push('/credit-term/bills')">账单中心</a-button>
      </div>
    </div>

    <a-alert
      v-if="loadError"
      type="error"
      show-icon
      message="调额信息加载失败"
      description="请检查网络连接后重试。"
      class="notice"
    >
      <template #action><a-button size="small" @click="load">重试</a-button></template>
    </a-alert>
    <a-skeleton v-else-if="loading" active :paragraph="{ rows: 7 }" />

    <template v-else-if="accountStatus === 'ACTIVE'">
      <a-card class="credit-card" :bordered="false">
        <div>
          <span>当前授信额度</span>
          <strong>¥{{ money(account?.creditLimit) }}</strong>
          <small>可用 ¥{{ money(account?.availableLimit) }} · 已用 ¥{{ money(account?.usedLimit) }}</small>
        </div>
        <div class="credit-meta">
          <div><span>账期</span><b>{{ account?.termDays || 0 }} 天</b></div>
          <div><span>风险等级</span><b>{{ account?.riskLevel || '标准' }}</b></div>
        </div>
      </a-card>

      <a-card v-if="pending" title="待审核的调额申请" :bordered="false" class="pending-card">
        <a-alert
          type="warning"
          show-icon
          message="审核中"
          description="审核期间原授信额度继续可用；审核通过后按新额度与账期执行。"
          class="notice"
        />
        <a-descriptions bordered :column="{ xs: 1, sm: 2, md: 2 }">
          <a-descriptions-item label="申请额度">¥{{ money(pending.requestedLimit) }}</a-descriptions-item>
          <a-descriptions-item label="申请账期">{{ pending.termDays }} 天</a-descriptions-item>
          <a-descriptions-item label="提交时间">{{ prettyTime(pending.applyTime) }}</a-descriptions-item>
          <a-descriptions-item label="申请状态">待审核</a-descriptions-item>
          <a-descriptions-item label="调整原因" :span="2">{{ pending.purpose || '—' }}</a-descriptions-item>
        </a-descriptions>
        <div class="pending-actions">
          <a-button danger :loading="withdrawing" @click="confirmWithdraw">撤回申请</a-button>
        </div>
      </a-card>

      <a-card v-else title="提交调额申请" :bordered="false" class="form-card">
        <a-form ref="formRef" layout="vertical" :model="form" :rules="rules" @finish="confirmSubmit">
          <div class="form-grid">
            <a-form-item label="新授信额度" name="requestedLimit">
              <a-input-number
                v-model:value="form.requestedLimit"
                :min="minLimit"
                :max="500000"
                :precision="2"
                style="width:100%"
                addon-before="¥"
              />
              <p class="hint">不得低于已用额度 ¥{{ money(account?.usedLimit) }}，最高 500,000 元。</p>
            </a-form-item>
            <a-form-item label="新账期" name="termDays">
              <a-radio-group v-model:value="form.termDays" class="terms">
                <a-radio-button :value="30">30 天</a-radio-button>
                <a-radio-button :value="60">60 天</a-radio-button>
                <a-radio-button :value="90">90 天</a-radio-button>
              </a-radio-group>
            </a-form-item>
          </div>
          <a-form-item label="调整原因" name="purpose">
            <a-textarea
              v-model:value="form.purpose"
              :rows="3"
              maxlength="255"
              show-count
              placeholder="例如：采购规模扩大、季节性备货、账期匹配回款周期"
            />
          </a-form-item>
          <div class="actions">
            <a-button @click="resetForm">重置</a-button>
            <a-button type="primary" html-type="submit" :loading="submitting">提交调额申请</a-button>
          </div>
        </a-form>
      </a-card>

      <a-card title="调额记录" :bordered="false" class="history-card">
        <a-table
          v-if="adjustments.length"
          :data-source="adjustments"
          :pagination="{ pageSize: 8, hideOnSinglePage: true }"
          row-key="id"
          :scroll="{ x: 820 }"
        >
          <a-table-column title="申请额度" key="requestedLimit" width="120">
            <template #default="{ record }">¥{{ money(record.requestedLimit) }}</template>
          </a-table-column>
          <a-table-column title="申请账期" key="termDays" width="100">
            <template #default="{ record }">{{ record.termDays }} 天</template>
          </a-table-column>
          <a-table-column title="审批额度" key="approvedLimit" width="120">
            <template #default="{ record }">
              {{ record.approvedLimit ? `¥${money(record.approvedLimit)}` : '—' }}
            </template>
          </a-table-column>
          <a-table-column title="审批账期" key="approvedTermDays" width="100">
            <template #default="{ record }">{{ record.approvedTermDays ? `${record.approvedTermDays} 天` : '—' }}</template>
          </a-table-column>
          <a-table-column title="状态" key="status" width="100">
            <template #default="{ record }">
              <a-tag :color="statusColor(record.status)">{{ statusLabel(record.status) }}</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="提交时间" key="applyTime" width="170">
            <template #default="{ record }">{{ prettyTime(record.applyTime) }}</template>
          </a-table-column>
          <a-table-column title="审核备注" key="reviewRemark">
            <template #default="{ record }">{{ record.reviewRemark || '—' }}</template>
          </a-table-column>
        </a-table>
        <a-empty v-else description="暂无调额记录" />
      </a-card>
    </template>

    <a-card v-else :bordered="false" class="empty-card">
      <a-empty :description="accountStatus === 'PENDING' ? '授信申请审核中，暂不可调额' : '尚未开通企业账期，暂不可调额'">
        <a-button type="primary" @click="goApply">
          {{ accountStatus === 'PENDING' ? '查看申请' : '去申请授信' }}
        </a-button>
      </a-empty>
    </a-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Modal, message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { creditTermApi } from '../api'
import { money, prettyTime } from '../utils/creditTerm'

const router = useRouter()
const formRef = ref()
const loading = ref(true)
const loadError = ref(false)
const submitting = ref(false)
const withdrawing = ref(false)
const accountStatus = ref('NOT_APPLIED')
const account = ref(null)
const adjustments = ref([])
const pending = ref(null)

const form = reactive({ requestedLimit: 0, termDays: 30, purpose: '' })

const minLimit = computed(() => Math.max(1000, Number(account.value?.usedLimit || 0)))

const rules = {
  requestedLimit: [
    { required: true, message: '请输入新的授信额度', type: 'number' },
    {
      type: 'number',
      validator: (_rule, value) => {
        const used = Number(account.value?.usedLimit || 0)
        if (Number(value) < used) return Promise.reject(new Error(`新额度不能低于已用额度 ¥${money(used)}`))
        if (Number(value) > 500000) return Promise.reject(new Error('授信额度不可超过 500,000 元'))
        return Promise.resolve()
      }
    }
  ],
  termDays: [{ required: true, message: '请选择账期', type: 'number' }]
}

const statusLabel = (value) =>
  value === 'PENDING' ? '待审核' : value === 'APPROVED' ? '已通过' : value === 'REJECTED' ? '已驳回' : '已撤回'
const statusColor = (value) =>
  value === 'PENDING' ? 'orange' : value === 'APPROVED' ? 'green' : value === 'REJECTED' ? 'red' : 'default'

function resetForm() {
  form.requestedLimit = Number(account.value?.creditLimit || 0)
  form.termDays = account.value?.termDays || 30
  form.purpose = ''
  formRef.value?.clearValidate?.()
}

function goApply() {
  router.push('/credit-term/apply')
}

function confirmSubmit() {
  if (Number(form.requestedLimit) === Number(account.value?.creditLimit) && form.termDays === account.value?.termDays) {
    message.warning('新额度与账期均未变化，无需提交')
    return
  }
  Modal.confirm({
    title: '确认提交调额申请？',
    content: `申请额度 ¥${money(form.requestedLimit)} / ${form.termDays} 天。审核期间当前授信继续可用。`,
    okText: '提交申请',
    cancelText: '取消',
    onOk: submit
  })
}

async function submit() {
  submitting.value = true
  try {
    await creditTermApi.applyAdjust({
      requestedLimit: Number(form.requestedLimit),
      termDays: form.termDays,
      purpose: form.purpose
    })
    message.success('调额申请已提交')
    await load()
  } catch (e) {
    // 请求拦截器已统一提示
  } finally {
    submitting.value = false
  }
}

function confirmWithdraw() {
  Modal.confirm({
    title: '撤回调额申请？',
    content: '撤回后本次申请不再进入审核，你可以重新提交新的调额申请。',
    okText: '确认撤回',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onOk: withdraw
  })
}

async function withdraw() {
  withdrawing.value = true
  try {
    await creditTermApi.withdrawAdjust(pending.value.id)
    message.success('调额申请已撤回')
    await load()
  } catch (e) {
    // 请求拦截器已统一提示
  } finally {
    withdrawing.value = false
  }
}

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const [overview, list] = await Promise.all([creditTermApi.overview(), creditTermApi.adjustments()])
    accountStatus.value = overview?.status || 'NOT_APPLIED'
    account.value = overview?.account || null
    adjustments.value = list || []
    pending.value = adjustments.value.find((item) => item.status === 'PENDING') || overview?.pendingAdjustment || null
    if (account.value) {
      form.requestedLimit = Number(account.value.creditLimit || 0)
      form.termDays = account.value.termDays || 30
      form.purpose = ''
    }
  } catch (e) {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.adjust{max-width:1000px;margin:0 auto}
.hero{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin-bottom:18px;flex-wrap:wrap}
.eyebrow{font-size:11px;letter-spacing:.18em;color:var(--ll-primary)}
h1{margin:5px 0}
.hero p{margin:0;color:var(--ll-muted)}
.hero-actions{display:flex;gap:10px;flex-wrap:wrap}
.notice{margin-bottom:16px;border-radius:14px}
.credit-card{border-radius:22px;background:var(--ll-brand-hero-gradient);color:#fff;box-shadow:var(--ll-glass-shadow);margin-bottom:16px}
.credit-card :deep(.ant-card-body){display:flex;justify-content:space-between;align-items:center;gap:20px;flex-wrap:wrap}
.credit-card span,.credit-card small{display:block;opacity:.7}
.credit-card strong{display:block;font-size:38px;margin:4px 0}
.credit-meta{display:flex;gap:12px}
.credit-meta div{padding:10px 14px;border:1px solid rgba(255,255,255,.16);border-radius:12px;background:rgba(255,255,255,.06);min-width:100px}
.credit-meta b{display:block;margin-top:4px}
.pending-card,.form-card,.history-card,.empty-card{border-radius:18px;margin-bottom:16px}
.pending-actions{display:flex;justify-content:flex-end;margin-top:16px}
.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.terms{display:flex;flex-wrap:wrap}
.hint{font-size:12px;color:var(--ll-muted);margin:6px 0 0;line-height:1.6}
.actions{display:flex;justify-content:flex-end;gap:10px}
@media(max-width:700px){
  .hero{align-items:flex-start;flex-direction:column;gap:10px}
  .hero-actions{width:100%}
  .credit-card :deep(.ant-card-body){flex-direction:column;align-items:flex-start}
  .credit-card strong{font-size:30px}
  .credit-meta{width:100%}
  .credit-meta div{flex:1}
  .form-grid{grid-template-columns:1fr}
  .actions{flex-direction:column-reverse}
  .actions :deep(.ant-btn){width:100%}
  .pending-actions :deep(.ant-btn){width:100%}
}
</style>
