<template>
  <div class="stripe-card-wrapper">
    <!-- 3D 拟真虚拟信用卡 (参考 21st.dev 风格) -->
    <div class="card-scene">
      <div class="virtual-card" :class="{ 'is-flipped': isCvcFocused }">
        <!-- 卡正面 -->
        <div class="card-face card-front">
          <div class="card-chip"></div>
          <div class="card-brand-badge">{{ cardBrandName }}</div>
          <div class="card-number-display">
            {{ formattedCardNumber || '•••• •••• •••• ••••' }}
          </div>
          <div class="card-details-row">
            <div class="card-holder">
              <span class="label">CARDHOLDER</span>
              <span class="value">{{ cardHolderName || 'LANLINK ENTERPRISE' }}</span>
            </div>
            <div class="card-expiry">
              <span class="label">EXPIRES</span>
              <span class="value">{{ expiryDate || 'MM/YY' }}</span>
            </div>
          </div>
        </div>

        <!-- 卡背面 -->
        <div class="card-face card-back">
          <div class="magnetic-strip"></div>
          <div class="cvc-bar">
            <span class="label">CVC</span>
            <div class="cvc-box">{{ cvcValue || '•••' }}</div>
          </div>
          <div class="card-back-note">
            <Lock :size="12" class="inline mr-1" />
            <span>256-Bit SSL Encrypted & PCI-DSS SAQ A Compliant</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 表单输入与安全提示 -->
    <div class="card-inputs-box">
      <div class="input-row">
        <label class="field-label">持卡人姓名 (拼音/英文)</label>
        <a-input
          v-model:value="cardHolderName"
          placeholder="如：ZHANG SAN"
          class="custom-input"
          :maxlength="40"
        />
      </div>

      <div class="input-row">
        <label class="field-label">卡号 (Credit / Debit Card)</label>
        <a-input
          v-model:value="rawCardNumber"
          placeholder="16位数字卡号"
          class="custom-input mono"
          :maxlength="19"
          @input="onCardNumberInput"
        >
          <template #prefix>
            <CreditCard :size="16" class="text-slate-400 mr-1" />
          </template>
        </a-input>
      </div>

      <div class="input-grid-two">
        <div class="input-row">
          <label class="field-label">有效期 (MM/YY)</label>
          <a-input
            v-model:value="expiryDate"
            placeholder="08/28"
            class="custom-input mono"
            :maxlength="5"
            @input="onExpiryInput"
          />
        </div>

        <div class="input-row">
          <label class="field-label">安全码 (CVC / CVV)</label>
          <a-input
            v-model:value="cvcValue"
            placeholder="3~4位"
            class="custom-input mono"
            :maxlength="4"
            @focus="isCvcFocused = true"
            @blur="isCvcFocused = false"
          >
            <template #prefix>
              <Lock :size="14" class="text-slate-400 mr-1" />
            </template>
          </a-input>
        </div>
      </div>

      <div class="security-banner">
        <ShieldCheck :size="16" class="text-indigo-600 flex-shrink-0" />
        <span class="security-text">
          由 Stripe 国际跨境支付网关安全托底，支持 3D Secure 2.0 银行级风控核验。
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { CreditCard, Lock, ShieldCheck } from 'lucide-vue-next'

const props = defineProps({
  clientSecret: {
    type: String,
    default: ''
  },
  publishableKey: {
    type: String,
    default: ''
  }
})

const cardHolderName = ref('')
const rawCardNumber = ref('')
const expiryDate = ref('')
const cvcValue = ref('')
const isCvcFocused = ref(false)

// 格式化卡号，4位空格分隔
const formattedCardNumber = computed(() => {
  const digits = (rawCardNumber.value || '').replace(/\D/g, '').slice(0, 16)
  return digits.replace(/(\d{4})(?=\d)/g, '$1 ')
})

function onCardNumberInput(e) {
  const v = e.target.value.replace(/\D/g, '').slice(0, 16)
  rawCardNumber.value = v.replace(/(\d{4})(?=\d)/g, '$1 ')
}

