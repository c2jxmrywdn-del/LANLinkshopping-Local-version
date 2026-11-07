<template>
  <div class="od-page">
    <!-- 面包屑 -->
    <div class="crumb-row">
      <a-breadcrumb class="crumb">
        <a-breadcrumb-item><a @click="$router.push('/merchant/center')">商户中心</a></a-breadcrumb-item>
        <a-breadcrumb-item><a @click="goBack">订单管理</a></a-breadcrumb-item>
        <a-breadcrumb-item>订单详情</a-breadcrumb-item>
      </a-breadcrumb>
      <a-button @click="goBack">返回列表</a-button>
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
            <a-space>
              <template v-if="vo.order.refundStatus === 'applied'">
                <a-button danger size="large" class="pill" @click="openRejectModal">
                  驳回退款
                </a-button>
                <a-button type="primary" size="large" class="pill" @click="openApproveModal">
                  同意初审
                </a-button>
              </template>
              <template v-else-if="vo.order.refundStatus === 'shipping_back' || vo.order.refundStatus === 'received'">
                <a-button type="primary" size="large" class="pill" @click="openInspectModal">
                  扫码验货质检入库
                </a-button>
              </template>
              <a-button v-if="canShip" type="primary" size="large" class="pill" @click="shipOpen = true">
                <Truck :size="16" class="inline-block mr-1 align-middle" /> 立即发货
              </a-button>
            </a-space>
          </div>
          <a-steps :current="stepIndex" size="small" class="steps"
                   :items="steps" />
          <a-alert v-if="refundBanner" :type="refundBanner.type" show-icon class="rf-banner"
                   :message="refundBanner.text" :description="refundBanner.desc" />
          <!-- 退款凭证证据区（链A t_order.refund_evidences / 链B rma.proofImages；存量无证据单显示空态不阻塞审核） -->
          <RefundEvidenceGallery v-if="hasRefundFlow" :evidences="evidenceJson" class="evidence-block" />
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
                <img v-if="it.coverUrl" :src="resolveStorageUrl(it.coverUrl)" alt="" loading="lazy" />
                <span v-else class="item-fallback"><Package :size="16" class="text-slate-400" /></span>
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
                <div class="panel-title" style="display: flex; justify-content: space-between; align-items: center; width: 100%;">
                  <div><strong>收货信息</strong><small>发货时请核对</small></div>
                  <a-button type="link" size="small" @click="contactBuyer">
                    <MessageSquare :size="13" class="inline-block mr-1 align-middle" /> 联系买家
                  </a-button>
                </div>
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

    <!-- 驳回退款申请弹窗 -->
    <a-modal v-model:open="rejectModalOpen" title="驳回退款申请" ok-text="确认驳回" cancel-text="取消"
             :confirm-loading="rejectLoading" ok-type="danger" @ok="doRejectRefund">
      <a-form layout="vertical">
        <a-form-item label="驳回原因（必填，将反馈给买家）" required>
          <a-textarea v-model:value="rejectReason" :rows="3" placeholder="请详细说明不符合退款条件的理由..." maxlength="200" show-count />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 仓库扫码验货质检入库弹窗 -->
    <a-modal v-model:open="inspectModalOpen" title="仓库收货验货与质检入库"
             :confirm-loading="inspectLoading" ok-text="确认质检结果" cancel-text="取消"
             @ok="doInspect">
      <a-alert type="info" show-icon style="margin-bottom: 16px;"
               :message="'买家寄回单号：' + (vo?.order?.returnCarrierName || '快递') + ' ' + (vo?.order?.returnWaybillNo || '—')"
               description="质检合格系统将自动恢复商品物理库存，并触发原路自动打款退款；若存在货损破损，将转入争议协商流程。" />
      <a-form layout="vertical">
        <a-form-item label="质检结论" required>
          <a-radio-group v-model:value="inspectStatus">
            <a-radio value="passed">质检合格 · 完好入库 (自动恢复库存并打款)</a-radio>
            <a-radio value="damaged">商品破损/少件 · 暂不入库 (转入争议协商)</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item label="质检说明 / 验货备注" required>
          <a-textarea v-model:value="inspectResult" :rows="3" maxlength="200" show-count
                      placeholder="例如：原包装完好，配件齐全，外观无划痕，已完成入库扫码" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Empty, message, Modal } from 'ant-design-vue'
import { Truck, CheckCircle2, Package, MessageSquare } from 'lucide-vue-next'
import { orderApi, merchantCenterApi, logisticsApi, rmaApi } from '../../api'
import { ORDER_STATUS, PAY_CHANNEL, REFUND_STATUS, WAYBILL_STATUS, fmtMoney, orderStepIndex } from '../../utils/merchantStatus'
import { resolveStorageUrl } from '../../utils/storageUrl'
import ShipModal from '../../components/merchant/ShipModal.vue'
import TraceDrawer from '../../components/merchant/TraceDrawer.vue'
import RefundEvidenceGallery from '../../components/refund/RefundEvidenceGallery.vue'

