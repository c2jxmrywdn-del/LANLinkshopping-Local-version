<template>
  <div class="orders-page">
    <section class="hero">
      <div class="hero-copy">
        <div class="eyebrow"><span class="dot"></span> 商户中心</div>
        <h1>订单管理</h1>
        <p>买家向您店铺下单的订单在此处理：待发货一键发货，退款申请随时跟踪。<br />
          您自己作为买家采购的订单，请前往 <a class="bridge-link" @click="$router.push('/orders')">我的订单</a> 查看。</p>
      </div>
      <div class="hero-actions">
        <a-input-search v-model:value="keyword" placeholder="搜索订单号 / 商品名" allow-clear
                        class="search" @search="onSearch" @pressEnter="onSearch" />
        <a-button size="large" class="pill" @click="$router.push('/merchant/center/logistics')">物流发货</a-button>
      </div>
    </section>

    <!-- 状态筛选 -->
    <div class="seg-row">
      <FluidAnimatedTabs v-model="tab" :items="merchantOrderTabs" variant="pill" @change="onFilter" />
      <a-button type="text" class="refresh-btn" @click="load">
        <RefreshCw :size="14" class="inline-block mr-1 align-middle" /> 刷新
      </a-button>
    </div>

    <a-card :bordered="false" class="panel">
      <!-- 空状态引导：讲清「销售订单 = 买家购买您的商品产生的订单」的因果 -->
      <a-alert v-if="!loading && rows.length === 0 && total === 0" type="info" show-icon class="empty-guide"
               :message="hasProducts ? '还没有买家向您下单' : '发布商品后，买家下单会出现在这里'"
               :description="hasProducts
                 ? '订单由买家购买您的商品产生：买家在商城下单并支付后，您可在这里发货、跟踪物流与处理退款。'
                 : '您的店铺还没有在售商品，商城订单暂时与您无关。先去发布商品并通过平台审核，买家购买后订单会自动出现在这里。'">
        <template #action>
          <a-button v-if="!hasProducts" size="small" type="primary"
                    @click="$router.push('/merchant/center/products')">去发布商品</a-button>
          <a-button v-else size="small" @click="$router.push('/mall')">预览店铺商品</a-button>
        </template>
      </a-alert>
      <a-table :data-source="rows" :columns="cols" row-key="orderNo" :loading="loading" class="mc-table"
               :scroll="{ x: 1150 }"
               :pagination="{ current, pageSize, total, showSizeChanger: false,
                              onChange: onPageChange,
                              showTotal: (t) => `共 ${t} 笔订单` }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'orderNo'">
            <div class="no-cell">
              <a class="mono" @click="$router.push({ path: `/merchant/center/orders/${record.order.orderNo}`, query: { fromTab: tab } })">{{ record.order.orderNo }}</a>
              <small>{{ shortTime(record.order.createTime) }}</small>
            </div>
          </template>
          <template v-else-if="column.key === 'goods'">
            <div class="goods-cell">
              <img v-if="record.myItems?.[0]?.coverUrl" :src="resolveStorageUrl(record.myItems[0].coverUrl)" alt="" loading="lazy" />
              <span v-else class="goods-fallback"><Package :size="16" class="text-slate-400" /></span>
              <div class="goods-meta">
                <b>{{ record.myItems?.[0]?.prodName || '—' }}</b>
                <small>
                  ×{{ record.myItems?.[0]?.quantity || 0 }}
                  <template v-if="(record.myItems?.length || 0) > 1"> 等 {{ record.myItems.length }} 种</template>
                </small>
              </div>
            </div>
          </template>
          <template v-else-if="column.key === 'myAmount'">
            <b class="amount">¥{{ money(record.myAmount) }}</b>
          </template>
          <template v-else-if="column.key === 'buyer'">
            <div class="buyer-cell">
              <span>{{ record.order.receiver || '—' }}</span>
              <small>{{ maskPhone(record.order.phone) }}</small>
            </div>
          </template>
          <template v-else-if="column.key === 'pay'">
            <a-tag :color="PAY_STATUS[record.order.payStatus]?.color">{{ PAY_STATUS[record.order.payStatus]?.text }}</a-tag>
          </template>
          <template v-else-if="column.key === 'status'">
            <div class="status-cell">
              <a-tag :color="ORDER_STATUS[record.order.orderStatus]?.color">{{ ORDER_STATUS[record.order.orderStatus]?.text }}</a-tag>
              <small v-if="record.order.refundStatus === 'applied'" class="rf-warn">待商户初审</small>
              <small v-else-if="record.order.refundStatus === 'merchant_approved'" class="rf-info">初审通过</small>
              <small v-else-if="record.order.refundStatus === 'shipping_back'" class="rf-warn">已寄回待质检</small>
              <small v-else-if="record.order.refundStatus === 'received'" class="rf-info">质检中</small>
              <small v-else-if="record.order.refundStatus === 'dispute'" class="rf-warn">质检异常</small>
              <small v-else-if="record.order.refundStatus === 'partial'" class="rf-warn">已部分退款</small>
              <small v-else-if="record.order.refundStatus === 'success'" class="rf-done">已退款</small>
            </div>
          </template>
          <template v-else-if="column.key === 'op'">
            <a-space :size="0">
              <a-button v-if="record.order.refundStatus === 'applied'" type="link" size="small"
                        danger @click="openRefundAudit(record)">审核退款</a-button>
              <a-button v-else-if="record.order.refundStatus === 'shipping_back' || record.order.refundStatus === 'received'"
                        type="link" size="small"
                        @click="$router.push({ path: `/merchant/center/orders/${record.order.orderNo}`, query: { action: 'inspect', fromTab: tab } })">验货质检</a-button>
              <a-button v-if="canShip(record.order)" type="link" size="small"
                        @click="openShip(record)">去发货</a-button>
              <a-button type="link" size="small"
                        @click="$router.push({ path: `/merchant/center/orders/${record.order.orderNo}`, query: { fromTab: tab } })">详情</a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- L4：发货弹窗 -->
    <ShipModal v-model:open="shipOpen" :order-no="shipOrderNo" :sender="sender" @shipped="load" />

    <!-- 商户退款初审弹窗 -->
    <a-modal v-model:open="auditOpen" title="商户退款初审" :footer="null">
      <div v-if="auditOrder" class="audit-modal-body">
        <a-descriptions :column="1" size="small" bordered style="margin-bottom: 16px;">
          <a-descriptions-item label="订单编号"><b class="mono">{{ auditOrder.order.orderNo }}</b></a-descriptions-item>
          <a-descriptions-item label="退款原因">{{ auditOrder.order.refundReason || '未填写' }}</a-descriptions-item>
          <a-descriptions-item label="订单金额">¥{{ fmtMoney(auditOrder.order.totalAmount) }}</a-descriptions-item>
          <a-descriptions-item label="本店小计">¥{{ fmtMoney(auditOrder.myAmount) }}</a-descriptions-item>
        </a-descriptions>
        <a-form layout="vertical">
          <a-form-item label="驳回原因（仅驳回时必填）">
            <a-textarea v-model:value="auditRejectReason" :rows="3" placeholder="若不同意退款，请填写具体驳回原因..." maxlength="200" show-count />
          </a-form-item>
          <div style="display: flex; justify-content: flex-end; gap: 10px; margin-top: 12px;">
            <a-button :loading="auditLoading" @click="auditOpen = false">取消</a-button>
            <a-button danger :loading="auditLoading" @click="confirmRejectRefund">驳回申请</a-button>
            <a-button type="primary" :loading="auditLoading" @click="confirmApproveRefund">同意初审</a-button>
          </div>
        </a-form>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { Package } from 'lucide-vue-next'
