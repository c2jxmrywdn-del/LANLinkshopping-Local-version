<template><div class="sys">
  <div class="hero"><div><div class="eyebrow membership-eyebrow"><MembershipIcon :size="30" label="会员图标" /><span>LANLINK MEMBERSHIP</span></div><h1>会员中心</h1><p>围绕等级、成长、积分与权益建立完整会员闭环。</p></div><div class="actions"><a-button type="primary" @click="$router.push('/membership/points')">积分中心</a-button><a-button @click="$router.push('/membership/orders')">订阅订单</a-button><a-button @click="$router.push('/membership/benefits')">我的权益</a-button></div></div>
  <a-card v-if="data" :bordered="false" class="member"><div><div class="level">{{data.level?.levelName||'普通会员'}}</div><div class="meta">成长值 {{data.card?.growth||0}} · 积分 {{data.card?.points||0}}</div></div><div class="discount">{{rate}}<small>等级折扣</small></div><div class="progress"><div :style="{width:growthPct}"></div></div><span class="hint">{{nextHint}}</span></a-card>
  <!-- VIP 订阅区块（P0-1 重构）：身份态条（三态）→ 计费周期切换+档位卡（radio 选择态，选择与提交分离）→ 权益对比 → 信任行+唯一 CTA -->
  <!-- 「自动续费」标注 P0 全线隐藏（后端无续费扣款调度，到期即断），改挂「到期可一键续费」诚实文案；P1-4 调度上线后回切 -->
  <section class="vip-subscribe">
    <VipIdentityBar
      :state="idbarState"
      :level-name="data?.level?.levelName || '普通会员'"
      :rate-text="rate"
      :plan-name="currentPlanName"
      :end-date="formatDate(sub?.endTime)"
      :status-text="statusLabel(sub?.status)"
      :multiplier="data?.rule?.vipEarnMultiplier || 2"
      :auto-renew="!!sub?.autoRenew"
      :busy="purchasing === sub?.planId"
      @renew="renew"
      @cancel="cancelSub" />
    <template v-if="!hasActiveSub">
      <VipPlanGrid v-if="plans.length" :plans="plans" v-model="selectedPlanId" :busy="purchasing" v-model:billing="billing" />
      <div v-if="selectedPlan" class="vip-cta-zone ll-glass--day--deep">
        <!-- D3 取消语义回切：取消=撤销自动续费，权益保留至到期日 -->
        <p class="trust-line"><ShieldCheck :size="14" :stroke-width="2" aria-hidden="true" />到期后不再自动续费，可随时取消续费</p>
        <a-button type="primary" size="large" class="vip-cta" :loading="!!purchasing" @click="buy(selectedPlan)">立即开通</a-button>
      </div>
    </template>
    <VipCompareTable />
  </section>
  <!-- P1 支付确认弹窗：复用 PaymentMethodSelector 渠道选择 → subscriptionApi.purchase 两段式 → 按渠道拉起支付 -->
  <a-modal v-model:open="payOpen" title="确认开通订阅" :footer="null" centered width="520px" class="pay-modal">
    <div class="pay-body">
      <div class="pay-plan">
        <div><div class="pay-name">{{ payPlan?.planName }}</div><div class="pay-meta">{{ payPlan?.durationDays != null ? payPlan.durationDays + ' 天' : '' }}</div></div>
        <div class="pay-price">¥{{ formatPrice(payPlan?.priceCents) }}</div>
      </div>
      <PaymentMethodSelector v-model="payChannel" :payable-total="payPlan?.priceCents || 0" />
      <p class="pay-hint">支付成功后订阅立即生效；连续包月方案到期将自动续费，可随时取消续费（权益保留至到期日）。</p>
      <a-button type="primary" size="large" block class="vip-cta" :loading="paying" @click="confirmPay">去支付</a-button>
    </div>
  </a-modal>
  <!-- 勋章墙：等级维度（后端等级映射）+ 成就维度（成长/积分里程碑），获取/升级动效由 MembershipBadge 承担 -->
  <section v-if="data" class="badge-wall">
    <div class="wall-head"><h2>我的勋章</h2><span class="wall-sub">等级勋章随成长值解锁 · 成就勋章记录里程碑</span></div>
    <div class="wall-grid">
      <MembershipBadge v-for="(m, i) in levelMedals" :key="'lv' + m.tier"
        :tier="m.tier" :eyebrow="m.eyebrow" :title="m.title" :state="m.state"
        :delay="i * 90" :burst="m.state !== 'locked'" @detail="openDetail(m)" />
    </div>
    <div class="wall-head wall-head--sub"><h2>成就勋章</h2><span class="wall-sub">消费 · 积分 · 忠诚多维成就</span></div>
    <div class="wall-grid">
      <MembershipBadge v-for="(m, i) in achMedals" :key="'ac' + m.title"
        :tier="m.tier" :eyebrow="m.eyebrow" :title="m.title" :state="m.state"
        :delay="400 + i * 90" :burst="m.state !== 'locked'" @detail="openDetail(m)" />
    </div>
  </section>
  <!-- 勋章详情弹窗：大号徽章 + 状态/条件/进度/说明 -->
  <a-modal v-model:open="detailOpen" :footer="null" centered width="440px" class="medal-modal">
    <template #title><span class="modal-title">勋章详情</span></template>
    <div v-if="detail" class="medal-detail">
      <MembershipBadge :tier="detail.tier" :eyebrow="detail.eyebrow" :title="detail.title"
        :state="detail.state" size="lg" :burst="detailOpen" />
      <div class="md-rows">
        <div class="md-row"><span>状态</span><b :class="{ 'md-on': detail.state !== 'locked' }">{{ detail.state === 'locked' ? '未解锁' : detail.state === 'current' ? '当前等级' : '已获得' }}</b></div>
        <div class="md-row"><span>解锁条件</span><b>{{ detail.req }}</b></div>
        <div class="md-row md-row--col"><span>当前进度</span>
          <a-progress :percent="Math.round(detail.progress * 100)" :status="detail.state === 'locked' ? 'normal' : 'success'" size="small" />
        </div>
        <div class="md-row md-row--col"><span>勋章说明</span><p>{{ detail.desc }}</p></div>
      </div>
    </div>
  </a-modal>
  <div class="grid"><a-card v-for="x in cards" :key="x.title" :bordered="false" class="nav-card" @click="$router.push(x.to)"><div class="num">{{x.num}}</div><h3>{{x.title}}</h3><p>{{x.desc}}</p><span>进入 →</span></a-card></div>
  <a-card :bordered="false" class="levels" title="等级速览"><a-steps :current="currentIndex" responsive size="small"><a-step v-for="l in (data?.levels||[])" :key="l.levelId" :title="l.levelName" :description="l.minGrowth+' 成长值'"/></a-steps><p v-if="!hasActiveSub" class="sub-hint">需有效订阅方可升级</p></a-card>