function onExpiryInput(e) {
  let v = e.target.value.replace(/\D/g, '').slice(0, 4)
  if (v.length >= 3) {
    expiryDate.value = v.slice(0, 2) + '/' + v.slice(2)
  } else {
    expiryDate.value = v
  }
}

// 识别卡品牌
const cardBrandName = computed(() => {
  const num = (rawCardNumber.value || '').replace(/\D/g, '')
  if (/^4/.test(num)) return 'VISA'
  if (/^5[1-5]/.test(num)) return 'Mastercard'
  if (/^3[47]/.test(num)) return 'AMEX'
  if (/^62/.test(num)) return 'UnionPay'
  if (/^35/.test(num)) return 'JCB'
  return 'GLOBAL CARD'
})
</script>

<style scoped>
.stripe-card-wrapper {
  display: flex;
  flex-direction: column;
  gap: 20px;
  width: 100%;
}

.card-scene {
  perspective: 1000px;
  display: flex;
  justify-content: center;
  padding: 8px 0;
}

.virtual-card {
  width: 100%;
  max-width: 340px;
  height: 200px;
  position: relative;
  transform-style: preserve-3d;
  transition: transform 0.6s cubic-bezier(0.175, 0.885, 0.32, 1.275);
  border-radius: 18px;
  box-shadow: 0 16px 36px -10px rgba(79, 70, 229, 0.35);
}

.virtual-card.is-flipped {
  transform: rotateY(180deg);
}

.card-face {
  position: absolute;
  width: 100%;
  height: 100%;
  border-radius: 18px;
  backface-visibility: hidden;
  box-sizing: border-box;
  overflow: hidden;
}

.card-front {
  background: linear-gradient(135deg, #1e1b4b, #312e81, #4338ca);
  color: #ffffff;
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.card-chip {
  width: 42px;
  height: 30px;
  background: linear-gradient(135deg, #fbbf24, #d97706);
  border-radius: 6px;
  border: 1px solid rgba(255, 255, 255, 0.3);
}

.card-brand-badge {
  position: absolute;
  top: 20px;
  right: 24px;
  font-weight: 800;
  font-size: 15px;
  letter-spacing: 0.05em;
  color: rgba(255, 255, 255, 0.9);
}

.card-number-display {
  font-family: monospace;
  font-size: 18px;
  letter-spacing: 0.12em;
  font-weight: 600;
  color: #f8fafc;
  margin: 12px 0 6px;
}

.card-details-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
}

.card-details-row .label {
  display: block;
  font-size: 9px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: #a5b4fc;
  margin-bottom: 2px;
}

.card-details-row .value {
  font-size: 12.5px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: #ffffff;
}

.card-back {
  background: linear-gradient(135deg, #1e1b4b, #312e81);
  color: #ffffff;
  transform: rotateY(180deg);
  padding: 24px 0 16px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.magnetic-strip {
  width: 100%;
  height: 40px;
  background: #09090b;
}

.cvc-bar {
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

.cvc-bar .label {
  font-size: 10px;
  font-weight: 600;
  color: #a5b4fc;
}

.cvc-box {
  width: 70px;
  height: 32px;
  background: #ffffff;
  color: #0f172a;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: monospace;
  font-weight: 700;
  letter-spacing: 0.1em;
}

.card-back-note {
  padding: 0 24px;
  font-size: 10px;
  color: #94a3b8;
  display: flex;
  align-items: center;
}

.card-inputs-box {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.input-row {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.field-label {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--mc-ink, #1e293b);
}

.custom-input {
  border-radius: 10px;
  height: 42px;
  font-size: 14px;
}

.input-grid-two {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.security-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #f5f3ff;
  border: 1px solid #ddd6fe;
  border-radius: 12px;
  padding: 10px 14px;
}

.security-text {
  font-size: 12px;
  color: #4f46e5;
  line-height: 1.4;
}
</style>
