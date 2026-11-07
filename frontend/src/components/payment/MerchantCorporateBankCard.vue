<template>
  <div class="corporate-bank-card">
    <div class="card-header">
      <div class="header-left">
        <div class="bank-icon-wrap">
          <Building2 :size="20" class="text-sky-600" />
        </div>
        <div>
          <h4 class="bank-name">{{ bankInfo.bankName || '中国工商银行股份有限公司深圳科技园支行' }}</h4>
          <span class="sub-label">供货商认证对公收款专用账户</span>
        </div>
      </div>
      <div class="verified-pill">
        <ShieldCheck :size="13" class="mr-1 text-emerald-500" /> 已企业认证
      </div>
    </div>

    <div class="card-body">
      <div class="field-item">
        <span class="field-label">对公银行账号</span>
        <div class="account-row">
          <span class="account-number">{{ formattedAccount }}</span>
          <a-button type="text" size="small" class="copy-btn" @click="copyAccount">
            <Check v-if="copied" :size="14" class="text-emerald-600 mr-1" />
            <Copy v-else :size="14" class="text-slate-500 mr-1" />
            <span>{{ copied ? '已复制' : '复制账号' }}</span>
          </a-button>
        </div>
      </div>

      <div class="grid-details">
        <div class="detail-cell">
          <span class="field-label">收款人全称 (户名)</span>
          <span class="field-value">{{ bankInfo.legalPerson || bankInfo.legalPersonMasked || '深圳市高新工业智联科技有限公司' }}</span>
        </div>
        <div class="detail-cell">
          <span class="field-label">纳税人识别号 / 统一信用代码</span>
          <span class="field-value mono">{{ bankInfo.taxRegNo || '91440300MA5EXXXX00' }}</span>
        </div>
      </div>

      <div v-if="orderNo" class="remark-notice">
        <span class="notice-title">电汇附言备注码（必填）：</span>
        <span class="order-code">{{ orderNo }}</span>
        <p class="notice-desc">网银转账时请务必在“汇款用途/备注”中附上此订单号，系统到账后将自动核销并进入备货发货流程。</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { message } from 'ant-design-vue'
import { Building2, ShieldCheck, Copy, Check } from 'lucide-vue-next'

const props = defineProps({
  bankInfo: {
    type: Object,
    default: () => ({})
  },
  orderNo: {
    type: String,
    default: ''
  }
})

const copied = ref(false)

const formattedAccount = computed(() => {
  const acc = props.bankInfo.bankAccount || props.bankInfo.bankAccountMasked || '6222 0240 0001 8899'
  return acc
})

async function copyAccount() {
  const text = (props.bankInfo.bankAccount || props.bankInfo.bankAccountMasked || '6222024000018899').replace(/\s+/g, '')
  try {
    await navigator.clipboard.writeText(text)
    copied.value = true
    message.success('对公银行账号已复制到剪贴板')
    setTimeout(() => { copied.value = false }, 2500)
  } catch (e) {
    message.info('账号：' + text)
  }
}
</script>

<style scoped>
.corporate-bank-card {
  background: linear-gradient(145deg, #f8fafc, #f1f5f9);
  border: 1.5px solid #cbd5e1;
  border-radius: 16px;
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  position: relative;
  overflow: hidden;
}

.corporate-bank-card::before {
  content: "";
  position: absolute;
  top: 0;
  left: 0;
  width: 4px;
  height: 100%;
  background: #0284c7;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.bank-icon-wrap {
  width: 42px;
  height: 42px;
  border-radius: 10px;
  background: #e0f2fe;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.bank-name {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: #0f172a;
}

.sub-label {
  font-size: 12px;
  color: #64748b;
}

.verified-pill {
  display: flex;
  align-items: center;
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
  color: #065f46;
  font-size: 11px;
  font-weight: 600;
  padding: 3px 9px;
  border-radius: 999px;
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.field-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field-label {
  font-size: 12px;
  color: #64748b;
  font-weight: 500;
}

.account-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.account-number {
  font-family: monospace;
  font-size: 19px;
  font-weight: 750;
  letter-spacing: 0.08em;
  color: #0369a1;
}

.copy-btn {
  display: inline-flex;
  align-items: center;
  font-size: 12.5px;
  padding: 2px 8px;
  border-radius: 6px;
  background: #ffffff;
  border: 1px solid #cbd5e1;
}

.grid-details {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 12px 14px;
}

.detail-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.field-value {
  font-size: 13.5px;
  font-weight: 600;
  color: #1e293b;
}

.field-value.mono {
  font-family: monospace;
}

.remark-notice {
  background: #eff6ff;
  border: 1px dashed #93c5fd;
  border-radius: 10px;
  padding: 12px 14px;
}

.notice-title {
  font-size: 12.5px;
  font-weight: 600;
  color: #1e40af;
}

.order-code {
  font-family: monospace;
  font-weight: 750;
  color: #1d4ed8;
  font-size: 14px;
  background: #dbeafe;
  padding: 2px 8px;
  border-radius: 4px;
  margin-left: 6px;
}

.notice-desc {
  margin: 6px 0 0 0;
  font-size: 12px;
  color: #3b82f6;
  line-height: 1.45;
}

@media (max-width: 640px) {
  .grid-details {
    grid-template-columns: 1fr;
  }
}
</style>
