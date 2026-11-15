<template>
  <div class="rfq-page">
    <div class="eyebrow">RFQ CENTER</div>
    <h2>{{ txt.title }}</h2>
    <p class="sub">{{ txt.sub }}</p>

    <a-alert type="info" show-icon class="rfq-notice" :message="txt.noticeTitle" :description="txt.noticeBody" />

    <div class="rfq-toolbar">
      <a-button type="primary" @click="openPublish">{{ txt.publish }}</a-button>
      <a-button @click="load" :loading="loading">{{ txt.refresh }}</a-button>
    </div>

    <a-table :data-source="rows" :columns="cols" row-key="rfqNo" :loading="loading" :pagination="{ pageSize: 10 }"
             :scroll="{ x: 960 }" :locale="{ emptyText: txt.empty }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'qty'">{{ record.quantity }} {{ record.unit || '' }}</template>
        <template v-else-if="column.key === 'deadline'">{{ fmtDate(record.quoteDeadline) }}</template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="statusColor(record.status)">{{ statusText(record.status) }}</a-tag>
        </template>
        <template v-else-if="column.key === 'op'">
          <a-space wrap>
            <a @click="openQuotes(record)">{{ txt.viewQuotes }}</a>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-empty v-if="!loading && rows.length === 0" :description="txt.empty">
      <a-button type="primary" @click="openPublish">{{ txt.publishFirst }}</a-button>
    </a-empty>

    <!-- 发布询价 -->
    <a-modal v-model:open="publishOpen" :title="txt.publishTitle" :ok-text="txt.submit" :cancel-text="txt.cancel"
             :confirm-loading="submitting" @ok="submitPublish" destroy-on-close>
      <a-form layout="vertical">
        <a-form-item :label="txt.fTitle" required>
          <a-input v-model:value="form.title" :maxlength="120" :placeholder="txt.fTitlePh" />
        </a-form-item>
        <a-form-item :label="txt.fDesc">
          <a-textarea v-model:value="form.description" :rows="3" :maxlength="1000" :placeholder="txt.fDescPh" />
        </a-form-item>
        <a-row :gutter="12">
          <a-col :span="12">
            <a-form-item :label="txt.fQty" required>
              <a-input-number v-model:value="form.quantity" :min="0.01" :precision="2" style="width:100%" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item :label="txt.fUnit">
              <a-select v-model:value="form.unit" :options="unitOptions" style="width:100%" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item :label="txt.fDeadline" required>
          <a-date-picker v-model:value="form.quoteDeadline" show-time value-format="YYYY-MM-DD HH:mm:ss"
                         style="width:100%" :placeholder="txt.fDeadlinePh" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 收到的报价 -->
    <a-drawer v-model:open="quotesOpen" :title="txt.quotesTitle" width="720">
      <a-descriptions :column="2" size="small" bordered class="rfq-meta">
        <a-descriptions-item :label="txt.cRfqNo">{{ current?.rfqNo }}</a-descriptions-item>
        <a-descriptions-item :label="txt.cStatus">
          <a-tag :color="statusColor(current?.status)">{{ statusText(current?.status) }}</a-tag>
        </a-descriptions-item>
        <a-descriptions-item :label="txt.cTitle" :span="2">{{ current?.title }}</a-descriptions-item>
        <a-descriptions-item :label="txt.cQty">{{ current?.quantity }} {{ current?.unit || '' }}</a-descriptions-item>
        <a-descriptions-item :label="txt.cDeadline">{{ fmtDate(current?.quoteDeadline) }}</a-descriptions-item>
      </a-descriptions>
      <a-spin :spinning="quotesLoading">
        <a-empty v-if="!quotesLoading && quotes.length === 0" :description="txt.noQuotes" />
        <div v-for="q in quotes" :key="q.quoteId" class="quote-card">
          <div class="quote-head">
            <span class="quote-amt">¥{{ money(q.unitPrice) }} <em>/ {{ current?.unit || txt.unitDefault }}</em></span>
            <a-tag :color="q.status === 'SUBMITTED' ? 'blue' : (q.status === 'ACCEPTED' ? 'green' : 'default')">
              {{ quoteStatusText(q.status) }}
            </a-tag>
          </div>
          <div class="quote-line">{{ txt.qTotal }}：<b>¥{{ money(q.totalAmount) }}</b> × {{ q.quantity }}</div>
          <div class="quote-line" v-if="q.validUntil">{{ txt.qValid }}：{{ fmtDate(q.validUntil) }}</div>
          <div class="quote-line" v-if="q.remark">{{ txt.qRemark }}：{{ q.remark }}</div>
          <div class="quote-actions" v-if="q.status === 'SUBMITTED' && canAccept">
            <a-button type="primary" size="small" @click="openAccept(q)">{{ txt.accept }}</a-button>
          </div>
        </div>
      </a-spin>
    </a-drawer>

    <!-- 接受报价 -->
    <a-modal v-model:open="acceptOpen" :title="txt.acceptTitle" :ok-text="txt.confirmAccept" :cancel-text="txt.cancel"
             :confirm-loading="accepting" @ok="submitAccept" destroy-on-close>
      <a-alert type="warning" show-icon :message="txt.acceptWarn" class="accept-warn" />
      <div class="accept-total">{{ txt.dealTotal }}：<b>¥{{ money(acceptQuote?.totalAmount) }}</b></div>
      <a-form layout="vertical">
        <a-form-item :label="txt.receiver" required>
          <a-input v-model:value="acceptForm.receiver" :maxlength="40" :placeholder="txt.receiverPh" />
        </a-form-item>
        <a-form-item :label="txt.phone" required>
          <a-input v-model:value="acceptForm.phone" :maxlength="20" :placeholder="txt.phonePh" />
        </a-form-item>
        <a-form-item :label="txt.address" required>
          <a-input v-model:value="acceptForm.address" :maxlength="255" :placeholder="txt.addressPh" />
        </a-form-item>
        <a-form-item :label="txt.payType">
          <a-select v-model:value="acceptForm.payType" :options="payOptions" style="width:100%" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { rfqApi } from '../api'
