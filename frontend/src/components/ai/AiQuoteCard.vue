<template>
  <div v-if="quote && (quote.productId || quote.finalTotalAmount)" class="ai-quote-card">
    <div class="quote-header">
      <span class="quote-tag" style="display:inline-flex;align-items:center;gap:4px">
        <CircleDollarSign :size="14" class="text-amber-500" aria-hidden="true" />
        B2B 现货阶梯核价单
      </span>
      <span v-if="quote.appliedTierDesc" class="tier-pill">{{ quote.appliedTierDesc }}</span>
    </div>

    <div class="quote-body">
      <h4 class="product-title">{{ quote.title || `工业品 #${quote.productId}` }}</h4>

      <div class="quote-grid">
        <div class="grid-item">
          <span class="label">采购数量</span>
          <span class="value">{{ quote.quantity }} 件/套</span>
        </div>
        <div class="grid-item">
          <span class="label">梯度单价</span>
          <span class="value price">¥{{ formatNumber(quote.effectiveUnitPrice) }}</span>
        </div>
        <div class="grid-item">
          <span class="label">增值税专用发票</span>
          <span class="value">含税 13% (¥{{ formatNumber(quote.estimatedVatTax) }})</span>
        </div>
        <div class="grid-item">
          <span class="label">预估专线运费</span>
          <span class="value">{{ Number(quote.estimatedFreight) > 0 ? `¥${formatNumber(quote.estimatedFreight)}` : '满额免运费' }}</span>
        </div>
      </div>

      <div class="quote-total-bar">
        <div class="total-left">
          <span class="total-label">核算总计：</span>
          <strong class="total-price">¥{{ formatNumber(quote.finalTotalAmount) }}</strong>
          <span v-if="Number(quote.totalSavings) > 0" class="savings-tag">
            立省 ¥{{ formatNumber(quote.totalSavings) }}
          </span>
        </div>

        <button type="button" class="btn-checkout" @click="handleDraftCheckout">
          一键生成意向单并结算 →
        </button>
      </div>

      <p v-if="quote.quotationSummary" class="quote-summary" style="display:flex;align-items:flex-start;gap:6px">
        <Lightbulb :size="15" class="text-amber-500 flex-shrink-0" style="margin-top:2px" aria-hidden="true" />
        <span>{{ quote.quotationSummary }}</span>
      </p>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { CircleDollarSign, Lightbulb } from 'lucide-vue-next'

const props = defineProps({
  quote: {
    type: Object,
    default: () => ({})
  }
})

const emit = defineEmits(['checkout-draft'])
const router = useRouter()

function formatNumber(num) {
  if (num === undefined || num === null) return '0.00'
  return Number(num).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function handleDraftCheckout() {
  emit('checkout-draft', props.quote)
  if (props.quote.draftId) {
    router.push({ path: '/checkout', query: { draftId: props.quote.draftId } })
  } else if (props.quote.productId) {
    router.push({ path: '/checkout', query: { productId: props.quote.productId, quantity: props.quote.quantity || 1 } })
  }
}
</script>

<style scoped>
.ai-quote-card {
  margin: 10px 0;
  border-radius: 12px;
  background: linear-gradient(135deg, #ffffff 0%, #f8fafc 100%);
  border: 1px solid rgba(203, 213, 225, 0.85);
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.05);
  overflow: hidden;
}

.quote-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: linear-gradient(90deg, #1e293b 0%, #334155 100%);
  color: #ffffff;
}

.quote-tag {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.tier-pill {
  padding: 1px 7px;
  border-radius: 999px;
  background: rgba(245, 158, 11, 0.25);
  color: #fcd34d;
  font-size: 10px;
  font-weight: 600;
  border: 1px solid rgba(245, 158, 11, 0.4);
}

.quote-body {
  padding: 10px 14px;
}

.product-title {
  margin: 0 0 8px 0;
  font-size: 13px;
  font-weight: 700;
  color: #0f172a;
}

.quote-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 6px 12px;
  padding: 8px;
  border-radius: 8px;
  background: rgba(241, 245, 249, 0.6);
  font-size: 11.5px;
}

.grid-item {
  display: flex;
  flex-direction: column;
}

.grid-item .label {
  color: #64748b;
  font-size: 10.5px;
}

.grid-item .value {
  color: #1e293b;
  font-weight: 600;
}

.grid-item .value.price {
  color: #e11d48;
  font-family: ui-monospace, SFMono-Regular, monospace;
}

.quote-total-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed rgba(203, 213, 225, 0.8);
}

.total-left {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.total-label {
  font-size: 11px;
  color: #64748b;
}

.total-price {
  font-size: 15px;
  font-weight: 750;
  color: #e11d48;
  font-family: ui-monospace, SFMono-Regular, monospace;
}

.savings-tag {
  margin-left: 4px;
  padding: 1px 5px;
  border-radius: 4px;
  background: rgba(16, 185, 129, 0.12);
  color: #059669;
  font-size: 10px;
  font-weight: 600;
}

.btn-checkout {
  padding: 6px 12px;
  border-radius: 6px;
  border: none;
  background: #2563eb;
  color: #ffffff;
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
  box-shadow: 0 2px 6px rgba(37, 99, 235, 0.25);
}

.btn-checkout:hover {
  background: #1d4ed8;
  transform: translateY(-1px);
  box-shadow: 0 4px 10px rgba(37, 99, 235, 0.35);
}

.quote-summary {
  margin: 6px 0 0 0;
  font-size: 10.5px;
  color: #64748b;
  line-height: 1.4;
}
</style>
