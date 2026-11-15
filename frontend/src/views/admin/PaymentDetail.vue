<template>
  <div class="page">
    <a-page-header title="交易详情" sub-title="订单、支付与退款核对" @back="$router.push('/admin/payments')" />
    <a-spin :spinning="loading">
      <template v-if="order">
        <a-card :bordered="false" class="hero">
          <div><div class="eyebrow">PAYMENT / {{order.orderNo}}</div><h1>¥{{money(order.totalAmount)}}</h1><p>{{payText[order.payStatus] || '未知状态'}} · {{channelText[order.payChannel] || order.payChannel || '未支付'}}</p></div>
          <a-tag :color="payColor[order.payStatus] || 'default'">{{payText[order.payStatus] || '未知'}}</a-tag>
        </a-card>
        <div class="grid">
          <a-card :bordered="false" class="card" title="订单摘要">
            <a-descriptions :column="1" size="small" bordered>
              <a-descriptions-item label="订单号">{{order.orderNo}}</a-descriptions-item>
              <a-descriptions-item label="用户ID">{{order.userId}}</a-descriptions-item>
              <a-descriptions-item label="订单状态">{{orderText[order.orderStatus] || order.orderStatus}}</a-descriptions-item>
              <a-descriptions-item label="支付方式">{{order.payType || '—'}}</a-descriptions-item>
              <a-descriptions-item label="创建时间">{{fmt(order.createTime)}}</a-descriptions-item>
              <a-descriptions-item label="支付时间">{{fmt(order.payTime)}}</a-descriptions-item>
            </a-descriptions>
          </a-card>
          <!-- 收货信息（P1-8 管理端补齐）：买家下单地址簿快照，运营核对发货与开票地址 -->
          <a-card :bordered="false" class="card" title="收货信息">
            <a-descriptions :column="1" size="small" bordered>
              <a-descriptions-item label="收货人">{{order.receiver || '—'}}</a-descriptions-item>
              <a-descriptions-item label="联系电话">{{order.phone || '—'}}</a-descriptions-item>
              <a-descriptions-item label="收货地址">{{order.address || '—'}}</a-descriptions-item>
              <a-descriptions-item v-if="order.remark" label="订单备注">{{order.remark}}</a-descriptions-item>
            </a-descriptions>
          </a-card>
          <a-card :bordered="false" class="card" title="支付核验">
            <a-result v-if="payment?.paid" status="success" title="支付已确认" sub-title="支付状态已由服务端返回" />
            <a-result v-else status="info" title="尚未完成支付" sub-title="当前交易没有已确认的支付结果" />
            <a-descriptions :column="1" size="small">
              <a-descriptions-item label="渠道">{{channelText[payment?.channel || order.payChannel] || payment?.channel || order.payChannel || '—'}}</a-descriptions-item>
              <a-descriptions-item label="交易状态">{{payment?.status || (order.payStatus === 1 ? 'SUCCESS' : 'PENDING')}}</a-descriptions-item>
            </a-descriptions>
          </a-card>
          <a-card :bordered="false" class="card" title="商品与金额">
            <div v-for="item in items" :key="item.itemId" class="item"><span>{{item.prodName}} × {{item.quantity}}</span><b>¥{{money(item.subtotal)}}</b></div>
            <a-divider/><div class="total"><span>订单合计</span><strong>¥{{money(order.totalAmount)}}</strong></div>
          </a-card>
          <a-card :bordered="false" class="card" title="退款信息">
            <a-descriptions :column="1" size="small">
              <a-descriptions-item label="退款状态">{{refundText}}</a-descriptions-item>
              <a-descriptions-item v-if="order.refundReason" label="退款申请原因">{{order.refundReason}}</a-descriptions-item>
              <a-descriptions-item v-if="order.refundApplyTime" label="申请时间">{{fmt(order.refundApplyTime)}}</a-descriptions-item>
              <a-descriptions-item v-if="order.refundStatus === 'rejected'" label="驳回原因">{{order.refundRejectReason || '不符合退款条件'}}</a-descriptions-item>
              <a-descriptions-item label="已退款金额">¥{{money(order.refundAmount)}}</a-descriptions-item>
            </a-descriptions>
            <!-- 退款凭证证据区：链A t_order.refund_evidences；链B（RMA）proofImages 经 rmaApi 按单取 -->
            <RefundEvidenceGallery v-if="hasRefundFlow" :evidences="evidenceJson" class="evidence-block" />
            <a-button v-if="order.payStatus === 1 && order.refundStatus !== 'success'" danger @click="$router.push('/admin/payments')">返回交易管理处理退款</a-button>
          </a-card>
          <!-- 发票需求快照（P1-5）：买家随单登记的开票信息，运营核对开票用；未登记时不渲染 -->
          <a-card v-if="order.invoiceNeeded === 1" :bordered="false" class="card" title="发票信息">
            <a-descriptions :column="1" size="small">
              <a-descriptions-item label="抬头类型">{{order.invoiceTitleType === 'company' ? '单位' : '个人'}}</a-descriptions-item>
              <a-descriptions-item label="发票抬头">{{order.invoiceTitle || '—'}}</a-descriptions-item>
              <a-descriptions-item v-if="order.invoiceTitleType === 'company'" label="纳税人识别号">{{order.invoiceTaxNo || '—'}}</a-descriptions-item>
              <a-descriptions-item label="发票内容">
                <template v-if="order.invoiceContentType === 'category'">商品类别：{{order.invoiceCategory || '—'}}</template>
                <template v-else>开具明细：{{order.invoiceDetail || '—'}}</template>
              </a-descriptions-item>
            </a-descriptions>
          </a-card>
        </div>
      </template>
      <a-result v-else-if="!loading" status="404" title="交易不存在或无法访问">
        <template #extra><a-button type="primary" @click="$router.push('/admin/payments')">返回交易管理</a-button></template>
      </a-result>
    </a-spin>
  </div>