import { locale } from '../i18n'

const router = useRouter()
const isEn = computed(() => locale.value === 'en-US')

const txt = computed(() => isEn.value ? {
  title: 'Request for Quotation',
  sub: 'Publish what you need, compare merchant quotes, and accept the best offer to create an order at the quoted price.',
  noticeTitle: 'Platform boundary',
  noticeBody: 'The platform does not bargain on your behalf, does not guarantee a deal, and does not collect transaction funds. Quotes are binding offers from merchants; accepting one creates an order at the quoted price with no further discounts.',
  publish: 'Publish RFQ', refresh: 'Refresh', empty: 'No RFQs yet',
  colRfqNo: 'RFQ No.', colTitle: 'Title', colQty: 'Quantity', colDeadline: 'Quote deadline',
  colStatus: 'Status', colOp: 'Actions', viewQuotes: 'View quotes', publishFirst: 'Publish your first RFQ',
  publishTitle: 'Publish an RFQ',
  fTitle: 'Title', fTitlePh: 'e.g. 20 tons of HRB400 rebar', fDesc: 'Description',
  fDescPh: 'Specifications, delivery location, acceptance requirements…',
  fQty: 'Quantity', fUnit: 'Unit', fDeadline: 'Quote deadline', fDeadlinePh: 'Select date and time',
  submit: 'Submit', cancel: 'Cancel', published: 'RFQ published',
  quotesTitle: 'Quotes received', cRfqNo: 'RFQ No.', cStatus: 'Status', cTitle: 'Title',
  cQty: 'Quantity', cDeadline: 'Quote deadline', noQuotes: 'No quotes yet',
  unitDefault: 'unit', qTotal: 'Total', qValid: 'Valid until', qRemark: 'Remark',
  accept: 'Accept quote', acceptTitle: 'Accept this quote',
  acceptWarn: 'Accepting creates an order at the quoted total. Coupons, points and member discounts are not applicable to quote orders.',
  dealTotal: 'Deal total', receiver: 'Receiver', receiverPh: 'Contact name',
  phone: 'Phone', phonePh: 'Contact phone', address: 'Address', addressPh: 'Delivery address',
  payType: 'Payment', payCorporate: 'Corporate transfer', payBalance: 'Wallet balance', payTerm: 'Credit terms',
  confirmAccept: 'Accept & create order', accepted: 'Order created from quote',
  pickerReload: 'Please refresh', fillRequired: 'Please fill in title, quantity and deadline',
  deadlineFuture: 'Quote deadline must be in the future', fillAccept: 'Please fill in receiver, phone and address',
  statusMap: { PUBLISHED: 'Published', QUOTED: 'Quoted', ACCEPTED: 'Accepted', ORDERED: 'Ordered', EXPIRED: 'Expired', CANCELLED: 'Cancelled' },
  quoteStatusMap: { SUBMITTED: 'Submitted', ACCEPTED: 'Accepted', REJECTED: 'Rejected', WITHDRAWN: 'Withdrawn', EXPIRED: 'Expired' }
} : {
  title: '询价中心',
  sub: '发布采购需求，对比商户报价，按报价总额接受并生成订单。',
  noticeTitle: '平台边界',
  noticeBody: '平台不参与议价、不承诺成交、不代收交易款。商户报价为要约，接受后按报价总额成交，不再叠加优惠券、积分或会员折扣。',
  publish: '发布询价', refresh: '刷新', empty: '暂无询价单',
  colRfqNo: '询价单号', colTitle: '标题', colQty: '数量', colDeadline: '报价截止',
  colStatus: '状态', colOp: '操作', viewQuotes: '查看报价', publishFirst: '发布第一条询价',
  publishTitle: '发布询价',
  fTitle: '标题', fTitlePh: '例如：采购 20 吨 HRB400 螺纹钢', fDesc: '需求描述',
  fDescPh: '规格型号、送货地址、验收要求等',
  fQty: '数量', fUnit: '单位', fDeadline: '报价截止时间', fDeadlinePh: '选择日期时间',
  submit: '提交', cancel: '取消', published: '询价已发布',
  quotesTitle: '收到的报价', cRfqNo: '询价单号', cStatus: '状态', cTitle: '标题',
  cQty: '数量', cDeadline: '报价截止', noQuotes: '暂无报价',
  unitDefault: '单位', qTotal: '总额', qValid: '有效期至', qRemark: '备注',
  accept: '接受报价', acceptTitle: '接受该报价',
  acceptWarn: '接受后将按报价总额生成订单；报价订单不支持优惠券、积分与会员折扣。',
  dealTotal: '成交总额', receiver: '收货人', receiverPh: '联系人姓名',
  phone: '联系电话', phonePh: '联系电话', address: '收货地址', addressPh: '详细收货地址',
  payType: '支付方式', payCorporate: '对公转账', payBalance: '余额支付', payTerm: '企业账期',
  confirmAccept: '接受并生成订单', accepted: '已按报价生成订单',
  pickerReload: '请刷新重试', fillRequired: '请填写标题、数量与报价截止时间',
  deadlineFuture: '报价截止时间必须晚于当前时间', fillAccept: '请填写收货人、电话与地址',
  statusMap: { PUBLISHED: '待报价', QUOTED: '已报价', ACCEPTED: '已接受', ORDERED: '已成交', EXPIRED: '已过期', CANCELLED: '已取消' },
  quoteStatusMap: { SUBMITTED: '待接受', ACCEPTED: '已接受', REJECTED: '未中选', WITHDRAWN: '已撤回', EXPIRED: '已失效' }
})