import { orderApi, merchantCenterApi } from '../../api'
import { ORDER_STATUS, PAY_STATUS, fmtMoney } from '../../utils/merchantStatus'
import { resolveStorageUrl } from '../../utils/storageUrl'
import ShipModal from '../../components/merchant/ShipModal.vue'
import AppleIcon from '../../components/ui/AppleIcon.vue'
import { RefreshCw } from 'lucide-vue-next'
import FluidAnimatedTabs from '../../components/ui/FluidAnimatedTabs.vue'

const route = useRoute()
const router = useRouter()
const rows = ref([])
const loading = ref(false)
const tab = ref('all')
const merchantOrderTabs = [
  { value: 'all', label: '全部' },
  { value: 'unpaid', label: '待付款' },
  { value: 'paid', label: '待发货' },
  { value: 'shipped', label: '已发货' },
  { value: 'done', label: '已完成' },
  { value: 'refund', label: '退款相关' }
]
const keyword = ref('')
const current = ref(Math.max(1, Number(route.query.page) || 1))
const pageSize = 10
const total = ref(0)

const shipOpen = ref(false)
const shipOrderNo = ref('')
const sender = ref({})

// 商户退款初审状态
const auditOpen = ref(false)
const auditOrder = ref(null)
const auditRejectReason = ref('')
const auditLoading = ref(false)

