<template>
  <div class="od-page">
    <!-- 面包屑 -->
    <div class="crumb-row">
      <a-breadcrumb class="crumb">
        <a-breadcrumb-item><a @click="$router.push('/merchant/center')">商户中心</a></a-breadcrumb-item>
        <a-breadcrumb-item><a @click="$router.push('/merchant/center/orders')">订单管理</a></a-breadcrumb-item>
        <a-breadcrumb-item>订单详情</a-breadcrumb-item>
      </a-breadcrumb>
      <a-button @click="$router.push('/merchant/center/orders')">返回列表</a-button>
    </div>

    <a-spin :spinning="loading">
      <template v-if="vo">
        <!-- 状态卡 + 步骤条 -->
        <a-card :bordered="false" class="panel status-panel">
          <div class="status-head">
            <div>
              <h2>{{ statusMain }}</h2>
              <p>{{ statusHint }}</p>
            </div>
            <a-button v-if="canShip" type="primary" size="large" class="pill" @click="shipOpen = true">
              <Truck :size="16" class="inline-block mr-1 align-middle" /> 立即发货
            </a-button>
          </div>
          <a-steps :current="stepIndex" size="small" class="steps"
                   :items="steps" />
          <a-alert v-if="refundBanner" :type="refundBanner.type" show-icon class="rf-banner"
                   :message="refundBanner.text" :description="refundBanner.desc" />
          <div class="no-line">
            <span>订单号 <b class="mono">{{ vo.order.orderNo }}</b></span>
            <span>下单时间 {{ shortTime(vo.order.createTime) }}</span>
            <span>支付方式 {{ payTypeText }}</span>
          </div>
        </a-card>

        <div class="grid">
          <!-- 我的商品明细 -->
          <a-card :bordered="false" class="panel">
            <template #title>
              <div class="panel-title">
                <div><strong>本店商品</strong><small>仅展示您店铺的商品明细</small></div>
              </div>
            </template>
            <div class="items">
              <div v-for="it in vo.myItems" :key="it.itemId" class="item-row">
                <img v-if="it.coverUrl" :src="it.coverUrl" alt="" loading="lazy" />
                <span v-else class="item-fallback">{{ (it.prodName || '商').slice(0, 1) }}</span>
                <div class="item-meta">
                  <b>{{ it.prodName }}</b>
                  <small>¥{{ money(it.price) }} × {{ it.quantity }}</small>
                </div>
                <b class="item-sub">¥{{ money(it.subtotal) }}</b>
              </div>
            </div>
            <div class="amount-line">
              <span>本店金额小计</span>
              <b>¥{{ money(vo.myAmount) }}</b>
            </div>
            <p v-if="Number(vo.order.totalAmount) !== Number(vo.myAmount)" class="amount-note">
              订单总额 ¥{{ money(vo.order.totalAmount) }}（可能包含其他商户商品或平台优惠分摊）
            </p>
          </a-card>

          <div class="col">
            <!-- 收货信息 -->
            <a-card :bordered="false" class="panel">
              <template #title>
                <div class="panel-title"><div><strong>收货信息</strong><small>发货时请核对</small></div></div>
              </template>
              <a-descriptions :column="1" size="small">
                <a-descriptions-item label="收货人">{{ vo.order.receiver || '—' }}</a-descriptions-item>
                <a-descriptions-item label="联系电话">{{ vo.order.phone || '—' }}</a-descriptions-item>
                <a-descriptions-item label="收货地址">{{ vo.order.address || '—' }}</a-descriptions-item>
                <a-descriptions-item v-if="vo.order.remark" label="买家备注">
                  <span class="remark">{{ vo.order.remark }}</span>
                </a-descriptions-item>
              </a-descriptions>
            </a-card>

            <!-- 物流运单 -->
            <a-card :bordered="false" class="panel">
              <template #title>
                <div class="panel-title"><div><strong>物流运单</strong><small>{{ vo.waybills?.length || 0 }} 个运单</small></div></div>
              </template>
              <template v-if="vo.waybills?.length">
                <button v-for="wb in vo.waybills" :key="wb.waybillId" type="button" class="wb-row"
                        @click="openTrace(wb)">
                  <div class="wb-info">
                    <b class="mono">{{ wb.waybillNo }}</b>
                    <small v-if="wb.logisticsNo">快递单号 {{ wb.logisticsNo }}</small>
                    <small>发货于 {{ shortTime(wb.shipTime) }}</small>
                  </div>
                  <span class="wb-right">
                    <a-tag :color="WAYBILL_STATUS[wb.status]?.color">{{ WAYBILL_STATUS[wb.status]?.text || wb.status }}</a-tag>
                    <i>轨迹 →</i>
                  </span>
                </button>
                <a-button v-if="canSign" block class="sign-btn" @click="signConfirm">
                  <CheckCircle2 :size="15" class="inline-block mr-1 align-middle text-emerald-500" /> 确认签收（买家已收货时操作）
                </a-button>
              </template>
              <a-empty v-else-if="!canShip" :image="aEmpty.PRESENTED_IMAGE_SIMPLE" description="暂无运单" />
              <p v-else class="ship-tip">完成发货后运单将显示在这里，买家可在订单页跟踪配送。</p>
            </a-card>
          </div>
        </div>
      </template>
    </a-spin>

    <!-- L4：发货弹窗 / 轨迹抽屉 -->
    <ShipModal v-model:open="shipOpen" :order-no="orderNo" :sender="sender" @shipped="load" />
    <TraceDrawer v-model:open="traceOpen" :waybill="traceWaybill" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Empty, message, Modal } from 'ant-design-vue'