const rows = ref([])
const loading = ref(false)
const publishOpen = ref(false)
const submitting = ref(false)
const form = reactive({ title: '', description: '', quantity: 1, unit: '吨', quoteDeadline: null })
const unitOptions = computed(() => {
  const zh = ['吨', '件', '米', '袋', '桶', '台', '套']
  const en = ['ton', 'pc', 'm', 'bag', 'drum', 'set', 'set']
  return zh.map((u, i) => ({ value: u, label: isEn.value ? en[i] : u }))
})

const cols = computed(() => [
  { title: txt.value.colRfqNo, dataIndex: 'rfqNo', key: 'rfqNo', width: 200 },
  { title: txt.value.colTitle, dataIndex: 'title', key: 'title', ellipsis: true },
  { title: txt.value.colQty, key: 'qty', width: 120 },
  { title: txt.value.colDeadline, key: 'deadline', width: 170 },
  { title: txt.value.colStatus, key: 'status', width: 110 },
  { title: txt.value.colOp, key: 'op', width: 120 }
])

const quotesOpen = ref(false)
const quotesLoading = ref(false)
const current = ref(null)
const quotes = ref([])
const canAccept = computed(() => ['QUOTED', 'PUBLISHED'].includes(current.value?.status))

const acceptOpen = ref(false)
const accepting = ref(false)
const acceptQuote = ref(null)
const acceptForm = reactive({ receiver: '', phone: '', address: '', payType: 'corporate' })
const payOptions = computed(() => [
  { value: 'corporate', label: txt.value.payCorporate },
  { value: 'balance', label: txt.value.payBalance },
  { value: 'term', label: txt.value.payTerm }
])