function openRefundAudit(record) {
  auditOrder.value = record
  auditRejectReason.value = ''
  auditOpen.value = true
}

async function confirmApproveRefund() {
  if (!auditOrder.value) return
  auditLoading.value = true
  try {
    await merchantCenterApi.approveRefund(auditOrder.value.order.orderNo)
    message.success('商户初审通过，已提交平台出款')
    auditOpen.value = false
    await load()
  } catch (e) { /* 拦截器已提示 */ }
  finally { auditLoading.value = false }
}

async function confirmRejectRefund() {
  if (!auditOrder.value) return
  if (!auditRejectReason.value.trim()) {
    message.warning('驳回退款申请时，必须填写驳回原因')
    return
  }
  auditLoading.value = true
  try {
    await merchantCenterApi.rejectRefund(auditOrder.value.order.orderNo, auditRejectReason.value.trim())
    message.success('已驳回该退款申请')
    auditOpen.value = false
    await load()
  } catch (e) { /* 拦截器已提示 */ }
  finally { auditLoading.value = false }
}

const cols = [
  { title: '订单号', key: 'orderNo', width: 178 },
  { title: '商品', key: 'goods', ellipsis: true },
  { title: '我的金额', key: 'myAmount', width: 104 },
  { title: '买家', key: 'buyer', width: 128 },
  { title: '支付', key: 'pay', width: 88 },
  { title: '状态', key: 'status', width: 104 },
  { title: '操作', key: 'op', width: 130, fixed: 'right' }
]

const money = fmtMoney
function shortTime(t) { return (t || '').replace('T', ' ').slice(0, 16) }
function maskPhone(p) {
  const s = String(p || '')
  return s.length === 11 ? s.slice(0, 3) + '****' + s.slice(7) : s
}
function canShip(o) {
  if (o?.refundStatus && !['none', 'rejected'].includes(o.refundStatus)) {
    return false
  }
  return Number(o?.payStatus) === 1 && (o?.orderStatus === 0 || o?.orderStatus === 4)
}

/** tab → 后端过滤参数 */
function tabParams() {
  switch (tab.value) {
    case 'unpaid': return { payStatus: 0 }
    case 'paid': return { payStatus: 1, orderStatus: 0 }
    case 'shipped': return { orderStatus: 1 }
    case 'done': return { orderStatus: 2 }
    case 'refund': return { refundFlag: 'any' }
    default: return {}
  }
}

function onSearch() { current.value = 1; dropPageParam(); load() }

/** tab 切换写入 URL（刷新/分享/后退不丢状态）；tab 真变化时由 route.query.tab watcher 统一触发加载，避免双请求 */
function onFilter() {
  current.value = 1
  const tabChanged = route.query.tab !== tab.value
  router.replace({ query: { ...route.query, tab: tab.value, page: undefined } })
  if (!tabChanged) load()
}

/** 分页写入 URL（第 1 页不携带 page 参数） */
function onPageChange(p) {
  current.value = p
  router.replace({ query: { ...route.query, tab: tab.value, ...(p > 1 ? { page: String(p) } : {}) } })
  load()
}

function dropPageParam() {
  if (route.query.page !== undefined) {
    router.replace({ query: { ...route.query, page: undefined } })
  }
}

async function load() {
  loading.value = true
  try {
    const page = await orderApi.merchantList({
      page: current.value, size: pageSize,
      keyword: keyword.value || undefined,
      ...tabParams()
    })
    rows.value = page.records || []
    total.value = Number(page.total || 0)
  } catch (e) { /* 拦截器已提示 */ }
  loading.value = false
}

function openShip(record) {
  shipOrderNo.value = record.order.orderNo
  shipOpen.value = true
}

// 寄件人预填 + 是否有在售商品（空状态引导文案分支用），静默加载失败不影响页面
const hasProducts = ref(false)
async function loadSender() {
  try {
    const s = await merchantCenterApi.summary()
    if (s?.store) sender.value = { name: s.store.entName || '', phone: s.store.contactPhone || '', address: s.store.businessAddress || '' }
    hasProducts.value = Number(s?.kpis?.totalProducts || 0) > 0
  } catch (e) { /* 忽略 */ }
}