const aEmpty = Empty
const route = useRoute()
const router = useRouter()
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
  if (o?.refundStatus && !['none', 'rejected'].includes(o.refundStatus)) {
    return false
  }
  return !!o && Number(o.payStatus) === 1 && (o.orderStatus === 0 || o.orderStatus === 4)
})

function goBack() {
  const fromTab = route.query.fromTab
  if (fromTab) {
    router.push({ path: '/merchant/center/orders', query: { tab: fromTab } })
  } else {
    router.push('/merchant/center/orders')
  }
}

function contactBuyer() {
  const buyerId = vo.value?.order?.userId
  if (buyerId) {
    router.push({ path: '/merchant/center/chat', query: { peer: buyerId, orderNo } })
  } else {
    message.info('未能获取买家账号信息')
  }
}
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
  if (o.refundStatus === 'applied') return '退款申请中 · 待商家初审'
  if (o.refundStatus === 'merchant_approved') return o.refundType === 'return_goods' ? '初审通过 · 待买家寄回' : '初审通过 · 待平台出款'
  if (o.refundStatus === 'shipping_back') return '买家已寄回 · 待仓库验货'
  if (o.refundStatus === 'received') return '仓库已收货 · 质检中'
  if (o.refundStatus === 'dispute') return '验货异常协商中'
  if (o.refundStatus === 'partial') return '已部分退款'
  if (o.refundStatus === 'success') return '已退款'
  return ORDER_STATUS[o.orderStatus]?.text || '—'
})
const statusHint = computed(() => {
  const o = vo.value?.order
  if (!o) return ''
  if (o.refundStatus === 'applied') return '买家已提交退款申请，请商户尽快核实并完成初审'
  if (o.refundStatus === 'merchant_approved') return o.refundType === 'return_goods' ? '您已同意退货，等待买家寄回商品并录入物流单号' : '您已初审同意退款，平台财务正在打款处理中'
  if (o.refundStatus === 'shipping_back') return `买家已寄出退货包裹（${o.returnCarrierName || '快递'} ${o.returnWaybillNo || ''}），请在仓库收货后验货质检`
  if (o.refundStatus === 'received') return '退货包裹仓库已收货，请点击上方「扫码验货质检入库」完成质检'
  if (o.refundStatus === 'dispute') return `验货异常（${o.inspectResult || '货损异常'}），请与买家沟通协商处置`
  if (o.refundStatus === 'partial') return `已部分退款 ¥${money(o.refundAmount)}，买家可再次申请剩余金额`
  if (o.refundStatus === 'success') return `退款金额 ¥${money(o.refundAmount)} 已原路退回`
  if (o.orderStatus === 0 && Number(o.payStatus) === 1) return '买家已支付，请尽快安排发货'
  if (o.orderStatus === 1) return '包裹运输中，买家确认收货或签收后订单完成'
  if (o.orderStatus === 2) return '订单已完成，货款可在资金结算中查看'
  if (o.orderStatus === 3) return '订单已取消'
  if (o.orderStatus === 4) return '订单已部分发货，请尽快安排剩余商品出库'
  if (o.orderStatus === 5) return '订单已全额退款并关闭'
  return '等待买家完成支付'
})
const refundBanner = computed(() => {
  const o = vo.value?.order
  if (!o || !o.refundStatus || o.refundStatus === 'none') return null
  const map = {
    applied: { type: 'warning', text: `买家申请退款：${o.refundReason || '未填写原因'}`, desc: '请商户及时初审：确认无误可点击上方「同意初审」，若情况不符可点击「驳回退款」并注明原因。' },
    merchant_approved: { type: 'info', text: o.refundType === 'return_goods' ? '初审已同意退货 · 待买家寄回' : '商户初审已通过 · 待平台出款', desc: o.refundType === 'return_goods' ? '已向买家下发退货收件人与仓库地址，等待买家寄回包裹并回填单号。' : '您已同意买家的退款申请，当前正在等待平台财务进行退款出账。' },
    shipping_back: { type: 'info', text: `买家已寄回退货包裹：${o.returnCarrierName || '快递'} ${o.returnWaybillNo || ''}`, desc: '请在仓库收到货物后，点击上方「扫码验货质检入库」录入质检结论。合格后系统自动打款。' },
    received: { type: 'info', text: '退货包裹仓库已签收 · 质检中', desc: '请尽快完成开箱验货并录入质检结论。' },
    dispute: { type: 'error', text: `退货验货异常：${o.inspectResult || '货物破损'}`, desc: '商品质检未通过，已进入争议协商流程，当前未恢复库存且未触发打款。' },
    rejected: { type: 'info', text: `退款申请已驳回：${o.refundRejectReason || ''}`, desc: '买家可重新发起申请，请留意订单状态变化。' },
    processing: { type: 'processing', text: '退款处理中', desc: '退款正在原路返回买家账户。' },
    partial: { type: 'warning', text: `已部分退款：¥${money(o.refundAmount)}`, desc: `买家可再次申请剩余金额退款。申请原因：${o.refundReason || '—'}` },
    success: { type: 'success', text: `退款成功：¥${money(o.refundAmount)}`, desc: `申请原因：${o.refundReason || '—'}` }
  }
  return map[o.refundStatus] || null
})