</div></template>
<script setup>
import {ref,computed,onMounted,onUnmounted,watch} from 'vue';import {message,Modal} from 'ant-design-vue';import {membershipApi,subscriptionApi,paymentApi} from '../api';import {ShieldCheck} from 'lucide-vue-next'
import MembershipIcon from '../components/ui/MembershipIcon.vue'
import MembershipBadge from '../components/ui/MembershipBadge.vue'
import VipIdentityBar from '../components/membership/VipIdentityBar.vue'
import VipPlanGrid from '../components/membership/VipPlanGrid.vue'
import VipCompareTable from '../components/membership/VipCompareTable.vue'
import PaymentMethodSelector from '../components/payment/PaymentMethodSelector.vue'
import {useMembershipStore} from '../store/membership'
const membership = useMembershipStore()
const data=ref(null);const cards=[{num:'01',title:'等级权益',desc:'成长值门槛、等级折扣与体系说明',to:'/membership/levels'},{num:'02',title:'积分中心',desc:'积分余额、获取、抵现与流水',to:'/membership/points'},{num:'03',title:'会员权益',desc:'折扣、积分、成长值等权益说明',to:'/membership/benefits'}];const rate=computed(()=>{const r=Number(data.value?.level?.discountRate||1);return r>=1?'无折扣':(r*10).toFixed(1)+' 折'});const currentIndex=computed(()=>Math.max(0,(data.value?.levels||[]).findIndex(x=>x.levelId===data.value?.level?.levelId)));const growthPct=computed(()=>{const ls=data.value?.levels||[],cur=data.value?.level?.minGrowth||0,n=ls.find(x=>x.minGrowth>cur);if(!n)return '100%';const got=(data.value?.card?.growth||0)-cur;return Math.max(5,Math.min(100,got/(n.minGrowth-cur)*100))+'%'});const nextHint=computed(()=>{const ls=data.value?.levels||[],cur=data.value?.level?.minGrowth||0,n=ls.find(x=>x.minGrowth>cur);return n?'距离 '+n.levelName+' 还需成长值 '+Math.max(0,n.minGrowth-(data.value?.card?.growth||0)):'已达最高等级'});
// ===== 勋章体系：等级维度（levelId→tier 映射）+ 成就维度（成长/积分里程碑阈值） =====
const TIER_BY_LEVEL={1:'regular',2:'bronze',3:'silver',4:'gold',5:'platinum',6:'diamond',7:'black-diamond'};
const levelMedals=computed(()=>{const cur=data.value?.level?.levelId||1;const g=data.value?.card?.growth||0;return (data.value?.levels||[]).map(l=>({tier:TIER_BY_LEVEL[l.levelId]||'regular',eyebrow:'LEVEL '+l.levelId,title:l.levelName,state:l.levelId===cur?'current':(l.levelId<cur?'earned':'locked'),req:l.minGrowth<=0?'注册即享':'成长值 ≥ '+l.minGrowth,desc:l.description||'等级勋章：随成长值累积自动晋升。',progress:l.minGrowth<=0?1:Math.min(1,g/l.minGrowth)}))});
const achMedals=computed(()=>{const g=data.value?.card?.growth||0,p=data.value?.card?.points||0;return [
 {tier:'azure',eyebrow:'GROWTH · 成长',title:'初出茅庐',state:g>=100000?'earned':'locked',req:'成长值 ≥ 100000',desc:'完成早期成长积累，达到青铜会员门槛。',progress:Math.min(1,g/100000)},
 {tier:'regular',eyebrow:'POINTS · 积分',title:'积分达人',state:p>=100000?'earned':'locked',req:'积分 ≥ 100000',desc:'积分累计突破十万，可享积分抵现等玩法。',progress:Math.min(1,p/100000)},
 {tier:'gold',eyebrow:'MILESTONE · 里程碑',title:'成长先锋',state:g>=1000000?'earned':'locked',req:'成长值 ≥ 1000000',desc:'成长值达到黄金会员门槛级别的里程碑成就。',progress:Math.min(1,g/1000000)},
 {tier:'platinum',eyebrow:'PLATINUM · 进阶',title:'铂金荣耀',state:g>=5000000?'earned':'locked',req:'成长值 ≥ 5000000',desc:'成长值达到铂金会员门槛级别的高阶成就。',progress:Math.min(1,g/5000000)},
 {tier:'black-diamond',eyebrow:'LOYALTY · 忠诚',title:'至尊挚友',state:g>=20000000?'earned':'locked',req:'成长值 ≥ 20000000',desc:'黑钻级忠诚成就，平台长期深度伙伴象征。',progress:Math.min(1,g/20000000)}]});
