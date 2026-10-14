<template>
  <div class="promotion-page">
    <div class="hero"><div><div class="eyebrow">LANLINK PROMOTION</div><h1>优惠中心</h1><p>把当前生效的满减与折扣集中展示，结算时再由系统统一计算。</p></div><a-button @click="$router.push('/mall')">返回商城</a-button></div>
    <div class="summary"><span>当前生效</span><strong>{{promotions.length}}</strong><small>项营销规则</small></div>
    <a-row :gutter="[16,16]">
      <a-col v-for="p in promotions" :key="p.promoId" :xs="24" :sm="12" :lg="8">
        <a-card :bordered="false" class="promo-card">
          <div class="promo-head"><a-tag color="red">{{typeName(p)}}</a-tag><span>{{scopeName(p)}}</span></div>
          <h3><a @click="$router.push('/promotions/'+p.promoId)">{{p.title}}</a></h3>
          <div class="benefit">{{benefit(p)}}</div>
          <p v-if="p.threshold">门槛：满 ¥{{money(p.threshold)}} 可用</p>
          <p v-if="p.startTime || p.endTime">{{fmt(p.startTime)}} ～ {{p.endTime?fmt(p.endTime):'长期'}}</p>
          <div class="foot"><span>{{scopeDetail(p)}}</span><a-button type="link" @click="$router.push('/mall')">去选购 →</a-button></div>
        </a-card>
      </a-col>
    </a-row>
    <a-empty v-if="!loading && !promotions.length" description="暂时没有生效优惠">
      <template #description><span>暂无正在进行的优惠活动，逛逛商城看看精选商品。</span></template>
      <a-button type="primary" @click="$router.push('/mall')">去商城</a-button>
    </a-empty>
  </div>
</template>
<script setup>
import {ref,onMounted} from 'vue'
import {promotionApi} from '../api'
const promotions=ref([]),loading=ref(false)
const money=v=>Number(v||0).toFixed(2),fmt=v=>v?String(v).replace('T',' ').slice(0,16):''
const typeName=p=>p.type==='full_reduce'?'满减':'折扣'
const scopeName=p=>p.scope==='all'?'全场':'行业专享'
const benefit=p=>p.type==='full_reduce'?'减 ¥'+money(p.benefitAmount):(Number(p.discountRate||1)*10).toFixed(1)+' 折'
const scopeDetail=p=>p.scope==='all'?'全场商品适用':'指定行业商品适用'
onMounted(async()=>{loading.value=true;try{promotions.value=await promotionApi.list()||[]}finally{loading.value=false}})
</script>
<style scoped>
.promotion-page{max-width:1080px;margin:0 auto}.hero{display:flex;justify-content:space-between;align-items:flex-start;gap:20px;margin-bottom:22px}.eyebrow{font-size:11px;letter-spacing:.17em;color:var(--ll-primary)}h1{font-size:30px;margin:6px 0}.hero p{margin:0;color:var(--ll-muted)}.summary{display:inline-flex;align-items:baseline;gap:10px;padding:10px 14px;margin-bottom:16px;border-radius:12px;background:#fff;border:1px solid #ebe8e2}.summary strong{font-size:26px;color:#9b2d20}.summary small{color:var(--ll-muted)}.promo-card{height:100%;border-radius:18px;border:1px solid #ebe8e2;box-shadow:0 8px 24px rgba(19,35,58,.04)}.promo-head{display:flex;justify-content:space-between;align-items:center}.promo-head span{color:var(--ll-muted);font-size:12px}.promo-card h3{font-size:17px;margin:14px 0 8px}.promo-card h3 a{color:var(--ll-ink);cursor:pointer}.promo-card h3 a:hover{color:var(--ll-primary)}.benefit{font-size:28px;font-weight:800;color:#9b2d20}.promo-card p{font-size:12px;color:var(--ll-muted);margin:7px 0}.foot{display:flex;justify-content:space-between;align-items:center;margin-top:16px;padding-top:10px;border-top:1px solid #f0eee9}.foot span{font-size:11px;color:#98a2b3}@media(max-width:640px){.hero{flex-direction:column}}
</style>