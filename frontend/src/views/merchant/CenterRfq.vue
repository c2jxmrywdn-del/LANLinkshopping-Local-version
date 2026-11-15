<template>
  <div class="merchant-rfq">
    <div class="head">
      <div>
        <h3>{{ txt.title }}</h3>
        <p class="sub">{{ txt.sub }}</p>
      </div>
      <a-button @click="load" :loading="loading">{{ txt.refresh }}</a-button>
    </div>

    <a-alert type="info" show-icon class="notice" :message="txt.noticeTitle" :description="txt.noticeBody" />

    <a-table :data-source="rows" :columns="cols" row-key="rfqNo" :loading="loading" :pagination="{ pageSize: 10 }"
             :scroll="{ x: 900 }" :locale="{ emptyText: txt.empty }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'qty'">{{ record.quantity }} {{ record.unit || '' }}</template>
        <template v-else-if="column.key === 'deadline'">{{ fmtDate(record.quoteDeadline) }}</template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="record.status === 'QUOTED' ? 'blue' : 'orange'">
            {{ record.status === 'QUOTED' ? txt.stQuoted : txt.stPublished }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'op'">
          <a @click="openQuote(record)">{{ txt.quote }}</a>
        </template>
      </template>
    </a-table>

    <a-empty v-if="!loading && rows.length === 0" :description="txt.empty" />

    <a-modal v-model:open="quoteOpen" :title="txt.quoteTitle" :ok-text="txt.submit" :cancel-text="txt.cancel"
             :confirm-loading="submitting" @ok="submitQuote" destroy-on-close width="640px">
      <div class="quote-meta">
        <div><span class="k">{{ txt.cRfqNo }}</span>{{ current?.rfqNo }}</div>
        <div><span class="k">{{ txt.cTitle }}</span>{{ current?.title }}</div>
        <div><span class="k">{{ txt.cQty }}</span>{{ current?.quantity }} {{ current?.unit || '' }}</div>
        <div><span class="k">{{ txt.cDeadline }}</span>{{ fmtDate(current?.quoteDeadline) }}</div>
      </div>
      <a-alert v-if="existing" type="success" show-icon :message="txt.existingHint" class="existing" />
      <a-form layout="vertical">
        <a-form-item :label="txt.fProduct" required>
          <a-select v-model:value="form.prodId" show-search option-filter-prop="label" :options="productOptions"
                    :placeholder="txt.fProductPh" style="width:100%" />
        </a-form-item>
        <a-row :gutter="12">
          <a-col :span="12">
            <a-form-item :label="txt.fPrice" required>
              <a-input-number v-model:value="form.unitPrice" :min="0.01" :precision="2" style="width:100%" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item :label="txt.fTotal">
              <div class="total">¥{{ totalPreview }}</div>
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item :label="txt.fValid">
          <a-date-picker v-model:value="form.validUntil" show-time value-format="YYYY-MM-DD HH:mm:ss"
                         style="width:100%" :placeholder="txt.fValidPh" />
        </a-form-item>
        <a-form-item :label="txt.fRemark">
          <a-textarea v-model:value="form.remark" :rows="3" :maxlength="500" :placeholder="txt.fRemarkPh" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { productApi, rfqApi } from '../../api'
import { locale } from '../../i18n'

const isEn = computed(() => locale.value === 'en-US')

const txt = computed(() => isEn.value ? {
  title: 'RFQ Quotes',
  sub: 'Browse open purchase requests and quote with your own on-sale products.',
  noticeTitle: 'Quote rules',
  noticeBody: 'Each quote must anchor an on-sale product of your own shop. The total is computed as unit price × requested quantity; competitors\' quotes are never visible to you.',
  refresh: 'Refresh', empty: 'No open RFQs',
  colRfqNo: 'RFQ No.', colTitle: 'Title', colQty: 'Quantity', colDeadline: 'Quote deadline',
  colStatus: 'Status', colOp: 'Actions', quote: 'Quote',
  stPublished: 'Open', stQuoted: 'Has quotes',
  quoteTitle: 'Submit quote', cRfqNo: 'RFQ No.', cTitle: 'Title', cQty: 'Quantity', cDeadline: 'Quote deadline',
  existingHint: 'You already quoted this RFQ; submitting again updates your quote.',
  fProduct: 'Product', fProductPh: 'Select one of your on-sale products',
  fPrice: 'Unit price', fTotal: 'Total (unit price × quantity)', fValid: 'Valid until', fValidPh: 'Optional',
  fRemark: 'Remark', fRemarkPh: 'Tax, freight, lead time…',
  submit: 'Submit', cancel: 'Cancel', submitted: 'Quote submitted',
  fillRequired: 'Please select a product and enter the unit price', unitShort: 'unit',
  loadProductsFail: 'Failed to load products'
} : {
  title: '询价报价',
  sub: '查看开放中的采购询价，用本店在售商品报价。',
  noticeTitle: '报价规则',
  noticeBody: '报价必须锚定本店在售商品；总额按「单价 × 询价数量」由系统计算；竞对报价对你不可见。',
  refresh: '刷新', empty: '暂无开放询价',
  colRfqNo: '询价单号', colTitle: '标题', colQty: '数量', colDeadline: '报价截止',
  colStatus: '状态', colOp: '操作', quote: '报价',
  stPublished: '待报价', stQuoted: '已有报价',
  quoteTitle: '提交报价', cRfqNo: '询价单号', cTitle: '标题', cQty: '数量', cDeadline: '报价截止',
  existingHint: '你已对该询价报过价，再次提交将更新原报价。',
  fProduct: '报价商品', fProductPh: '选择本店在售商品',
  fPrice: '报价单价', fTotal: '报价总额（单价 × 数量）', fValid: '报价有效期', fValidPh: '可不填',
  fRemark: '报价备注', fRemarkPh: '含税、含运费、交期等',
  submit: '提交', cancel: '取消', submitted: '报价已提交',
  fillRequired: '请选择商品并填写报价单价', unitShort: '单位',
  loadProductsFail: '商品加载失败'
})

const rows = ref([])
const loading = ref(false)
const quoteOpen = ref(false)
const submitting = ref(false)
const current = ref(null)
const existing = ref(null)
const products = ref([])
const form = reactive({ prodId: null, unitPrice: null, remark: '', validUntil: null })

const cols = computed(() => [
  { title: txt.value.colRfqNo, dataIndex: 'rfqNo', key: 'rfqNo', width: 200 },
  { title: txt.value.colTitle, dataIndex: 'title', key: 'title', ellipsis: true },
  { title: txt.value.colQty, key: 'qty', width: 120 },
  { title: txt.value.colDeadline, key: 'deadline', width: 170 },
  { title: txt.value.colStatus, key: 'status', width: 110 },
  { title: txt.value.colOp, key: 'op', width: 100 }
])

const productOptions = computed(() => products.value.map(p => ({
  value: p.prodId,
  label: `${p.title} (¥${Number(p.price).toFixed(2)} / ${p.spec || txt.value.unitShort})`
})))

const totalPreview = computed(() => {
  const n = Number(form.unitPrice) * Number(current.value?.quantity || 0)
  return Number.isFinite(n) && n > 0 ? n.toFixed(2) : '—'
})

function fmtDate(v) {
  if (!v) return '—'
  const d = new Date(String(v).replace(' ', 'T'))
  return Number.isNaN(d.getTime()) ? String(v)
    : d.toLocaleString(isEn.value ? 'en-US' : 'zh-CN', { hour12: false })
}
function toIso(v) { return v ? String(v).replace(' ', 'T') : null }

async function load() {
  loading.value = true
  try { rows.value = (await rfqApi.merchantOpen()) || [] } catch (e) { /* 拦截器已提示 */ } finally { loading.value = false }
}

async function loadProducts() {
  try {
    const page = await productApi.myList({ current: 1, size: 200 })
    products.value = (page.records || []).filter(p => p.status === 1)
  } catch (e) {
    products.value = []
    message.warning(txt.value.loadProductsFail)
  }
}

async function openQuote(record) {
  current.value = record
  existing.value = null
  form.prodId = null
  form.unitPrice = null
  form.remark = ''
  form.validUntil = null
  quoteOpen.value = true
  if (products.value.length === 0) loadProducts()
  try {
    const mine = await rfqApi.myQuote(record.rfqNo)
    if (mine) {
      existing.value = mine
      form.prodId = mine.prodId
      form.unitPrice = Number(mine.unitPrice)
      form.remark = mine.remark || ''
      form.validUntil = mine.validUntil ? String(mine.validUntil).replace('T', ' ').slice(0, 19) : null
    }
  } catch (e) { /* 404 = 尚未报价，保持空表单 */ }
}

async function submitQuote() {
  if (!form.prodId || !form.unitPrice) { message.warning(txt.value.fillRequired); return }
  submitting.value = true
  try {
    await rfqApi.submitQuote(current.value.rfqNo, {
      prodId: form.prodId,
      unitPrice: form.unitPrice,
      remark: form.remark,
      validUntil: toIso(form.validUntil)
    })
    message.success(txt.value.submitted)
    quoteOpen.value = false
    load()
  } catch (e) { /* 拦截器已提示 */ } finally { submitting.value = false }
}

onMounted(() => { load(); loadProducts() })
</script>

<style scoped>
.merchant-rfq { padding: 4px 0 24px; }
.head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px; }
.head h3 { margin: 0 0 4px; }
.sub { color: #6b7280; margin: 0; }
.notice { margin-bottom: 16px; }
.quote-meta { background: #f8fafc; border-radius: 8px; padding: 10px 14px; margin-bottom: 12px; color: #374151; font-size: 13px; line-height: 1.9; }
.quote-meta .k { color: #8a8f99; margin-right: 8px; }
.existing { margin-bottom: 12px; }
.total { font-size: 20px; font-weight: 600; line-height: 32px; }
</style>