// ===== 勋章详情弹窗 =====
const detailOpen=ref(false);const detail=ref(null);
function openDetail(m){detail.value=m;detailOpen.value=true}
// ===== 会员订阅：计划 / 当前订阅 / 购买 / 续费 / 取消 =====
const plans=ref([]);const sub=ref(null);const purchasing=ref(null);
// 档位选择与提交分离：卡片 radio 选择态存 selectedPlanId，提交只走底部唯一 CTA「立即开通」；计费周期默认按年（D1）
const selectedPlanId=ref(null);const billing=ref('year');
const selectedPlan=computed(()=>plans.value.find(p=>String(p.planId)===String(selectedPlanId.value))||null);
// 身份态条三态：未订阅 / 生效中 / 已取消或已过期（恢复走续费同链路）
const idbarState=computed(()=>!sub.value?'none':(hasActiveSub.value?'active':'ended'));
// 默认选中推荐档（按年段周期最长档 = 年度订阅），计划异步到货后补齐一次
function pickDefaultPlan(list){const year=list.filter(p=>{const c=String(p.planCode||'').toUpperCase();return !(c.includes('MONTH')||c.includes('AUTO'))&&Number(p.durationDays)>30});const pool=year.length?year:list;return pool.reduce((b,p)=>Number(p.durationDays)>Number(b.durationDays)?p:b)}
watch(plans,(list)=>{if(!list.length)return;if(!selectedPlanId.value||!list.some(p=>String(p.planId)===String(selectedPlanId.value)))selectedPlanId.value=pickDefaultPlan(list).planId})
// 切换计费段时校验选中档归属：残留他段选中态会让底部 CTA 提交不可见档位（买错档）
function segmentOf(p){const code=String(p?.planCode||'').toUpperCase();if(code.includes('MONTH')||code.includes('AUTO'))return 'month';return p?.durationDays!=null&&Number(p.durationDays)>30?'year':'month'}
watch(billing,(seg)=>{const list=plans.value.filter(p=>segmentOf(p)===seg);if(list.length&&!list.some(p=>String(p.planId)===String(selectedPlanId.value)))selectedPlanId.value=pickDefaultPlan(list).planId})
// 状态归一：兼容后端以字符串（active/expired/cancelled）或数字（1/2/3）返回 status
const STATUS_MAP={active:{label:'有效',active:true},valid:{label:'有效',active:true},normal:{label:'有效',active:true},running:{label:'有效',active:true},paid:{label:'有效',active:true},'1':{label:'有效',active:true},'有效':{label:'有效',active:true},expired:{label:'已过期',active:false},finished:{label:'已过期',active:false},'2':{label:'已过期',active:false},'已过期':{label:'已过期',active:false},cancelled:{label:'已取消',active:false},canceled:{label:'已取消',active:false},refunded:{label:'已取消',active:false},'3':{label:'已取消',active:false},'0':{label:'已取消',active:false},'已取消':{label:'已取消',active:false}};
function normStatus(s){if(s==null||s==='')return{label:'有效',active:true};return STATUS_MAP[String(s).toLowerCase()]||{label:String(s),active:true}}
const statusLabel=(s)=>normStatus(s).label;
const hasActiveSub=computed(()=>!!sub.value&&normStatus(sub.value.status).active);
const currentPlanName=computed(()=>{if(!sub.value)return'';const p=plans.value.find(x=>String(x.planId)===String(sub.value.planId));return p?.planName||sub.value.planName||''});
function _pad(n){return String(n).padStart(2,'0')}
function _fmtDate(d){return d.getFullYear()+'-'+_pad(d.getMonth()+1)+'-'+_pad(d.getDate())}
function formatDate(v){if(!v)return'—';const s=String(v);const m=s.match(/^(\d{4})[-/](\d{1,2})[-/](\d{1,2})/);if(m)return m[1]+'-'+_pad(+m[2])+'-'+_pad(+m[3]);if(/^\d+$/.test(s)){const d=new Date(Number(s));if(!isNaN(d.getTime()))return _fmtDate(d)}const d2=new Date(v);return isNaN(d2.getTime())?s:_fmtDate(d2)}
const DEFAULT_PLANS=[
 {planId:1,planCode:'MONTHLY',planName:'月度订阅',priceCents:8888,durationDays:30,autoRenew:false,discountLabel:'',sort:1},
 {planId:2,planCode:'AUTO_MONTHLY',planName:'连续包月',priceCents:8299,durationDays:30,autoRenew:true,discountLabel:'省7%',sort:2},
 {planId:3,planCode:'QUARTERLY',planName:'季度订阅',priceCents:23999,durationDays:90,autoRenew:false,discountLabel:'省10%',sort:3},
 {planId:4,planCode:'ANNUAL',planName:'年度订阅',priceCents:88888,durationDays:365,autoRenew:false,discountLabel:'省17%',sort:4}];
