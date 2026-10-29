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
      <a-radio-group v-model:value="tab" button-style="solid" class="seg" @change="onFilter">
        <a-radio-button value="all">全部</a-radio-button>
        <a-radio-button value="unpaid">待付款</a-radio-button>
        <a-radio-button value="paid">待发货</a-radio-button>
        <a-radio-button value="shipped">已发货</a-radio-button>
        <a-radio-button value="done">已完成</a-radio-button>
        <a-radio-button value="refund">退款相关</a-radio-button>
      </a-radio-group>
      <a-button type="text" @click="load"><AppleIcon name="refresh" :size="15" /> 刷新</a-button>
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
                              onChange: (p) => { current = p; load() },
                              showTotal: (t) => `共 ${t} 笔订单` }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'orderNo'">
            <div class="no-cell">
              <a class="mono" @click="$router.push(`/merchant/center/orders/${record.order.orderNo}`)">{{ record.order.orderNo }}</a>
              <small>{{ shortTime(record.order.createTime) }}</small>
            </div>
          </template>
          <template v-else-if="column.key === 'goods'">
            <div class="goods-cell">
              <img v-if="record.myItems?.[0]?.coverUrl" :src="record.myItems[0].coverUrl" alt="" loading="lazy" />
              <span v-else class="goods-fallback">{{ (record.myItems?.[0]?.prodName || '商').slice(0, 1) }}</span>
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
              <small v-if="record.order.refundStatus === 'applied'" class="rf-warn">退款申请中</small>
              <small v-else-if="record.order.refundStatus === 'partial'" class="rf-warn">已部分退款</small>
              <small v-else-if="record.order.refundStatus === 'success'" class="rf-done">已退款</small>
            </div>
          </template>
          <template v-else-if="column.key === 'op'">
            <a-space :size="0">
              <a-button v-if="canShip(record.order)" type="link" size="small"
                        @click="openShip(record)">去发货</a-button>
              <a-button type="link" size="small"
                        @click="$router.push(`/merchant/center/orders/${record.order.orderNo}`)">详情</a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- L4：发货弹窗 -->
    <ShipModal v-model:open="shipOpen" :order-no="shipOrderNo" :sender="sender" @shipped="load" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { orderApi, merchantCenterApi } from '../../api'
import { ORDER_STATUS, PAY_STATUS, fmtMoney } from '../../utils/merchantStatus'
import ShipModal from '../../components/merchant/ShipModal.vue'
import AppleIcon from '../../components/ui/AppleIcon.vue'

const route = useRoute()
const rows = ref([])
const loading = ref(false)
const tab = ref('all')
const keyword = ref('')
const current = ref(1)
const pageSize = 10
const total = ref(0)

const shipOpen = ref(false)
const shipOrderNo = ref('')
const sender = ref({})

const cols = [
  { title: '订单号', key: 'orderNo', width: 178 },
  { title: '商品', key: 'goods', ellipsis: true },
  { title: '我的金额', key: 'myAmount', width: 104 },
  { title: '买家', key: 'buyer', width: 128 },
  { title: '支付', key: 'pay', width: 88 },
  { title: '状态', key: 'status', width: 104 },
  { title: '操作', key: 'op', width: 118 }
]

const money = fmtMoney
function shortTime(t) { return (t || '').replace('T', ' ').slice(0, 16) }
function maskPhone(p) {
  const s = String(p || '')
  return s.length === 11 ? s.slice(0, 3) + '****' + s.slice(7) : s
}
function canShip(o) {
  return Number(o?.payStatus) === 1 && o?.orderStatus === 0
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

function onSearch() { current.value = 1; load() }
function onFilter() { current.value = 1; load() }

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

// 工作台待办跳转（?tab=paid/refund）
watch(() => route.query.tab, (v) => {
  if (v && ['all', 'unpaid', 'paid', 'shipped', 'done', 'refund'].includes(v)) {
    tab.value = v
    current.value = 1
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
