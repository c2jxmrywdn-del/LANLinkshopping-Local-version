<template>
  <div class="inv-box" data-testid="invoice-box">
    <div class="inv-head">
      <a-checkbox v-model:checked="inv.needed" data-testid="invoice-needed">
        <span class="inv-head-label"><FileText :size="15" class="inv-ico" aria-hidden="true" />需要开发票</span>
      </a-checkbox>
      <span class="inv-sub">增值税发票，随单登记开票需求</span>
    </div>
    <template v-if="inv.needed">
      <div class="inv-grid">
        <div class="inv-field">
          <label class="inv-label">抬头类型</label>
          <a-radio-group v-model:value="inv.titleType" data-testid="invoice-title-type">
            <a-radio value="company">单位</a-radio>
            <a-radio value="personal">个人</a-radio>
          </a-radio-group>
        </div>
        <div class="inv-field">
          <label class="inv-label">发票内容</label>
          <a-radio-group v-model:value="inv.contentType" data-testid="invoice-content-type">
            <a-radio value="category">商品类别</a-radio>
            <a-radio value="detail">开具明细</a-radio>
          </a-radio-group>
        </div>
        <div class="inv-field inv-span2">
          <label class="inv-label">发票抬头</label>
          <a-input v-model:value="inv.title" data-testid="invoice-title"
                   :maxlength="INVOICE_TITLE_MAX" placeholder="个人姓名或单位全称" />
        </div>
        <div v-if="inv.titleType === 'company'" class="inv-field inv-span2">
          <label class="inv-label">纳税人识别号</label>
          <a-input v-model:value="inv.taxNo" data-testid="invoice-tax-no"
                   :maxlength="INVOICE_TAX_NO_MAX" placeholder="15-20 位大写字母或数字（统一社会信用代码）" />
        </div>
        <div v-if="inv.contentType === 'category'" class="inv-field inv-span2">
          <label class="inv-label">商品类别</label>
          <a-input v-model:value="inv.category" data-testid="invoice-category"
                   :maxlength="INVOICE_CATEGORY_MAX" placeholder="如：工业设备、办公用品" />
        </div>
        <div v-else class="inv-field inv-span2">
          <label class="inv-label">开具明细</label>
          <a-textarea v-model:value="inv.detail" data-testid="invoice-detail"
                      :rows="2" :maxlength="INVOICE_DETAIL_MAX" placeholder="按订单商品开具的明细说明" />
        </div>
      </div>
      <div class="inv-hint">发票需求随订单快照保存，开票信息可在订单详情中查看。</div>
    </template>
  </div>
</template>

<script setup>
import { reactive, watch } from 'vue'
import { FileText } from 'lucide-vue-next'
import {
  INVOICE_TYPE_PERSONAL, INVOICE_TYPE_COMPANY,
  INVOICE_CONTENT_CATEGORY, INVOICE_CONTENT_DETAIL,
  INVOICE_TITLE_MAX, INVOICE_TAX_NO_MAX, INVOICE_CATEGORY_MAX, INVOICE_DETAIL_MAX
} from '../../utils/validationRules'

/**
 * 发票需求表单（P1-5）：勾选后展开抬头类型/抬头/税号/内容字段。
 * 校验规则与后端 OrderService.applyInvoiceSnapshot 同契约（validationRules.js），
 * 提交校验由父组件调 validateInvoiceRequest，本组件只负责形状与联动。
 */
const props = defineProps({
  modelValue: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue'])

const DEFAULTS = () => ({
  needed: false,
  titleType: INVOICE_TYPE_COMPANY,
  title: '',
  taxNo: '',
  contentType: INVOICE_CONTENT_CATEGORY,
  category: '',
  detail: ''
})

const inv = reactive({ ...DEFAULTS(), ...(props.modelValue || {}) })

watch(inv, () => emit('update:modelValue', { ...inv }), { deep: true })

// 外部重置（下单成功后清空）时同步回默认形状
watch(() => props.modelValue, (v) => {
  if (v === null) Object.assign(inv, DEFAULTS())
}, { deep: true })

// 单位抬头默认必填税号；个人抬头清掉税号避免把无关值带进快照
watch(() => inv.titleType, (t) => { if (t !== INVOICE_TYPE_COMPANY) inv.taxNo = '' })
// 内容类型切换时清掉另一侧内容，保证快照单侧有效
watch(() => inv.contentType, (t) => {
  if (t === INVOICE_CONTENT_CATEGORY) inv.detail = ''
  else inv.category = ''
})
</script>

<style scoped>
.inv-box { background: rgba(30, 110, 184, .04); border: 1px dashed rgba(30, 110, 184, .25);
           border-radius: 10px; padding: 10px 12px; margin-bottom: 12px; }
.inv-head { display: flex; align-items: center; justify-content: space-between; gap: 8px 12px; flex-wrap: wrap; }
/* 图标与文字同一基线：项目没有 Tailwind，原来的 inline-block/mr-1/align-middle 全是无效类，
   导致图标和文字贴在一起。这里用 flex + gap 显式控制间距，不依赖任何工具类。 */
.inv-head-label { display: inline-flex; align-items: center; gap: 6px;
                  font-weight: 600; font-size: 13px; line-height: 1.4;
                  color: var(--ll-ink, #0f172a); white-space: nowrap; }
.inv-ico { flex-shrink: 0; color: var(--ll-muted, #64748b); }
.inv-sub { font-size: 12px; line-height: 1.5; color: var(--ll-muted, #64748b); }
.inv-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 14px; margin-top: 10px; }
.inv-field { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.inv-span2 { grid-column: span 2; }
.inv-label { font-size: 12px; color: var(--ll-gray, #4b5563); }
.inv-hint { margin-top: 8px; font-size: 11.5px; color: var(--ll-muted, #64748b); line-height: 1.6; }
@media (max-width: 640px) {
  .inv-grid { grid-template-columns: 1fr; }
  .inv-span2 { grid-column: span 1; }
}
</style>