import { Truck, CheckCircle2 } from 'lucide-vue-next'
import { orderApi, merchantCenterApi, logisticsApi } from '../../api'
import { ORDER_STATUS, PAY_CHANNEL, REFUND_STATUS, WAYBILL_STATUS, fmtMoney, orderStepIndex } from '../../utils/merchantStatus'
import ShipModal from '../../components/merchant/ShipModal.vue'
import TraceDrawer from '../../components/merchant/TraceDrawer.vue'

const aEmpty = Empty
const route = useRoute()
const orderNo = route.params.orderNo

const loading = ref(true)
const vo = ref(null)
const shipOpen = ref(false)
const traceOpen = ref(false)
const traceWaybill = ref(null)
const sender = ref({})

const money = fmtMoney
function shortTime(t) { return (t || '').replace('T', ' ').slice(0, 16) }

const steps = [
  { title: '买家下单' },
  { title: '完成支付' },
  { title: '商户发货' },
  { title: '交易完成' }
]

// 步骤条当前项：utils 基准（0=下单,1=支付,2=发货,3=完成）+1 对齐四步步骤条（1=下单进行中…4=全部完成）
const stepIndex = computed(() => orderStepIndex(vo.value?.order) + 1)
const canShip = computed(() => {
  const o = vo.value?.order
  return !!o && Number(o.payStatus) === 1 && o.orderStatus === 0
})
// 确认签收：状态机仅允许「派送中 → 已签收」，运输中/已揽收需等待服务商轨迹推进
const canSign = computed(() => vo.value?.waybills?.some(wb => wb.status === 'out_for_delivery'))
const payTypeText = computed(() => {
  const o = vo.value?.order
  if (!o) return '—'
  return o.payType === 'term' ? '企业账期' : (PAY_CHANNEL[o.payChannel] || '未支付')
})
const statusMain = computed(() => {
  const o = vo.value?.order
  if (!o) return ''
  if (o.refundStatus === 'applied') return '退款申请中 · 待平台处理'
  if (o.refundStatus === 'success') return '已退款'
  return ORDER_STATUS[o.orderStatus]?.text || '—'
})
const statusHint = computed(() => {
  const o = vo.value?.order
  if (!o) return ''
  if (o.refundStatus === 'applied') return '买家已提交退款申请，平台审核后将自动处理退款'
  if (o.refundStatus === 'success') return `退款金额 ¥${money(o.refundAmount)} 已原路退回`
  if (o.orderStatus === 0 && Number(o.payStatus) === 1) return '买家已支付，请尽快安排发货'
  if (o.orderStatus === 1) return '包裹运输中，买家确认收货或签收后订单完成'
  if (o.orderStatus === 2) return '订单已完成，货款可在资金结算中查看'
  if (o.orderStatus === 3) return '订单已取消'
  if (o.orderStatus === 4) return '订单已全额退款'
  return '等待买家完成支付'
})
const refundBanner = computed(() => {
  const o = vo.value?.order
  if (!o || !o.refundStatus || o.refundStatus === 'none') return null
  const map = {
    applied: { type: 'warning', text: `买家申请退款：${o.refundReason || '未填写原因'}`, desc: '退款由平台统一审核处理，审核结果将通过站内消息通知。' },
    rejected: { type: 'info', text: `平台已驳回该退款申请：${o.refundRejectReason || ''}`, desc: '买家可重新发起申请，请留意订单状态变化。' },
    processing: { type: 'processing', text: '退款处理中', desc: '退款正在原路返回买家账户。' },
    success: { type: 'success', text: `退款成功：¥${money(o.refundAmount)}`, desc: `申请原因：${o.refundReason || '—'}` }
  }
  return map[o.refundStatus] || null
})

async function load() {
  loading.value = true
  try {
    vo.value = await orderApi.merchantDetail(orderNo)
  } catch (e) { /* 拦截器已提示 */ }
  loading.value = false
}

function openTrace(wb) {
  traceWaybill.value = wb
  traceOpen.value = true
}

function signConfirm() {
  const wb = vo.value?.waybills?.find(w => w.status === 'out_for_delivery')
  if (!wb) return
  Modal.confirm({
    title: '确认该运单已签收？',
    content: `运单 ${wb.waybillNo} 将被标记为「已签收」，订单进入完成状态。请与买家确认后操作。`,
    okText: '确认签收', cancelText: '取消',
    onOk: async () => {
      try {
        await logisticsApi.transit(wb.waybillId, 'delivered', '商户确认签收')
        message.success('运单已签收，订单完成')
        load()
      } catch (e) { /* 拦截器已提示 */ }
    }
  })
}

