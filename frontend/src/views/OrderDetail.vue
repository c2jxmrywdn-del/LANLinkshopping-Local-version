<template>
  <div class="detail-page">
    <a-page-header title="订单详情" sub-title="订单状态、商品、收货与支付信息" @back="$router.push('/orders')" />
    <a-spin :spinning="loading">
      <template v-if="detail?.order">
        <a-card :bordered="false" class="status-card">
          <div class="status-main">
            <div>
              <div class="eyebrow">ORDER / {{detail.order.orderNo}}</div>
              <h1>{{statusText(detail.order)}}</h1>
              <p>{{statusHint(detail.order)}}</p>
            </div>
            <div class="status-actions">
              <a-button v-if="canPay" type="primary" size="large" @click="openPay">立即支付</a-button>
              <a-button v-if="canConfirm" type="primary" size="large" @click="confirmReceipt">确认收货</a-button>
              <a-button v-if="canRefund" size="large" @click="openRefund">申请退款</a-button>
              <a-button v-if="canCancel" size="large" @click="cancel">取消订单</a-button>
            </div>
          </div>
          <a-steps :current="stepIndex" size="small" responsive class="steps">
            <a-step title="提交订单" :description="fmt(detail.order.createTime)" />
            <a-step title="支付完成" :description="fmt(detail.order.payTime)" />
            <a-step title="履约中" description="等待商户发货" />
            <a-step title="完成" description="订单完成" />
          </a-steps>
        </a-card>

        <div class="grid">
          <a-card :bordered="false" class="card" title="商品清单">
            <div v-for="item in detail.items" :key="item.itemId" class="item">
              <div class="thumb"><img v-if="item.coverUrl" :src="item.coverUrl" :alt="item.prodName" loading="lazy" decoding="async" /><span v-else>商品</span></div>
              <div class="item-main"><a @click="$router.push('/product/'+item.prodId)">{{item.prodName}}</a><small>¥{{money(item.price)}} × {{item.quantity}}</small></div>
              <strong>¥{{money(item.subtotal)}}</strong>
            </div>
            <a-empty v-if="!detail.items?.length" description="暂无商品明细" />
            <a-divider />
            <!-- 积分抵扣明细：1000 积分 = 1 元，整元抵扣 -->
            <div v-if="Number(detail.order.pointDeductAmount) > 0" class="sum point-sum">
              <span>积分抵扣（{{ detail.order.pointDeductPoints || 0 }} 积分 · 1000 积分=1 元）</span>
              <strong class="deduct">-¥{{money(detail.order.pointDeductAmount)}}</strong>
            </div>
            <div class="sum"><span>{{ Number(detail.order.pointDeductAmount) > 0 ? '实付金额' : '订单金额' }}</span><strong>¥{{money(detail.order.totalAmount)}}</strong></div>
          </a-card>

          <a-card :bordered="false" class="card" title="收货信息">
            <a-descriptions :column="1" size="small">
              <a-descriptions-item label="收货人">{{detail.order.receiver || '—'}}</a-descriptions-item>
              <a-descriptions-item label="联系电话">{{maskPhone(detail.order.phone)}}</a-descriptions-item>
              <a-descriptions-item label="收货地址">{{detail.order.address || '—'}}</a-descriptions-item>
              <a-descriptions-item label="订单备注">{{detail.order.remark || '—'}}</a-descriptions-item>
            </a-descriptions>
          </a-card>

          <a-card :bordered="false" class="card" title="支付与退款">
            <a-button v-if="detail.order.payType === 'term'" type="link" size="small" @click="$router.push('/credit-term/bills')">进入账期账单 →</a-button>
            <a-descriptions :column="1" size="small">
              <a-descriptions-item label="支付方式">{{payType[detail.order.payType] || detail.order.payType || '—'}}</a-descriptions-item>
              <a-descriptions-item label="支付渠道">{{channelText[detail.order.payChannel] || detail.order.payChannel || '—'}}</a-descriptions-item>
              <a-descriptions-item label="支付时间">{{fmt(detail.order.payTime)}}</a-descriptions-item>
              <a-descriptions-item label="退款状态">{{refundText(detail.order)}}</a-descriptions-item>
              <a-descriptions-item v-if="detail.order.refundReason" label="退款申请原因">{{ detail.order.refundReason }}</a-descriptions-item>
              <a-descriptions-item v-if="detail.order.refundApplyTime" label="申请时间">{{ fmt(detail.order.refundApplyTime) }}</a-descriptions-item>
              <a-descriptions-item v-if="detail.order.refundStatus === 'rejected'" label="驳回原因">{{ detail.order.refundRejectReason || '不符合退款条件' }}</a-descriptions-item>
              <a-descriptions-item v-if="detail.order.refundAmount" label="已退金额">¥{{money(detail.order.refundAmount)}}</a-descriptions-item>
            </a-descriptions>
          </a-card>

          <a-card :bordered="false" class="card" title="订单信息">
            <a-descriptions :column="1" size="small">
              <a-descriptions-item label="订单号">{{detail.order.orderNo}}</a-descriptions-item>
              <a-descriptions-item label="订单状态">{{statusMap[detail.order.orderStatus] || detail.order.orderStatus}}</a-descriptions-item>
              <a-descriptions-item label="创建时间">{{fmt(detail.order.createTime)}}</a-descriptions-item>
              <a-descriptions-item label="更新时间">{{fmt(detail.order.updateTime)}}</a-descriptions-item>
            </a-descriptions>
          </a-card>
        </div>
      </template>
      <a-result v-else-if="!loading" status="404" title="订单不存在或无权查看" sub-title="请返回我的订单重新选择">
        <template #extra><a-button type="primary" @click="$router.push('/orders')">返回订单</a-button></template>
      </a-result>
    </a-spin>
    <a-modal v-model:open="payOpen" title="选择支付方式" :footer="null" @cancel="stopPayPolling">
      <div class="pay-summary">应付 <b>¥{{ money(detail?.order?.totalAmount) }}</b></div>

      <!-- 扫码支付：优先展示渠道 codeUrl 二维码；渠道未配置时退化为可扫演示码 -->
      <div v-if="payQr" class="pay-qr">
        <div class="pay-qr__plate">
          <canvas ref="payCanvasRef" role="img" aria-label="扫码支付二维码"></canvas>
          <span class="pay-qr__logo" aria-hidden="true"><img src="/logo.svg" alt="" /></span>
        </div>
        <p class="pay-qr__hint">
          {{ payQrDemo ? '支付渠道未配置，以下为演示二维码（可真实扫描查看订单信息）' : '请使用微信「扫一扫」完成支付，支付成功后订单状态自动更新' }}
        </p>
        <a-button block :loading="payLoading" @click="payWithScan">刷新二维码</a-button>
        <a-button v-if="payQrDemo" block type="primary" :loading="payLoading" @click="payWith('mock')">模拟支付成功</a-button>
        <a-button block @click="closePayQr">返回其它支付方式</a-button>
      </div>

      <a-space v-else direction="vertical" style="width:100%" size="middle">
        <a-button block size="large" :loading="payLoading" :disabled="walletBalance < Number(detail?.order?.totalAmount || 0)" @click="payWith('wallet')">钱包余额　¥{{ walletBalance.toFixed(2) }}<span v-if="walletBalance < Number(detail?.order?.totalAmount || 0)">（余额不足）</span></a-button>
        <a-button block size="large" :loading="payLoading" @click="payWith('mock')">模拟网关　演示环境即时到账</a-button>
        <a-button block size="large" :loading="payLoading" @click="payWithScan">微信扫码支付</a-button>
      </a-space>
      <div class="pay-tip">真实微信/支付宝渠道需完成服务端商户凭证配置后启用。</div>
    </a-modal>

    <!-- 申请退款弹窗 -->
    <a-modal v-model:open="refundOpen" title="申请退款" ok-text="提交申请" cancel-text="取消"
             :confirm-loading="refunding" @ok="doRefund">
      <a-form layout="vertical">
        <a-form-item label="可退金额"><b>¥{{ money(Number(detail?.order?.totalAmount || 0) - Number(detail?.order?.refundAmount || 0)) }}</b></a-form-item>
        <a-form-item label="退款原因（必填，将提交平台审核）" required>
          <a-textarea v-model:value="refundReason" :rows="3" maxlength="200" show-count
                      placeholder="例如：商品缺货 / 与描述不符 / 沟通后协商退款" />
        </a-form-item>
        <a-alert type="info" show-icon message="申请后由平台审核处理"
                 description="申请按可退余额全额提交，平台审核通过后资金原路退回；若被驳回会展示驳回原因，你可再次申请。" />
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import {ref,computed,onMounted,nextTick,watch,onBeforeUnmount} from 'vue'
import {message,Modal} from 'ant-design-vue'
import {useRoute,useRouter} from 'vue-router'
import {orderApi,paymentApi,walletApi,trafficApi} from '../api'
import {buildPayQrPayload,renderQrToCanvas} from '../utils/qrCode'
const route=useRoute(),router=useRouter(),detail=ref(null),loading=ref(false),payLoading=ref(false),walletBalance=ref(0),payOpen=ref(false)
const refundOpen=ref(false),refunding=ref(false),refundReason=ref('')
// 扫码支付状态
const payQr=ref(''),payQrDemo=ref(false),payCanvasRef=ref(null)
let payPollTimer=0
const statusMap={0:'待发货',1:'已发货',2:'已完成',3:'已取消',4:'已退款'}
const payType={corporate:'对公转账',balance:'企业钱包',term:'账期/尾款'}
const channelText={wallet:'钱包',mock:'模拟网关',wechat:'微信支付',alipay:'支付宝'}
const canPay=computed(()=>{const o=detail.value?.order;return o&&o.payStatus===0&&o.orderStatus===0&&o.payType!=='term'})
const canCancel=computed(()=>{const o=detail.value?.order;return o&&o.orderStatus===0&&(o.payStatus===0||o.payType==='term')})
/** 可确认收货：已支付且已发货（订单完成闭环入口） */
const canConfirm=computed(()=>{const o=detail.value?.order;return !!o&&Number(o.payStatus)===1&&Number(o.orderStatus)===1})
/** 可申请退款：已支付、非账期、订单未结束，且无进行中/已完成退款（被驳回可再次申请） */
const canRefund=computed(()=>{const o=detail.value?.order;return !!o&&Number(o.payStatus)===1&&o.payType!=='term'&&![3,4].includes(Number(o.orderStatus))&&!['applied','processing','success'].includes(o.refundStatus||'none')})
const stepIndex=computed(()=>{const o=detail.value?.order;if(!o)return 0;if(o.orderStatus===3||o.orderStatus===4)return 0;if(o.orderStatus===2)return 3;if(o.orderStatus===1)return 2;return o.payStatus===1?2:0})
const fmt=v=>v?String(v).replace('T',' '):'—'
const money=v=>Number(v||0).toFixed(2)
const maskPhone=v=>{const s=String(v||'');return s.length>=7?s.slice(0,3)+'****'+s.slice(-4):s||'—'}
const refundText=o=>o.refundStatus==='success'?'已退款':o.refundStatus==='processing'?'退款处理中':o.refundStatus==='applied'?'退款申请中 · 待平台处理':o.refundStatus==='rejected'?'退款申请被驳回':'无退款'
const statusText=o=>o.orderStatus===3?'订单已取消':o.orderStatus===4?'订单已退款':o.payStatus===1?'订单已支付':'待支付'
const statusHint=o=>o.orderStatus===3?'订单已取消，无法继续操作':o.orderStatus===4?'该订单已完成退款':o.payStatus===1?'订单已进入履约流程':'完成支付后商户才会开始履约'
async function load(){loading.value=true;try{detail.value=await orderApi.detail(route.params.orderNo)}catch(e){detail.value=null}finally{loading.value=false}}
async function openPay(){try{const w=await walletApi.my();walletBalance.value=Number(w?.balance||0);payOpen.value=true}catch(e){payOpen.value=true}}
/** 支付成功埋点：订单详情无商户归属信息，PAY 作为平台级事件上报（后端以 0 兜底），失败静默 */
function trackPay(){ trafficApi.trackPage({eventType:'PAY',sourceType:'order_detail'}).catch(()=>{}) }
async function payWith(channel){if(payLoading.value)return;if(channel==='wallet'&&walletBalance.value<Number(detail.value?.order?.totalAmount||0)){router.push('/wallet/recharge');payOpen.value=false;return}payLoading.value=true;try{const vo=await paymentApi.create(route.params.orderNo,channel);if(channel==='mock'&&vo?.mock)await paymentApi.mockConfirm(route.params.orderNo);if(channel==='wallet'&&!vo?.payInfo?.paid)throw new Error('支付未确认');message.success('支付成功');trackPay();payOpen.value=false;await load()}catch(e){}finally{payLoading.value=false}}
async function cancel(){try{await orderApi.cancel(route.params.orderNo);message.success('订单已取消');await load()}catch(e){}}
function confirmReceipt(){
  Modal.confirm({
    title:'确认已收到货？',
    content:`订单 ${route.params.orderNo} 将标记为「已完成」，在途运单同步签收。请确认商品已验收无误。`,
    okText:'确认收货',cancelText:'再等等',
    onOk:async()=>{try{await orderApi.confirmReceipt(route.params.orderNo);message.success('确认收货成功，交易完成');await load()}catch(e){/* 拦截器已提示 */}}
  })
}
function openRefund(){refundReason.value='';refundOpen.value=true}
async function doRefund(){if(!refundReason.value.trim()){message.warning('请填写退款原因');return}refunding.value=true;try{await orderApi.applyRefund(route.params.orderNo,refundReason.value.trim());message.success('退款申请已提交，请等待平台处理');refundOpen.value=false;await load()}catch(e){/* 拦截器已提示 */}finally{refunding.value=false}}
// ===== 扫码支付 =====
async function drawPayQr(){
  await nextTick()
  if(payCanvasRef.value) await renderQrToCanvas(payCanvasRef.value,payQr.value,{width:200,errorCorrectionLevel:'Q'})
}
/** 生成扫码支付二维码：渠道有 codeUrl 用真实码并轮询到账；未配置则退化为可扫演示码 */
async function payWithScan(){
  if(payLoading.value)return
  payLoading.value=true
  try{
    const vo=await paymentApi.create(route.params.orderNo,'wechat')
    const codeUrl=vo?.codeUrl
    payQrDemo.value=!codeUrl
    payQr.value=buildPayQrPayload({codeUrl,orderNo:route.params.orderNo,amount:detail.value?.order?.totalAmount})
    await drawPayQr()
    if(!payQrDemo.value) startPayPolling()
  }catch(e){
    // 渠道未配置（本地 mock 模式）：退化为演示码，保证交互可验证
    payQrDemo.value=true
    payQr.value=buildPayQrPayload({orderNo:route.params.orderNo,amount:detail.value?.order?.totalAmount})
    await drawPayQr()
  }finally{payLoading.value=false}
}
function closePayQr(){ stopPayPolling(); payQr.value=''; payQrDemo.value=false }
function startPayPolling(){
  stopPayPolling()
  payPollTimer=setInterval(async()=>{
    try{
      const st=await paymentApi.query(route.params.orderNo)
      if(Number(st?.payStatus)===1){ stopPayPolling(); message.success('支付成功'); trackPay(); payOpen.value=false; await load() }
    }catch(e){ /* 静默重试 */ }
  },3000)
}
function stopPayPolling(){ if(payPollTimer){ clearInterval(payPollTimer); payPollTimer=0 } }

