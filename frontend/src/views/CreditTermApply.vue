<template>
  <div class="apply">
    <a-button type="link" class="back" @click="$router.push('/credit-term')">← 返回账期中心</a-button>

    <a-card class="card" :bordered="false">
      <div class="eyebrow">CREDIT APPLICATION</div>
      <h1>{{ status === 'ACTIVE' ? '企业账期' : '申请企业账期' }}</h1>
      <p class="sub">
        {{ status === 'ACTIVE'
          ? '查看当前授信；如需调整额度或账期，请提交调额申请。'
          : '根据采购规模填写申请额度与账期，审核通过后即可在结算页使用。' }}
      </p>

      <a-alert
        v-if="loadError"
        type="error"
        show-icon
        message="申请信息加载失败"
        description="请检查网络后重试。"
        class="alert"
      >
        <template #action><a-button size="small" @click="load">重试</a-button></template>
      </a-alert>
      <a-skeleton v-else-if="loading" active :paragraph="{ rows: 6 }" />

      <template v-else>
        <a-alert
          v-if="status === 'ACTIVE'"
          type="success"
          show-icon
          message="当前授信已生效"
          description="已有生效中的授信额度，如需调整额度或账期，请提交调额申请。"
          class="alert"
        />
        <a-alert
          v-else-if="status === 'PENDING'"
          type="warning"
          show-icon
          message="授信审核中"
          description="平台正在核验企业信息与采购需求。如需修改，可重新提交申请覆盖当前待审内容。"
          class="alert"
        />
        <a-alert
          v-else-if="status === 'REJECTED'"
          type="error"
          show-icon
          message="本次申请未通过"
          :description="account?.reviewRemark || '可根据审核备注调整后重新提交。'"
          class="alert"
        />

        <a-descriptions
          v-if="status !== 'NOT_APPLIED' && account"
          bordered
          :column="{ xs: 1, sm: 2, md: 2 }"
          class="current"
        >
          <a-descriptions-item :label="status === 'ACTIVE' ? '授信额度' : '申请额度'">
            ¥{{ money(status === 'ACTIVE' ? account.creditLimit : account.requestedLimit) }}
          </a-descriptions-item>
          <a-descriptions-item :label="status === 'ACTIVE' ? '当前账期' : '申请账期'">{{ account.termDays }} 天</a-descriptions-item>
          <a-descriptions-item :label="status === 'ACTIVE' ? '生效时间' : '提交时间'">
            {{ prettyTime(status === 'ACTIVE' ? (account.approvedTime || account.applyTime) : account.applyTime) }}
          </a-descriptions-item>
          <a-descriptions-item :label="status === 'ACTIVE' ? '授信状态' : '申请状态'">{{ statusLabel(account.status) }}</a-descriptions-item>
          <a-descriptions-item label="主要用途" :span="2">{{ account.purpose || '—' }}</a-descriptions-item>
        </a-descriptions>

        <div v-if="status === 'ACTIVE'" class="active-actions">
          <a-button @click="$router.push('/credit-term/bills')">查看账单</a-button>
          <a-button type="primary" @click="$router.push('/credit-term/adjust')">去调额申请</a-button>
        </div>

        <template v-else>
          <a-alert
            type="info"
            show-icon
            message="申请口径"
            description="平台会综合企业资料、采购记录与履约情况评估授信；页面额度为申请上限，并非承诺授信额度。"
            class="alert"
          />

          <a-form ref="formRef" layout="vertical" :model="form" :rules="rules" @finish="onFinish">
            <a-form-item label="申请额度" name="requestedLimit">
              <a-input-number
                v-model:value="form.requestedLimit"
                :min="1000"
                :max="500000"
                :precision="2"
                style="width:100%"
                addon-before="¥"
              />
              <p class="hint">额度范围 1,000 – 500,000 元；审核通过后以此为基础核定授信。</p>
            </a-form-item>

            <a-form-item label="账期" name="termDays">
              <a-radio-group v-model:value="form.termDays" class="terms">
                <a-radio-button :value="30">30 天</a-radio-button>
                <a-radio-button :value="60">60 天</a-radio-button>
                <a-radio-button :value="90">90 天</a-radio-button>
              </a-radio-group>
            </a-form-item>

            <a-form-item label="主要用途" name="purpose">
              <a-textarea
                v-model:value="form.purpose"
                :rows="4"
                maxlength="255"
                show-count
                placeholder="例如：钢材原料采购、季节性备货、项目型集中采购"
              />
            </a-form-item>

            <div class="actions">
              <a-button @click="$router.push('/credit-term')">返回</a-button>
              <a-button type="primary" html-type="submit" :loading="submitting">
                {{ status === 'PENDING' ? '重新提交申请' : '提交申请' }}
              </a-button>
            </div>
          </a-form>
        </template>

        <a-divider />
        <a-steps :current="status === 'PENDING' ? 1 : 0" :items="steps" size="small" />
      </template>
    </a-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Modal, message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { creditTermApi } from '../api'