// 退款初审操作
const rejectModalOpen = ref(false)
const rejectReason = ref('')
const rejectLoading = ref(false)

function openApproveModal() {
  Modal.confirm({
    title: '同意退款初审',
    content: '同意退款初审后，系统将提交至平台财务出款通道，是否确认同意？',
    okText: '确认同意',
    cancelText: '取消',
    onOk: async () => {
      try {
        await merchantCenterApi.approveRefund(orderNo)
        message.success('初审通过，已提交平台出款')
        await load()
      } catch (e) { /* 拦截器已提示 */ }
    }
  })
}

function openRejectModal() {
  rejectReason.value = ''
  rejectModalOpen.value = true
}

async function doRejectRefund() {
  if (!rejectReason.value.trim()) {
    message.warning('请填写驳回原因')
    return
  }
  rejectLoading.value = true
  try {
    await merchantCenterApi.rejectRefund(orderNo, rejectReason.value.trim())
    message.success('已驳回退款申请')
    rejectModalOpen.value = false
    await load()
  } catch (e) { /* 拦截器已提示 */ }
  finally { rejectLoading.value = false }
}

// 仓库扫码验货质检入库
const inspectModalOpen = ref(false)
const inspectLoading = ref(false)
const inspectStatus = ref('passed')
const inspectResult = ref('')

function openInspectModal() {
  inspectStatus.value = 'passed'
  inspectResult.value = '开箱查验完好，符合退货入库标准'
  inspectModalOpen.value = true
}

async function doInspect() {
  if (!inspectResult.value.trim()) {
    message.warning('请填写质检说明')
    return
  }
  const o = vo.value?.order
  if (!o?.currentRmaNo) {
    message.error('未找到关联的售后服务单号')
    return
  }
  inspectLoading.value = true
  try {
    await rmaApi.merchantInspect(o.currentRmaNo, {
      inspectStatus: inspectStatus.value,
      result: inspectResult.value.trim()
    })
    message.success(inspectStatus.value === 'passed' ? '质检合格，已自动恢复库存并退款' : '已记录货损异常，转入争议协商')
    inspectModalOpen.value = false
    await load()
  } catch (e) { /* 拦截器已提示 */ }
  finally { inspectLoading.value = false }
}

async function load() {
  loading.value = true
  try {
    vo.value = await orderApi.merchantDetail(orderNo)
    await loadRmaEvidence()
    checkActionQuery()
  } catch (e) { /* 拦截器已提示 */ }
  loading.value = false
}

// 退款证据：链A权威在 t_order.refund_evidences（VO.order 原样带出）；
// 链B（RMA）权威在 t_order_rma.proof_images，经 rmaApi 按单取，两者不双写
const rmaInfo = ref(null)
const evidenceJson = computed(() => rmaInfo.value?.proofImages || vo.value?.order?.refundEvidences || '')
const hasRefundFlow = computed(() => {
  const s = vo.value?.order?.refundStatus
  return !!s && !['none', 'rejected'].includes(s)
})
async function loadRmaEvidence() {
  const no = vo.value?.order?.currentRmaNo
  if (!no) { rmaInfo.value = null; return }
  try { rmaInfo.value = await rmaApi.getDetail(no) } catch (e) { rmaInfo.value = null }
}

function checkActionQuery() {
  const action = route.query.action
  if (action === 'inspect') {
    if (vo.value?.order?.refundStatus === 'shipping_back' || vo.value?.order?.refundStatus === 'received') {
      openInspectModal()
    }
  } else if (action === 'ship') {
    if (canShip.value) {
      shipOpen.value = true
    }
  } else if (action === 'audit') {
    if (vo.value?.order?.refundStatus === 'applied') {
      openApproveModal()
    }
  }
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
.evidence-block { margin-top: 12px; padding: 12px; border: 1px solid var(--ll-border, #e5e7eb); border-radius: var(--ll-radius-lg, 14px); background: var(--ll-card-soft, #fbfcfe); }
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