// 弹窗关闭即清理轮询与二维码态，避免残留
watch(payOpen,(v)=>{ if(!v) closePayQr() })
onBeforeUnmount(stopPayPolling)

onMounted(load)
</script>

<style scoped>
.detail-page{max-width:1100px;margin:0 auto}.status-card,.card{border-radius:18px}.status-card{margin-bottom:16px;background:var(--ll-brand-hero-gradient);color:#fff}.eyebrow{font-size:11px;letter-spacing:.16em;opacity:.7}.status-main{display:flex;justify-content:space-between;gap:20px;align-items:center}.status-card h1{color:#fff;margin:6px 0;font-size:28px}.status-card p{color:rgba(255,255,255,.72);margin:0}.pay-summary{font-size:15px;margin-bottom:18px}.pay-summary b{font-size:24px;color:#9b2d20;margin-left:6px}.pay-tip{margin-top:14px;color:var(--ll-muted);font-size:12px}.status-actions{display:flex;gap:8px}.steps{margin-top:26px}.status-card :deep(.ant-steps-item-title),.status-card :deep(.ant-steps-item-description){color:rgba(255,255,255,.8)}.grid{display:grid;grid-template-columns:1.5fr 1fr;gap:16px}.item{display:flex;align-items:center;gap:12px;padding:11px 0;border-bottom:1px solid #eee}.thumb{width:58px;height:58px;border-radius:10px;background:#f5f0e8;display:grid;place-items:center;overflow:hidden;flex:none}.thumb img{width:100%;height:100%;object-fit:cover}.item-main{flex:1}.item-main a{display:block;color:var(--ll-ink);font-weight:600}.item-main small{display:block;color:var(--ll-muted);margin-top:4px}.sum{display:flex;justify-content:space-between}.sum strong{font-size:22px;color:#9b2d20}.point-sum{margin-bottom:6px}.point-sum strong{font-size:16px}.point-sum .deduct{color:#10b981}@media(max-width:700px){.status-main{flex-direction:column;align-items:flex-start}.grid{grid-template-columns:1fr}.status-actions{width:100%}.status-actions .ant-btn{flex:1}}
.pay-qr{display:flex;flex-direction:column;align-items:center;gap:10px;padding:4px 0 2px}
.pay-qr__plate{position:relative;display:grid;place-items:center;padding:10px;border-radius:var(--ll-radius-md,10px);background:#fff;box-shadow:var(--ll-shadow-sm,0 2px 8px rgba(19,35,58,.07))}
.pay-qr__plate canvas{display:block;width:200px;height:200px}
.pay-qr__logo{position:absolute;display:grid;place-items:center;width:38px;height:38px;border-radius:9px;background:#fff;box-shadow:0 0 0 3px #fff,0 1px 6px rgba(19,35,58,.18)}
.pay-qr__logo img{width:26px;height:26px;object-fit:contain}
.pay-qr__hint{margin:0;font-size:11px;line-height:1.6;color:var(--ll-muted,#64748b);text-align:center}
.pay-qr .ant-btn{width:100%}
</style>
