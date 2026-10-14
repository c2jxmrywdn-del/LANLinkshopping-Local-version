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
              <a-descriptions-item label="已退款金额">¥{{money(order.refundAmount)}}</a-descriptions-item>
            </a-descriptions>
            <a-button v-if="order.payStatus === 1 && order.refundStatus !== 'success'" danger @click="$router.push('/admin/payments')">返回交易管理处理退款</a-button>
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
import {orderApi,paymentApi} from '../../api'
const route=useRoute(),router=useRouter(),loading=ref(false),order=ref(null),payment=ref(null)
const items=computed(()=>order.value?.items||[])
const payText={0:'未支付',1:'已支付',2:'已退款'},payColor={0:'orange',1:'green',2:'purple'}
const orderText={0:'待发货',1:'已发货',2:'已完成',3:'已取消',4:'已退款'}
const channelText={wallet:'钱包',mock:'模拟网关',wechat:'微信支付',alipay:'支付宝'}
const money=v=>Number(v||0).toFixed(2),fmt=v=>v?String(v).replace('T',' ').slice(0,19):'—'
const refundText=computed(()=>order.value?.refundStatus==='success'?'已退款':order.value?.refundStatus==='processing'?'退款处理中':'无退款')
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
  }catch(e){order.value=null}finally{loading.value=false}
}
onMounted(load)
</script>
<style scoped>
.page{max-width:1120px;margin:0 auto}.hero{border-radius:22px;background:var(--ll-brand-hero-gradient);color:#fff;margin-bottom:16px;display:flex;justify-content:space-between;align-items:flex-start}.hero h1{color:#fff;font-size:34px;margin:7px 0}.hero p{color:rgba(255,255,255,.7);margin:0}.eyebrow{font-size:11px;letter-spacing:.16em;opacity:.7}.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.card{border-radius:18px}.item,.total{display:flex;justify-content:space-between;padding:8px 0}.total strong{font-size:21px;color:#9b2d20}@media(max-width:700px){.grid{grid-template-columns:1fr}}
</style>