// 工作台待办跳转（?tab=paid/refund）与路由变动联动；首次进入保留 URL 中的 page（分页深链）
watch(() => route.query.tab, (v, oldV) => {
  const target = v && ['all', 'unpaid', 'paid', 'shipped', 'done', 'refund'].includes(v) ? v : 'all'
  if (target !== tab.value) {
    tab.value = target
  }
  if (oldV !== undefined) {
    current.value = 1
    dropPageParam()
    load()
  }
}, { immediate: true })

onMounted(() => { load(); loadSender() })
</script>

<style scoped>
.orders-page { display: flex; flex-direction: column; gap: 14px; }
.hero { display: flex; justify-content: space-between; align-items: flex-end; gap: 14px; padding: 6px 2px 2px; }
.eyebrow { font-size: 12px; color: var(--mc-muted, #6e6e73); margin-bottom: 7px; letter-spacing: .04em; }
.dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: #9b2d20; margin-right: 7px; }
.hero h1 { margin: 0; font-size: 28px; font-weight: 750; letter-spacing: -.03em; color: var(--mc-ink, #1d1d1f); }
 .hero p { margin: 8px 0 0; color: var(--mc-muted, #6e6e73); font-size: 14px; line-height: 1.7; }
.bridge-link { color: var(--mc-accent, #9b2d20); cursor: pointer; text-decoration: none; font-weight: 600; }
.bridge-link:hover { text-decoration: underline; }
.hero-actions { display: flex; gap: 10px; flex-shrink: 0; align-items: center; }
.search { width: 250px; }
.pill { border-radius: 999px; height: 40px; padding-inline: 16px; }

.seg-row { display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.seg :deep(.ant-radio-button-wrapper) { border-radius: 0; }
.seg :deep(.ant-radio-button-wrapper:first-child) { border-radius: 10px 0 0 10px; }
.seg :deep(.ant-radio-button-wrapper:last-child) { border-radius: 0 10px 10px 0; }

 .panel { border-radius: 16px; border: 1px solid var(--mc-border, #e8e6e1); box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.empty-guide { border-radius: 12px; margin-bottom: 14px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12.5px; }
.no-cell a { display: block; }
.no-cell small { color: var(--mc-faint, #a1a1a6); font-size: 11px; }
.goods-cell { display: flex; align-items: center; gap: 9px; min-width: 0; }
.goods-cell img { width: 34px; height: 34px; border-radius: 8px; object-fit: cover; background: var(--mc-soft, #f5f5f7); flex-shrink: 0; }
.goods-fallback { width: 34px; height: 34px; border-radius: 8px; background: var(--mc-soft, #f5f5f7); color: var(--mc-faint, #b9c3d4); display: grid; place-items: center; font-size: 14px; flex-shrink: 0; }
.goods-meta b { display: block; font-size: 12.5px; font-weight: 500; color: var(--mc-ink, #1d1d1f); max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.goods-meta small { color: var(--mc-faint, #a1a1a6); font-size: 11px; }
.amount { font-size: 13px; color: var(--mc-ink, #1d1d1f); letter-spacing: -.01em; }
.buyer-cell span { display: block; font-size: 12.5px; color: var(--mc-ink, #1d1d1f); }
.buyer-cell small { color: var(--mc-faint, #a1a1a6); font-size: 11px; }
.status-cell small { display: block; margin-top: 3px; font-size: 11px; }
.rf-warn { color: #c2410c; }
.rf-info { color: #0284c7; }
.rf-done { color: #8e44ad; }

@media (max-width: 900px) {
  .hero { flex-direction: column; align-items: flex-start; }
  .hero h1 { font-size: 22px; }
  .hero-actions { width: 100%; }
  .search { flex: 1; width: auto; }
}
@media (max-width: 700px) {
  .seg-row { flex-direction: column; align-items: stretch; }
  .seg { display: flex; overflow-x: auto; scrollbar-width: none; }
  .seg::-webkit-scrollbar { display: none; }
  .seg :deep(.ant-radio-button-wrapper) { flex: 1; text-align: center; white-space: nowrap; }
  .goods-meta b { max-width: 120px; }
}
</style>