</template>
<script setup>
import {ref,computed,onMounted} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {orderApi,paymentApi,rmaApi} from '../../api'
import { refundStatusText } from './refundReview'
import RefundEvidenceGallery from '../../components/refund/RefundEvidenceGallery.vue'
const route=useRoute(),router=useRouter(),loading=ref(false),order=ref(null),payment=ref(null)
const items=computed(()=>order.value?.items||[])
const payText={0:'未支付',1:'已支付',2:'已退款'},payColor={0:'orange',1:'green',2:'purple'}
const orderText={0:'待发货',1:'已发货',2:'已完成',3:'已取消',4:'已退款'}
const channelText={wallet:'钱包',mock:'模拟网关',wechat:'微信支付',alipay:'支付宝'}
const money=v=>Number(v||0).toFixed(2),fmt=v=>v?String(v).replace('T',' ').slice(0,19):'—'
const refundText=computed(()=>{
  const s=order.value?.refundStatus
  if(s==='success') return '已退款'
  if(s==='partial') return `已部分退款 ¥${money(order.value?.refundAmount)}`
  // 覆盖 P0-C 新增中间态（商户初审通过/已寄回/已收货/争议），避免详情误显"无退款"
  return refundStatusText(s)
})
async function load(){
  loading.value=true;
  try{
    const res=await orderApi.detail(route.params.orderNo);
    if(res?.order){
      order.value={ ...res.order, items: res.items || [] };
    } else {
      order.value=res;
    }
    try{payment.value=await paymentApi.query(route.params.orderNo)}catch(e){payment.value=null}
    // 链B（RMA）证据权威在 t_order_rma.proof_images：按单取最新 RMA，链A列无值时兜底
    try{
      if(order.value?.currentRmaNo){
        rmaInfo.value = await rmaApi.getDetail(order.value.currentRmaNo);
      }
    }catch(e){rmaInfo.value=null}
  }catch(e){order.value=null}finally{loading.value=false}
}

const rmaInfo = ref(null);
const hasRefundFlow = computed(()=>{
  const s = order.value?.refundStatus;
  return !!s && !['none','rejected'].includes(s);
});
const evidenceJson = computed(()=> rmaInfo.value?.proofImages || order.value?.refundEvidences || '');
onMounted(load)
</script>
<style scoped>
.page{max-width:1120px;margin:0 auto}.hero{border-radius:22px;background:var(--ll-brand-hero-gradient);color:#fff;margin-bottom:16px;display:flex;justify-content:space-between;align-items:flex-start}.hero h1{color:#fff;font-size:34px;margin:7px 0}.hero p{color:rgba(255,255,255,.7);margin:0}.eyebrow{font-size:11px;letter-spacing:.16em;opacity:.7}.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.card{border-radius:18px}.item,.total{display:flex;justify-content:space-between;padding:8px 0}.total strong{font-size:21px;color:#9b2d20}.evidence-block{margin-top:12px;padding:12px;border:1px solid var(--ll-border,#e5e7eb);border-radius:var(--ll-radius-lg,14px);background:var(--ll-card-soft,#fbfcfe)}@media(max-width:700px){.grid{grid-template-columns:1fr}}
</style>