function statusText(s) { return txt.value.statusMap[s] || s || '—' }
function quoteStatusText(s) { return txt.value.quoteStatusMap[s] || s || '—' }
function statusColor(s) {
  if (s === 'ORDERED' || s === 'ACCEPTED') return 'green'
  if (s === 'EXPIRED' || s === 'CANCELLED') return 'default'
  if (s === 'QUOTED') return 'blue'
  return 'orange'
}
function money(v) { const n = Number(v); return Number.isFinite(n) ? n.toFixed(2) : '—' }
function fmtDate(v) {
  if (!v) return '—'
  const d = new Date(String(v).replace(' ', 'T'))
  return Number.isNaN(d.getTime()) ? String(v)
    : d.toLocaleString(isEn.value ? 'en-US' : 'zh-CN', { hour12: false })
}
function toIso(v) { return v ? String(v).replace(' ', 'T') : null }

async function load() {
  loading.value = true
  try { rows.value = (await rfqApi.my()) || [] } catch (e) { /* 拦截器已提示 */ } finally { loading.value = false }
}

function openPublish() {
  form.title = ''
  form.description = ''
  form.quantity = 1
  form.unit = '吨'
  form.quoteDeadline = null
  publishOpen.value = true
}

async function submitPublish() {
  if (!form.title || !form.quantity || !form.quoteDeadline) { message.warning(txt.value.fillRequired); return }
  const deadline = new Date(String(form.quoteDeadline).replace(' ', 'T'))
  if (!(deadline.getTime() > Date.now())) { message.warning(txt.value.deadlineFuture); return }
  submitting.value = true
  try {
    await rfqApi.create({
      title: form.title,
      description: form.description,
      quantity: form.quantity,
      unit: form.unit,
      quoteDeadline: toIso(form.quoteDeadline)
    })
    message.success(txt.value.published)
    publishOpen.value = false
    load()
  } catch (e) { /* 拦截器已提示 */ } finally { submitting.value = false }
}

async function openQuotes(record) {
  current.value = record
  quotesOpen.value = true
  quotesLoading.value = true
  try { quotes.value = (await rfqApi.quotes(record.rfqNo)) || [] } catch (e) { quotes.value = [] } finally { quotesLoading.value = false }
}

function openAccept(q) {
  acceptQuote.value = q
  acceptForm.receiver = ''
  acceptForm.phone = ''
  acceptForm.address = ''
  acceptForm.payType = 'corporate'
  acceptOpen.value = true
}

async function submitAccept() {
  if (!acceptForm.receiver || !acceptForm.phone || !acceptForm.address) {
    message.warning(txt.value.fillAccept)
    return
  }
  accepting.value = true
  try {
    const order = await rfqApi.accept(acceptQuote.value.quoteId, {
      receiver: acceptForm.receiver,
      phone: acceptForm.phone,
      address: acceptForm.address,
      payType: acceptForm.payType,
      expectedTotal: acceptQuote.value.totalAmount
    })
    message.success(txt.value.accepted)
    acceptOpen.value = false
    quotesOpen.value = false
    load()
    if (order?.orderNo) router.push(`/orders/${order.orderNo}`)
  } catch (e) { /* 拦截器已提示 */ } finally { accepting.value = false }
}

onMounted(load)
</script>

<style scoped>
.rfq-page { max-width: 1180px; margin: 24px auto 64px; padding: 0 16px; }
.eyebrow { letter-spacing: .18em; color: #8a8f99; font-size: 12px; }
.rfq-page h2 { margin: 6px 0 4px; }
.sub { color: #6b7280; margin-bottom: 16px; }
.rfq-notice { margin-bottom: 16px; }
.rfq-toolbar { display: flex; gap: 12px; margin-bottom: 16px; }
.rfq-meta { margin-bottom: 16px; }
.quote-card { border: 1px solid #e5e7eb; border-radius: 10px; padding: 14px 16px; margin-bottom: 12px; background: #fff; }
.quote-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.quote-amt { font-size: 18px; font-weight: 600; }
.quote-amt em { font-style: normal; font-weight: 400; color: #8a8f99; font-size: 12px; }
.quote-line { color: #4b5563; font-size: 13px; line-height: 1.9; }
.quote-actions { margin-top: 8px; text-align: right; }
.accept-warn { margin-bottom: 12px; }
.accept-total { font-size: 16px; margin-bottom: 12px; }
</style>