async function loadSender() {
  try {
    const s = (await merchantCenterApi.summary())?.store
    if (s) sender.value = { name: s.entName || '', phone: s.contactPhone || '', address: s.businessAddress || '' }
  } catch (e) { /* 忽略 */ }
}

onMounted(() => { load(); loadSender() })
</script>

<style scoped>
.od-page { display: flex; flex-direction: column; gap: 14px; }
.crumb-row { display: flex; justify-content: space-between; align-items: center; padding-top: 4px; }
.crumb { font-size: 12.5px; }
.panel { border-radius: 16px; border: 1px solid var(--mc-border, #e8e6e1); box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.panel :deep(.ant-card-head) { border-bottom: 1px solid var(--mc-border-soft, #f0eee9); padding: 0 20px; min-height: 56px; }
.panel :deep(.ant-card-body) { padding: 16px 20px; }
.panel-title strong { display: block; font-size: 15px; color: var(--mc-ink, #1d1d1f); letter-spacing: -.01em; }
.panel-title small { display: block; color: var(--mc-faint, #a1a1a6); font-size: 11px; margin-top: 3px; }

.status-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.status-head h2 { margin: 0 0 6px; font-size: 21px; font-weight: 750; letter-spacing: -.02em; color: var(--mc-ink, #1d1d1f); }
.status-head p { margin: 0; font-size: 13px; color: var(--mc-muted, #6e6e73); }
.steps { margin: 18px 0 14px; }
.rf-banner { margin-bottom: 14px; border-radius: 12px; }
.no-line { display: flex; gap: 22px; flex-wrap: wrap; padding-top: 12px; border-top: 1px solid var(--mc-border-soft, #f0eee9); font-size: 12px; color: var(--mc-muted2, #86868b); }
.no-line b { color: var(--mc-ink, #1d1d1f); font-weight: 600; }
.pill { border-radius: 999px; }

.grid { display: grid; grid-template-columns: 1.55fr 1fr; gap: 14px; align-items: start; }
.col { display: flex; flex-direction: column; gap: 14px; }

.items { display: flex; flex-direction: column; }
.item-row { display: flex; align-items: center; gap: 12px; padding: 11px 0; border-bottom: 1px solid var(--mc-border-soft, #f5f4f0); }
.item-row:last-child { border-bottom: 0; }
.item-row img, .item-fallback { width: 46px; height: 46px; border-radius: 10px; object-fit: cover; background: var(--mc-soft, #f5f5f7); display: grid; place-items: center; color: var(--mc-faint, #b9c3d4); font-size: 15px; flex-shrink: 0; }
.item-meta { min-width: 0; flex: 1; }
.item-meta b { display: block; font-size: 13px; font-weight: 500; color: var(--mc-ink, #1d1d1f); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.item-meta small { color: var(--mc-muted2, #86868b); font-size: 11.5px; }
.item-sub { font-size: 13.5px; color: var(--mc-ink, #1d1d1f); white-space: nowrap; }
.amount-line { display: flex; justify-content: space-between; align-items: center; margin-top: 10px; padding: 13px 14px; background: var(--mc-tint, #faf9f6); border-radius: 12px; font-size: 13px; color: var(--mc-muted, #6e6e73); }
.amount-line b { font-size: 18px; color: var(--mc-accent, #9b2d20); letter-spacing: -.02em; }
.amount-note { margin: 8px 2px 0; font-size: 11.5px; color: var(--mc-faint, #a1a1a6); }

.wb-row { display: flex; justify-content: space-between; align-items: center; width: 100%; border: 1px solid var(--mc-border-soft, #f0eee9); background: var(--mc-card, #fff); border-radius: 12px; padding: 11px 13px; margin-bottom: 9px; cursor: pointer; font: inherit; text-align: left; transition: border-color .18s ease, background .18s ease; }
.wb-row:hover { border-color: var(--mc-border, #d8d5cf); background: var(--mc-tint, #fafaf8); }
.wb-info b { display: block; font-size: 12.5px; color: var(--mc-ink, #1d1d1f); }
.wb-info small { display: block; color: var(--mc-faint, #a1a1a6); font-size: 11px; margin-top: 2px; }
.wb-right { display: flex; flex-direction: column; align-items: flex-end; gap: 5px; }
.wb-right i { font-style: normal; font-size: 11.5px; color: var(--mc-accent, #9b2d20); }
.sign-btn { margin-top: 4px; border-radius: 12px; }
.ship-tip { margin: 4px 0; font-size: 12.5px; color: var(--mc-muted2, #86868b); }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
.remark { color: #92400e; background: #fef3c7; border-radius: 6px; padding: 2px 8px; }

@media (max-width: 900px) {
  .grid { grid-template-columns: 1fr; }
}
@media (max-width: 700px) {
  .status-head { flex-direction: column; }
  .status-head .pill { width: 100%; }
  .no-line { gap: 10px; flex-direction: column; }
}
</style>