async function loadPlans(){let list=[];try{const r=await subscriptionApi.plans();list=Array.isArray(r)?r:[]}catch(e){/* 拦截器已提示 */}if(!list.length)list=DEFAULT_PLANS;plans.value=list.slice().sort((a,b)=>(Number(a.sort)||0)-(Number(b.sort)||0))}
async function loadSub(){try{sub.value=await subscriptionApi.my()}catch(e){sub.value=null}}
// 价格为整数元（price_cents 存整数元，只加千分位，禁止除以 100）
function formatPrice(n){return n==null?'—':Number(n).toLocaleString('zh-CN')}
// ===== P1 支付流：卡片选择 → 支付确认弹窗（PaymentMethodSelector）→ 两段式下单 → 渠道分发 → 轮询支付结果 =====
const payOpen=ref(false);const payPlan=ref(null);const payChannel=ref('stripe');const paying=ref(false);let payTimer=null
function buy(p){if(!p||purchasing.value)return;payPlan.value=p;payChannel.value='stripe';payOpen.value=true}
function renew(){if(!sub.value)return;const p=plans.value.find(x=>String(x.planId)===String(sub.value.planId));buy(p||{planId:sub.value.planId,planName:sub.value.planName||'当前方案',durationDays:null,priceCents:null})}
function closePay(){payOpen.value=false}
// 渠道分发：wallet/mock 同步确认；alipay 表单跳转；wechat/stripe 拉起后轮询 /payment/query/{SUB单号}
async function confirmPay(){
  if(paying.value||!payPlan.value)return
  if(['term','corporate'].includes(payChannel.value)){message.warning('订阅暂不支持该支付方式，请选择外卡/支付宝/微信/钱包');return}
  paying.value=true
  try{
    const vo=await subscriptionApi.purchase(payPlan.value.planId,payChannel.value)
    if(!vo||!vo.orderNo)throw new Error('订阅单创建失败')
    if(vo.channel==='wallet'&&vo.payInfo?.paid){finishPay();return}
    if(vo.mock){await paymentApi.mockConfirm(vo.orderNo);finishPay();return}
    if(vo.payInfo?.form){const div=document.createElement('div');div.innerHTML=vo.payInfo.form;document.body.appendChild(div);document.forms[0]?.submit();payOpen.value=false;message.info('已跳转支付，完成后订阅自动生效');return}
    if(vo.payInfo?.codeUrl){message.success('请使用微信扫码完成支付')}
    else if(vo.payInfo?.clientSecret){message.info('支付预授权已创建，请完成支付')}
    payOpen.value=false
    startPolling(vo.orderNo)
  }catch(e){/* 拦截器已提示业务错误（余额不足/渠道未配置等） */}finally{paying.value=false}
}
// 轮询订阅单支付状态（SUB 单号走 /payment/query 分流分支），2.5s 一次、最长 4 分钟
function startPolling(orderNo){
  stopPolling()
  let ticks=0
  payTimer=setInterval(async()=>{
    ticks++
    if(ticks>96){stopPolling();message.info('支付确认中，完成后可在「订阅订单」查看结果');return}
    try{
      const st=await paymentApi.query(orderNo)
      if(st&&Number(st.payStatus)===1){finishPay();stopPolling()}
    }catch(e){/* 单次轮询失败忽略，下轮重试 */}
  },2500)
}
function stopPolling(){if(payTimer){clearInterval(payTimer);payTimer=null}}
async function finishPay(){
  stopPolling();closePay()
  message.success('订阅成功')
  await loadSub();membership.setSub(sub.value)
}
onUnmounted(stopPolling)
function cancelSub(){Modal.confirm({title:'取消订阅',content:'确认取消订阅？取消后到期将不再自动续费，权益保留至到期日。',okType:'danger',onOk:async()=>{try{await subscriptionApi.cancel();message.success('已撤销自动续费，到期后失效');await loadSub();membership.setSub(sub.value)}catch(e){/* 拦截器已提示 */}}})}
onMounted(async()=>{try{data.value=await membershipApi.my()}catch(e){}loadPlans();loadSub()})</script>
<style scoped>
.sys{max-width:1040px;margin:0 auto}.hero{display:flex;justify-content:space-between;gap:20px;align-items:flex-start;margin-bottom:20px}.eyebrow{font-size:11px;letter-spacing:.18em;color:var(--ll-primary)}.membership-eyebrow{display:flex;align-items:center;gap:9px}h1{margin:5px 0;font-size:30px}.hero p{margin:0;color:var(--ll-muted)}.actions{display:flex;gap:8px}.member{border-radius:22px;background:var(--ll-brand-hero-gradient);color:#fff;margin-bottom:16px;position:relative;overflow:hidden}.level{font-size:28px;font-weight:800}.meta,.hint{opacity:.72}.discount{position:absolute;right:26px;top:24px;text-align:right;font-size:24px;font-weight:800}.discount small{display:block;font-size:11px;font-weight:400;opacity:.65}.progress{height:6px;border-radius:99px;background:rgba(255,255,255,.14);margin:22px 0 8px;overflow:hidden}.progress div{height:100%;background:#f6d38a;border-radius:99px}.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin-bottom:16px}.nav-card,.levels{border-radius:18px}.nav-card{cursor:pointer;transition:transform .2s,box-shadow .2s}.nav-card:hover{transform:translateY(-3px);box-shadow:0 16px 40px rgba(19,35,58,.08)}.num{font-size:11px;color:#9b2d20;letter-spacing:.14em}.nav-card h3{margin:8px 0 4px}.nav-card p{color:var(--ll-muted);font-size:12px}.nav-card span{color:var(--ll-primary);font-size:12px}
/* VIP 订阅区块：玻璃子区自上而下（身份态条/档位卡/对比表在组件内），容器禁 overflow:hidden（day-glass 底部反光沿越界 10px 会被裁） */
.vip-subscribe{margin-bottom:16px}
.vip-cta-zone{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:14px 18px;margin-bottom:14px;border-radius:var(--ll-glass-day-radius);flex-wrap:wrap}
.trust-line{display:flex;align-items:center;gap:6px;margin:0;font-size:12px;color:var(--ll-slate-muted)}
.trust-line svg{color:var(--ll-primary)}
.vip-cta{min-width:180px;background:var(--ll-brand-gradient);border-color:transparent;color:var(--ll-vip-badge-ink);font-weight:700}
.vip-cta:not(:disabled):hover{filter:brightness(1.05);color:var(--ll-vip-badge-ink)}
.vip-cta:not(:disabled):active{filter:brightness(.94)}
@media(max-width:480px){.vip-cta{width:100%}}
/* P1 支付确认弹窗：档位摘要 + 渠道选择 + 提交 */
.pay-body{display:flex;flex-direction:column;gap:2px}
.pay-plan{display:flex;justify-content:space-between;align-items:center;padding:12px 14px;margin-bottom:12px;border-radius:12px;background:var(--ll-surface-inset)}
.pay-name{font-weight:700;color:var(--ll-ink)}
.pay-meta{font-size:12px;color:var(--ll-muted)}
.pay-price{font-size:20px;font-weight:800;color:var(--ll-primary);font-variant-numeric:tabular-nums}
.pay-hint{margin:10px 0 12px;font-size:12px;color:var(--ll-muted);line-height:1.6}
.sub-hint{margin:10px 0 0;font-size:12px;color:var(--ll-muted)}
/* 勋章墙：胶囊比例（260:54），自适应列宽，窄屏单列 */
.badge-wall{margin:4px 0 18px}.wall-head{display:flex;align-items:baseline;gap:10px;margin:0 0 10px}.wall-head h2{margin:0;font-size:17px;font-weight:800;color:var(--ll-ink)}.wall-head--sub{margin-top:16px}.wall-sub{font-size:12px;color:var(--ll-muted)}
.wall-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:14px 12px;align-items:start}
@media(max-width:620px){.wall-grid{grid-template-columns:1fr}}
/* 详情弹窗 */
.modal-title{font-weight:800}
.medal-detail{display:flex;flex-direction:column;gap:16px}
.md-rows{display:flex;flex-direction:column;gap:10px}
.md-row{display:flex;align-items:center;gap:12px;font-size:13px}
.md-row span{flex:0 0 64px;color:var(--ll-muted)}
.md-row b{font-weight:700;color:var(--ll-ink)}
.md-row b.md-on{color:var(--ll-primary)}
.md-row--col{flex-direction:column;align-items:stretch;gap:6px}
.md-row--col p{margin:0;color:var(--ll-gray,#4b5563);line-height:1.7;font-size:13px}
@media(max-width:800px){.grid{grid-template-columns:1fr 1fr}}@media(max-width:560px){.hero{flex-direction:column}.grid{grid-template-columns:1fr}.discount{position:static;text-align:left;margin-top:12px}}
</style>