import { money, prettyTime } from '../utils/creditTerm'

const router = useRouter()
const formRef = ref()
const loading = ref(true)
const loadError = ref(false)
const submitting = ref(false)
const status = ref('NOT_APPLIED')
const account = ref(null)
const form = reactive({ requestedLimit: 100000, termDays: 30, purpose: '' })

const rules = {
  requestedLimit: [
    { required: true, message: '请输入申请额度', type: 'number' },
    { type: 'number', min: 1000, max: 500000, message: '申请额度需在 1,000 – 500,000 元之间' }
  ],
  termDays: [{ required: true, message: '请选择账期', type: 'number' }]
}

const steps = [
  { title: '提交申请', description: '填写额度账期' },
  { title: '平台审核', description: '核验企业资料' },
  { title: '生效使用', description: '结算选择账期' },
  { title: '到期还款', description: '钱包或转账还款' }
]

const statusLabel = (value) =>
  value === 'ACTIVE' ? '已生效' : value === 'PENDING' ? '待审核' : value === 'REJECTED' ? '已驳回' : '未申请'

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const data = await creditTermApi.overview()
    status.value = data?.status || 'NOT_APPLIED'
    account.value = data?.account || null
    if (account.value) {
      form.requestedLimit = Number(account.value.requestedLimit || account.value.creditLimit || 100000)
      form.termDays = account.value.termDays || 30
      form.purpose = account.value.purpose || ''
    }
  } catch (e) {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function onFinish() {
  if (status.value === 'PENDING') {
    Modal.confirm({
      title: '重新提交申请？',
      content: '当前申请仍在审核中，重新提交将覆盖原申请内容。',
      okText: '重新提交',
      cancelText: '取消',
      onOk: submit
    })
    return
  }
  submit()
}

async function submit() {
  submitting.value = true
  try {
    await creditTermApi.apply({
      requestedLimit: Number(form.requestedLimit),
      termDays: form.termDays,
      purpose: form.purpose
    })
    message.success('账期申请已提交')
    router.push('/credit-term')
  } catch (e) {
    // 请求拦截器已统一提示
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.apply{max-width:760px;margin:0 auto}
.back{padding-left:0;margin-bottom:6px}
.card{border-radius:22px;box-shadow:0 18px 60px rgba(19,35,58,.08)}
.eyebrow{font-size:11px;letter-spacing:.16em;color:var(--ll-primary)}
h1{margin:6px 0}
.sub{color:var(--ll-muted);margin-bottom:18px}
.alert{margin-bottom:16px;border-radius:12px}
.current{margin-bottom:18px}
.active-actions{display:flex;justify-content:flex-end;gap:10px;margin-bottom:8px}
.terms{display:flex;flex-wrap:wrap}
.hint{font-size:12px;color:var(--ll-muted);margin:6px 0 0;line-height:1.6}
.actions{display:flex;justify-content:flex-end;gap:10px}
@media(max-width:700px){
  .actions{flex-direction:column-reverse}
  .actions :deep(.ant-btn){width:100%}
  .active-actions{flex-direction:column-reverse}
  .active-actions :deep(.ant-btn){width:100%}
}
</style>
