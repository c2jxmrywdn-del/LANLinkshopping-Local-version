<template>
  <div class="tier-editor">
    <div class="tier-head">
      <div>
        <b>阶梯批发价（选填）</b>
        <span>买家一次购买数量越多，成交单价越低；不填则按商品单价成交</span>
      </div>
      <a-button size="small" type="dashed" :disabled="rows.length >= MAX_ROWS" @click="addRow">
        <PlusOutlined /> 添加档位
      </a-button>
    </div>

    <div v-if="rows.length" class="tier-grid">
      <div class="tier-cols tier-title-row">
        <span>起购数量（件）</span>
        <span>批发单价（元）</span>
        <span class="tier-op-col"></span>
      </div>
      <div v-for="(row, idx) in rows" :key="idx" class="tier-cols">
        <a-input-number v-model:value="row.minQty" :min="1" :precision="0" style="width:100%"
                        placeholder="如 100" @blur="sortRows" />
        <a-input-number v-model:value="row.price" :min="0.01" :max="maxPrice || 9999999" :precision="2"
                        style="width:100%" placeholder="如 88.00" />
        <a-button type="text" danger size="small" class="tier-op-col" @click="rows.splice(idx, 1)">删除</a-button>
      </div>
    </div>
    <div v-else class="tier-empty">未配置阶梯价，商品按单价 {{ money(maxPrice) }} 元成交；首档数量即起订量</div>

    <p v-if="rows.length" class="tier-hint">
      首档（{{ firstRowQty }} 件）将作为起订量：买家一次购买不足该数量时无法下单；
      单价需随数量递减或持平，且不高于商品单价
    </p>
    <p v-if="rows.length >= MAX_ROWS" class="tier-hint">最多 {{ MAX_ROWS }} 档</p>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { PlusOutlined } from '@ant-design/icons-vue'

/**
 * 阶梯批发价编辑区（P1-17）：发布/编辑表单共用的动态行编辑器。
 * 数据契约：tierPriceJson = [{minQty, price}, ...]，minQty 严格递增、price 递减或相等、≥1 条；
 * 行内即时校验（数量正整数、单价两位小数且 ≤ 商品单价），排序归一与终检在后端 encodeContract。
 */
const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  basePrice: { type: [Number, String], default: null }
})
const emit = defineEmits(['update:modelValue'])

const MAX_ROWS = 10

const rows = computed({
  get: () => (Array.isArray(props.modelValue) ? props.modelValue : []),
  set: (v) => emit('update:modelValue', v)
})
const maxPrice = computed(() => Number(props.basePrice) || null)
const firstRowQty = computed(() => {
  const qts = rows.value.map(r => Number(r.minQty)).filter(q => q > 0)
  return qts.length ? Math.min(...qts) : 0
})

function addRow() {
  rows.value = [...rows.value, { minQty: null, price: null }]
}
function sortRows() {
  const filled = rows.value.filter(r => r.minQty != null)
  if (filled.length > 1) {
    rows.value = [...filled].sort((a, b) => a.minQty - b.minQty)
  }
}
function money(v) {
  return Number(v || 0).toFixed(2)
}
</script>

<style scoped>
.tier-editor { border: 1px dashed var(--mc-border-soft, #e8e6e1); border-radius: 10px; padding: 12px 14px; background: var(--mc-bg-soft, #fafaf8); }
.tier-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.tier-head b { display: block; font-size: 13px; color: var(--mc-ink, #1d1d1f); }
.tier-head span { font-size: 12px; color: var(--mc-muted2, #86868b); }
.tier-grid { margin-top: 10px; }
.tier-cols { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) 52px; gap: 8px; margin-bottom: 8px; align-items: center; }
.tier-title-row { margin-bottom: 2px; font-size: 11.5px; color: var(--mc-muted2, #86868b); }
.tier-op-col { justify-self: end; }
.tier-empty { margin-top: 8px; font-size: 12px; color: var(--mc-muted2, #86868b); }
.tier-hint { margin: 8px 0 0; font-size: 12px; color: var(--mc-muted2, #86868b); }
@media (max-width: 700px) {
  .tier-head { flex-direction: column; align-items: stretch; }
}
</style>
